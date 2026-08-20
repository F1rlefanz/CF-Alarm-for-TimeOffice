package com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import kotlinx.coroutines.flow.Flow

/**
 * Interface für Alarm Repository Operations
 * 
 * TESTING IMPROVEMENT: Interface ermöglicht Mock-Implementierungen
 * - Dependency Inversion: Abstraktion statt konkrete Implementierung
 * - Testbarkeit: UseCase/ViewModel kann mit Mock-Repository getestet werden
 * - Flexibilität: Implementierung austauschbar (Database/SharedPrefs/InMemory)
 * 
 * REACTIVE ENHANCEMENT: Added activeAlarms Flow for immediate UI updates
 */
interface IAlarmRepository {
    
    /**
     * REACTIVE: Flow of active alarms for immediate UI updates
     * 
     * @return Flow that emits current alarm list whenever it changes
     */
    val activeAlarms: Flow<List<AlarmInfo>>
    
    /**
     * Speichert oder aktualisiert eine Alarm-Information
     * 
     * @param alarmInfo Alarm-Information die gespeichert werden soll
     * @return Result mit Erfolgs- oder Fehlerinformation
     */
    suspend fun saveAlarm(alarmInfo: AlarmInfo): Result<Unit>

    /**
     * Ist die Persistenz fuer diesen Prozess gesperrt, weil der Bestand nicht lesbar war?
     *
     * Braucht ein Aufrufer, der GANZ raeumen will: nach einem gescheiterten Init-Load ist der
     * Cache auf eine leere Liste degradiert, und [getAllAlarms] meldet das NICHT als Fehler (es
     * liefert den degradierten Stand als Erfolg). Wer sich darauf verlaesst, cancelt keinen
     * einzigen System-Alarm, leert aber Store und Direct-Boot-Spiegel - und laesst genau die
     * verwaisten, armierten Alarme zurueck, von denen die App danach nichts mehr weiss.
     *
     * ANTWORTET NICHT auf "war der letzte Schreibvorgang erfolgreich?" - dafuer gibt es
     * [istLetzterSchreibvorgangGescheitert]. Die beiden Lagen duerfen nicht zu einem Signal
     * verschmelzen (siehe die Begruendung dort).
     */
    suspend fun isPersistenceBlocked(): Boolean

    /**
     * Ist der zuletzt geschriebene Stand moeglicherweise NUR im Arbeitsspeicher gelandet?
     *
     * Der zweite Weg dorthin: der Schreibweg selbst wirft (voller Speicher, IOException,
     * beschaedigte Datei). [saveAlarm] meldet trotzdem Erfolg - der Alarm wird armiert und
     * klingelt in diesem Prozess -, aber er steht weder in der Preferences-Datei noch im
     * Direct-Boot-Spiegel und ist nach Prozesstod oder Neustart weg.
     *
     * NUR FUER ANZEIGE UND WARNUNG: einziger Konsument ist der manuelle Wecker
     * (`AlarmViewModel.createManualAlarm()`), weil ausgerechnet der sich nicht aus dem Kalender
     * rekonstruieren laesst. Diese Antwort darf NIE einen Raeum- oder Cancel-Weg anhalten - der
     * Bestand ist hier vollstaendig lesbar, und ein uebersprungenes `cancelSystemAlarm()` liesse
     * armierte Alarme zurueck, die niemand mehr abbrechen kann.
     *
     * Beschreibt den LETZTEN Versuch: ein gelungener Schreibvorgang hebt die Antwort wieder auf.
     */
    suspend fun istLetzterSchreibvorgangGescheitert(): Boolean

    /**
     * Lädt alle gespeicherten Alarm-Informationen
     * 
     * @return Result mit Liste aller Alarme oder Fehler
     */
    suspend fun getAllAlarms(): Result<List<AlarmInfo>>
    
    /**
     * Lädt spezifische Alarm-Information anhand der ID
     * 
     * @param alarmId Eindeutige ID des Alarms
     * @return Result mit AlarmInfo oder Fehler wenn nicht gefunden
     */
    suspend fun getAlarmById(alarmId: Int): Result<AlarmInfo?>
    
    /**
     * Löscht einen Alarm anhand der ID
     * 
     * @param alarmId Eindeutige ID des zu löschenden Alarms
     * @return Result mit Erfolgs- oder Fehlerinformation
     */
    suspend fun deleteAlarm(alarmId: Int): Result<Unit>
    
    /**
     * Löscht alle gespeicherten Alarme
     * 
     * @return Result mit Erfolgs- oder Fehlerinformation
     */
    suspend fun deleteAllAlarms(): Result<Unit>
    
    /**
     * Prüft ob ein Alarm mit der angegebenen ID existiert
     * 
     * @param alarmId Eindeutige ID des Alarms
     * @return Result mit Boolean (true wenn vorhanden) oder Fehler
     */
    suspend fun alarmExists(alarmId: Int): Result<Boolean>
}
