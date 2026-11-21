import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { evaluateStreetParking, type RulesConfig } from "../src/rules.js";

// The same cases are checked against the Kotlin port in android/app/src/test (RulesVectorsTest).
const vectors = JSON.parse(readFileSync("android/app/src/test/resources/rules_vectors.json", "utf8"));
const rules = JSON.parse(readFileSync("config/rules.json", "utf8"));
const cfg: RulesConfig = { ...rules, holidays: JSON.parse(readFileSync("config/holidays.json", "utf8")).days };

describe("shared rule vectors (TypeScript)", () => {
  for (const v of vectors) {
    it(v.name, () => {
      const e = evaluateStreetParking(cfg, v.zone, new Date(v.start), v.minutes, v.used, v.boundary);
      expect({ freeMinutes: e.freeMinutes, paidMinutes: e.paidMinutes, costILS: e.costILS, paidFrom: e.paidFrom, appRequired: e.appRequired }).toEqual(v.expect);
    });
  }
});
