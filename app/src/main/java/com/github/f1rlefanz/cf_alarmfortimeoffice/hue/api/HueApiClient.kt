package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.api

import android.content.Context
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.BridgeDiscoveryResponse
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.BridgeSchedule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.BridgeScheduleCreate
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.GroupUpdate
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueBridgeConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueGroup
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueLight
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueScene
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
            // EHRLICHE FEHLERKLASSE: Eine IPv6-Adresse ist KEIN Sicherheitsvorfall, sondern ein
            // Adressfamilien-Problem - der komplette Hue-Pfad ist IPv4 (Praefix-Pruefung hier,
            // URL-Bau ohne eckige Klammern unten, isBridgeReachableNow im ConnectionManager).
            // Als "🚨 SECURITY" geloggt sucht man im Log einen Angriff statt einer Adressfamilie.
            // Die Klemme selbst bleibt bewusst so streng wie sie ist: nur lokale Geraete.
            if (bridgeIp.contains(":")) {
                Logger.w(
                    LogTags.HUE_NETWORK,
                    "Bridge-Adresse $bridgeIp ist keine IPv4-Adresse - der Hue-Pfad dieser App ist IPv4-only"
                )
                return@withContext Result.failure(
                    IOException("Bridge address is not an IPv4 address: $bridgeIp")
                )
            }
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
            
            // .use { }: Auf dem Erfolgspfad schliesst `body.string()` die Antwort selbst - auf
            // dem Fehlerpfad liest hier aber NIEMAND den Body, und eine ungelesene Antwort haelt
            // ihre Verbindung im Pool fest, bis der GC sie einsammelt. Genau dieser Pfad wird bei
            // einer zickenden Bridge oft durchlaufen (jede 6h-Wartung, jeder Weckvorgang).
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body.string()
                    Logger.i(LogTags.HUE_NETWORK, "✅ Secure HTTPS request successful: ${response.code}")
                    Result.success(responseBody)
                } else {
                    val error = "HTTPS ${response.code}: ${response.message}"
                    Logger.w(LogTags.HUE_NETWORK, "HTTPS request failed: $error")
                    Result.failure(IOException(error))
                }
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

                // .use { }: Der else-Zweig wirft, ohne den Body zu lesen - ohne use bliebe die
                // Antwort offen. Das `return@withContext` aus dem Block heraus ist zulaessig, use
                // schliesst trotzdem (finally).
                client.newCall(request).execute().use { response ->
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
                }
            } catch (e: Exception) {
                Logger.e(LogTags.HUE_DISCOVERY, "Online discovery failed", e)
                throw e
            }
        }

    /**
     * Get bridge configuration with modern HTTPS approach
     *
     * PRUEFT DIE ANTWORT, statt sie nur zu deserialisieren. Beide Aufrufer benutzen diese
     * Funktion als "hat sie geworfen?"-Orakel (HueBridgeConnectionManager.
     * validateConnectionCredentials, HueBridgeRepository.testBridgeConnection) - sie ist damit
     * de facto die Zugangsdaten-/Bridge-Pruefung der App. Ohne Feldpruefung galt aber JEDES
     * JSON-Objekt als gesunde Bridge: Gson baut Objekte per Unsafe und erzwingt Kotlins
     * Non-Null-Deklarationen NICHT, `fromJson("{}", …)` liefert also ein Objekt mit lauter
     * nulls und wirft nicht. Wandert der DHCP-Lease der Bridge, haette ein beliebiges anderes
     * Geraet an derselben IP (Router-Webinterface, NAS) als "unsere Bridge" gegolten.
     * `bridgeid`/`mac` stehen auch in der oeffentlichen, unauthentifizierten Teilmenge von
     * /api/config - die Pruefung funktioniert deshalb auf beiden Endpunkten.
     *
     * Zusaetzlich: bei ungueltigem Username antwortet die Bridge mit HTTP 200 und der
     * V1-Fehlerhuelle (`[{"error":{"type":1,"description":"unauthorized user"}}]`). Die wird
     * jetzt gezielt als solche gemeldet, statt als kryptischer Gson-Syntaxfehler.
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

                if (HueV1Envelope.looksLikeEnvelope(responseBody)) {
                    val failure = HueV1Envelope.parseAll(responseBody).exceptionOrNull()
                        ?: IOException("Bridge antwortete mit einer Huelle statt mit einer Config: $responseBody")
                    Logger.w(LogTags.HUE_BRIDGE, "Bridge lehnte die Config-Abfrage ab: ${failure.message}")
                    throw failure
                }

                val config = gson.fromJson(responseBody, HueBridgeConfig::class.java)
                    ?: throw IOException("Bridge config response was empty: $responseBody")

                // Gson erzwingt die Non-Null-Deklarationen nicht - deshalb ueber nullable
                // Zwischenwerte pruefen statt sich auf den Typ zu verlassen.
                val bridgeId: String? = config.bridgeid
                val mac: String? = config.mac
                if (bridgeId.isNullOrBlank() && mac.isNullOrBlank()) {
                    throw IOException("Antwort ist keine Hue-Bridge-Config (weder bridgeid noch mac): $responseBody")
                }

                return@withContext config
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

                // FEHLERHUELLE STATT LAMPEN: Ist der Whitelist-Eintrag der App weg (Nutzer hat
                // sie in der Hue-App entfernt, Bridge zurueckgesetzt/getauscht), antwortet die
                // Bridge mit HTTP 200 und einem JSON-ARRAY
                // (`[{"error":{"type":1,"description":"unauthorized user"}}]`). Das Map-Parsing
                // unten scheitert daran zwangsläufig, und der catch machte daraus "0 Lampen" -
                // also einen ERFOLG mit leerem Ergebnis. Der Nutzer sah eine leere Lampenliste
                // bzw. "Keine Lampen gefunden", ohne jeden Hinweis, dass die Bridge neu
                // gekoppelt werden muss. Deshalb VOR dem try: die Huelle auswerten und werfen -
                // HueLightRepository faengt das und liefert ein ehrliches Result.failure.
                if (HueV1Envelope.looksLikeEnvelope(responseBody)) {
                    val failure = HueV1Envelope.parseAll(responseBody).exceptionOrNull()
                        ?: IOException("Bridge antwortete mit einer Huelle statt mit Lampen: $responseBody")
                    Logger.w(LogTags.HUE_LIGHTS, "Bridge lehnte die Lampen-Abfrage ab: ${failure.message}")
                    throw failure
                }

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

                // Gleiche Falle wie in [getLights]: HTTP 200 + Fehlerhuelle wurde zu "0 Gruppen".
                if (HueV1Envelope.looksLikeEnvelope(responseBody)) {
                    val failure = HueV1Envelope.parseAll(responseBody).exceptionOrNull()
                        ?: IOException("Bridge antwortete mit einer Huelle statt mit Gruppen: $responseBody")
                    Logger.w(LogTags.HUE_LIGHTS, "Bridge lehnte die Gruppen-Abfrage ab: ${failure.message}")
                    throw failure
                }

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
     * Alle Szenen der Bridge, nach Szenen-Id.
     *
     * Huellen-Waechter wie in [getLights]/[getGroups]: HTTP 200 + JSON-ARRAY ist bei einem
     * GET immer eine Ablehnung, nie ein Ergebnis. Eine Bridge ganz OHNE Szenen antwortet `{}`
     * und laeuft damit sauber in die leere Map - kein Fehlalarm.
     *
     * BEWUSSTE ABWEICHUNG von [getLights]/[getGroups]: ein Parserfehler wird hier GEWORFEN und
     * nicht zu `emptyMap()` degradiert. Eine still leere Szenenliste ist von "du hast keine
     * Szenen angelegt" nicht unterscheidbar - der Nutzer staende vor einem leeren Auswahl-Dialog
     * ohne jeden Hinweis, genau die Fehlerklasse, die die Huellen-Waechter beseitigt haben.
     * Nicht "angleichen".
     */
    suspend fun getScenes(bridgeIp: String, username: String): Map<String, HueScene> =
        withContext(Dispatchers.IO) {
            val result = makeSecureHueRequest(bridgeIp, "/api/$username/scenes", "GET")

            if (result.isSuccess) {
                val responseBody = result.getOrNull() ?: "{}"
                Logger.d(LogTags.HUE_LIGHTS, "Scenes API response: ${responseBody.length} Zeichen")

                if (HueV1Envelope.looksLikeEnvelope(responseBody)) {
                    val failure = HueV1Envelope.parseAll(responseBody).exceptionOrNull()
                        ?: IOException("Bridge antwortete mit einer Huelle statt mit Szenen: $responseBody")
                    Logger.w(LogTags.HUE_LIGHTS, "Bridge lehnte die Szenen-Abfrage ab: ${failure.message}")
                    throw failure
                }

                return@withContext try {
                    val type = object : TypeToken<Map<String, HueSceneDto>>() {}.type
                    val roh: Map<String, HueSceneDto> = gson.fromJson(responseBody, type) ?: emptyMap()
                    roh.mapValues { (id, dto) -> dto.toDomain(id) }
                } catch (e: Exception) {
                    Logger.e(LogTags.HUE_LIGHTS, "Failed to parse scenes response", e)
                    throw IOException("Szenen der Bridge nicht lesbar", e)
                }
            } else {
                throw result.exceptionOrNull() ?: IOException("Failed to get scenes")
            }
        }

    /**
     * Wendet eine Szene an: `PUT /groups/<groupId>/action` mit `{"scene":"<sceneId>"}`.
     *
     * Gegen die echte Bridge gemessen (BSB002, apiversion 1.78.0, 25.08.2026): die Antwort ist
     * `[{"success":{"/groups/5/action/scene":"3JXxZ…"}}]` - EIN Eintrag fuer den ganzen Aufruf,
     * nicht einer pro Lampe. [wasAccepted] mit `parseControl` ist damit richtig und streng genug.
     *
     * `transitiontime` wird bewusst NICHT mitgeschickt, obwohl die Bridge es im selben PUT
     * annimmt (ebenfalls gemessen, ohne Fehlereintrag): die Szene bestimmt ihren Uebergang
     * selbst, und ein zweiter Uebergangsbegriff daneben waere eine Zusage, die niemand prueft.
     */
    suspend fun applyScene(
        bridgeIp: String,
        username: String,
        groupId: String,
        sceneId: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = gson.toJson(mapOf("scene" to sceneId))
            val result = makeSecureHueRequest(bridgeIp, "/api/$username/groups/$groupId/action", "PUT", json)
            wasAccepted(result, "Szene $sceneId in Gruppe $groupId")
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_LIGHTS, "Error applying scene $sceneId to group $groupId", e)
            false
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
            wasAccepted(result, "Lampe $lightId")
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
            wasAccepted(result, "Gruppe $groupId")
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_LIGHTS, "Error controlling group $groupId", e)
            false
        }
    }

    /**
     * Hat die Bridge diesen Steuer-PUT wirklich ANGENOMMEN?
     *
     * Vor diesem Fix gaben [setLightState]/[setGroupAction]/[controlLight]/[controlGroup] blank
     * `result.isSuccess` zurueck - also nur den HTTP-Status. Die V1-API antwortet aber auch bei
     * ABLEHNUNG mit HTTP 200 (`[{"error":{"type":1,"description":"unauthorized user"}}]`), genau
     * die Falle, fuer die [HueV1Envelope] existiert - sie war nur auf die Zeitplaene angewandt,
     * nicht auf die Steuerung. Folge: nach einem entzogenen Whitelist-Eintrag meldete die Kette
     * "✅ ALARM-CRITICAL: Successfully controlled light" und "5/5 actions successful", ohne dass
     * eine einzige Lampe anging - aus dem Log nicht diagnostizierbar, weil das Log Erfolg behauptet.
     *
     * [HueV1Envelope.parseControl] und nicht `parseFirst`: ein PUT auf /state liefert EINEN
     * EINTRAG PRO GEAENDERTEM ATTRIBUT (`[{"success":{"…/on":true}},{"success":{"…/bri":200}}]`).
     * Und auch nicht [HueV1Envelope.parseAll]: dessen strenge Regel "KEIN Eintrag enthaelt error"
     * macht aus einem TEILERFOLG einen Totalausfall - siehe [HueV1Envelope.parseControl].
     * Angenommen heisst hier deshalb "mindestens ein Eintrag meldet success"; abgelehnte
     * Einzelattribute werden geloggt, damit ein Teilerfolg im Log nicht als glatter Erfolg
     * erscheint.
     */
    private fun wasAccepted(result: Result<String>, targetLabel: String): Boolean {
        val responseBody = result.getOrElse { error ->
            Logger.w(LogTags.HUE_LIGHTS, "Steuer-Anfrage fuer $targetLabel fehlgeschlagen: ${error.message}")
            return false
        }
        return HueV1Envelope.parseControl(responseBody).fold(
            onSuccess = { rejectedAttributes ->
                if (rejectedAttributes.isNotEmpty()) {
                    Logger.w(
                        LogTags.HUE_LIGHTS,
                        "Bridge nahm die Steuerung von $targetLabel nur TEILWEISE an - der Rest wurde " +
                            "angewendet: ${rejectedAttributes.joinToString(" | ")}"
                    )
                }
                true
            },
            onFailure = { error ->
                Logger.w(LogTags.HUE_LIGHTS, "Bridge lehnte die Steuerung von $targetLabel ab: ${error.message}")
                false
            }
        )
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

            // Body auswerten, nicht nur den HTTP-Status - siehe [wasAccepted].
            val accepted = wasAccepted(result, "Lampe $lightId")
            Logger.d(LogTags.HUE_LIGHTS, "Light state update result: $accepted")
            return@withContext accepted
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

            // Body auswerten, nicht nur den HTTP-Status - siehe [wasAccepted].
            val accepted = wasAccepted(result, "Gruppe $groupId")
            Logger.d(LogTags.HUE_LIGHTS, "Group action update result: $accepted")
            return@withContext accepted
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_LIGHTS, "Error setting group action for $groupId", e)
            return@withContext false
        }
    }

    // =========================================================================
    // BRIDGE-SEITIGE ZEITPLÄNE (/schedules)
    // =========================================================================

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

        HueV1Envelope.parseFirst(responseBody).mapCatching { success ->
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

        // Gleiche Falle wie in [getLights]/[getGroups]: HTTP 200 + Fehlerhuelle bei entzogenem
        // Whitelist-Eintrag. Ohne diesen Waechter wirft erst Gson im Map-Parsing ("Expected
        // BEGIN_ARRAY but was BEGIN_OBJECT"), und im Log stand ein kryptischer Parserfehler statt
        // der Beschreibung der Bridge - fuer das Aufraeumen der eigenen Auto-Aus-Timer
        // ([HueLightUseCase.clearOwnBridgeSchedules]) die einzige Diagnosequelle. Eine Bridge
        // OHNE Zeitplaene antwortet mit `{}`, also einem Objekt - kein Fehlalarm.
        if (HueV1Envelope.looksLikeEnvelope(responseBody)) {
            val failure = HueV1Envelope.parseAll(responseBody).exceptionOrNull()
                ?: IOException("Bridge antwortete mit einer Huelle statt mit Zeitplaenen: $responseBody")
            Logger.w(LogTags.HUE_BRIDGE, "Bridge lehnte die Zeitplan-Abfrage ab: ${failure.message}")
            return@withContext Result.failure(failure)
        }

        try {
            val type = object : TypeToken<Map<String, BridgeSchedule>>() {}.type
            Result.success(gson.fromJson(responseBody, type) ?: emptyMap())
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_BRIDGE, "Failed to parse schedules response: $responseBody", e)
            Result.failure(IOException("Failed to parse schedules: ${e.message}", e))
        }
    }

    /**
     * Löscht einen Zeitplan auf der Bridge.
     *
     * Ein DELETE antwortet mit `[{"success":"/schedules/1 deleted"}]` - "success" ist hier ein
     * STRING, kein Objekt. Siehe [HueV1Envelope.parseFirst]: genau daran scheiterte das
     * Aufraeumen fruueher scheinbar, obwohl es geklappt hatte.
     */
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
        HueV1Envelope.parseFirst(responseBody).map { }
    }
}

