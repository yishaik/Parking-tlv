import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { evaluateStreetParking, type RulesConfig } from "../src/rules.js";
import { haversineMeters, pointInRings } from "../src/geo.js";

const cfg: RulesConfig = JSON.parse(readFileSync("config/rules.json", "utf8"));
// 2026-11-22 is a Sunday, 2026-11-27 a Friday, 2026-11-28 a Saturday.

describe("evaluateStreetParking", () => {
  it("is free all day in own zone", () => {
    const r = evaluateStreetParking(cfg, 9, new Date("2026-11-22T10:00"), 600);
    expect(r.costILS).toBe(0);
  });

  it("center zone: 2 free hours then 30% off 12.40", () => {
    const r = evaluateStreetParking(cfg, 7, new Date("2026-11-22T10:00"), 240);
    expect(r.freeMinutes).toBe(120);
    expect(r.paidMinutes).toBe(120);
    expect(r.costILS).toBe(17.36); // 2h * 12.4 * 0.7
    expect(r.paidFrom).toBe("2026-11-22T12:00");
  });

  it("center zone stops charging at 21:00 Sun-Thu", () => {
    const r = evaluateStreetParking(cfg, 7, new Date("2026-11-22T20:00"), 120, 120);
    expect(r.paidMinutes).toBe(60);
  });

  it("outer zone stops charging at 19:00 and has unknown rate", () => {
    const r = evaluateStreetParking(cfg, 4, new Date("2026-11-22T18:00"), 120, 120);
    expect(r.paidMinutes).toBe(60);
    expect(r.costILS).toBeNull();
  });

  it("Friday ends at 17:00, Saturday is free", () => {
    expect(evaluateStreetParking(cfg, 7, new Date("2026-11-27T16:00"), 120, 120).paidMinutes).toBe(60);
    expect(evaluateStreetParking(cfg, 7, new Date("2026-11-28T10:00"), 600).paidMinutes).toBe(0);
  });

  it("honours the free-parking override", () => {
    const r = evaluateStreetParking({ ...cfg, freeParkingOverride: true }, 7, new Date("2026-11-22T10:00"), 600);
    expect(r.costILS).toBe(0);
  });

  it("unknown outside any zone", () => {
    expect(evaluateStreetParking(cfg, null, new Date("2026-11-22T10:00"), 60).costILS).toBeNull();
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
