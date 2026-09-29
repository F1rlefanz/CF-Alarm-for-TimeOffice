package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data

import androidx.compose.runtime.Immutable

/**
 * Fortschrittsmeldung der Bridge-Suche. Erzeugt an acht Stellen in
 * [com.github.f1rlefanz.cf_alarmfortimeoffice.hue.discovery.OfficialHueDiscoveryService],
 * gelesen in `AnimatedDiscoveryCard` und `HueTabContent`. Der Fehlerfall steht in [stage]
 * ("FAILED") und [message].
 *
 * [method] bleibt vorerst, OBWOHL sie ebenfalls keinen Leser hat: sie ist der einzige Verwender
 * des Enums [DiscoveryMethod]. Der Blickwinkel "Enum-TYPEN ohne Verwender" (Issue #72) hat das
 * gemessen und NICHT mitgeschnitten: [DiscoveryMethod] hat mit dieser Zeile und den acht
 * Erzeugerstellen in `OfficialHueDiscoveryService` (neun Nennungen — Zeile 155 waehlt ternaer)
 * sehr wohl Verwender, ist also kein Fund jenes Blickwinkels.
 * Tot ist die ganze KETTE (nur geschrieben, nie gelesen) — das ist eine andere Messung und
 * steht als eigenes Issue (#116).
 */
@Immutable
data class DiscoveryStatus(
    val method: DiscoveryMethod,
    // Zeichenkette, gesetzt in `OfficialHueDiscoveryService`, gelesen in `AnimatedDiscoveryCard`.
    val stage: String,
    val message: String,
    val progress: Float = 0f, // 0.0 to 1.0
    val isComplete: Boolean = false,
    val currentMethod: String? = null
)

/**
 * Discovery method being used (Enhanced 2025 Edition)
 */
enum class DiscoveryMethod {
    // Die drei, die es wirklich gibt - je ein Erzeuger in den Discovery-Diensten.
    ONLINE_DISCOVERY, N_UPNP, MDNS
}
