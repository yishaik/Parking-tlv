// Where can curb-marking data for zone 9 come from? Writes findings to data/probe/curbs/.
import { mkdir, writeFile } from "node:fs/promises";
const OUT = "data/probe/curbs";
await mkdir(OUT, { recursive: true });
const [W, S, E, N] = [34.76177, 32.07363, 34.78373, 32.10471];
const report = {};

// 1. OpenStreetMap curb/parking tags inside zone 9's bbox
try {
  const q = `[out:json][timeout:60];way(${S},${W},${N},${E})[highway][~"^parking:"~"."];out tags center;`;
  const op = async (query) => {
    for (const ep of ["https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter", "https://overpass.private.coffee/api/interpreter"]) {
      const r = await fetch(ep, { method: "POST", headers: { "User-Agent": "parking-tlv-probe/1.0 (github.com/yishaik)", "Content-Type": "application/x-www-form-urlencoded" }, body: "data=" + encodeURIComponent(query) });
      const t = await r.text();
      if (t.trim().startsWith("{")) return JSON.parse(t);
      console.error(ep, r.status, t.slice(0, 200));
    }
    throw new Error("all overpass endpoints failed");
  };
  const j = await op(q);
  const tags = {};
  for (const el of j.elements) for (const [k, v] of Object.entries(el.tags)) if (k.startsWith("parking:")) tags[`${k}=${v}`] = (tags[`${k}=${v}`] ?? 0) + 1;
  const streets = await op(`[out:json][timeout:60];way(${S},${W},${N},${E})[highway~"residential|tertiary|secondary|primary|unclassified|living_street"];out ids;`);
  report.osm = { waysWithParkingTags: j.elements.length, totalStreetWays: streets.elements.length, topTags: Object.entries(tags).sort((a, b) => b[1] - a[1]).slice(0, 40) };
} catch (e) { report.osm = { error: String(e) }; }

await writeFile(`${OUT}/osm.json`, JSON.stringify(report, null, 1));
console.log(JSON.stringify(report, null, 1).slice(0, 6000));
