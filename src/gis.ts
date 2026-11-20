// Tel Aviv municipal ArcGIS server (public, no key). Layer ids verified 2026-11-20.
export const GIS_BASE = "https://gisn.tel-aviv.gov.il/arcgis/rest/services/IView2/MapServer";

export const LAYERS = {
  zones: 544, // אזורי חניה (polygons, ms_ezor = zone number)
  garages: 970, // חניוני אחוזות החוף (points, status_chenyon = occupancy)
  nightLots: 488, // חניוני לילה לתושבים / קרוב לבית
  publicLots: 556,
  privateLots: 555,
  autotel: 602,
  motorcycles: 951,
} as const;

export interface Feature {
  attributes: Record<string, unknown>;
  geometry?: { x?: number; y?: number; rings?: [number, number][][] };
}

/** Fetch every feature of a layer in WGS84, paging past the 2000-record limit. */
export async function fetchLayer(layerId: number, pageSize = 1000): Promise<Feature[]> {
  const out: Feature[] = [];
  for (let offset = 0; ; offset += pageSize) {
    const params = new URLSearchParams({
      where: "1=1",
      outFields: "*",
      outSR: "4326",
      returnGeometry: "true",
      resultOffset: String(offset),
      resultRecordCount: String(pageSize),
      f: "json",
    });
    const res = await fetch(`${GIS_BASE}/${layerId}/query?${params}`);
    if (!res.ok) throw new Error(`layer ${layerId}: HTTP ${res.status}`);
    const body = (await res.json()) as { features?: Feature[]; exceededTransferLimit?: boolean; error?: { message: string } };
    if (body.error) throw new Error(`layer ${layerId}: ${body.error.message}`);
    out.push(...(body.features ?? []));
    if (!body.exceededTransferLimit) break;
  }
  return out;
}
