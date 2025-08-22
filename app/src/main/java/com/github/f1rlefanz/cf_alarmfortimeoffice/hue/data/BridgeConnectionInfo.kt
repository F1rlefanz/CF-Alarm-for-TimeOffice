package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data

import androidx.compose.runtime.Immutable

/**
 * Bridge Connection Information für UI State
 * @Immutable annotation für Compose Performance-Optimierung
 */
@Immutable
data class BridgeConnectionInfo(
    val isConnected: Boolean = false,
    val bridgeIp: String? = null,
    val bridgeName: String? = null,
    val username: String? = null,
    val lastValidated: Long? = null
)
