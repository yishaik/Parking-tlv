package il.yishai.parking

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

object Notifications {
    private const val CHANNEL = "parking"
    const val EXTRA_CLASSIFY = "classify_event"
    const val ID_CLASSIFY = 1001

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "חניה", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    private fun openApp(ctx: Context, classifyId: String?, request: Int): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (classifyId != null) i.putExtra(EXTRA_CLASSIFY, classifyId)
        return PendingIntent.getActivity(ctx, request, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun pangoIntent(ctx: Context): PendingIntent? {
        val i = ctx.packageManager.getLaunchIntentForPackage("com.unicell.pangoandroid") ?: return null
        return PendingIntent.getActivity(ctx, 3001, i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun canPost(ctx: Context) = Build.VERSION.SDK_INT < 33 || Perms.has(ctx, Manifest.permission.POST_NOTIFICATIONS)

    @SuppressLint("MissingPermission")
    /** forward: risk level for Morty's Telegram ("high" passes quiet hours), or null to keep it on the phone. */
    fun reminder(ctx: Context, id: Int, title: String, text: String, pangoAction: Boolean = true, forward: String? = "high") {
        if (forward != null) Sync.forward(ctx, title, text, if (pangoAction) "pango" else "app", forward)
        if (!canPost(ctx)) return
        ensureChannel(ctx)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(if (pangoAction) pangoIntent(ctx) ?: openApp(ctx, null, id) else openApp(ctx, null, id))
            .apply { if (pangoAction) pangoIntent(ctx)?.let { addAction(0, "פתח פנגו", it) } }
            .build()
        NotificationManagerCompat.from(ctx).notify(id, n)
    }

    /** Heads-up right after parking: pick the curb color from the notification, or open the full picker. */
    @SuppressLint("MissingPermission")
    fun askType(ctx: Context, eventId: String, street: String?) {
        if (!canPost(ctx)) return
        ensureChannel(ctx)
        fun action(type: SpotType, req: Int) = PendingIntent.getBroadcast(
            ctx, req, Intent(ctx, ClassifyActionReceiver::class.java).putExtra("event", eventId).putExtra("type", type.key),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("חנית${street?.let { " ב$it" } ?: ""}. מה צבע המדרכה?")
            .setContentText("לחיצה אחת שומרת. 'אחר' פותח את כל האפשרויות.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, eventId, ID_CLASSIFY))
            .addAction(0, "כחול-לבן", action(SpotType.BLUE_WHITE, 4001))
            .addAction(0, "אפור", action(SpotType.UNMARKED, 4002))
            .addAction(0, "אחר…", openApp(ctx, eventId, 4003))
            .build()
        NotificationManagerCompat.from(ctx).notify(ID_CLASSIFY, n)
    }

    fun cancel(ctx: Context, id: Int) = NotificationManagerCompat.from(ctx).cancel(id)
}

/** Saves the curb color chosen straight from the parking notification. */
class ClassifyActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("event") ?: return
        val type = SpotType.of(intent.getStringExtra("type")) ?: return
        Notifications.cancel(context, Notifications.ID_CLASSIFY)
        val pending = goAsync()
        AppScope.io.launch {
            try { Parking.classify(context, id, type, null) } finally { pending.finish() }
        }
    }
}

/**
 * Parking reminders. Times are exact when the system allows it (USE_EXACT_ALARM on Android 13+),
 * otherwise as close as the system permits.
 */
object Reminders {
    private const val REQ_PAID_STARTS = 2011
    private const val REQ_FREE_ENDING = 2012
    private const val REQ_FREE_ENDED = 2013
    private const val REQ_SHORT_ENDING = 2021
    private const val REQ_SHORT_ENDED = 2022
    private val parkingRequests = listOf(REQ_PAID_STARTS, REQ_FREE_ENDING, REQ_FREE_ENDED, REQ_SHORT_ENDING, REQ_SHORT_ENDED)

    private fun hhmm(t: LocalDateTime) = String.format(Locale.US, "%02d:%02d", t.hour, t.minute)
    private fun millis(t: LocalDateTime) = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    internal fun pending(ctx: Context, req: Int, title: String?, text: String?): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).putExtra("req", req)
        if (title != null) i.putExtra("title", title)
        if (text != null) i.putExtra("text", text)
        return PendingIntent.getBroadcast(ctx, req, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    // Alarms are kept in prefs too, so they can be re-armed after a reboot (see BootReceiver).
    private fun saved(ctx: Context) = ctx.applicationContext.getSharedPreferences("alarms", Context.MODE_PRIVATE)
    internal fun forget(ctx: Context, vararg reqs: Int) = saved(ctx).edit().apply { reqs.forEach { remove(it.toString()) } }.apply()

    /** After a reboot: future reminders are set again; one missed by under 30 minutes is shown now. */
    fun restore(ctx: Context) {
        val now = System.currentTimeMillis()
        saved(ctx).all.forEach { (k, v) ->
            val req = k.toIntOrNull() ?: return@forEach
            val o = try { JSONObject(v as String) } catch (_: Exception) { forget(ctx, req); return@forEach }
            val whenMillis = o.optLong("at"); val title = o.optString("title"); val text = o.optString("text")
            when {
                whenMillis > now -> at(ctx, whenMillis, req, title, text)
                now - whenMillis < 30 * 60_000L -> { forget(ctx, req); Notifications.reminder(ctx, 1100 + req % 100, title, text) }
                else -> forget(ctx, req)
            }
        }
    }

    @SuppressLint("MissingPermission")
    internal fun at(ctx: Context, whenMillis: Long, req: Int, title: String, text: String) {
        saved(ctx).edit().putString(req.toString(), JSONObject().put("at", whenMillis).put("title", title).put("text", text).toString()).apply()
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pending(ctx, req, title, text)
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMillis, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMillis, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMillis, pi)
        }
    }

    /** Cancels the free-time reminders (e.g. once Pango is running). */
    fun cancel(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        listOf(REQ_PAID_STARTS, REQ_FREE_ENDING, REQ_FREE_ENDED).forEach { am.cancel(pending(ctx, it, null, null)) }
        forget(ctx, REQ_PAID_STARTS, REQ_FREE_ENDING, REQ_FREE_ENDED)
    }

    fun cancelAll(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        parkingRequests.forEach { am.cancel(pending(ctx, it, null, null)) }
        forget(ctx, *parkingRequests.toIntArray())
    }

    /**
     * Called right after parking. Outside the permit zone the free hours only count through Pango, so:
     * start Pango now (if paying hours are on), or a reminder before paying hours start;
     * then 10 minutes before and at the moment the free time runs out.
     */
    fun schedule(ctx: Context, rules: Rules, zone: Int?, onBoundary: Boolean, usedToday: Int) {
        cancelAll(ctx)
        if (rules.freeOverride) return
        if (rules.hasPermit && zone != null && zone == rules.permitZone) return
        val now = LocalDateTime.now()
        val g = rules.groupFor(zone)
        if (onBoundary && rules.hasPermit) {
            if (g == null || rules.isPaid(g, now)) {
                Notifications.reminder(ctx, 1002, "הפעל פנגו עכשיו", "רחוב גובל של אזור ${rules.permitZone}: החניה חינם לך רק אם מפעילים פנגו מהדקה הראשונה.")
            } else rules.nextPaidStart(zone, now)?.let { start ->
                at(ctx, millis(start.minusMinutes(10)), REQ_PAID_STARTS, "בעוד 10 דקות מתחילות שעות התשלום",
                    "ברחוב הגובל שבו חנית צריך להפעיל פנגו מ-${hhmm(start)}, ואז זה חינם לך.")
            }
            return
        }
        if (g == null) return
        if (rules.isPaid(g, now)) {
            Notifications.reminder(
                ctx, 1002, "הפעל פנגו עכשיו",
                if (rules.hasPermit) "חנית באזור $zone. השעתיים החינם נספרות רק אם פנגו פועל." else "חנית באזור $zone, שעות תשלום.",
            )
        } else rules.nextPaidStart(zone, now)?.let { start ->
            at(ctx, millis(start.minusMinutes(10)), REQ_PAID_STARTS, "שעות התשלום מתחילות ב-${hhmm(start)}",
                "הרכב באזור $zone. הפעל פנגו כדי שהשעתיים החינם ייספרו, או הזז את הרכב.")
        }
        val e = rules.evaluate(zone, now, 24 * 60, usedToday)
        val paidFrom = e.paidFrom ?: return
        val rate = rules.groupFor(zone)?.visitorHourly?.let { String.format(Locale.US, "₪%.2f", it * (if (rules.hasPermit) 1 - rules.discount else 1.0)) }
        val ending = paidFrom.minusMinutes(10)
        if (millis(ending) > System.currentTimeMillis()) {
            at(ctx, millis(ending), REQ_FREE_ENDING, "בעוד 10 דקות נגמר הזמן החינם",
                "באזור $zone מ-${hhmm(paidFrom)} משלמים${rate?.let { " $it לשעה" } ?: ""}. ודא שפנגו פועל או הזז את הרכב.")
        }
        at(ctx, millis(paidFrom), REQ_FREE_ENDED, "נגמר הזמן החינם",
            "מעכשיו החניה באזור $zone בתשלום${rate?.let { ", $it לשעה" } ?: ""}. ודא שפנגו פועל.")
    }

    /** Short-term (60-minute) spot: warn at 50 minutes and at the limit. */
    fun shortTerm(ctx: Context, parkedAt: Long) {
        val end = parkedAt + 60 * 60_000L
        if (end - 10 * 60_000L > System.currentTimeMillis()) {
            at(ctx, end - 10 * 60_000L, REQ_SHORT_ENDING, "נשארו 10 דקות בחניה קצרת המועד", "החניה קצרת המועד מוגבלת ל-60 דקות, פעם ביום.")
        }
        if (end > System.currentTimeMillis()) at(ctx, end, REQ_SHORT_ENDED, "נגמרו 60 הדקות", "הזז את הרכב, אחרת עלול להגיע דוח.")
    }
}

/** A Pango session the user started: remind 10 minutes before and at the chosen end time. */
object PangoSession {
    private const val REQ_ENDING = 2002
    private const val REQ_ENDED = 2003
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("pango", Context.MODE_PRIVATE)

