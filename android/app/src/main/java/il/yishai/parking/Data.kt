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
}

class ParkingRepository(private val context: Context) {
    private fun asset(name: String) = context.assets.open(name).bufferedReader().use { it.readText() }

    val rules: Rules by lazy { Rules.parse(JSONObject(asset("rules.json"))) }

    val zones: List<Zone> by lazy {
        val features = JSONObject(asset("zones.json")).getJSONArray("features")
        (0 until features.length()).map { i ->
            val f = features.getJSONObject(i)
            val rings = f.getJSONObject("geometry").getJSONArray("rings")
            Zone(
                f.getJSONObject("attributes").getInt("ms_ezor"),
                (0 until rings.length()).map { r ->
                    val ring = rings.getJSONArray(r)
                    (0 until ring.length()).map { p ->
                        val pt = ring.getJSONArray(p)
                        doubleArrayOf(pt.getDouble(0), pt.getDouble(1))
                    }
                },
            )
        }
    }

    fun zoneAt(lat: Double, lon: Double): Int? = zones.firstOrNull { Geo.pointInRings(lon, lat, it.rings) }?.number

    val nightLots: List<NightLot> by lazy {
        val features = JSONObject(asset("nightLots.json")).getJSONArray("features")
        (0 until features.length()).mapNotNull { i ->
            val f = features.getJSONObject(i)
            val g = f.optJSONObject("geometry") ?: return@mapNotNull null
            val a = f.getJSONObject("attributes")
            NightLot(a.str("shem"), a.str("ktovet"), g.getDouble("y"), g.getDouble("x"), a.str("taarif_layla"), a.str("hearot_atar"))
        }
    }

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
            parseGarages(JSONObject(asset("garages.json")).getJSONArray("features")) to false
        }
    }

    private fun parseGarages(features: JSONArray): List<Garage> = (0 until features.length()).mapNotNull { i ->
        val a = features.getJSONObject(i).getJSONObject("attributes")
        val lat = a.optDouble("lat")
        val lon = a.optDouble("lon")
        if (lat.isNaN() || lon.isNaN()) null
        else Garage(a.str("shem_chenyon"), a.str("ktovet"), lat, lon, a.str("status_chenyon"), a.str("taarif_yom"), a.str("taarif_layla"), a.str("hearot_taarif"))
    }
}

private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key, "").trim()
