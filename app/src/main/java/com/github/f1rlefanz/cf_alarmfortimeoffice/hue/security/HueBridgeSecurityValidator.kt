package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.security

import android.security.NetworkSecurityPolicy
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Security Validator für Hue Bridge Kommunikation
 * 
 * Implementiert HTTPS-First Ansatz mit intelligentem HTTP-Fallback
 * gemäß Android Security Best Practices 2025 und GDPR-Compliance.
 * 
 * Features:
 * - RFC 1918 Private Network Validation
 * - HTTPS-First mit HTTP-Fallback
 * - Protocol Capability Detection
 * - Security Policy Enforcement
 */
object HueBridgeSecurityValidator {
    
    /**
     * Prüft ob eine IP-Adresse für lokale IoT-Kommunikation erlaubt ist
     */
    fun isPrivateNetworkAddress(ipAddress: String): Boolean {
        return try {
            val addr = InetAddress.getByName(ipAddress)
            val bytes = addr.address
            
            when {
                // RFC 1918: 192.168.0.0/16
                bytes.size == 4 && 
                bytes[0].toInt() and 0xFF == 192 && 
                bytes[1].toInt() and 0xFF == 168 -> {
                    Logger.d(LogTags.HUE_SECURITY, "IP $ipAddress is valid RFC 1918 Class C (192.168.x.x)")
                    true
                }
                
                // RFC 1918: 10.0.0.0/8
                bytes.size == 4 && 
                bytes[0].toInt() and 0xFF == 10 -> {
                    Logger.d(LogTags.HUE_SECURITY, "IP $ipAddress is valid RFC 1918 Class A (10.x.x.x)")
                    true
                }
                
                // RFC 1918: 172.16.0.0/12 (172.16.0.0 - 172.31.255.255)
                bytes.size == 4 && 
                bytes[0].toInt() and 0xFF == 172 && 
                (bytes[1].toInt() and 0xFF) in 16..31 -> {
                    Logger.d(LogTags.HUE_SECURITY, "IP $ipAddress is valid RFC 1918 Class B (172.16-31.x.x)")
                    true
                }
                
                // Link-Local: 169.254.0.0/16
                bytes.size == 4 && 
                bytes[0].toInt() and 0xFF == 169 && 
                bytes[1].toInt() and 0xFF == 254 -> {
                    Logger.d(LogTags.HUE_SECURITY, "IP $ipAddress is valid Link-Local (169.254.x.x)")
                    true
                }
                
                // Localhost
                addr.isLoopbackAddress -> {
                    Logger.d(LogTags.HUE_SECURITY, "IP $ipAddress is localhost")
                    true
                }
                
                else -> {
                    Logger.w(LogTags.HUE_SECURITY, "IP $ipAddress is NOT a private network address - blocking for security")
                    false
                }
            }
        } catch (e: UnknownHostException) {
            Logger.e(LogTags.HUE_SECURITY, "Invalid IP address format: $ipAddress", e)
            false
        }
    }
    
    /**
     * Prüft ob HTTP-Verbindungen für diese IP erlaubt sind
     * Kombiniert Network Security Policy mit Private Network Validation
     */
    fun isCleartextPermitted(ipAddress: String): Boolean {
        // Erst prüfen ob es eine private IP ist
        if (!isPrivateNetworkAddress(ipAddress)) {
            Logger.w(LogTags.HUE_SECURITY, "Cleartext denied: $ipAddress is not a private network")
            return false
        }
        
        // Dann Android Network Security Policy prüfen
        val isPermitted = try {
            NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(ipAddress)
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_SECURITY, "Error checking cleartext policy for $ipAddress", e)
            false
        }
        
        Logger.d(LogTags.HUE_SECURITY, "Cleartext permission for $ipAddress: $isPermitted")
        return isPermitted
    }
    
    /**
     * Bestimmt optimales Protokoll für Bridge-Kommunikation
     * 
     * @param ipAddress Bridge IP-Adresse
     * @return Pair<protocol, port> - z.B. Pair("https", 443) oder Pair("http", 80)
     */
    fun determineOptimalProtocol(ipAddress: String): Pair<String, Int> {
        if (!isPrivateNetworkAddress(ipAddress)) {
            Logger.w(LogTags.HUE_SECURITY, "Non-private IP $ipAddress - forcing HTTPS only")
            return Pair("https", 443)
        }
        
        // Für private IPs: HTTPS bevorzugt, HTTP als Fallback erlaubt
        return if (isCleartextPermitted(ipAddress)) {
            Logger.i(LogTags.HUE_SECURITY, "Private IP $ipAddress - HTTPS preferred, HTTP fallback available")
            Pair("https", 443) // Start with HTTPS, fallback wird in HueBridgeClient implementiert
        } else {
            Logger.i(LogTags.HUE_SECURITY, "Private IP $ipAddress - HTTPS only (cleartext blocked)")
            Pair("https", 443)
        }
    }
    
    /**
     * Validiert Bridge-Hostname für mDNS-entdeckte Geräte
     */
    fun validateBridgeHostname(hostname: String): Boolean {
        val isValid = when {
            // Hue Bridge mDNS Pattern: "Hue Bridge - XXXXX"
            hostname.matches(Regex("^Hue Bridge - [A-F0-9]{6}$", RegexOption.IGNORE_CASE)) -> true
            
            // Philips Hue mDNS Service Pattern
            hostname.contains("hue", ignoreCase = true) && 
            hostname.contains("bridge", ignoreCase = true) -> true
            
            // IP-Adressen direkt (falls über N-UPnP gefunden)
            hostname.matches(Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")) -> 
                isPrivateNetworkAddress(hostname)
            
            else -> {
                Logger.w(LogTags.HUE_SECURITY, "Unknown hostname pattern: $hostname")
                false
            }
        }
        
        Logger.d(LogTags.HUE_SECURITY, "Hostname validation for '$hostname': $isValid")
        return isValid
    }
}
