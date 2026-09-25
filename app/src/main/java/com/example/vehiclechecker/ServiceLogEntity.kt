package com.example.vehiclechecker

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "service_logs")
data class ServiceLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val registration: String,
    val date: String,
    val mileage: String,
    val description: String,
    val cost: String,
    val timestamp: Long = System.currentTimeMillis()
)
