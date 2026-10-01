package com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftMatch
import kotlinx.coroutines.flow.Flow

/**
 * Interface für Shift UseCase Operations
 *
 * ENTFERNT (Aufraeumrunde 24): `hasValidConfig` - im ganzen Baum ohne Aufrufstelle, ein reines
 * Durchreichen an `IShiftConfigRepository.hasValidConfig()`, das es inzwischen ebenfalls nicht
 * mehr gibt (auch dort ohne Aufrufer). Wer wissen will, ob eine
 * Konfiguration taugt, liest sie mit [getCurrentShiftConfig] und sieht ihre Definitionen an;
 * ein separates Ja/Nein war eine zweite Wahrheit ohne Leser. Nicht zu verwechseln mit
 * [resetToDefaults], das ebenfalls ohne Verwender ist - fuer den Knopf aber UNGEEIGNET, siehe dort.
 */
interface IShiftUseCase {
    
    /**
     * Flow für reaktive Beobachtung der Schicht-Konfiguration
     */
    val shiftConfig: Flow<ShiftConfig>
    
    /**
     * Speichert oder aktualisiert die Schicht-Konfiguration
     */
    suspend fun saveShiftConfig(config: ShiftConfig): Result<Unit>
    
    /**
     * Lädt die aktuelle Schicht-Konfiguration (einmalig)
     */
    suspend fun getCurrentShiftConfig(): Result<ShiftConfig>

    /**
     * Erkennt Schichten in Kalender-Events basierend auf der aktuellen Konfiguration
     */
    suspend fun recognizeShiftsInEvents(events: List<CalendarEvent>): Result<List<ShiftMatch>>
    
    /**
     * Setzt die Schicht-Konfiguration auf Standardwerte zurück
     *
     * OHNE VERWENDER - und NICHT an den Knopf anschliessen. Den Knopf "Auf Standardwerte
     * zurücksetzen" gibt es (ShiftConfigScreen); er geht bewusst an dieser Funktion
     * vorbei (`resetToDefaultsPreservingAutoAlarm()` + `ShiftViewModel.updateShiftConfig()`).
     * Denn hier wird `ShiftConfig.getDefaultConfig()` geschrieben, und die traegt
     * `autoAlarmEnabled = true`: das hoebe eine bewusste Pause der automatischen Alarme auf und
     * zoege sofort wieder Wecker nach - ausserdem fasste es die System-Alarme nicht an. Ein
     * Kandidat zum Entfernen (samt `IShiftConfigRepository.resetToDefaults()`), sobald das
     * jemand ausdruecklich angeht.
     */
    suspend fun resetToDefaults(): Result<Unit>
}
