import { describe, expect, it } from "vitest";
import { garagesNear, israelNow, parkingNearDestination, parseLocal, snapshotGarages, stats, streetCost, zoneAt, type Event } from "../src/tools";

const NORDAU = { lat: 32.0916827757229, lon: 34.78161161459743 };

describe("server tools", () => {
  it("knows the owner's zone", () => {
    expect(zoneAt(NORDAU.lat, NORDAU.lon)).toBe(9);
    expect(streetCost(NORDAU.lat, NORDAU.lon, parseLocal("2026-11-22T10:00"), 120).costILS).toBe(0);
  });

  it("parses Israel local time without shifting", () => {
    expect(parseLocal("2026-11-22T18:30").getHours()).toBe(18);
    const winter = israelNow(new Date("2026-11-22T16:30:00Z"));
    expect(winter.getHours()).toBe(18); // UTC+2 in November
  });

  it("lists garages near home sorted by distance", () => {
    const g = garagesNear(snapshotGarages(), NORDAU.lat, NORDAU.lon, 800);
    expect(g.length).toBeGreaterThan(0);
    expect(g[0].distanceMeters).toBeLessThanOrEqual(g.at(-1)!.distanceMeters);
    expect(g[0].navigate).toContain("google.com/maps");
  });

  it("ranks free street parking first in the permit zone", () => {
    const r = parkingNearDestination(NORDAU, parseLocal("2026-11-22T10:00"), 120, snapshotGarages(), []);
    expect(r.options[0].kind).toBe("street");
    expect(r.options[0].costILS).toBe(0);
  });

  it("computes stats from history", () => {
    const ev = (street: string, search: string, type: string): Event => ({ id: street + search, lat: NORDAU.lat, lon: NORDAU.lon, time: Date.parse("2026-11-20T17:00:00Z"), source: "manual", zone: 9, street, type, search });
    const s = stats([ev("נורדאו", "0", "blue_white"), ev("נורדאו", "5", "blue_white"), ev("אוסישקין", "30", "garage")]);
    expect(s.parkings).toBe(3);
    expect(s.fastestStreets[0].street).toBe("נורדאו");
    expect(s.byType["כחול-לבן"]).toBe(2);
  });
});
