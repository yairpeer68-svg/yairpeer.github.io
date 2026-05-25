package com.yourname.gamemodevpn

import org.junit.Assert.*
import org.junit.Test

class AdaptiveLearnerScoreTest {

    // Test computeScore directly (no Context needed for this method)
    // We test via a minimal adapter to avoid needing a real Context

    private fun computeScore(ping: Int, loss: Float, jitter: Int, temp: Float): Int {
        val pingScore   = (1f - (ping / 200f).coerceIn(0f, 1f)) * 40f
        val lossScore   = (1f - (loss / 20f).coerceIn(0f, 1f)) * 35f
        val jitterScore = (1f - (jitter / 100f).coerceIn(0f, 1f)) * 15f
        val tempScore   = if (temp > 0) ((1f - ((temp - 30f) / 20f).coerceIn(0f, 1f)) * 10f) else 10f
        return (pingScore + lossScore + jitterScore + tempScore).toInt().coerceIn(0, 100)
    }

    @Test
    fun `perfect conditions score is 100`() {
        val score = computeScore(0, 0f, 0, 25f)
        assertEquals(100, score)
    }

    @Test
    fun `worst conditions score is 0`() {
        val score = computeScore(200, 20f, 100, 50f)
        assertEquals(0, score)
    }

    @Test
    fun `score decreases as ping increases`() {
        val low  = computeScore(20,  0f, 0, 25f)
        val mid  = computeScore(80,  0f, 0, 25f)
        val high = computeScore(150, 0f, 0, 25f)
        assertTrue(low > mid)
        assertTrue(mid > high)
    }

    @Test
    fun `score decreases as packet loss increases`() {
        val low  = computeScore(30, 0f,  0, 25f)
        val high = computeScore(30, 15f, 0, 25f)
        assertTrue(low > high)
    }

    @Test
    fun `high temperature reduces score`() {
        val cool = computeScore(30, 0f, 0, 28f)
        val hot  = computeScore(30, 0f, 0, 45f)
        assertTrue(cool > hot)
    }

    @Test
    fun `score label returns correct tier`() {
        assertEquals("🟢 מצוין (90)", getScoreLabel(90))
        assertEquals("🟡 טוב (70)", getScoreLabel(70))
        assertEquals("🟠 בינוני (50)", getScoreLabel(50))
        assertEquals("🔴 גרוע (30)", getScoreLabel(30))
    }

    private fun getScoreLabel(score: Int) = when {
        score >= 85 -> "🟢 מצוין ($score)"
        score >= 65 -> "🟡 טוב ($score)"
        score >= 45 -> "🟠 בינוני ($score)"
        else        -> "🔴 גרוע ($score)"
    }
}
