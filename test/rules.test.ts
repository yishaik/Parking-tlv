import { describe, expect, it } from "vitest";
import { evaluateStreetParking, type RulesConfig } from "../src/rules.js";
import { haversineMeters, pointInRings } from "../src/geo.js";

const cfg: RulesConfig = {
  profile: { hasPermit: true, permitZone: 9 },
  freeParkingOverride: false,
  outsideZoneFreeMinutesPerDay: 120,
  residentDiscountOutsideZone: 0.3,
  paidWindow: { from: "08:00", to: "24:00" },
  visitorHourlyILS: 12,
};

describe("evaluateStreetParking", () => {
  it("is free all day in own zone", () => {
    const r = evaluateStreetParking(cfg, 9, new Date("2026-11-20T10:00"), 600);
    expect(r.costILS).toBe(0);
    expect(r.paidMinutes).toBe(0);
  });

  it("gives 2 free hours outside the zone then 30% off", () => {
    const r = evaluateStreetParking(cfg, 7, new Date("2026-11-20T10:00"), 240);
    expect(r.freeMinutes).toBe(120);
    expect(r.paidMinutes).toBe(120);
    expect(r.costILS).toBe(16.8); // 2h * 12 * 0.7
  });

  it("counts already-used free minutes", () => {
    const r = evaluateStreetParking(cfg, 7, new Date("2026-11-20T10:00"), 60, 120);
    expect(r.paidMinutes).toBe(60);
  });

  it("does not charge outside the paid window", () => {
    const r = evaluateStreetParking(cfg, 7, new Date("2026-11-20T02:00"), 300);
    expect(r.paidMinutes).toBe(0);
  });

  it("never invents a cost when the rate is unknown", () => {
    const r = evaluateStreetParking({ ...cfg, visitorHourlyILS: null }, 7, new Date("2026-11-20T10:00"), 240);
    expect(r.costILS).toBeNull();
  });

  it("honours the free-parking override", () => {
    const r = evaluateStreetParking({ ...cfg, freeParkingOverride: true }, 7, new Date("2026-11-20T10:00"), 600);
    expect(r.costILS).toBe(0);
  });
});

describe("geo", () => {
  const square: [number, number][] = [[0, 0], [0, 2], [2, 2], [2, 0], [0, 0]];
  it("detects inside/outside", () => {
    expect(pointInRings(1, 1, [square])).toBe(true);
    expect(pointInRings(3, 1, [square])).toBe(false);
  });
  it("computes distance", () => {
    expect(Math.round(haversineMeters(32.08, 34.78, 32.08, 34.79))).toBeGreaterThan(900);
  });
});
