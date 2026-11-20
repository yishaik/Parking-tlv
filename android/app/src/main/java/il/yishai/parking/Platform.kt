package il.yishai.parking

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Perms {
    fun has(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    fun hasLocation(ctx: Context) = has(ctx, Manifest.permission.ACCESS_FINE_LOCATION) || has(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
    fun hasBackground(ctx: Context) = Build.VERSION.SDK_INT < 29 || has(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    fun hasBtConnect(ctx: Context) = Build.VERSION.SDK_INT < 31 || has(ctx, Manifest.permission.BLUETOOTH_CONNECT)
}

object LocationHelper {
    @SuppressLint("MissingPermission")
    suspend fun current(ctx: Context, timeoutMs: Long = 12000): Location? {
        if (!Perms.hasLocation(ctx)) return null
        val fused = LocationServices.getFusedLocationProviderClient(ctx)
        return try {
            withTimeoutOrNull(timeoutMs) {
                fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token).await()
            } ?: fused.lastLocation.await()
        } catch (e: Exception) {
            null
        }
    }
}

data class Spot(val lat: Double, val lon: Double, val time: Long, val source: String) {
    fun timeText(): String = SimpleDateFormat("EEE HH:mm", Locale("he")).format(Date(time))
}

class LastSpotStore(ctx: Context) {
    private val p = ctx.getSharedPreferences("parking", Context.MODE_PRIVATE)

    fun save(lat: Double, lon: Double, source: String) {
        p.edit().putString("lat", lat.toString()).putString("lon", lon.toString())
            .putLong("time", System.currentTimeMillis()).putString("source", source).commit()
    }

    fun load(): Spot? {
        val lat = p.getString("lat", null)?.toDoubleOrNull() ?: return null
        val lon = p.getString("lon", null)?.toDoubleOrNull() ?: return null
        return Spot(lat, lon, p.getLong("time", 0), p.getString("source", "") ?: "")
    }

    fun clear() = p.edit().remove("lat").remove("lon").remove("time").remove("source").apply()

    var carDeviceAddress: String?
        get() = p.getString("carDevice", null)
        set(v) { p.edit().putString("carDevice", v).apply() }

    var carDeviceName: String?
        get() = p.getString("carDeviceName", null)
        set(v) { p.edit().putString("carDeviceName", v).apply() }
}

object Launch {
    private const val PANGO = "com.unicell.pangoandroid"
    const val DIGITEL_URL = "https://www.tel-aviv.gov.il/Residents/Digitel/Pages/Digitel.aspx"

    fun navigate(ctx: Context, lat: Double, lon: Double, walking: Boolean = false) {
        val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lon&mode=${if (walking) "w" else "d"}"))
            .setPackage("com.google.android.apps.maps").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(nav)
        } catch (e: ActivityNotFoundException) {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun pango(ctx: Context) {
        val i = ctx.packageManager.getLaunchIntentForPackage(PANGO)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PANGO"))
        try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: ActivityNotFoundException) {}
    }

    fun web(ctx: Context, url: String) {
        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: ActivityNotFoundException) {}
    }
}
