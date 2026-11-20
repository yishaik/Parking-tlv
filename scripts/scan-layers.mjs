// Lists every layer in every Tel Aviv GIS MapServer and flags parking-related ones.
const root = "https://gisn.tel-aviv.gov.il/arcgis/rest/services";
const services = ["IView2", "IView2MapHeb", "IView2RekaHeb", "IView2____test", "WM/IView2WM", "WM/IView2MapHebWM", "WM/TA5500WM", "WM/TA5500WM2026", "WM/IView2WM____test"];
const words = /חני|כחול|לבן|הסדר|מדרכ|תמרור|תושב|מועדפ|סימון|parking|park/i;
const out = [];
for (const s of services) {
  try {
    const j = await (await fetch(`${root}/${s}/MapServer?f=json`)).json();
    for (const l of j.layers ?? []) {
      if (words.test(l.name)) out.push(`${s}\t${l.id}\t${l.name}\t${l.geometryType ?? ""}`);
    }
    console.error(`${s}: ${(j.layers ?? []).length} layers`);
  } catch (e) { console.error(`${s}: ERROR ${e}`); }
}
console.log(out.join("\n"));
