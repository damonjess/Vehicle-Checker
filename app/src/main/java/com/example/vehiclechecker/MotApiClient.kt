package com.example.vehiclechecker

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

object MotApiClient {
    private val client = OkHttpClient()
    private val gson = Gson()

    @Volatile
    private var cachedAccessToken: String? = null
    @Volatile
    private var tokenExpiryTimeMs: Long = 0L

    private val placeholderValues = setOf(
        "your_client_id", "your_client_secret", "your_tenant_id",
        "your_api_key", "your_provided_scope_url"
    )

    /** Checks if credentials in local.properties / BuildConfig are configured. */
    fun isConfigured(): Boolean {
        val clientId = BuildConfig.MOT_CLIENT_ID.trim()
        val clientSecret = BuildConfig.MOT_CLIENT_SECRET.trim()
        val tenantId = BuildConfig.MOT_TENANT_ID.trim()
        val apiKey = BuildConfig.MOT_API_KEY.trim()
        val scope = BuildConfig.MOT_SCOPE.trim()
        // Case-insensitive placeholder detection: "your_tenant_id" must not count as configured
        // just because it differs in case from the documented placeholder.
        val values = listOf(clientId, clientSecret, tenantId, apiKey, scope)
        if (values.any { it.isBlank() || it.lowercase() in placeholderValues }) return false
        // Client-credential flows require a resource URI with /.default (e.g.
        // https://history.mot.api.gov.uk/.default); anything else fails OAuth with invalid_scope.
        return scope.startsWith("https://")
    }

    @Throws(SecurityException::class, IOException::class)
    private fun getAccessToken(): String? {
        val currentTime = System.currentTimeMillis()
        val existingToken = cachedAccessToken
        if (!existingToken.isNullOrBlank() && currentTime < tokenExpiryTimeMs - 60_000L) {
            return existingToken
        }

        if (!isConfigured()) {
            Log.w("MotApiClient", "DVSA MOT API credentials are not configured in local.properties")
            return null
        }

        return synchronized(this) {
            val now = System.currentTimeMillis()
            val validToken = cachedAccessToken
            if (!validToken.isNullOrBlank() && now < tokenExpiryTimeMs - 60_000L) {
                return@synchronized validToken
            }

            val tenantId = BuildConfig.MOT_TENANT_ID
            val clientId = BuildConfig.MOT_CLIENT_ID
            val clientSecret = BuildConfig.MOT_CLIENT_SECRET
            val scope = BuildConfig.MOT_SCOPE

            val tokenUrl = "https://login.microsoftonline.com/$tenantId/oauth2/v2.0/token"
            val formBody = FormBody.Builder()
                .add("grant_type", "client_credentials")
                .add("client_id", clientId)
                .add("client_secret", clientSecret)
                .add("scope", scope)
                .build()

            val tokenRequest = Request.Builder().url(tokenUrl).post(formBody).build()
            client.newCall(tokenRequest).execute().use { tokenResponse ->
                if (!tokenResponse.isSuccessful) {
                    Log.e("MotApiClient", "OAuth token request failed: code=${tokenResponse.code}")
                    if (tokenResponse.code == 401 || tokenResponse.code == 400 || tokenResponse.code == 403) {
                        throw SecurityException("OAuth token request failed with code ${tokenResponse.code}")
                    }
                    return@synchronized null
                }

                val responseBody = tokenResponse.body?.string() ?: ""
                val tokenJson = JSONObject(responseBody)
                val accessToken = if (tokenJson.has("access_token")) tokenJson.getString("access_token") else null
                if (accessToken.isNullOrBlank()) return@synchronized null
                val expiresIn = tokenJson.optLong("expires_in", 3600L)

                cachedAccessToken = accessToken
                tokenExpiryTimeMs = now + (expiresIn * 1000L)
                Log.d("MotApiClient", "Acquired new DVSA OAuth token, expires in $expiresIn sec")
                accessToken
            }
        }
    }

