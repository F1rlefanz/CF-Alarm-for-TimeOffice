package com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftMatch
import kotlinx.coroutines.flow.Flow

/**
 * Interface für Shift UseCase Operations
 * 
 * TESTING IMPROVEMENT: Interface ermöglicht Mock-Implementierungen
 * - Dependency Inversion: ViewModel abhängig von Abstraktion
 * - Testbarkeit: ViewModel kann mit Mock-UseCase getestet werden
 * - Business Logic Separation: Kapselt Shift-spezifische Geschäftslogik
 *
 * ENTFERNT (Aufraeumrunde 24): `hasValidConfig` - im ganzen Baum ohne Aufrufstelle, ein reines
 * Durchreichen an `IShiftConfigRepository.hasValidConfig()`. Wer wissen will, ob eine
 * Konfiguration taugt, liest sie mit [getCurrentShiftConfig] und sieht ihre Definitionen an;
 * ein separates Ja/Nein war eine zweite Wahrheit ohne Leser. Nicht zu verwechseln mit
 * [resetToDefaults], das bewusst ohne Verwender stehen bleibt - Begruendung dort.
 */
interface IShiftUseCase {
    
    /**
     * Flow für reaktive Beobachtung der Schicht-Konfiguration
     * 
     * @return Flow<ShiftConfig> der bei Änderungen automatisch emittiert
     */
    val shiftConfig: Flow<ShiftConfig>
    
    /**
     * Speichert oder aktualisiert die Schicht-Konfiguration
     * 
     * @param config Neue Schicht-Konfiguration
     * @return Result mit Erfolgs- oder Fehlerinformation
     */
    suspend fun saveShiftConfig(config: ShiftConfig): Result<Unit>
    
    /**
     * Lädt die aktuelle Schicht-Konfiguration (einmalig)
     * 
     * @return Result mit aktueller ShiftConfig oder Fehler
     */
    suspend fun getCurrentShiftConfig(): Result<ShiftConfig>
    
    
    
    
    /**
     * Erkennt Schichten in Kalender-Events basierend auf der aktuellen Konfiguration
     * 
     * @param events Liste der Kalender-Events
     * @return Result mit Liste der erkannten Schicht-Matches oder Fehler
     */
    suspend fun recognizeShiftsInEvents(events: List<CalendarEvent>): Result<List<ShiftMatch>>
    
    /**
     * Setzt die Schicht-Konfiguration auf Standardwerte zurück
     *
     * OHNE VERWENDER, und das bleibt so, bis jemand die Oberflaeche dazu baut: Im ganzen Baum
     * ruft diese Funktion niemand (Aufraeumrunde 24 hat es gemessen). Sie ist trotzdem KEINE
     * Altlast - drei Stellen im Produktivcode benennen `resetToDefaults()` ausdruecklich als
     * "den bewussten Weg zum Default", der "dem Nutzer gehoert"
     * (`ShiftConfigRepository`, `ShiftViewModel`, `CalendarViewModel`): Genau WEIL kein
     * Lesefehler mehr still auf die Standardkonfiguration zurueckfaellt, braucht es einen
     * ausdruecklichen Weg dorthin. Was fehlt, ist der Knopf, nicht die Funktion.
     *
     * @return Result mit Erfolgs- oder Fehlerinformation
     */
    suspend fun resetToDefaults(): Result<Unit>
}
