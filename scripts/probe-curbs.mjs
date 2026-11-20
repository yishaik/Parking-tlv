// Where can curb-marking data for zone 9 come from? Writes findings to data/probe/curbs/.
import { mkdir, writeFile } from "node:fs/promises";
const OUT = "data/probe/curbs";
await mkdir(OUT, { recursive: true });
const [W, S, E, N] = [34.76177, 32.07363, 34.78373, 32.10471];
const report = {};

// 1. OpenStreetMap curb/parking tags inside zone 9's bbox
try {
  const q = `[out:json][timeout:60];way(${S},${W},${N},${E})[highway][~"^parking:"~"."];out tags center;`;
  const r = await fetch("https://overpass-api.de/api/interpreter", { method: "POST", body: "data=" + encodeURIComponent(q) });
  const j = await r.json();
  const tags = {};
  for (const el of j.elements) for (const [k, v] of Object.entries(el.tags)) if (k.startsWith("parking:")) tags[`${k}=${v}`] = (tags[`${k}=${v}`] ?? 0) + 1;
  const streets = await (await fetch("https://overpass-api.de/api/interpreter", { method: "POST", body: "data=" + encodeURIComponent(`[out:json][timeout:60];way(${S},${W},${N},${E})[highway~"residential|tertiary|secondary|primary|unclassified|living_street"];out ids;`) })).json();
  report.osm = { waysWithParkingTags: j.elements.length, totalStreetWays: streets.elements.length, topTags: Object.entries(tags).sort((a, b) => b[1] - a[1]).slice(0, 40) };
} catch (e) { report.osm = { error: String(e) }; }

// 2. City iView viewer: look for layer configs mentioning parking arrangements
try {
  const base = "https://gisn.tel-aviv.gov.il/iview2js4/";
  const html = await (await fetch(base)).text();
  const urls = [...html.matchAll(/(?:src|href)="([^"]+\.(?:js|json))"/g)].map((m) => new URL(m[1], base).href);
  const hits = [];
  for (const u of urls.slice(0, 40)) {
    try {
      const t = await (await fetch(u)).text();
      for (const m of t.matchAll(/.{0,120}(הסדרי חניה|כחול.?לבן|MapServer|FeatureServer).{0,120}/g)) hits.push(`${u.split("/").pop()}: ${m[0]}`);
    } catch {}
  }
  report.viewer = { scripts: urls, hits: [...new Set(hits)].slice(0, 80) };
} catch (e) { report.viewer = { error: String(e) }; }

// 3. 2026 orthophoto: resolution + a crop around Nordau 86 for visual inspection
try {
  const svc = "https://gisn.tel-aviv.gov.il/arcgis/rest/services/WM/IView2Ortho2026WM/MapServer";
  const info = await (await fetch(`${svc}?f=json`)).json();
  report.ortho = { lods: info.tileInfo?.lods?.slice(-4), pixelSize: info.pixelSizeX, spatialReference: info.spatialReference };
  // Web Mercator crop ~60m x 40m around Nordau 86
  const lon = 34.78161, lat = 32.09168;
  const x = (lon * 20037508.34) / 180, y = (Math.log(Math.tan(((90 + lat) * Math.PI) / 360)) * 20037508.34) / Math.PI;
  for (const [name, half] of [["nordau86_60m", 30], ["nordau86_25m", 12.5]]) {
    const bbox = [x - half, y - half * 0.66, x + half, y + half * 0.66].join(",");
    const img = await fetch(`${svc}/export?bbox=${bbox}&bboxSR=3857&imageSR=3857&size=1200,800&format=jpg&f=image`);
    await writeFile(`${OUT}/${name}.jpg`, Buffer.from(await img.arrayBuffer()));
  }
} catch (e) { report.ortho = { error: String(e) }; }

await writeFile(`${OUT}/report.json`, JSON.stringify(report, null, 1));
console.log(JSON.stringify(report, null, 1).slice(0, 6000));
