package il.yishai.parking

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ParkingApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}

/**
 * Keeps the last crash (and recent background errors) on the phone so it can be shared from the app.
 * Nothing is sent anywhere by itself.
 */
object CrashLog {
    private const val TAG = "parking"
    private lateinit var dir: File

    fun install(ctx: Context) {
        dir = ctx.applicationContext.filesDir
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { File(dir, "crash.txt").writeText(entry("crash on ${t.name}", e)) } catch (_: Throwable) {}
            previous?.uncaughtException(t, e)
        }
    }

    /** A caught error from background work: logged, kept (last ~30 KB), never thrown. */
    fun error(where: String, e: Throwable) {
        Log.w(TAG, where, e)
        if (!::dir.isInitialized) return
        try {
            val f = File(dir, "errors.txt")
            val old = if (f.exists()) f.readText().takeLast(30_000) else ""
            f.writeText(old + entry(where, e))
        } catch (_: Throwable) {}
    }

    /** Last crash and recent errors, or null when there is nothing to report. */
    fun report(ctx: Context): String? {
        val d = ctx.applicationContext.filesDir
        val parts = listOf("crash.txt", "errors.txt").mapNotNull { n -> File(d, n).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() } }
        if (parts.isEmpty()) return null
        val version = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (_: Exception) { "?" }
        return "parking-tlv $version · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MODEL}\n\n" + parts.joinToString("\n")
    }

    fun clear(ctx: Context) = listOf("crash.txt", "errors.txt").forEach { File(ctx.applicationContext.filesDir, it).delete() }

    private fun entry(where: String, e: Throwable): String {
        val sw = StringWriter().also { e.printStackTrace(PrintWriter(it)) }
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return "--- $time $where\n${sw.toString().lines().take(40).joinToString("\n")}\n"
    }
}

/** Process-wide scope for background work started from receivers and helpers: one failure never crashes the app. */
object AppScope {
    val io = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> CrashLog.error("background", e) })
}

/** Maps a JSON array, skipping elements that do not parse instead of failing the whole list. */
inline fun <T> JSONArray.mapSafe(f: (JSONObject) -> T?): List<T> =
    (0 until length()).mapNotNull { i -> try { optJSONObject(i)?.let(f) } catch (_: Exception) { null } }

/**
 * Small JSON list files in app storage, written atomically. A file that cannot be read is kept aside
 * (name.bad) rather than overwritten, so one bad write never wipes the user's history.
 */
class JsonListFile(private val file: File) {
    @Synchronized fun read(): JSONArray? {
        if (!file.exists()) return JSONArray()
        return try { JSONArray(file.readText()) } catch (e: Exception) {
            CrashLog.error("unreadable ${file.name}", e)
            file.renameTo(File(file.parentFile, file.name + ".bad"))
            null
        }
    }

    @Synchronized fun write(a: JSONArray) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(a.toString())
        if (!tmp.renameTo(file)) { file.writeText(a.toString()); tmp.delete() }
    }
}

/** Re-arms parking reminders after a reboot or an app update (the system drops alarms on reboot). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        try {
            Reminders.restore(context)
            GarageWatch.schedule(context)
            WeeklyReport.schedule(context)
        } catch (e: Exception) { CrashLog.error("boot", e) }
    }
}
