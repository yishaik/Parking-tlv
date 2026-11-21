package il.yishai.parking

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Garages the user asked to watch, each until an expiry time. Status changes trigger a notification. */
object GarageWatch {
    private const val WORK = "garage-watch"
    private const val HOURS = 3L
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("watch", Context.MODE_PRIVATE)

    fun watched(ctx: Context): Set<String> {
        val now = System.currentTimeMillis()
        return prefs(ctx).all.filter { (k, v) -> !k.startsWith("status:") && (v as? Long ?: 0L) > now }.keys
    }

    fun isWatched(ctx: Context, name: String) = name in watched(ctx)

    fun toggle(ctx: Context, name: String, currentStatus: String) {
        val p = prefs(ctx)
        if (isWatched(ctx, name)) p.edit().remove(name).remove("status:$name").apply()
        else p.edit().putLong(name, System.currentTimeMillis() + HOURS * 3600_000L).putString("status:$name", currentStatus).apply()
        schedule(ctx)
    }

    fun schedule(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        if (watched(ctx).isEmpty()) { wm.cancelUniqueWork(WORK); return }
        // 15 minutes is the shortest periodic interval Android allows.
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<GarageWatchWorker>(15, TimeUnit.MINUTES).build())
    }

    internal fun lastStatus(ctx: Context, name: String) = prefs(ctx).getString("status:$name", null)
    internal fun setStatus(ctx: Context, name: String, s: String) = prefs(ctx).edit().putString("status:$name", s).apply()
}

class GarageWatchWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val watched = GarageWatch.watched(applicationContext)
        if (watched.isEmpty()) { GarageWatch.schedule(applicationContext); return Result.success() }
        val (garages, live) = ParkingRepository(applicationContext).garages()
        if (!live) return Result.retry()
        for (g in garages.filter { it.name in watched }) {
            val before = GarageWatch.lastStatus(applicationContext, g.name)
            if (before != null && before != g.status) {
                val opened = g.status == "פנוי" || (g.status == "מעט" && before == "מלא")
                val filled = g.status == "מלא"
                if (opened || filled) Notifications.reminder(
                    applicationContext, 1200 + (g.name.hashCode() and 0x7f),
                    if (opened) "${g.name}: ${statusLabel(g.status)}" else "${g.name} התמלא",
                    if (opened) "החניון ${g.name} (${g.address}) עבר ל'${statusLabel(g.status)}'." else "החניון ${g.name} מלא עכשיו. כדאי לבחור חניון אחר.",
                    pangoAction = false, forward = "medium",
                )
            }
            GarageWatch.setStatus(applicationContext, g.name, g.status)
        }
        return Result.success()
    }
}

/** Sunday-morning summary of the past week's parking, from local history. */
object WeeklyReport {
    private const val WORK = "weekly-report"

    fun schedule(ctx: Context) {
        val now = LocalDateTime.now()
        var next = now.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).withHour(8).withMinute(30).withSecond(0)
        if (!next.isAfter(now)) next = next.plusWeeks(1)
        val req = PeriodicWorkRequestBuilder<WeeklyReportWorker>(7, TimeUnit.DAYS)
            .setInitialDelay(Duration.between(now, next).toMinutes(), TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun summary(ctx: Context): Pair<String, String>? {
        val since = System.currentTimeMillis() - 7 * 86_400_000L
        val week = HistoryStore(ctx).all().filter { it.time >= since }
        if (week.isEmpty()) return null
        val rules = ParkingRepository(ctx).rules
        val searched = week.mapNotNull { SearchTime.of(it.search)?.minutes }
        val fast = week.filter { it.street != null && SearchTime.of(it.search) != null }.groupBy { it.street!! }
            .mapValues { (_, l) -> l.map { SearchTime.of(it.search)!!.minutes }.average() }.minByOrNull { it.value }
        val types = week.mapNotNull { SpotType.of(it.type)?.label }.groupingBy { it }.eachCount().maxByOrNull { it.value }
        val outside = week.count { it.zone != null && it.zone != rules.permitZone }
        val unclassified = week.count { it.type == null }
        val lines = buildList {
            add("${week.size} חניות השבוע" + if (outside > 0) ", $outside מהן מחוץ לאזור ${rules.permitZone}" else "")
            if (searched.isNotEmpty()) add(String.format(Locale.US, "חיפוש ממוצע: %.0f דקות", searched.average()))
            fast?.let { add("הכי מהר מצאת ב${it.key}") }
            types?.let { add("בעיקר ${it.key}") }
            if (unclassified > 0) add("$unclassified חניות לא סומנו, סמן אותן כדי שהסטטיסטיקה תשתפר")
        }
        return "סיכום החניה השבועי" to lines.joinToString(" · ")
    }
}

class WeeklyReportWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        WeeklyReport.summary(applicationContext)?.let { (title, text) ->
            Notifications.reminder(applicationContext, 1300, title, text, pangoAction = false, forward = null) // the server sends its own weekly summary
        }
        return Result.success()
    }
}
