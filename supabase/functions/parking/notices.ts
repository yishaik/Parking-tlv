// Parking-relevant news (Google News, Hebrew): free-parking announcements, price changes, events in zone 9.
// News is a signal for the agent to mention, never applied to the rules automatically.
export interface Notice { kind: "free_parking" | "price_change" | "event"; title: string; source: string; link: string; publishedAt: string }

const QUERIES: [Notice["kind"], string, RegExp, number][] = [
  ["free_parking", "תל אביב חניה חינם כחול לבן", /חניה חינם|חנייה חינם|ללא תשלום|בחינם בכחול/, 3],
  ["price_change", "תל אביב תעריף חניה כחול לבן", /מתייקר|העלא|תעריף|יזנקו|מחיר/, 14],
  ["event", 'פארק הירקון OR "נמל תל אביב" OR "גני התערוכה" OR "אקספו תל אביב" אירוע', /הופע|פסטיבל|אירוע|מופע|תערוכ|יריד|מרתון|משחק/, 3],
];

let cache: { at: number; items: Notice[] } | null = null;

export async function fetchNotices(): Promise<Notice[]> {
  if (cache && Date.now() - cache.at < 3600_000) return cache.items;
  const out: Notice[] = [];
  await Promise.all(QUERIES.map(async ([kind, q, words, days]) => {
    try {
      const r = await fetch(`https://news.google.com/rss/search?q=${encodeURIComponent(q)}&hl=he&gl=IL&ceid=IL:he`, { signal: AbortSignal.timeout(8000) });
      const xml = await r.text();
      for (const m of xml.matchAll(/<item>([\s\S]*?)<\/item>/g)) {
        const tag = (t: string) => (m[1].match(new RegExp(`<${t}>([\\s\\S]*?)</${t}>`))?.[1] ?? "").replace(/^<!\[CDATA\[|\]\]>$/g, "").trim();
        const title = tag("title");
        const pub = new Date(tag("pubDate"));
        if (isNaN(pub.getTime()) || Date.now() - pub.getTime() > days * 86_400_000 || !words.test(title)) continue;
        const cut = title.lastIndexOf(" - ");
        out.push({ kind, title: cut > 0 ? title.slice(0, cut) : title, source: cut > 0 ? title.slice(cut + 3) : "", link: tag("link"), publishedAt: pub.toISOString() });
      }
    } catch { /* skip this query */ }
  }));
  const seen = new Set<string>();
  const items = out.filter((n) => !seen.has(n.title) && seen.add(n.title)).sort((a, b) => b.publishedAt.localeCompare(a.publishedAt)).slice(0, 8);
  cache = { at: Date.now(), items };
  return items;
}
