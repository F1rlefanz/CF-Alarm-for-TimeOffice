package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data

import androidx.compose.runtime.Immutable

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

// Raumtyp bei Bedarf als EIN Feld mit @SerializedName("class").

/**
 * Group State - aggregated state of all lights in group.
 */
@Immutable
data class GroupState(
    val any_on: Boolean // True if any light in group is on
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
