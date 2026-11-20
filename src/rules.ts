type Window = [string, string] | null;

export interface ZoneGroup {
  name: string;
  zones: number[];
  /** Visitor street rate per hour in ILS; null = not verified, cost is reported as unknown. */
  visitorHourlyILS: number | null;
  paid: { sunThu: Window; fri: Window; sat: Window };
}

export interface RulesConfig {
  profile: { hasPermit: boolean; permitZone: number | null };
  /** Set true when the city announces free blue-white parking (e.g. emergency). */
  freeParkingOverride: boolean;
  outsideZoneFreeMinutesPerDay: number;
  residentDiscountOutsideZone: number;
  zoneGroups: ZoneGroup[];
}

export interface Evaluation {
  allowed: true;
  /** Minutes that cost nothing (own-zone permit, outside paid hours, or the daily free allowance). */
  freeMinutes: number;
  paidMinutes: number;
  /** null when the rate is unknown; never guessed. */
  costILS: number | null;
  /** First minute (local ISO) at which paying would start, if any. */
  paidFrom: string | null;
  notes: string[];
}

const toMin = (hhmm: string) => {
  const [h, m] = hhmm.split(":").map(Number);
  return h * 60 + m;
};

export function groupFor(cfg: RulesConfig, zone: number | null): ZoneGroup | null {
  return zone === null ? null : (cfg.zoneGroups.find((g) => g.zones.includes(zone)) ?? null);
}

/** Is this local minute inside the paid window? Uses local time fields of `t`. */
export function isPaidMinute(group: ZoneGroup, t: Date): boolean {
  const day = t.getDay(); // 0 = Sunday
  const w = day === 6 ? group.paid.sat : day === 5 ? group.paid.fri : group.paid.sunThu;
  if (!w) return false;
  const m = t.getHours() * 60 + t.getMinutes();
  return m >= toMin(w[0]) && m < toMin(w[1]);
}

const localIso = (t: Date) =>
  `${t.getFullYear()}-${String(t.getMonth() + 1).padStart(2, "0")}-${String(t.getDate()).padStart(2, "0")}T${String(t.getHours()).padStart(2, "0")}:${String(t.getMinutes()).padStart(2, "0")}`;

/**
 * Blue-white street parking cost for the configured driver.
 * Policy (tel-aviv.gov.il, Feb 2025): permit holders park free all day in their own zone;
 * elsewhere 2 cumulative free hours per day, then 30% off the visitor rate.
 */
export function evaluateStreetParking(
  cfg: RulesConfig,
  zone: number | null,
  start: Date,
  durationMinutes: number,
  freeMinutesUsedToday = 0,
): Evaluation {
  const notes: string[] = [];
  const all = (n: string): Evaluation => ({ allowed: true, freeMinutes: durationMinutes, paidMinutes: 0, costILS: 0, paidFrom: null, notes: [n] });
  if (cfg.freeParkingOverride) return all("Free-parking override is on");
  if (cfg.profile.hasPermit && zone !== null && zone === cfg.profile.permitZone) return all(`Own zone (${zone}): free with permit`);

  const group = groupFor(cfg, zone);
  if (!group) {
    return { allowed: true, freeMinutes: 0, paidMinutes: 0, costILS: null, paidFrom: null, notes: ["Point is outside all mapped parking zones; rules unknown"] };
  }

  let allowance = cfg.profile.hasPermit ? Math.max(0, cfg.outsideZoneFreeMinutesPerDay - freeMinutesUsedToday) : 0;
  let free = 0;
  let paid = 0;
  let paidFrom: string | null = null;
  for (let i = 0; i < durationMinutes; i++) {
    const t = new Date(start.getTime() + i * 60000);
    if (!isPaidMinute(group, t)) free++;
    else if (allowance > 0) {
      allowance--;
      free++;
    } else {
      paid++;
      paidFrom ??= localIso(t);
    }
  }

  let cost: number | null = null;
  if (paid === 0) cost = 0;
  else if (group.visitorHourlyILS !== null) {
    const factor = cfg.profile.hasPermit ? 1 - cfg.residentDiscountOutsideZone : 1;
    cost = Math.round((paid / 60) * group.visitorHourlyILS * factor * 100) / 100;
  } else notes.push(`Visitor rate for zone group '${group.name}' is not verified; cost unknown`);
  notes.push("Holiday eves are not modelled (paid hours end 17:00 like Friday)");
  return { allowed: true, freeMinutes: free, paidMinutes: paid, costILS: cost, paidFrom, notes };
}
