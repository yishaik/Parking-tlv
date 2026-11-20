// Tel Aviv municipal ArcGIS server (public, no key). Layer ids verified 2026-11-20.
export const GIS_BASE = "https://gisn.tel-aviv.gov.il/arcgis/rest/services/IView2/MapServer";

export const LAYERS = {
  zones: 544, // אזורי חניה (polygons, ms_ezor = zone number)
  garages: 970, // חניוני אחוזות החוף (points, status_chenyon = occupancy)
  nightLots: 488, // חניוני לילה לתושבים / קרוב לבית
  publicLots: 556, // חניונים ציבוריים (licensed public lots, incl. non-Ahuzot)
  privateLots: 555,
  autotel: 602, // Autotel car-share reserved spots (no parking for others)
  loadingSigns: 449, // תמרורי פריקה וטעינה, with sign text
  streets: 507, // צירי רחוב (polylines)
  motorcycles: 951,
} as const;

/** Zone 9 bounding box with a small margin (WGS84). */
export const ZONE9_BBOX = [34.759, 32.071, 34.786, 32.107] as const;

export interface Feature {
  attributes: Record<string, unknown>;
  geometry?: { x?: number; y?: number; rings?: [number, number][][]; paths?: [number, number][][] };
}

/** Fetch every feature of a layer in WGS84, paging past the transfer limit. Optional bbox and field list. */
export async function fetchLayer(layerId: number, opts: { bbox?: readonly number[]; fields?: string } = {}, pageSize = 1000): Promise<Feature[]> {
  const out: Feature[] = [];
  for (let offset = 0; ; offset += pageSize) {
    const params = new URLSearchParams({
      where: "1=1",
      outFields: opts.fields ?? "*",
      outSR: "4326",
      returnGeometry: "true",
      resultOffset: String(offset),
      resultRecordCount: String(pageSize),
      f: "json",
    });
    if (opts.bbox) {
      params.set("geometry", opts.bbox.join(","));
      params.set("geometryType", "esriGeometryEnvelope");
      params.set("inSR", "4326");
      params.set("spatialRel", "esriSpatialRelIntersects");
    }
    const res = await fetch(`${GIS_BASE}/${layerId}/query?${params}`);
    if (!res.ok) throw new Error(`layer ${layerId}: HTTP ${res.status}`);
    const body = (await res.json()) as { features?: Feature[]; exceededTransferLimit?: boolean; error?: { message: string } };
    if (body.error) throw new Error(`layer ${layerId}: ${body.error.message}`);
    out.push(...(body.features ?? []));
    if (!body.exceededTransferLimit) break;
  }
  return out;
}
