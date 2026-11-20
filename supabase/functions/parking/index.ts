import postgres from "npm:postgres@3.4.5";
import {
  GIS, garagesNear, israelNow, loadData, mapsLink, parkingNearDestination, parseGarages, parseLocal, rules, snapshotGarages,
  stats, streetCost, TYPE_LABELS, zoneAt, type Event, type Garage,
} from "./tools.ts";

const sql = postgres(Deno.env.get("SUPABASE_DB_URL")!, { prepare: false, max: 3 });

/** Single-owner store: owner token + parking events in the private `parking` schema. */
const store = {
  async token(): Promise<string | null> {
    const r = await sql`select token from parking.owner limit 1`;
    return r[0]?.token ?? null;
  },
  async register(): Promise<string | null> {
    if (await store.token()) return null;
    const t = [...crypto.getRandomValues(new Uint8Array(24))].map((b) => b.toString(16).padStart(2, "0")).join("");
    await sql`insert into parking.owner (token) values (${t})`;
    return t;
  },
  async upsert(events: Event[]) {
    for (const e of events) {
      await sql`insert into parking.events (id, time, data) values (${e.id}, ${e.time}, ${sql.json(e as any)})
                on conflict (id) do update set time = excluded.time, data = excluded.data`;
    }
    return events.length;
  },
  async remove(id: string) { await sql`delete from parking.events where id = ${id}`; },
  async events(limit = 5000): Promise<Event[]> {
    const r = await sql`select data from parking.events order by time desc limit ${limit}`;
    return r.map((x: any) => x.data as Event);
  },
};
type Store = typeof store;

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", "access-control-allow-origin": "*" } });

async function liveGarages(): Promise<{ garages: Garage[]; live: boolean }> {
  try {
    const q = new URLSearchParams({ where: "1=1", outFields: "shem_chenyon,ktovet,lat,lon,status_chenyon,taarif_yom,taarif_layla,hearot_taarif", returnGeometry: "false", f: "json" });
    const r = await fetch(`${GIS}/970/query?${q}`, { signal: AbortSignal.timeout(8000) });
    const b = (await r.json()) as { features?: { attributes: Record<string, unknown> }[] };
    if (b.features?.length) return { garages: parseGarages(b.features), live: true };
  } catch {}
  return { garages: snapshotGarages(), live: false };
}

/** Tel Aviv address -> coordinates via the city's own address layer (527). "דיזנגוף 50" */
async function geocode(address: string): Promise<{ lat: number; lon: number; label: string } | null> {
  const m = address.trim().replace(/,?\s*תל[ -]אביב.*$/, "").match(/^(.+?)\s+(\d+)/);
  if (!m) return null;
  const [, street, num] = m;
  const q = new URLSearchParams({
    where: `t_rechov LIKE '%${street.replace(/'/g, "''")}%' AND ms_bayit = ${Number(num)}`,
    outFields: "t_ktovet_melea,lat,lon", returnGeometry: "false", f: "json", resultRecordCount: "5",
  });
  try {
    const r = await fetch(`${GIS}/527/query?${q}`, { signal: AbortSignal.timeout(8000) });
    const b = (await r.json()) as { features?: { attributes: { t_ktovet_melea: string; lat: number; lon: number } }[] };
    const a = b.features?.[0]?.attributes;
    return a ? { lat: a.lat, lon: a.lon, label: a.t_ktovet_melea.trim() } : null;
  } catch { return null; }
}

// ---------- MCP (stateless Streamable HTTP, JSON responses) ----------
const num = { type: "number" } as const;
const str = { type: "string" } as const;
const TOOLS = [
  { name: "parking_near_destination", description: "Best parking options near a Tel Aviv destination for the owner (zone 9 permit holder): street rules and cost, nearby garages with live occupancy, and the owner's own history near the destination. Give either address (Hebrew street + number, e.g. 'דיזנגוף 50') or lat/lon.", inputSchema: { type: "object", properties: { address: str, lat: num, lon: num, arrival: { type: "string", description: "Israel local time, e.g. 2026-11-20T18:30. Default now." }, minutes: { type: "number", description: "Planned stay, default 120" } } } },
  { name: "where_is_my_car", description: "The owner's last saved parking spot with walking navigation link.", inputSchema: { type: "object", properties: {} } },
  { name: "parking_history", description: "The owner's parking history (newest first): place, zone, street, parking type, search time.", inputSchema: { type: "object", properties: { limit: num } } },
  { name: "parking_stats", description: "Statistics from the owner's history: parkings by type, streets where a spot was found fastest, search time by time of day. Optionally around a point.", inputSchema: { type: "object", properties: { lat: num, lon: num, radiusMeters: num } } },
  { name: "garages_near", description: "Ahuzot HaHof garages near a point, with live occupancy and tariffs (full/closed excluded).", inputSchema: { type: "object", properties: { lat: num, lon: num, radiusMeters: num }, required: ["lat", "lon"] } },
  { name: "zone_at", description: "Tel Aviv parking zone at a point and whether it is the owner's permit zone.", inputSchema: { type: "object", properties: { lat: num, lon: num }, required: ["lat", "lon"] } },
  { name: "street_parking_cost", description: "Blue-white street parking cost at a point for the owner. costILS null = rate not verified; never invent one.", inputSchema: { type: "object", properties: { lat: num, lon: num, start: str, minutes: num, freeMinutesUsedToday: num }, required: ["lat", "lon"] } },
  { name: "geocode", description: "Convert a Tel Aviv address (Hebrew street + house number) to coordinates using the city's address data.", inputSchema: { type: "object", properties: { address: str }, required: ["address"] } },
];

