package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.discovery

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueBridge
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Official N-UPnP (Network UPnP) Hue Bridge Discovery Service
 * Uses the official Philips discovery endpoint as documented at:
 * https://developers.meethue.com/develop/get-started-2/
 */
class HueNUpnpDiscoveryService {
    
    companion object {
        private const val OFFICIAL_DISCOVERY_ENDPOINT = "https://discovery.meethue.com"
        private const val CONNECTION_TIMEOUT_SECONDS = 10L
        private const val READ_TIMEOUT_SECONDS = 15L
    }
    
    @Serializable
    data class DiscoveryResponse(
        val id: String,
        val internalipaddress: String,
        val port: Int? = null
    )
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    
    /**
     * Discovers Hue bridges using the official Philips N-UPnP discovery service
     * This method requires bridges to be registered with meethue.com
     */
    suspend fun discoverBridges(): Result<List<HueBridge>> = withContext(Dispatchers.IO) {
        Logger.d(LogTags.HUE_DISCOVERY, "Starting N-UPnP discovery via $OFFICIAL_DISCOVERY_ENDPOINT")
        
        try {
            val request = Request.Builder()
                .url(OFFICIAL_DISCOVERY_ENDPOINT)
                .addHeader("User-Agent", "CFAlarmForTimeOffice/1.0")
                .get()
                .build()
            
            val response = httpClient.newCall(request).execute()
            
            if (!response.isSuccessful) {
                val errorMessage = when (response.code) {
                    404 -> "Discovery service unavailable (404). Bridge may not be registered with meethue.com"
                    503 -> "Discovery service temporarily unavailable (503)"
                    else -> "Discovery service error: ${response.code}"
                }
                Logger.w(LogTags.HUE_DISCOVERY, errorMessage)
                throw IOException(errorMessage)
            }
            
            val responseBody = response.body?.string()
            if (responseBody.isNullOrBlank()) {
                Logger.w(LogTags.HUE_DISCOVERY, "Empty response from discovery service")
                return@withContext Result.success(emptyList())
            }
            
            Logger.d(LogTags.HUE_DISCOVERY, "Received discovery response: $responseBody")
            
            val discoveryResponses = json.decodeFromString<List<DiscoveryResponse>>(responseBody)
            
            val bridges = discoveryResponses.map { response ->
                HueBridge(
                    id = response.id,
                    ipAddress = response.internalipaddress,
                    name = "Philips Hue Bridge",
                    discoveryMethod = com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.DiscoveryMethod.N_UPNP
                )
            }
            
            Logger.i(LogTags.HUE_DISCOVERY, "N-UPnP discovery completed: ${bridges.size} bridges found")
            Result.success(bridges)
            
        } catch (e: SocketTimeoutException) {
            Logger.w(LogTags.HUE_DISCOVERY, "N-UPnP discovery timeout", e)
            Result.failure(IOException("Discovery service timeout. Check your internet connection."))
        } catch (e: IOException) {
            Logger.w(LogTags.HUE_DISCOVERY, "N-UPnP discovery failed", e)
            Result.failure(e)
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_DISCOVERY, "N-UPnP discovery unexpected error", e)
            Result.failure(IOException("Discovery failed: ${e.message}"))
        }
    }
}
