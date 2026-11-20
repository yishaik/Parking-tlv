// Tests the Supabase function logic against the synced city snapshots.
import { describe, expect, it, beforeAll } from "vitest";
import { readFileSync } from "node:fs";
import {
  curbMapNear, garagesNear, israelNow, loadingSignsNear, onPermitBoundary, otherLotsNear, parkingNearDestination, parseGarages,
  parseLocal, setData, stats, streetCost, zoneAt, type Event,
} from "../supabase/functions/parking/tools.ts";

const snap = (n: string) => JSON.parse(readFileSync(`data/${n}.json`, "utf8")).features;
const NORDAU = { lat: 32.0916827757229, lon: 34.78161161459743 };
const garages = () => parseGarages(snap("garages"));

beforeAll(() => {
  setData({ zones: snap("zones"), nightLots: snap("nightLots").filter((f: any) => f.geometry), publicLots: snap("publicLots").filter((f: any) => f.geometry), loadingSigns: snap("loadingSigns").filter((f: any) => f.geometry), autotel: snap("autotel") });
});

describe("function tools", () => {
  it("knows the owner's zone and that it is free", () => {
    expect(zoneAt(NORDAU.lat, NORDAU.lon)).toBe(9);
    const s = streetCost(NORDAU.lat, NORDAU.lon, parseLocal("2026-11-22T10:00"), 120);
    expect(s.costILS).toBe(0);
    expect(s.appRequired).toBe(false);
  });

  it("detects a boundary street just outside zone 9", () => {
    const z9 = snap("zones").find((f: any) => f.attributes.ms_ezor === 9);
    const [lon, lat] = z9.geometry.rings[0][0];
    // A point a few meters outside the first vertex, in whichever direction leaves the zone.
    const candidates = [[lat + 0.0001, lon], [lat - 0.0001, lon], [lat, lon + 0.0001], [lat, lon - 0.0001]];
    const outside = candidates.find(([la, lo]) => zoneAt(la, lo) !== 9)!;
    expect(onPermitBoundary(outside[0], outside[1])).toBe(true);
    expect(onPermitBoundary(NORDAU.lat, NORDAU.lon)).toBe(false);
  });

  it("parses Israel local time without shifting", () => {
    expect(parseLocal("2026-11-22T18:30").getHours()).toBe(18);
    expect(israelNow(new Date("2026-11-22T16:30:00Z")).getHours()).toBe(18);
  });

  it("marks assigned close-to-home lots free at night", () => {
    const g = garagesNear(garages(), NORDAU.lat, NORDAU.lon, 1500, 20, ["בזל"], parseLocal("2026-11-22T20:00"));
    const basel = g.find((x) => x.name === "בזל");
    if (basel) expect(basel.freeForOwnerAtTime).toBe(true);
    expect(g[0].distanceMeters).toBeLessThanOrEqual(g.at(-1)!.distanceMeters);
  });

  it("finds other lots and loading signs with text", () => {
    expect(otherLotsNear(32.0854, 34.7818, 1500).length).toBeGreaterThan(0);
    const anySign = snap("loadingSigns").find((f: any) => f.geometry);
    const s = loadingSignsNear(anySign.geometry.y, anySign.geometry.x, 5);
    expect(s[0].text.length).toBeGreaterThan(5);
  });

  it("ranks free street parking first in the permit zone and includes context", () => {
    const r = parkingNearDestination(NORDAU, parseLocal("2026-11-22T10:00"), 120, garages(), [], []);
    expect(r.options[0].kind).toBe("street");
    expect(r.options[0].costILS).toBe(0);
    expect(Array.isArray(r.loadingSignsNearby)).toBe(true);
  });

  it("builds the curb map from marks and classified parkings", () => {
    const ev: Event = { id: "e1", lat: NORDAU.lat, lon: NORDAU.lon, time: Date.now(), source: "manual", zone: 9, street: "נורדאו", type: "blue_white", search: "5" };
    const m = curbMapNear([{ id: "c1", lat: NORDAU.lat + 0.0002, lon: NORDAU.lon, type: "red_white", street: "נורדאו", time: Date.now(), source: "map" }], [ev], NORDAU.lat, NORDAU.lon, 100);
    expect(m.byStreet["נורדאו"]["כחול-לבן"]).toBe(1);
    expect(m.byStreet["נורדאו"]["אדום-לבן (אסור)"]).toBe(1);
  });

  it("computes stats from history", () => {
    const ev = (street: string, search: string, type: string): Event => ({ id: street + search, lat: NORDAU.lat, lon: NORDAU.lon, time: Date.parse("2026-11-20T17:00:00Z"), source: "manual", zone: 9, street, type, search });
    const s = stats([ev("נורדאו", "0", "blue_white"), ev("נורדאו", "5", "blue_white"), ev("אוסישקין", "30", "garage")]);
    expect(s.fastestStreets[0].street).toBe("נורדאו");
  });
});
