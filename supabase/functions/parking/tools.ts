// Parking logic for the Supabase Edge Function. City GIS layers are loaded at runtime and cached.
// Pure functions take data explicitly where practical so they can be unit-tested with snapshots.
import rulesJson from "./rules_config.ts";
import { distanceToRingsMeters, haversineMeters, pointInRings, type Ring } from "./geo.ts";
import { closeToHomeFree, evaluateStreetParking, groupFor, type RulesConfig } from "./rules.ts";

export const GIS = "https://gisn.tel-aviv.gov.il/arcgis/rest/services/IView2/MapServer";
export const rules = rulesJson as unknown as RulesConfig;

type Attrs = Record<string, any>;
type PointFeature = { attributes: Attrs; geometry: { x: number; y: number } };

export interface CityData {
  zones: { attributes: Attrs; geometry: { rings: Ring[] } }[];
  nightLots: PointFeature[];
  publicLots: PointFeature[];
  loadingSigns: PointFeature[];
  autotel: { attributes: Attrs }[];
}

export const data: CityData = { zones: [], nightLots: [], publicLots: [], loadingSigns: [], autotel: [] };
let loadedAt = 0;

export function setData(d: Partial<CityData>) { Object.assign(data, d); loadedAt = Date.now(); }

async function layer(id: number, fields = "*") {
  const q = new URLSearchParams({ where: "1=1", outFields: fields, outSR: "4326", returnGeometry: "true", f: "json" });
  const r = await fetch(`${GIS}/${id}/query?${q}`, { signal: AbortSignal.timeout(15000) });
  return ((await r.json()) as { features?: any[] }).features ?? [];
}

/** City layers, refreshed every 12 hours. Failed layers keep their previous value. */
export async function loadData() {
  if (data.zones.length && Date.now() - loadedAt < 12 * 3600_000) return;
  const [zones, nightLots, publicLots, loadingSigns, autotel] = await Promise.all([
    layer(544), layer(488),
    layer(556, "name,address,num_vehicles,description"),
    layer(449, "traffic_sign_text,status"),
    layer(602, "address0,capacity,lat,lon"),
  ].map((p) => p.catch(() => [])));
  const pts = (l: any[]) => l.filter((f) => f.geometry);
  setData({
    zones: zones.length ? zones : data.zones,
    nightLots: nightLots.length ? pts(nightLots) : data.nightLots,
    publicLots: publicLots.length ? pts(publicLots) : data.publicLots,
    loadingSigns: loadingSigns.length ? pts(loadingSigns) : data.loadingSigns,
    autotel: autotel.length ? autotel : data.autotel,
  });
}

export interface Event {
  id: string; lat: number; lon: number; time: number; source: string;
  zone: number | null; street: string | null; type: string | null; search: string | null;
}
export interface Curb { id: string; lat: number; lon: number; type: string; street: string | null; time: number; source: string }

export const TYPE_LABELS: Record<string, string> = {
  blue_white: "כחול-לבן", permit_only: "לבעלי תו אזורי", unmarked: "ללא סימון (חינם)", garage: "חניון",
  close_to_home: "חניון קרוב לבית", short_term: "קצרת מועד", private: "חניה פרטית", other: "אחר",
};
export const CURB_LABELS: Record<string, string> = {
  blue_white: "כחול-לבן", preferred: "מועדפת לתושבי האזור", gray: "אפור / ללא סימון", red_white: "אדום-לבן (אסור)",
  short_term: "קצרת מועד", loading: "פריקה וטעינה", disabled: "נכים", bus_taxi: "תחנת אוטובוס / מונית", driveway: "כניסה לחניה / חסום",
};
const SEARCH_MIN: Record<string, number> = { "0": 0, "5": 3, "15": 10, "30": 20 };

/** Israel wall-clock time as a Date whose UTC-environment local fields equal Israel time. */
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
  for (const z of data.zones) if (pointInRings(lon, lat, z.geometry.rings)) return Number(z.attributes.ms_ezor);
  return null;
}

/** Outside the permit zone but within boundaryMeters of its edge: a boundary street. */
export function onPermitBoundary(lat: number, lon: number): boolean {
  const z = data.zones.find((f) => Number(f.attributes.ms_ezor) === rules.profile.permitZone);
  if (!z || pointInRings(lon, lat, z.geometry.rings)) return false;
  return distanceToRingsMeters(lat, lon, z.geometry.rings) <= (rules.boundaryMeters ?? 25);
}

