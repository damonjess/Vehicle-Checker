package com.example.vehiclechecker

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface NoteDao {

    @Query("SELECT * FROM my_notes WHERE registration = :registration")
    suspend fun getNote(registration: String): NoteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: NoteEntity)

    @Query("DELETE FROM my_notes WHERE registration = :registration")
    suspend fun delete(registration: String)
}
