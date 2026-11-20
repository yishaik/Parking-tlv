// Runs in GitHub Actions (open network). Geocodes configured addresses with the city's
// address layer (527) and reports which parking zone each falls in. Output: data/probe/*.json
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { GIS_BASE } from "../src/gis.js";
import { pointInRings } from "../src/geo.js";

interface Field { name: string; type: string }
const places: { label: string; street: string; number: string }[] = JSON.parse(await readFile("config/places.json", "utf8"));
const zones = JSON.parse(await readFile("data/zones.json", "utf8")).features as { attributes: { ms_ezor: number }; geometry: { rings: [number, number][][] } }[];

await mkdir("data/probe", { recursive: true });
const def = (await (await fetch(`${GIS_BASE}/527?f=json`)).json()) as { fields: Field[] };
await writeFile("data/probe/layer527.json", JSON.stringify(def, null, 1));
const strFields = def.fields.filter((f) => f.type === "esriFieldTypeString").map((f) => f.name);

const result: unknown[] = [];
for (const p of places) {
  const where = strFields.map((f) => `${f} LIKE '%${p.street}%'`).join(" OR ");
  const params = new URLSearchParams({ where, outFields: "*", outSR: "4326", returnGeometry: "true", resultRecordCount: "2000", f: "json" });
  const body = (await (await fetch(`${GIS_BASE}/527/query?${params}`)).json()) as { features?: { attributes: Record<string, unknown>; geometry?: { x: number; y: number } }[] };
  const hits = (body.features ?? []).filter((f) => String(f.attributes.ms_bayit).trim() === p.number && String(f.attributes.t_rechov).includes(p.street));
  result.push({
    ...p,
    candidates: hits.slice(0, 5).map((h) => ({
      attributes: h.attributes,
      lon: h.geometry?.x,
      lat: h.geometry?.y,
      zone: h.geometry ? (zones.find((z) => pointInRings(h.geometry!.x, h.geometry!.y, z.geometry.rings))?.attributes.ms_ezor ?? null) : null,
    })),
    streetMatches: body.features?.length ?? 0,
  });
}
await writeFile("data/probe/places.json", JSON.stringify(result, null, 1));
console.log(JSON.stringify(result, null, 1).slice(0, 3000));
