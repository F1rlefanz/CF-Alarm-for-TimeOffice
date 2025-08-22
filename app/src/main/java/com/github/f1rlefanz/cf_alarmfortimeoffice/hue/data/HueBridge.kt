package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data

import androidx.compose.runtime.Immutable
import com.google.gson.annotations.SerializedName

/**
 * Represents a Philips Hue Bridge (Enhanced 2025 Edition)
 * @Immutable annotation optimizes Compose performance by preventing unnecessary recompositions
 * 
 * Enhanced Features:
 * - Protocol capability tracking (HTTPS/HTTP support)
 * - Discovery method metadata
 * - Connectivity status
 * - Security validation status
 */
@Immutable
data class HueBridge(
    val id: String,
    val ipAddress: String, // Renamed from internalipaddress for clarity
    val port: Int = 80,
    val macAddress: String? = null,
    val name: String? = null,
    val modelid: String? = null,
    val swversion: String? = null,
    
    // === ENHANCED 2025 FEATURES ===
    
    /** Discovery method used to find this bridge */
    val discoveryMethod: DiscoveryMethod? = null,
    
    /** Whether the bridge is currently reachable */
    val isReachable: Boolean = false,
    
    /** Preferred protocol (https/http) based on capability detection */
    val preferredProtocol: String? = null,
    
    /** Last successful connection timestamp */
    val lastSeen: Long? = null,
    
    /** Bridge capabilities (HTTPS/HTTP support) */
    val capabilities: BridgeCapabilities? = null
) {
    // Legacy compatibility property
    val internalipaddress: String
        get() = ipAddress
}

/**
 * Bridge Protocol Capabilities
 */
@Immutable
data class BridgeCapabilities(
    val supportsHttps: Boolean = false,
    val supportsHttp: Boolean = false,
    val httpsPort: Int = 443,
    val httpPort: Int = 80,
    val certificateType: String? = null, // "signify", "self-signed", "unknown"
    val tlsVersion: String? = null // "1.2", "1.3", etc.
)

/**
 * Bridge configuration response
 */
@Immutable
data class HueBridgeConfig(
    val name: String,
    val datastoreversion: String,
    val swversion: String,
    val apiversion: String,
    val mac: String,
    val bridgeid: String,
    val factorynew: Boolean,
    val replacesbridgeid: String?,
    val modelid: String,
    val whitelist: Map<String, HueUser>? = null
)

/**
 * Hue User/Application
 */
@Immutable
data class HueUser(
    @SerializedName("last use date")
    val lastUseDate: String,
    @SerializedName("create date")
    val createDate: String,
    val name: String
)

/**
 * Bridge discovery response
 */
@Immutable
data class BridgeDiscoveryResponse(
    val id: String,
    val internalipaddress: String,
    val port: Int? = null
)
