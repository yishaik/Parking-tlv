package il.yishai.parking

import android.net.Uri
import java.util.Calendar
import kotlin.math.atan2

/** Time-of-day buckets used for odds. */
fun bucketOf(time: Long): Int {
    val h = Calendar.getInstance().apply { timeInMillis = time }.get(Calendar.HOUR_OF_DAY)
    return when (h) { in 6..11 -> 0; in 12..16 -> 1; in 17..20 -> 2; else -> 3 }
}
val BUCKET_LABELS = listOf("בוקר", "צהריים", "ערב", "לילה")

/** How easy it was to park on a street, from the user's own history. */
data class StreetOdds(val street: String, val parkings: Int, val avgSearchMinutes: Double, val sameTimeOfDay: Boolean) {
    /** 0 = hard, 1 = medium, 2 = easy */
    val level: Int get() = when { avgSearchMinutes <= 4 -> 2; avgSearchMinutes <= 10 -> 1; else -> 0 }
}

object Planner {
    /**
     * Per-street odds for the current time of day; falls back to all-day data when a street has no
     * parkings in this time bucket.
     */
    fun odds(events: List<ParkingEvent>, now: Long = System.currentTimeMillis()): Map<String, StreetOdds> {
        val bucket = bucketOf(now)
        val withSearch = events.filter { it.street != null && SearchTime.of(it.search) != null }
        return withSearch.groupBy { it.street!! }.mapValues { (street, list) ->
            val same = list.filter { bucketOf(it.time) == bucket }
            val use = same.ifEmpty { list }
            StreetOdds(street, use.size, use.map { SearchTime.of(it.search)!!.minutes }.average(), same.isNotEmpty())
        }
    }

    /** Closest point on a street's centreline to a target (lat, lon), plus its distance in meters. */
    private fun nearestOnStreet(s: Street, lat: Double, lon: Double): Triple<Double, Double, Double>? {
        var best: Triple<Double, Double, Double>? = null
        for (p in s.paths) for (pt in p) {
            val d = Geo.distance(lat, lon, pt[1], pt[0])
            if (best == null || d < best.third) best = Triple(pt[1], pt[0], d)
        }
        return best
    }

    data class RoutePlan(val streets: List<String>, val url: String, val reason: String)

    /**
     * A short driving loop through the best streets around a destination: streets where the user found parking
     * fast, streets with parkable curb marks, and (when relevant) streets inside the permit zone.
     */
    fun searchRoute(repo: ParkingRepository, events: List<ParkingEvent>, curbs: List<CurbMark>, destLat: Double, destLon: Double): RoutePlan? {
        val odds = odds(events)
        val parkable = setOf(CurbType.BLUE_WHITE.key, CurbType.GRAY.key, CurbType.PREFERRED.key, CurbType.SHORT_TERM.key)
        val curbScore = curbs.filter { it.street != null && it.type in parkable }.groupingBy { it.street!! }.eachCount()
        val permitZone = repo.rules.permitZone
        data class Cand(val name: String, val lat: Double, val lon: Double, val dist: Double, val score: Double)
        val byName = repo.streets.filter { it.name.length > 1 }.groupBy { it.name }
        val cands = byName.mapNotNull { (name, segs) ->
            val near = segs.mapNotNull { nearestOnStreet(it, destLat, destLon) }.minByOrNull { it.third } ?: return@mapNotNull null
            if (near.third > 600) return@mapNotNull null
            val o = odds[name]
            var score = 0.0
            if (o != null) score += o.level + 1 + minOf(o.parkings, 5) * 0.3   // easy streets score high
            score += minOf(curbScore[name] ?: 0, 5) * 0.4
            if (permitZone != null && repo.zoneAt(near.first, near.second) == permitZone) score += 1.5
            score -= near.third / 300.0                                                          // prefer close to destination
            Cand(name, near.first, near.second, near.third, score)
        }
        val picked = cands.sortedByDescending { it.score }.take(3)
        if (picked.isEmpty()) return null
        // Order the waypoints around the destination so the drive is a loop, not a zigzag.
        val ordered = picked.sortedBy { atan2(it.lat - destLat, it.lon - destLon) }
        val waypoints = ordered.joinToString("|") { "${it.lat},${it.lon}" }
        val url = "https://www.google.com/maps/dir/?api=1&destination=$destLat,$destLon&travelmode=driving&waypoints=" + Uri.encode(waypoints)
        val reason = when {
            picked.any { odds.containsKey(it.name) } -> "לפי הרחובות שבהם מצאת חניה מהר בשעה הזו"
            picked.any { (curbScore[it.name] ?: 0) > 0 } -> "לפי סימוני המדרכה שלך"
            else -> "הרחובות הקרובים${if (permitZone != null) " באזור $permitZone" else ""} (עוד אין לך היסטוריה כאן)"
        }
        return RoutePlan(ordered.map { it.name }, url, reason)
    }
}
