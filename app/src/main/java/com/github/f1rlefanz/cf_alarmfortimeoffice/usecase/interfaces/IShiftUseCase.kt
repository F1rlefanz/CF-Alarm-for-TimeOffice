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
 * ein separates Ja/Nein war eine zweite Wahrheit ohne Leser.
 *
 * ENTFERNT (01.10.2026): `resetToDefaults()` samt `IShiftConfigRepository.resetToDefaults()` -
 * ohne Aufrufer und fuer den Knopf "Auf Standardwerte zuruecksetzen" UNGEEIGNET: es schrieb
 * `ShiftConfig.getDefaultConfig()` mit `autoAlarmEnabled = true` (hoebe eine bewusste Pause der
 * automatischen Alarme auf) und fasste die System-Alarme nicht an. Der Knopf geht ueber
 * `resetToDefaultsPreservingAutoAlarm()` + `ShiftViewModel.updateShiftConfig()`.
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
}
