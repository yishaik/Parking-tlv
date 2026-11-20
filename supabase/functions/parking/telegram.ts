// Telegram bot for the owner: where is my car, where to park near an address (optionally at a time), weekly summary.
// The first chat that sends /start after setup becomes the only chat the bot answers.
import { israelNow, mapsLink, parkingNearDestination, stats, TYPE_LABELS, type Curb, type Event, type Garage } from "./tools.ts";
import type { Notice } from "./notices.ts";

export interface BotDeps {
  setting<T>(key: string, fallback: T): Promise<T>;
  setSetting(key: string, value: unknown): Promise<void>;
  events(limit?: number): Promise<Event[]>;
  curbs(): Promise<Curb[]>;
  geocode(address: string): Promise<{ lat: number; lon: number; label: string } | null>;
  liveGarages(): Promise<{ garages: Garage[]; live: boolean }>;
  notices(): Promise<Notice[]>;
}

export async function send(token: string, chatId: number | string, text: string) {
  await fetch(`https://api.telegram.org/bot${token}/sendMessage`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ chat_id: chatId, text, disable_web_page_preview: true }),
  });
}

const HELP = [
  "מה אפשר לשאול:",
  "• כתובת בתל אביב, למשל: דיזנגוף 50  או  דיזנגוף 50 ב-20:00",
  "• שליחת מיקום (📎 ← מיקום) כדי לקבל המלצה ליד הנקודה",
  "• /car  איפה הרכב",
  "• /week  סיכום השבוע",
  "• /news  חדשות חניה רלוונטיות",
].join("\n");

