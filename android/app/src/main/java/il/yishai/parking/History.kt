package il.yishai.parking

import android.content.Context
import android.location.Geocoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID

/** Parking types the user marks after parking, each shown as a picture of the sign/curb. */
enum class SpotType(val key: String, val label: String) {
    BLUE_WHITE("blue_white", "כחול-לבן"),
    PERMIT_ONLY("permit_only", "לבעלי תו אזורי"),
    UNMARKED("unmarked", "ללא סימון (חינם)"),
    GARAGE("garage", "חניון"),
    CLOSE_TO_HOME("close_to_home", "חניון קרוב לבית"),
    SHORT_TERM("short_term", "קצרת מועד"),
    PRIVATE("private", "חניה פרטית"),
    OTHER("other", "אחר");

    companion object { fun of(k: String?) = entries.firstOrNull { it.key == k } }
}

/** How long the search took — the signal for "where is it worth looking". */
enum class SearchTime(val key: String, val label: String, val minutes: Int) {
    NONE("0", "מיד", 0),
    SHORT("5", "עד 5 ד׳", 3),
    MID("15", "5–15 ד׳", 10),
    LONG("30", "מעל 15 ד׳", 20);

    companion object { fun of(k: String?) = entries.firstOrNull { it.key == k } }
}

data class ParkingEvent(
    val id: String,
    val lat: Double,
    val lon: Double,
    val time: Long,
    val source: String,
    val zone: Int?,
    val street: String?,
    val type: String?,
    val search: String?,
    val synced: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("lat", lat); put("lon", lon); put("time", time); put("source", source)
        put("zone", zone ?: JSONObject.NULL); put("street", street ?: JSONObject.NULL)
        put("type", type ?: JSONObject.NULL); put("search", search ?: JSONObject.NULL); put("synced", synced)
    }

    companion object {
        private fun JSONObject.optStr(k: String) = if (isNull(k)) null else optString(k)
        fun fromJson(o: JSONObject) = ParkingEvent(
            o.getString("id"), o.getDouble("lat"), o.getDouble("lon"), o.getLong("time"), o.optString("source"),
            if (o.isNull("zone")) null else o.getInt("zone"), o.optStr("street"), o.optStr("type"), o.optStr("search"),
            o.optBoolean("synced", false),
        )
    }
}

/** Append-only-ish local history in a JSON file (hundreds to a few thousand rows). */
class HistoryStore(ctx: Context) {
    private val file = File(ctx.applicationContext.filesDir, "history.json")

    @Synchronized fun all(): List<ParkingEvent> = try {
        val a = JSONArray(file.readText())
        (0 until a.length()).map { ParkingEvent.fromJson(a.getJSONObject(it)) }.sortedByDescending { it.time }
    } catch (_: Exception) { emptyList() }

    @Synchronized private fun write(list: List<ParkingEvent>) {
        val tmp = File(file.parentFile, "history.json.tmp")
        tmp.writeText(JSONArray().apply { list.forEach { put(it.toJson()) } }.toString())
        tmp.renameTo(file)
    }

    @Synchronized fun add(e: ParkingEvent) = write(all() + e)
    @Synchronized fun get(id: String) = all().firstOrNull { it.id == id }
    @Synchronized fun update(id: String, f: (ParkingEvent) -> ParkingEvent) = write(all().map { if (it.id == id) f(it) else it })
    @Synchronized fun delete(id: String) = write(all().filterNot { it.id == id })
}

object Parking {
    /** Single entry point for every save (manual, Bluetooth, Android Auto). */
    fun record(ctx: Context, lat: Double, lon: Double, source: String): ParkingEvent {
        val app = ctx.applicationContext
        LastSpotStore(app).save(lat, lon, source)
        val repo = ParkingRepository(app)
        val zone = repo.zoneAt(lat, lon)
        val e = ParkingEvent(UUID.randomUUID().toString(), lat, lon, System.currentTimeMillis(), source, zone, repo.streetNear(lat, lon), null, null)
        val history = HistoryStore(app)
        history.add(e)
        Reminders.schedule(app, repo.rules, zone)
        if (source != "manual") Notifications.askType(app, e.id)
        CoroutineScope(Dispatchers.IO).launch {
            if (e.street == null) streetAt(app, lat, lon)?.let { s -> history.update(e.id) { it.copy(street = s) } }
            Sync.push(app)
        }
        return e
    }

    @Suppress("DEPRECATION")
    private fun streetAt(ctx: Context, lat: Double, lon: Double): String? = try {
        Geocoder(ctx, Locale("he")).getFromLocation(lat, lon, 1)?.firstOrNull()?.thoroughfare
    } catch (_: Exception) { null }
}
