package il.yishai.parking

import android.content.Context
import android.location.Geocoder
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID

/** Parking types the user marks after parking, each shown as a picture of the sign/curb. */
enum class SpotType(val key: String, val label: String) {
    BLUE_WHITE("blue_white", "כחול-לבן"),
    PERMIT_ONLY("permit_only", "מועדפת / תו אזורי"),
    UNMARKED("unmarked", "אפור / ללא סימון"),
    RED_WHITE("red_white", "אדום-לבן"),
    LOADING("loading", "פריקה וטעינה"),
    GARAGE("garage", "חניון"),
    CLOSE_TO_HOME("close_to_home", "חניון קרוב לבית"),
    SHORT_TERM("short_term", "קצרת מועד"),
    PRIVATE("private", "חניה פרטית"),
    OTHER("other", "אחר");

    companion object {
        fun of(k: String?) = entries.firstOrNull { it.key == k }
        /** Curb colors shown first in the picker; the rest are off-street options. */
        val curbs = listOf(BLUE_WHITE, UNMARKED, PERMIT_ONLY, SHORT_TERM, RED_WHITE, LOADING)
        val offStreet = listOf(GARAGE, CLOSE_TO_HOME, PRIVATE, OTHER)
    }
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

/** Local history in a JSON file (hundreds to a few thousand rows). A bad row is skipped, never the whole file. */
class HistoryStore(ctx: Context) {
    private val file = JsonListFile(File(ctx.applicationContext.filesDir, "history.json"))

    fun all(): List<ParkingEvent> = synchronized(LOCK) {
        (file.read() ?: JSONArray()).mapSafe { ParkingEvent.fromJson(it) }.sortedByDescending { it.time }
    }

    private fun write(list: List<ParkingEvent>) = synchronized(LOCK) { file.write(JSONArray().apply { list.forEach { put(it.toJson()) } }) }

    fun add(e: ParkingEvent) = synchronized(LOCK) { write(all() + e) }
    fun get(id: String) = all().firstOrNull { it.id == id }
    fun update(id: String, f: (ParkingEvent) -> ParkingEvent) = synchronized(LOCK) { write(all().map { if (it.id == id) f(it) else it }) }
    fun delete(id: String) = synchronized(LOCK) { write(all().filterNot { it.id == id }) }

    // One lock for every instance: receivers, workers and the UI each create their own store.
    private companion object { val LOCK = Any() }
}

object Parking {
    /** Single entry point for every save (manual, Bluetooth, Android Auto). */
    fun record(ctx: Context, lat: Double, lon: Double, source: String): ParkingEvent {
        val app = ctx.applicationContext
        LastSpotStore(app).save(lat, lon, source)
        val repo = ParkingRepository.get(app)
        val zone = repo.zoneAt(lat, lon)
        val history = HistoryStore(app)
        val used = freeMinutesUsedToday(repo.rules, history.all())
        val e = ParkingEvent(UUID.randomUUID().toString(), lat, lon, System.currentTimeMillis(), source, zone, repo.streetNear(lat, lon), null, null)
        history.add(e)
        Reminders.schedule(app, repo.rules, zone, repo.onPermitBoundary(lat, lon), used)
        if (source != "manual") Notifications.askType(app, e.id, e.street)
        AppScope.io.launch {
            if (e.street == null) streetAt(app, lat, lon)?.let { s -> history.update(e.id) { it.copy(street = s) } }
            try { ParkingWidget().updateAll(app) } catch (x: Exception) { CrashLog.error("widget update", x) }
            Sync.push(app)
        }
        return e
    }

    /** Saves the parking type (from the app or the notification), adds the curb to the map, sets type-specific reminders. */
    fun classify(ctx: Context, id: String, type: SpotType?, search: SearchTime?) {
        val app = ctx.applicationContext
        val history = HistoryStore(app)
        val ev = history.get(id) ?: return
        history.update(id) { it.copy(type = type?.key, search = search?.key ?: it.search, synced = false) }
        CurbType.fromSpot(type)?.let { c -> CurbStore(app).add(ev.lat, ev.lon, c, ev.street ?: ParkingRepository.get(app).streetNear(ev.lat, ev.lon), "parking") }
        if (type == SpotType.SHORT_TERM) Reminders.shortTerm(app, ev.time)
        if (type == SpotType.GARAGE || type == SpotType.CLOSE_TO_HOME || type == SpotType.PRIVATE) Reminders.cancelAll(app)
        AppScope.io.launch { Sync.push(app) }
    }

    /**
     * Estimate of today's free street minutes already used outside the permit zone:
     * paid-window minutes from each of today's parkings until the next one (capped at the daily allowance).
     */
    private fun freeMinutesUsedToday(rules: Rules, events: List<ParkingEvent>): Int {
        val zoneId = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now()
        val sorted = events.sortedBy { it.time }
        var used = 0
        sorted.forEachIndexed { i, e ->
            val start = java.time.Instant.ofEpochMilli(e.time).atZone(zoneId).toLocalDateTime()
            if (start.toLocalDate() != today || e.zone == null || e.zone == rules.permitZone) return@forEachIndexed
            if (e.type in setOf(SpotType.GARAGE.key, SpotType.CLOSE_TO_HOME.key, SpotType.PRIVATE.key)) return@forEachIndexed
            val g = rules.groupFor(e.zone) ?: return@forEachIndexed
            val endMillis = sorted.getOrNull(i + 1)?.time ?: System.currentTimeMillis()
            var t = start
            val end = java.time.Instant.ofEpochMilli(endMillis).atZone(zoneId).toLocalDateTime()
            while (t.isBefore(end) && used < rules.outsideFreeMinutes) { if (rules.isPaid(g, t)) used++; t = t.plusMinutes(1) }
        }
        return used.coerceAtMost(rules.outsideFreeMinutes)
    }

    @Suppress("DEPRECATION")
    private fun streetAt(ctx: Context, lat: Double, lon: Double): String? = try {
        Geocoder(ctx, Locale("he")).getFromLocation(lat, lon, 1)?.firstOrNull()?.thoroughfare
    } catch (_: Exception) { null }
}