export const mapsLink = (lat: number, lon: number, mode: "driving" | "walking" = "driving") =>
  `https://www.google.com/maps/dir/?api=1&destination=${lat},${lon}&travelmode=${mode}`;

export function streetCost(lat: number, lon: number, start: Date, minutes: number, used = 0) {
  const zone = zoneAt(lat, lon);
  const boundary = onPermitBoundary(lat, lon);
  const e = evaluateStreetParking(rules, zone, start, minutes, used, boundary);
  return { zone, isPermitZone: zone !== null && zone === rules.profile.permitZone, onPermitZoneBoundary: boundary, zoneGroup: groupFor(rules, zone)?.name ?? null, ...e };
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

const STATUS: Record<string, string> = { "פנוי": "יש מקום", "מעט": "מעט מקום", "מלא": "מלא", "סגור": "סגור", "פעיל": "פתוח, אין נתוני תפוסה" };

export function garagesNear(all: Garage[], lat: number, lon: number, radius = 700, limit = 8, assigned: string[] = [], at = israelNow()) {
  const nightByName = new Map(data.nightLots.map((n) => [String(n.attributes.shem).trim(), n.attributes]));
  return all
    .map((g) => {
      const night = nightByName.get(g.name);
      const discount = /(\d{2})%/.exec(`${night?.hearot_atar ?? ""} ${g.notes}`)?.[1];
      const mine = !!night && assigned.includes(g.name);
      return {
        name: g.name, address: g.address, distanceMeters: Math.round(haversineMeters(lat, lon, g.lat, g.lon)),
        occupancy: STATUS[g.status] ?? "אין מידע", dayTariff: g.dayTariff, nightTariff: g.nightTariff, notes: g.notes,
        closeToHome: !!night, assignedToOwner: mine, freeForOwnerAtTime: mine ? closeToHomeFree(at, rules.holidays) : null,
        residentDiscountPercent: discount ? Number(discount) : null,
        lat: g.lat, lon: g.lon, navigate: mapsLink(g.lat, g.lon),
      };
    })
    .filter((g) => g.distanceMeters <= radius && g.occupancy !== "מלא" && g.occupancy !== "סגור")
    .sort((a, b) => a.distanceMeters - b.distanceMeters)
    .slice(0, limit);
}

export function otherLotsNear(lat: number, lon: number, radius = 700) {
  return data.publicLots
    .map((f) => ({
      name: String(f.attributes.name ?? f.attributes.address ?? "").trim(), address: String(f.attributes.address ?? "").trim(),
      spots: Number(f.attributes.num_vehicles) || null, description: String(f.attributes.description ?? "").trim(),
      distanceMeters: Math.round(haversineMeters(lat, lon, f.geometry.y, f.geometry.x)), navigate: mapsLink(f.geometry.y, f.geometry.x),
    }))
    .filter((l) => l.distanceMeters <= radius)
    .sort((a, b) => a.distanceMeters - b.distanceMeters)
    .slice(0, 8);
}

export function loadingSignsNear(lat: number, lon: number, radius = 80) {
  return data.loadingSigns
    .filter((f) => !f.attributes.status || f.attributes.status === "קיים")
    .map((f) => ({
      distanceMeters: Math.round(haversineMeters(lat, lon, f.geometry.y, f.geometry.x)),
      text: String(f.attributes.traffic_sign_text ?? "").replace(/\r/g, "").split("\n").filter((x) => x.trim()).join(" · "),
    }))
    .filter((s) => s.distanceMeters <= radius)
    .sort((a, b) => a.distanceMeters - b.distanceMeters);
}

export function autotelNear(lat: number, lon: number, radius = 60) {
  return data.autotel
    .map((f) => ({ address: String(f.attributes.address0 ?? ""), distanceMeters: Math.round(haversineMeters(lat, lon, Number(f.attributes.lat), Number(f.attributes.lon))) }))
    .filter((a) => a.distanceMeters <= radius);
}

/** The owner's curb map near a point: explicit curb marks plus curb types implied by classified parkings. */
export function curbMapNear(curbs: Curb[], events: Event[], lat: number, lon: number, radius = 300) {
  const fromParking: Record<string, string> = { blue_white: "blue_white", permit_only: "preferred", unmarked: "gray", short_term: "short_term" };
  const marks = [
    ...curbs.map((c) => ({ ...c, kind: c.type, from: "curb-mark" })),
    ...events.filter((e) => e.type && fromParking[e.type]).map((e) => ({ id: e.id, lat: e.lat, lon: e.lon, type: fromParking[e.type!], street: e.street, time: e.time, source: "parking", kind: fromParking[e.type!], from: "parking" })),
  ].map((m) => ({ ...m, distanceMeters: Math.round(haversineMeters(lat, lon, m.lat, m.lon)) })).filter((m) => m.distanceMeters <= radius);
  const byStreet: Record<string, Record<string, number>> = {};
  for (const m of marks) {
    const s = m.street ?? "לא ידוע";
    byStreet[s] ??= {};
    byStreet[s][CURB_LABELS[m.kind] ?? m.kind] = (byStreet[s][CURB_LABELS[m.kind] ?? m.kind] ?? 0) + 1;
  }
  return {
    marks: marks.sort((a, b) => a.distanceMeters - b.distanceMeters).slice(0, 60)
      .map((m) => ({ type: CURB_LABELS[m.kind] ?? m.kind, street: m.street, distanceMeters: m.distanceMeters, from: m.from, lat: m.lat, lon: m.lon })),
    byStreet,
  };
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
export function parkingNearDestination(
  dest: { lat: number; lon: number; label?: string }, arrival: Date, minutes: number,
  garages: Garage[], events: Event[], curbs: Curb[], assigned: string[] = [],
) {
  const street = streetCost(dest.lat, dest.lon, arrival, minutes);
  const nearGarages = garagesNear(garages, dest.lat, dest.lon, 800, 6, assigned, arrival);
  const options: { rank: number; kind: string; summary: string; costILS: number | null; appRequired: boolean; navigate: string }[] = [];
  options.push({
    rank: 0, kind: "street",
    summary: street.isPermitZone ? `כחול-לבן באזור ${street.zone} (האזור שלך): חינם, בלי אפליקציה`
      : street.onPermitZoneBoundary ? `רחוב גובל של אזור ${rules.profile.permitZone}: חינם בהפעלת פנגו מהדקה הראשונה`
      : street.zone === null ? "מחוץ לאזורי החניה של העירייה"
      : `כחול-לבן באזור ${street.zone}: ${street.freeMinutes} דקות חינם, ${street.paidMinutes} בתשלום${street.appRequired ? " (בהפעלת פנגו)" : ""}`,
    costILS: street.costILS, appRequired: street.appRequired, navigate: mapsLink(dest.lat, dest.lon),
  });
  for (const g of nearGarages) {
    options.push({
      rank: 0, kind: g.freeForOwnerAtTime ? "assigned-close-to-home" : "garage",
      summary: `${g.name} (${g.distanceMeters} מ׳ מהיעד, ${g.occupancy})${g.freeForOwnerAtTime ? ", משויך אליך וחינם בשעה הזו" : ""}${g.residentDiscountPercent ? `, ${g.residentDiscountPercent}% הנחת תושב` : ""}`,
      costILS: g.freeForOwnerAtTime ? 0 : null, appRequired: false, navigate: g.navigate,
    });
  }
  // Free street, free assigned lot, cheap street, then garages by distance (garage prices are free text, never guessed).
  options.forEach((o, i) => (o.rank = o.costILS === 0 ? (o.kind === "street" ? 0 : 1) : o.kind === "street" && o.costILS !== null ? 2 : 3 + i));
  options.sort((a, b) => a.rank - b.rank);
  return {
    destination: dest, arrival: `${arrival.getFullYear()}-${String(arrival.getMonth() + 1).padStart(2, "0")}-${String(arrival.getDate()).padStart(2, "0")}T${String(arrival.getHours()).padStart(2, "0")}:${String(arrival.getMinutes()).padStart(2, "0")}`,
    minutes, street, options, garages: nearGarages,
    otherLots: otherLotsNear(dest.lat, dest.lon, 600),
    loadingSignsNearby: loadingSignsNear(dest.lat, dest.lon, 60),
    autotelReservedNearby: autotelNear(dest.lat, dest.lon, 60),
    yourCurbMarksNearby: curbMapNear(curbs, events, dest.lat, dest.lon, 250).byStreet,
    yourHistoryNearby: stats(events, { lat: dest.lat, lon: dest.lon, radius: 500 }),
    reminders: [
      "60-minute short-term spots are free once a day, but must be started in a parking app",
      "Preferred-parking segments are for that zone's permit holders only: check the sign",
    ],
  };
}
