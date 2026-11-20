// Generates config/holidays.json: Israeli yom tov days and their eves (for blue-white paid hours).
import { writeFile } from "node:fs/promises";
import { HebrewCalendar, flags } from "@hebcal/core";

const start = new Date().getFullYear();
const days: Record<string, "erev" | "yomtov"> = {};
for (let y = start; y <= start + 3; y++) {
  for (const ev of HebrewCalendar.calendar({ year: y, il: true, noMinorFast: true, noModern: true, noRoshChodesh: true, noSpecialShabbat: true })) {
    const d = ev.getDate().greg();
    const iso = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
    if (ev.getFlags() & flags.CHAG) days[iso] = "yomtov";
  }
}
// Holiday eve = the day before a yom tov that is not itself a yom tov (Erev Purim etc. are not "ערב חג").
for (const iso of Object.keys(days)) {
  const d = new Date(`${iso}T12:00:00Z`);
  d.setUTCDate(d.getUTCDate() - 1);
  const prev = d.toISOString().slice(0, 10);
  if (!days[prev]) days[prev] = "erev";
}
const sorted = Object.fromEntries(Object.entries(days).sort());
await writeFile("config/holidays.json", JSON.stringify({ generatedAt: new Date().toISOString().slice(0, 10), source: "@hebcal/core, Israel schedule", days: sorted }, null, 1));
console.log(Object.entries(sorted).slice(0, 30).map(([d, k]) => `${d} ${k}`).join("\n"));
