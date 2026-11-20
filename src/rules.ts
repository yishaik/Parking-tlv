export interface RulesConfig {
  profile: { hasPermit: boolean; permitZone: number | null };
  /** Set true when the city announces free blue-white parking (e.g. emergency). */
  freeParkingOverride: boolean;
  outsideZoneFreeMinutesPerDay: number;
  residentDiscountOutsideZone: number;
  paidWindow: { from: string; to: string };
  /** Visitor street rate per hour in ILS for the zone; null = not yet verified/configured. */
  visitorHourlyILS: number | null;
}

export interface Evaluation {
  allowed: true;
  /** Minutes that cost nothing (own-zone permit, outside paid window, or the daily free allowance). */
  freeMinutes: number;
  paidMinutes: number;
  /** null when the rate is unknown; never guessed. */
  costILS: number | null;
  notes: string[];
}

const toMin = (hhmm: string) => {
  const [h, m] = hhmm.split(":").map(Number);
  return h * 60 + m;
};

/**
 * Blue-white street parking cost for a permit holder.
 * Verified policy (tel-aviv.gov.il, Feb 2025): free all day in own zone;
 * outside it 2 cumulative free hours/day, then 30% off the visitor rate.
 * `start` is interpreted in local time fields (getHours/getMinutes).
 */
export function evaluateStreetParking(
  cfg: RulesConfig,
  zone: number | null,
  start: Date,
  durationMinutes: number,
  freeMinutesUsedToday = 0,
): Evaluation {
  const notes: string[] = [];
  if (cfg.freeParkingOverride) {
    return { allowed: true, freeMinutes: durationMinutes, paidMinutes: 0, costILS: 0, notes: ["Free-parking override is on"] };
  }
  const inOwnZone = cfg.profile.hasPermit && zone !== null && zone === cfg.profile.permitZone;
  if (inOwnZone) {
    return { allowed: true, freeMinutes: durationMinutes, paidMinutes: 0, costILS: 0, notes: [`Own zone (${zone}): free with permit`] };
  }

  const from = toMin(cfg.paidWindow.from);
  const to = toMin(cfg.paidWindow.to);
  let allowance = Math.max(0, cfg.outsideZoneFreeMinutesPerDay - freeMinutesUsedToday);
  let free = 0;
  let paid = 0;
  for (let i = 0; i < durationMinutes; i++) {
    const t = new Date(start.getTime() + i * 60000);
    const minuteOfDay = t.getHours() * 60 + t.getMinutes();
    if (minuteOfDay < from || minuteOfDay >= to) free++;
    else if (allowance > 0) {
      allowance--;
      free++;
    } else paid++;
  }

  const rate = cfg.visitorHourlyILS;
  let cost: number | null = null;
  if (paid === 0) cost = 0;
  else if (rate !== null) {
    const factor = cfg.profile.hasPermit ? 1 - cfg.residentDiscountOutsideZone : 1;
    cost = Math.round((paid / 60) * rate * factor * 100) / 100;
  } else notes.push("Visitor hourly rate for this zone is not configured, cost unknown");
  if (zone === null) notes.push("Point is outside all mapped parking zones");
  return { allowed: true, freeMinutes: free, paidMinutes: paid, costILS: cost, notes };
}
