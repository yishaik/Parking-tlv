package il.yishai.parking

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DataTest {
    @get:Rule val tmp = TemporaryFolder()

    /** The bundled zone snapshot puts both of the owner's addresses in zone 9 (same check as the TS tests). */
    @Test fun ownerAddressesAreInZone9() {
        val features = JSONObject(File("../../data/zones.json").readText()).getJSONArray("features")
        val zones = features.mapSafe { f ->
            val rings = f.getJSONObject("geometry").getJSONArray("rings")
            f.getJSONObject("attributes").getInt("ms_ezor") to (0 until rings.length()).map { r ->
                val ring = rings.getJSONArray(r)
                (0 until ring.length()).map { p -> doubleArrayOf(ring.getJSONArray(p).getDouble(0), ring.getJSONArray(p).getDouble(1)) }
            }
        }
        fun zoneAt(lat: Double, lon: Double) = zones.firstOrNull { Geo.pointInRings(lon, lat, it.second) }?.first
        assertEquals(9, zoneAt(32.0916827757229, 34.78161161459743)) // Nordau 86
        assertEquals(9, zoneAt(32.09500641606727, 34.77916908119569)) // Usishkin 54
        assertNull(zoneAt(32.09, 34.70)) // the sea
    }

    @Test fun mapSafeSkipsBadRows() {
        val a = JSONArray("""[{"id":"a","n":1},{"id":"b"},"not an object",{"id":"c","n":3}]""")
        assertEquals(listOf("a", "c"), a.mapSafe { o -> o.getInt("n"); o.getString("id") })
    }

    @Test fun unreadableFileIsKeptAsideNotWiped() {
        val f = File(tmp.root, "history.json").apply { writeText("{broken") }
        val store = JsonListFile(f)
        assertNull(store.read())
        assertTrue(File(tmp.root, "history.json.bad").readText() == "{broken")
        store.write(JSONArray().put(1))
        assertEquals(1, store.read()!!.length())
    }

    @Test fun parkingEventRoundTrip() {
        val e = ParkingEvent("id1", 32.09, 34.78, 1_700_000_000_000, "manual", 9, "נורדאו", "blue_white", "5")
        assertEquals(e, ParkingEvent.fromJson(e.toJson()))
        val noZone = e.copy(zone = null, street = null, type = null, search = null)
        assertEquals(noZone, ParkingEvent.fromJson(noZone.toJson()))
    }
}
