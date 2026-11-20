// Parking logic for the Supabase Edge Function. City GIS layers are loaded at runtime and cached.
import rulesJson from "./rules_config.ts";
import { haversineMeters, pointInRings, type Ring } from "./geo.ts";
import { evaluateStreetParking, groupFor, type RulesConfig } from "./rules.ts";

export const GIS = "https://gisn.tel-aviv.gov.il/arcgis/rest/services/IView2/MapServer";
export const rules = rulesJson as unknown as RulesConfig;

type Attrs = Record<string, any>;
let zones: { attributes: Attrs; geometry: { rings: Ring[] } }[] = [];
let nightLots: { attributes: Attrs; geometry: { x: number; y: number } }[] = [];
let loadedAt = 0;

async function layer(id: number) {
  const q = new URLSearchParams({ where: "1=1", outFields: "*", outSR: "4326", returnGeometry: "true", f: "json" });
  const r = await fetch(`${GIS}/${id}/query?${q}`, { signal: AbortSignal.timeout(15000) });
  return ((await r.json()) as { features?: any[] }).features ?? [];
}

/** Zones (544) and close-to-home lots (488) from the city, refreshed every 12 hours. */
export async function loadData() {
  if (zones.length && Date.now() - loadedAt < 12 * 3600_000) return;
  const [z, n] = await Promise.all([layer(544), layer(488)]);
  if (z.length) zones = z;
  if (n.length) nightLots = n.filter((f: any) => f.geometry);
  loadedAt = Date.now();
}

export interface Event {
  id: string; lat: number; lon: number; time: number; source: string;
  zone: number | null; street: string | null; type: string | null; search: string | null;
}

export const TYPE_LABELS: Record<string, string> = {
  blue_white: "כחול-לבן", permit_only: "לבעלי תו אזורי", unmarked: "ללא סימון (חינם)", garage: "חניון", close_to_home: "חניון קרוב לבית",
  short_term: "קצרת מועד", private: "חניה פרטית", other: "אחר",
};
const SEARCH_MIN: Record<string, number> = { "0": 0, "5": 3, "15": 10, "30": 20 };

