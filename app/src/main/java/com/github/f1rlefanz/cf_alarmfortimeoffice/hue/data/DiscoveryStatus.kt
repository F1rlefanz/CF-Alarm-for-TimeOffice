package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data

import androidx.compose.runtime.Immutable

/**
 * Fortschrittsmeldung der Bridge-Suche. Erzeugt an acht Stellen in
 * [com.github.f1rlefanz.cf_alarmfortimeoffice.hue.discovery.OfficialHueDiscoveryService],
 * gelesen in `AnimatedDiscoveryCard` und `HueTabContent`. Der Fehlerfall steht in [stage]
 * ("FAILED") und [message].
 */
@Immutable
data class DiscoveryStatus(
    // Zeichenkette, gesetzt in `OfficialHueDiscoveryService`, gelesen in `AnimatedDiscoveryCard`.
    val stage: String,
    val message: String,
    val progress: Float = 0f, // 0.0 to 1.0
    val isComplete: Boolean = false,
    val currentMethod: String? = null
)
