type Window = [string, string] | null;

export interface ZoneGroup {
  name: string;
  zones: number[];
  /** Visitor street rate per hour in ILS; null = not verified, cost is reported as unknown. */
  visitorHourlyILS: number | null;
  paid: { sunThu: Window; fri: Window; sat: Window };
}

export type HolidayKind = "erev" | "yomtov";

export interface RulesConfig {
  profile: { hasPermit: boolean; permitZone: number | null };
  /** Set true when the city announces free blue-white parking (e.g. emergency). */
  freeParkingOverride: boolean;
  outsideZoneFreeMinutesPerDay: number;
  residentDiscountOutsideZone: number;
  /** A point this close to the permit zone's edge is treated as a boundary street. */
  boundaryMeters?: number;
  zoneGroups: ZoneGroup[];
  /** YYYY-MM-DD -> erev | yomtov (Israel). */
  holidays?: Record<string, HolidayKind>;
}

export interface Evaluation {
  allowed: true;
  /** Minutes that cost nothing (own-zone permit, outside paid window, or the daily free allowance). */
  freeMinutes: number;
  paidMinutes: number;
  /** null when the rate is unknown; never guessed. */
  costILS: number | null;
  /** First minute (local ISO) at which paying would start, if any. */
  paidFrom: string | null;
  /** Whether a parking app (Pango / Cellopark) must be started, even when the parking is free. */
  appRequired: boolean;
  notes: string[];
}

const toMin = (hhmm: string) => {
  const [h, m] = hhmm.split(":").map(Number);
  return h * 60 + m;
};

export const localDate = (t: Date) =>
  `${t.getFullYear()}-${String(t.getMonth() + 1).padStart(2, "0")}-${String(t.getDate()).padStart(2, "0")}`;
const localIso = (t: Date) => `${localDate(t)}T${String(t.getHours()).padStart(2, "0")}:${String(t.getMinutes()).padStart(2, "0")}`;

export function groupFor(cfg: RulesConfig, zone: number | null): ZoneGroup | null {
  return zone === null ? null : (cfg.zoneGroups.find((g) => g.zones.includes(zone)) ?? null);
}

/** Which weekly window applies on this date: Saturday/yom tov, Friday/holiday eve, or a weekday. */
export function dayKind(t: Date, holidays?: Record<string, HolidayKind>): "sat" | "fri" | "sunThu" {
  const h = holidays?.[localDate(t)];
  if (t.getDay() === 6 || h === "yomtov") return "sat";
  if (t.getDay() === 5 || h === "erev") return "fri";
  return "sunThu";
}

/** Is this local minute inside the paid window? Uses local time fields of `t`. */
export function isPaidMinute(group: ZoneGroup, t: Date, holidays?: Record<string, HolidayKind>): boolean {
  const w = group.paid[dayKind(t, holidays)];
  if (!w) return false;
  const m = t.getHours() * 60 + t.getMinutes();
  return m >= toMin(w[0]) && m < toMin(w[1]);
}

/** "Close to home" lots: free for assigned permit holders Sun-Thu 19:00-08:00, Fri/eve from 15:00, Saturday and yom tov. */
export function closeToHomeFree(t: Date, holidays?: Record<string, HolidayKind>): boolean {
  const k = dayKind(t, holidays);
  const h = t.getHours();
  if (k === "sat" || h < 8) return true;
  return k === "fri" ? h >= 15 : h >= 19;
}

/**
 * Blue-white street parking cost for the configured driver.
 * Policy (tel-aviv.gov.il, Feb 2025): permit holders park free all day in their own zone and on boundary streets
 * of their zone (via app); elsewhere 2 cumulative free hours per day via app, then 30% off the visitor rate.
 */
export function evaluateStreetParking(
  cfg: RulesConfig,
  zone: number | null,
  start: Date,
  durationMinutes: number,
  freeMinutesUsedToday = 0,
  onPermitBoundary = false,
): Evaluation {
  const notes: string[] = [];
  const all = (n: string, appRequired = false): Evaluation => ({ allowed: true, freeMinutes: durationMinutes, paidMinutes: 0, costILS: 0, paidFrom: null, appRequired, notes: [n] });
  if (cfg.freeParkingOverride) return all("Free-parking override is on");
  if (cfg.profile.hasPermit && zone !== null && zone === cfg.profile.permitZone) return all(`Own zone (${zone}): free with permit, no app needed`);
  if (cfg.profile.hasPermit && onPermitBoundary) {
    return all(`Boundary street of zone ${cfg.profile.permitZone}: free for its permit holders, but start a parking app from the first minute`, true);
  }

  const group = groupFor(cfg, zone);
  if (!group) {
    return { allowed: true, freeMinutes: 0, paidMinutes: 0, costILS: null, paidFrom: null, appRequired: false, notes: ["Point is outside all mapped parking zones; rules unknown"] };
  }

  let allowance = cfg.profile.hasPermit ? Math.max(0, cfg.outsideZoneFreeMinutesPerDay - freeMinutesUsedToday) : 0;
  let free = 0;
  let paid = 0;
  let paidFrom: string | null = null;
  let paidWindowTouched = false;
  for (let i = 0; i < durationMinutes; i++) {
    const t = new Date(start.getTime() + i * 60000);
    if (!isPaidMinute(group, t, cfg.holidays)) free++;
    else {
      paidWindowTouched = true;
      if (allowance > 0) {
        allowance--;
        free++;
      } else {
        paid++;
        paidFrom ??= localIso(t);
      }
    }
  }

  let cost: number | null = null;
  if (paid === 0) cost = 0;
  else if (group.visitorHourlyILS !== null) {
    const factor = cfg.profile.hasPermit ? 1 - cfg.residentDiscountOutsideZone : 1;
    cost = Math.round((paid / 60) * group.visitorHourlyILS * factor * 100) / 100;
  } else notes.push(`Visitor rate for zone group '${group.name}' is not verified; cost unknown`);
  if (paidWindowTouched) {
    notes.push("Start Pango/Cellopark when parking: the free hours only count through a parking app");
    notes.push("Some blue-white segments are 'preferred parking' for that zone's permit holders only: check the sign");
  }
  return { allowed: true, freeMinutes: free, paidMinutes: paid, costILS: cost, paidFrom, appRequired: paidWindowTouched, notes };
}
