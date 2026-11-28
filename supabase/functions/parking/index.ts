import postgres from "npm:postgres@3.4.5";
import {
  GIS, autotelNear, curbMapNear, data, dataSource, garagesNear, israelNow, loadData, loadingSignsNear, mapsLink, otherLotsNear, parkingNearDestination,
  parseGarages, parseLocal, rules, stats, streetCost, TYPE_LABELS, zoneAt, type Curb, type Event, type Garage,
} from "./tools.ts";
import { fetchNotices } from "./notices.ts";
import { TOOLS } from "./catalog.ts";
import { freshNews, guide, HELLO, LINK_NAME, LINK_TITLE, linkPayload, newCode, newToken, weeklyText } from "./link.ts";

const sql = postgres(Deno.env.get("SUPABASE_DB_URL")!, { prepare: false, max: 3 });

/** Single-owner store in the private `parking` schema. */
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
  async saveCurbs(curbs: Curb[], deleted: string[]) {
    for (const c of curbs) {
      await sql`insert into parking.curbs (id, time, data) values (${c.id}, ${c.time}, ${sql.json(c as any)})
                on conflict (id) do update set time = excluded.time, data = excluded.data`;
    }
    if (deleted.length) await sql`delete from parking.curbs where id = any(${deleted})`;
    return curbs.length;
  },
  async curbs(): Promise<Curb[]> {
    const r = await sql`select data from parking.curbs order by time desc limit 20000`;
    return r.map((x: any) => x.data as Curb);
  },
  async setting<T>(key: string, fallback: T): Promise<T> {
    const r = await sql`select value from parking.settings where key = ${key}`;
    return (r[0]?.value as T) ?? fallback;
  },
  async setSetting(key: string, value: unknown) {
    await sql`insert into parking.settings (key, value) values (${key}, ${sql.json(value as any)})
              on conflict (key) do update set value = excluded.value`;
  },
  async deleteSetting(key: string) { await sql`delete from parking.settings where key = ${key}`; },
  /** A linked agent host (Morty) by its token; marks it seen. */
  async agent(token: string): Promise<boolean> {
    const r = await sql`update parking.agents set last_seen = now() where token = ${token} returning name`;
    return r.length > 0;
  },
  async agents(): Promise<{ name: string; created: Date; last_seen: Date | null }[]> {
    return (await sql`select name, created, last_seen from parking.agents order by created desc`) as any;
  },
  /** One token per agent host: relinking Morty replaces Morty's old token, other agents keep theirs. */
  async replaceAgent(name: string, token: string) {
    await sql`delete from parking.agents where name = ${name}`;
    await sql`insert into parking.agents (token, name) values (${token}, ${name})`;
  },
  /** Removes one agent host, or all of them; returns how many are left. */
  async removeAgents(name?: string): Promise<number> {
    if (name) await sql`delete from parking.agents where name = ${name}`;
    else await sql`delete from parking.agents`;
    return Number((await sql`select count(*) as n from parking.agents`)[0].n);
  },
  async queue(kind: string, risk: string, text: string): Promise<boolean> {
    if (!(await sql`select 1 from parking.agents limit 1`).length) return false;
    await sql`insert into parking.notifications (kind, risk, text) values (${kind}, ${risk}, ${text.slice(0, 1500)})`;
    await sql`delete from parking.notifications where time < now() - interval '14 days'`;
    return true;
  },
  async feed(after: number) {
    return await sql`select id, time, kind, risk, text from parking.notifications where id > ${after} order by id limit 50`;
  },
  async lastNotification(): Promise<number> {
    const r = await sql`select coalesce(max(id), 0) as id from parking.notifications`;
    return Number(r[0].id);
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
  } catch { /* fall through */ }
  return { garages: [], live: false };
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

