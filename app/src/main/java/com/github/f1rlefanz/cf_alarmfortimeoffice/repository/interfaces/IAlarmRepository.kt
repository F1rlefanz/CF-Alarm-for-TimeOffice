package com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import kotlinx.coroutines.flow.Flow

/**
 * Interface für Alarm Repository Operations
 */
interface IAlarmRepository {
    
    /** Flow of active alarms for immediate UI updates */
    val activeAlarms: Flow<List<AlarmInfo>>
    
    /** Speichert oder aktualisiert eine Alarm-Information */
    suspend fun saveAlarm(alarmInfo: AlarmInfo): Result<Unit>

    /**
     * Ist die Persistenz fuer diesen Prozess gesperrt, weil der Bestand nicht lesbar war? Braucht,
     * wer GANZ raeumen will: [getAllAlarms] liefert den degradierten Stand als Erfolg.
     * NICHT "war der letzte Schreibvorgang erfolgreich?" ([istLetzterSchreibvorgangGescheitert]) -
     * nie verodern. Hergang: Skill cfalarm-persistenz-und-auth, reference/persistenz.md.
     */
    suspend fun isPersistenceBlocked(): Boolean

    /**
     * Ist der zuletzt geschriebene Stand moeglicherweise NUR im Arbeitsspeicher gelandet
     * ([saveAlarm] meldet trotzdem Erfolg)? NUR FUER ANZEIGE UND WARNUNG (manueller Wecker) - darf
     * NIE einen Raeum- oder Cancel-Weg anhalten. Beschreibt den LETZTEN Versuch.
     */
    suspend fun istLetzterSchreibvorgangGescheitert(): Boolean

    /** Lädt alle gespeicherten Alarm-Informationen */
    suspend fun getAllAlarms(): Result<List<AlarmInfo>>
    
    /** Löscht einen Alarm anhand der ID */
    suspend fun deleteAlarm(alarmId: Int): Result<Unit>
    
    /** Löscht alle gespeicherten Alarme */
    suspend fun deleteAllAlarms(): Result<Unit>
}
