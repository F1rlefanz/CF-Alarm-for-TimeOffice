package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data

import androidx.compose.runtime.Immutable

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
    val name: String? = null,
    val modelid: String? = null,
    val swversion: String? = null,
    
    /** Whether the bridge is currently reachable */
    val isReachable: Boolean = false
) {
    // Legacy compatibility property
    val internalipaddress: String
        get() = ipAddress
}

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
    val modelid: String
)

/**
 * Bridge discovery response
 */
@Immutable
data class BridgeDiscoveryResponse(
    val id: String,
    val internalipaddress: String
)
