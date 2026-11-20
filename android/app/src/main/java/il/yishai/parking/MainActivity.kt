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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.LocalDateTime

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { ParkingScreen() }
            }
        }
    }
}

/** Where the screen is evaluated: "here" (GPS) or one of the saved places (index). */
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
fun ParkingScreen() {
    val ctx = LocalContext.current.applicationContext
    val repo = remember { ParkingRepository(ctx) }
    val store = remember { LastSpotStore(ctx) }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(UiState()) }
    var focus by remember { mutableStateOf<Int?>(null) } // null = not chosen yet
    var spot by remember { mutableStateOf(store.load()) }
    var carName by remember { mutableStateOf(store.carDeviceName) }
    var hasBackground by remember { mutableStateOf(Perms.hasBackground(ctx)) }
    var pickCar by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            state = state.copy(loading = true)
            val loc = LocationHelper.current(ctx)
            val (garages, live) = repo.garages()
            val zone = loc?.let { repo.zoneAt(it.latitude, it.longitude) }
            state = UiState(false, loc?.latitude, loc?.longitude, zone, garages, live)
            // First load: use GPS if we're inside Tel Aviv zones, otherwise the first saved place (home).
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

    // Resolve the point the screen is about.
    val f = focus ?: HERE
    val place = repo.places.getOrNull(f)
    val pLat = place?.lat ?: state.myLat
    val pLon = place?.lon ?: state.myLon
    val pZone = if (place != null) repo.zoneAt(place.lat, place.lon) else state.myZone
    val summary = if (pLat == null) null else repo.rules.summary(pZone, LocalDateTime.now())
    val garages = if (pLat == null || pLon == null) emptyList() else
        state.garages.map { it.copy(distance = Geo.distance(pLat, pLon, it.lat, it.lon)) }.sortedBy { it.distance }.take(10)
    val nightLots = if (pLat == null || pLon == null) emptyList() else
        repo.nightLots.map { it.copy(distance = Geo.distance(pLat, pLon, it.lat, it.lon)) }.filter { it.distance < 1200 }.sortedBy { it.distance }.take(5)
    val farAway = state.myLat != null && state.myZone == null

    LazyColumn(
        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("חניה בת״א", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                TextButton(onClick = { refresh() }) { Text(if (state.loading) "טוען…" else "רענן") }
            }
        }

        // Where to check
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("בדיקה עבור", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = f == HERE, onClick = { focus = HERE }, label = { Text("המיקום שלי") })
                    repo.places.forEachIndexed { i, p ->
                        FilterChip(selected = f == i, onClick = { focus = i }, label = { Text(p.label) })
                    }
                }
                if (farAway && f == HERE) {
                    Text("אתה כרגע מחוץ לתל אביב. בחר כתובת כדי לתכנן מראש.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // Status
        item {
            val bg = when (summary?.freeNow) {
                true -> Color(0xFF1B5E20)
                false -> Color(0xFF8D4A00)
                null -> MaterialTheme.colorScheme.surfaceVariant
            }
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = bg)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val fg = if (summary?.freeNow == null) MaterialTheme.colorScheme.onSurfaceVariant else Color.White
                    Text(summary?.headline ?: "ממתין למיקום…", color = fg, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (!summary?.detail.isNullOrBlank()) Text(summary!!.detail, color = fg)
                }
            }
        }

        // My car
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("הרכב שלי", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    val s = spot
                    if (s == null) Text("לא שמור מקום חניה")
                    else {
                        val dist = if (state.myLat != null) " · " + distanceText(Geo.distance(state.myLat!!, state.myLon!!, s.lat, s.lon)) + " ממך" else ""
                        Text("חנית ${s.timeText()}$dist")
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (s != null) Button(onClick = { Launch.navigate(ctx, s.lat, s.lon, walking = true) }) { Text("ניווט לרכב") }
                        OutlinedButton(enabled = state.myLat != null, onClick = {
                            store.save(state.myLat!!, state.myLon!!, "manual"); spot = store.load()
                        }) { Text("חניתי כאן") }
                        if (s != null) OutlinedButton(onClick = { store.clear(); spot = null }) { Text("מחק מיקום") }
                    }
                    HorizontalDivider()
                    val ready = carName != null && hasBackground
                    Text(
                        if (ready) "שמירה אוטומטית פעילה: כשהרכב ($carName) מתנתק"
                        else "שמירה אוטומטית: צריך לבחור את ה-Bluetooth של הרכב" + if (!hasBackground) " ולאשר מיקום 'תמיד'" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (!ready) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            if (Perms.hasBtConnect(ctx)) pickCar = true
                            else if (Build.VERSION.SDK_INT >= 31) btPerm.launch(Manifest.permission.BLUETOOTH_CONNECT)
                        }) { Text(if (carName == null) "בחר רכב" else "החלף רכב") }
                        if (!hasBackground && Build.VERSION.SDK_INT >= 29) {
                            OutlinedButton(onClick = { backgroundPerm.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }) { Text("אשר מיקום תמיד") }
                        }
                    }
                }
            }
        }

        // Garages
        item {
            Column(Modifier.padding(top = 8.dp)) {
                Text("חניוני אחוזות החוף", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    (place?.let { "סביב ${it.label}" } ?: "סביבך") + if (state.live) " · תפוסה בזמן אמת" else " · נתונים שמורים",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        items(garages) { g -> GarageRow(g) { Launch.navigate(ctx, g.lat, g.lon) } }

        if (nightLots.isNotEmpty()) {
            item {
                Column(Modifier.padding(top = 8.dp)) {
                    Text("חניוני 'קרוב לבית'", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("חינם בלילות ובסופי שבוע לבעלי תו משויך, הנחה לתושבים", style = MaterialTheme.typography.bodySmall)
                }
            }
            items(nightLots) { l ->
                Card(Modifier.fillMaxWidth().clickable { Launch.navigate(ctx, l.lat, l.lon) }) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(l.name, fontWeight = FontWeight.SemiBold)
                            Text(distanceText(l.distance))
                        }
                        if (l.nightTariff.isNotBlank()) Text("לילה: ${l.nightTariff}", style = MaterialTheme.typography.bodySmall)
                        if (l.notes.isNotBlank()) Text(l.notes, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    }
                }
            }
        }

        // Shortcuts
        item {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("קיצורי דרך", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                ShortcutRow("פנגו", "הפעלת תשלום חניה בכחול-לבן") { Launch.pango(ctx) }
                ShortcutRow("תו חניה אזורי", "בקשה, חידוש ובדיקת זכאות (אתר העירייה)") { Launch.web(ctx, Launch.PERMIT_URL) }
                ShortcutRow("דיגיתל שלי", "חשבון ארנונה, תו קיים ודוחות חניה") { Launch.web(ctx, Launch.DIGITEL_URL) }
            }
        }
        item { Text(" ") }
    }
}

@Composable
private fun ShortcutRow(title: String, subtitle: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun statusColor(s: String) = when (s) {
    "פנוי" -> Color(0xFF43A047)
    "מעט" -> Color(0xFFFB8C00)
    "מלא", "סגור" -> Color(0xFFE53935)
    else -> Color.Gray
}

@Composable
private fun GarageRow(g: Garage, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(g.name, fontWeight = FontWeight.SemiBold)
                Text(distanceText(g.distance))
            }
            Text(statusLabel(g.status), color = statusColor(g.status), fontWeight = FontWeight.Bold)
            if (g.dayTariff.isNotBlank()) Text(g.dayTariff, style = MaterialTheme.typography.bodySmall, maxLines = 3)
            if (g.nightTariff.isNotBlank()) Text("לילה: ${g.nightTariff}", style = MaterialTheme.typography.bodySmall, maxLines = 2)
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
                    Text(name, Modifier.fillMaxWidth().clickable { onPick(addr, name) }.padding(vertical = 10.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("ביטול") } },
    )
}
