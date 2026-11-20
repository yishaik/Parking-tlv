// One-off probe of extra data sources for zone 9. Output: data/probe/sources.json
import { mkdir, writeFile } from "node:fs/promises";
const GIS = "https://gisn.tel-aviv.gov.il/arcgis/rest/services";
const [W, S, E, N] = [34.76177, 32.07363, 34.78373, 32.10471];
const report = {};
const UA = { "User-Agent": "parking-tlv-probe/1.0 (github.com/yishaik/parking-tlv)" };

async function overpass(query) {
  const errors = [];
  for (const ep of ["https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter", "https://maps.mail.ru/osm/tools/overpass/api/interpreter", "https://overpass.private.coffee/api/interpreter"]) {
    try {
      const r = await fetch(`${ep}?data=${encodeURIComponent(query)}`, { headers: UA, signal: AbortSignal.timeout(90000) });
      const t = await r.text();
      if (t.trim().startsWith("{")) return JSON.parse(t);
      errors.push(`${ep} ${r.status} ${t.slice(0, 120)}`);
    } catch (e) { errors.push(`${ep} ${e.cause?.code ?? e}`); }
  }
  throw new Error(errors.join(" | "));
}

// OSM: street parking tags, parking amenities, driveways
try {
  const lanes = await overpass(`[out:json][timeout:80];way(${S},${W},${N},${E})[highway][~"^parking:"~"."];out tags;`);
  const tags = {}; const names = new Set();
  for (const el of lanes.elements) { if (el.tags.name) names.add(el.tags.name); for (const [k, v] of Object.entries(el.tags)) if (k.startsWith("parking:")) tags[`${k}=${v}`] = (tags[`${k}=${v}`] ?? 0) + 1; }
  const streets = await overpass(`[out:json][timeout:80];way(${S},${W},${N},${E})[highway~"^(residential|tertiary|secondary|primary|unclassified|living_street)$"];out tags;`);
  const allNames = new Set(streets.elements.map((e) => e.tags.name).filter(Boolean));
  const lots = await overpass(`[out:json][timeout:80];nwr(${S},${W},${N},${E})[amenity=parking];out tags center;`);
  const lotTags = {};
  for (const el of lots.elements) for (const k of ["access", "fee", "parking", "operator", "capacity", "opening_hours"]) if (el.tags[k]) lotTags[`${k}=${el.tags[k]}`] = (lotTags[`${k}=${el.tags[k]}`] ?? 0) + 1;
  report.osm = {
    streetWaysWithParkingTags: lanes.elements.length, streetWaysTotal: streets.elements.length,
    streetsWithTags: [...names], streetNamesTotal: allNames.size,
    topParkingTags: Object.entries(tags).sort((a, b) => b[1] - a[1]).slice(0, 40),
    parkingAmenities: lots.elements.length, parkingAmenityTags: Object.entries(lotTags).sort((a, b) => b[1] - a[1]).slice(0, 30),
    sampleLots: lots.elements.slice(0, 8).map((e) => e.tags),
  };
} catch (e) { report.osm = { error: String(e) }; }

// City layers: fields + count inside zone 9 bbox
for (const id of [556, 555, 602, 507, 449, 488]) {
  try {
    const info = await (await fetch(`${GIS}/IView2/MapServer/${id}?f=json`)).json();
    const q = new URLSearchParams({ geometry: `${W},${S},${E},${N}`, geometryType: "esriGeometryEnvelope", inSR: "4326", spatialRel: "esriSpatialRelIntersects", outFields: "*", returnGeometry: "false", resultRecordCount: "3", f: "json" });
    const cnt = await (await fetch(`${GIS}/IView2/MapServer/${id}/query?${new URLSearchParams({ geometry: `${W},${S},${E},${N}`, geometryType: "esriGeometryEnvelope", inSR: "4326", spatialRel: "esriSpatialRelIntersects", returnCountOnly: "true", f: "json" })}`)).json();
    const sample = await (await fetch(`${GIS}/IView2/MapServer/${id}/query?${q}`)).json();
    report[`layer${id}`] = { name: info.name, geometry: info.geometryType, fields: info.fields?.map((f) => `${f.name}:${f.alias}`), countInZone9Bbox: cnt.count, sample: sample.features?.map((f) => f.attributes) };
  } catch (e) { report[`layer${id}`] = { error: String(e) }; }
}

// City Hebrew basemap tiles (for the in-app map)
try {
  const info = await (await fetch(`${GIS}/WM/IView2MapHebWM/MapServer?f=json`)).json();
  report.basemap = { tiled: !!info.tileInfo, lods: info.tileInfo?.lods?.map((l) => l.level), sr: info.spatialReference?.latestWkid };
} catch (e) { report.basemap = { error: String(e) }; }

// Israeli holidays (for holiday-eve paid hours)
try {
  const h = await (await fetch("https://www.hebcal.com/hebcal?v=1&cfg=json&maj=on&min=off&mod=off&nx=off&ss=off&mf=off&c=off&i=on&year=2026", { headers: UA })).json();
  report.hebcal = h.items?.filter((i) => i.yomtov || /Erev/.test(i.title)).map((i) => `${i.date} ${i.title}${i.yomtov ? " [yomtov]" : ""}`);
} catch (e) { report.hebcal = { error: String(e) }; }

await mkdir("data/probe", { recursive: true });
await writeFile("data/probe/sources.json", JSON.stringify(report, null, 1));
console.log(JSON.stringify(report, null, 1).slice(0, 4000));
