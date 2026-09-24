package com.example.vehiclechecker

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

object MotApiClient {
    private val client = OkHttpClient()
    private val gson = Gson()

    // You will fill these in when your DVSA email arrives
    private const val CLIENT_ID = "YOUR_CLIENT_ID"
    private const val CLIENT_SECRET = "YOUR_CLIENT_SECRET"
    private const val TENANT_ID = "YOUR_TENANT_ID"
    private const val API_KEY = "YOUR_API_KEY"
    private const val SCOPE = "YOUR_PROVIDED_SCOPE_URL"

    suspend fun fetchMotHistory(registration: String): MotRecord? {
        return withContext(Dispatchers.IO) {
            try {
                // 1. Request the OAuth Bearer Token
                val tokenUrl = "https://login.microsoftonline.com/$TENANT_ID/oauth2/v2.0/token"
                val formBody = FormBody.Builder()
                    .add("grant_type", "client_credentials")
                    .add("client_id", CLIENT_ID)
                    .add("client_secret", CLIENT_SECRET)
                    .add("scope", SCOPE)
                    .build()

                val tokenRequest = Request.Builder().url(tokenUrl).post(formBody).build()
                val tokenResponse = client.newCall(tokenRequest).execute()

                if (!tokenResponse.isSuccessful) return@withContext null

                // Parse the temporary access token
                val tokenJson = JSONObject(tokenResponse.body?.string() ?: "")
                val accessToken = tokenJson.getString("access_token")

                // 2. Request the MOT History using the Token and API Key
                val motUrl = "https://history.mot.api.gov.uk/v1/trade/vehicles/registration/$registration"
                val motRequest = Request.Builder()
                    .url(motUrl)
                    .addHeader("Authorization", "Bearer $accessToken")
                    .addHeader("X-API-Key", API_KEY)
                    .get()
                    .build()

                val motResponse = client.newCall(motRequest).execute()

                if (!motResponse.isSuccessful) return@withContext null

                // 3. Map the JSON response to our Kotlin Data Classes
                val responseData = motResponse.body?.string()

                // The API returns a list of vehicles, we just want the first one
                val records = gson.fromJson(responseData, Array<MotRecord>::class.java)
                return@withContext records.firstOrNull()

            } catch (e: Exception) {
                e.printStackTrace()
                return@withContext null
            }
        }
    }
}
