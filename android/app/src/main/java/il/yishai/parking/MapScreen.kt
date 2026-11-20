package il.yishai.parking

import android.content.Context
import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import kotlin.math.cos
import kotlin.math.hypot

/** The city's own Hebrew base map (ArcGIS cached tiles, Web Mercator). */
private class CityTiles : OnlineTileSourceBase(
    "TLV-Heb", 0, 21, 256, "",
    arrayOf("https://gisn.tel-aviv.gov.il/arcgis/rest/services/WM/IView2MapHebWM/MapServer"), "© עיריית תל אביב-יפו",
) {
    override fun getTileURLString(index: Long): String =
        "${baseUrl}/tile/${MapTileIndex.getZoom(index)}/${MapTileIndex.getY(index)}/${MapTileIndex.getX(index)}"
}

private fun dot(color: Int, sizePx: Int, stroke: Int = AColor.WHITE) = GradientDrawable().apply {
    shape = GradientDrawable.OVAL; setColor(color); setStroke(3, stroke); setSize(sizePx, sizePx)
}

/** Short colored stroke along the nearest street centreline, so a mark reads like painted curb. */
private fun curbSegment(repo: ParkingRepository, lat: Double, lon: Double, halfMeters: Double = 12.0): List<GeoPoint> {
    val kx = 111320.0 * cos(Math.toRadians(lat)); val ky = 110540.0
    var best: Triple<Double, DoubleArray, DoubleArray>? = null
    for (s in repo.streets) for (p in s.paths) for (i in 1 until p.size) {
        val ax = (p[i - 1][0] - lon) * kx; val ay = (p[i - 1][1] - lat) * ky
        val bx = (p[i][0] - lon) * kx; val by = (p[i][1] - lat) * ky
        val dx = bx - ax; val dy = by - ay; val len2 = dx * dx + dy * dy
        if (len2 == 0.0) continue
        val t = (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        val d = hypot(ax + t * dx, ay + t * dy)
        if (best == null || d < best.first) best = Triple(d, doubleArrayOf(ax + t * dx, ay + t * dy), doubleArrayOf(dx, dy))
    }
    val b = best ?: return listOf(GeoPoint(lat, lon), GeoPoint(lat + 0.00005, lon))
    if (b.first > 30) return listOf(GeoPoint(lat, lon), GeoPoint(lat + 0.00005, lon))
    val len = hypot(b.third[0], b.third[1])
    // Offset toward the tapped side of the street so each side can carry its own color.
    val side = if (b.first > 1) doubleArrayOf(-b.second[0] / b.first * 4, -b.second[1] / b.first * 4) else doubleArrayOf(0.0, 0.0)
    val ux = b.third[0] / len; val uy = b.third[1] / len
    fun pt(s: Double) = GeoPoint(lat + (b.second[1] + side[1] + uy * s) / ky, lon + (b.second[0] + side[0] + ux * s) / kx)
    return listOf(pt(-halfMeters), pt(halfMeters))
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MapScreen(
    repo: ParkingRepository,
    garages: List<Garage>,
    spot: Spot?,
    center: Pair<Double, Double>?,
    events: List<ParkingEvent>,
    onBack: () -> Unit,
    onCurbsChanged: () -> Unit,
) {
    val ctx = LocalContext.current
    val curbs = remember { CurbStore(ctx) }
    var marks by remember { mutableStateOf(curbs.all()) }
    var pending by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var mapRef by remember { mutableStateOf<MapView?>(null) }

    val odds = remember(events) { Planner.odds(events) }
    fun redraw(map: MapView) = drawOverlays(ctx, map, repo, garages, spot, marks, odds) { lat, lon -> pending = lat to lon }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                Configuration.getInstance().apply { userAgentValue = c.packageName; load(c, c.getSharedPreferences("osmdroid", Context.MODE_PRIVATE)) }
                MapView(c).apply {
                    setTileSource(CityTiles())
                    setMultiTouchControls(true)
                    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                    controller.setZoom(17.5)
                    val (la, lo) = center ?: (32.0917 to 34.7816)
                    controller.setCenter(GeoPoint(la, lo))
                    mapRef = this
                    redraw(this)
                }
            },
            update = { redraw(it) },
        )
        DisposableEffect(Unit) { onDispose { mapRef?.onDetach() } }

        // Header
        Row(Modifier.statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(onClick = onBack, shape = CircleShape, color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface, shadowElevation = 4.dp) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "חזרה") }
            }
            Spacer(Modifier.width(8.dp))
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface, shadowElevation = 4.dp) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text("מפת חניה · אזור ${repo.rules.permitZone ?: ""}", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("לחיצה ארוכה על מדרכה = סימון", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // Legend + mark-here
        Surface(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp).fillMaxWidth(),
            shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface, shadowElevation = 4.dp,
        ) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Legend(Color(0xFF22C55E), "אחוזות החוף"); Legend(Color(0xFF3B82F6), "קרוב לבית"); Legend(Color(0xFF8B5CF6), "חניון נוסף")
                Legend(Color(0xFFF59E0B), "פריקה וטעינה"); Legend(Color(0xFF6B7280), "אוטותל"); Legend(Color(0xFFEF4444), "הרכב שלי")
                if (odds.isNotEmpty()) { Legend(Color(0xFF22C55E), "קל למצוא (${BUCKET_LABELS[bucketOf(System.currentTimeMillis())]})"); Legend(Color(0xFFF59E0B), "בינוני"); Legend(Color(0xFFEF4444), "קשה") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${marks.size} סימונים", Modifier.weight(1f), fontSize = 13.sp, maxLines = 1)
                TextButton(onClick = {
                    val c = mapRef?.mapCenter ?: return@TextButton
                    pending = c.latitude to c.longitude
                }) { Icon(Icons.Outlined.MyLocation, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("סמן במרכז המפה", maxLines = 1) }
            }
        } }
        Box(Modifier.align(Alignment.Center).size(10.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)))
    }

    pending?.let { (lat, lon) ->
        val street = remember(lat, lon) { repo.streetNear(lat, lon) }
        val existing = marks.firstOrNull { Geo.distance(it.lat, it.lon, lat, lon) < 8 }
        ModalBottomSheet(onDismissRequest = { pending = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("מה הסימון כאן?", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(street?.let { "רחוב $it" } ?: "לא זוהה רחוב קרוב", color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CurbType.entries.forEach { t ->
                        Row(
                            Modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable {
                                curbs.add(lat, lon, t, street, "map"); marks = curbs.all(); pending = null; onCurbsChanged()
                            }.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CurbSwatch(t)
                            Spacer(Modifier.width(8.dp))
                            Text(t.label, fontSize = 14.sp)
                        }
                    }
                }
                if (existing != null) TextButton(onClick = { curbs.delete(existing.id); marks = curbs.all(); pending = null; onCurbsChanged() }) {
                    Text("מחק את הסימון הקיים (${CurbType.of(existing.type)?.label ?: existing.type})")
                }
            }
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 11.sp)
    }
}

