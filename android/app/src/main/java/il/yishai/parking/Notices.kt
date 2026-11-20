package il.yishai.parking

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

/** A news item that may affect parking: a free-parking announcement, a price change, or an event in zone 9. */
data class Notice(val kind: Kind, val title: String, val source: String, val link: String, val time: Long) {
    enum class Kind { FREE_PARKING, PRICE_CHANGE, EVENT }
}

/**
 * Watches Google News (Hebrew) for parking-relevant items. News is a signal, not the rule:
 * the app shows it and lets the user switch on "free parking" mode, it never applies it silently.
 */
object Notices {
    private const val TTL = 60 * 60_000L
    private var cache: Pair<Long, List<Notice>>? = null

    private val queries = listOf(
        Notice.Kind.FREE_PARKING to "תל אביב חניה חינם כחול לבן",
        Notice.Kind.PRICE_CHANGE to "תל אביב תעריף חניה כחול לבן",
        Notice.Kind.EVENT to "פארק הירקון OR \"נמל תל אביב\" OR \"גני התערוכה\" OR \"אקספו תל אביב\" אירוע",
    )
    private val freeWords = Regex("חניה חינם|חנייה חינם|ללא תשלום|בחינם בכחול")
    private val priceWords = Regex("מתייקר|העלא|תעריף|יזנקו|מחיר")
    private val eventWords = Regex("הופע|פסטיבל|אירוע|מופע|תערוכ|יריד|מרתון|משחק")

    private fun maxAgeDays(k: Notice.Kind) = when (k) { Notice.Kind.FREE_PARKING -> 3; Notice.Kind.PRICE_CHANGE -> 14; Notice.Kind.EVENT -> 3 }

    suspend fun fetch(): List<Notice> = withContext(Dispatchers.IO) {
        cache?.let { if (System.currentTimeMillis() - it.first < TTL) return@withContext it.second }
        val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        val out = mutableListOf<Notice>()
        for ((kind, q) in queries) try {
            val url = "https://news.google.com/rss/search?q=${URLEncoder.encode(q, "UTF-8")}&hl=he&gl=IL&ceid=IL:he"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000 }
            val xml = conn.inputStream.bufferedReader().use { it.readText() }
            Regex("<item>([\\s\\S]*?)</item>").findAll(xml).forEach { m ->
                val item = m.groupValues[1]
                fun tag(t: String) = Regex("<$t>([\\s\\S]*?)</$t>").find(item)?.groupValues?.get(1)?.trim()?.removePrefix("<![CDATA[")?.removeSuffix("]]>") ?: ""
                val full = tag("title")
                val time = try { fmt.parse(tag("pubDate"))?.time ?: 0 } catch (_: Exception) { 0L }
                if (System.currentTimeMillis() - time > maxAgeDays(kind) * 86_400_000L) return@forEach
                val words = when (kind) { Notice.Kind.FREE_PARKING -> freeWords; Notice.Kind.PRICE_CHANGE -> priceWords; Notice.Kind.EVENT -> eventWords }
                if (!words.containsMatchIn(full)) return@forEach
                val source = full.substringAfterLast(" - ", "")
                out += Notice(kind, full.substringBeforeLast(" - "), source, tag("link"), time)
            }
        } catch (_: Exception) {}
        val result = out.distinctBy { it.title }.sortedByDescending { it.time }.take(6)
        cache = System.currentTimeMillis() to result
        result
    }
}

/** User-confirmed "free blue-white parking" mode (after an announcement), valid until a chosen time. */
object FreeOverride {
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("override", Context.MODE_PRIVATE)
    fun until(ctx: Context): Long? = prefs(ctx).getLong("until", 0L).takeIf { it > System.currentTimeMillis() }
    fun set(ctx: Context, untilMillis: Long) = prefs(ctx).edit().putLong("until", untilMillis).apply()
    fun clear(ctx: Context) = prefs(ctx).edit().remove("until").apply()
}
