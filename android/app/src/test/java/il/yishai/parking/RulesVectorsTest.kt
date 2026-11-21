package il.yishai.parking

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

/** Same cases as test/vectors.test.ts: the Kotlin rules must answer exactly like the server's TypeScript rules. */
class RulesVectorsTest {
    private val repoRoot = File("../..")
    private val rules = Rules.parse(
        JSONObject(File(repoRoot, "config/rules.json").readText()),
        JSONObject(File(repoRoot, "config/holidays.json").readText()),
    )

    @Test fun matchesTypeScript() {
        val vectors = JSONArray(javaClass.classLoader!!.getResource("rules_vectors.json")!!.readText())
        val failures = mutableListOf<String>()
        for (i in 0 until vectors.length()) {
            val v = vectors.getJSONObject(i)
            val zone = if (v.isNull("zone")) null else v.getInt("zone")
            val e = rules.evaluate(zone, LocalDateTime.parse(v.getString("start")), v.getInt("minutes"), v.getInt("used"), v.getBoolean("boundary"))
            val x = v.getJSONObject("expect")
            val want = listOf(x.getInt("freeMinutes"), x.getInt("paidMinutes"), if (x.isNull("costILS")) null else x.getDouble("costILS"),
                if (x.isNull("paidFrom")) null else x.getString("paidFrom"), x.getBoolean("appRequired"))
            val got = listOf(e.freeMinutes, e.paidMinutes, e.cost, e.paidFrom?.toString(), e.appRequired)
            if (want != got) failures += "${v.getString("name")}: want $want got $got"
        }
        assertEquals(failures.joinToString("\n"), 0, failures.size)
    }
}
