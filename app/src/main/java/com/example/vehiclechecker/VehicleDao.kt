package com.example.vehiclechecker

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface VehicleDao {

    // If the plate already exists, replace it to update the timestamp
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSearch(vehicle: VehicleEntity)

    // Pull all searches, ordered by newest first
    @Query("SELECT * FROM recent_searches ORDER BY timestamp DESC")
    suspend fun getAllRecentSearches(): List<VehicleEntity>

    // Favourites / watchlist
    @Query("SELECT * FROM recent_searches WHERE isFavourite = 1 ORDER BY timestamp DESC")
    suspend fun getFavourites(): List<VehicleEntity>

    @Query("UPDATE recent_searches SET isFavourite = :favourite WHERE registration = :registration")
    suspend fun setFavourite(registration: String, favourite: Boolean)

    // Optional: Let the user clear their history
    @Query("DELETE FROM recent_searches")
    suspend fun clearHistory()
}
