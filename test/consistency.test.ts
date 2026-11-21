import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";

// The Supabase function ships copies of the shared modules and a generated
// rules config. These checks fail if one side was edited without the other.
const read = (p: string) => readFileSync(p, "utf8");

describe("server copies stay in sync", () => {
  for (const f of ["rules.ts", "geo.ts"]) {
    it(`supabase/functions/parking/${f} matches src/${f}`, () => {
      expect(read(`supabase/functions/parking/${f}`)).toBe(read(`src/${f}`));
    });
  }
  it("rules_config.ts is generated from config/rules.json and config/holidays.json", () => {
    const rules = JSON.parse(read("config/rules.json"));
    delete rules._sources;
    const holidays = JSON.parse(read("config/holidays.json")).days;
    const src = read("supabase/functions/parking/rules_config.ts");
    const json = src.slice(src.indexOf("export default ") + "export default ".length, src.lastIndexOf(";"));
    expect(JSON.parse(json)).toEqual({ ...rules, holidays });
  });
});
