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

data class UiState(
    val loading: Boolean = true,
    val lat: Double? = null,
    val lon: Double? = null,
    val ruleText: String = "",
    val garages: List<Garage> = emptyList(),
    val live: Boolean = false,
    val nightLots: List<NightLot> = emptyList(),
)

private suspend fun buildState(ctx: Context, repo: ParkingRepository): UiState {
    val loc = LocationHelper.current(ctx)
    val (garages, live) = repo.garages()
    if (loc == null) return UiState(false, null, null, "אין מיקום. אשר הרשאת מיקום.", garages, live)
    val (lat, lon) = loc.latitude to loc.longitude
    val zone = repo.zoneAt(lat, lon)
    return UiState(
        loading = false,
        lat = lat,
        lon = lon,
        ruleText = repo.rules.describe(zone, LocalDateTime.now()),
        garages = garages.map { it.copy(distance = Geo.distance(lat, lon, it.lat, it.lon)) }.sortedBy { it.distance }.take(12),
        live = live,
        nightLots = repo.nightLots.map { it.copy(distance = Geo.distance(lat, lon, it.lat, it.lon)) }
            .filter { it.distance < 1200 }.sortedBy { it.distance }.take(6),
    )
}

@Composable
fun ParkingScreen() {
    val ctx = LocalContext.current.applicationContext
    val repo = remember { ParkingRepository(ctx) }
    val store = remember { LastSpotStore(ctx) }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(UiState()) }
    var spot by remember { mutableStateOf(store.load()) }
    var carName by remember { mutableStateOf(store.carDeviceName) }
    var hasBackground by remember { mutableStateOf(Perms.hasBackground(ctx)) }
    var pickCar by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            state = state.copy(loading = true)
            state = buildState(ctx, repo)
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

    LazyColumn(
        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("חניה בת״א", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                TextButton(onClick = { refresh() }) { Text(if (state.loading) "טוען…" else "רענן") }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("כאן ועכשיו", fontWeight = FontWeight.Bold)
                    Text(state.ruleText.ifBlank { "…" })
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("הרכב שלי", fontWeight = FontWeight.Bold)
                    val s = spot
                    if (s == null) Text("אין מקום חניה שמור")
                    else {
                        val dist = if (state.lat != null) " · " + distanceText(Geo.distance(state.lat!!, state.lon!!, s.lat, s.lon)) else ""
                        Text("חנית ${s.timeText()}$dist")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (s != null) Button(onClick = { Launch.navigate(ctx, s.lat, s.lon, walking = true) }) { Text("הליכה לרכב") }
                        OutlinedButton(enabled = state.lat != null, onClick = {
                            store.save(state.lat!!, state.lon!!, "manual"); spot = store.load()
                        }) { Text("שמור כאן") }
                        if (s != null) TextButton(onClick = { store.clear(); spot = null }) { Text("נקה") }
                    }
                    Text(
                        "שמירה אוטומטית: " + (carName?.let { "כשהרכב ($it) מתנתק" } ?: "לא הוגדר רכב") +
                            (if (!hasBackground) " · צריך מיקום 'תמיד'" else ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            if (Perms.hasBtConnect(ctx)) pickCar = true
                            else if (Build.VERSION.SDK_INT >= 31) btPerm.launch(Manifest.permission.BLUETOOTH_CONNECT)
                        }) { Text("בחר רכב") }
                        if (!hasBackground && Build.VERSION.SDK_INT >= 29) {
                            OutlinedButton(onClick = { backgroundPerm.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }) { Text("מיקום תמיד") }
                        }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { Launch.pango(ctx) }) { Text("פנגו") }
                OutlinedButton(onClick = { Launch.web(ctx, Launch.DIGITEL_URL) }) { Text("דיגיתל: תו וארנונה") }
            }
        }
        item {
            Text(
                "חניוני אחוזות החוף" + if (state.live) " · תפוסה חיה" else " · נתונים שמורים",
                fontWeight = FontWeight.Bold,
            )
        }
        items(state.garages) { g -> GarageRow(g) { Launch.navigate(ctx, g.lat, g.lon) } }
        if (state.nightLots.isNotEmpty()) {
            item { Text("חניוני 'קרוב לבית' בסביבה (חינם בלילה ובסופ״ש לתו מתאים)", fontWeight = FontWeight.Bold) }
            items(state.nightLots) { l ->
                Card(Modifier.fillMaxWidth().clickable { Launch.navigate(ctx, l.lat, l.lon) }) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${l.name} · ${distanceText(l.distance)}", fontWeight = FontWeight.SemiBold)
                        if (l.nightTariff.isNotBlank()) Text("לילה: ${l.nightTariff}", style = MaterialTheme.typography.bodySmall)
                        if (l.notes.isNotBlank()) Text(l.notes, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    }
                }
            }
        }
        item { Text(" ") }
    }
}

private fun statusColor(s: String) = when (s) {
    "פנוי" -> Color(0xFF2E7D32)
    "מעט" -> Color(0xFFEF6C00)
    "מלא", "סגור" -> Color(0xFFC62828)
    else -> Color.Gray
}

@Composable
private fun GarageRow(g: Garage, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(g.name, fontWeight = FontWeight.SemiBold)
                Text(distanceText(g.distance))
            }
            Text(g.status.ifBlank { "אין מידע" }, color = statusColor(g.status), fontWeight = FontWeight.Bold)
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