/** Curb swatch: striped for blue-white / red-white, solid otherwise. */
@Composable
fun CurbSwatch(t: CurbType) {
    Row(Modifier.width(28.dp).height(12.dp).clip(RoundedCornerShape(3.dp))) {
        val striped = t == CurbType.BLUE_WHITE || t == CurbType.RED_WHITE || t == CurbType.PREFERRED
        repeat(4) { i ->
            Box(Modifier.weight(1f).fillMaxSize().background(if (striped && i % 2 == 1) Color.White else Color(t.color)))
        }
    }
}

private fun drawOverlays(
    ctx: Context, map: MapView, repo: ParkingRepository, garages: List<Garage>, spot: Spot?, marks: List<CurbMark>,
    odds: Map<String, StreetOdds>, onLongPress: (Double, Double) -> Unit,
) {
    map.overlays.clear()
    map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
        override fun singleTapConfirmedHelper(p: GeoPoint?) = false
        override fun longPressHelper(p: GeoPoint?): Boolean { p?.let { onLongPress(it.latitude, it.longitude) }; return true }
    }))

    // Permit zone outline
    repo.permitZoneRings.forEach { ring ->
        map.overlays.add(Polygon(map).apply {
            points = ring.map { GeoPoint(it[1], it[0]) }
            fillPaint.color = AColor.argb(28, 37, 99, 235)
            outlinePaint.color = AColor.argb(230, 37, 99, 235); outlinePaint.strokeWidth = 9f
            outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(28f, 14f), 0f)
            setOnClickListener { _, _, _ -> false }
        })
    }

    // Parking odds per street (from the user's history), wide translucent strokes under everything else
    if (odds.isNotEmpty()) repo.streets.forEach { st ->
        val o = odds[st.name] ?: return@forEach
        val color = when (o.level) { 2 -> AColor.argb(110, 34, 197, 94); 1 -> AColor.argb(110, 245, 158, 11); else -> AColor.argb(110, 239, 68, 68) }
        st.paths.forEach { p ->
            map.overlays.add(Polyline(map).apply {
                setPoints(p.map { GeoPoint(it[1], it[0]) }); outlinePaint.color = color; outlinePaint.strokeWidth = 22f
                title = "${st.name}: ${o.parkings} חניות, חיפוש ממוצע ${o.avgSearchMinutes.toInt()} ד׳" + if (o.sameTimeOfDay) "" else " (כל שעות היום)"
            })
        }
    }

    // Curb marks as painted strokes
    marks.forEach { m ->
        val t = CurbType.of(m.type) ?: return@forEach
        val seg = curbSegment(repo, m.lat, m.lon)
        val striped = t == CurbType.BLUE_WHITE || t == CurbType.RED_WHITE || t == CurbType.PREFERRED
        map.overlays.add(Polyline(map).apply {
            setPoints(seg); outlinePaint.color = t.color.toInt(); outlinePaint.strokeWidth = 14f; outlinePaint.strokeCap = Paint.Cap.BUTT
            title = "${t.label}${m.street?.let { " · $it" } ?: ""}"
        })
        if (striped) map.overlays.add(Polyline(map).apply {
            setPoints(seg); outlinePaint.color = AColor.WHITE; outlinePaint.strokeWidth = 14f; outlinePaint.strokeCap = Paint.Cap.BUTT
            outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f, 14f), 0f)
        })
    }

    fun marker(lat: Double, lon: Double, color: Int, size: Int, title: String, snippet: String = "") = Marker(map).apply {
        position = GeoPoint(lat, lon); icon = dot(color, size); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        this.title = title; this.snippet = snippet
    }
    repo.autotel.forEach { map.overlays.add(marker(it.lat, it.lon, AColor.rgb(107, 114, 128), 22, "אוטותל: חניה שמורה", it.label)) }
    repo.loadingSigns.forEach { map.overlays.add(marker(it.lat, it.lon, AColor.rgb(245, 158, 11), 24, "פריקה וטעינה", it.text)) }
    repo.otherLots.forEach { l ->
        map.overlays.add(marker(l.lat, l.lon, AColor.rgb(139, 92, 246), 34, l.name,
            listOfNotNull(l.kind, l.spots?.let { "$it מקומות" }, when (l.free) { true -> "חינם"; false -> "בתשלום"; null -> null }, "מקור: ${l.source}").joinToString(" · ")))
    }
    repo.nightLots.forEach { map.overlays.add(marker(it.lat, it.lon, AColor.rgb(59, 130, 246), 38, "קרוב לבית: ${it.name}", it.notes)) }
    garages.forEach { g ->
        map.overlays.add(marker(g.lat, g.lon, AColor.rgb(34, 197, 94), 40, g.name, "${statusLabel(g.status)} · ${g.dayTariff.take(120)}"))
    }
    spot?.let { map.overlays.add(marker(it.lat, it.lon, AColor.rgb(239, 68, 68), 48, "הרכב שלי", it.timeText())) }

    if (Perms.hasLocation(ctx)) map.overlays.add(MyLocationNewOverlay(GpsMyLocationProvider(ctx), map).apply { enableMyLocation() })
    map.invalidate()
}
