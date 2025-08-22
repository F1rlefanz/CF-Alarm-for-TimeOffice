package com.github.f1rlefanz.cf_alarmfortimeoffice.model

/**
 * Data class representing the current alarm skip state.
 * Used for persisting and tracking which alarm should be skipped.
 */
data class AlarmSkipState(
    val isNextAlarmSkipped: Boolean = false,
    val skippedAlarmId: Int? = null,
    val skipActivatedAt: Long = 0L,
    val skipReason: String = "Manuell übersprungen"
)
