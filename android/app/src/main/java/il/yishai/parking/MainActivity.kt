package il.yishai.parking

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LocalParking
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.PinDrop
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme(background = Color(0xFF0F1115), surface = Color(0xFF0F1115)) else lightColorScheme(background = Color(0xFFF3F4F6), surface = Color(0xFFF3F4F6))) {
                ParkingScreen(dark)
            }
        }
    }
}

/** Semantic colors for the status hero and occupancy dots. */
private data class Tone(val container: Color, val strong: Color, val onStrong: Color, val text: Color)

private fun tone(free: Boolean?, dark: Boolean): Tone = when (free) {
    true -> if (dark) Tone(Color(0xFF12321F), Color(0xFF3DD68C), Color(0xFF06210F), Color(0xFFB8F2D2)) else Tone(Color(0xFFDDF5E7), Color(0xFF1E8E53), Color.White, Color(0xFF0E5130))
    false -> if (dark) Tone(Color(0xFF3A2710), Color(0xFFF5A524), Color(0xFF2B1A04), Color(0xFFFBDDB0)) else Tone(Color(0xFFFDEBD3), Color(0xFFC9730A), Color.White, Color(0xFF6B3D04))
    null -> if (dark) Tone(Color(0xFF1C1F26), Color(0xFF5B6270), Color.White, Color(0xFFC9CED8)) else Tone(Color(0xFFE6E8EC), Color(0xFF6B7280), Color.White, Color(0xFF374151))
}

private fun occupancyColor(s: String) = when (s) {
    "פנוי" -> Color(0xFF22C55E)
    "מעט" -> Color(0xFFF59E0B)
    "מלא", "סגור" -> Color(0xFFEF4444)
    else -> Color(0xFF9CA3AF)
}

private const val HERE = -1

