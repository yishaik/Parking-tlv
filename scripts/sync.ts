import { mkdir, writeFile } from "node:fs/promises";
import { fetchLayer, LAYERS } from "../src/gis.js";

// Layers the MCP server reads. Snapshots go to data/<name>.json.
const WANTED = ["zones", "garages", "nightLots"] as const;

await mkdir("data", { recursive: true });
let failed = 0;
for (const name of WANTED) {
  try {
    const features = await fetchLayer(LAYERS[name]);
    await writeFile(
      `data/${name}.json`,
      JSON.stringify({ layer: LAYERS[name], fetchedAt: new Date().toISOString(), features }, null, 1),
    );
    console.log(`${name}: ${features.length} features`);
  } catch (e) {
    failed++;
    console.error(`${name}: FAILED`, e);
  }
}
process.exit(failed ? 1 : 0);
