package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data

import androidx.compose.runtime.Immutable

/**
 * Eine gefundene Hue-Bridge. Erzeugt wird sie von Hand in den Discovery-Diensten
 * ([com.github.f1rlefanz.cf_alarmfortimeoffice.hue.discovery.HueMdnsDiscoveryService],
 * [com.github.f1rlefanz.cf_alarmfortimeoffice.hue.discovery.HueNUpnpDiscoveryService]) — NICHT
 * von Gson; die Felder sind also kein Drahtformat, sondern genau das, was die App fuehrt.
 * @Immutable annotation optimizes Compose performance by preventing unnecessary recompositions
 *
 * Die Erreichbarkeit fuehrt das Modell bewusst nicht - darueber urteilt
 * `HueBridgeConnectionManager.probeBridge()` mit einem echten Request.
 */
@Immutable
data class HueBridge(
    val id: String,
    val ipAddress: String,
    val name: String? = null
) {
    val internalipaddress: String
        get() = ipAddress
}

/**
 * Antwort von `GET /api/<user>/config` bzw. `GET /api/config`.
 *
 * Abgebildet wird nur, was auch GELESEN wird (Regel wie bei [HueScene]): [bridgeid] ist der
 * geraeteuebergreifende Anker der Bridge, [mac] der Rueckfall dafuer. Beide werden in
 * `HueApiClient.getBridgeConfig` geprueft — ueber nullable Zwischenwerte, denn Gson erzwingt
 * die Non-Null-Deklarationen hier NICHT.
 */
@Immutable
data class HueBridgeConfig(
    val mac: String,
    val bridgeid: String
)