data class UiState(
    val loading: Boolean = true,
    val myLat: Double? = null,
    val myLon: Double? = null,
    val myZone: Int? = null,
    val garages: List<Garage> = emptyList(),
    val live: Boolean = false,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ParkingScreen(dark: Boolean) {
    val ctx = LocalContext.current.applicationContext
    val repo = remember { ParkingRepository(ctx) }
    val store = remember { LastSpotStore(ctx) }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(UiState()) }
    var focus by remember { mutableStateOf<Int?>(null) }
    var spot by remember { mutableStateOf(store.load()) }
    var carName by remember { mutableStateOf(store.carDeviceName) }
    var hasBackground by remember { mutableStateOf(Perms.hasBackground(ctx)) }
    var setupOpen by remember { mutableStateOf(false) }
    var pickCar by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            state = state.copy(loading = true)
            val loc = LocationHelper.current(ctx)
            val (garages, live) = repo.garages()
            val zone = loc?.let { repo.zoneAt(it.latitude, it.longitude) }
            state = UiState(false, loc?.latitude, loc?.longitude, zone, garages, live)
            if (focus == null) focus = if (zone != null || repo.places.isEmpty()) HERE else 0
            spot = store.load()
        }
    }

    val locationPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }
    val backgroundPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasBackground = Perms.hasBackground(ctx) }
    val btPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) pickCar = true }

    LaunchedEffect(Unit) {
        if (Perms.hasLocation(ctx)) refresh()
        else locationPerm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    if (pickCar) CarPicker(ctx, onPick = { addr, name ->
        store.carDeviceAddress = addr; store.carDeviceName = name; carName = name; pickCar = false
    }, onDismiss = { pickCar = false })

    if (setupOpen) AlertDialog(
        onDismissRequest = { setupOpen = false },
        title = { Text("שמירת חניה אוטומטית") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("כשה-Bluetooth של הרכב מתנתק, האפליקציה שומרת איפה חנית.")
                SetupStep(1, "בחר את הרכב", carName ?: "לא נבחר", carName != null) {
                    if (Perms.hasBtConnect(ctx)) pickCar = true
                    else if (Build.VERSION.SDK_INT >= 31) btPerm.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }
                SetupStep(2, "אשר מיקום 'תמיד'", if (hasBackground) "מאושר" else "נדרש לשמירה ברקע", hasBackground) {
                    if (Build.VERSION.SDK_INT >= 29) backgroundPerm.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
            }
        },
        confirmButton = { TextButton(onClick = { setupOpen = false }) { Text("סגור") } },
    )

    val f = focus ?: HERE
    val place = repo.places.getOrNull(f)
    val pLat = place?.lat ?: state.myLat
    val pLon = place?.lon ?: state.myLon
    val pZone = if (place != null) repo.zoneAt(place.lat, place.lon) else state.myZone
    val summary = if (pLat == null) null else repo.rules.summary(pZone, LocalDateTime.now())
    val garages = if (pLat == null || pLon == null) emptyList() else
        state.garages.map { it.copy(distance = Geo.distance(pLat, pLon, it.lat, it.lon)) }.sortedBy { it.distance }.take(8)
    val nightByName = remember { repo.nightLots.associateBy { it.name.trim() } }
    val autoReady = carName != null && hasBackground
    val cardColor = if (dark) Color(0xFF191C22) else Color.White

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).navigationBarsPadding().padding(16.dp, 8.dp, 16.dp, 12.dp)) {
                Row(
                    Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.onBackground).clickable { Launch.pango(ctx) },
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.LocalParking, null, tint = MaterialTheme.colorScheme.background)
                    Spacer(Modifier.width(8.dp))
                    Text("הפעל חניה בפנגו", color = MaterialTheme.colorScheme.background, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                }
            }
        },
    ) { inner ->
        LazyColumn(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = inner.calculateBottomPadding() + 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE · HH:mm", Locale("he"))), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("חניה בת״א", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    }
                    CircleIcon(Icons.Outlined.Refresh, "רענן", cardColor, MaterialTheme.colorScheme.onSurface) { refresh() }
                }
            }

            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("כאן", Icons.Outlined.MyLocation, f == HERE, cardColor) { focus = HERE }
                    repo.places.forEachIndexed { i, p ->
                        Pill(p.label, if (i == 0) Icons.Outlined.Home else Icons.Outlined.Work, f == i, cardColor) { focus = i }
                    }
                }
            }

            item {
                val t = tone(summary?.freeNow, dark)
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(t.container).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)).background(t.strong),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                        ) {
                            Text("אזור", fontSize = 12.sp, color = t.onStrong)
                            Text(summary?.zone?.toString() ?: "–", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = t.onStrong, lineHeight = 34.sp)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(if (state.loading && summary == null) "מאתר מיקום…" else summary?.title ?: "אין מיקום", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = t.text)
                            if (!summary?.subtitle.isNullOrBlank()) Text(summary!!.subtitle, fontSize = 14.sp, color = t.text)
                        }
                    }
                    if (!summary?.detail.isNullOrBlank()) Text(summary!!.detail, fontSize = 13.sp, color = t.text)
                }
            }

            item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cardColor).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    val s = spot
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF3B82F6).copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.DirectionsCar, null, tint = Color(0xFF3B82F6))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("הרכב שלי", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            val sub = when {
                                s == null -> "לא שמור מקום חניה"
                                state.myLat != null -> "${s.timeText()} · ${distanceText(Geo.distance(state.myLat!!, state.myLon!!, s.lat, s.lon))}"
                                else -> s.timeText()
                            }
                            Text(sub, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (s != null) CircleIcon(Icons.AutoMirrored.Outlined.DirectionsWalk, "ניווט רגלי לרכב", MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.surface, size = 52) {
                            Launch.navigate(ctx, s.lat, s.lon, walking = true)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SoftButton("חניתי כאן", Icons.Outlined.PinDrop, Modifier.weight(1f), dark) {
                            if (state.myLat != null) { store.save(state.myLat!!, state.myLon!!, "manual"); spot = store.load() }
                        }
                        if (s != null) SoftButton("מחק", Icons.Outlined.Delete, Modifier.weight(1f), dark) { store.clear(); spot = null }
                    }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { setupOpen = true }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Bluetooth, null, Modifier.size(18.dp), tint = if (autoReady) Color(0xFF22C55E) else Color(0xFFF59E0B))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (autoReady) "שמירה אוטומטית · $carName" else "הפעל שמירה אוטומטית",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                        )
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            item {
                SectionHeader("חניונים קרובים", if (state.live) "תפוסה חיה" else "נתונים שמורים", if (state.live) Color(0xFF22C55E) else null)
            }
            item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cardColor)) {
                    if (garages.isEmpty()) Text(if (state.loading) "טוען…" else "אין חניונים להצגה", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    garages.forEachIndexed { i, g ->
                        if (i > 0) HorizontalDivider(Modifier.padding(start = 40.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        GarageRow(g, nightByName[g.name.trim()], expanded == g.name, onToggle = { expanded = if (expanded == g.name) null else g.name }) { Launch.navigate(ctx, g.lat, g.lon) }
                    }
                }
            }

        }
    }
}

