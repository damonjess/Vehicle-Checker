package com.example.vehiclechecker

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "my_notes")
data class NoteEntity(
    @PrimaryKey val registration: String,
    val note: String,
    val timestamp: Long = System.currentTimeMillis()
)
