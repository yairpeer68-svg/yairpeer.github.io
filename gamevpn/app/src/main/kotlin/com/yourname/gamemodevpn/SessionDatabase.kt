package com.yourname.gamemodevpn

import android.content.Context
import com.yourname.gamemodevpn.db.AppDatabase
import com.yourname.gamemodevpn.db.SessionEntity

// Existing SessionRecord data class stays the same for backward compatibility
data class SessionRecord(
    val id: Long = 0,
    val game: String,
    val startTime: Long,
    val durationSec: Int,
    val avgPing: Int,
    val minPing: Int,
    val maxPing: Int,
    val packetLoss: Float,
    val avgJitter: Int
)

// Bridge: SessionRecord ↔ SessionEntity
private fun SessionRecord.toEntity() = SessionEntity(
    id = id, game = game, startTime = startTime, durationSec = durationSec,
    avgPing = avgPing, minPing = minPing, maxPing = maxPing,
    packetLoss = packetLoss, avgJitter = avgJitter
)

private fun SessionEntity.toRecord() = SessionRecord(
    id = id, game = game, startTime = startTime, durationSec = durationSec,
    avgPing = avgPing, minPing = minPing, maxPing = maxPing,
    packetLoss = packetLoss, avgJitter = avgJitter
)

class SessionDatabase(ctx: Context) {

    private val dao = AppDatabase.get(ctx).sessionDao()

    fun insert(s: SessionRecord) { dao.insert(s.toEntity()) }

    fun getLast(n: Int = 20): List<SessionRecord> = dao.getLast(n).map { it.toRecord() }

    fun getOverallStats(): Map<String, Any> {
        val raw = runCatching { dao.getOverallRaw() }.getOrNull() ?: return emptyMap()
        return mapOf(
            "sessions"    to raw.count,
            "avg_ping"    to raw.avgPing.toInt(),
            "best_ping"   to raw.minPing,
            "worst_ping"  to raw.maxPing,
            "packet_loss" to raw.avgLoss.toFloat(),
            "total_hours" to (raw.totalSec / 3600).toInt()
        )
    }

    fun pruneOldSessions(keepDays: Int = 30) {
        val cutoff = System.currentTimeMillis() - keepDays.toLong() * 24 * 3600 * 1000
        dao.deleteOlderThan(cutoff)
    }
}
