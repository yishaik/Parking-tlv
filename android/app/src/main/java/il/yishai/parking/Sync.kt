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
        try {
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            return code to text
        } finally { c.disconnect() }
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
        // Assigned close-to-home lots (profile), only when changed
        val assigned = (ctx.getSharedPreferences("assigned", Context.MODE_PRIVATE).getStringSet("lots", emptySet())!! +
            ParkingRepository.get(ctx).rules.permit.assignedLots).sorted()
        val sig = assigned.joinToString("|")
        if (prefs(ctx).getString("profileSig", null) != sig) try {
            val (code, _) = call("$base/api/profile", "POST", token, JSONObject().put("assignedLots", JSONArray(assigned)).toString())
            if (code == 200) prefs(ctx).edit().putString("profileSig", sig).apply()
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

    // ---------- Morty (Telegram) ----------
    /** Morty's Telegram bot username, without @. Bot usernames are public, not secrets. */
    fun mortyBot(ctx: Context): String = prefs(ctx).getString("mortyBot", DEFAULT_MORTY_BOT)!!
    fun setMortyBot(ctx: Context, name: String) = prefs(ctx).edit().putString("mortyBot", name.trim().removePrefix("@").removePrefix("https://t.me/")).apply()
    fun mortyLinked(ctx: Context): Boolean = prefs(ctx).getBoolean("mortyLinked", false)
    private fun setMortyLinked(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("mortyLinked", v).apply()
    const val DEFAULT_MORTY_BOT = "YishaiMortybot"

    /** Asks the server for a one-time link code and returns the t.me link that hands it to Morty. */
    suspend fun mortyLinkUrl(ctx: Context): Result<String> = withContext(Dispatchers.IO) {
        val bot = mortyBot(ctx).ifBlank { return@withContext Result.failure(Exception("חסר שם הבוט של מורטי")) }
        val base = baseUrl(ctx) ?: return@withContext Result.failure(Exception("השרת לא מוגדר"))
        val token = token(ctx) ?: return@withContext Result.failure(Exception("אין חיבור לשרת"))
        try {
            val (code, text) = call("$base/api/link/start", "POST", token, "{}")
            if (code != 200) return@withContext Result.failure(Exception("השרת החזיר $code"))
            Result.success("https://t.me/$bot?start=" + JSONObject(text).getString("payload"))
        } catch (e: Exception) { Result.failure(Exception("החיבור נכשל: ${e.message}")) }
    }

    /** Agent hosts linked to the server (Morty, and others later), or null when it cannot be reached. */
    suspend fun linkedAgents(ctx: Context): List<String>? = withContext(Dispatchers.IO) {
        val base = baseUrl(ctx) ?: return@withContext null
        val token = token(ctx) ?: return@withContext null
        try {
            val (code, text) = call("$base/api/link/status", "GET", token, null)
            if (code != 200) return@withContext null
            val arr = JSONObject(text).optJSONArray("agents") ?: org.json.JSONArray()
            List(arr.length()) { arr.getJSONObject(it).optString("client") }.also { setMortyLinked(ctx, it.isNotEmpty()) }
        } catch (_: Exception) { null }
    }

    /** Whether Morty is linked; null when the server cannot be reached. */
    suspend fun mortyStatus(ctx: Context): Boolean? = linkedAgents(ctx)?.contains("morty")

    suspend fun mortyUnlink(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        val base = baseUrl(ctx) ?: return@withContext false
        val token = token(ctx) ?: return@withContext false
        try {
            val (code, text) = call("$base/api/link/revoke", "POST", token, JSONObject().put("client", "morty").toString())
            (code == 200).also { if (it) setMortyLinked(ctx, JSONObject(text).optInt("left") > 0) }
        } catch (_: Exception) { false }
    }

    /** Queues an alert for Morty's Telegram. risk "high" passes Morty's quiet hours. */
    suspend fun notify(ctx: Context, text: String, kind: String, risk: String): Boolean = withContext(Dispatchers.IO) {
        val base = baseUrl(ctx) ?: return@withContext false
        val token = token(ctx) ?: return@withContext false
        val body = JSONObject().put("text", text).put("kind", kind).put("risk", risk).toString()
        try {
            val (code, resp) = call("$base/api/notify", "POST", token, body)
            if (code == 200 && !JSONObject(resp).optBoolean("queued")) setMortyLinked(ctx, false)
            code == 200
        } catch (_: Exception) { false }
    }

    /** Fire-and-forget forwarding that survives the receiver ending and waits for a network. */
    fun forward(ctx: Context, title: String, text: String, kind: String, risk: String) {
        if (!mortyLinked(ctx) || baseUrl(ctx) == null) return
        val req = androidx.work.OneTimeWorkRequestBuilder<ForwardWorker>()
            .setInputData(androidx.work.workDataOf("text" to "$title\n$text", "kind" to kind, "risk" to risk))
            .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
            .build()
        androidx.work.WorkManager.getInstance(ctx).enqueue(req)
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

class ForwardWorker(ctx: Context, params: androidx.work.WorkerParameters) : androidx.work.CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val text = inputData.getString("text") ?: return Result.success()
        val ok = Sync.notify(applicationContext, text, inputData.getString("kind") ?: "app", inputData.getString("risk") ?: "low")
        return if (ok || runAttemptCount >= 3) Result.success() else Result.retry()
    }
}