const hhmm = (d: Date) => `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;

/** "דיזנגוף 50 ב-20:00" -> { address, arrival } (time today, or tomorrow if already past). */
function parseQuery(text: string): { address: string; arrival?: Date } {
  let t = text.replace(/^(איפה (לחנות|כדאי לחנות)|חניה|חנייה)\s*(ליד|ב)?\s*/u, "").trim();
  const m = t.match(/(?:ב-?|בשעה\s*)?(\d{1,2}):(\d{2})/);
  let arrival: Date | undefined;
  if (m) {
    const now = israelNow();
    arrival = new Date(now);
    arrival.setHours(Number(m[1]), Number(m[2]), 0, 0);
    if (arrival.getTime() < now.getTime() - 30 * 60_000) arrival.setDate(arrival.getDate() + 1);
    t = t.replace(m[0], "").trim();
  }
  return { address: t.replace(/[,،]\s*$/, ""), arrival };
}

export function formatPlan(r: ReturnType<typeof parkingNearDestination>, label: string): string {
  const lines = [`🅿️ ${label} · ${hhmm(new Date(r.arrival))}`];
  const s = r.street;
  lines.push(s.isPermitZone ? `כחול-לבן: אזור ${s.zone}, האזור שלך. חינם, בלי אפליקציה.`
    : s.onPermitZoneBoundary ? "רחוב גובל של האזור שלך: חינם, אבל להפעיל פנגו מהדקה הראשונה."
    : s.zone === null ? "מחוץ לאזורי החניה של תל אביב."
    : `כחול-לבן: אזור ${s.zone}. ${s.freeMinutes} דק׳ חינם, ${s.paidMinutes} בתשלום${s.costILS != null ? ` (₪${s.costILS})` : ""}${s.appRequired ? ". חובה פנגו." : "."}`);
  lines.push("", "הכי משתלם:");
  r.options.slice(0, 3).forEach((o, i) => lines.push(`${i + 1}. ${o.summary}\n   ${o.navigate}`));
  if (r.loadingSignsNearby.length) lines.push("", `⚠️ פריקה וטעינה ב-${r.loadingSignsNearby[0].distanceMeters} מ׳: ${r.loadingSignsNearby[0].text}`);
  if (r.autotelReservedNearby.length) lines.push("⚠️ יש ליד היעד חניות אוטותל (אסור).");
  const marks = Object.entries(r.yourCurbMarksNearby).slice(0, 4).map(([st, t]) => `${st}: ${Object.entries(t).map(([k, n]) => `${k}×${n}`).join(", ")}`);
  if (marks.length) lines.push("", "הסימונים שלך באזור:", ...marks);
  const fast = r.yourHistoryNearby.fastestStreets.slice(0, 2).map((f) => `${f.street} (~${f.averageSearchMinutes} ד׳)`);
  if (fast.length) lines.push(`מצאת מהר בעבר: ${fast.join(", ")}`);
  return lines.join("\n");
}

export async function handleUpdate(update: any, deps: BotDeps): Promise<void> {
  const token = await deps.setting<string | null>("telegramToken", null);
  if (!token) return;
  const msg = update.message ?? update.edited_message;
  if (!msg) return;
  const chatId = msg.chat.id;
  const owner = await deps.setting<number | null>("telegramChat", null);
  const text: string = (msg.text ?? "").trim();
  if (owner == null) {
    if (text.startsWith("/start")) {
      await deps.setSetting("telegramChat", chatId);
      await send(token, chatId, `מחובר. מעכשיו אענה רק לצ׳אט הזה.\n\n${HELP}`);
    }
    return;
  }
  if (chatId !== owner) return;

  try {
    if (msg.location) {
      const [{ garages }, events, curbs] = await Promise.all([deps.liveGarages(), deps.events(), deps.curbs()]);
      const assigned = await deps.setting<string[]>("assignedLots", []);
      const r = parkingNearDestination({ lat: msg.location.latitude, lon: msg.location.longitude }, israelNow(), 120, garages, events, curbs, assigned);
      return send(token, chatId, formatPlan(r, "המיקום ששלחת"));
    }
    if (/^\/(start|help)/.test(text) || text === "עזרה") return send(token, chatId, HELP);
    if (/^\/car|איפה הרכב/.test(text)) {
      const e = (await deps.events(1))[0];
      if (!e) return send(token, chatId, "אין מקום חניה שמור.");
      const when = israelNow(new Date(e.time));
      return send(token, chatId, `🚗 ${e.street ?? "מיקום שמור"}${e.zone ? ` · אזור ${e.zone}` : ""} · ${when.getDate()}/${when.getMonth() + 1} ${hhmm(when)}${e.type ? ` · ${TYPE_LABELS[e.type] ?? e.type}` : ""}\nהליכה: ${mapsLink(e.lat, e.lon, "walking")}`);
    }
    if (/^\/week|סיכום/.test(text)) return send(token, chatId, await weeklyText(deps));
    if (/^\/news|חדשות/.test(text)) {
      const n = await deps.notices();
      return send(token, chatId, n.length ? n.map((x) => `• ${x.title} (${x.source})\n  ${x.link}`).join("\n") : "אין כרגע חדשות חניה רלוונטיות.");
    }
    const q = parseQuery(text);
    const dest = await deps.geocode(q.address);
    if (!dest) return send(token, chatId, `לא מצאתי את "${q.address}" בכתובות של תל אביב. כתוב רחוב ומספר, למשל: דיזנגוף 50\n\n${HELP}`);
    const [{ garages }, events, curbs] = await Promise.all([deps.liveGarages(), deps.events(), deps.curbs()]);
    const assigned = await deps.setting<string[]>("assignedLots", []);
    const r = parkingNearDestination(dest, q.arrival ?? israelNow(), 120, garages, events, curbs, assigned);
    await send(token, chatId, formatPlan(r, dest.label));
  } catch (e) {
    await send(token, chatId, `משהו נכשל: ${String(e).slice(0, 200)}`);
  }
}

export async function weeklyText(deps: Pick<BotDeps, "events">): Promise<string> {
  const since = Date.now() - 7 * 86_400_000;
  const week = (await deps.events()).filter((e) => e.time >= since);
  if (!week.length) return "לא נשמרו חניות השבוע.";
  const s = stats(week);
  const lines = [`📊 סיכום החניה השבועי: ${s.parkings} חניות`];
  if (s.averageSearchMinutes != null) lines.push(`חיפוש ממוצע: ${s.averageSearchMinutes} דקות`);
  const types = Object.entries(s.byType).sort((a, b) => b[1] - a[1]).map(([k, n]) => `${k} ${n}`).join(", ");
  if (types) lines.push(`סוגים: ${types}`);
  if (s.fastestStreets.length) lines.push(`הכי מהר: ${s.fastestStreets.slice(0, 3).map((f) => `${f.street} (~${f.averageSearchMinutes} ד׳)`).join(", ")}`);
  const tod = Object.entries(s.byTimeOfDay).map(([b, v]) => `${b} ~${v.averageSearchMinutes} ד׳`).join(", ");
  if (tod) lines.push(`לפי שעה: ${tod}`);
  const unmarked = week.filter((e) => !e.type).length;
  if (unmarked) lines.push(`${unmarked} חניות לא סומנו.`);
  return lines.join("\n");
}
