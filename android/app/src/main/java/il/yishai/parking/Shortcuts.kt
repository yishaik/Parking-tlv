package il.yishai.parking

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.net.Uri
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.compose.ui.graphics.Color

const val ACTION_PARK_HERE = "il.yishai.parking.PARK_HERE"

fun parkHereIntent(ctx: Context) = Intent(ctx, MainActivity::class.java)
    .setAction(ACTION_PARK_HERE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

fun walkToCarIntent(spot: Spot): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${spot.lat},${spot.lon}&mode=w"))
        .setPackage("com.google.android.apps.maps").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** Share where the car is (street, time and a Google Maps link). */
fun shareCarIntent(spot: Spot, street: String?): Intent {
    val text = "הרכב חונה${street?.let { " ב$it" } ?: ""} (מ-${spot.timeText()}): https://www.google.com/maps/search/?api=1&query=${spot.lat},${spot.lon}"
    return Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "שתף את מיקום הרכב")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/** Home-screen widget: where the car is, "I parked here", and walk to the car. */
class ParkingWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val spot = LastSpotStore(context).load()
        val street = spot?.let { ParkingRepository.get(context).streetNear(it.lat, it.lon) }
        val bg = ColorProvider(day = Color(0xFFFFFFFF), night = Color(0xFF191C22))
        val fg = ColorProvider(day = Color(0xFF111111), night = Color(0xFFF1F2F4))
        val accent = ColorProvider(day = Color(0xFF111111), night = Color(0xFFF1F2F4))
        val onAccent = ColorProvider(day = Color(0xFFFFFFFF), night = Color(0xFF111111))
        provideContent {
            Column(GlanceModifier.fillMaxSize().background(bg).cornerRadius(20.dp).padding(12.dp)) {
                Text("הרכב שלי", style = TextStyle(color = fg, fontWeight = FontWeight.Bold, fontSize = 15.sp))
                Text(
                    spot?.let { listOfNotNull(street, it.timeText()).joinToString(" · ") } ?: "לא שמור מקום חניה",
                    style = TextStyle(color = fg, fontSize = 13.sp), maxLines = 1,
                )
                Spacer(GlanceModifier.height(10.dp))
                Row(GlanceModifier.fillMaxWidth()) {
                    Box(
                        GlanceModifier.defaultWeight().height(40.dp).background(accent).cornerRadius(12.dp).clickable(actionStartActivity(parkHereIntent(context))),
                        contentAlignment = Alignment.Center,
                    ) { Text("חניתי כאן", style = TextStyle(color = onAccent, fontWeight = FontWeight.Bold)) }
                    if (spot != null) {
                        Spacer(GlanceModifier.width(8.dp))
                        Box(
                            GlanceModifier.defaultWeight().height(40.dp).background(ColorProvider(day = Color(0xFFE5E7EB), night = Color(0xFF2A2E37))).cornerRadius(12.dp)
                                .clickable(actionStartActivity(walkToCarIntent(spot))),
                            contentAlignment = Alignment.Center,
                        ) { Text("הליכה לרכב", style = TextStyle(color = fg)) }
                    }
                }
            }
        }
    }
}

class ParkingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ParkingWidget()
}

/** Quick Settings tile: one tap saves "I parked here". */
class ParkTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply { state = Tile.STATE_INACTIVE; label = "חניתי כאן"; updateTile() }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        val intent = parkHereIntent(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 5001, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(intent)
        }
    }
}

/**
 * Guesses the curb color from a photo: share of blue, red, white and gray pixels in the lower part of the frame.
 * Returns null when it cannot tell; the user always confirms.
 */
object CurbPhoto {
    fun detect(bmp: Bitmap): SpotType? {
        val w = bmp.width; val h = bmp.height
        var blue = 0; var red = 0; var white = 0; var gray = 0; var n = 0
        val hsv = FloatArray(3)
        val step = maxOf(1, minOf(w, h) / 120)
        for (y in h / 3 until h step step) for (x in 0 until w step step) {
            AColor.colorToHSV(bmp.getPixel(x, y), hsv)
            val (hue, s, v) = Triple(hsv[0], hsv[1], hsv[2])
            n++
            when {
                s > 0.35f && v > 0.25f && hue in 195f..250f -> blue++
                s > 0.40f && v > 0.30f && (hue < 15f || hue > 340f) -> red++
                s < 0.15f && v > 0.75f -> white++
                s < 0.15f && v in 0.30f..0.75f -> gray++
            }
        }
        if (n == 0) return null
        val b = blue.toFloat() / n; val r = red.toFloat() / n; val wt = white.toFloat() / n; val g = gray.toFloat() / n
        return when {
            b > 0.06f && wt > 0.05f && b >= r -> SpotType.BLUE_WHITE
            r > 0.06f && wt > 0.05f -> SpotType.RED_WHITE
            g > 0.35f && b < 0.03f && r < 0.03f -> SpotType.UNMARKED
            else -> null
        }
    }
}
