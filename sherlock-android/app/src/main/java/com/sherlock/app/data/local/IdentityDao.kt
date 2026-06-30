package com.sherlock.app.data.local

import androidx.room.*
import com.sherlock.app.data.model.DigitalIdentity
import com.sherlock.app.data.model.IdentityLink
import kotlinx.coroutines.flow.Flow

@Dao
interface DigitalIdentityDao {
    @Insert suspend fun insert(identity: DigitalIdentity): Long
    @Update suspend fun update(identity: DigitalIdentity)
    @Delete suspend fun delete(identity: DigitalIdentity)
    @Query("SELECT * FROM digital_identities ORDER BY createdAt DESC") fun getAll(): Flow<List<DigitalIdentity>>
    @Query("SELECT * FROM digital_identities WHERE id = :id") fun getByIdFlow(id: Long): Flow<DigitalIdentity?>
}

@Dao
interface IdentityLinkDao {
    @Insert suspend fun insert(link: IdentityLink): Long
    @Delete suspend fun delete(link: IdentityLink)
    @Query("SELECT * FROM identity_links WHERE identityId = :identityId ORDER BY createdAt DESC") fun getForIdentity(identityId: Long): Flow<List<IdentityLink>>
    @Query("SELECT * FROM identity_links ORDER BY createdAt DESC") fun getAll(): Flow<List<IdentityLink>>
    @Query("DELETE FROM identity_links WHERE identityId = :identityId") suspend fun deleteAllForIdentity(identityId: Long)
}