    suspend fun fetchMotHistory(registration: String): MotHistoryData? {
        return withContext(Dispatchers.IO) {
            val cleanReg = registration.replace(" ", "").uppercase().trim()
            if (cleanReg.isBlank()) return@withContext null

            if (!isConfigured()) {
                return@withContext MotHistoryData(
                    registration = cleanReg,
                    errorMessage = "MOT API credentials not configured"
                )
            }

            try {
                val accessToken = getAccessToken() ?: return@withContext MotHistoryData(
                    registration = cleanReg,
                    errorMessage = "Check MOT API credentials"
                )
                val apiKey = BuildConfig.MOT_API_KEY
                
                val motUrl = "https://history.mot.api.gov.uk/v1/trade/vehicles/registration/$cleanReg"
                val motRequest = Request.Builder()
                    .url(motUrl)
                    .addHeader("Authorization", "Bearer $accessToken")
                    .addHeader("X-API-Key", apiKey)
                    .get()
                    .build()

                client.newCall(motRequest).execute().use { motResponse ->
                    when (motResponse.code) {
                        404 -> {
                            Log.d("MotApiClient", "Vehicle $cleanReg not found in DVSA API")
                            return@withContext MotHistoryData(
                                registration = cleanReg,
                                errorMessage = "No MOT records found for $cleanReg"
                            )
                        }
                        401, 403 -> {
                            Log.e("MotApiClient", "DVSA API auth failed: status=${motResponse.code}")
                            return@withContext MotHistoryData(
                                registration = cleanReg,
                                errorMessage = "Check MOT API credentials"
                            )
                        }
                        429 -> {
                            Log.e("MotApiClient", "DVSA API rate limit exceeded")
                            return@withContext MotHistoryData(
                                registration = cleanReg,
                                errorMessage = "MOT API rate limit exceeded"
                            )
                        }
                        else -> if (!motResponse.isSuccessful) {
                            Log.e("MotApiClient", "MOT request failed: status=${motResponse.code}")
                            return@withContext null
                        }
                    }

                    val responseData = motResponse.body?.string() ?: ""
                    if (responseData.isBlank()) return@withContext null

                    Log.d("MotApiClient", "DVSA MOT API response preview: ${responseData.take(300)}...")

                    val record = parseMotRecordResponse(responseData) ?: return@withContext null
                    return@withContext mapRecordToMotHistoryData(record, cleanReg)
                }
            } catch (_: SecurityException) {
                return@withContext MotHistoryData(registration = cleanReg, errorMessage = "Check MOT API credentials")
            } catch (_: IOException) {
                return@withContext MotHistoryData(registration = cleanReg, errorMessage = "Network error fetching MOT history")
            } catch (e: Exception) {
                Log.e("MotApiClient", "Error fetching MOT history from API: ${e.message}", e)
                return@withContext MotHistoryData(
                    registration = cleanReg,
                    errorMessage = "Error fetching MOT history"
                )
            }
        }
    }

    @VisibleForTesting
    internal fun parseMotRecordResponse(responseData: String): MotRecord? {
        return try {
            val trimmed = responseData.trim()
            if (trimmed.startsWith("[")) {
                val records = gson.fromJson(trimmed, Array<MotRecord>::class.java)
                records.firstOrNull()
            } else {
                gson.fromJson(trimmed, MotRecord::class.java)
            }
        } catch (e: Exception) {
            Log.e("MotApiClient", "Error parsing MotRecord JSON: ${e.message}")
            null
        }
    }

