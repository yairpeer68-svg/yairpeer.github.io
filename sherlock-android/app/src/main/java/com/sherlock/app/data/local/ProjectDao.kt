package com.sherlock.app.data.local

import androidx.room.*
import com.sherlock.app.data.model.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Insert suspend fun insert(project: Project): Long
    @Update suspend fun update(project: Project)
    @Delete suspend fun delete(project: Project)
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC") fun getAll(): Flow<List<Project>>
    @Query("SELECT * FROM projects WHERE id = :id") suspend fun getById(id: Long): Project?
    @Query("SELECT COUNT(*) FROM projects") suspend fun getCount(): Int
}

@Dao
interface NoteDao {
    @Insert suspend fun insert(note: ProfileNote): Long
    @Update suspend fun update(note: ProfileNote)
    @Delete suspend fun delete(note: ProfileNote)
    @Query("SELECT * FROM profile_notes WHERE profileUrl = :url") fun getForProfile(url: String): Flow<List<ProfileNote>>
    @Query("SELECT * FROM profile_notes WHERE projectId = :projectId ORDER BY timestamp DESC") fun getForProject(projectId: Long): Flow<List<ProfileNote>>
    @Query("SELECT * FROM profile_notes ORDER BY timestamp DESC") fun getAll(): Flow<List<ProfileNote>>
    @Query("SELECT COUNT(*) FROM profile_notes") suspend fun getCount(): Int
    @Query("DELETE FROM profile_notes") suspend fun clearAll()
}

@Dao
interface TemplateDao {
    @Insert suspend fun insert(template: SearchTemplate): Long
    @Update suspend fun update(template: SearchTemplate)
    @Delete suspend fun delete(template: SearchTemplate)
    @Query("SELECT * FROM search_templates ORDER BY name") fun getAll(): Flow<List<SearchTemplate>>
}

@Dao
interface LoginDao {
    @Insert suspend fun insert(record: LoginRecord)
    @Query("SELECT * FROM login_history ORDER BY timestamp DESC LIMIT 50") fun getRecent(): Flow<List<LoginRecord>>
    @Query("DELETE FROM login_history") suspend fun clearAll()
}

@Dao
interface CustomSiteDao {
    @Insert suspend fun insert(site: CustomSite): Long
    @Update suspend fun update(site: CustomSite)
    @Delete suspend fun delete(site: CustomSite)
    @Query("SELECT * FROM custom_sites ORDER BY name") fun getAll(): Flow<List<CustomSite>>
    @Query("SELECT COUNT(*) FROM custom_sites") suspend fun getCount(): Int
}
