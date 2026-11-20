import { readFile } from "node:fs/promises";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
import { haversineMeters, pointInRings } from "./geo.js";
import { evaluateStreetParking, type RulesConfig } from "./rules.js";
import type { Feature } from "./gis.js";

interface Snapshot {
  fetchedAt: string;
  features: Feature[];
}

async function load<T>(path: string): Promise<T> {
  return JSON.parse(await readFile(new URL(`../${path}`, import.meta.url), "utf8")) as T;
}

const text = (value: unknown) => ({ content: [{ type: "text" as const, text: JSON.stringify(value, null, 2) }] });

async function zoneAt(lat: number, lon: number): Promise<number | null> {
  const snap = await load<Snapshot>("data/zones.json");
  for (const f of snap.features) {
    if (f.geometry?.rings && pointInRings(lon, lat, f.geometry.rings)) return Number(f.attributes.ms_ezor);
  }
  return null;
}

const server = new McpServer({ name: "parking-tlv", version: "0.1.0" });

server.registerTool(
  "zone_at",
  {
    description: "Which Tel Aviv parking zone (1-13) a coordinate is in, and whether it is the user's permit zone.",
    inputSchema: { lat: z.number(), lon: z.number() },
  },
  async ({ lat, lon }) => {
    const cfg = await load<RulesConfig>("config/rules.json");
    const zone = await zoneAt(lat, lon);
    return text({ zone, isPermitZone: zone !== null && zone === cfg.profile.permitZone });
  },
);

server.registerTool(
  "street_parking_cost",
  {
    description:
      "Cost of blue-white street parking at a coordinate for the configured permit holder. costILS is null when the rate is not verified; never invent one.",
    inputSchema: {
      lat: z.number(),
      lon: z.number(),
      start: z.string().describe("ISO local time, e.g. 2026-11-20T18:30"),
      minutes: z.number().int().positive().max(1440),
      freeMinutesUsedToday: z.number().int().min(0).default(0),
    },
  },
  async ({ lat, lon, start, minutes, freeMinutesUsedToday }) => {
    const cfg = await load<RulesConfig>("config/rules.json");
    const zone = await zoneAt(lat, lon);
    return text({ zone, ...evaluateStreetParking(cfg, zone, new Date(start), minutes, freeMinutesUsedToday) });
  },
);

server.registerTool(
  "garages_near",
  {
    description: "Ahuzot HaHof garages near a coordinate with tariffs and occupancy status (status may be stale; see statusUpdated).",
    inputSchema: { lat: z.number(), lon: z.number(), radiusMeters: z.number().default(600), limit: z.number().int().default(8) },
  },
  async ({ lat, lon, radiusMeters, limit }) => {
    const snap = await load<Snapshot>("data/garages.json");
    const rows = snap.features
      .map((f) => {
        const a = f.attributes;
        const d = haversineMeters(lat, lon, Number(a.lat), Number(a.lon));
        return {
          name: a.shem_chenyon,
          address: a.ktovet,
          distanceMeters: Math.round(d),
          status: a.status_chenyon,
          statusUpdated: a.tr_status_chenyon,
          dayTariff: a.taarif_yom,
          nightTariff: a.taarif_layla,
          tariffNotes: a.hearot_taarif,
          lat: a.lat,
          lon: a.lon,
        };
      })
      .filter((r) => r.distanceMeters <= radiusMeters)
      .sort((x, y) => x.distanceMeters - y.distanceMeters)
      .slice(0, limit);
    return text({ dataFetchedAt: snap.fetchedAt, garages: rows });
  },
);

server.registerTool(
  "night_lots_near",
  {
    description: "'Close to home' resident lots (free nights/weekends with a matching permit) near a coordinate.",
    inputSchema: { lat: z.number(), lon: z.number(), radiusMeters: z.number().default(800) },
  },
  async ({ lat, lon, radiusMeters }) => {
    const snap = await load<Snapshot>("data/nightLots.json");
    const rows = snap.features
      .filter((f) => f.geometry?.x !== undefined && f.geometry?.y !== undefined)
      .map((f) => ({
        name: f.attributes.shem,
        address: f.attributes.ktovet,
        spots: f.attributes.num_vehicles,
        nightTariff: f.attributes.taarif_layla,
        dayTariff: f.attributes.taarif_yom,
        notes: f.attributes.hearot_atar,
        distanceMeters: Math.round(haversineMeters(lat, lon, f.geometry!.y!, f.geometry!.x!)),
      }))
      .filter((r) => r.distanceMeters <= radiusMeters)
      .sort((a, b) => a.distanceMeters - b.distanceMeters);
    return text({ dataFetchedAt: snap.fetchedAt, lots: rows });
  },
);

await server.connect(new StdioServerTransport());