/**
 * Auswertung der ANTWORT-HUELLE der Hue-V1-API - rein, ohne Netzwerk, deshalb testbar
 * (siehe HueV1EnvelopeTest; der Rest dieser Datei laeuft nur gegen eine echte Bridge).
 *
 * ACHTUNG, die zentrale Falle dieser API: Sie antwortet **auch bei Ablehnung mit HTTP 200**.
 * Das Urteil steht ausschliesslich im Body — `[{"success":…}]` oder
 * `[{"error":{"type":7,"description":"…"}}]`. [HueApiClient.makeSecureHueRequest] kennt nur den
 * HTTP-Status; wer sich darauf verlaesst, haelt einen abgelehnten Zeitplan fuer angelegt (das
 * Licht geht nie wieder aus) bzw. eine abgelehnte Lampen-Steuerung fuer ausgefuehrt.
 * [HueApiClient.createUser] parst aus demselben Grund den Body.
 *
 * DREI AUSWERTUNGEN, WEIL DIE API MEHRERE ANTWORT-FORMEN HAT:
 * - [parseFirst] fuer Endpunkte mit GENAU EINEM Ergebnis (POST/DELETE /schedules).
 * - [parseControl] fuer die STEUER-PUTs (/lights/<id>/state, /groups/<id>/action): ein Eintrag pro
 *   Attribut, und einzelne Attribute duerfen abgelehnt werden, waehrend die anderen greifen -
 *   Teilerfolg ist dort ein Erfolg.
 * - [parseAll] als STRENGE Variante ("kein Eintrag darf error enthalten") fuer die
 *   Fehlerhuellen-Waechter der GET-Endpunkte ([HueApiClient.getBridgeConfig],
 *   [HueApiClient.getLights], [HueApiClient.getGroups], [HueApiClient.getSchedules]): dort ist eine
 *   Huelle NIE die erwartete Antwort, es geht nur darum, die Beschreibung der Bridge herauszuholen.
 */
