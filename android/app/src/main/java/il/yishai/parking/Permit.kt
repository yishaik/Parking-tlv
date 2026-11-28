package il.yishai.parking

import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * What the city's permit letter says (config/rules.json profile.permit): validity, the close-to-home
 * lots assigned to this permit and their hours, and the resident discount in each Ahuzot HaHof garage.
 */
class Permit(
    val validUntil: LocalDate?,
    val assignedLots: List<String>,
    private val lotHours: Map<String, String>,
    private val discount50: List<String>,
    private val discountDefault: Int,
) {
    /** "חניון אסותא" and "אסותא" are the same lot. */
    private fun norm(n: String) = n.replace("חניון", "").replace("\"", "").replace(Regex("\\s+"), " ").trim()

    /** A name matches an entry when it contains it, or is the bare name of a numbered lot ("שרתון" for "שרתון 1"). */
    private fun matches(name: String, entry: String): Boolean {
        val n = norm(name); val e = norm(entry)
        return n.isNotEmpty() && (n == e || n.contains(e) || e.startsWith("$n "))
    }

    /** The assigned lot this garage or close-to-home lot is, or null. */
    fun assignedLot(name: String): String? = assignedLots.firstOrNull { matches(name, it) }

    /** Resident discount in an Ahuzot HaHof garage, in percent. */
    fun discountFor(name: String): Int = if (discount50.any { matches(name, it) }) 50 else discountDefault

    /** Hours rule of a close-to-home lot: "default", "basel" or "sheraton2". */
    fun hoursOf(lot: String): String = lotHours.entries.firstOrNull { matches(lot, it.key) }?.value ?: "default"

    fun daysLeft(today: LocalDate = LocalDate.now()): Long? = validUntil?.let { ChronoUnit.DAYS.between(today, it) }

    companion object {
        val NONE = Permit(null, emptyList(), emptyMap(), emptyList(), 75)
        fun parse(profile: JSONObject?): Permit {
            val p = profile?.optJSONObject("permit") ?: return NONE
            fun list(k: String) = p.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            val hours = p.optJSONObject("lotHours")?.let { h -> h.keys().asSequence().associateWith { h.getString(it) } } ?: emptyMap()
            return Permit(
                p.optString("validUntil").takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                list("assignedLots"), hours, list("discount50"), p.optInt("discountDefault", 75),
            )
        }
    }
}
