import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { closeToHomeFree, evaluateStreetParking, type RulesConfig } from "../src/rules.js";
import { distanceToRingsMeters, haversineMeters, pointInRings } from "../src/geo.js";

const cfg: RulesConfig = { ...JSON.parse(readFileSync("config/rules.json", "utf8")), holidays: JSON.parse(readFileSync("config/holidays.json", "utf8")).days };
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

  it("outer zone stops charging at 19:00, permit rate 4.90", () => {
    const r = evaluateStreetParking(cfg, 4, new Date("2026-11-22T18:00"), 120, 120);
    expect(r.paidMinutes).toBe(60);
    expect(r.costILS).toBe(4.9);
  });

  it("holiday eve uses Friday hours, yom tov is free", () => {
    // 2027-04-21 erev Pesach (Wednesday), 2027-04-22 Pesach I
    expect(evaluateStreetParking(cfg, 7, new Date("2027-04-21T16:00"), 120, 120).paidMinutes).toBe(60);
    expect(evaluateStreetParking(cfg, 7, new Date("2027-04-22T10:00"), 300).paidMinutes).toBe(0);
  });

  it("boundary street of the permit zone is free but needs the app", () => {
    const r = evaluateStreetParking(cfg, 7, new Date("2026-11-22T10:00"), 300, 0, true);
    expect(r.costILS).toBe(0);
    expect(r.appRequired).toBe(true);
  });

  it("close-to-home lot windows", () => {
    expect(closeToHomeFree(new Date("2026-11-22T20:00"), cfg.holidays)).toBe(true); // Sun night
    expect(closeToHomeFree(new Date("2026-11-22T12:00"), cfg.holidays)).toBe(false); // Sun noon
    expect(closeToHomeFree(new Date("2026-11-27T15:30"), cfg.holidays)).toBe(true); // Fri afternoon
    expect(closeToHomeFree(new Date("2026-11-28T12:00"), cfg.holidays)).toBe(true); // Saturday
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
  it("distance to polygon edge", () => {
    const sq: [number, number][] = [[34.78, 32.08], [34.78, 32.09], [34.79, 32.09], [34.79, 32.08], [34.78, 32.08]];
    expect(Math.round(distanceToRingsMeters(32.085, 34.7801, [sq]))).toBeLessThan(15);
    expect(distanceToRingsMeters(32.085, 34.785, [sq])).toBeGreaterThan(400);
  });
  it("computes distance", () => {
    expect(Math.round(haversineMeters(32.08, 34.78, 32.08, 34.79))).toBeGreaterThan(900);
  });
});
