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
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object Notifications {
    private const val CHANNEL = "parking"
    const val EXTRA_CLASSIFY = "classify_event"

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

    @SuppressLint("MissingPermission")
    private fun post(ctx: Context, id: Int, title: String, text: String, classifyId: String?, pangoAction: Boolean = false) {
        if (Build.VERSION.SDK_INT >= 33 && !Perms.has(ctx, Manifest.permission.POST_NOTIFICATIONS)) return
        ensureChannel(ctx)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(if (pangoAction) pangoIntent(ctx) ?: openApp(ctx, null, id) else openApp(ctx, classifyId, id))
            .apply { if (pangoAction) pangoIntent(ctx)?.let { addAction(0, "פתח פנגו", it) } }
            .build()
        NotificationManagerCompat.from(ctx).notify(id, n)
    }

    fun askType(ctx: Context, eventId: String) =
        post(ctx, 1001, "שמרתי איפה חנית", "לחץ כדי לסמן איזו חניה זו. זה בונה את הסטטיסטיקה שלך.", eventId)

    fun paidSoon(ctx: Context, text: String) = post(ctx, 1002, "החניה החינמית נגמרת בקרוב", text, null, pangoAction = true)

    fun pangoEnding(ctx: Context, text: String) = post(ctx, 1003, "החניה בפנגו נגמרת בקרוב", text, null, pangoAction = true)
}

object Reminders {
    private const val REQUEST = 2001

    private fun intent(ctx: Context, text: String?): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java)
        if (text != null) i.putExtra("text", text)
        return PendingIntent.getBroadcast(ctx, REQUEST, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun cancel(ctx: Context) = ctx.getSystemService(AlarmManager::class.java).cancel(intent(ctx, null))

    /** Outside the permit zone: notify ~10 minutes before paid parking would start (after the free allowance). */
    fun schedule(ctx: Context, rules: Rules, zone: Int?) {
        cancel(ctx)
        if (rules.freeOverride || rules.groupFor(zone) == null) return
        if (rules.hasPermit && zone == rules.permitZone) return
        val paidFrom = rules.evaluate(zone, LocalDateTime.now(), 24 * 60).paidFrom ?: return
        val text = "באזור $zone התשלום מתחיל ב-${paidFrom.format(DateTimeFormatter.ofPattern("HH:mm"))}. הפעל פנגו או הזז את הרכב."
        val at = paidFrom.minusMinutes(10).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (at <= System.currentTimeMillis()) { Notifications.paidSoon(ctx, text); return }
        ctx.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(ctx, text))
    }
}

/** A Pango session the user started: remind 10 minutes before the chosen end time so it can be extended. */
object PangoSession {
    private const val REQUEST = 2002
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("pango", Context.MODE_PRIVATE)

    fun endTime(ctx: Context): Long? = prefs(ctx).getLong("end", 0L).takeIf { it > System.currentTimeMillis() }

    private fun intent(ctx: Context, text: String?): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).putExtra("kind", "pango")
        if (text != null) i.putExtra("text", text)
        return PendingIntent.getBroadcast(ctx, REQUEST, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun start(ctx: Context, minutes: Int) {
        val end = System.currentTimeMillis() + minutes * 60_000L
        prefs(ctx).edit().putLong("end", end).apply()
        Reminders.cancel(ctx) // paid parking is now covered by Pango
        val endText = java.text.SimpleDateFormat("HH:mm", java.util.Locale("he")).format(java.util.Date(end))
        val at = end - 10 * 60_000L
        val text = "החניה בפנגו מסתיימת ב-$endText. לחץ כדי לחדש."
        if (at <= System.currentTimeMillis()) return
        ctx.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(ctx, text))
    }

    fun stop(ctx: Context) {
        prefs(ctx).edit().remove("end").apply()
        ctx.getSystemService(AlarmManager::class.java).cancel(intent(ctx, null))
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text") ?: "בדוק את החניה"
        if (intent.getStringExtra("kind") == "pango") Notifications.pangoEnding(context, text) else Notifications.paidSoon(context, text)
    }
}
