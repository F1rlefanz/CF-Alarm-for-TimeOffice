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
    val capabilities: LightCapabilities? = null,
    val uniqueid: String
)

/**
 * Light State
 */
@Immutable
data class LightState(
    val on: Boolean,
    val bri: Int = 254, // Brightness 1-254
    val hue: Int? = null, // Hue 0-65535
    val sat: Int? = null, // Saturation 0-254
    val xy: List<Float>? = null, // CIE color space coordinates
    val ct: Int? = null, // Color temperature 153-500
    val alert: String = "none", // "none", "select", "lselect"
    val effect: String = "none", // "none", "colorloop"
    val transitiontime: Int? = null, // Transition time in 100ms
    val reachable: Boolean = true
)

/**
 * Light Capabilities
 */
@Immutable
data class LightCapabilities(
    val certified: Boolean,
    val control: Control? = null,
    val streaming: Streaming? = null
)

@Immutable
data class Control(
    val mindimlevel: Int? = null,
    val maxlumen: Int? = null,
    val colorgamuttype: String? = null,
    val colorgamut: List<List<Float>>? = null,
    val ct: ColorTemperatureRange? = null
)

@Immutable
data class ColorTemperatureRange(
    val min: Int,
    val max: Int
)

@Immutable
data class Streaming(
    val renderer: Boolean,
    val proxy: Boolean
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
