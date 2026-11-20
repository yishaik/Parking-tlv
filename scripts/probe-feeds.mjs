// One-off: find sources for school lots, city events and free-parking announcements.
import { mkdir, writeFile } from "node:fs/promises";
const UA = { "User-Agent": "Mozilla/5.0 (parking-tlv probe)" };
const out = {};
const get = async (u, opt = {}) => { const r = await fetch(u, { headers: UA, signal: AbortSignal.timeout(30000), ...opt }); return { status: r.status, text: await r.text() }; };

// 1. SharePoint list behind the school-lots table
try {
  const { text } = await get("https://www.tel-aviv.gov.il/Residents/Transportation/Pages/ParkingArrangements.aspx");
  out.pageApiHints = [...new Set([...text.matchAll(/.{0,80}(_api\/web\/lists[^"'\s]*|ListId[^,]{0,60}|listGuid[^,]{0,60}|lists\/getbytitle\([^)]*\)|ListName[^,]{0,60}|"WebUrl"[^,]{0,80}|webId[^,]{0,60}).{0,80}/gi)].map((m) => m[0]))].slice(0, 60);
  out.pageScripts = [...new Set([...text.matchAll(/src="([^"]+\.js[^"]*)"/g)].map((m) => m[1]))].slice(0, 40);
  out.angularHints = [...new Set([...text.matchAll(/.{0,100}(fli\.|fp\.|linksClient|tableClient|col\.Caption|ng-init|data-list|listId|ListID).{0,140}/g)].map((m) => m[0]))].slice(0, 40);
} catch (e) { out.page = String(e); }

// 2. API portal: CityPOI / events
try {
  const { text } = await get("https://apiportal.tel-aviv.gov.il/docs/services");
  out.apiportalLinks = [...new Set([...text.matchAll(/href="([^"]+)"/g)].map((m) => m[1]))].filter((h) => /poi|event|swagger|api|docs/i.test(h)).slice(0, 60);
} catch (e) { out.apiportal = String(e); }
for (const u of ["https://api.tel-aviv.gov.il/citypoi/events", "https://api.tel-aviv.gov.il/CityPOI/api/events", "https://apiportal.tel-aviv.gov.il/docs/services/CityPOI", "https://apiportal.tel-aviv.gov.il/api/docs/services/CityPOI"]) {
  try { const r = await get(u); out[u] = `${r.status} ${r.text.slice(0, 300)}`; } catch (e) { out[u] = String(e); }
}

// 3. Google News RSS for free-parking announcements and Yarkon/port events
for (const q of ["תל אביב חניה חינם כחול לבן", "פארק הירקון הופעה", "נמל תל אביב אירוע"]) {
  try {
    const { status, text } = await get(`https://news.google.com/rss/search?q=${encodeURIComponent(q)}&hl=he&gl=IL&ceid=IL:he`);
    out[`news:${q}`] = { status, items: [...text.matchAll(/<item>[\s\S]*?<title>([\s\S]*?)<\/title>[\s\S]*?<pubDate>([\s\S]*?)<\/pubDate>/g)].slice(0, 8).map((m) => `${m[2]} | ${m[1]}`) };
  } catch (e) { out[`news:${q}`] = String(e); }
}

// 4. City events pages
for (const u of ["https://www.tel-aviv.gov.il/Pages/Events.aspx", "https://www.tel-aviv.gov.il/Residents/Culture/Pages/Events.aspx", "https://www.tel-aviv.gov.il/_api/web/lists?$select=Title,Id&$top=200"]) {
  try { const r = await get(u, { headers: { ...UA, Accept: "application/json;odata=verbose" } }); out[u] = `${r.status} ${r.text.slice(0, 1500)}`; } catch (e) { out[u] = String(e); }
}

await mkdir("data/probe", { recursive: true });
await writeFile("data/probe/feeds.json", JSON.stringify(out, null, 1));
console.log(JSON.stringify(out, null, 1).slice(0, 3000));
