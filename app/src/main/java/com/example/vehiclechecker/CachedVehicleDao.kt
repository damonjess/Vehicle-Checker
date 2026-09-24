package com.example.vehiclechecker

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CachedVehicleDao {

    @Query("SELECT * FROM cached_vehicles WHERE registration = :registration")
    suspend fun get(registration: String): CachedVehicleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cache: CachedVehicleEntity)

    @Query("DELETE FROM cached_vehicles WHERE timestamp < :olderThan")
    suspend fun purgeOlderThan(olderThan: Long)
}
