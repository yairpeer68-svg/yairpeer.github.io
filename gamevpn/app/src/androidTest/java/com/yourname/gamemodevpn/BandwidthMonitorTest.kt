package com.yourname.gamemodevpn

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BandwidthMonitorInstrumentedTest {

    @Test
    fun bandwidthMonitor_sampleReceived_within3Seconds() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val latch = CountDownLatch(1)
        var received: BandwidthMonitor.Sample? = null

        val monitor = BandwidthMonitor()
        monitor.onSample = { sample ->
            received = sample
            latch.countDown()
        }
        monitor.start()

        val triggered = latch.await(3, TimeUnit.SECONDS)
        monitor.stop()

        assertTrue("No sample received within 3s", triggered)
        assertNotNull(received)
        // RX/TX should be non-negative
        assertTrue(received!!.rxKbps >= 0f)
        assertTrue(received!!.txKbps >= 0f)
    }

    @Test
    fun bandwidthMonitor_stop_preventsFurtherCallbacks() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        var callCount = 0
        val monitor = BandwidthMonitor()
        monitor.onSample = { callCount++ }
        monitor.start()
        Thread.sleep(1500)
        monitor.stop()
        val countAfterStop = callCount
        Thread.sleep(2000)
        // No new callbacks after stop
        assertEquals(countAfterStop, callCount)
    }
}