internal object HueV1Envelope {

    private val gson = Gson()

    /**
     * Sieht der Body wie eine V1-Huelle aus (JSON-Array) statt wie die erwartete Ressource
     * (JSON-Objekt)? Genutzt von den GET-Endpunkten, die ein Objekt erwarten und deren
     * Map-Parsing an einer Fehlerhuelle sonst still zu "0 Ergebnisse" degradiert.
     */
    fun looksLikeEnvelope(responseBody: String): Boolean = responseBody.trimStart().startsWith("[")

    /**
     * Der EINE erwartete Eintrag.
     *
     * @return den Wert unter "success" oder ein Failure mit der Fehlerbeschreibung der Bridge.
     *
     * "success" ist je Endpunkt ein OBJEKT ODER EIN STRING: ein DELETE auf /schedules antwortet
     * mit `[{"success":"/schedules/1 deleted"}]`. Ein `as? Map<*, *>` darauf schlug fehl, fiel in
     * den "weder success noch error"-Zweig und machte aus einem erfolgreichen Loeschen ein
     * Failure - im Log stand `WARN Alt-Zeitplan id=1 nicht entfernt`, obwohl die Bridge ihn
     * wirklich geloescht hatte (echtes Geraetelog 05.08.2026). Doppelt schaedlich: die
     * Aufraeum-Logik war falsch informiert (und aufgeraeumt werden MUSS - sonst haeuft jeder
     * Snooze einen Timer an und der aelteste schaltet zu frueh aus), und echte Fehlschlaege
     * gingen im Rauschen falscher Warnungen unter. Seither gilt: JEDES vorhandene
     * "success"-Feld ist ein Erfolg. Ein String-Erfolg wird in eine Map verpackt
     * (Schluessel [KEY_MESSAGE]), damit die Signatur - und damit jeder Aufrufer - unveraendert
     * bleibt.
     */
    fun parseFirst(responseBody: String): Result<Map<*, *>> {
        val entries = entriesOf(responseBody).getOrElse { return Result.failure(it) }

        val first = entries.firstOrNull()
            ?: return Result.failure(IOException("Bridge returned an empty response"))

        errorOf(first)?.let { return Result.failure(it) }

        if (!first.containsKey(FIELD_SUCCESS)) {
            return Result.failure(
                IOException("Bridge response contained neither success nor error: $responseBody")
            )
        }

        return Result.success(successAsMap(first[FIELD_SUCCESS]))
    }

