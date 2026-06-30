package com.sherlock.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "digital_identities")
data class DigitalIdentity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "identity_links")
data class IdentityLink(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val identityId: Long,
    val platform: String,
    val username: String = "",
    val url: String,
    val createdAt: Long = System.currentTimeMillis()
)

data class LinkHealthResult(
    val url: String,
    val isAlive: Boolean,
    val statusCode: Int?,
    val errorMessage: String? = null
)

data class UsernameMatchResult(
    val usernameA: String,
    val usernameB: String,
    val similarityPercent: Int,
    val verdict: String,
    val notes: List<String>
)

data class PlatformFootprint(
    val category: String,
    val platforms: List<String>
)

data class UsernameFormatRule(
    val platform: String,
    val minLength: Int,
    val maxLength: Int,
    val allowedPattern: Regex,
    val description: String
)

data class PlatformGuideTip(
    val platform: String,
    val tips: List<String>
)
