package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data

import androidx.compose.runtime.Immutable
import com.google.gson.annotations.SerializedName

/**
 * Represents a Philips Hue Light
 * @Immutable annotation optimizes Compose performance
 */
@Immutable
data class HueLight(
    val id: String,
    val name: String,
    val type: String,
    val modelid: String?,
    val manufacturername: String?,
    val productname: String?,
    val state: LightState,
    val uniqueid: String
)

/**
 * Der Zustand einer Lampe, wie die Bridge ihn liefert.
 *
 * Abgebildet wird nur, was auch GELESEN wird — dieselbe Regel wie bei [HueScene] und
 * `HueSceneDto`: Gson ignoriert alle uebrigen Schluessel der Antwort von sich aus, und ein Feld
 * ohne Leser sieht spaeter wie eine vorhandene Faehigkeit aus. Einziger Leser ist die
 * Ziel-Auswahl ("An"/"Aus" je Lampe, `ZielAuswahlInhalt`).
 *
 * ENTFERNT (14.09.2026): `bri`, `hue`, `sat`, `xy`, `ct`, `alert`, `effect`, `transitiontime`,
 * `reachable`. Die Bridge sendet sie weiter, im ganzen Baum las sie niemand. Die App STELLT
 * Helligkeit und Farbe (ueber die Roh-Map in `HueLightRepository.controlLight`), sie fragt sie
 * nie ab. Wer eines davon spaeter braucht, holt es zurueck — dann mit Leser.
 */
@Immutable
data class LightState(
    val on: Boolean
)

/**
 * Update light state request
 */
@Immutable
data class LightStateUpdate(
    val on: Boolean? = null,
    val bri: Int? = null,
    val hue: Int? = null,
    val sat: Int? = null,
    val xy: List<Float>? = null,
    val ct: Int? = null,
    val alert: String? = null,
    val effect: String? = null,
    val transitiontime: Int? = null,
    @SerializedName("bri_inc") val briInc: Int? = null,
    @SerializedName("sat_inc") val satInc: Int? = null,
    @SerializedName("hue_inc") val hueInc: Int? = null,
    @SerializedName("ct_inc") val ctInc: Int? = null,
    @SerializedName("xy_inc") val xyInc: List<Float>? = null
)
