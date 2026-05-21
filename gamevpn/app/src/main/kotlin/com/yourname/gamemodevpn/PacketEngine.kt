package com.yourname.gamemodevpn

object PacketEngine {
    init { try { System.loadLibrary("packetengine") } catch (e: UnsatisfiedLinkError) { } }

    // ── Packet processing ─────────────────────────────────────────────────────
    external fun processPacket(packet: ByteArray, length: Int, isGamePkt: Boolean = false): Int
    external fun buildRst(original: ByteArray, length: Int): ByteArray?
    external fun recordLoss()
    external fun getPacketLoss(): Float
    external fun resetCounters()
    external fun getVersion(): String

    // ── CPU/Thread ────────────────────────────────────────────────────────────
    external fun pinToBigCores(): Int
    external fun setRealtimeScheduling(): Int
    external fun setHighPriorityMode(enable: Boolean)
    external fun getNumCores(): Int

    // ── Socket tuning ─────────────────────────────────────────────────────────
    external fun tuneSocket(fd: Int): Int

    // ── Memory ────────────────────────────────────────────────────────────────
    external fun adviseKeepInRam(pid: Int): Int  // madvise WILLNEED for game
}
