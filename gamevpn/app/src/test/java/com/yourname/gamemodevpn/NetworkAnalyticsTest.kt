package com.yourname.gamemodevpn

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class NetworkAnalyticsTest {

    private fun computeCorrelation(pings: List<Int>, temps: List<Float>): Float {
        if (pings.size != temps.size || pings.size < 2) return 0f
        val n = pings.size
        val xMean = pings.average()
        val yMean = temps.map { it.toDouble() }.average()
        var cov = 0.0; var sx = 0.0; var sy = 0.0
        for (i in 0 until n) {
            val dx = pings[i] - xMean; val dy = temps[i] - yMean
            cov += dx * dy; sx += dx * dx; sy += dy * dy
        }
        return if (sx > 0 && sy > 0) (cov / sqrt(sx * sy)).toFloat() else 0f
    }

    @Test
    fun `perfect positive correlation returns 1`() {
        val pings = listOf(10, 20, 30, 40, 50)
        val temps = listOf(30f, 31f, 32f, 33f, 34f)
        val r = computeCorrelation(pings, temps)
        assertEquals(1f, r, 0.001f)
    }

    @Test
    fun `perfect negative correlation returns -1`() {
        val pings = listOf(50, 40, 30, 20, 10)
        val temps = listOf(30f, 31f, 32f, 33f, 34f)
        val r = computeCorrelation(pings, temps)
        assertEquals(-1f, r, 0.001f)
    }

    @Test
    fun `mismatched sizes returns 0`() {
        val r = computeCorrelation(listOf(10, 20), listOf(30f))
        assertEquals(0f, r, 0.001f)
    }

    @Test
    fun `single element returns 0`() {
        val r = computeCorrelation(listOf(10), listOf(30f))
        assertEquals(0f, r, 0.001f)
    }

    @Test
    fun `uncorrelated data returns near 0`() {
        val pings = listOf(10, 50, 20, 80, 30)
        val temps = listOf(30f, 30f, 30f, 30f, 30f) // no variance in temps
        val r = computeCorrelation(pings, temps)
        assertEquals(0f, r, 0.001f)
    }
}
