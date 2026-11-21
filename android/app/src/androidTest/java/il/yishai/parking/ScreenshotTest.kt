package il.yishai.parking

import android.app.LocaleManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.LocaleList
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Walks through every screen with sample data and saves screenshots for the docs
 * (to the app's external files dir; the CI job pulls them into docs/screenshots).
 * Doubles as a smoke test: the run fails if any screen crashes or does not appear.
 *
 * Sample data only: the server token is replaced by a dummy, so nothing reaches the real server.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context = instr.targetContext
    private val device = UiDevice.getInstance(instr)
    private val outDir = File(ctx.getExternalFilesDir(null), "screenshots").apply { deleteRecursively(); mkdirs() }

    private fun shot(name: String, settleMs: Long = 800) {
        compose.waitForIdle()
        Thread.sleep(settleMs)
        val bmp: Bitmap = instr.uiAutomation.takeScreenshot() ?: error("screenshot failed: $name")
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitForText(text: String, timeoutMs: Long = 25_000, substring: Boolean = true) =
        compose.waitUntil(timeoutMs) { compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty() }

    private fun seed(theme: String) {
        ctx.getSharedPreferences("sync", Context.MODE_PRIVATE).edit().putString("token", "screenshot-test-not-a-real-token").commit()
        ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putString("theme", theme).remove("dismissedClassify").commit()
        if (Build.VERSION.SDK_INT >= 33) ctx.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("he-IL")

        val repo = ParkingRepository.get(ctx)
        // A point in the middle of each street's centreline, so the sample spots sit on real zone 9 streets.
        fun pointOn(name: String): Pair<Double, Double>? = repo.streets.firstOrNull { it.name.contains(name) }?.paths?.maxByOrNull { it.size }
            ?.let { p -> p[p.size / 2] }?.let { it[1] to it[0] }
        val streets = listOf("נורדאו", "אוסישקין", "ישעיהו", "סוקולוב", "ארלוזורוב", "דיזנגוף", "בן יהודה", "פנקס").mapNotNull { n -> pointOn(n)?.let { n to it } }
        check(streets.size >= 4) { "street data missing: ${streets.map { it.first }}" }

        File(ctx.filesDir, "history.json").delete()
        File(ctx.filesDir, "curbs.json").delete()
        val history = HistoryStore(ctx)
        val now = System.currentTimeMillis()
        val types = listOf(SpotType.BLUE_WHITE, SpotType.UNMARKED, SpotType.BLUE_WHITE, SpotType.PERMIT_ONLY, SpotType.GARAGE, SpotType.BLUE_WHITE, SpotType.CLOSE_TO_HOME)
        val searches = listOf("0", "5", "15", "5", "30", "0", "5")
        for (i in 1..26) {
            val (street, p) = streets[i % streets.size]
            val lat = p.first + (i % 3 - 1) * 0.00015; val lon = p.second + (i % 2) * 0.0001
            history.add(ParkingEvent("sample-$i", lat, lon, now - i * 19L * 3600_000L, listOf("bluetooth", "android-auto", "manual")[i % 3],
                repo.zoneAt(lat, lon), street, types[i % types.size].key, searches[i % searches.size], synced = true))
        }
        // The newest parking has no curb color yet: the app asks for it on open.
        val (lastStreet, last) = streets[1]
        history.add(ParkingEvent("sample-0", last.first, last.second, now - 20 * 60_000L, "bluetooth", repo.zoneAt(last.first, last.second), lastStreet, null, null, synced = true))
        LastSpotStore(ctx).save(last.first, last.second, "bluetooth")

        val curbs = CurbStore(ctx)
        listOf(CurbType.BLUE_WHITE, CurbType.GRAY, CurbType.RED_WHITE, CurbType.PREFERRED, CurbType.LOADING, CurbType.BLUE_WHITE).forEachIndexed { i, t ->
            val (street, p) = streets[(i + 1) % streets.size]
            curbs.add(p.first + 0.0004 * (i % 2), p.second + 0.0003 * i, t, street, "map")
        }
        curbs.markSynced(curbs.all().map { it.id }.toSet())
        curbs.clearTombstones(curbs.tombstones())
    }

    @Test fun walkThrough() {
        seed("light")
        ActivityScenario.launch(MainActivity::class.java).use {
            // 1. Right after parking: the curb color sheet opens by itself.
            waitForText("מה צבע המדרכה")
            shot("01-after-parking-curb-color")
            device.pressBack()

            // 2. Home: the rule at the exact spot.
            waitForText("אזור")
            Thread.sleep(4000) // live garage occupancy and news
            shot("02-home")

            // 3. Further down: my car, search route, garages.
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, 30)
            shot("03-home-garages")
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, 30)
            shot("04-home-more")

            // 4. Morty on Telegram.
            compose.onNodeWithTag("main-list").performScrollToNode(hasText("מורטי בטלגרם"))
            compose.onNodeWithText("מורטי בטלגרם").performClick()
            waitForText("שם הבוט של מורטי")
            shot("05-morty-telegram")
            compose.onNodeWithText("סגור").performClick()

            // 5. History and statistics.
            compose.onNodeWithTag("main-list").performScrollToNode(hasText("חניה בת״א"))
            compose.onNodeWithContentDescription("היסטוריה").performClick()
            waitForText("חניות")
            shot("06-history")

            device.pressBack()
            // 6. Map of zone 9 with curb marks, garages and the car.
            waitForText("אזור")
            compose.onNodeWithContentDescription("מפה").performClick()
            waitForText("מפת חניה")
            shot("07-map", settleMs = 9000) // city tiles
            compose.onNodeWithText("סמן במרכז המפה").performClick()
            waitForText("מה הסימון כאן")
            shot("08-map-mark-curb")
            device.pressBack()
            device.pressBack()
        }

        // 7. Dark mode.
        seed("dark")
        ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putString("dismissedClassify", "sample-0").commit()
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForText("אזור")
            Thread.sleep(4000)
            shot("09-home-dark")
            compose.onNodeWithContentDescription("היסטוריה").performClick()
            waitForText("חניות")
            shot("10-history-dark")
            device.pressBack()
            try {
                waitForText("אזור", 8000)
                compose.onNodeWithContentDescription("מפה").performClick()
                waitForText("מפת חניה")
                shot("11-map-dark", settleMs = 9000)
            } catch (_: ComposeTimeoutException) { /* map in dark mode is a bonus shot */ }
        }
    }
}
