package com.example.vehiclechecker

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.google.ai.client.generativeai.GenerativeModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

object VehicleAiAnalyst {

    private const val TAG = "VehicleAiAnalyst"
    private const val API_KEY = BuildConfig.GEMINI_API_KEY

    // Flash models optimized for free tier and fast execution
    private val MODELS_TO_TRY = listOf(
        "gemini-3.5-flash",
        "gemini-3.8-flash",
        "gemini-flash-latest",
        "gemini-3.1-flash-lite",
        "gemini-flash-lite-latest",
    )

    // Backoff delays in milliseconds for transient server demand spikes
    private val BACKOFF_DELAYS_MS = listOf(2000L, 5000L, 10000L)

    /**
     * Runs the mechanic analysis, streaming the answer back through [onPartial] as tokens
     * arrive (each call passes the response built so far, for the caller to render on the main
     * thread). The return value is always the complete answer.
     */
    suspend fun analyze(
        vehicle: VehicleData,
        mot: MotHistoryData?,
        onPartial: (String) -> Unit = {},
    ): String {
        return withContext(Dispatchers.IO) {
            val prompt = buildPrompt(vehicle, mot)
            safeLogD(TAG, "=== PROMPT START ===\n$prompt\n=== PROMPT END ===")

            var lastException: Exception? = null

            for (modelName in MODELS_TO_TRY) {
                val generativeModel = GenerativeModel(
                    modelName = modelName,
                    apiKey = API_KEY,
                )

                val maxAttempts = BACKOFF_DELAYS_MS.size + 1
                for (attempt in 0 until maxAttempts) {
                    try {
                        val streamed = StringBuilder()
                        generativeModel.generateContentStream(prompt).collect { chunk ->
                            val piece = chunk.text
                            if (!piece.isNullOrBlank()) {
                                streamed.append(piece)
                                if (streamed.isNotEmpty()) onPartial(streamed.toString())
                            }
                        }
                        if (streamed.isNotBlank()) {
                            return@withContext streamed.toString()
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

    @VisibleForTesting
    internal fun buildPrompt(vehicle: VehicleData, mot: MotHistoryData?): String {
        val modelNameText = mot?.model ?: ""
        val motHistorySection = if (mot != null && mot.tests.isNotEmpty()) {
            val recentTests = mot.tests.take(3)
            val testDetails = recentTests.mapIndexed { index, test ->
                val lines = mutableListOf<String>()
                val statusText = if (test.isPass) "PASS" else "FAIL"
                val mileageStr = if (test.mileage.isNotBlank()) " | Mileage: ${test.mileage}" else ""
                lines.add("Test #${index + 1}: Date ${test.dateTested} | Result: $statusText$mileageStr")

                val failuresList = if (test.failures.isNotEmpty()) {
                    test.failures
                } else if (!test.isPass && test.advisories.isNotEmpty()) {
                    test.advisories
                } else emptyList()

                val advisoriesList = if (test.failures.isNotEmpty()) {
                    test.advisories
                } else if (test.isPass) {
                    test.advisories
                } else emptyList()

                if (failuresList.isNotEmpty()) {
                    lines.add("  - Failures/Defects:")
                    failuresList.forEach { lines.add("    * $it") }
                }
                if (advisoriesList.isNotEmpty()) {
                    lines.add("  - Advisories:")
                    advisoriesList.forEach { lines.add("    * $it") }
                }
                if (failuresList.isEmpty() && advisoriesList.isEmpty()) {
                    lines.add("  - Clean test (no advisories or failures recorded)")
                }
                lines.joinToString("\n")
            }.joinToString("\n\n")

            val anomalyWarnings = mot.mileageAnomalies
            val anomalyText = if (anomalyWarnings.isNotEmpty()) {
                "\n\nMileage / Odometer Flags:\n" + anomalyWarnings.joinToString("\n") { "• ${it.title}: ${it.detail}" }
            } else ""

            val recallText = if (mot.recallStatus == RecallStatus.OUTSTANDING) {
                "\n\nRecall Alert: Outstanding safety recall recorded (${mot.recallDetail})"
            } else ""

            """
            Actual MOT Test History for this vehicle (Last ${recentTests.size} tests):
            $testDetails$anomalyText$recallText
            """.trimIndent()
        } else {
            "No specific MOT test history recorded for this vehicle."
        }

        return """
            You are an expert mechanic giving advice to a friend buying a used car.
            The car is a ${vehicle.yearOfManufacture} ${vehicle.make} $modelNameText 
            with a ${vehicle.engineSize} ${vehicle.fuelType} engine.
            
            $motHistorySection
            
            CRITICAL INSTRUCTIONS:
            - You are provided with the vehicle's actual MOT defect history above.
            - You MUST explicitly cite the specific failures, dangerous defects, and recurring advisories from these MOT records in your advice.
            - If there are recurring advisories (such as brake discs, suspension bushes, rust/corrosion, or oil leaks), warn the buyer specifically about them and how they relate to this vehicle's condition.
            - Do NOT give generic model advice alone. Direct reference to the actual MOT test records provided above is mandatory.
            
            Please structure your response clearly:
            1. Key insights or red flags from this vehicle's actual MOT history (highlighting specific failures, dangerous defects, or recurring advisories).
            2. The top 3 most common mechanical faults or reliability issues for this specific model/generation.
            3. Specific items to check or listen for during a test drive (incorporating any specific warnings flagged in its MOT history).
            4. A 1-sentence verdict on its overall reliability and buying advice.
            
            Keep the formatting clean, use bullet points, and be concise. Do not use markdown headers (##), just bold text.
        """.trimIndent()
    }

    private fun safeLogD(tag: String, msg: String) {
        try {
            Log.d(tag, msg)
        } catch (_: Throwable) {
            // Ignored during local JVM unit tests
        }
    }
}
