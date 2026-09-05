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
    // Die zwei, die es wirklich gibt - je ein Erzeuger in `OfficialHueDiscoveryService`.
    ONLINE_DISCOVERY, MDNS
    // ENTFERNT (nach v1.39.5): N_UPNP hatte KEINEN Erzeuger - die fruehere Notiz "die drei ...
    // je ein Erzeuger" war falsch. Verdeckt hat das eine Namensaehnlichkeit: die N-UPnP-Suche
    // meldet sich als ONLINE_DISCOVERY und haelt ihren Fortschritt als Zeichenkette
    // "N_UPNP_SEARCH" fest - die gehoert zum ANDEREN Enum [DiscoveryStage]. [DiscoveryStatus] ist
    // reiner UI-Zustand (nicht @Serializable), und [DiscoveryStatus.method] hat keinen Leser.
    // ENTFERNT (v1.34.3): LOCAL_NETWORK und IP_TEST waren im Code selbst als deprecated markiert,
    // MANUAL und CACHE hatten nie einen Erzeuger. Ein `when` ueber die Werte gibt es nicht,
    // Exhaustiveness konnte also nicht brechen. MANUAL beschrieb eine manuelle IP-Eingabe, die es
    // in der Oberflaeche nicht gibt - vor dem Loeschen geprueft.
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
