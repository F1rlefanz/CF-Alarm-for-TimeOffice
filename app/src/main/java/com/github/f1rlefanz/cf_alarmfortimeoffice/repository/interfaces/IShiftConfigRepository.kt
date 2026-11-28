package com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import kotlinx.coroutines.flow.Flow

/**
 * Interface für Shift Configuration Repository Operations
 */
interface IShiftConfigRepository {
    
    /**
     * Flow für reaktive Beobachtung der Shift-Konfiguration
     */
    val shiftConfig: Flow<ShiftConfig>
    
    /**
     * Speichert oder aktualisiert die Shift-Konfiguration
     */
    suspend fun saveShiftConfig(config: ShiftConfig): Result<Unit>

    /**
     * Ändert die gespeicherte Konfiguration in EINER Transaktion (#250): lesen, [transform], schreiben.
     *
     * WARUM ES DAS GIBT: [saveShiftConfig] schreibt eine ganze Konfiguration, die der Aufrufer
     * vorher gelesen hat. Ein Schreiber im Hintergrund (Ziel-Abgleich der Lichtregeln beim
     * Bridge-Kontakt oder Import) überschriebe so einen dazwischen gespeicherten Stand. Hier liest
     * die Transaktion selbst, strikt und frisch aus dem Speicher (nicht aus dem Cache); ein
     * gleichzeitiger zweiter Aufruf wartet, bis der erste geschrieben hat.
     *
     * - Unlesbar (defekt, vor der ersten Entsperrung) = Fehlschlag, es wird NICHTS geschrieben.
     *   Noch nie konfiguriert = die Standardkonfiguration ist die Grundlage, wie bei
     *   [getCurrentShiftConfig].
     * - Liefert [transform] eine gleiche Konfiguration, wird nicht geschrieben.
     * - [transform] läuft innerhalb der Transaktion und sperrt den Speicher: keine Netz- oder
     *   Bridge-Aufrufe darin. Eine Ablehnung gibt die unveränderte Konfiguration zurück und hält
     *   ihren Grund selbst fest, statt zu werfen.
     *
     * @return die danach gespeicherte Konfiguration.
     *
     * Der Rumpf scheitert absichtlich (Muster `TypLichtQuelle.Leser.NICHT_VERBUNDEN`): Test-Fakes
     * brauchen ihn nicht zu kennen, und eine echte Umsetzung, die ihn vergisst, schreibt laut nichts
     * statt still Lesen-dann-Speichern zu machen.
     */
    suspend fun updateShiftConfig(transform: suspend (ShiftConfig) -> ShiftConfig): Result<ShiftConfig> =
        Result.failure(UnsupportedOperationException("updateShiftConfig ist hier nicht umgesetzt"))
    
    /**
     * Lädt die aktuelle Shift-Konfiguration (einmalig)
     */
    suspend fun getCurrentShiftConfig(): Result<ShiftConfig>
}
