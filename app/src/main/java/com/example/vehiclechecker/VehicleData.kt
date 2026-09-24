package com.example.vehiclechecker

data class VehicleData(
    val make: String = "",
    val colour: String = "",
    val fuelType: String = "",
    val year: String = "",
    val taxStatus: String = "",
    val motStatus: String = "",
    val errorMessage: String? = null
)
