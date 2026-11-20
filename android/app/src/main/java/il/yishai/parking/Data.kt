package il.yishai.parking

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

const val GIS_BASE = "https://gisn.tel-aviv.gov.il/arcgis/rest/services/IView2/MapServer"

data class Garage(
    val name: String,
    val address: String,
    val lat: Double,
    val lon: Double,
    val status: String,
    val dayTariff: String,
    val nightTariff: String,
    val notes: String,
    val distance: Double = Double.NaN,
)

data class NightLot(
    val name: String,
    val address: String,
    val lat: Double,
    val lon: Double,
    val nightTariff: String,
    val notes: String,
    val distance: Double = Double.NaN,
)

/** Licensed public lot (city layer 556) or an OpenStreetMap parking amenity. */
data class OtherLot(
    val name: String,
    val address: String,
    val lat: Double,
    val lon: Double,
    val spots: Int?,
    /** true = free, false = paid, null = unknown */
    val free: Boolean?,
    val kind: String,
    val source: String,
    val distance: Double = Double.NaN,
)

data class LoadingSign(val lat: Double, val lon: Double, val text: String)
data class PointOnly(val lat: Double, val lon: Double, val label: String)
class Street(val name: String, val paths: List<List<DoubleArray>>)

fun statusLabel(s: String): String = when (s) {
    "פנוי" -> "יש מקום"
    "מעט" -> "מעט מקום"
    "מלא" -> "מלא"
    "סגור" -> "סגור"
    "פעיל" -> "פתוח · אין נתוני תפוסה"
    else -> "אין מידע"
}

data class NamedPlace(val label: String, val lat: Double, val lon: Double)

class Zone(val number: Int, val rings: List<List<DoubleArray>>)

fun distanceText(meters: Double): String = when {
    meters.isNaN() -> ""
    meters < 1000 -> "${meters.roundToInt()} מ׳"
    else -> String.format(java.util.Locale.US, "%.1f ק״מ", meters / 1000)
}

object Geo {
    fun pointInRings(lon: Double, lat: Double, rings: List<List<DoubleArray>>): Boolean {
        var inside = false
        for (ring in rings) {
            var j = ring.size - 1
            for (i in ring.indices) {
                val (xi, yi) = ring[i][0] to ring[i][1]
                val (xj, yj) = ring[j][0] to ring[j][1]
                if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) inside = !inside
                j = i
            }
        }
        return inside
    }

    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
        return 2 * r * asin(sqrt(a))
    }

    /** Shortest distance (m) from a point to a set of polylines/rings, local equirectangular projection. */
    fun distanceToLines(lat: Double, lon: Double, lines: List<List<DoubleArray>>): Double {
        val kx = 111320.0 * cos(Math.toRadians(lat))
        val ky = 110540.0
        var best = Double.MAX_VALUE
        for (line in lines) for (i in 1 until line.size) {
            val ax = (line[i - 1][0] - lon) * kx; val ay = (line[i - 1][1] - lat) * ky
            val bx = (line[i][0] - lon) * kx; val by = (line[i][1] - lat) * ky
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            best = minOf(best, hypot(ax + t * dx, ay + t * dy))
        }
        return best
    }
}

class ParkingRepository(private val context: Context) {
    private fun asset(name: String) = context.assets.open(name).bufferedReader().use { it.readText() }
    private fun features(name: String): JSONArray = try { JSONObject(asset(name)).getJSONArray("features") } catch (_: Exception) { JSONArray() }

    private val baseRules: Rules by lazy {
        Rules.parse(JSONObject(asset("rules.json")), try { JSONObject(asset("holidays.json")) } catch (_: Exception) { null })
    }

    /** Config rules, plus the user-confirmed free-parking switch when it is on. */
    val rules: Rules get() = baseRules.withOverride(baseRules.freeOverride || FreeOverride.until(context) != null)

    val zones: List<Zone> by lazy {
        val fs = features("zones.json")
        (0 until fs.length()).map { i ->
            val f = fs.getJSONObject(i)
            Zone(f.getJSONObject("attributes").getInt("ms_ezor"), lines(f.getJSONObject("geometry").getJSONArray("rings")))
        }
    }

    private fun lines(a: JSONArray): List<List<DoubleArray>> = (0 until a.length()).map { r ->
        val ring = a.getJSONArray(r)
        (0 until ring.length()).map { p -> val pt = ring.getJSONArray(p); doubleArrayOf(pt.getDouble(0), pt.getDouble(1)) }
    }

    fun zoneAt(lat: Double, lon: Double): Int? = zones.firstOrNull { Geo.pointInRings(lon, lat, it.rings) }?.number

    /** Outside the permit zone but on its edge: a boundary street (free for its permit holders via app). */
    fun onPermitBoundary(lat: Double, lon: Double): Boolean {
        val pz = rules.permitZone ?: return false
        val zone = zones.firstOrNull { it.number == pz } ?: return false
        if (Geo.pointInRings(lon, lat, zone.rings)) return false
        return Geo.distanceToLines(lat, lon, zone.rings) <= rules.boundaryMeters
    }

    val permitZoneRings: List<List<DoubleArray>> get() = zones.firstOrNull { it.number == rules.permitZone }?.rings ?: emptyList()

    val nightLots: List<NightLot> by lazy {
        val fs = features("nightLots.json")
        (0 until fs.length()).mapNotNull { i ->
            val f = fs.getJSONObject(i)
            val g = f.optJSONObject("geometry") ?: return@mapNotNull null
            val a = f.getJSONObject("attributes")
            NightLot(a.str("shem"), a.str("ktovet"), g.getDouble("y"), g.getDouble("x"), a.str("taarif_layla"), a.str("hearot_atar"))
        }
    }