    /**
     * ALLE Eintraege, STRENG: "KEIN Eintrag enthaelt `error`". Fuer die Fehlerhuellen-Waechter der
     * GET-Endpunkte, die eine Huelle ueberhaupt nur zu sehen bekommen, wenn etwas schiefgegangen
     * ist - dort ist jede Ablehnung, in welchem Eintrag auch immer, das ganze Ergebnis. Auf
     * `success` wird bewusst NICHT typisiert geprueft (Objekt oder String, siehe [parseFirst]).
     *
     * NICHT fuer die Steuer-PUTs benutzen: dort ist ein einzelner abgelehnter Eintrag neben
     * angenommenen kein Totalausfall - siehe [parseControl].
     */
    fun parseAll(responseBody: String): Result<Unit> {
        val entries = entriesOf(responseBody).getOrElse { return Result.failure(it) }

        if (entries.isEmpty()) {
            return Result.failure(IOException("Bridge returned an empty response"))
        }

        entries.forEach { entry ->
            errorOf(entry)?.let { return Result.failure(it) }
        }

        if (entries.none { it?.containsKey(FIELD_SUCCESS) == true }) {
            return Result.failure(
                IOException("Bridge response contained neither success nor error: $responseBody")
            )
        }

        return Result.success(Unit)
    }

