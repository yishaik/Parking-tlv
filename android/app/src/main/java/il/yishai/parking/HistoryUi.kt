package il.yishai.parking

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val SignBlue = Color(0xFF1E5AA8)
private val Asphalt = Color(0xFF3A3D44)
private val CurbGray = Color(0xFFB8BBC2)

/** Hand-drawn picture of what the spot looks like: curb colors or the parking sign. */
@Composable
fun SignImage(type: SpotType, size: Dp = 64.dp) {
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        fun label(text: String, color: Color, sizeSp: Float, center: Offset) {
            val layout = measurer.measure(text, TextStyle(color = color, fontSize = sizeSp.sp, fontWeight = FontWeight.Bold))
            drawText(layout, topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f))
        }
        fun pSign(top: Float, height: Float) {
            drawRoundRect(SignBlue, Offset(w * 0.15f, top), Size(w * 0.7f, height), CornerRadius(w * 0.08f))
            drawRoundRect(Color.White, Offset(w * 0.19f, top + w * 0.04f), Size(w * 0.62f, height - w * 0.08f), CornerRadius(w * 0.06f), style = Stroke(w * 0.02f))
            label("P", Color.White, size.value * 0.42f, Offset(w / 2, top + height / 2))
        }
        fun plate(text: String) {
            drawRoundRect(Color.White, Offset(w * 0.1f, h * 0.68f), Size(w * 0.8f, h * 0.28f), CornerRadius(w * 0.05f))
            drawRoundRect(Color(0xFF222222), Offset(w * 0.1f, h * 0.68f), Size(w * 0.8f, h * 0.28f), CornerRadius(w * 0.05f), style = Stroke(w * 0.015f))
            label(text, Color(0xFF111111), size.value * 0.14f, Offset(w / 2, h * 0.82f))
        }
        when (type) {
            SpotType.BLUE_WHITE, SpotType.UNMARKED, SpotType.RED_WHITE, SpotType.LOADING, SpotType.PERMIT_ONLY -> {
                drawRoundRect(Asphalt, Offset.Zero, Size(w, h), CornerRadius(w * 0.12f))
                val curbTop = h * 0.62f
                val stripes = 5
                for (i in 0 until stripes) {
                    val c = when (type) {
                        SpotType.UNMARKED, SpotType.LOADING -> CurbGray
                        SpotType.RED_WHITE -> if (i % 2 == 0) Color(0xFFD32F2F) else Color.White
                        else -> if (i % 2 == 0) SignBlue else Color.White
                    }
                    drawRect(c, Offset(w * i / stripes, curbTop), Size(w / stripes + 1, h * 0.2f))
                }
                drawRect(Color(0xFFD9D4C7), Offset(0f, curbTop + h * 0.2f), Size(w, h * 0.18f))
                if (type == SpotType.PERMIT_ONLY || type == SpotType.LOADING) {
                    val bg = if (type == SpotType.LOADING) Color(0xFFF59E0B) else Color.White
                    drawRoundRect(bg, Offset(w * 0.14f, h * 0.12f), Size(w * 0.72f, h * 0.36f), CornerRadius(w * 0.05f))
                    label(if (type == SpotType.LOADING) "פריקה" else "תו אזורי", Color(0xFF111111), size.value * 0.15f, Offset(w / 2, h * 0.30f))
                }
            }
            SpotType.GARAGE -> pSign(h * 0.08f, h * 0.84f)
            SpotType.CLOSE_TO_HOME -> { pSign(h * 0.02f, h * 0.62f); plate("לתושבים") }
            SpotType.SHORT_TERM -> { pSign(h * 0.02f, h * 0.62f); plate("60 דק׳") }
            SpotType.PRIVATE -> {
                drawRoundRect(Color.White, Offset(w * 0.1f, h * 0.15f), Size(w * 0.8f, h * 0.7f), CornerRadius(w * 0.06f))
                drawRoundRect(Color(0xFFD32F2F), Offset(w * 0.1f, h * 0.15f), Size(w * 0.8f, h * 0.7f), CornerRadius(w * 0.06f), style = Stroke(w * 0.05f))
                label("פרטי", Color(0xFFD32F2F), size.value * 0.22f, Offset(w / 2, h / 2))
            }
            SpotType.OTHER -> {
                drawCircle(Color(0xFF6B7280), w * 0.4f)
                label("?", Color.White, size.value * 0.4f, Offset(w / 2, h / 2))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpotTiles(types: List<SpotType>, selected: SpotType?, onPick: (SpotType) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        types.forEach { t ->
            val sel = selected == t
            Column(
                Modifier.width(100.dp).clip(RoundedCornerShape(16.dp))
                    .border(if (sel) 2.dp else 1.dp, if (sel) Color(0xFF3B82F6) else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                    .background(if (sel) Color(0xFF3B82F6).copy(alpha = 0.12f) else Color.Transparent)
                    .clickable { onPick(t) }.padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SignImage(t, 56.dp)
                Spacer(Modifier.height(6.dp))
                Text(t.label, fontSize = 12.sp, maxLines = 2)
            }
        }
    }
}

/** Bottom sheet shown after parking: pick the sign picture and how long the search took. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ClassifySheet(event: ParkingEvent, onSave: (SpotType?, SearchTime?) -> Unit, onDismiss: () -> Unit) {
    var type by remember { mutableStateOf(SpotType.of(event.type)) }
    var search by remember { mutableStateOf(SearchTime.of(event.search)) }
    var photoNote by remember { mutableStateOf<String?>(null) }
    val camera = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.TakePicturePreview()) { bmp ->
        if (bmp == null) return@rememberLauncherForActivityResult
        val guess = CurbPhoto.detect(bmp)
        if (guess != null) { type = guess; photoNote = "מהתמונה זה נראה כמו ${guess.label}. בדוק ואשר." }
        else photoNote = "לא הצלחתי לזהות מהתמונה. נסה לצלם מקרוב, כשהמדרכה ממלאת את חלקו התחתון של הפריים."
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("מה צבע המדרכה איפה שחנית?", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            event.street?.let { Text("ליד $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            androidx.compose.material3.OutlinedButton(onClick = { camera.launch(null) }) { Text("צלם את המדרכה וזהה צבע") }
            photoNote?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            SpotTiles(SpotType.curbs, type) { type = it }
            Text("או שחנית מחוץ לרחוב", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SpotTiles(SpotType.offStreet, type) { type = it }
            Text("כמה זמן חיפשת?", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SearchTime.entries.forEach { s ->
                    val selected = search == s
                    Text(
                        s.label,
                        Modifier.clip(CircleShape).background(if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { search = s }.padding(horizontal = 16.dp, vertical = 10.dp),
                        color = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Box(
                Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.onSurface).clickable { onSave(type, search) },
                contentAlignment = Alignment.Center,
            ) { Text("שמור", color = MaterialTheme.colorScheme.surface, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        }
    }
}

private fun bucket(time: Long): String {
    val h = Calendar.getInstance().apply { timeInMillis = time }.get(Calendar.HOUR_OF_DAY)
    return when (h) { in 6..11 -> "בוקר"; in 12..16 -> "צהריים"; in 17..20 -> "ערב"; else -> "לילה" }
}

/** History list with the statistics that answer "where is it worth looking". */
@Composable
fun HistoryScreen(cardColor: Color, onBack: () -> Unit, onEdit: (ParkingEvent) -> Unit, onDelete: (ParkingEvent) -> Unit, events: List<ParkingEvent>) {
    val fmt = remember { SimpleDateFormat("EEE d/M · HH:mm", Locale("he")) }
    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).clip(CircleShape).background(cardColor).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "חזרה")
                }
                Spacer(Modifier.width(12.dp))
                Text("היסטוריה וסטטיסטיקה", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (events.isEmpty()) {
            item { Text("עוד אין חניות שמורות. אחרי כל חניה סמן את סוג החניה, והסטטיסטיקה תיבנה כאן.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            item { StatsCard(events, cardColor) }
            item { Text("כל החניות", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp)) }
            items(events, key = { it.id }) { e ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(cardColor).clickable { onEdit(e) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val t = SpotType.of(e.type)
                    if (t != null) SignImage(t, 44.dp) else Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFF59E0B).copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                        Text("?", color = Color(0xFFF59E0B), fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(listOfNotNull(e.street, e.zone?.let { "אזור $it" }).joinToString(" · ").ifBlank { "מיקום שמור" }, fontWeight = FontWeight.SemiBold)
                        val parts = listOfNotNull(fmt.format(Date(e.time)), t?.label ?: "לא סומן", SearchTime.of(e.search)?.let { "חיפוש: ${it.label}" })
                        Text(parts.joinToString(" · "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box(Modifier.size(36.dp).clip(CircleShape).clickable { onDelete(e) }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Delete, "מחק", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsCard(events: List<ParkingEvent>, cardColor: Color) {
    val typed = events.filter { it.type != null }
    val searched = events.filter { it.search != null }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(cardColor).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row {
            Metric("חניות", events.size.toString(), Modifier.weight(1f))
            Metric("חיפוש ממוצע", if (searched.isEmpty()) "–" else "${searched.map { SearchTime.of(it.search)!!.minutes }.average().toInt()} ד׳", Modifier.weight(1f))
            Metric("לא סומנו", (events.size - typed.size).toString(), Modifier.weight(1f))
        }
        if (typed.isNotEmpty()) {
            Text("לפי סוג", fontWeight = FontWeight.SemiBold)
            typed.groupBy { it.type }.entries.sortedByDescending { it.value.size }.forEach { (k, list) ->
                val share = list.size.toFloat() / typed.size
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SignImage(SpotType.of(k) ?: SpotType.OTHER, 28.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(SpotType.of(k)?.label ?: k.orEmpty(), Modifier.width(110.dp), fontSize = 13.sp)
                    Box(Modifier.weight(1f).height(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
                        Box(Modifier.fillMaxWidth(share).height(8.dp).clip(CircleShape).background(Color(0xFF3B82F6)))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("${list.size}", fontSize = 13.sp)
                }
            }
        }
        val fastStreets = searched.filter { it.street != null }.groupBy { it.street!! }
            .mapValues { (_, l) -> l.size to l.map { SearchTime.of(it.search)!!.minutes }.average() }
            .entries.sortedWith(compareBy({ it.value.second }, { -it.value.first })).take(5)
        if (fastStreets.isNotEmpty()) {
            Text("איפה מצאת הכי מהר", fontWeight = FontWeight.SemiBold)
            fastStreets.forEach { (street, v) ->
                Row { Text(street, Modifier.weight(1f), fontSize = 13.sp); Text("${v.first} חניות · ~${v.second.toInt()} ד׳", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        if (searched.isNotEmpty()) {
            Text("לפי שעה ביום", fontWeight = FontWeight.SemiBold)
            listOf("בוקר", "צהריים", "ערב", "לילה").forEach { b ->
                val l = searched.filter { bucket(it.time) == b }
                if (l.isNotEmpty()) {
                    val topType = l.mapNotNull { SpotType.of(it.type) }.groupBy { it }.maxByOrNull { it.value.size }?.key?.label ?: "–"
                    Row {
                        Text(b, Modifier.weight(1f), fontSize = 13.sp)
                        Text("~${l.map { SearchTime.of(it.search)!!.minutes }.average().toInt()} ד׳ · בעיקר $topType", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
