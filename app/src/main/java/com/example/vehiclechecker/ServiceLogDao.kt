package com.example.vehiclechecker

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ServiceLogDao {
    @Query("SELECT * FROM service_logs WHERE registration = :registration ORDER BY timestamp DESC")
    suspend fun getLogs(registration: String): List<ServiceLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: ServiceLogEntity)

    @Query("DELETE FROM service_logs WHERE id = :id")
    suspend fun deleteLog(id: Int)
}
