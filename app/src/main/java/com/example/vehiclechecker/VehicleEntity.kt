package com.example.vehiclechecker

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recent_searches")
data class VehicleEntity(
    @PrimaryKey val registration: String,
    val make: String,
    val colour: String,
    val timestamp: Long = System.currentTimeMillis()
)