    fun endTime(ctx: Context): Long? = prefs(ctx).getLong("end", 0L).takeIf { it > System.currentTimeMillis() }

    fun start(ctx: Context, minutes: Int) {
        val end = System.currentTimeMillis() + minutes * 60_000L
        prefs(ctx).edit().putLong("end", end).apply()
        Reminders.cancel(ctx) // paying is now handled by Pango
        val endText = java.text.SimpleDateFormat("HH:mm", Locale("he")).format(java.util.Date(end))
        if (end - 10 * 60_000L > System.currentTimeMillis()) {
            Reminders.at(ctx, end - 10 * 60_000L, REQ_ENDING, "החניה בפנגו נגמרת בעוד 10 דקות", "היא מסתיימת ב-$endText. לחץ כדי לחדש.")
        }
        Reminders.at(ctx, end, REQ_ENDED, "נגמר הזמן בפנגו", "החניה בפנגו הסתיימה. חדש אותה אם הרכב עדיין שם.")
    }

    fun stop(ctx: Context) {
        prefs(ctx).edit().remove("end").apply()
        val am = ctx.getSystemService(AlarmManager::class.java)
        listOf(REQ_ENDING, REQ_ENDED).forEach { am.cancel(Reminders.pending(ctx, it, null, null)) }
        Reminders.forget(ctx, REQ_ENDING, REQ_ENDED)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Reminders.forget(context, intent.getIntExtra("req", 0))
        Notifications.reminder(
            context, 1100 + intent.getIntExtra("req", 0) % 100,
            intent.getStringExtra("title") ?: "תזכורת חניה", intent.getStringExtra("text") ?: "בדוק את החניה",
        )
    }
}
