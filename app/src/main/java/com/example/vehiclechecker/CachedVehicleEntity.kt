package com.example.vehiclechecker

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.Gson

@Entity(tableName = "cached_vehicles")
data class CachedVehicleEntity(
    @PrimaryKey val registration: String,
    val vehicleJson: String,
    val motJson: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        private val gson = Gson()

        fun fromData(vehicle: VehicleData, mot: MotHistoryData?): CachedVehicleEntity =
            CachedVehicleEntity(
                registration = vehicle.registration.replace(" ", "").uppercase(),
                vehicleJson = gson.toJson(vehicle),
                motJson = mot?.let { gson.toJson(it) } ?: ""
            )
    }

    fun toVehicle(): VehicleData = gson.fromJson(vehicleJson, VehicleData::class.java)

    fun toMot(): MotHistoryData? =
        if (motJson.isBlank()) null else gson.fromJson(motJson, MotHistoryData::class.java)
}
