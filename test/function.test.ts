// Tests the Supabase function logic against the synced city snapshots.
import { describe, expect, it, beforeAll } from "vitest";
import { readFileSync } from "node:fs";
import {
  curbMapNear, garagesNear, residentDiscount, israelNow, loadingSignsNear, onPermitBoundary, otherLotsNear, parkingNearDestination, parseGarages,
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
    // Permit letter: Basel is free Sun-Thu only from 21:00; assigned lots come from the letter with no setting.
    const at = (s: string) => garagesNear(garages(), NORDAU.lat, NORDAU.lon, 1500, 20, [], parseLocal(s)).find((x) => x.name === "בזל");
    const basel = at("2026-11-22T20:00");
    if (basel) {
      expect(basel.assignedToOwner).toBe(true);
      expect(basel.freeForOwnerAtTime).toBe(false);
      expect(at("2026-11-22T21:30")!.freeForOwnerAtTime).toBe(true);
      expect(basel.residentDiscountPercent).toBe(50);
    }
    const g = garagesNear(garages(), NORDAU.lat, NORDAU.lon, 1500, 20, [], parseLocal("2026-11-22T20:00"));
    expect(g[0].distanceMeters).toBeLessThanOrEqual(g.at(-1)!.distanceMeters);
  });

  it("takes the resident discount from the permit letter", () => {
    expect(residentDiscount("בני דן")).toBe(50);
    expect(residentDiscount("כרמל 1")).toBe(50);
    expect(residentDiscount("שרתון")).toBe(50);
    expect(residentDiscount("אסותא")).toBe(75);
    expect(residentDiscount("רידינג מערב")).toBe(75);
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

import { freshNews, guide, linkPayload, newCode, weeklyText } from "../supabase/functions/parking/link.ts";

describe("link for Morty", () => {
  it("builds a Telegram start payload within limits", () => {
    const p = linkPayload("vybivdkskrkafcuedvbg.supabase.co", newCode());
    expect(p).toMatch(/^link-sb-vybivdkskrkafcuedvbg-parking-[A-Za-z0-9]{16}$/);
    expect(p.length).toBeLessThanOrEqual(64);
  });
  it("describes every tool in the agent guide", () => {
    const g = guide();
    for (const t of ["parking_near_destination", "where_is_my_car", "parking_notices", "geocode"]) expect(g).toContain(t);
    expect(g).toContain("/api/tool/");
  });
  it("pushes only new free-parking and price news", () => {
    const n = (kind: any, link: string) => ({ kind, title: "t", source: "s", link, publishedAt: "2026-11-20T10:00:00Z" });
    const r = freshNews([n("free_parking", "a"), n("event", "b"), n("price_change", "c"), n("free_parking", "d")], ["d"]);
    expect(r.push.map((p) => p.text.split("\n")[1])).toEqual(["a", "c"]);
    expect(r.push[0].risk).toBe("medium");
    expect(r.seen.slice(0, 4)).toEqual(["a", "b", "c", "d"]);
  });
  it("summarises the last week only", () => {
    const now = Date.parse("2026-11-22T08:00:00Z");
    const e = (days: number, type: string | null): Event => ({ id: String(days), lat: NORDAU.lat, lon: NORDAU.lon, time: now - days * 86_400_000, source: "t", zone: 9, street: "נורדאו", type, search: "5" });
    expect(weeklyText([e(10, "blue_white")], now)).toBeNull();
    const t = weeklyText([e(1, "blue_white"), e(2, null), e(9, "garage")], now)!;
    expect(t).toContain("2 חניות");
    expect(t).toContain("1 חניות לא סומנו");
  });
});

describe("city data fallback", () => {
  it("uses the repo snapshot when the city GIS is down", async () => {
    const { loadData, data, dataSource, SNAPSHOT } = await import("../supabase/functions/parking/tools.ts");
    const { readFileSync } = await import("node:fs");
    const real = globalThis.fetch;
    globalThis.fetch = (async (input: any) => {
      const u = String(input);
      if (u.startsWith(SNAPSHOT)) return new Response(readFileSync(`data/${u.split("/").pop()}`, "utf8"));
      throw new Error("GIS down");
    }) as typeof fetch;
    try {
      await loadData(true);
    } finally { globalThis.fetch = real; }
    expect(data.zones.length).toBeGreaterThan(5);
    expect(dataSource.zones).toBe("snapshot");
    expect(dataSource.nightLots).toBe("snapshot");
  });
});
