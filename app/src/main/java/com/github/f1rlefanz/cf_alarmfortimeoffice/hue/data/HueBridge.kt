package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data

import androidx.compose.runtime.Immutable

/**
 * Eine gefundene Hue-Bridge. Erzeugt wird sie von Hand in den Discovery-Diensten
 * ([com.github.f1rlefanz.cf_alarmfortimeoffice.hue.discovery.HueMdnsDiscoveryService],
 * [com.github.f1rlefanz.cf_alarmfortimeoffice.hue.discovery.HueNUpnpDiscoveryService]) — NICHT
 * von Gson; die Felder sind also kein Drahtformat, sondern genau das, was die App fuehrt.
 * @Immutable annotation optimizes Compose performance by preventing unnecessary recompositions
 *
 * ENTFERNT (14.09.2026): `modelid`, `swversion`, `isReachable`. Keine der beiden Erzeugungs-
 * stellen setzte sie je, gelesen hat sie niemand — `isReachable` war damit auf JEDER Bridge
 * dauerhaft `false` und behauptete trotzdem, die Erreichbarkeit zu fuehren. Die gilt es hier
 * gar nicht zu fuehren: darueber urteilt `HueBridgeConnectionManager.probeBridge()` mit einem
 * echten Request. Mit den Feldern ist die "Enhanced Features"-Liste dieses KDoc gefallen; sie
 * zaehlte vier Faehigkeiten auf, von denen drei nie ein Feld hatten.
 */
@Immutable
data class HueBridge(
    val id: String,
    val ipAddress: String, // Renamed from internalipaddress for clarity
    val name: String? = null
) {
    // Legacy compatibility property
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
 *
 * ENTFERNT (14.09.2026): `name`, `datastoreversion`, `swversion`, `apiversion`, `factorynew`,
 * `replacesbridgeid`, `modelid` — die Bridge sendet sie weiter, gelesen hat sie niemand. Der
 * angezeigte Bridge-Name kommt aus [HueBridge.name] und damit aus der Discovery, nicht von hier.
 */
@Immutable
data class HueBridgeConfig(
    val mac: String,
    val bridgeid: String
)

/**
 * Bridge discovery response
 */
@Immutable
data class BridgeDiscoveryResponse(
    val id: String,
    val internalipaddress: String
)
