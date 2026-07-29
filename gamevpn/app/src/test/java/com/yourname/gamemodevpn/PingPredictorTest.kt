package com.yourname.gamemodevpn

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PingPredictorTest {

    private lateinit var predictor: PingPredictor

    @Before
    fun setup() { predictor = PingPredictor() }

    @Test
    fun `returns no data message when fewer than 5 samples`() {
        predictor.addSample(30)
        predictor.addSample(31)
        val pred = predictor.predict()
        assertFalse(pred.spikeWarning)
        assertEquals(0, pred.predicted)
    }

    @Test
    fun `stable ping produces no spike warning`() {
        repeat(30) { predictor.addSample(20) }
        val pred = predictor.predict()
        assertFalse(pred.spikeWarning)
        assertEquals("✅ Ping יציב (20ms)", pred.message)
    }

    @Test
    fun `rising trend produces spike warning`() {
        repeat(20) { predictor.addSample(20) }       // baseline
        repeat(10) { i -> predictor.addSample(20 + i * 15) }  // sharp rise
        val pred = predictor.predict()
        assertTrue(pred.spikeWarning)
    }

    @Test
    fun `high absolute ping triggers spike warning`() {
        repeat(20) { predictor.addSample(20) }
        predictor.addSample(200)
        val pred = predictor.predict()
        assertTrue(pred.spikeWarning)
    }

    @Test
    fun `falling ping produces improving message`() {
        repeat(10) { predictor.addSample(150) }
        repeat(15) { i -> predictor.addSample(150 - i * 8) }
        val pred = predictor.predict()
        assertFalse(pred.spikeWarning)
        assertTrue(pred.trend < 0)
    }

    @Test
    fun `reset clears all state`() {
        repeat(30) { predictor.addSample(50) }
        predictor.reset()
        val pred = predictor.predict()
        assertEquals(0, pred.predicted)
        assertEquals(0f, pred.confidence, 0.001f)
    }

    @Test
    fun `confidence increases with more samples`() {
        repeat(10) { predictor.addSample(30) }
        val low = predictor.predict().confidence
        repeat(100) { predictor.addSample(30) }
        val high = predictor.predict().confidence
        assertTrue("Confidence should increase with samples", high > low)
    }

    @Test
    fun `getStats returns correct keys`() {
        repeat(10) { predictor.addSample(40) }
        val stats = predictor.getStats()
        assertTrue(stats.containsKey("ema"))
        assertTrue(stats.containsKey("trend"))
        assertTrue(stats.containsKey("min"))
        assertTrue(stats.containsKey("max"))
        assertTrue(stats.containsKey("samples"))
    }

    @Test
    fun `adaptive alpha increases for noisy input`() {
        // Stable baseline
        repeat(30) { predictor.addSample(20) }
        val stablePred = predictor.predict()

        predictor.reset()

        // Noisy baseline
        repeat(30) { i -> predictor.addSample(if (i % 2 == 0) 10 else 100) }
        val noisyPred = predictor.predict()

        // Noisy should have lower confidence
        assertTrue(stablePred.confidence > noisyPred.confidence)
    }
}
