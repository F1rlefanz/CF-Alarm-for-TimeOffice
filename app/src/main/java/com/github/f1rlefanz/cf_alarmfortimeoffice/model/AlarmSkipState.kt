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
    val skippedAlarmTriggerTime: Long = 0L,
    /**
     * Vollstaendiger Schnappschuss des uebersprungenen Weckers als JSON - NUR bei einem MANUELL
     * gestellten Wecker gesetzt, sonst null.
     *
     * Warum: Ueberspringen loescht den Alarm hart (System-Alarm gecancelt, Eintrag geloescht), und
     * dabei muss es bleiben - der Rest der App verlaesst sich an mehreren Stellen darauf, dass ein
     * uebersprungener Alarm WEG ist (Direct-Boot-Spiegel, Hue-Tagesplanung). Ein kalenderbasierter
     * Wecker entsteht beim "Aufheben" aus dem Kalenderstand neu; ein manueller hat keine solche
     * Quelle - fuer ihn ist dieser Schnappschuss die einzige Moeglichkeit, das versprochene
     * "Aufheben" einzuloesen.
     *
     * Format: `AlarmInfoData` (kotlinx.serialization), dasselbe wie im Alarm-Bestand - siehe
     * `ManualAlarmSnapshot`.
     */
    val skippedManualAlarm: String? = null
)