async function callTool(name: string, a: Record<string, any>, store: Store): Promise<unknown> {
  const radius = (d: number) => a.radiusMeters ?? d;
  switch (name) {
    case "geocode": return (await geocode(a.address)) ?? { error: "Address not found in Tel Aviv address data" };
    case "parking_notices": return await fetchNotices();
    case "zone_at": { const s = streetCost(a.lat, a.lon, israelNow(), 1); return { zone: s.zone, isPermitZone: s.isPermitZone, onPermitZoneBoundary: s.onPermitZoneBoundary }; }
    case "street_parking_cost": return streetCost(a.lat, a.lon, parseLocal(a.start), a.minutes ?? 120, a.freeMinutesUsedToday ?? 0);
    case "garages_near": {
      const [g, assigned] = await Promise.all([liveGarages(), store.setting<string[]>("assignedLots", [])]);
      return { live: g.live, garages: garagesNear(g.garages, a.lat, a.lon, radius(700), 8, assigned) };
    }
    case "other_lots_near": return otherLotsNear(a.lat, a.lon, radius(700));
    case "loading_signs_near": return loadingSignsNear(a.lat, a.lon, radius(100));
    case "curb_map_near": {
      const [curbs, events] = await Promise.all([store.curbs(), store.events()]);
      return curbMapNear(curbs, events, a.lat, a.lon, radius(300));
    }
    case "where_is_my_car": {
      const e = (await store.events(1))[0];
      if (!e) return { error: "No saved parking spot" };
      return { ...e, typeLabel: e.type ? TYPE_LABELS[e.type] : null, savedAtIsrael: israelNow(new Date(e.time)).toISOString().slice(0, 16), walk: mapsLink(e.lat, e.lon, "walking"), zoneNow: zoneAt(e.lat, e.lon), autotelNearby: autotelNear(e.lat, e.lon, 30) };
    }
    case "parking_history": return (await store.events(a.limit ?? 50)).map((e) => ({ ...e, typeLabel: e.type ? TYPE_LABELS[e.type] : null, timeIsrael: israelNow(new Date(e.time)).toISOString().slice(0, 16) }));
    case "parking_stats": return stats(await store.events(), a.lat != null && a.lon != null ? { lat: a.lat, lon: a.lon, radius: a.radiusMeters ?? 500 } : undefined);
    case "parking_near_destination": {
      let dest: { lat: number; lon: number; label?: string } | null = a.lat != null && a.lon != null ? { lat: a.lat, lon: a.lon, label: a.address } : null;
      if (!dest && a.address) dest = await geocode(a.address);
      if (!dest) return { error: "Destination not found. Give a Tel Aviv street and house number in Hebrew, or lat/lon." };
      const [{ garages, live }, events, curbs, assigned] = await Promise.all([liveGarages(), store.events(), store.curbs(), store.setting<string[]>("assignedLots", [])]);
      const notices = await fetchNotices().catch(() => []);
      return { liveOccupancy: live, ...parkingNearDestination(dest, parseLocal(a.arrival), a.minutes ?? 120, garages, events, curbs, assigned), notices };
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
      return reply({ protocolVersion: msg.params?.protocolVersion ?? "2025-03-26", capabilities: { tools: {} }, serverInfo: { name: "parking-tlv", version: "0.6.3" } });
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

const CRON_JOBS: [string, string, string][] = [
  // [job name, schedule (UTC), path]
  ["parking-weekly", "30 6 * * 0", "weekly"], // Sunday 08:30 Israel winter time
  ["parking-news", "17 * * * *", "news"],
];

async function scheduleCron(base: string) {
  let secret = await store.setting<string | null>("cronSecret", null);
  if (!secret) { secret = newToken(); await store.setSetting("cronSecret", secret); }
  for (const [job, when, path] of CRON_JOBS) {
    await sql`select cron.unschedule(jobid) from cron.job where jobname = ${job}`;
    await sql`select cron.schedule(${job}, ${when}, ${`select net.http_post(url := '${base}/cron/${path}/${secret}', headers := '{"content-type":"application/json"}'::jsonb, body := '{}'::jsonb)`})`;
  }
}
async function unscheduleCron() {
  for (const [job] of CRON_JOBS) await sql`select cron.unschedule(jobid) from cron.job where jobname = ${job}`;
}

Deno.serve(async (req: Request): Promise<Response> => {
  try {
    return await handle(req);
  } catch (e) {
    // Log the details, answer without them.
    console.error("parking:", e);
    return json({ error: "Internal error, try again in a moment" }, 500);
  }
});

async function handle(req: Request): Promise<Response> {
  const url = new URL(req.url);
  const path = url.pathname.replace(/^.*?\/parking/, "") || "/";
  const base = `https://${url.host}/functions/v1/parking`;
  if (req.method === "OPTIONS") return new Response(null, { headers: { "access-control-allow-origin": "*", "access-control-allow-headers": "authorization, content-type, mcp-session-id, mcp-protocol-version", "access-control-allow-methods": "GET, POST, DELETE, OPTIONS" } });
  if (path === "/") return json({ name: "parking-tlv", version: "0.6.3", mcp: `${base}/mcp` });
  // Public, no data: what the server knows right now. server-health checks it.
  if (path === "/health") {
    await loadData();
    return json({ ok: data.zones.length > 0, version: "0.6.3", zones: data.zones.length, nightLots: data.nightLots.length, publicLots: data.publicLots.length, loadingSigns: data.loadingSigns.length, autotel: data.autotel.length, source: dataSource });
  }
  if (path === "/api/register" && req.method === "POST") {
    const t = await store.register();
    return t ? json({ token: t }) : json({ error: "Already registered" }, 409);
  }

  // An agent host (Morty) redeems the one-time code the app put in the Telegram link.
  if (path === "/link/claim" && req.method === "POST") {
    const body = (await req.json().catch(() => ({}))) as { code?: string; client?: string };
    const pending = await store.setting<{ code: string; expires: number } | null>("linkCode", null);
    if (!pending || !body.code || body.code !== pending.code || Date.now() > pending.expires) {
      return json({ error: "The link code is wrong or expired. Tap 'connect to Morty' in the parking app again." }, 400);
    }
    await store.deleteSetting("linkCode");
    const token = newToken();
    await store.replaceAgent(String(body.client ?? "agent").slice(0, 32), token);
    await store.setSetting("seenNews", (await fetchNotices().catch(() => [])).map((n) => n.link));
    await scheduleCron(base);
    return json({ name: LINK_NAME, title: LINK_TITLE, base, token, auth: { header: "Authorization", scheme: "Bearer" }, guide: guide(), feed: "/api/feed", cursor: await store.lastNotification(), hello: HELLO });
  }

  // Scheduled jobs: authenticated by a random secret in the path.
  const cron = path.match(/^\/cron\/(weekly|news)\/([a-f0-9]{32,})$/);
  if (cron) {
    const secret = await store.setting<string | null>("cronSecret", null);
    if (!secret || cron[2] !== secret) return json({ error: "Not found" }, 404);
    await loadData();
    if (cron[1] === "weekly") {
      const text = weeklyText(await store.events());
      if (text) await store.queue("weekly", "low", text);
    } else {
      const fresh = freshNews(await fetchNotices().catch(() => []), await store.setting<string[]>("seenNews", []));
      for (const n of fresh.push) await store.queue("news", n.risk, n.text);
      await store.setSetting("seenNews", fresh.seen);
    }
    return json({ ok: true });
  }

  const token = await store.token();
  const given = req.headers.get("authorization")?.replace(/^Bearer\s+/i, "") ?? url.searchParams.get("key") ?? "";
  const who = token && given === token ? "owner" : given && (await store.agent(given)) ? "agent" : null;
  if (!who) return json({ error: "Unauthorized" }, 401);

  await loadData();
  // Owner (the app) and a linked agent host may read.
  if (path === "/mcp") return mcp(req, store);
  if (path === "/api/tools" && req.method === "GET") return json(TOOLS);
  const tool = path.match(/^\/api\/tool\/([a-z_]+)$/);
  if (tool && req.method === "POST") {
    const args = (await req.json().catch(() => ({}))) as Record<string, unknown>;
    try { return json(await callTool(tool[1], args ?? {}, store)); } catch (e) { return json({ error: String(e) }, 400); }
  }
  if (path === "/api/feed" && req.method === "GET") return json(await store.feed(Number(url.searchParams.get("after") ?? 0) || 0));
  if (who !== "owner") return json({ error: "Only the parking app can do this" }, 403);

  if (path === "/api/events" && req.method === "POST") {
    const body = (await req.json()) as { events: Event[] };
    return json({ saved: await store.upsert(body.events ?? []) });
  }
  if (path === "/api/events" && req.method === "GET") return json(await store.events());
  if (path === "/api/curbs" && req.method === "POST") {
    const body = (await req.json()) as { curbs?: Curb[]; deleted?: string[] };
    return json({ saved: await store.saveCurbs(body.curbs ?? [], body.deleted ?? []) });
  }
  if (path === "/api/profile" && req.method === "POST") {
    const body = (await req.json()) as { assignedLots?: string[] };
    if (Array.isArray(body.assignedLots)) await store.setSetting("assignedLots", body.assignedLots);
    return json({ ok: true });
  }
  if (path === "/api/link/start" && req.method === "POST") {
    const code = newCode();
    await store.setSetting("linkCode", { code, expires: Date.now() + 10 * 60_000 });
    return json({ payload: linkPayload(url.host, code), expiresInSeconds: 600 });
  }
  if (path === "/api/link/status" && req.method === "GET") {
    const agents = (await store.agents()).map((a) => ({ client: a.name, since: a.created, lastSeen: a.last_seen }));
    return json({ linked: agents.length > 0, agents });
  }
  if (path === "/api/link/revoke" && req.method === "POST") {
    const body = (await req.json().catch(() => ({}))) as { client?: string };
    const left = await store.removeAgents(body.client || undefined);
    if (!left) await unscheduleCron();
    return json({ ok: true, left });
  }
  if (path === "/api/notify" && req.method === "POST") {
    const body = (await req.json()) as { text?: string; kind?: string; risk?: string };
    const text = (body.text ?? "").trim();
    if (!text) return json({ error: "text is required" }, 400);
    const risk = ["low", "medium", "high"].includes(body.risk ?? "") ? body.risk! : "low";
    return json({ queued: await store.queue((body.kind ?? "app").slice(0, 24), risk, text) });
  }
  const del = path.match(/^\/api\/events\/([\w-]+)$/);
  if (del && req.method === "DELETE") { await store.remove(del[1]); return json({ ok: true }); }
  return json({ error: "Not found" }, 404);
}
