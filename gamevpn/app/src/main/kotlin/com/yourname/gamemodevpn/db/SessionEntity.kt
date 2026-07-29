package com.yourname.gamemodevpn.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "game")         val game: String,
    @ColumnInfo(name = "start_time")   val startTime: Long,
    @ColumnInfo(name = "duration_sec") val durationSec: Int,
    @ColumnInfo(name = "avg_ping")     val avgPing: Int,
    @ColumnInfo(name = "min_ping")     val minPing: Int,
    @ColumnInfo(name = "max_ping")     val maxPing: Int,
    @ColumnInfo(name = "packet_loss")  val packetLoss: Float,
    @ColumnInfo(name = "avg_jitter")   val avgJitter: Int
)
