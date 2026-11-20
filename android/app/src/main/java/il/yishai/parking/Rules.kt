package il.yishai.parking

import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

/** Kotlin port of src/rules.ts. Reads the same config/rules.json. */
class ZoneGroup(
    val name: String,
    val zones: Set<Int>,
    val visitorHourly: Double?,
    val sunThu: Pair<LocalTime, LocalTime>?,
    val fri: Pair<LocalTime, LocalTime>?,
    val sat: Pair<LocalTime, LocalTime>?,
) {
    fun window(date: LocalDate) = when (date.dayOfWeek) {
        DayOfWeek.SATURDAY -> sat
        DayOfWeek.FRIDAY -> fri
        else -> sunThu
    }

    fun isPaid(t: LocalDateTime): Boolean {
        val w = window(t.toLocalDate()) ?: return false
        val m = t.toLocalTime()
        return !m.isBefore(w.first) && m.isBefore(w.second)
    }
}

class Rules(
    val hasPermit: Boolean,
    val permitZone: Int?,
    val freeOverride: Boolean,
    val outsideFreeMinutes: Int,
    val discount: Double,
    val groups: List<ZoneGroup>,
) {
    data class Eval(val freeMinutes: Int, val paidMinutes: Int, val cost: Double?, val paidFrom: LocalDateTime?)

    fun groupFor(zone: Int?) = zone?.let { z -> groups.firstOrNull { z in it.zones } }

    fun evaluate(zone: Int?, start: LocalDateTime, minutes: Int, usedToday: Int = 0): Eval {
        if (freeOverride || (hasPermit && zone != null && zone == permitZone)) return Eval(minutes, 0, 0.0, null)
        val g = groupFor(zone) ?: return Eval(0, 0, null, null)
        var allowance = if (hasPermit) maxOf(0, outsideFreeMinutes - usedToday) else 0
        var free = 0
        var paid = 0
        var paidFrom: LocalDateTime? = null
        for (i in 0 until minutes) {
            val t = start.plusMinutes(i.toLong())
            when {
                !g.isPaid(t) -> free++
                allowance > 0 -> { allowance--; free++ }
                else -> { paid++; if (paidFrom == null) paidFrom = t }
            }
        }
        val cost = when {
            paid == 0 -> 0.0
            g.visitorHourly != null -> Math.round(paid / 60.0 * g.visitorHourly * (if (hasPermit) 1 - discount else 1.0) * 100) / 100.0
            else -> null
        }
        return Eval(free, paid, cost, paidFrom)
    }

    /** zone = null outside Tel Aviv. title is the short answer, subtitle/detail the context. freeNow = null when unknown. */
    data class Summary(val zone: Int?, val title: String, val subtitle: String, val detail: String, val freeNow: Boolean?)

    fun summary(zone: Int?, now: LocalDateTime): Summary {
        if (freeOverride) return Summary(zone, "חינם עכשיו", "העירייה הכריזה על חניה חינם בכחול-לבן", "", true)
        if (zone == null) return Summary(null, "מחוץ לתל אביב", "אין כאן אזורי חניה של העירייה", "", null)
        if (hasPermit && zone == permitZone) return Summary(zone, "חינם עם התו שלך", "כל היממה · כל השבוע", "", true)
        val g = groupFor(zone) ?: return Summary(zone, "אין נתונים", "אין נתוני תעריף לאזור הזה", "", null)
        val hourly = g.visitorHourly?.let { String.format(Locale.US, "₪%.2f", it * (if (hasPermit) 1 - discount else 1.0)) }
        val w = g.window(now.toLocalDate())
        val window = if (w == null) "היום אין תשלום" else "תשלום היום ${w.first}–${w.second}"
        val paidNow = g.isPaid(now)
        val title = when {
            !paidNow -> "חינם עכשיו"
            hasPermit -> "שעתיים חינם, אחר כך ${hourly ?: "בתשלום"}/ש׳"
            else -> hourly?.let { "$it לשעה" } ?: "בתשלום"
        }
        val est = evaluate(zone, now, 180).cost?.let { String.format(Locale.US, "₪%.2f", it) } ?: "לא ידוע"
        return Summary(zone, title, window, "3 שעות מעכשיו: $est" + if (hasPermit) " (אם לא ניצלת את השעתיים היום)" else "", !paidNow)
    }

    companion object {
        private fun window(o: JSONObject, key: String): Pair<LocalTime, LocalTime>? {
            val a = o.optJSONArray(key) ?: return null
            return LocalTime.parse(a.getString(0)) to LocalTime.parse(fixMidnight(a.getString(1)))
        }

        private fun fixMidnight(s: String) = if (s == "24:00") "23:59" else s

        fun parse(o: JSONObject): Rules {
            val profile = o.getJSONObject("profile")
            val groups = o.getJSONArray("zoneGroups")
            return Rules(
                hasPermit = profile.getBoolean("hasPermit"),
                permitZone = if (profile.isNull("permitZone")) null else profile.getInt("permitZone"),
                freeOverride = o.optBoolean("freeParkingOverride", false),
                outsideFreeMinutes = o.getInt("outsideZoneFreeMinutesPerDay"),
                discount = o.getDouble("residentDiscountOutsideZone"),
                groups = (0 until groups.length()).map { i ->
                    val g = groups.getJSONObject(i)
                    val zones = g.getJSONArray("zones")
                    val paid = g.getJSONObject("paid")
                    ZoneGroup(
                        g.getString("name"),
                        (0 until zones.length()).map { zones.getInt(it) }.toSet(),
                        if (g.isNull("visitorHourlyILS")) null else g.getDouble("visitorHourlyILS"),
                        window(paid, "sunThu"), window(paid, "fri"), window(paid, "sat"),
                    )
                },
            )
        }
    }
}
