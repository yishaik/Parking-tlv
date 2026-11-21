package il.yishai.parking

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Curb marking the user reports on the map. Colors follow the real curb paint where there is one. */
enum class CurbType(val key: String, val label: String, val color: Long) {
    BLUE_WHITE("blue_white", "כחול-לבן", 0xFF1E5AA8),
    PREFERRED("preferred", "מועדפת לתושבי האזור", 0xFF7C3AED),
    GRAY("gray", "אפור / ללא סימון", 0xFF9CA3AF),
    RED_WHITE("red_white", "אדום-לבן (אסור)", 0xFFDC2626),
    SHORT_TERM("short_term", "קצרת מועד", 0xFF0EA5E9),
    LOADING("loading", "פריקה וטעינה", 0xFFF59E0B),
    DISABLED("disabled", "נכים", 0xFF14B8A6),
    BUS_TAXI("bus_taxi", "תחנת אוטובוס / מונית", 0xFFEAB308),
    DRIVEWAY("driveway", "כניסה לחניה / חסום", 0xFF4B5563),
    SCHOOL_LOT("school_lot", "חניון בית ספר (אחרי הלימודים)", 0xFF10B981);

    companion object {
        fun of(k: String?) = entries.firstOrNull { it.key == k }

        /** Parking types that tell us what the curb looks like. */
        fun fromSpot(t: SpotType?) = when (t) {
            SpotType.BLUE_WHITE -> BLUE_WHITE
            SpotType.PERMIT_ONLY -> PREFERRED
            SpotType.UNMARKED -> GRAY
            SpotType.SHORT_TERM -> SHORT_TERM
            SpotType.RED_WHITE -> RED_WHITE
            SpotType.LOADING -> LOADING
            else -> null
        }
    }
}

data class CurbMark(
    val id: String,
    val lat: Double,
    val lon: Double,
    val type: String,
    val street: String?,
    val time: Long,
    val source: String,
    val synced: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("lat", lat); put("lon", lon); put("type", type); put("street", street ?: JSONObject.NULL)
        put("time", time); put("source", source); put("synced", synced)
    }

    companion object {
        fun fromJson(o: JSONObject) = CurbMark(
            o.getString("id"), o.getDouble("lat"), o.getDouble("lon"), o.getString("type"),
            if (o.isNull("street")) null else o.optString("street"), o.getLong("time"), o.optString("source"), o.optBoolean("synced"),
        )
    }
}

class CurbStore(ctx: Context) {
    private val file = JsonListFile(File(ctx.applicationContext.filesDir, "curbs.json"))

    fun all(): List<CurbMark> = synchronized(LOCK) { (file.read() ?: JSONArray()).mapSafe { CurbMark.fromJson(it) } }

    private fun write(list: List<CurbMark>) = synchronized(LOCK) { file.write(JSONArray().apply { list.forEach { put(it.toJson()) } }) }

    /** A new mark replaces older ones within 8 m (the curb was re-checked). */
    fun add(lat: Double, lon: Double, type: CurbType, street: String?, source: String): CurbMark = synchronized(LOCK) {
        val m = CurbMark(UUID.randomUUID().toString(), lat, lon, type.key, street, System.currentTimeMillis(), source)
        val replaced = all().filter { Geo.distance(it.lat, it.lon, lat, lon) < 8 }
        write(all().filterNot { it in replaced } + m)
        if (replaced.isNotEmpty()) prefs.edit().putStringSet("deleted", tombstones() + replaced.map { it.id }).apply()
        m
    }

    private val prefs = ctx.applicationContext.getSharedPreferences("curbs", Context.MODE_PRIVATE)

    /** Deleted ids not yet removed on the server. */
    fun tombstones(): Set<String> = prefs.getStringSet("deleted", emptySet())!!.toSet()
    fun clearTombstones(ids: Set<String>) = prefs.edit().putStringSet("deleted", tombstones() - ids).apply()

    fun delete(id: String) = synchronized(LOCK) {
        write(all().filterNot { it.id == id })
        prefs.edit().putStringSet("deleted", tombstones() + id).apply()
    }
    fun markSynced(ids: Set<String>) = synchronized(LOCK) { write(all().map { if (it.id in ids) it.copy(synced = true) else it }) }

    private companion object { val LOCK = Any() }
}
