package com.yourname.gamemodevpn

import org.junit.Assert.*
import org.junit.Test

class ServerPingScannerTest {

    @Test
    fun `getBestServer returns null when no servers pinged`() {
        // Before any scan, all server pingMs == -1 so getBestServer returns null
        assertNull(ServerPingScanner.getBestServer("CoD"))
    }

    @Test
    fun `GameServer data class holds correct values`() {
        val server = GameServer("Middle East", "example.com", 443, "CoD", 45L, "🟢 מעולה")
        assertEquals("Middle East", server.region)
        assertEquals("example.com", server.host)
        assertEquals(443, server.port)
        assertEquals("CoD", server.game)
        assertEquals(45L, server.pingMs)
    }

    @Test
    fun `servers sorted by ping ascending`() {
        val servers = listOf(
            GameServer("EU", "eu.example.com", 443, "test", 100L),
            GameServer("ME", "me.example.com", 443, "test", 30L),
            GameServer("AS", "as.example.com", 443, "test", 60L)
        )
        val sorted = servers.sortedBy { it.pingMs }
        assertEquals("ME", sorted[0].region)
        assertEquals("AS", sorted[1].region)
        assertEquals("EU", sorted[2].region)
    }
}
