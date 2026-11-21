// Linking an agent host (Morty) to this server: what it is told, and what
// lands in its notification feed.
import { TOOLS } from "./catalog.ts";
import { stats, type Event } from "./tools.ts";
import type { Notice } from "./notices.ts";

export const LINK_NAME = "parking";
export const LINK_TITLE = "חניה בתל אביב";

/** Telegram /start payload Morty understands: link-sb-<project ref>-<function>-<code>. */
export function linkPayload(host: string, code: string): string {
  return `link-sb-${host.split(".")[0]}-${LINK_NAME}-${code}`;
}

export function newCode(): string {
  const abc = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
  return [...crypto.getRandomValues(new Uint8Array(16))].map((b) => abc[b % abc.length]).join("");
}

export function newToken(): string {
  return [...crypto.getRandomValues(new Uint8Array(24))].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** How an agent should use this service. The host adds the auth header line itself. */
export function guide(): string {
  const args = (t: (typeof TOOLS)[number]) => {
    const p = (t.inputSchema as { properties?: Record<string, unknown> }).properties ?? {};
    return Object.keys(p).join(", ");
  };
  return [
    "Tel Aviv parking for the owner. He holds a zone 9 resident permit (home Nordau 86, also Usishkin 54).",
    "Use it whenever he asks where to park, what parking costs, where his car is, or about parking news in Tel Aviv.",
    "Call: POST <base>/api/tool/<tool> with a JSON object of arguments (compact JSON comes back).",
    "Addresses are Hebrew street + house number, e.g. דיזנגוף 50. Times are Israel local time, e.g. 2026-11-21T20:00.",
    "Tools:",
    ...TOOLS.map((t) => `- ${t.name}(${args(t)}): ${t.description}`),
    "How to answer: cheapest legal option first; say plainly whether Pango must be started; include the navigation link;",
    "costILS null means the price is not verified, never invent one; news is a signal, not the rule.",
  ].join("\n");
}

export const HELLO = "מחובר לחניה בתל אביב. אפשר לשאול למשל: איפה לחנות ליד דיזנגוף 50 ב-20:00, איפה הרכב, יש חדשות חניה? התראות מהאפליקציה יגיעו לכאן.";

export function weeklyText(events: Event[], now = Date.now()): string | null {
  const week = events.filter((e) => e.time >= now - 7 * 86_400_000);
  if (!week.length) return null;
  const s = stats(week);
  const lines = [`📊 סיכום החניה השבועי: ${s.parkings} חניות`];
  if (s.averageSearchMinutes != null) lines.push(`חיפוש ממוצע: ${s.averageSearchMinutes} דקות`);
  const types = Object.entries(s.byType).sort((a, b) => b[1] - a[1]).map(([k, n]) => `${k} ${n}`).join(", ");
  if (types) lines.push(`סוגים: ${types}`);
  if (s.fastestStreets.length) lines.push(`הכי מהר: ${s.fastestStreets.slice(0, 3).map((f) => `${f.street} (~${f.averageSearchMinutes} ד׳)`).join(", ")}`);
  const unmarked = week.filter((e) => !e.type).length;
  if (unmarked) lines.push(`${unmarked} חניות לא סומנו באפליקציה.`);
  return lines.join("\n");
}

/** News worth pushing: free parking and price changes not pushed before. Events stay in the app. */
export function freshNews(items: Notice[], seen: string[]): { push: { text: string; risk: string }[]; seen: string[] } {
  const known = new Set(seen);
  const push = items
    .filter((n) => (n.kind === "free_parking" || n.kind === "price_change") && !known.has(n.link))
    .map((n) => ({
      text: `${n.kind === "free_parking" ? "🅿️ חניה חינם?" : "💸 שינוי מחיר חניה?"} ${n.title}${n.source ? ` (${n.source})` : ""}\n${n.link}`,
      risk: n.kind === "free_parking" ? "medium" : "low",
    }));
  return { push, seen: [...items.map((n) => n.link), ...seen].filter((v, i, a) => a.indexOf(v) === i).slice(0, 200) };
}
