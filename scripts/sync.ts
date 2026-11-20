import { mkdir, writeFile } from "node:fs/promises";
import { fetchLayer, LAYERS, ZONE9_BBOX } from "../src/gis.js";

// Layers the app and MCP server read. Snapshots go to data/<name>.json.
const JOBS: { name: keyof typeof LAYERS; bbox?: readonly number[]; fields?: string; round?: boolean }[] = [
  { name: "zones" },
  { name: "garages" },
  { name: "nightLots" },
  { name: "publicLots", fields: "name,address,num_vehicles,num_disabled,description,covered,achuzot" },
  { name: "autotel", fields: "address0,capacity,lat,lon" },
  { name: "loadingSigns", fields: "traffic_sign_text,traffic_sign_code,status" },
  { name: "streets", bbox: ZONE9_BBOX, fields: "t_rechov,k_rechov", round: true },
];

// Round coordinates to ~1 m to keep snapshots small.
function roundGeometry<T extends { geometry?: any }>(f: T): T {
  const r = (v: number) => Math.round(v * 1e5) / 1e5;
  const g = f.geometry;
  if (!g) return f;
  if (g.paths) g.paths = g.paths.map((p: number[][]) => p.map(([x, y]) => [r(x), r(y)]));
  if (g.rings) g.rings = g.rings.map((p: number[][]) => p.map(([x, y]) => [r(x), r(y)]));
  return f;
}

await mkdir("data", { recursive: true });
let failed = 0;
for (const job of JOBS) {
  try {
    let features = await fetchLayer(LAYERS[job.name], { bbox: job.bbox, fields: job.fields });
    if (job.round) features = features.map(roundGeometry);
    await writeFile(
      `data/${job.name}.json`,
      JSON.stringify({ layer: LAYERS[job.name], fetchedAt: new Date().toISOString(), features }),
    );
    console.log(`${job.name}: ${features.length} features`);
  } catch (e) {
    failed++;
    console.error(`${job.name}: FAILED`, e);
  }
}

// OpenStreetMap street-parking tags in zone 9 (best effort; Overpass is often overloaded).
try {
  const [W, S, E, N] = ZONE9_BBOX;
  const q = `[out:json][timeout:120];(way(${S},${W},${N},${E})[highway][~"^parking:"~"."];nwr(${S},${W},${N},${E})[amenity=parking];);out tags center;`;
  let saved = false;
  for (const ep of ["https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter", "https://maps.mail.ru/osm/tools/overpass/api/interpreter"]) {
    try {
      const r = await fetch(`${ep}?data=${encodeURIComponent(q)}`, { headers: { "User-Agent": "parking-tlv/1.0 (github.com/yishaik)" }, signal: AbortSignal.timeout(150000) });
      const t = await r.text();
      if (!t.trim().startsWith("{")) continue;
      await writeFile("data/osmParking.json", JSON.stringify({ fetchedAt: new Date().toISOString(), source: ep, ...JSON.parse(t) }));
      console.log(`osm: ${JSON.parse(t).elements.length} elements from ${ep}`);
      saved = true;
      break;
    } catch {}
  }
  if (!saved) console.log("osm: all Overpass endpoints busy, keeping previous snapshot");
} catch {}

process.exit(failed ? 1 : 0);
