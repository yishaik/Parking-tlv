package il.yishai.parking

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to the parking server (MCP + REST). Base URL comes from assets/server.json; when it is null
 * the app works fully offline. The first device to register gets the owner token, kept in prefs.
 */
object Sync {
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("sync", Context.MODE_PRIVATE)

    fun baseUrl(ctx: Context): String? = try {
        val o = JSONObject(ctx.assets.open("server.json").bufferedReader().use { it.readText() })
        if (o.isNull("baseUrl")) null else o.getString("baseUrl").trimEnd('/')
    } catch (_: Exception) { null }

    private fun call(url: String, method: String, token: String?, body: String?): Pair<Int, String> {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 10000; readTimeout = 15000
            setRequestProperty("Content-Type", "application/json")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
            if (body != null) { doOutput = true; outputStream.use { it.write(body.toByteArray()) } }
        }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        return code to text
    }

    /** Owner token; registers on first use. null when no server is configured or it is unreachable. */
    suspend fun token(ctx: Context): String? = withContext(Dispatchers.IO) {
        prefs(ctx).getString("token", null)?.let { return@withContext it }
        val base = baseUrl(ctx) ?: return@withContext null
        try {
            val (code, text) = call("$base/api/register", "POST", null, "{}")
            if (code != 200) return@withContext null
            JSONObject(text).getString("token").also { prefs(ctx).edit().putString("token", it).apply() }
        } catch (_: Exception) { null }
    }

    /** Upload events not yet synced (also re-sends edited ones, which are marked unsynced). */
    suspend fun push(ctx: Context) = withContext(Dispatchers.IO) {
        val base = baseUrl(ctx) ?: return@withContext
        val token = token(ctx) ?: return@withContext
        val store = HistoryStore(ctx)
        val pending = store.all().filter { !it.synced }
        if (pending.isNotEmpty()) try {
            val body = JSONObject().put("events", JSONArray().apply { pending.forEach { put(it.toJson()) } }).toString()
            val (code, _) = call("$base/api/events", "POST", token, body)
            if (code == 200) pending.forEach { e -> store.update(e.id) { it.copy(synced = true) } }
        } catch (_: Exception) {}
        // Curb marks and their deletions
        val curbs = CurbStore(ctx)
        val newCurbs = curbs.all().filter { !it.synced }
        val deleted = curbs.tombstones()
        if (newCurbs.isNotEmpty() || deleted.isNotEmpty()) try {
            val body = JSONObject()
                .put("curbs", JSONArray().apply { newCurbs.forEach { put(it.toJson()) } })
                .put("deleted", JSONArray(deleted.toList())).toString()
            val (code, _) = call("$base/api/curbs", "POST", token, body)
            if (code == 200) { curbs.markSynced(newCurbs.map { it.id }.toSet()); curbs.clearTombstones(deleted) }
        } catch (_: Exception) {}
    }

    suspend fun delete(ctx: Context, id: String) = withContext(Dispatchers.IO) {
        val base = baseUrl(ctx) ?: return@withContext
        val token = token(ctx) ?: return@withContext
        try { call("$base/api/events/$id", "DELETE", token, null) } catch (_: Exception) {}
    }

    /** Prompt the user pastes into any agent so it connects to the MCP server. */
    fun agentPrompt(base: String, token: String): String = """
        התחבר לשרת ה-MCP של אפליקציית החניה שלי בתל אביב:
        כתובת: $base/mcp
        כותרת הרשאה: Authorization: Bearer $token

        המטרה: לכוון אותי לחניה הכי טובה וזולה ליעד שלי.
        לכל יעד:
        1. קרא ל-parking_near_destination עם כתובת היעד והשעה שבה אגיע.
        2. העדף בסדר הזה: חניה חינם (האזור שלי, שעות בלי תשלום), כחול-לבן בתוך שעתיים החינם, חניון עם הנחת תושב, ואחר כך כל השאר.
        3. השתמש ב-parking_stats כדי להמליץ על רחובות שבהם מצאתי חניה מהר בעבר.
        4. תן לי מקום אחד מומלץ ועוד שתי חלופות, עם מחיר, מרחק הליכה ליעד וקישור ניווט של Google Maps.
        5. השתמש ב-curb_map_near כדי לראות אילו קטעי מדרכה סימנתי (כחול-לבן, אפור, אדום-לבן, מועדפת) ליד היעד.
        כלים נוספים: where_is_my_car, parking_history, garages_near, other_lots_near, loading_signs_near, zone_at, street_parking_cost.
        אל תמציא תעריפים: אם השרת מחזיר עלות לא ידועה, תגיד את זה.
    """.trimIndent()
}
