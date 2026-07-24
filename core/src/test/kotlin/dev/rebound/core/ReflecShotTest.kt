package dev.rebound.core

import dev.rebound.core.play.ReflecShot
import dev.rebound.core.FieldGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflecShotTest {

    private fun shot(startX: Float, landingX: Float, endMs: Double = 1000.0) =
        ReflecShot(id = 0, startX = startX, startMs = 0.0, landingX = landingX, endMs = endMs)

    // --- folding ------------------------------------------------------------

    @Test
    fun `inside the field a position passes straight through`() {
        assertEquals(0.25f, FieldGeometry.fold(0.25f), 1e-6f)
        assertEquals(0.9f, FieldGeometry.fold(0.9f), 1e-6f)
    }

    @Test
    fun `past a wall a position comes back inside`() {
        assertEquals("reflected off the right wall", 0.8f, FieldGeometry.fold(1.2f), 1e-6f)
        assertEquals("reflected off the left wall", 0.3f, FieldGeometry.fold(-0.3f), 1e-6f)
    }

    @Test
    fun `folding never leaves the field however far out it starts`() {
        var u = -6.0f
        while (u <= 6.0f) {
            val folded = FieldGeometry.fold(u)
            assertTrue("escaped at $u: $folded", folded in -1e-5f..1f + 1e-5f)
            u += 0.037f
        }
    }

    // --- aiming -------------------------------------------------------------

    @Test
    fun `asking for no bounce takes the direct line`() {
        assertEquals(0.8f, ReflecShot.unfoldedTarget(0.2f, 0.8f, dirX = 0.0f, wanted = 0), 1e-6f)
    }

    @Test
    fun `a straight flick keeps the shot on the direct line`() {
        val s = shot(0.2f, 0.8f, endMs = 1000.0)
        s.swing(dirX = 0.0f, dirY = -1f)
        assertEquals("no wall on a straight shot", 0, s.sideBounces)
        assertEquals(0.8f, s.xAt(1000.0), 1e-5f)
    }

    @Test
    fun `a sideways flick still lands where it must`() {
        val target = ReflecShot.unfoldedTarget(0.2f, 0.8f, dirX = 1f, wanted = 2)
        assertTrue("aimed to the right", target > 0.2f)
        assertEquals(
            "but folds back onto the landing point",
            0.8f, FieldGeometry.fold(target), 1e-5f,
        )
    }

    @Test
    fun `the aim meets exactly the number of walls asked for`() {
        val once = ReflecShot.unfoldedTarget(0.5f, 0.2f, dirX = 1f, wanted = 1)
        assertEquals(1, FieldGeometry.wallCrossings(0.5f, once))
        assertEquals(0.2f, FieldGeometry.fold(once), 1e-5f)

        val twice = ReflecShot.unfoldedTarget(0.5f, 0.2f, dirX = 1f, wanted = 2)
        assertEquals(2, FieldGeometry.wallCrossings(0.5f, twice))
        assertEquals(0.2f, FieldGeometry.fold(twice), 1e-5f)
    }

    @Test
    fun `flicking either way sends it round a different side`() {
        val right = ReflecShot.unfoldedTarget(0.5f, 0.5f, dirX = 1f, wanted = 2)
        val left = ReflecShot.unfoldedTarget(0.5f, 0.5f, dirX = -1f, wanted = 2)
        assertTrue(right > 0.5f)
        assertTrue(left < 0.5f)
        assertEquals(0.5f, FieldGeometry.fold(right), 1e-5f)
        assertEquals(0.5f, FieldGeometry.fold(left), 1e-5f)
    }

    // --- flight -------------------------------------------------------------

    @Test
    fun `a shot crosses the field over its whole flight`() {
        val s = shot(0.5f, 0.5f, endMs = 800.0)
        assertEquals(1f, s.yAt(0.0), 1e-6f)
        assertEquals(0.5f, s.yAt(400.0), 1e-6f)
        assertEquals(0f, s.yAt(800.0), 1e-6f)
    }

    @Test
    fun `a longer gap simply slows the shot down`() {
        val quick = shot(0.2f, 0.7f, endMs = 400.0)
        val slow = shot(0.2f, 0.7f, endMs = 4000.0)

        // Same path, different pace: at the same moment the slow one is further back.
        assertTrue(slow.yAt(400.0) > quick.yAt(400.0))
        assertEquals(0.7f, quick.xAt(400.0), 1e-5f)
        assertEquals(0.7f, slow.xAt(4000.0), 1e-5f)
    }

    @Test
    fun `a swung shot bounces yet still arrives on target`() {
        val s = shot(0.5f, 0.2f, endMs = 1000.0)
        s.swing(dirX = 1f)

        assertTrue("should meet a wall", s.bounces >= 1)
        assertEquals(0.2f, s.xAt(1000.0), 1e-5f)
    }

    @Test
    fun `a swung shot stays inside the field throughout`() {
        val s = shot(0.5f, 0.2f, endMs = 1000.0)
        s.swing(dirX = -1f)

        var t = 0.0
        while (t <= 1000.0) {
            val x = s.xAt(t)
            assertTrue("escaped at $t: $x", x in -1e-5f..1f + 1e-5f)
            t += 7.0
        }
    }

    @Test
    fun `a shot bound to a tap-point object stops short of the bar`() {
        val s = shot(0.5f, 0.5f, endMs = 1000.0)
        // A green object is judged part way up the field, not on the bar.
        s.aimAt(landingX = 0.5f, arriveAtMs = 1000.0, landingY = 0.26f)

        assertEquals("stops where the object is judged", 0.26f, s.yAt(1000.0), 1e-5f)
        assertTrue("and is still short of the bar", s.yAt(1000.0) > 0f)
    }

    @Test
    fun `re-aiming moves both the landing point and the arrival`() {
        val s = shot(0.5f, 0.5f, endMs = 500.0)
        s.aimAt(landingX = 0.9f, arriveAtMs = 3000.0)

        assertEquals(3000.0, s.endMs, 1e-9)
        assertEquals(0.9f, s.xAt(3000.0), 1e-5f)
    }

    @Test
    fun `a shot reports arrival only once it is due`() {
        val s = shot(0.5f, 0.5f, endMs = 1000.0)
        assertFalse(s.hasArrived(999.0))
        assertTrue(s.hasArrived(1000.0))
    }

    @Test
    fun `position is clamped outside the flight`() {
        val s = shot(0.2f, 0.8f, endMs = 1000.0)
        assertEquals(1f, s.yAt(-500.0), 1e-6f)
        assertEquals(0f, s.yAt(9000.0), 1e-6f)
    }
}
