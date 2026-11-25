package il.yishai.parking

import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

/** Kotlin port of src/rules.ts. Reads config/rules.json and config/holidays.json. */
class ZoneGroup(
    val name: String,
    val zones: Set<Int>,
    val visitorHourly: Double?,
    val sunThu: Pair<LocalTime, LocalTime>?,
    val fri: Pair<LocalTime, LocalTime>?,
    val sat: Pair<LocalTime, LocalTime>?,
)

enum class DayKind { SUN_THU, FRI, SAT }

class Rules(
    val hasPermit: Boolean,
    val permitZone: Int?,
    val freeOverride: Boolean,
    val outsideFreeMinutes: Int,
    val discount: Double,
    val boundaryMeters: Double,
    val groups: List<ZoneGroup>,
    /** yyyy-MM-dd -> "erev" | "yomtov" */
    val holidays: Map<String, String>,
    /** Vehicle the permit is issued for, e.g. "59-999-79" (permit letter 24/11/2026). */
    val permitVehicle: String? = null,
    /** Permit expiry, yyyy-MM-dd, e.g. "2027-11-24". */
    val permitValidUntil: String? = null,
    /** Municipality reference, e.g. "31351457". */
    val permitRef: String? = null,
) {
    /** Same rules with the "free blue-white parking" switch set (from a user-confirmed announcement). */
    fun withOverride(on: Boolean): Rules = if (on == freeOverride) this
        else Rules(hasPermit, permitZone, on, outsideFreeMinutes, discount, boundaryMeters, groups, holidays, permitVehicle, permitValidUntil, permitRef)

    /** "התו שלך: אזור 9 · רכב 59-999-79 · בתוקף עד 24/11/2027" — warns when ≤ 60 days remain. */
    fun permitLine(now: LocalDate = LocalDate.now()): String? {
        if (!hasPermit || permitZone == null) return null
        val base = "התו שלך: אזור $permitZone" + (permitVehicle?.let { " · רכב $it" } ?: "")
        val until = permitValidUntil?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return base
        val fmt = until.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))
        val days = java.time.temporal.ChronoUnit.DAYS.between(now, until)
        return when {
            days < 0 -> "$base · פג תוקף ב-$fmt"
            days <= 60 -> "$base · פג בעוד $days ימים ($fmt)"
            else -> "$base · בתוקף עד $fmt"
        }
    }

    data class Eval(val freeMinutes: Int, val paidMinutes: Int, val cost: Double?, val paidFrom: LocalDateTime?, val appRequired: Boolean)

    fun groupFor(zone: Int?) = zone?.let { z -> groups.firstOrNull { z in it.zones } }

    fun holiday(date: LocalDate): String? = holidays[date.toString()]

    fun dayKind(date: LocalDate): DayKind {
        val h = holiday(date)
        return when {
            date.dayOfWeek == DayOfWeek.SATURDAY || h == "yomtov" -> DayKind.SAT
            date.dayOfWeek == DayOfWeek.FRIDAY || h == "erev" -> DayKind.FRI
            else -> DayKind.SUN_THU
        }
    }

    fun window(g: ZoneGroup, date: LocalDate) = when (dayKind(date)) { DayKind.SAT -> g.sat; DayKind.FRI -> g.fri; DayKind.SUN_THU -> g.sunThu }

    fun isPaid(g: ZoneGroup, t: LocalDateTime): Boolean {
        val w = window(g, t.toLocalDate()) ?: return false
        val m = t.toLocalTime()
        return !m.isBefore(w.first) && m.isBefore(w.second)
    }

    /** Close-to-home lots: free for assigned permit holders Sun–Thu 19:00–08:00, Fri/eve from 15:00, Saturday and yom tov. */
    fun closeToHomeFree(t: LocalDateTime): Boolean {
        val k = dayKind(t.toLocalDate())
        val h = t.hour
        if (k == DayKind.SAT || h < 8) return true
        return if (k == DayKind.FRI) h >= 15 else h >= 19
    }

    /** Next time today a close-to-home lot becomes free (null if free now). */
    fun closeToHomeFreeFrom(t: LocalDateTime): LocalTime? {
        if (closeToHomeFree(t)) return null
        return if (dayKind(t.toLocalDate()) == DayKind.FRI) LocalTime.of(15, 0) else LocalTime.of(19, 0)
    }

    fun evaluate(zone: Int?, start: LocalDateTime, minutes: Int, usedToday: Int = 0, onPermitBoundary: Boolean = false): Eval {
        if (freeOverride || (hasPermit && zone != null && zone == permitZone)) return Eval(minutes, 0, 0.0, null, false)
        if (hasPermit && onPermitBoundary) return Eval(minutes, 0, 0.0, null, true)
        val g = groupFor(zone) ?: return Eval(0, 0, null, null, false)
        var allowance = if (hasPermit) maxOf(0, outsideFreeMinutes - usedToday) else 0
        var free = 0
        var paid = 0
        var touched = false
        var paidFrom: LocalDateTime? = null
        for (i in 0 until minutes) {
            val t = start.plusMinutes(i.toLong())
            if (!isPaid(g, t)) { free++; continue }
            touched = true
            if (allowance > 0) { allowance--; free++ } else { paid++; if (paidFrom == null) paidFrom = t }
        }
        val cost = when {
            paid == 0 -> 0.0
            g.visitorHourly != null -> Math.round(paid / 60.0 * g.visitorHourly * (if (hasPermit) 1 - discount else 1.0) * 100) / 100.0
            else -> null
        }
        return Eval(free, paid, cost, paidFrom, touched)
    }

    /** zone = null outside Tel Aviv. title is the short answer, subtitle/detail the context. freeNow = null when unknown. */
    data class Summary(val zone: Int?, val title: String, val subtitle: String, val detail: String, val freeNow: Boolean?, val warnings: List<String>)

    fun summary(zone: Int?, now: LocalDateTime, onPermitBoundary: Boolean = false): Summary {
        val holidayNote = when (holiday(now.toLocalDate())) { "erev" -> "ערב חג: שעות כמו בשישי"; "yomtov" -> "חג: ללא תשלום ברחוב (כמו שבת)"; else -> null }
        if (freeOverride) return Summary(zone, "חינם עכשיו", "העירייה הכריזה על חניה חינם בכחול-לבן", "", true, emptyList())
        if (zone == null && !onPermitBoundary) return Summary(null, "מחוץ לתל אביב", "אין כאן אזורי חניה של העירייה", "", null, emptyList())
        if (hasPermit && zone == permitZone) return Summary(zone, "חינם עם התו שלך", "כל היממה · כל השבוע · בלי אפליקציה", "", true, emptyList())
        if (hasPermit && onPermitBoundary) return Summary(
            zone, "רחוב גובל: חינם לך", "גבול אזור $permitZone · חינם לבעלי תו של שני האזורים", "",
            true, listOf("חובה להפעיל פנגו או סלופארק מהדקה הראשונה, גם כשזה חינם"),
        )
        val g = groupFor(zone) ?: return Summary(zone, "אין נתונים", "אין נתוני תעריף לאזור הזה", "", null, emptyList())
        val hourly = g.visitorHourly?.let { String.format(Locale.US, "₪%.2f", it * (if (hasPermit) 1 - discount else 1.0)) }
        val w = window(g, now.toLocalDate())
        val window = if (w == null) "היום אין תשלום ברחוב" else "תשלום היום ${w.first}–${w.second}"
        val paidNow = isPaid(g, now)
        val title = when {
            !paidNow -> "חינם עכשיו"
            hasPermit -> "שעתיים חינם, אחר כך ${hourly ?: "בתשלום"}/ש׳"
            else -> hourly?.let { "$it לשעה" } ?: "בתשלום"
        }
        val e = evaluate(zone, now, 180)
        val est = e.cost?.let { String.format(Locale.US, "₪%.2f", it) } ?: "לא ידוע"
        val warnings = buildList {
            if (e.appRequired) add("שעתיים החינם נספרות רק אם מפעילים פנגו או סלופארק")
            if (e.appRequired) add("בקטעי 'חניה מועדפת' רק לבעלי תו של אותו אזור, בדוק את התמרור")
        }
        return Summary(
            zone, title, listOfNotNull(window, holidayNote).joinToString(" · "),
            "3 שעות מעכשיו: $est" + if (hasPermit) " (אם לא ניצלת את השעתיים היום)" else "",
            !paidNow, warnings,
        )
    }

    /** Plain-language explanation of the rule at one spot. actionable = something to do or know beyond "free for you". */
    data class Explanation(val headline: String, val tag: String, val lines: List<String>, val nowLine: String?, val freeNow: Boolean?, val actionable: Boolean)

    private fun hhmm(t: LocalTime) = String.format(Locale.US, "%02d:%02d", t.hour, t.minute)
    private fun win(w: Pair<LocalTime, LocalTime>?) = w?.let { "${hhmm(it.first)}–${hhmm(it.second)}" } ?: "חינם"

    private fun whenText(t: LocalDateTime, now: LocalDateTime): String {
        val days = java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), t.toLocalDate())
        val dayName = t.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale("he"))
        return when (days) { 0L -> "היום ב-${hhmm(t.toLocalTime())}"; 1L -> "מחר ב-${hhmm(t.toLocalTime())}"; else -> "$dayName ב-${hhmm(t.toLocalTime())}" }
    }

    /** First minute (within 4 days) when street parking in this zone is paid. */
    fun nextPaidStart(zone: Int?, now: LocalDateTime): LocalDateTime? = groupFor(zone)?.let { nextPaidStart(it, now) }

    private fun nextPaidStart(g: ZoneGroup, now: LocalDateTime): LocalDateTime? {
        var t = now.withSecond(0).withNano(0)
        repeat(4 * 24 * 60) { if (isPaid(g, t)) return t; t = t.plusMinutes(1) }
        return null
    }

    private fun paidEndToday(g: ZoneGroup, now: LocalDateTime): LocalTime? = window(g, now.toLocalDate())?.second

    fun explain(zone: Int?, now: LocalDateTime, onPermitBoundary: Boolean = false): Explanation {
        val holiday = when (holiday(now.toLocalDate())) { "erev" -> "היום ערב חג: שעות כמו בשישי"; "yomtov" -> "היום חג: אין תשלום ברחוב (כמו שבת), בדוק תמרור"; else -> null }
        if (freeOverride) return Explanation("חינם לכולם עכשיו", "הכרזת עירייה", listOf("העירייה הכריזה על חניה חינם בכחול-לבן"), null, true, true)
        if (hasPermit && zone != null && zone == permitZone) return Explanation(
            "חינם לך כאן", "האזור שלך",
            listOf("כחול-לבן באזור $zone, האזור של התו שלך", "חינם כל היממה וכל השבוע, בלי להפעיל אפליקציה", "גם בקטעי 'חניה מועדפת' של אזור $zone מותר לך"),
            null, true, false,
        )
        if (hasPermit && onPermitBoundary) return Explanation(
            "חינם לך, רק עם פנגו", "רחוב גובל",
            listOfNotNull("רחוב גובל בין אזור $permitZone לאזור ${zone ?: "סמוך"}", "בעלי תו של שני האזורים חונים כאן חינם", "חובה להפעיל פנגו או סלופארק מהדקה הראשונה, אחרת זה דוח", holiday),
            null, true, true,
        )
        if (zone == null) return Explanation("לא ידוע", "מחוץ לת״א", listOf("זה מחוץ לאזורי החניה של תל אביב", "החוק כאן לפי התמרור במקום"), null, null, false)
        val g = groupFor(zone) ?: return Explanation("לא ידוע", "אזור $zone", listOf("אין לי נתוני תעריף לאזור $zone, פעל לפי התמרור"), null, null, true)
        val rate = g.visitorHourly?.let { String.format(Locale.US, "₪%.2f", it * (if (hasPermit) 1 - discount else 1.0)) }
        val schedule = "שעות תשלום: א׳–ה׳ ${win(g.sunThu)} · ו׳ וערבי חג ${win(g.fri)} · שבת וחג חינם"
        val forYou = if (hasPermit) "לך: שעתיים ראשונות ביום חינם (מצטבר), רק בהפעלת פנגו, ואחר כך ${rate ?: "בתשלום"} לשעה" else "תעריף: ${rate ?: "לא ידוע"} לשעה"
        val preferred = "בקטע עם תמרור 'חניה מועדפת' מותר רק לבעלי תו של אזור $zone, בדוק את התמרור"
        val paidNow = isPaid(g, now)
        return if (paidNow) {
            val e = evaluate(zone, now, 180)
            val end = paidEndToday(g, now)?.let { "התשלום נגמר היום ב-${hhmm(it)}" }
            Explanation(
                if (hasPermit) "שעתיים חינם, רק עם פנגו" else "בתשלום עכשיו", "אזור $zone · לא שלך",
                listOfNotNull(forYou, schedule, preferred, holiday),
                listOfNotNull(e.cost?.let { String.format(Locale.US, "חניה של 3 שעות מעכשיו: ₪%.2f", it) + if (hasPermit) " (אם לא ניצלת היום את השעתיים)" else "" }, end).joinToString(" · "),
                false, true,
            )
        } else {
            val next = nextPaidStart(g, now)
            Explanation(
                "חינם עכשיו", "אזור $zone · לא שלך",
                listOfNotNull(schedule, forYou, preferred, holiday),
                next?.let { "אין תשלום עד ${whenText(it, now)}. מאז: ${if (hasPermit) "שעתיים חינם עם פנגו, ואז " else ""}${rate ?: "בתשלום"} לשעה" },
                true, true,
            )
        }
    }

    companion object {
        private fun window(o: JSONObject, key: String): Pair<LocalTime, LocalTime>? {
            val a = o.optJSONArray(key) ?: return null
            return LocalTime.parse(a.getString(0)) to LocalTime.parse(fixMidnight(a.getString(1)))
        }

        private fun fixMidnight(s: String) = if (s == "24:00") "23:59" else s

        fun parse(o: JSONObject, holidaysJson: JSONObject?): Rules {
            val profile = o.getJSONObject("profile")
            val groups = o.getJSONArray("zoneGroups")
            val hol = mutableMapOf<String, String>()
            holidaysJson?.optJSONObject("days")?.let { d -> d.keys().forEach { hol[it] = d.getString(it) } }
            return Rules(
                hasPermit = profile.getBoolean("hasPermit"),
                permitZone = if (profile.isNull("permitZone")) null else profile.getInt("permitZone"),
                freeOverride = o.optBoolean("freeParkingOverride", false),
                outsideFreeMinutes = o.getInt("outsideZoneFreeMinutesPerDay"),
                discount = o.getDouble("residentDiscountOutsideZone"),
                boundaryMeters = o.optDouble("boundaryMeters", 25.0),
                permitVehicle = profile.optString("permitVehicle").takeIf { it.isNotBlank() },
                permitValidUntil = profile.optString("permitValidUntil").takeIf { it.isNotBlank() },
                permitRef = profile.optString("permitRef").takeIf { it.isNotBlank() },
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
                holidays = hol,
            )
        }
    }
}
