package il.yishai.parking

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.ContentCopy
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val classifyRequest = mutableStateOf<String?>(null)
    private val parkRequest = mutableStateOf(false)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(Notifications.EXTRA_CLASSIFY)?.let { classifyRequest.value = it }
        if (intent.action == ACTION_PARK_HERE) parkRequest.value = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        classifyRequest.value = intent?.getStringExtra(Notifications.EXTRA_CLASSIFY)
        parkRequest.value = intent?.action == ACTION_PARK_HERE
        WeeklyReport.schedule(this)
        GarageWatch.schedule(this)
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        setContent {
            // "system" | "dark" | "light"
            var themeMode by remember { mutableStateOf(prefs.getString("theme", "system") ?: "system") }
            val systemDark = isSystemInDarkTheme()
            val dark = when (themeMode) { "dark" -> true; "light" -> false; else -> systemDark }
            DisposableEffect(dark) {
                val style = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            MaterialTheme(colorScheme = if (dark) darkColorScheme(background = Color(0xFF0F1115), surface = Color(0xFF0F1115)) else lightColorScheme(background = Color(0xFFF3F4F6), surface = Color(0xFFF3F4F6))) {
                ParkingScreen(dark, themeMode, onThemeToggle = {
                    themeMode = when (themeMode) { "system" -> "dark"; "dark" -> "light"; else -> "system" }
                    prefs.edit().putString("theme", themeMode).apply()
                }, parkRequested = parkRequest.value, consumePark = { parkRequest.value = false },
                    classifyFromNotification = classifyRequest.value) { classifyRequest.value = null }
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
fun ParkingScreen(
    dark: Boolean, themeMode: String, onThemeToggle: () -> Unit, parkRequested: Boolean, consumePark: () -> Unit,
    classifyFromNotification: String?, consumeClassify: () -> Unit,
) {
    val ctx = LocalContext.current.applicationContext
    val repo = remember { ParkingRepository.get(ctx) }
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
    var pangoEnd by remember { mutableStateOf(PangoSession.endTime(ctx)) }
    var showMap by remember { mutableStateOf(false) }
    var heroExpanded by remember { mutableStateOf(false) }
    var notices by remember { mutableStateOf<List<Notice>>(emptyList()) }
    var overrideUntil by remember { mutableStateOf(FreeOverride.until(ctx)) }
    var watchTick by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var telegramOpen by remember { mutableStateOf(false) }
    var pickCarOnMap by remember { mutableStateOf(false) }
    var crashReport by remember { mutableStateOf(CrashLog.report(ctx)) }
    var mortyLinked by remember { mutableStateOf(Sync.mortyLinked(ctx)) }
    LaunchedEffect(telegramOpen) { if (!telegramOpen) Sync.mortyStatus(ctx)?.let { mortyLinked = it } }
    val curbStore = remember { CurbStore(ctx) }
    var curbMarks by remember { mutableStateOf(curbStore.all()) }
    val assignedPrefs = remember { ctx.getSharedPreferences("assigned", android.content.Context.MODE_PRIVATE) }
    var assigned by remember { mutableStateOf(assignedPrefs.getStringSet("lots", emptySet())!!.toSet()) }
    var askPango by remember { mutableStateOf(false) }
    val history = remember { HistoryStore(ctx) }
    var events by remember { mutableStateOf(history.all()) }
    var classifying by remember { mutableStateOf<ParkingEvent?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(classifyFromNotification) {
        classifyFromNotification?.let { id -> history.get(id)?.let { classifying = it }; consumeClassify() }
    }
    // Pop the curb picker on open if the last parking (within 3 hours) has no type yet and wasn't dismissed.
    LaunchedEffect(Unit) {
        val last = history.all().firstOrNull()
        val dismissed = ctx.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE).getString("dismissedClassify", null)
        if (classifying == null && last != null && last.type == null && last.id != dismissed &&
            System.currentTimeMillis() - last.time < 3 * 3600_000L) classifying = last
    }
    BackHandler(enabled = showHistory || showMap) { showHistory = false; showMap = false }

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

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && !Perms.has(ctx, Manifest.permission.POST_NOTIFICATIONS)) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        scope.launch { Sync.push(ctx) }
    }
    LaunchedEffect(Unit) { notices = Notices.fetch() }
    // Widget / Quick Settings tile: save the spot as soon as we have a location.
    LaunchedEffect(parkRequested) {
        if (!parkRequested) return@LaunchedEffect
        consumePark()
        val loc = LocationHelper.current(ctx)
        if (loc != null) {
            val e = Parking.record(ctx, loc.latitude, loc.longitude, "manual")
            spot = store.load(); events = history.all(); classifying = e
        }
    }
    LaunchedEffect(Unit) {
        if (Perms.hasLocation(ctx)) refresh()
        else locationPerm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    if (telegramOpen) {
        var bot by remember { mutableStateOf(Sync.mortyBot(ctx)) }
        var linked by remember { mutableStateOf(Sync.mortyLinked(ctx)) }
        var result by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        var agents by remember { mutableStateOf<List<String>>(emptyList()) }
        LaunchedEffect(Unit) { Sync.linkedAgents(ctx)?.let { agents = it; linked = "morty" in it } }
        AlertDialog(
            onDismissRequest = { telegramOpen = false },
            title = { Text("מורטי בטלגרם") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (linked) {
                        Text("מחובר. אפשר לשאול את מורטי בטלגרם איפה לחנות ליד כתובת, איפה הרכב ומה חדש.", fontSize = 14.sp)
                        Text("תזכורות פנגו, חניון שהתפנה, חניה חינם וסיכום שבועי נשלחים גם לטלגרם.", fontSize = 14.sp)
                    } else {
                        Text("לחיצה על \"חבר\" פותחת את הצ'אט עם מורטי בטלגרם. לוחצים שם Start וזהו, בלי להעתיק שום טוקן.", fontSize = 14.sp)
                        androidx.compose.material3.OutlinedTextField(bot, { bot = it }, singleLine = true, label = { Text("שם הבוט של מורטי") }, placeholder = { Text("@…bot") })
                    }
                    val others = agents.filter { it != "morty" }
                    if (others.isNotEmpty()) Text("אייג'נטים נוספים מחוברים: ${others.joinToString()}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    result?.let { Text(it, fontWeight = FontWeight.SemiBold) }
                }
            },
            confirmButton = {
                if (linked) TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch { result = if (Sync.mortyUnlink(ctx)) { linked = false; "נותק" } else "הניתוק נכשל, נסה שוב"; busy = false }
                }) { Text("נתק") }
                else TextButton(enabled = !busy && bot.isNotBlank(), onClick = {
                    busy = true
                    Sync.setMortyBot(ctx, bot)
                    scope.launch {
                        Sync.mortyLinkUrl(ctx)
                            .onSuccess { Launch.web(ctx, it); result = "בטלגרם לחץ Start. אחרי שמורטי מאשר, חזור לכאן ולחץ \"בדוק\"." }
                            .onFailure { result = it.message }
                        busy = false
                    }
                }) { Text(if (busy) "רגע…" else "חבר") }
            },
            dismissButton = {
                Row {
                    if (!linked) TextButton(onClick = { scope.launch { Sync.mortyStatus(ctx)?.let { linked = it; if (!it) result = "עוד לא מחובר" } ?: run { result = "אין חיבור לשרת" } } }) { Text("בדוק") }
                    TextButton(onClick = { telegramOpen = false }) { Text("סגור") }
                }
            },
        )
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

    if (askPango) AlertDialog(
        onDismissRequest = { askPango = false },
        title = { Text("לכמה זמן הפעלת בפנגו?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("אזכיר לך 10 דקות לפני הסוף, כדי לחדש.", fontSize = 13.sp)
                listOf(30 to "חצי שעה", 60 to "שעה", 120 to "שעתיים", 180 to "3 שעות", 240 to "4 שעות").forEach { (m, label) ->
                    Text(label, Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                        PangoSession.start(ctx, m); pangoEnd = PangoSession.endTime(ctx); askPango = false
                    }.padding(vertical = 12.dp, horizontal = 8.dp), fontSize = 16.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = { askPango = false }) { Text("לא הפעלתי") } },
    )

    classifying?.let { ev ->
        ClassifySheet(ev, onSave = { t, sTime ->
            Parking.classify(ctx, ev.id, t, sTime)
            Notifications.cancel(ctx, Notifications.ID_CLASSIFY)
            events = history.all(); curbMarks = curbStore.all(); classifying = null
        }, onDismiss = {
            ctx.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE).edit().putString("dismissedClassify", ev.id).apply()
            classifying = null
        })
    }

    val cardColorTop = if (dark) Color(0xFF191C22) else Color.White
    if (showHistory) {
        HistoryScreen(cardColorTop, onBack = { showHistory = false }, onEdit = { classifying = it }, onDelete = { e ->
            history.delete(e.id); events = history.all(); scope.launch { Sync.delete(ctx, e.id) }
        }, events = events)
        return
    }

    if (showMap) {
        val c = repo.places.getOrNull(focus ?: HERE)?.let { it.lat to it.lon } ?: state.myLat?.let { it to state.myLon!! }
        val carPick = pickCarOnMap
        MapScreen(repo, state.garages, spot, if (carPick) spot?.let { it.lat to it.lon } ?: c else c, events,
            onBack = { showMap = false; pickCarOnMap = false; curbMarks = curbStore.all() },
            onCurbsChanged = { curbMarks = curbStore.all(); scope.launch { Sync.push(ctx) } },
            onCarMoved = { spot = store.load(); events = history.all(); showMap = false; pickCarOnMap = false
                android.widget.Toast.makeText(ctx, "מיקום הרכב עודכן", android.widget.Toast.LENGTH_SHORT).show() },
            pickCar = carPick)
        return
    }

    val f = focus ?: HERE
    val place = repo.places.getOrNull(f)
    val pLat = place?.lat ?: state.myLat
    val pLon = place?.lon ?: state.myLon
    val pZone = if (place != null) repo.zoneAt(place.lat, place.lon) else state.myZone
    val onBoundary = pLat != null && pLon != null && repo.onPermitBoundary(pLat, pLon)
    val summary = if (pLat == null) null else repo.rules.summary(pZone, LocalDateTime.now(), onBoundary)
    val loadingSigns = if (pLat != null && pLon != null) repo.loadingSignsNear(pLat, pLon, 40.0) else emptyList()
    val explanation = if (pLat == null) null else repo.rules.explain(pZone, LocalDateTime.now(), onBoundary)
    val spotStreet = if (pLat != null && pLon != null) remember(pLat, pLon) { repo.streetNear(pLat, pLon, 40.0) } else null
    val curbHere = if (pLat != null && pLon != null) curbMarks.filter { Geo.distance(it.lat, it.lon, pLat, pLon) < 15 }.maxByOrNull { it.time } else null
    val autotelHere = if (pLat != null && pLon != null) repo.autotel.any { Geo.distance(it.lat, it.lon, pLat, pLon) < 25 } else false
    // (text, warning?): a warning needs attention; the rest just confirms what is here.
    val restricted = setOf(CurbType.RED_WHITE.key, CurbType.LOADING.key, CurbType.DRIVEWAY.key, CurbType.BUS_TAXI.key, CurbType.DISABLED.key, CurbType.SHORT_TERM.key)
    val localNotes = buildList {
        curbHere?.let { add("סימנת כאן: ${CurbType.of(it.type)?.label ?: it.type}" to (it.type in restricted)) }
        loadingSigns.firstOrNull()?.let { (d, sign) -> add("תמרור פריקה וטעינה ב-$d מ׳: ${sign.text}" to true) }
        if (autotelHere) add("יש כאן חניות שמורות לאוטותל, אסור לחנות בהן" to true)
    }
    val heroActionable = (explanation?.actionable ?: false) || localNotes.any { it.second }
    // Whether a parking app is needed here now (own zone or free hours: no).
    val pangoNeeded = pLat == null || repo.rules.evaluate(pZone, LocalDateTime.now(), 120, 0, onBoundary).appRequired
    val otherLots = if (pLat == null || pLon == null) emptyList() else
        repo.otherLots.map { it.copy(distance = Geo.distance(pLat, pLon, it.lat, it.lon)) }.filter { it.distance < 700 }.sortedBy { it.distance }.take(5)
    val now = LocalDateTime.now()
    val garages = if (pLat == null || pLon == null) emptyList() else
        state.garages.map { it.copy(distance = Geo.distance(pLat, pLon, it.lat, it.lon)) }.sortedBy { it.distance }.take(8)
    val nightByName = remember { repo.nightLots.associateBy { it.name.trim() } }
    val autoReady = carName != null && hasBackground
    val cardColor = if (dark) Color(0xFF191C22) else Color.White

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).navigationBarsPadding().padding(16.dp, 8.dp, 16.dp, 12.dp)) {
                // Full-strength only when the spot needs a parking app now; otherwise a quiet outline.
                val fg = if (pangoNeeded) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onBackground
                Row(
                    Modifier.fillMaxWidth().height(if (pangoNeeded) 54.dp else 46.dp).clip(RoundedCornerShape(18.dp))
                        .then(if (pangoNeeded) Modifier.background(MaterialTheme.colorScheme.onBackground)
                              else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp)))
                        .clickable { askPango = true; Launch.pango(ctx) },
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.LocalParking, null, tint = fg)
                    Spacer(Modifier.width(8.dp))
                    Text(if (pangoNeeded) "הפעל חניה בפנגו" else "פנגו · לא צריך כאן עכשיו", color = fg,
                        fontWeight = if (pangoNeeded) FontWeight.SemiBold else FontWeight.Normal, fontSize = if (pangoNeeded) 16.sp else 14.sp)
                }
            }
        },
    ) { inner ->
        LazyColumn(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().testTag("main-list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = inner.calculateBottomPadding() + 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE · HH:mm", Locale("he"))), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("חניה בת״א", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    }
                    CircleIcon(
                        when (themeMode) { "dark" -> Icons.Outlined.DarkMode; "light" -> Icons.Outlined.LightMode; else -> Icons.Outlined.BrightnessAuto },
                        when (themeMode) { "dark" -> "מצב כהה"; "light" -> "מצב בהיר"; else -> "לפי המערכת" },
                        cardColor, MaterialTheme.colorScheme.onSurface,
                    ) { onThemeToggle() }
                    Spacer(Modifier.width(8.dp))
                    CircleIcon(Icons.Outlined.Map, "מפה", cardColor, MaterialTheme.colorScheme.onSurface) { showMap = true }
                    Spacer(Modifier.width(8.dp))
                    CircleIcon(Icons.Outlined.History, "היסטוריה", cardColor, MaterialTheme.colorScheme.onSurface) { events = history.all(); showHistory = true }
                    Spacer(Modifier.width(8.dp))
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
                val ex = explanation
                val t = tone(ex?.freeNow, dark)
                val locationLine = listOfNotNull(spotStreet ?: place?.label, summary?.zone?.let { "אזור $it" }).joinToString(" · ").ifBlank { null }
                val expanded = heroActionable || heroExpanded
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(t.container)
                        .clickable(enabled = !heroActionable) { heroExpanded = !heroExpanded }
                        .animateContentSize().padding(if (expanded) 18.dp else 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            Modifier.size(if (expanded) 64.dp else 44.dp).clip(RoundedCornerShape(if (expanded) 16.dp else 12.dp)).background(t.strong),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                        ) {
                            if (expanded) Text("אזור", fontSize = 11.sp, color = t.onStrong)
                            Text(summary?.zone?.toString() ?: "–", fontSize = if (expanded) 28.sp else 20.sp, fontWeight = FontWeight.Bold, color = t.onStrong, lineHeight = 30.sp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                when { state.loading && ex == null -> "מאתר מיקום…"; ex == null -> "אין מיקום"; else -> ex.headline },
                                fontSize = if (expanded) 20.sp else 17.sp, fontWeight = FontWeight.Bold, color = t.text,
                            )
                            Text(listOfNotNull(locationLine, ex?.tag).joinToString(" · "), fontSize = 13.sp, color = t.text, maxLines = 1)
                        }
                        if (!heroActionable) Icon(
                            if (heroExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            if (heroExpanded) "פחות" else "פרטים", tint = t.text,
                        )
                    }
                    if (expanded && ex != null) {
                        ex.lines.forEach { Text("• $it", fontSize = 14.sp, color = t.text) }
                        ex.nowLine?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 14.sp, color = t.text, fontWeight = FontWeight.SemiBold) }
                        repo.rules.permitLine()?.let { Text(it, fontSize = 13.sp, color = t.text, fontWeight = FontWeight.SemiBold) }
                        localNotes.forEach { (note, warn) -> Text((if (warn) "⚠ " else "✓ ") + note, fontSize = 13.sp, color = t.text, fontWeight = if (warn) FontWeight.SemiBold else FontWeight.Normal, maxLines = 3) }
                    }
                }
            }

            if (overrideUntil != null || notices.isNotEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFFF59E0B).copy(alpha = 0.14f)).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        overrideUntil?.let { u ->
                            Text("מצב 'חניה חינם בכחול-לבן' פעיל עד ${java.text.SimpleDateFormat("EEE HH:mm", Locale("he")).format(java.util.Date(u))}", fontWeight = FontWeight.Bold)
                            TextButton(onClick = { FreeOverride.clear(ctx); overrideUntil = null }) { Text("כבה") }
                        }
                        notices.take(3).forEach { n ->
                            val label = when (n.kind) { Notice.Kind.FREE_PARKING -> "חניה חינם?"; Notice.Kind.PRICE_CHANGE -> "שינוי מחיר"; Notice.Kind.EVENT -> "אירוע באזור" }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).clickable { Launch.web(ctx, n.link) }) {
                                    Text("$label · ${n.source}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(n.title, fontSize = 14.sp, maxLines = 2)
                                }
                                if (n.kind == Notice.Kind.FREE_PARKING && overrideUntil == null) TextButton(onClick = {
                                    val midnight = java.time.LocalDate.now().plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                                    FreeOverride.set(ctx, midnight); overrideUntil = midnight
                                }) { Text("הפעל עד חצות") }
                            }
                        }
                    }
                }
            }

            if (pLat != null && pLon != null) {
                item {
                    val plan = remember(pLat, pLon, events.size, curbMarks.size) { Planner.searchRoute(repo, events, curbMarks, pLat, pLon) }
                    plan?.let { r ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(cardColor).clickable { Launch.web(ctx, r.url) }.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.Route, null, tint = Color(0xFF3B82F6))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("מסלול חיפוש חניה${place?.let { " ל${it.label}" } ?: ""}", fontWeight = FontWeight.SemiBold)
                                Text(r.streets.joinToString(" ← "), fontSize = 13.sp, maxLines = 1)
                                Text(r.reason, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null)
                        }
                    }
                }
            }

            pangoEnd?.let { end ->
                item {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFF3B82F6).copy(alpha = 0.15f)).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.LocalParking, null, tint = Color(0xFF3B82F6))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("חניה בפנגו פעילה עד ${java.text.SimpleDateFormat("HH:mm", Locale("he")).format(java.util.Date(end))}", fontWeight = FontWeight.SemiBold)
                            Text("תזכורת 10 דקות לפני הסוף", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { askPango = true; Launch.pango(ctx) }) { Text("הארך") }
                        TextButton(onClick = { PangoSession.stop(ctx); pangoEnd = null }) { Text("סיימתי") }
                    }
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
                            if (state.myLat != null) { val e = Parking.record(ctx, state.myLat!!, state.myLon!!, "manual"); spot = store.load(); events = history.all(); classifying = e }
                        }
                        if (s != null) SoftButton("שתף", Icons.Outlined.Share, Modifier.weight(1f), dark) {
                            ctx.startActivity(shareCarIntent(s, events.firstOrNull()?.street ?: repo.streetNear(s.lat, s.lon)))
                        }
                        if (s != null) SoftButton("מחק", Icons.Outlined.Delete, Modifier.weight(1f), dark) { store.clear(); spot = null }
                    }
                    // Forgot to save when leaving the car, or the saved spot is off.
                    SoftButton(if (s != null) "תקן על המפה" else "סמן על המפה איפה חניתי", Icons.Outlined.Map, Modifier.fillMaxWidth(), dark) {
                        pickCarOnMap = true; showMap = true
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

            events.firstOrNull()?.takeIf { it.type == null }?.let { last ->
                item {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFFF59E0B).copy(alpha = 0.15f)).clickable { classifying = last }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("סמן איזו חניה הייתה האחרונה", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null)
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
                        val night = nightByName[g.name.trim()]
                        val mine = night != null && g.name.trim() in assigned
                        val exception = closeToHomeException(g.name)
                        val watching = remember(watchTick, g.name) { GarageWatch.isWatched(ctx, g.name) }
                        GarageRow(
                            g, night, expanded == g.name,
                            assignedNote = when { !mine -> null; exception != null -> "משויך לך · שעות מיוחדות"; repo.rules.closeToHomeFree(now) -> "משויך לך · חינם עכשיו"; else -> "משויך לך · חינם מ-${repo.rules.closeToHomeFreeFrom(now)}" },
                            exception = exception,
                            watching = watching,
                            onToggleWatch = { GarageWatch.toggle(ctx, g.name, g.status); watchTick++ },
                            onToggleAssigned = if (night == null) null else ({
                                assigned = if (mine) assigned - g.name.trim() else assigned + g.name.trim()
                                assignedPrefs.edit().putStringSet("lots", assigned).apply()
                                scope.launch { Sync.push(ctx) }
                            }),
                            isAssigned = mine,
                            onToggle = { expanded = if (expanded == g.name) null else g.name },
                        ) { Launch.navigate(ctx, g.lat, g.lon) }
                    }
                }
            }

            if (otherLots.isNotEmpty()) {
                item { SectionHeader("חניונים נוספים", "עירייה ו-OpenStreetMap", null) }
                item {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cardColor)) {
                        otherLots.forEachIndexed { i, l ->
                            if (i > 0) HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            Row(Modifier.fillMaxWidth().clickable { Launch.navigate(ctx, l.lat, l.lon) }.padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(l.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    Text(listOfNotNull(l.kind, l.spots?.let { "$it מקומות" }, when (l.free) { true -> "חינם"; false -> "בתשלום"; null -> null }).joinToString(" · "),
                                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(distanceText(l.distance), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            crashReport?.let { report ->
                item {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(cardColor).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("האפליקציה נתקלה בשגיאה", fontWeight = FontWeight.SemiBold)
                        Text("הפרטים נשמרו רק בטלפון. שליחה שלהם עוזרת לתקן.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row {
                            TextButton(onClick = {
                                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                                    .putExtra(Intent.EXTRA_SUBJECT, "parking-tlv: דוח שגיאה").putExtra(Intent.EXTRA_TEXT, report), "שלח דוח שגיאה")
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }) { Text("שלח דוח") }
                            TextButton(onClick = { CrashLog.clear(ctx); crashReport = null }) { Text("נקה") }
                        }
                    }
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(cardColor).clickable { telegramOpen = true }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Outlined.Send, null)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("מורטי בטלגרם", fontWeight = FontWeight.SemiBold)
                        Text(if (mortyLinked) "מחובר · התראות נשלחות גם לטלגרם" else "חיבור בלחיצה: שאלות חניה והתראות בטלגרם", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(cardColor).clickable {
                        scope.launch {
                            val base = Sync.baseUrl(ctx)
                            val token = Sync.token(ctx)
                            if (base == null || token == null) copied = "השרת עוד לא מחובר"
                            else { clipboard.setText(AnnotatedString(Sync.agentPrompt(base, token))); copied = "הפרומפט הועתק" }
                        }
                    }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.SmartToy, null)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("העתק פרומפט לאייג'נט", fontWeight = FontWeight.SemiBold)
                        Text(copied ?: "חיבור ל-MCP: חניה ליד יעד, הרכב שלי, היסטוריה", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Outlined.ContentCopy, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun GarageRow(
    g: Garage, night: NightLot?, open: Boolean, assignedNote: String?, exception: String?, watching: Boolean, onToggleWatch: () -> Unit,
    onToggleAssigned: (() -> Unit)?, isAssigned: Boolean, onToggle: () -> Unit, onNavigate: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().animateContentSize().clickable(onClick = onToggle).padding(16.dp, 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(occupancyColor(g.status)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(g.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(statusLabel(g.status), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val tags = buildList {
                    if (assignedNote != null) add(assignedNote) else if (night != null) add("קרוב לבית")
                    if (watching) add("מעקב פעיל")
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
                if (night != null) Text(exception?.let { "חניון 'קרוב לבית' עם שעות מיוחדות: $it" }
                    ?: "חניון 'קרוב לבית': חינם לבעלי תו משויך בא׳–ה׳ 19:00–08:00, ובשישי וערבי חג מ-15:00 עד ראשון 08:00 / צאת החג", fontSize = 13.sp)
                TextButton(onClick = onToggleWatch) {
                    Icon(if (watching) Icons.Outlined.NotificationsOff else Icons.Outlined.NotificationsActive, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (watching) "הפסק מעקב" else "התראה כשמתפנה / מתמלא (3 שעות)")
                }
                if (onToggleAssigned != null) TextButton(onClick = onToggleAssigned) {
                    Text(if (isAssigned) "הסר: החניון לא משויך אליי" else "החניון הזה משויך אליי (לפי דיגיתל / 3013*)")
                }
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


/** Close-to-home lots with their own hours (tel-aviv.gov.il ParkingArrangements). */
fun closeToHomeException(name: String): String? = when {
    "רוטשילד" in name || name.trim() == "התרבות" -> "א׳–ה׳ מ-22:00; בסופ״ש עד שבת 12:00, ומ-22:00 עד 07:00 למחרת"
    "רמז" in name -> "א׳–ה׳ מ-21:00; שישי מ-16:00 עד 08:00 למחרת; שבת מ-16:00 עד 07:00 למחרת"
    "בזל" in name -> "א׳–ה׳ מ-21:00 עד 08:00; שישי וערבי חג מ-15:00 עד 08:00 למחרת"
    "מפעל הפיס" in name -> "עד 08:00 בבוקר"
    "מוזיאון" in name -> "22:00–08:00 כל יום, כולל שבת וחג, בהצגת תעודה מזהה"
    "שרתון 2" in name -> "19:00–06:30 כל יום, כולל שבת וחג"
    else -> null
}
