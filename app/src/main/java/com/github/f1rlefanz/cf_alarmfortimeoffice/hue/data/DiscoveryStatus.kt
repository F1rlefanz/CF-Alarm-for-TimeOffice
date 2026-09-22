package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data

import androidx.compose.runtime.Immutable

/**
 * Fortschrittsmeldung der Bridge-Suche. Erzeugt an acht Stellen in
 * [com.github.f1rlefanz.cf_alarmfortimeoffice.hue.discovery.OfficialHueDiscoveryService],
 * gelesen in `AnimatedDiscoveryCard` und `HueTabContent`.
 * @Immutable annotation optimizes Compose performance
 *
 * ENTFERNT (14.09.2026): `duration`, `foundBridges`, `isError`. Keinen der drei las irgendeine
 * Stelle im Baum; `duration` wurde ausserdem nie gesetzt und stand auf jeder Meldung auf 0L.
 * Der Fehlerfall steht weiterhin in [stage] ("FAILED") und [message], die beide GELESEN werden —
 * `isError` war eine zweite, stille Wahrheit daneben.
 *
 * [method] bleibt vorerst, OBWOHL sie ebenfalls keinen Leser hat: sie ist der einzige Verwender
 * des Enums [DiscoveryMethod], und dessen Schicksal gehoert in den offenen Blickwinkel
 * "Enum-TYPEN ohne Verwender" (Issue #72), nicht in diese Runde.
 */
@Immutable
data class DiscoveryStatus(
    val method: DiscoveryMethod,
    val stage: String, // Changed from enum to String for backward compatibility
    val message: String,
    val progress: Float = 0f, // 0.0 to 1.0
    val isComplete: Boolean = false,
    val currentMethod: String? = null // Current discovery method being used
)

/**
 * Discovery method being used (Enhanced 2025 Edition)
 */
enum class DiscoveryMethod {
    // ERZEUGER, ausgezaehlt am 22.09.2026: von den acht `emit(DiscoveryStatus(`-Stellen in
    // OfficialHueDiscoveryService setzen VIER ONLINE_DISCOVERY und FUENF MDNS (eine davon
    // waehlt zur Laufzeit zwischen beiden) - N_UPNP setzt KEINE. Hier stand bis dahin das
    // Gegenteil ("je ein Erzeuger"); wer es zurueckschreibt, schreibt eine Messung um.
    // Die N-UPnP-Phase selbst gibt es sehr wohl: sie meldet sich ueber `stage = "N_UPNP_SEARCH"`
    // und `currentMethod = "N-UPnP"` und traegt dabei `method = ONLINE_DISCOVERY`. Ob deshalb
    // der Eintrag weg soll oder die Phase ihn setzen sollte, ist Issue #19 - keine Aufraeumfrage.
    ONLINE_DISCOVERY, N_UPNP, MDNS
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