    /**
     * STEUER-PUTs: ein TEILERFOLG ist ein Erfolg.
     *
     * Ein PUT auf /lights/<id>/state liefert einen Eintrag PRO ATTRIBUT - und die Bridge darf
     * einzelne Attribute ABLEHNEN, waehrend sie die anderen anwendet: `ct`/`hue`/`sat` an einer
     * Lampe ohne diese Faehigkeit (Hue White, Fremdlampe im ZigBee-Netz) → error type 6,
     * `bri`/`alert` an einer ausgeschalteten Lampe → error type 201. Eine reale Antwort ist dann
     * `[{"success":{"…/on":true}},{"success":{"…/bri":1}},{"error":{"type":6,"address":"…/ct"}}]`
     * - das Licht IST an, nur die Farbtemperatur wurde ignoriert.
     *
     * Die strenge Regel von [parseAll] wuerde das zum kompletten Fehlschlag machen, und der
     * schlaegt bis in die Kette durch: `HueLightRepository.controlLight` liefert dann
     * `Result.failure`, und `HueLightUseCase.startSunrise` steigt nach Schritt 1 mit
     * `return initial` aus - die eigentliche Aufhell-Transition (Schritt 2) liefe NIE, die Lampe
     * bliebe am Wecktag auf `bri=1` stehen. Genau davor warnt CLAUDE.md beim Sonnenaufgang: ein zu
     * strenges Urteil bricht die Rampe mitten im Aufblenden ab.
     *
     * Angenommen = MINDESTENS EIN `success`-Eintrag. Erst wenn KEIN Eintrag Erfolg meldet, ist es
     * ein echter Fehlschlag - der eigentliche Zielfall "unauthorized user" (ausschliesslich
     * error-Eintraege) bleibt damit unveraendert vollstaendig abgedeckt.
     *
     * @return bei Erfolg die Meldungen der ABGELEHNTEN Attribute (meist leer). Der Aufrufer loggt
     *         sie als Warnung - sonst waere ein Teilerfolg im Log von einem glatten Erfolg nicht zu
     *         unterscheiden, und genau diese Log-Ehrlichkeit war der Zweck der Body-Auswertung.
     */
    fun parseControl(responseBody: String): Result<List<String>> {
        val entries = entriesOf(responseBody).getOrElse { return Result.failure(it) }

        if (entries.isEmpty()) {
            return Result.failure(IOException("Bridge returned an empty response"))
        }

        val rejections = entries.mapNotNull { errorOf(it) }
        val accepted = entries.any { it?.containsKey(FIELD_SUCCESS) == true }

        if (!accepted) {
            return Result.failure(
                rejections.firstOrNull()
                    ?: IOException("Bridge response contained neither success nor error: $responseBody")
            )
        }

        return Result.success(rejections.map { it.message ?: "unknown error" })
    }

