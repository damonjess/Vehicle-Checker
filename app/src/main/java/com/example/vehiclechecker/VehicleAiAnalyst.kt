package com.example.vehiclechecker

import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

object VehicleAiAnalyst {

    private const val TAG = "VehicleAiAnalyst"
    private const val API_KEY = BuildConfig.GEMINI_API_KEY

    // Flash models optimized for free tier and fast execution
    private val MODELS_TO_TRY = listOf(
        "gemini-2.5-flash",
        "gemini-2.0-flash",
        "gemini-1.5-flash",
        "gemini-1.5-pro",
    )

    // Backoff delays in milliseconds for transient server demand spikes
    private val BACKOFF_DELAYS_MS = listOf(2000L, 5000L, 10000L)

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
                val generativeModel = GenerativeModel(
                    modelName = modelName,
                    apiKey = API_KEY,
                )

                val maxAttempts = BACKOFF_DELAYS_MS.size + 1
                for (attempt in 0 until maxAttempts) {
                    try {
                        val response = generativeModel.generateContent(prompt)
                        val text = response.text
                        if (!text.isNullOrBlank()) {
                            return@withContext text
                        }
                    } catch (e: Exception) {
                        lastException = e
                        val errorMessage = e.message ?: ""
                        val isTransient = errorMessage.contains("503") ||
                                errorMessage.contains("high demand") ||
                                errorMessage.contains("UNAVAILABLE") ||
                                errorMessage.contains("429") ||
                                errorMessage.contains("RESOURCE_EXHAUSTED")

                        if (isTransient && (attempt < BACKOFF_DELAYS_MS.size)) {
                            val backoff = BACKOFF_DELAYS_MS[attempt]
                            Log.w(
                                TAG,
                                "Model $modelName experienced transient error (attempt ${attempt + 1}/$maxAttempts). Retrying in ${backoff}ms...",
                            )
                            delay(backoff)
                        } else {
                            Log.w(
                                TAG,
                                "Model $modelName failed on attempt ${attempt + 1}: $errorMessage",
                            )
                            break // Move to next model if non-transient error or max retries reached
                        }
                    }
                }
            }

            Log.e(TAG, "All AI model attempts failed", lastException)
            return@withContext "AI Analysis is currently unavailable due to high server demand. Please try again in a few moments."
        }
    }
}
