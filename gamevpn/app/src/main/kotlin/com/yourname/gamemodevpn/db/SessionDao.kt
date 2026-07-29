package com.yourname.gamemodevpn.db

import androidx.room.*

@Dao
interface SessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(session: SessionEntity): Long

    @Query("SELECT * FROM sessions ORDER BY start_time DESC LIMIT :n")
    fun getLast(n: Int): List<SessionEntity>

    @Query("""
        SELECT COUNT(*) AS count, AVG(avg_ping) AS avgPing, MIN(min_ping) AS minPing,
               MAX(max_ping) AS maxPing, AVG(packet_loss) AS avgLoss, SUM(duration_sec) AS totalSec
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
