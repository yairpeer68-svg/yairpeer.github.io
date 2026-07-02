package com.sherlock.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Type of identifier being investigated. */
enum class SubjectType(val label: String, val icon: String) {
    USERNAME("Username", "person"),
    EMAIL("Email", "mail"),
    PHONE("Phone", "phone"),
    DOMAIN("Domain", "language"),
    IP("IP Address", "router"),
    NAME("Full Name", "badge"),
    IMAGE("Image", "image");

    companion object {
        fun fromName(n: String): SubjectType = entries.firstOrNull { it.name == n } ?: USERNAME
    }
}

/** An investigation case — the top-level container. */
@Entity(tableName = "cases")
data class CaseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/** An identifier tracked inside a case (username, email, phone, ...). */
@Entity(
    tableName = "subjects",
    indices = [Index("caseId"), Index(value = ["caseId", "type", "value"], unique = true)]
)
data class SubjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val caseId: Long,
    val type: String,
    val value: String,
    val note: String = "",
    val origin: String = "manual",          // "manual" or "derived:<source>"
    val derivedFromFindingId: Long? = null,
    val investigated: Boolean = false,
    val addedAt: Long = System.currentTimeMillis()
) {
    val subjectType: SubjectType get() = SubjectType.fromName(type)
}

/** A result produced by investigating a subject against one source. */
@Entity(
    tableName = "findings",
    indices = [Index("caseId"), Index("subjectId")]
)
data class FindingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val caseId: Long,
    val subjectId: Long,
    val source: String,           // "GitHub", "ip-api", "RDAP", ...
    val category: String = "",    // grouping bucket
    val title: String,
    val detail: String = "",
    val url: String = "",
    val positive: Boolean = true, // true = hit / found
    val createdAt: Long = System.currentTimeMillis()
)
