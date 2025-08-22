package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data

import androidx.compose.runtime.Immutable

/**
 * Enhanced Discovery status for Hue bridge discovery process with animation support
 * @Immutable annotation optimizes Compose performance
 */
@Immutable
data class DiscoveryStatus(
    val method: DiscoveryMethod,
    val stage: String, // Changed from enum to String for backward compatibility
    val message: String,
    val progress: Float = 0f, // 0.0 to 1.0
    val isComplete: Boolean = false,
    val isError: Boolean = false,
    val currentMethod: String? = null, // Current discovery method being used
    val foundBridges: Int = 0, // Number of bridges found so far
    val duration: Long = 0L // Discovery duration in milliseconds
)

/**
 * Discovery method being used (Enhanced 2025 Edition)
 */
enum class DiscoveryMethod {
    ONLINE_DISCOVERY, // Using Philips cloud service (N-UPnP)
    N_UPNP,          // Alias for ONLINE_DISCOVERY - modern name
    MDNS,            // Using mDNS discovery (_hue._tcp.local)
    LOCAL_NETWORK,   // Deprecated: Legacy local network scanning
    IP_TEST,         // Deprecated: Testing specific IP addresses
    MANUAL,          // Manually entered IP
    CACHE            // From cached discovery
}

/**
 * Stage of discovery process (keeping enum for new implementations)
 */
enum class DiscoveryStage {
    STARTING,
    N_UPNP_SEARCH,
    MDNS_SEARCH,
    VALIDATING,
    COMPLETED,
    FAILED
}
