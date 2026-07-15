package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.api

import android.content.Context
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.BridgeDiscoveryResponse
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.BridgeSchedule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.BridgeScheduleCreate
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.GroupUpdate
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueBridgeConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueGroup
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueLight
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.LightStateUpdate
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.network.HueTrustManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.util.HueConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager

/**
 * HTTP client for Hue API communication.
 *
 * TLS TRUST MODEL
 * Uses [HueTrustManager], a permissive local-IoT trust model (system trust store first,
 * Hue certificate-pattern fallback second, optional bridge-ID pinning as an additive,
 * non-blocking audit layer). See [HueTrustManager] for the full rationale - this is
 * NOT full PKI validation, and is NOT a "trust all certificates" bypass either.
 *
 * @param context Optional application context. When provided, enables bridge-ID
 * pinning (Trust-On-First-Use) as an additional audit layer on top of the existing
 * hybrid certificate validation. Pass null only if a context is genuinely unavailable;
 * the client still works correctly without it, just without the pinning audit layer.
 */
class HueApiClient(context: Context? = null) {

    companion object {
        private const val DISCOVERY_URL = "https://discovery.meethue.com"
        private const val TIMEOUT_SECONDS = 10L
    }

    private val gson = Gson()

    // Application context captured for the (lazy) TLS trust layer.
    private val appContext = context?.applicationContext