async function callTool(name: string, a: Record<string, any>, store: Store): Promise<unknown> {
  switch (name) {
    case "geocode": return (await geocode(a.address)) ?? { error: "Address not found in Tel Aviv address data" };
    case "zone_at": { const zone = zoneAt(a.lat, a.lon); return { zone, isPermitZone: zone === rules.profile.permitZone }; }
    case "street_parking_cost": return streetCost(a.lat, a.lon, parseLocal(a.start), a.minutes ?? 120, a.freeMinutesUsedToday ?? 0);
    case "garages_near": { const g = await liveGarages(); return { live: g.live, garages: garagesNear(g.garages, a.lat, a.lon, a.radiusMeters ?? 700) }; }
    case "where_is_my_car": {
      const e = (await store.events(1))[0];
      if (!e) return { error: "No saved parking spot" };
      return { ...e, typeLabel: e.type ? TYPE_LABELS[e.type] : null, savedAtIsrael: israelNow(new Date(e.time)).toISOString().slice(0, 16), walk: mapsLink(e.lat, e.lon, "walking") };
    }
    case "parking_history": return (await store.events(a.limit ?? 50)).map((e) => ({ ...e, typeLabel: e.type ? TYPE_LABELS[e.type] : null, timeIsrael: israelNow(new Date(e.time)).toISOString().slice(0, 16) }));
    case "parking_stats": return stats(await store.events(), a.lat != null && a.lon != null ? { lat: a.lat, lon: a.lon, radius: a.radiusMeters ?? 500 } : undefined);
    case "parking_near_destination": {
      let dest: { lat: number; lon: number; label?: string } | null = a.lat != null && a.lon != null ? { lat: a.lat, lon: a.lon, label: a.address } : null;
      if (!dest && a.address) dest = await geocode(a.address);
      if (!dest) return { error: "Destination not found. Give a Tel Aviv street and house number in Hebrew, or lat/lon." };
      const [{ garages, live }, events] = await Promise.all([liveGarages(), store.events()]);
      return { liveOccupancy: live, ...parkingNearDestination(dest, parseLocal(a.arrival), a.minutes ?? 120, garages, events) };
    }
    default: throw new Error(`Unknown tool ${name}`);
  }
}

async function mcp(req: Request, store: Store): Promise<Response> {
  if (req.method !== "POST") return new Response("Method not allowed", { status: 405 });
  const msg = (await req.json()) as { id?: number | string; method: string; params?: any };
  if (msg.id === undefined) return new Response(null, { status: 202 }); // notification
  const reply = (result: unknown) => json({ jsonrpc: "2.0", id: msg.id, result });
  switch (msg.method) {
    case "initialize":
      return reply({ protocolVersion: msg.params?.protocolVersion ?? "2025-03-26", capabilities: { tools: {} }, serverInfo: { name: "parking-tlv", version: "0.3.0" } });
    case "ping": return reply({});
    case "tools/list": return reply({ tools: TOOLS });
    case "tools/call":
      try {
        const out = await callTool(msg.params.name, msg.params.arguments ?? {}, store);
        return reply({ content: [{ type: "text", text: JSON.stringify(out, null, 1) }] });
      } catch (e) {
        return reply({ content: [{ type: "text", text: String(e) }], isError: true });
      }
    default:
      return json({ jsonrpc: "2.0", id: msg.id, error: { code: -32601, message: "Method not found" } });
  }
}

Deno.serve(async (req: Request): Promise<Response> => {
  // Path after the function name: /functions/v1/parking/<path>
  const url = new URL(req.url);
  const path = url.pathname.replace(/^.*?\/parking/, "") || "/";
  if (req.method === "OPTIONS") return new Response(null, { headers: { "access-control-allow-origin": "*", "access-control-allow-headers": "authorization, content-type, mcp-session-id, mcp-protocol-version", "access-control-allow-methods": "GET, POST, DELETE, OPTIONS" } });
  if (path === "/") return json({ name: "parking-tlv", mcp: `${url.origin}/functions/v1/parking/mcp` });
  if (path === "/api/register" && req.method === "POST") {
    const t = await store.register();
    return t ? json({ token: t }) : json({ error: "Already registered" }, 409);
  }
  const token = await store.token();
  const given = req.headers.get("authorization")?.replace(/^Bearer\s+/i, "") ?? url.searchParams.get("key");
  if (!token || given !== token) return json({ error: "Unauthorized" }, 401);

  await loadData();
  if (path === "/mcp") return mcp(req, store);
  if (path === "/api/events" && req.method === "POST") {
    const body = (await req.json()) as { events: Event[] };
    return json({ saved: await store.upsert(body.events ?? []) });
  }
  if (path === "/api/events" && req.method === "GET") return json(await store.events());
  const del = path.match(/^\/api\/events\/([\w-]+)$/);
  if (del && req.method === "DELETE") { await store.remove(del[1]); return json({ ok: true }); }
  return json({ error: "Not found" }, 404);
});
