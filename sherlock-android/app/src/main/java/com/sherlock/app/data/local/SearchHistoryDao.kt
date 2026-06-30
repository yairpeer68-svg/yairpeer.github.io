package com.sherlock.app.data.local

import androidx.room.*
import com.sherlock.app.data.model.SearchHistory
import com.sherlock.app.data.model.SearchResult
import com.sherlock.app.data.model.SiteCategory
import kotlinx.coroutines.flow.Flow

@Dao
interface SearchHistoryDao {

    @Insert
    suspend fun insertHistory(history: SearchHistory): Long

    @Insert
    suspend fun insertResults(results: List<SearchResult>)

    @Query("SELECT * FROM search_history ORDER BY timestamp DESC")
    fun getAllHistory(): Flow<List<SearchHistory>>

    @Query("SELECT * FROM search_history ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentHistory(limit: Int): Flow<List<SearchHistory>>

    @Query("SELECT * FROM search_results WHERE historyId = :historyId")
    suspend fun getResultsForHistory(historyId: Long): List<SearchResult>

    @Query("SELECT COUNT(*) FROM search_history")
    suspend fun getTotalSearches(): Int

    @Query("SELECT SUM(totalFound) FROM search_history")
    suspend fun getTotalFound(): Int

    @Query("SELECT AVG(totalFound * 100.0 / totalChecked) FROM search_history WHERE totalChecked > 0")
    suspend fun getAverageSuccessRate(): Float

    @Query("SELECT searchType, COUNT(*) as count FROM search_history GROUP BY searchType ORDER BY count DESC")
    suspend fun getSearchTypeStats(): List<SearchTypeStat>

    @Query("DELETE FROM search_history")
    suspend fun clearAllHistory()

    @Delete
    suspend fun deleteHistory(history: SearchHistory)

    @Query("DELETE FROM search_results WHERE historyId = :historyId")
    suspend fun deleteResultsForHistory(historyId: Long)

    @Query("DELETE FROM search_history WHERE timestamp < :before")
    suspend fun deleteHistoryBefore(before: Long)

    @Query("SELECT siteName, COUNT(*) as count FROM search_results WHERE `exists` = 1 GROUP BY siteName ORDER BY count DESC LIMIT 10")
    suspend fun getTopSites(): List<SiteCountStat>

    @Query("SELECT category, COUNT(*) as count FROM search_results WHERE `exists` = 1 GROUP BY category ORDER BY count DESC")
    suspend fun getCategoryStats(): List<CategoryCountStat>

    @Query("SELECT strftime('%Y-%m-%d', timestamp / 1000, 'unixepoch') as day, COUNT(*) as count FROM search_history GROUP BY day ORDER BY day DESC LIMIT 14")
    suspend fun getDailyActivity(): List<DailyActivityStat>

    @Query("SELECT siteName, AVG(responseTimeMs) as avgResponseMs FROM search_results WHERE `exists` = 1 AND responseTimeMs > 0 GROUP BY siteName ORDER BY avgResponseMs DESC LIMIT 10")
    suspend fun getSlowestSites(): List<SiteResponseStat>

    @Query("SELECT AVG(responseTimeMs) FROM search_results WHERE responseTimeMs > 0")
    suspend fun getAverageResponseTime(): Float

    @Query("SELECT COUNT(*) FROM search_history WHERE timestamp >= :since")
    suspend fun getSearchCountSince(since: Long): Int
}

data class SearchTypeStat(
    val searchType: String,
    val count: Int
)

data class SiteCountStat(
    val siteName: String,
    val count: Int
)

data class CategoryCountStat(
    val category: SiteCategory,
    val count: Int
)

data class DailyActivityStat(
    val day: String,
    val count: Int
)

data class SiteResponseStat(
    val siteName: String,
    val avgResponseMs: Float
)
