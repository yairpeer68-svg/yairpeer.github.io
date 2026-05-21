package com.yourname.gamemodevpn

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

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

class SessionDatabase(ctx: Context) :
    SQLiteOpenHelper(ctx, "sessions.db", null, 2) {

    companion object {
        const val TABLE = "sessions"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE $TABLE (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            game TEXT, start_time INTEGER, duration_sec INTEGER,
            avg_ping INTEGER, min_ping INTEGER, max_ping INTEGER,
            packet_loss REAL, avg_jitter INTEGER
        )""")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE"); onCreate(db)
    }

    fun insert(s: SessionRecord) {
        writableDatabase.insert(TABLE, null, ContentValues().apply {
            put("game", s.game); put("start_time", s.startTime)
            put("duration_sec", s.durationSec); put("avg_ping", s.avgPing)
            put("min_ping", s.minPing); put("max_ping", s.maxPing)
            put("packet_loss", s.packetLoss); put("avg_jitter", s.avgJitter)
        })
    }

    fun getLast(n: Int = 20): List<SessionRecord> {
        val list = mutableListOf<SessionRecord>()
        val c = readableDatabase.query(TABLE, null, null, null, null, null,
            "start_time DESC", "$n")
        c.use {
            while (it.moveToNext()) list.add(SessionRecord(
                id          = it.getLong(0),
                game        = it.getString(1),
                startTime   = it.getLong(2),
                durationSec = it.getInt(3),
                avgPing     = it.getInt(4),
                minPing     = it.getInt(5),
                maxPing     = it.getInt(6),
                packetLoss  = it.getFloat(7),
                avgJitter   = it.getInt(8)
            ))
        }
        return list
    }

    fun getOverallStats(): Map<String, Any> {
        val c = readableDatabase.rawQuery("""
            SELECT COUNT(*), AVG(avg_ping), MIN(min_ping), MAX(max_ping),
                   AVG(packet_loss), SUM(duration_sec)
            FROM $TABLE""", null)
        return if (c.moveToFirst()) mapOf(
            "sessions"    to c.getInt(0),
            "avg_ping"    to c.getInt(1),
            "best_ping"   to c.getInt(2),
            "worst_ping"  to c.getInt(3),
            "packet_loss" to c.getFloat(4),
            "total_hours" to c.getInt(5) / 3600
        ).also { c.close() } else emptyMap()
    }
}
