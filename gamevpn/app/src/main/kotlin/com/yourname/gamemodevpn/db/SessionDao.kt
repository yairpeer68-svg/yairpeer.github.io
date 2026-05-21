package com.yourname.gamemodevpn.db

import androidx.room.*

@Dao
interface SessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(session: SessionEntity): Long

    @Query("SELECT * FROM sessions ORDER BY start_time DESC LIMIT :n")
    fun getLast(n: Int): List<SessionEntity>

    @Query("""
        SELECT COUNT(*), AVG(avg_ping), MIN(min_ping), MAX(max_ping),
               AVG(packet_loss), SUM(duration_sec)
        FROM sessions
    """)
    fun getOverallRaw(): OverallStatsRaw

    @Query("DELETE FROM sessions WHERE start_time < :cutoff")
    fun deleteOlderThan(cutoff: Long): Int

    @Query("SELECT COUNT(*) FROM sessions")
    fun count(): Int
}

data class OverallStatsRaw(
    val count: Int,
    val avgPing: Double,
    val minPing: Int,
    val maxPing: Int,
    val avgLoss: Double,
    val totalSec: Long
)
