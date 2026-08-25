package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data

import androidx.compose.runtime.Immutable
import com.google.gson.annotations.SerializedName

/**
 * Represents a Philips Hue Group (Room, Zone, Entertainment Area)
 * @Immutable annotation optimizes Compose performance
 */
@Immutable
data class HueGroup(
    val id: String,
    val name: String,
    val type: String, // "Room", "Zone", "Entertainment"
    val lights: List<String>, // List of light IDs in this group
    val sensors: List<String>? = null, // List of sensor IDs
    val state: GroupState,
    val action: GroupAction,
    val recycle: Boolean? = null
)

// KEIN Raumtyp mehr (bis v1.34.3 `roomClass` + `classField`): Die Bridge sendet ihn unter dem
// JSON-Schluessel `class`, und `classField` trug ihn per @SerializedName auch herein - gelesen hat
// ihn NIE jemand. `roomClass` daneben hatte gar kein @SerializedName und war deshalb IMMER null;
// zwei Kopierzeilen im Repository schoben folglich null nach null. Ein Feld, das aussieht, als
// truege es den Raumtyp, und immer leer ist, ist schlimmer als keines. Wer ihn spaeter braucht,
// nimmt EIN Feld mit @SerializedName("class") - nicht diese zwei.

/**
 * Group State - aggregated state of all lights in group
 */
@Immutable
data class GroupState(
    val any_on: Boolean, // True if any light in group is on
    val all_on: Boolean  // True if all lights in group are on
)

/**
 * Group Action - last action applied to group
 */
@Immutable
data class GroupAction(
    val on: Boolean,
    val bri: Int? = null,
    val hue: Int? = null,
    val sat: Int? = null,
    val xy: List<Float>? = null,
    val ct: Int? = null,
    val alert: String? = null,
    val effect: String? = null,
    val transitiontime: Int? = null
)

/**
 * Group action update request
 */
@Immutable
data class GroupUpdate(
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
