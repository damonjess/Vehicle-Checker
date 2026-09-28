package com.example.vehiclechecker

import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object VehicleAiAnalyst {

    private const val TAG = "VehicleAiAnalyst"
    private const val API_KEY = BuildConfig.GEMINI_API_KEY

    // List of valid Gemini models to try in order (falling back if a model is temporarily unavailable)
    private val MODELS_TO_TRY = listOf(
        "gemini-2.0-flash",
        "gemini-1.5-flash",
        "gemini-1.5-pro",
    )

    suspend fun analyze(vehicle: VehicleData, mot: MotHistoryData?): String {
        return withContext(Dispatchers.IO) {
            val modelNameText = mot?.model ?: ""
            val prompt = """
                You are an expert mechanic giving advice to a friend buying a used car.
                The car is a ${vehicle.yearOfManufacture} ${vehicle.make} $modelNameText 
                with a ${vehicle.engineSize} ${vehicle.fuelType} engine.
                
                Please provide:
                1. The top 3 most common mechanical faults or reliability issues for this specific generation.
                2. Things to specifically listen for or check during a test drive.
                3. A 1-sentence verdict on its overall reliability.
                
                Keep the formatting clean, use bullet points, and be concise. Do not use markdown headers (##), just bold text.
            """.trimIndent()

            var lastException: Exception? = null

            for (modelName in MODELS_TO_TRY) {
                try {
                    val generativeModel = GenerativeModel(
                        modelName = modelName,
                        apiKey = API_KEY,
                    )
                    val response = generativeModel.generateContent(prompt)
                    val text = response.text
                    if (!text.isNullOrBlank()) {
                        return@withContext text
                    }
                } catch (e: Exception) {
                    lastException = e
                    Log.w(TAG, "Model $modelName failed: ${e.message}")
                }
            }

            Log.e(TAG, "All AI model attempts failed", lastException)
            return@withContext "AI Analysis is currently unavailable. Please try again in a few moments."
        }
    }
}
