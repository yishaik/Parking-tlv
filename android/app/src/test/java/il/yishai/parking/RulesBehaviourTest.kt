package il.yishai.parking

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.LocalTime

class RulesBehaviourTest {
    private val rules = Rules.parse(
        JSONObject(File("../../config/rules.json").readText()),
        JSONObject(File("../../config/holidays.json").readText()),
    )
    private val sundayMorning = LocalDateTime.parse("2026-11-22T10:00")

    @Test fun ownZoneIsFreeAndNeedsNothing() {
        val ex = rules.explain(9, sundayMorning)
        assertEquals("חינם לך כאן", ex.headline)
        assertTrue(ex.freeNow == true)
        assertFalse("own zone needs no action", ex.actionable)
        assertFalse(rules.evaluate(9, sundayMorning, 120).appRequired)
    }

    @Test fun boundaryStreetIsFreeButNeedsPango() {
        val ex = rules.explain(6, sundayMorning, onPermitBoundary = true)
        assertTrue(ex.actionable)
        assertTrue(rules.evaluate(6, sundayMorning, 60, onPermitBoundary = true).appRequired)
    }

    @Test fun freeOverrideWins() {
        val on = rules.withOverride(true)
        assertEquals(0.0, on.evaluate(6, sundayMorning, 300).cost!!, 0.0)
        assertFalse(on.evaluate(6, sundayMorning, 300).appRequired)
    }

    @Test fun nextPaidStartAfterSaturday() {
        assertEquals(LocalDateTime.parse("2026-11-22T08:00"), rules.nextPaidStart(6, LocalDateTime.parse("2026-11-21T12:00")))
    }

    @Test fun closeToHomeLotHours() {
        assertTrue(rules.closeToHomeFree(LocalDateTime.parse("2026-11-22T19:30")))
        assertFalse(rules.closeToHomeFree(LocalDateTime.parse("2026-11-22T12:00")))
        assertEquals(LocalTime.of(19, 0), rules.closeToHomeFreeFrom(LocalDateTime.parse("2026-11-22T12:00")))
        assertEquals(LocalTime.of(15, 0), rules.closeToHomeFreeFrom(LocalDateTime.parse("2026-11-20T12:00"))) // Friday
        assertNull(rules.closeToHomeFreeFrom(LocalDateTime.parse("2026-11-21T12:00"))) // Saturday
    }

    @Test fun holidayEveCountsAsFriday() {
        assertEquals(DayKind.FRI, rules.dayKind(java.time.LocalDate.parse("2027-04-21")))
        assertEquals(DayKind.SAT, rules.dayKind(java.time.LocalDate.parse("2027-04-22")))
    }

    @Test fun permitDetailsFromApprovalLetter() {
        assertEquals("59-999-79", rules.permitVehicle)
        assertEquals("2027-11-24", rules.permitValidUntil)
        assertEquals("31351457", rules.permitRef)
        assertEquals(
            "התו שלך: אזור 9 · רכב 59-999-79 · בתוקף עד 24/11/2027",
            rules.permitLine(java.time.LocalDate.parse("2026-11-25")),
        )
        assertEquals(
            "התו שלך: אזור 9 · רכב 59-999-79 · פג בעוד 30 ימים (24/11/2027)",
            rules.permitLine(java.time.LocalDate.parse("2027-10-25")),
        )
        assertEquals(
            "התו שלך: אזור 9 · רכב 59-999-79 · פג תוקף ב-24/11/2027",
            rules.permitLine(java.time.LocalDate.parse("2027-11-25")),
        )
    }

    @Test fun baselCloseToHomeStartsAtNine() {
        assertEquals("א׳–ה׳ מ-21:00 עד 08:00; שישי וערבי חג מ-15:00 עד 08:00 למחרת", closeToHomeException("חניון בזל"))
    }
}
