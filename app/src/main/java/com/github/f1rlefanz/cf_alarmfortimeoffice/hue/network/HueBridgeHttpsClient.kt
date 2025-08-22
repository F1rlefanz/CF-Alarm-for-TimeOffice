package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.network

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.security.HueBridgeSecurityValidator
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

/**
 * Modern HTTPS-First Hue Bridge Client
 * 
 * Implementiert bleeding-edge 2025 Standards:
 * - HTTPS-First mit intelligentem HTTP-Fallback
 * - TLS Certificate Validation für Philips CA
 * - Moderne Protocol Detection
 * - Security-by-Design Ansatz
 */
class HueBridgeHttpsClient {
    
    companion object {
        private const val TIMEOUT_SECONDS = 10L
        private const val HTTPS_PORT = 443
        private const val HTTP_PORT = 80
    }
    
    // Bridge Capability Cache
    private val bridgeCapabilities = mutableMapOf<String, BridgeCapability>()
    
    data class BridgeCapability(
        val supportsHttps: Boolean,
        val supportsHttp: Boolean,
        val preferredProtocol: String,
        val preferredPort: Int,
        val lastTested: Long = System.currentTimeMillis()
    )
    
    /**
     * Erstellt HTTPS-optimierten OkHttpClient für moderne Hue Bridges
     */
    private fun createHttpsClient(ipAddress: String): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .hostnameVerifier { hostname, session ->
                // Custom Hostname Verification für Hue Bridges
                verifyHueBridgeHostname(hostname, session)
            }
            .sslSocketFactory(createTrustingSSLSocketFactory(), createTrustingTrustManager())
            .build()
    }
    
    /**
     * Erstellt HTTP-Client für Legacy Bridge Fallback
     */
    private fun createHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }
    
    /**
     * Hostname Verification für Hue Bridges
     * Berücksichtigt Bridge-spezifische Certificate Patterns
     */
    private fun verifyHueBridgeHostname(hostname: String, session: SSLSession): Boolean {
        try {
            val peerCertificates = session.peerCertificates
            if (peerCertificates.isEmpty()) {
                Logger.w(LogTags.HUE_HTTPS, "No peer certificates found for $hostname")
                return false
            }
            
            val cert = peerCertificates[0] as? X509Certificate
            if (cert == null) {
                Logger.w(LogTags.HUE_HTTPS, "No X509 certificate found for $hostname")
                return false
            }
            
            val subjectCN = cert.subjectDN.toString()
            Logger.d(LogTags.HUE_HTTPS, "Certificate Subject for $hostname: $subjectCN")
            
            // Hue Bridge Certificate Patterns
            val isValidCert = when {
                // Moderne Signify-signierte Certificates
                subjectCN.contains("Philips Hue", ignoreCase = true) -> true
                
                // Bridge ID basierte Certificates
                subjectCN.contains(Regex("[A-F0-9]{12}", RegexOption.IGNORE_CASE)) -> true
                
                // Self-signed Legacy Certificates (weniger sicher, aber erlaubt)
                cert.issuerDN == cert.subjectDN -> {
                    Logger.i(LogTags.HUE_HTTPS, "Self-signed certificate detected for $hostname - allowing for legacy bridge")
                    true
                }
                
                else -> false
            }
            
            if (isValidCert) {
                Logger.i(LogTags.HUE_HTTPS, "Certificate validation successful for Hue Bridge $hostname")
            } else {
                Logger.w(LogTags.HUE_HTTPS, "Certificate validation failed for $hostname")
            }
            
            return isValidCert
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_HTTPS, "Hostname verification error for $hostname", e)
            return false
        }
    }
    
    /**
     * Creates Android 14+ compliant SSL Socket Factory for Hue Bridge Certificates
     * 
     * SECURITY IMPROVEMENT: Uses SecureHueTrustManager with hybrid trust model
     * - Primary: Android System Trust Store (auto-updated via Play Services)
     * - Fallback: Hue-specific validation for local bridges
     */
    private fun createTrustingSSLSocketFactory(): SSLSocketFactory {
        return SecureHueTrustManager.createSecureSSLContext().socketFactory
    }
    
    /**
     * Creates Android 14+ compliant Trust Manager for Hue Bridge Certificates
     * 
     * SECURITY IMPROVEMENT: Uses SecureHueTrustManager with system trust store integration
     * This resolves Android Lint warnings while maintaining security for local bridge communication.
     */
    private fun createTrustingTrustManager(): X509TrustManager {
        return SecureHueTrustManager.create()
    }
    
    /**
     * Intelligent HTTPS-First Bridge Communication
     * 
     * Algorithmus:
     * 1. Prüfe Security Policy für IP
     * 2. Teste HTTPS (443) zuerst
     * 3. Fallback zu HTTP (80) falls erlaubt
     * 4. Cache Capabilities für zukünftige Requests
     */
    suspend fun makeRequest(
        ipAddress: String,
        endpoint: String,
        method: String = "GET",
        body: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        
        // Security Validation
        if (!HueBridgeSecurityValidator.isPrivateNetworkAddress(ipAddress)) {
            return@withContext Result.failure(
                SecurityException("IP $ipAddress is not a private network address")
            )
        }
        
        // Check cached capabilities
        val cached = bridgeCapabilities[ipAddress]
        if (cached != null && (System.currentTimeMillis() - cached.lastTested) < 300_000) { // 5min cache
            Logger.d(LogTags.HUE_HTTPS, "Using cached capabilities for $ipAddress: ${cached.preferredProtocol}")
            return@withContext makeRequestWithProtocol(ipAddress, endpoint, method, body, cached.preferredProtocol, cached.preferredPort)
        }
        
        // Protocol Detection: HTTPS first
        Logger.i(LogTags.HUE_HTTPS, "Starting HTTPS-first protocol detection for bridge $ipAddress")
        
        // Try HTTPS first
        val httpsResult = makeRequestWithProtocol(ipAddress, endpoint, method, body, "https", HTTPS_PORT)
        if (httpsResult.isSuccess) {
            Logger.business(LogTags.HUE_HTTPS, "✅ HTTPS connection successful to $ipAddress - caching as preferred")
            bridgeCapabilities[ipAddress] = BridgeCapability(
                supportsHttps = true,
                supportsHttp = false, // nicht getestet
                preferredProtocol = "https",
                preferredPort = HTTPS_PORT
            )
            return@withContext httpsResult
        }
        
        Logger.i(LogTags.HUE_HTTPS, "HTTPS failed for $ipAddress, trying HTTP fallback")
        
        // Fallback to HTTP if allowed
        if (!HueBridgeSecurityValidator.isCleartextPermitted(ipAddress)) {
            Logger.w(LogTags.HUE_HTTPS, "HTTP fallback blocked by security policy for $ipAddress")
            return@withContext Result.failure(
                SecurityException("HTTPS failed and HTTP not permitted for $ipAddress")
            )
        }
        
        val httpResult = makeRequestWithProtocol(ipAddress, endpoint, method, body, "http", HTTP_PORT)
        if (httpResult.isSuccess) {
            Logger.business(LogTags.HUE_HTTPS, "⚠️ HTTP fallback successful for $ipAddress - legacy bridge detected")
            bridgeCapabilities[ipAddress] = BridgeCapability(
                supportsHttps = false,
                supportsHttp = true,
                preferredProtocol = "http",
                preferredPort = HTTP_PORT
            )
            return@withContext httpResult
        }
        
        Logger.e(LogTags.HUE_HTTPS, "❌ Both HTTPS and HTTP failed for bridge $ipAddress")
        return@withContext Result.failure(
            IOException("Bridge $ipAddress unreachable via HTTPS or HTTP")
        )
    }
    
    /**
     * Macht Request mit spezifischem Protokoll
     */
    private suspend fun makeRequestWithProtocol(
        ipAddress: String,
        endpoint: String,
        method: String,
        body: String?,
        protocol: String,
        port: Int
    ): Result<String> = withContext(Dispatchers.IO) {
        
        val client = if (protocol == "https") {
            createHttpsClient(ipAddress)
        } else {
            createHttpClient()
        }
        
        val url = "$protocol://$ipAddress:$port$endpoint"
        Logger.d(LogTags.HUE_HTTPS, "Making $method request to $url")
        
        try {
            val requestBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", "CFAlarm/1.0 (Kotlin)")
            
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
                val responseBody = response.body?.string() ?: ""
                Logger.d(LogTags.HUE_HTTPS, "$protocol request to $ipAddress successful")
                Result.success(responseBody)
            } else {
                Logger.w(LogTags.HUE_HTTPS, "$protocol request to $ipAddress failed: ${response.code}")
                Result.failure(IOException("HTTP ${response.code}: ${response.message}"))
            }
            
        } catch (e: Exception) {
            Logger.d(LogTags.HUE_HTTPS, "$protocol request to $ipAddress failed: ${e.message}")
            Result.failure(e)
        }
    }
    
    /**
     * Prüft Bridge-Erreichbarkeit und bestimmt beste Verbindungsmethode
     */
    suspend fun testBridgeConnectivity(ipAddress: String): BridgeCapability? = withContext(Dispatchers.IO) {
        Logger.i(LogTags.HUE_HTTPS, "Testing connectivity and capabilities for bridge $ipAddress")
        
        var httpsWorks = false
        var httpWorks = false
        
        // Test HTTPS
        try {
            val httpsResult = makeRequestWithProtocol(ipAddress, "/api", "GET", null, "https", HTTPS_PORT)
            httpsWorks = httpsResult.isSuccess
            Logger.d(LogTags.HUE_HTTPS, "HTTPS test for $ipAddress: $httpsWorks")
        } catch (e: Exception) {
            Logger.d(LogTags.HUE_HTTPS, "HTTPS test failed for $ipAddress: ${e.message}")
        }
        
        // Test HTTP nur falls erlaubt
        if (HueBridgeSecurityValidator.isCleartextPermitted(ipAddress)) {
            try {
                val httpResult = makeRequestWithProtocol(ipAddress, "/api", "GET", null, "http", HTTP_PORT)
                httpWorks = httpResult.isSuccess
                Logger.d(LogTags.HUE_HTTPS, "HTTP test for $ipAddress: $httpWorks")
            } catch (e: Exception) {
                Logger.d(LogTags.HUE_HTTPS, "HTTP test failed for $ipAddress: ${e.message}")
            }
        }
        
        val capability = when {
            httpsWorks -> BridgeCapability(
                supportsHttps = true,
                supportsHttp = httpWorks,
                preferredProtocol = "https",
                preferredPort = HTTPS_PORT
            )
            httpWorks -> BridgeCapability(
                supportsHttps = false,
                supportsHttp = true,
                preferredProtocol = "http",
                preferredPort = HTTP_PORT
            )
            else -> null
        }
        
        if (capability != null) {
            bridgeCapabilities[ipAddress] = capability
            Logger.business(LogTags.HUE_HTTPS, "Bridge $ipAddress capabilities: HTTPS=${capability.supportsHttps}, HTTP=${capability.supportsHttp}, preferred=${capability.preferredProtocol}")
        } else {
            Logger.w(LogTags.HUE_HTTPS, "Bridge $ipAddress is unreachable via both HTTPS and HTTP")
        }
        
        capability
    }
}
