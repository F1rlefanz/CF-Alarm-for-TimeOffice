package com.github.f1rlefanz.cf_alarmfortimeoffice.model

/**
 * Data class representing the current alarm skip state.
 * Used for persisting and tracking which alarm should be skipped.
 */
data class AlarmSkipState(
    val isNextAlarmSkipped: Boolean = false,
    val skippedAlarmId: Int? = null,
    val skipActivatedAt: Long = 0L,
    val skipReason: String = "Manuell übersprungen",
    // Urspruengliche triggerTime des uebersprungenen Alarms. Basis fuer clearExpiredSkip():
    // sobald dieser Zeitpunkt verstrichen ist, hat der Skip seinen Zweck erfuellt und das Flag
    // wird automatisch zurueckgesetzt - unabhaengig davon, ob der zugehoerige System-Alarm je
    // feuert (er wird beim Ueberspringen sofort geloescht, siehe AlarmSkipUseCase.skipNextAlarm).
    val skippedAlarmTriggerTime: Long = 0L
)
