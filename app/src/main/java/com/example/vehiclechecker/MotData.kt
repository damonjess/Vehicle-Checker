package com.example.vehiclechecker

import com.google.gson.annotations.SerializedName

// The root response from the API
data class MotRecord(
    val registration: String,
    val make: String,
    val model: String,
    val motTests: List<MotTest>?
)

// Individual test details
data class MotTest(
    val completedDate: String,
    val testResult: String,
    val odometerValue: String?,
    val odometerUnit: String?,
    val defects: List<MotDefect>?
)

// Specific failures or advisories
data class MotDefect(
    val text: String,
    val type: String, // e.g., "Advisory", "Failure", "PRS"
    val dangerous: Boolean
)
