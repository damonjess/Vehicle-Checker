package com.example.vehiclechecker

data class VehicleData(
    val registration: String = "",
    val make: String = "",
    val colour: String = "",
    val fuelType: String = "",
    val yearOfManufacture: String = "",
    val firstRegistered: String = "",
    val engineSize: String = "",
    val co2Emissions: String = "",
    val vehicleStatus: String = "",
    val wheelplan: String = "",
    val lastV5cIssued: String = "",
    val taxStatus: String = "",
    val taxDueDate: String = "",
    val motStatus: String = "",
    val motExpiryDate: String = "",
    val errorMessage: String? = null
)
