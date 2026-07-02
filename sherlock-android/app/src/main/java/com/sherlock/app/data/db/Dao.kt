package com.sherlock.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CaseDao {
    @Query("SELECT * FROM cases ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<CaseEntity>>

    @Query("SELECT * FROM cases WHERE id = :id")
    fun observe(id: Long): Flow<CaseEntity?>

    @Query("SELECT * FROM cases WHERE id = :id")
    suspend fun get(id: Long): CaseEntity?

    @Insert
    suspend fun insert(entity: CaseEntity): Long

    @Update
    suspend fun update(entity: CaseEntity)

    @Query("UPDATE cases SET updatedAt = :ts WHERE id = :id")
    suspend fun touch(id: Long, ts: Long)

    @Query("DELETE FROM cases WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface SubjectDao {
    @Query("SELECT * FROM subjects WHERE caseId = :caseId ORDER BY addedAt ASC")
    fun observeForCase(caseId: Long): Flow<List<SubjectEntity>>

    @Query("SELECT * FROM subjects WHERE id = :id")
    suspend fun get(id: Long): SubjectEntity?

    @Query("SELECT COUNT(*) FROM subjects WHERE caseId = :caseId AND type = :type AND value = :value")
    suspend fun exists(caseId: Long, type: String, value: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(subject: SubjectEntity): Long

    @Query("UPDATE subjects SET investigated = :done WHERE id = :id")
    suspend fun markInvestigated(id: Long, done: Boolean)

    @Query("DELETE FROM subjects WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface FindingDao {
    @Query("SELECT * FROM findings WHERE caseId = :caseId ORDER BY createdAt DESC")
    fun observeForCase(caseId: Long): Flow<List<FindingEntity>>

    @Query("SELECT * FROM findings WHERE subjectId = :subjectId ORDER BY createdAt DESC")
    fun observeForSubject(subjectId: Long): Flow<List<FindingEntity>>

    @Query("SELECT * FROM findings WHERE caseId = :caseId")
    suspend fun getForCase(caseId: Long): List<FindingEntity>

    @Query("DELETE FROM findings WHERE subjectId = :subjectId")
    suspend fun deleteForSubject(subjectId: Long)

    @Insert
    suspend fun insert(finding: FindingEntity): Long
}