@Composable
private fun Tag(text: String) {
    Text(
        text, fontSize = 11.sp, color = Color(0xFF3B82F6),
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF3B82F6).copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun SectionHeader(title: String, note: String, dot: Color?) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (dot != null) { Box(Modifier.size(8.dp).clip(CircleShape).background(dot)); Spacer(Modifier.width(6.dp)) }
        Text(note, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Pill(label: String, icon: ImageVector, selected: Boolean, idle: Color, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.onBackground else idle
    val fg = if (selected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.clip(CircleShape).background(bg).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = fg)
        Spacer(Modifier.width(6.dp))
        Text(label, color = fg, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun CircleIcon(icon: ImageVector, description: String, bg: Color, fg: Color, size: Int = 42, onClick: () -> Unit) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(bg).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = fg)
    }
}

@Composable
private fun SoftButton(label: String, icon: ImageVector, modifier: Modifier, dark: Boolean, onClick: () -> Unit) {
    Row(
        modifier.height(44.dp).clip(RoundedCornerShape(14.dp)).background(if (dark) Color(0xFF242832) else Color(0xFFF1F2F4)).clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 14.sp)
    }
}

@Composable
private fun GarageRow(g: Garage, night: NightLot?, open: Boolean, onToggle: () -> Unit, onNavigate: () -> Unit) {
    Column(Modifier.fillMaxWidth().animateContentSize().clickable(onClick = onToggle).padding(16.dp, 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(occupancyColor(g.status)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(g.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(statusLabel(g.status), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val tags = buildList {
                    if (night != null) add("קרוב לבית")
                    Regex("(\\d{2})%").find(night?.notes.orEmpty() + " " + g.notes)?.let { add("${it.groupValues[1]}% לתושב") }
                }
                if (tags.isNotEmpty()) Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { tags.forEach { Tag(it) } }
            }
            Text(distanceText(g.distance), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) {
            Column(Modifier.padding(start = 24.dp, top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (g.dayTariff.isNotBlank()) Text(g.dayTariff, fontSize = 13.sp)
                if (g.nightTariff.isNotBlank()) Text("לילה: ${g.nightTariff}", fontSize = 13.sp)
                if (night != null) Text("חניון 'קרוב לבית': חינם בלילות ובסופי שבוע לבעלי תו משויך", fontSize = 13.sp)
                if (g.notes.isNotBlank()) Text(g.notes, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Surface(onClick = onNavigate, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.onSurface) {
                    Text("נווט לחניון", Modifier.padding(horizontal = 16.dp, vertical = 10.dp), color = MaterialTheme.colorScheme.surface, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun SetupStep(n: Int, title: String, status: String, done: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(if (done) Color(0xFF22C55E) else Color(0xFF9CA3AF)), contentAlignment = Alignment.Center) {
            Text(if (done) "✓" else "$n", color = Color.White, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun CarPicker(ctx: Context, onPick: (String, String) -> Unit, onDismiss: () -> Unit) {
    val devices = remember {
        try {
            ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices
                ?.map { it.address to (it.name ?: it.address) }?.sortedBy { it.second } ?: emptyList()
        } catch (_: SecurityException) { emptyList() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("איזה Bluetooth הוא הרכב?") },
        text = {
            Column {
                if (devices.isEmpty()) Text("אין מכשירים מצומדים")
                devices.forEach { (addr, name) ->
                    Text(name, Modifier.fillMaxWidth().clickable { onPick(addr, name) }.padding(vertical = 12.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("ביטול") } },
    )
}