    /** City public lots (556) plus OSM parking amenities that are not private. */
    val otherLots: List<OtherLot> by lazy {
        val out = mutableListOf<OtherLot>()
        val pub = features("publicLots.json")
        for (i in 0 until pub.length()) {
            val f = pub.getJSONObject(i)
            val g = f.optJSONObject("geometry") ?: continue
            val a = f.getJSONObject("attributes")
            out += OtherLot(a.str("name").ifBlank { a.str("address") }, a.str("address"), g.getDouble("y"), g.getDouble("x"),
                a.optInt("num_vehicles").takeIf { it > 0 }, null, "חניון ציבורי", "עירייה")
        }
        try {
            val el = JSONObject(asset("osmParking.json")).getJSONArray("elements")
            for (i in 0 until el.length()) {
                val e = el.getJSONObject(i)
                val t = e.optJSONObject("tags") ?: continue
                if (t.optString("amenity") != "parking" || t.optString("access") in setOf("private", "no", "customers")) continue
                val (lat, lon) = if (e.has("lat")) e.getDouble("lat") to e.getDouble("lon")
                else e.optJSONObject("center")?.let { it.getDouble("lat") to it.getDouble("lon") } ?: continue
                if (out.any { Geo.distance(it.lat, it.lon, lat, lon) < 40 }) continue
                val kind = when (t.optString("parking")) { "underground" -> "חניון תת-קרקעי"; "multi-storey" -> "חניון קומות"; "street_side" -> "מפרץ חניה"; else -> "חניון" }
                out += OtherLot(t.optString("name").ifBlank { kind }, "", lat, lon, t.optString("capacity").toIntOrNull(),
                    when (t.optString("fee")) { "no" -> true; "yes" -> false; else -> null }, kind, "OSM")
            }
        } catch (_: Exception) {}
        out
    }

    val loadingSigns: List<LoadingSign> by lazy {
        val fs = features("loadingSigns.json")
        (0 until fs.length()).mapNotNull { i ->
            val f = fs.getJSONObject(i)
            val g = f.optJSONObject("geometry") ?: return@mapNotNull null
            val a = f.getJSONObject("attributes")
            if (a.str("status").let { it.isNotBlank() && it != "קיים" }) return@mapNotNull null
            LoadingSign(g.getDouble("y"), g.getDouble("x"), a.str("traffic_sign_text").replace("\r", "").lines().filter { it.isNotBlank() }.joinToString(" · "))
        }
    }

    fun loadingSignsNear(lat: Double, lon: Double, radius: Double = 80.0): List<Pair<Int, LoadingSign>> =
        loadingSigns.map { Geo.distance(lat, lon, it.lat, it.lon).roundToInt() to it }.filter { it.first <= radius }.sortedBy { it.first }

    val autotel: List<PointOnly> by lazy {
        val fs = features("autotel.json")
        (0 until fs.length()).mapNotNull { i ->
            val a = fs.getJSONObject(i).getJSONObject("attributes")
            val lat = a.optDouble("lat"); val lon = a.optDouble("lon")
            if (lat.isNaN() || lon.isNaN()) null else PointOnly(lat, lon, a.str("address0"))
        }
    }

    val streets: List<Street> by lazy {
        val fs = features("streets.json")
        (0 until fs.length()).mapNotNull { i ->
            val f = fs.getJSONObject(i)
            val paths = f.optJSONObject("geometry")?.optJSONArray("paths") ?: return@mapNotNull null
            Street(f.getJSONObject("attributes").str("t_rechov"), lines(paths))
        }
    }

    /** Nearest street name within [maxMeters], from the city street centrelines. */
    fun streetNear(lat: Double, lon: Double, maxMeters: Double = 30.0): String? =
        streets.map { it to Geo.distanceToLines(lat, lon, it.paths) }.filter { it.second <= maxMeters && it.first.name.length > 1 }
            .minByOrNull { it.second }?.first?.name

    val places: List<NamedPlace> by lazy {
        val a = JSONArray(asset("places.json"))
        (0 until a.length()).map { val o = a.getJSONObject(it); NamedPlace(o.getString("label"), o.getDouble("lat"), o.getDouble("lon")) }
    }

    /** Live garage list from the city GIS (layer 970); falls back to the bundled snapshot. Second = live? */
    suspend fun garages(): Pair<List<Garage>, Boolean> = withContext(Dispatchers.IO) {
        try {
            val fields = "shem_chenyon,ktovet,lat,lon,status_chenyon,taarif_yom,taarif_layla,hearot_taarif"
            val url = "$GIS_BASE/970/query?where=1%3D1&returnGeometry=false&f=json&outFields=" + URLEncoder.encode(fields, "UTF-8")
            val conn = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000 }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            parseGarages(JSONObject(body).getJSONArray("features")) to true
        } catch (e: Exception) {
            parseGarages(features("garages.json")) to false
        }
    }

    private fun parseGarages(features: JSONArray): List<Garage> = (0 until features.length()).mapNotNull { i ->
        val a = features.getJSONObject(i).getJSONObject("attributes")
        val lat = a.optDouble("lat")
        val lon = a.optDouble("lon")
        if (lat.isNaN() || lon.isNaN() || "נסגר" in a.str("taarif_yom") + a.str("hearot_taarif")) null
        else Garage(a.str("shem_chenyon"), a.str("ktovet"), lat, lon, a.str("status_chenyon"), a.str("taarif_yom"), a.str("taarif_layla"), a.str("hearot_taarif"))
    }
}

private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key, "").trim()
