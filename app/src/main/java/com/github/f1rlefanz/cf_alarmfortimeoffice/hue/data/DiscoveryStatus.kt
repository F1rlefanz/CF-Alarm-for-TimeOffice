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
    // Die zwei, die es wirklich gibt. Erzeuger gezaehlt in OfficialHueDiscoveryService:
    // ONLINE_DISCOVERY an vier Stellen (60, 113, 141, 156), MDNS an fuenf (74, 92, 156, 167, 184).
    ONLINE_DISCOVERY, MDNS
    // ENTFERNT (v1.34.3): LOCAL_NETWORK und IP_TEST waren im Code selbst als deprecated markiert,
    // MANUAL und CACHE hatten nie einen Erzeuger. Ein `when` ueber die Werte gibt es nicht,
    // Exhaustiveness konnte also nicht brechen. MANUAL beschrieb eine manuelle IP-Eingabe, die es
    // in der Oberflaeche nicht gibt - vor dem Loeschen geprueft.
    // ENTFERNT (Runde 18): N_UPNP - und zwar mit einem ANDEREN Hergang als die vier oben, denn
    // einen Erzeuger hatte es sehr wohl: HueNUpnpDiscoveryService setzte
    // `discoveryMethod = DiscoveryMethod.N_UPNP`, bis 5a48374 (25.08.2026) das Feld
    // `HueBridge.discoveryMethod` entfernte. Genau dieser Commit schrieb die Zeile "Die drei, die
    // es wirklich gibt - je ein Erzeuger" - sie war bei der Geburt veraltet UND zaehlte falsch.
    // Deshalb steht oben jetzt eine nachpruefbare Zahl statt einer Behauptung.
    // NICHT zu verwechseln mit [DiscoveryStage.N_UPNP_SEARCH] und der gleichnamigen Zeichenkette:
    // die N-UPnP-Suche laeuft weiter (Phase 2, Cloud-Fallback), meldet sich aber als
    // ONLINE_DISCOVERY. Entfernt ist der Enum-Eintrag, nicht die Funktion.
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
