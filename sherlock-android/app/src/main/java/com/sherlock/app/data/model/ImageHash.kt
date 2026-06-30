package com.sherlock.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "image_hashes")
data class ImageHash(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val hash: Long,
    val imagePath: String,
    val createdAt: Long = System.currentTimeMillis()
)

data class VehicleInfo(
    val plate: String,
    val manufacturer: String?,
    val model: String?,
    val year: String?,
    val color: String?,
    val fuelType: String?,
    val ownershipType: String?
)
