package com.sherlock.app.data.local

import androidx.room.*
import com.sherlock.app.data.model.SearchHistory
import com.sherlock.app.data.model.SearchResult
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
}

data class SearchTypeStat(
    val searchType: String,
    val count: Int
)
