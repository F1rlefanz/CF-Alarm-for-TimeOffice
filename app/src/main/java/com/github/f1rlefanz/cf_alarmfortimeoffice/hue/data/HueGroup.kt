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
    val lights: List<String>, // List of light IDs in this group
    val state: GroupState
)

// Raumtyp bei Bedarf als EIN Feld mit @SerializedName("class").

/**
 * Group State - aggregated state of all lights in group.
 */
@Immutable
data class GroupState(
    val any_on: Boolean // True if any light in group is on
)
