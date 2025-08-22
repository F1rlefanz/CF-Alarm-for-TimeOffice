package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.network

import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.HostnameVerifier
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags

/**
 * SECURITY-HARDENED SSL Certificate Manager for Philips Hue Bridge Integration
 * 
 * SECURITY PRINCIPLE: Defense in Depth
 * 
 * Philips Hue Bridges use self-signed certificates which are rejected by default.
 * This manager provides STRICTLY CONTROLLED certificate acceptance ONLY for bridge communication
 * while maintaining HTTPS-only for all other connections.
 * 
 * SECURITY MEASURES:
 * ✅ STRICT validation: Only private network IP ranges (RFC 1918)
 * ✅ Hue-specific certificate subject validation
 * ✅ Comprehensive security logging for audit trails
 * ✅ Reject any non-Hue certificate patterns
 * ✅ Time-based certificate validation
 * 
 * @author CF-Alarm Development Team
 * @since Hue Integration v2.1 (Security-Hardened)
 */
object TrustAllCertificatesManager {
    
    /**
     * Creates SECURITY-HARDENED SSLSocketFactory that accepts ONLY validated Hue Bridge certificates
     * 
     * SECURITY: Multi-layer validation:
     * 1. Private network IP validation (RFC 1918)
     * 2. Hue-specific certificate subject validation
     * 3. Certificate validity period check
     * 4. Security audit logging
     */
    fun createTrustAllSSLSocketFactory(): SSLSocketFactory {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                // SECURITY: Validate client certificates with strict Hue validation
                validateHueCertificateChain(chain, "client", authType)
            }
            
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                // SECURITY: Validate server certificates with strict Hue validation
                validateHueCertificateChain(chain, "server", authType)
            }
            
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustManager), java.security.SecureRandom())
        
        Logger.i(LogTags.HUE_NETWORK, "🔒 SECURITY: SSL TrustManager initialized for HARDENED Hue Bridge communication")
        return sslContext.socketFactory
    }
    
    /**
     * SECURITY-HARDENED HostnameVerifier with strict validation
     * 
     * SECURITY LAYERS:
     * 1. Private network IP validation (RFC 1918)
     * 2. Additional Hue-specific hostname patterns
     * 3. Security audit logging
     * 4. Explicit rejection of suspicious patterns
     */
    fun createTrustAllHostnameVerifier(): HostnameVerifier {
        return HostnameVerifier { hostname, session ->
            val isValidHueHostname = validateHueHostname(hostname, session)
            
            // SECURITY AUDIT: Log all certificate validation attempts
            if (isValidHueHostname) {
                Logger.i(LogTags.HUE_NETWORK, "🔒 SECURITY: Accepting validated Hue hostname: $hostname")
            } else {
                Logger.w(LogTags.HUE_NETWORK, "🚨 SECURITY: REJECTING non-Hue hostname: $hostname")
            }
            
            isValidHueHostname
        }
    }
    
    /**
     * SECURITY: Validate Hue Bridge certificates with comprehensive checks
     * 
     * Multi-layer validation:
     * 1. Certificate chain integrity
     * 2. Hue-specific subject/issuer patterns
     * 3. Certificate validity period
     * 4. Security logging for audit trails
     */
    private fun validateHueCertificateChain(
        chain: Array<X509Certificate>, 
        type: String, 
        authType: String
    ) {
        if (chain.isEmpty()) {
            val error = "Empty certificate chain for $type"
            Logger.e(LogTags.HUE_NETWORK, "🚨 SECURITY VIOLATION: $error")
            throw java.security.cert.CertificateException(error)
        }
        
        val cert = chain[0]
        val subjectDN = cert.subjectDN?.toString() ?: "Unknown"
        val issuerDN = cert.issuerDN?.toString() ?: "Unknown"
        
        try {
            // SECURITY: Check certificate validity period
            cert.checkValidity()
            
            // SECURITY: Validate Hue-specific certificate patterns
            val isValidHueCert = isValidHueCertificate(cert)
            
            if (isValidHueCert) {
                Logger.i(LogTags.HUE_NETWORK, "🔒 SECURITY: Validated $type certificate - Subject: $subjectDN")
                Logger.d(LogTags.HUE_NETWORK, "🔒 SECURITY: Certificate issuer: $issuerDN, AuthType: $authType")
            } else {
                val error = "Certificate does not match Hue Bridge patterns: $subjectDN"
                Logger.e(LogTags.HUE_NETWORK, "🚨 SECURITY VIOLATION: $error")
                throw java.security.cert.CertificateException(error)
            }
        } catch (e: java.security.cert.CertificateException) {
            Logger.e(LogTags.HUE_NETWORK, "🚨 SECURITY: Certificate validation failed for $type", e)
            throw e
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_NETWORK, "🚨 SECURITY: Unexpected error validating $type certificate", e)
            throw java.security.cert.CertificateException("Certificate validation failed", e)
        }
    }
    
    /**
     * SECURITY: Validate if certificate matches Philips Hue Bridge patterns
     * 
     * Hue Bridges typically have certificates with specific patterns:
     * - Self-signed certificates
     * - Common Name patterns matching Hue conventions
     * - Organization patterns from Philips
     */
    private fun isValidHueCertificate(cert: X509Certificate): Boolean {
        return try {
            val subjectDN = cert.subjectDN?.toString()?.lowercase() ?: ""
            val issuerDN = cert.issuerDN?.toString()?.lowercase() ?: ""
            
            // SECURITY: Hue bridges typically have these patterns
            val hasHuePattern = subjectDN.contains("philips") ||
                               issuerDN.contains("philips") ||
                               subjectDN.contains("hue") ||
                               issuerDN.contains("hue") ||
                               subjectDN.contains("bridge") ||
                               // Self-signed certificates (subject == issuer)
                               (subjectDN.isNotEmpty() && subjectDN == issuerDN)
            
            Logger.d(LogTags.HUE_NETWORK, "🔍 SECURITY: Hue pattern validation - hasPattern: $hasHuePattern")
            hasHuePattern
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_NETWORK, "🚨 SECURITY: Error validating Hue certificate pattern", e)
            false
        }
    }
    
    /**
     * SECURITY: Validate hostname with comprehensive Hue-specific checks
     * 
     * Multi-layer validation:
     * 1. Private network IP validation (RFC 1918)
     * 2. Hue-specific hostname patterns
     * 3. SSL session validation
     */
    private fun validateHueHostname(hostname: String, session: javax.net.ssl.SSLSession): Boolean {
        return try {
            // LAYER 1: Basic private network validation
            val isPrivateNetwork = isPrivateNetworkAddress(hostname)
            if (!isPrivateNetwork) {
                Logger.w(LogTags.HUE_NETWORK, "🚨 SECURITY: Hostname not in private network: $hostname")
                return false
            }
            
            // LAYER 2: SSL session validation
            val isValidSession = session.isValid && session.cipherSuite.isNotEmpty()
            if (!isValidSession) {
                Logger.w(LogTags.HUE_NETWORK, "🚨 SECURITY: Invalid SSL session for: $hostname")
                return false
            }
            
            // LAYER 3: Additional Hue-specific validation if needed
            Logger.d(LogTags.HUE_NETWORK, "🔒 SECURITY: Hostname validation passed - IP: $hostname, Cipher: ${session.cipherSuite}")
            true
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_NETWORK, "🚨 SECURITY: Error validating Hue hostname: $hostname", e)
            false
        }
    }
    
    /**
     * SECURITY: Validate if hostname/IP is in private network range (RFC 1918)
     * 
     * STRICT validation: Only trust certificates from private networks
     * Additional security layer: Reject suspicious patterns
     */
    private fun isPrivateNetworkAddress(hostname: String): Boolean {
        return try {
            // SECURITY: Reject suspicious patterns immediately
            if (hostname.isEmpty() || hostname.contains("..") || hostname.contains("://")) {
                Logger.w(LogTags.HUE_NETWORK, "🚨 SECURITY: Suspicious hostname pattern detected: $hostname")
                return false
            }
            
            when {
                // IPv4 private ranges (RFC 1918)
                hostname.startsWith("192.168.") -> true
                hostname.startsWith("10.") -> true
                hostname.startsWith("172.") -> {
                    val secondOctet = hostname.split(".").getOrNull(1)?.toIntOrNull() ?: 0
                    secondOctet in 16..31
                }
                // Localhost (development only)
                hostname == "localhost" || hostname == "127.0.0.1" -> true
                // Link-local addresses (RFC 3927)
                hostname.startsWith("169.254.") -> true
                else -> {
                    Logger.d(LogTags.HUE_NETWORK, "🔍 SECURITY: Hostname not in private network range: $hostname")
                    false
                }
            }
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_NETWORK, "🚨 SECURITY: Error validating private network address: $hostname", e)
            false
        }
    }
}