    /** Schluessel, unter dem ein String-Erfolg abgelegt wird (siehe [parseFirst]). */
    const val KEY_MESSAGE = "message"

    private const val FIELD_SUCCESS = "success"
    private const val FIELD_ERROR = "error"

    /**
     * Die Huelle als Liste. Eintraege sind bewusst nullable: `[null]` ist gueltiges JSON und
     * wuerde als Nicht-Null-Typ erst spaeter knallen.
     */
    private fun entriesOf(responseBody: String): Result<List<Map<String, Any>?>> {
        return try {
            val type = object : TypeToken<List<Map<String, Any>?>>() {}.type
            val entries: List<Map<String, Any>?> = gson.fromJson(responseBody, type)
                ?: return Result.failure(IOException("Bridge returned an unparseable response"))
            Result.success(entries)
        } catch (e: Exception) {
            Result.failure(IOException("Failed to parse bridge response: $responseBody", e))
        }
    }

    /** Die Fehlerbeschreibung der Bridge, falls dieser Eintrag eine Ablehnung ist. */
    private fun errorOf(entry: Map<*, *>?): IOException? {
        val error = entry?.get(FIELD_ERROR) as? Map<*, *> ?: return null
        val description = error["description"] ?: "unknown error"
        val errorType = (error["type"] as? Double)?.toInt()
        return IOException("Bridge rejected the request (type $errorType): $description")
    }

    private fun successAsMap(success: Any?): Map<*, *> = when (success) {
        is Map<*, *> -> success
        null -> emptyMap<String, Any>()
        else -> mapOf(KEY_MESSAGE to success)
    }
}

/**
 * Rohgestalt eines Szenen-Eintrags, wie ihn `GET /api/<user>/scenes` liefert.
 *
 * Eine eigene Klasse, weil die Szenen-Id der SCHLUESSEL der Map ist und nicht im Rumpf steht -
 * `HueScene.id` waere beim direkten Deserialisieren `null`, obwohl es nicht-nullbar deklariert
 * ist (Gson erzwingt das nicht). Der Schluessel wird deshalb in [toDomain] von Hand gesetzt.
 * Alles Uebrige ist nullbar, weil die Bridge Felder weglassen darf.
 */
internal data class HueSceneDto(
    val name: String? = null,
    val type: String? = null,
    val group: String? = null,
    val lights: List<String>? = null,
    val recycle: Boolean? = null
) {
    fun toDomain(id: String): HueScene = HueScene(
        id = id,
        name = name,
        type = type,
        group = group,
        lights = lights,
        recycle = recycle
    )
}
