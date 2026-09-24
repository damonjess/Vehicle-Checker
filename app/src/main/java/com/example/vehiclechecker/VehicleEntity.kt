package com.example.vehiclechecker

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recent_searches")
data class VehicleEntity(
    @PrimaryKey val registration: String,
    val make: String,
    val colour: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isFavourite: Boolean = false,
    val taxDueEpochMs: Long? = null,
    val motExpiryEpochMs: Long? = null
)