    fun mapRecordToMotHistoryData(record: MotRecord, fallbackReg: String): MotHistoryData {
        val reg = record.registration?.ifBlank { fallbackReg }?.replace(" ", "")?.uppercase() ?: fallbackReg.replace(" ", "").uppercase()

        val rawTests = record.motTests ?: emptyList()
        
        val testsSorted = rawTests.sortedByDescending { it.completedDate.orEmpty().replace('.', '-') }

        if (testsSorted.isEmpty()) {
            return MotHistoryData(
                registration = reg,
                errorMessage = "No MOT tests on record yet for $reg"
            )
        }

        val mappedTests = testsSorted.map { test ->
            val normalisedResult = when {
                test.testResult.equals("PASSED", ignoreCase = true) -> "PASS"
                else -> "FAIL"
            }

            val dateTestedFormatted = niceDate(test.completedDate)
            val expiryDateFormatted = niceDate(test.expiryDate)

            val m = miles(test)

            val mileageText = if (m != null) {
                String.format(Locale.UK, "%,d miles", m)
            } else ""

            val advisories = test.defects
                ?.filter { it.type.equals("ADVISORY", ignoreCase = true) || it.type.equals("MINOR", ignoreCase = true) }
                ?.mapNotNull { it.text?.trim()?.takeIf(String::isNotEmpty) }
                ?: emptyList()

            val failures = test.defects
                ?.filter { !it.type.equals("ADVISORY", ignoreCase = true) && !it.type.equals("MINOR", ignoreCase = true) }
                ?.mapNotNull { defect ->
                    val text = defect.text?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
                    if (defect.dangerous) "[DANGEROUS] $text" else text
                }
                ?: emptyList()

            safeLogD("MotApiClient", "Parsed test on $dateTestedFormatted: Result=$normalisedResult, Failures=${failures.size}, Advisories=${advisories.size}")

            MotTestRecord(
                dateTested = dateTestedFormatted,
                result = normalisedResult,
                mileage = mileageText,
                mileageMiles = m,
                testNumber = test.motTestNumber ?: "",
                expiryDate = expiryDateFormatted,
                advisories = advisories,
                failures = failures
            )
        }

        val withDiffs = mappedTests.mapIndexed { index, test ->
            val prev = mappedTests.getOrNull(index + 1)
            val diff = if (test.mileageMiles != null && prev?.mileageMiles != null) {
                test.mileageMiles - prev.mileageMiles
            } else null
            test.copy(mileageDifference = diff)
        }

        val motValidUntil = niceDate(testsSorted.firstOrNull { it.testResult.equals("PASSED", ignoreCase = true) }?.expiryDate)

        val dateRegisteredFormatted = niceDate(record.firstUsedDate ?: "")
        
        val recallStatus = when (record.hasOutstandingRecall?.trim()?.lowercase()) {
            "yes", "true" -> RecallStatus.OUTSTANDING
            "no", "false" -> RecallStatus.NONE
            else -> RecallStatus.UNKNOWN
        }

        return MotHistoryData(
            registration = reg,
            make = record.make ?: "",
            model = record.model ?: "",
            colour = record.primaryColour ?: "",
            fuelType = record.fuelType ?: "",
            dateRegistered = dateRegisteredFormatted,
            motValidUntil = motValidUntil,
            tests = withDiffs,
            recallStatus = recallStatus,
        )
    }
    
    private fun miles(t: MotTest): Int? {
        if (t.odometerResultType.equals("NO_ODOMETER", true) ||
            t.odometerResultType.equals("UNREADABLE", true)) return null
        val v = t.odometerValue?.replace(",", "")?.trim()?.toDoubleOrNull() ?: return null
        return (if (t.odometerUnit.equals("km", true)) v * 0.621371 else v).toInt()
    }

    private fun niceDate(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val out = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK)
        val ymd = raw.trim().take(10).replace('.', '-').replace('/', '-')
        return try { LocalDate.parse(ymd).format(out) } catch (e: Exception) { raw.trim() }
    }

    private fun safeLogD(tag: String, msg: String) {
        try {
            Log.d(tag, msg)
        } catch (_: Throwable) {
            // Ignored during local JVM unit tests
        }
    }
}