/** Israel wall-clock time as a Date whose UTC-environment local fields equal Israel time (Workers run in UTC). */
export function israelNow(now = new Date()): Date {
  const p = Object.fromEntries(
    new Intl.DateTimeFormat("en-GB", { timeZone: "Asia/Jerusalem", hourCycle: "h23", year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" })
      .formatToParts(now).map((x) => [x.type, x.value]),
  );
  return new Date(`${p.year}-${p.month}-${p.day}T${p.hour}:${p.minute}:00`);
}

/** "2026-11-20T18:30" (no offset) is taken as Israel local time. */
export function parseLocal(s?: string): Date {
  if (!s) return israelNow();
  const d = new Date(s.replace(/(Z|[+-]\d\d:\d\d)$/, ""));
  return isNaN(d.getTime()) ? israelNow() : d;
}

export function zoneAt(lat: number, lon: number): number | null {
  for (const z of zones) if (pointInRings(lon, lat, z.geometry.rings)) return Number(z.attributes.ms_ezor);
  return null;
}

export const mapsLink = (lat: number, lon: number, mode: "driving" | "walking" = "driving") =>
  `https://www.google.com/maps/dir/?api=1&destination=${lat},${lon}&travelmode=${mode}`;

export function streetCost(lat: number, lon: number, start: Date, minutes: number, used = 0) {
  const zone = zoneAt(lat, lon);
  const e = evaluateStreetParking(rules, zone, start, minutes, used);
  return { zone, isPermitZone: zone !== null && zone === rules.profile.permitZone, zoneGroup: groupFor(rules, zone)?.name ?? null, ...e };
}

export interface Garage { name: string; address: string; lat: number; lon: number; status: string; dayTariff: string; nightTariff: string; notes: string; }

export function parseGarages(features: { attributes: Attrs }[]): Garage[] {
  return features
    .map(({ attributes: a }) => ({
      name: String(a.shem_chenyon ?? "").trim(), address: String(a.ktovet ?? "").trim(), lat: Number(a.lat), lon: Number(a.lon),
      status: String(a.status_chenyon ?? "").trim(), dayTariff: String(a.taarif_yom ?? "").trim(),
      nightTariff: String(a.taarif_layla ?? "").trim(), notes: String(a.hearot_taarif ?? "").trim(),
    }))
    .filter((g) => Number.isFinite(g.lat) && Number.isFinite(g.lon) && !/נסגר/.test(g.dayTariff + g.notes));
}

export const snapshotGarages = (): Garage[] => [];

const STATUS: Record<string, string> = { "פנוי": "יש מקום", "מעט": "מעט מקום", "מלא": "מלא", "סגור": "סגור", "פעיל": "פתוח, אין נתוני תפוסה" };

export function garagesNear(all: Garage[], lat: number, lon: number, radius = 700, limit = 8) {
  const nightByName = new Map(nightLots.map((n) => [String(n.attributes.shem).trim(), n.attributes]));
  return all
    .map((g) => {
      const night = nightByName.get(g.name);
      const discount = /(\d{2})%/.exec(`${night?.hearot_atar ?? ""} ${g.notes}`)?.[1];
      return {
        name: g.name, address: g.address, distanceMeters: Math.round(haversineMeters(lat, lon, g.lat, g.lon)),
        occupancy: STATUS[g.status] ?? "אין מידע", dayTariff: g.dayTariff, nightTariff: g.nightTariff, notes: g.notes,
        closeToHome: !!night, residentDiscountPercent: discount ? Number(discount) : null,
        lat: g.lat, lon: g.lon, navigate: mapsLink(g.lat, g.lon),
      };
    })
    .filter((g) => g.distanceMeters <= radius && g.occupancy !== "מלא" && g.occupancy !== "סגור")
    .sort((a, b) => a.distanceMeters - b.distanceMeters)
    .slice(0, limit);
}

export function stats(events: Event[], near?: { lat: number; lon: number; radius: number }) {
  const list = near ? events.filter((e) => haversineMeters(near.lat, near.lon, e.lat, e.lon) <= near.radius) : events;
  const searched = list.filter((e) => e.search !== null && e.search in SEARCH_MIN);
  const avg = (l: Event[]) => (l.length ? Math.round(l.reduce((s, e) => s + SEARCH_MIN[e.search!], 0) / l.length) : null);
  const bucket = (t: number) => {
    const h = israelNow(new Date(t)).getHours();
    return h >= 6 && h < 12 ? "בוקר" : h < 17 && h >= 12 ? "צהריים" : h >= 17 && h < 21 ? "ערב" : "לילה";
  };
  const byType: Record<string, number> = {};
  for (const e of list) if (e.type) byType[TYPE_LABELS[e.type] ?? e.type] = (byType[TYPE_LABELS[e.type] ?? e.type] ?? 0) + 1;
  const streets = new Map<string, Event[]>();
  for (const e of searched) if (e.street) streets.set(e.street, [...(streets.get(e.street) ?? []), e]);
  const buckets = new Map<string, Event[]>();
  for (const e of searched) buckets.set(bucket(e.time), [...(buckets.get(bucket(e.time)) ?? []), e]);
  return {
    parkings: list.length,
    averageSearchMinutes: avg(searched),
    byType,
    fastestStreets: [...streets.entries()].map(([street, l]) => ({ street, parkings: l.length, averageSearchMinutes: avg(l) }))
      .sort((a, b) => a.averageSearchMinutes! - b.averageSearchMinutes! || b.parkings - a.parkings).slice(0, 8),
    byTimeOfDay: Object.fromEntries([...buckets.entries()].map(([b, l]) => [b, { parkings: l.length, averageSearchMinutes: avg(l) }])),
  };
}

/** Main agent tool: everything needed to pick where to park near a destination. */
export function parkingNearDestination(dest: { lat: number; lon: number; label?: string }, arrival: Date, minutes: number, garages: Garage[], events: Event[]) {
  const street = streetCost(dest.lat, dest.lon, arrival, minutes);
  const nearGarages = garagesNear(garages, dest.lat, dest.lon, 800, 6);
  const history = stats(events, { lat: dest.lat, lon: dest.lon, radius: 500 });
  const options: { rank: number; kind: string; summary: string; costILS: number | null; navigate: string }[] = [];
  options.push({
    rank: 0,
    kind: "street",
    summary: street.isPermitZone ? `כחול-לבן באזור ${street.zone} (האזור שלך): חינם` :
      street.zone === null ? "מחוץ לאזורי החניה של העירייה" :
      `כחול-לבן באזור ${street.zone}: ${street.freeMinutes} דקות חינם, ${street.paidMinutes} בתשלום`,
    costILS: street.costILS,
    navigate: mapsLink(dest.lat, dest.lon),
  });
  for (const g of nearGarages) {
    options.push({
      rank: 0, kind: "garage",
      summary: `${g.name} (${g.distanceMeters} מ׳ מהיעד, ${g.occupancy})${g.residentDiscountPercent ? `, ${g.residentDiscountPercent}% הנחת תושב` : ""}${g.closeToHome ? ", קרוב לבית" : ""}`,
      costILS: null, navigate: g.navigate,
    });
  }
  // Free street first, then cheap street, then garages by distance (garage prices are free text, never guessed).
  options.forEach((o, i) => (o.rank = o.kind === "street" && o.costILS === 0 ? 0 : o.kind === "street" && o.costILS !== null ? 1 : 2 + i));
  options.sort((a, b) => a.rank - b.rank);
  return { destination: dest, arrival: arrival.toISOString().slice(0, 16), minutes, street, garages: nearGarages, yourHistoryNearby: history, options };
}
