// Writes android/app/src/test/resources/rules_vectors.json: street-parking cases with the answers of
// src/rules.ts. Both the TypeScript tests and the Kotlin unit tests (Rules.kt) must give the same answers.
// Rerun after a deliberate rules change: npx tsx scripts/gen-rule-vectors.ts
process.env.TZ = "UTC";
import { readFileSync, writeFileSync } from "node:fs";
import { evaluateStreetParking, type RulesConfig } from "../src/rules.ts";

const rules = JSON.parse(readFileSync("config/rules.json", "utf8"));
const cfg: RulesConfig = { ...rules, holidays: JSON.parse(readFileSync("config/holidays.json", "utf8")).days };

const cases: { name: string; zone: number | null; start: string; minutes: number; used?: number; boundary?: boolean }[] = [
  { name: "own zone, weekday morning", zone: 9, start: "2026-11-22T10:00", minutes: 120 },
  { name: "center zone, weekday, past the free two hours", zone: 6, start: "2026-11-22T10:00", minutes: 180 },
  { name: "center zone, evening, paid hour covered by the free allowance", zone: 6, start: "2026-11-22T20:00", minutes: 120 },
  { name: "center zone, Saturday", zone: 6, start: "2026-11-21T10:00", minutes: 120 },
  { name: "center zone, Friday afternoon", zone: 6, start: "2026-11-20T16:00", minutes: 180 },
  { name: "outer zone, allowance mostly used", zone: 4, start: "2026-11-23T18:00", minutes: 120, used: 100 },
  { name: "boundary street of the permit zone", zone: 6, start: "2026-11-22T10:00", minutes: 120, boundary: true },
  { name: "outside every zone", zone: null, start: "2026-11-22T10:00", minutes: 60 },
  { name: "holiday eve after Friday hours", zone: 6, start: "2027-04-21T18:00", minutes: 60 },
  { name: "yom tov", zone: 6, start: "2027-04-22T10:00", minutes: 120 },
  { name: "early morning into paid hours", zone: 6, start: "2026-11-22T07:30", minutes: 60 },
  { name: "no permit allowance left", zone: 7, start: "2026-11-24T12:00", minutes: 90, used: 120 },
];

const out = cases.map((c) => {
  const e = evaluateStreetParking(cfg, c.zone, new Date(c.start), c.minutes, c.used ?? 0, c.boundary ?? false);
  return { ...c, used: c.used ?? 0, boundary: c.boundary ?? false,
    expect: { freeMinutes: e.freeMinutes, paidMinutes: e.paidMinutes, costILS: e.costILS, paidFrom: e.paidFrom, appRequired: e.appRequired } };
});
writeFileSync("android/app/src/test/resources/rules_vectors.json", JSON.stringify(out, null, 1) + "\n");
console.log(`${out.length} vectors`);
