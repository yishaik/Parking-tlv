import { describe, expect, it } from "vitest";
import { existsSync, readFileSync } from "node:fs";
import { pointInRings } from "../src/geo.js";

// Runs against the real synced snapshot (skipped if data/ is empty).
const has = existsSync("data/zones.json");
describe.skipIf(!has)("real zone data", () => {
  const zones = has ? JSON.parse(readFileSync("data/zones.json", "utf8")).features : [];
  const zoneOf = (lat: number, lon: number) =>
    zones.find((z: any) => pointInRings(lon, lat, z.geometry.rings))?.attributes.ms_ezor ?? null;
  it("Nordau 86 and Usishkin 54 are zone 9", () => {
    expect(zoneOf(32.0916827757229, 34.78161161459743)).toBe(9);
    expect(zoneOf(32.09500641606727, 34.77916908119569)).toBe(9);
  });
  it("a point in the sea is in no zone", () => {
    expect(zoneOf(32.09, 34.7)).toBeNull();
  });
});