    /**
     * PERF (startup jank): building the TLS stack — SSLContext.getInstance("TLS").init(...) plus the
     * OkHttp client — costs ~250-330ms cold. It used to run in the constructor, which executes INSIDE
     * HueBridgeConnectionManager.getInstance()'s `synchronized` block. At every cold start the main
     * thread (MainActivity's eager `@Inject bridgeConnectionManager`) parked on that lock for the full
     * build (~270ms measured, reproducible on emulator). Every real caller is suspend/Dispatchers.IO,
     * so the client is built lazily on first network use — off the main thread, out of the startup path.
     */
    private val client: OkHttpClient by lazy {
        val trustManager = HueTrustManager.create(appContext)
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), null)
        }
        val built = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier(HueTrustManager.createHostnameVerifier())
            .build()
        Logger.i(
            LogTags.HUE_NETWORK,
            "🔒 HueApiClient TLS stack built lazily (hybrid trust model, bridge-ID pinning=${appContext != null})"
        )
        built
    }

    /**
     * CORRECT MODERN SOLUTION: Philips Hue Bridge API with Signify Certificate Authority
     * 
     * OFFICIAL PHILIPS/SIGNIFY APPROACH (2025):
     * ✅ HTTPS-Only (no HTTP fallback for modern bridges)
     * ✅ Certificate Pinning with Signify CA
     * ✅ Hostname Verification with Bridge ID as Common Name
     * ✅ Automatic Bridge ID discovery and validation
     * 
     * SECURITY: Follows official Philips Hue developer guidelines
     * 
     * @param bridgeIp Bridge IP address
     * @param endpoint API endpoint (e.g., "/api/config")
     * @param method HTTP method (GET, POST, PUT, DELETE)
     * @param body Request body for POST/PUT requests
     * @return Result<String> containing response body or error
     */
    private suspend fun makeSecureHueRequest(
        bridgeIp: String,
        endpoint: String,
        method: String,
        body: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        
        // Validate private network address first
        if (!isPrivateNetworkAddress(bridgeIp)) {
            Logger.w(LogTags.HUE_NETWORK, "🚨 SECURITY: Bridge IP $bridgeIp is not a private network address")
            return@withContext Result.failure(
                SecurityException("Bridge IP must be in private network range")
            )
        }

        // Modern Philips Hue approach: HTTPS with certificate validation
        Logger.d(LogTags.HUE_NETWORK, "🔒 Making secure HTTPS request to Hue Bridge $bridgeIp")
        
        try {
            val url = "https://$bridgeIp$endpoint"
            Logger.d(LogTags.HUE_NETWORK, "Making $method request to $url")
            
            val requestBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", "CFAlarm/2.0 (Android)")
            
            when (method.uppercase()) {
                "GET" -> requestBuilder.get()
                "POST" -> {
                    val requestBody = body?.toRequestBody("application/json".toMediaType())
                        ?: "".toRequestBody("application/json".toMediaType())
                    requestBuilder.post(requestBody)
                }
                "PUT" -> {
                    val requestBody = body?.toRequestBody("application/json".toMediaType())
                        ?: "".toRequestBody("application/json".toMediaType())
                    requestBuilder.put(requestBody)
                }
                "DELETE" -> requestBuilder.delete()
            }
            
            val response = client.newCall(requestBuilder.build()).execute()
            
            if (response.isSuccessful) {
                val responseBody = response.body.string()
                Logger.i(LogTags.HUE_NETWORK, "✅ Secure HTTPS request successful: ${response.code}")
                Result.success(responseBody)
            } else {
                val error = "HTTPS ${response.code}: ${response.message}"
                Logger.w(LogTags.HUE_NETWORK, "HTTPS request failed: $error")
                Result.failure(IOException(error))
            }
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_NETWORK, "Secure HTTPS request to $bridgeIp failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Validates if IP address is in private network range (RFC 1918)
     * SECURITY: Only allows Hue communication with local network devices
     */
    private fun isPrivateNetworkAddress(ipAddress: String): Boolean {
        return try {
            when {
                ipAddress.startsWith("192.168.") -> true
                ipAddress.startsWith("10.") -> true
                ipAddress.startsWith("172.") -> {
                    val secondOctet = ipAddress.split(".").getOrNull(1)?.toIntOrNull() ?: 0
                    secondOctet in 16..31
                }
                ipAddress.startsWith("169.254.") -> true // Link-local
                ipAddress == "127.0.0.1" || ipAddress == "localhost" -> true
                else -> false
            }
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_NETWORK, "Error validating private network address: $ipAddress", e)
            false
        }
    }

    /**
     * Discover bridges using Philips online service
     */
    suspend fun discoverBridgesOnline(): List<BridgeDiscoveryResponse> =
        withContext(Dispatchers.IO) {
            try {
                Logger.d(LogTags.HUE_DISCOVERY, "Attempting online bridge discovery")

                val request = Request.Builder()
                    .url("$DISCOVERY_URL/api/nupnp")
                    .get()
                    .build()

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val responseBody = response.body.string().ifBlank { "[]" }
                    val type = object : TypeToken<List<BridgeDiscoveryResponse>>() {}.type
                    val bridges = gson.fromJson<List<BridgeDiscoveryResponse>>(responseBody, type)

                    Logger.i(
                        LogTags.HUE_DISCOVERY,
                        "Online discovery successful: ${bridges.size} bridges"
                    )
                    return@withContext bridges
                } else {
                    throw IOException("Discovery service unavailable: ${response.code}")
                }
            } catch (e: Exception) {
                Logger.e(LogTags.HUE_DISCOVERY, "Online discovery failed", e)
                throw e
            }
        }

    /**
     * Get bridge configuration with modern HTTPS approach
     */
    suspend fun getBridgeConfig(bridgeIp: String, username: String? = null): HueBridgeConfig =
        withContext(Dispatchers.IO) {
            val endpoint = if (username != null) {
                "/api/$username/config"
            } else {
                "/api/config"
            }

            val result = makeSecureHueRequest(bridgeIp, endpoint, "GET")
            
            if (result.isSuccess) {
                val responseBody = result.getOrNull() ?: "{}"
                return@withContext gson.fromJson(responseBody, HueBridgeConfig::class.java)
            } else {
                throw result.exceptionOrNull() ?: IOException("Failed to get bridge config")
            }
        }

    /**
     * Create user on bridge (requires link button press) with HTTPS-First approach
     */
    suspend fun createUser(bridgeIp: String, appName: String): String =
        withContext(Dispatchers.IO) {
            val requestBody = mapOf("devicetype" to appName)
            val json = gson.toJson(requestBody)

            val result = makeSecureHueRequest(bridgeIp, "/api", "POST", json)
            
            if (result.isSuccess) {
                val responseBody = result.getOrNull() ?: "[]"
                val type = object : TypeToken<List<Map<String, Any>>>() {}.type
                val responseList = gson.fromJson<List<Map<String, Any>>>(responseBody, type)

                responseList.firstOrNull()?.let { firstResponse ->
                    when {
                        firstResponse.containsKey("success") -> {
                            // TYPE SAFE: Eliminiert unchecked cast warning
                            val successMap = firstResponse["success"]
                            if (successMap is Map<*, *>) {
                                val username = successMap["username"] as? String
                                return@withContext username
                                    ?: throw IOException("Username not found in response")
                            } else {
                                throw IOException("Invalid success response format")
                            }
                        }

                        firstResponse.containsKey("error") -> {
                            // TYPE SAFE: Eliminiert unchecked cast warning
                            val errorMap = firstResponse["error"]
                            if (errorMap is Map<*, *>) {
                                val errorType = errorMap["type"] as? Double
                                if (errorType == 101.0) {
                                    throw IOException("Link button not pressed. Please press the link button on your Hue bridge and try again.")
                                } else {
                                    throw IOException("Bridge error: ${errorMap["description"]}")
                                }
                            } else {
                                throw IOException("Invalid error response format")
                            }
                        }
                    }
                }
            } else {
                throw result.exceptionOrNull() ?: IOException("Failed to create user")
            }

            throw IOException("Failed to create user")
        }

    /**
     * Get all lights from bridge with HTTPS-First approach
     */
    suspend fun getLights(bridgeIp: String, username: String): Map<String, HueLight> =
        withContext(Dispatchers.IO) {
            val result = makeSecureHueRequest(bridgeIp, "/api/$username/lights", "GET")
            
            if (result.isSuccess) {
                val responseBody = result.getOrNull() ?: "{}"
                Logger.d(LogTags.HUE_LIGHTS, "Lights API response: $responseBody")

                return@withContext try {
                    val type = object : TypeToken<Map<String, HueLight>>() {}.type
                    gson.fromJson(responseBody, type) ?: emptyMap()
                } catch (e: Exception) {
                    Logger.e(
                        LogTags.HUE_LIGHTS,
                        "Failed to parse lights response: $responseBody",
                        e
                    )
                    emptyMap()
                }
            } else {
                throw result.exceptionOrNull() ?: IOException("Failed to get lights")
            }
        }

    /**
     * Get all groups from bridge with HTTPS-First approach
     */
    suspend fun getGroups(bridgeIp: String, username: String): Map<String, HueGroup> =
        withContext(Dispatchers.IO) {
            val result = makeSecureHueRequest(bridgeIp, "/api/$username/groups", "GET")
            
            if (result.isSuccess) {
                val responseBody = result.getOrNull() ?: "{}"
                Logger.d(LogTags.HUE_LIGHTS, "Groups API response: $responseBody")

                return@withContext try {
                    val type = object : TypeToken<Map<String, HueGroup>>() {}.type
                    gson.fromJson(responseBody, type) ?: emptyMap()
                } catch (e: Exception) {
                    Logger.e(
                        LogTags.HUE_LIGHTS,
                        "Failed to parse groups response: $responseBody",
                        e
                    )
                    emptyMap()
                }
            } else {
                throw result.exceptionOrNull() ?: IOException("Failed to get groups")
            }
        }

    /**
     * Control a light with HTTPS-First approach
     */
    suspend fun controlLight(
        bridgeIp: String,
        username: String,
        lightId: String,
        update: LightStateUpdate
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = gson.toJson(update)
            val result = makeSecureHueRequest(bridgeIp, "/api/$username/lights/$lightId/state", "PUT", json)
            result.isSuccess
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_LIGHTS, "Error controlling light $lightId", e)
            false
        }
    }

    /**
     * Control a group with HTTPS-First approach
     */
    suspend fun controlGroup(
        bridgeIp: String,
        username: String,
        groupId: String,
        update: GroupUpdate
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = gson.toJson(update)
            val result = makeSecureHueRequest(bridgeIp, "/api/$username/groups/$groupId/action", "PUT", json)
            result.isSuccess
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_LIGHTS, "Error controlling group $groupId", e)
            false
        }
    }

    /**
     * Get specific light from bridge with HTTPS-First approach
     */
    suspend fun getLight(bridgeIp: String, username: String, lightId: String): HueLight =
        withContext(Dispatchers.IO) {
            val result = makeSecureHueRequest(bridgeIp, "/api/$username/lights/$lightId", "GET")
            
            if (result.isSuccess) {
                val responseBody = result.getOrNull() ?: "{}"
                Logger.d(LogTags.HUE_LIGHTS, "Light $lightId API response: $responseBody")

                return@withContext try {
                    gson.fromJson(responseBody, HueLight::class.java)
                        ?: throw IOException("Failed to parse light response")
                } catch (e: Exception) {
                    Logger.e(
                        LogTags.HUE_LIGHTS,
                        "Failed to parse light $lightId response: $responseBody",
                        e
                    )
                    throw IOException("Failed to parse light $lightId: ${e.message}", e)
                }
            } else {
                throw result.exceptionOrNull() ?: IOException("Failed to get light $lightId")
            }
        }

    /**
     * Get specific group from bridge with HTTPS-First approach
     */
    suspend fun getGroup(bridgeIp: String, username: String, groupId: String): HueGroup =
        withContext(Dispatchers.IO) {
            val result = makeSecureHueRequest(bridgeIp, "/api/$username/groups/$groupId", "GET")
            
            if (result.isSuccess) {
                val responseBody = result.getOrNull() ?: "{}"
                Logger.d(LogTags.HUE_LIGHTS, "Group $groupId API response: $responseBody")

                return@withContext try {
                    gson.fromJson(responseBody, HueGroup::class.java)
                        ?: throw IOException("Failed to parse group response")
                } catch (e: Exception) {
                    Logger.e(
                        LogTags.HUE_LIGHTS,
                        "Failed to parse group $groupId response: $responseBody",
                        e
                    )
                    throw IOException("Failed to parse group $groupId: ${e.message}", e)
                }
            } else {
                throw result.exceptionOrNull() ?: IOException("Failed to get group $groupId")
            }
        }

    /**
     * Set light state using raw Map (for Repository compatibility) with HTTPS-First approach
     */
    suspend fun setLightState(
        bridgeIp: String,
        username: String,
        lightId: String,
        stateChange: Map<String, Any>
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = gson.toJson(stateChange)
            val result = makeSecureHueRequest(bridgeIp, "/api/$username/lights/$lightId/state", "PUT", json)
            
            Logger.d(LogTags.HUE_LIGHTS, "Light state update result: ${result.isSuccess}")
            return@withContext result.isSuccess
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_LIGHTS, "Error setting light state for $lightId", e)
            return@withContext false
        }
    }

    /**
     * Set group action using raw Map (for Repository compatibility) with HTTPS-First approach
     */
    suspend fun setGroupAction(
        bridgeIp: String,
        username: String,
        groupId: String,
        actionChange: Map<String, Any>
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = gson.toJson(actionChange)
            val result = makeSecureHueRequest(bridgeIp, "/api/$username/groups/$groupId/action", "PUT", json)

            Logger.d(LogTags.HUE_LIGHTS, "Group action update result: ${result.isSuccess}")
            return@withContext result.isSuccess
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_LIGHTS, "Error setting group action for $groupId", e)
            return@withContext false
        }
    }

    // =========================================================================
    // BRIDGE-SEITIGE ZEITPLÄNE (/schedules)
    // =========================================================================

    /**
     * Wertet die Antwort-Hülle der V1-API aus.
     *
     * ACHTUNG, die zentrale Falle dieser API: Sie antwortet **auch bei Ablehnung mit HTTP 200**.
     * Das Urteil steht ausschliesslich im Body — `[{"success":{…}}]` oder
     * `[{"error":{"type":7,"description":"…"}}]`. [makeSecureHueRequest] kennt nur den
     * HTTP-Status; wer sich darauf verlaesst, haelt einen abgelehnten Zeitplan fuer angelegt und
     * schaltet das Licht nie wieder aus. [createUser] parst aus demselben Grund den Body.
     *
     * @return den Wert unter "success" oder ein Failure mit der Fehlerbeschreibung der Bridge.
     */
    private fun parseV1Envelope(responseBody: String): Result<Map<*, *>> {
        return try {
            val type = object : TypeToken<List<Map<String, Any>>>() {}.type
            val entries: List<Map<String, Any>> = gson.fromJson(responseBody, type)
                ?: return Result.failure(IOException("Bridge returned an unparseable response"))

            val first = entries.firstOrNull()
                ?: return Result.failure(IOException("Bridge returned an empty response"))

            (first["error"] as? Map<*, *>)?.let { error ->
                val description = error["description"] ?: "unknown error"
                val errorType = (error["type"] as? Double)?.toInt()
                return Result.failure(IOException("Bridge rejected the request (type $errorType): $description"))
            }

            (first["success"] as? Map<*, *>)?.let { return Result.success(it) }

            Result.failure(IOException("Bridge response contained neither success nor error: $responseBody"))
        } catch (e: Exception) {
            Result.failure(IOException("Failed to parse bridge response: $responseBody", e))
        }
    }

    /**
     * Legt einen Zeitplan auf der Bridge an.
     *
     * @return die von der Bridge vergebene Zeitplan-ID.
     */
    suspend fun createSchedule(
        bridgeIp: String,
        username: String,
        schedule: BridgeScheduleCreate
    ): Result<String> = withContext(Dispatchers.IO) {
        val json = gson.toJson(schedule)
        val result = makeSecureHueRequest(
            bridgeIp,
            "/api/$username${HueConstants.Bridge.SCHEDULES_ENDPOINT}",
            "POST",
            json
        )
        val responseBody = result.getOrElse { return@withContext Result.failure(it) }

        parseV1Envelope(responseBody).mapCatching { success ->
            success["id"] as? String
                ?: throw IOException("Bridge accepted the schedule but returned no id: $responseBody")
        }
    }

    /** Alle Zeitpläne der Bridge, nach Zeitplan-ID. */
    suspend fun getSchedules(
        bridgeIp: String,
        username: String
    ): Result<Map<String, BridgeSchedule>> = withContext(Dispatchers.IO) {
        val result = makeSecureHueRequest(
            bridgeIp,
            "/api/$username${HueConstants.Bridge.SCHEDULES_ENDPOINT}",
            "GET"
        )
        val responseBody = result.getOrElse { return@withContext Result.failure(it) }

        try {
            val type = object : TypeToken<Map<String, BridgeSchedule>>() {}.type
            Result.success(gson.fromJson(responseBody, type) ?: emptyMap())
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_BRIDGE, "Failed to parse schedules response: $responseBody", e)
            Result.failure(IOException("Failed to parse schedules: ${e.message}", e))
        }
    }

    /** Löscht einen Zeitplan auf der Bridge. */
    suspend fun deleteSchedule(
        bridgeIp: String,
        username: String,
        scheduleId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val result = makeSecureHueRequest(
            bridgeIp,
            "/api/$username${HueConstants.Bridge.SCHEDULES_ENDPOINT}/$scheduleId",
            "DELETE"
        )
        val responseBody = result.getOrElse { return@withContext Result.failure(it) }
        parseV1Envelope(responseBody).map { }
    }
}
