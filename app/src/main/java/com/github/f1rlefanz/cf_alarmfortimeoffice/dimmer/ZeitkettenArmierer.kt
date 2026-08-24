package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Armiert die beiden Zeitketten neu — **Dimmer zuerst, dann DND**.
 *
 * WARUM ES DAS GEBEN MUSS: Dieselbe Handvoll Zeilen stand bis v1.34.3 an **fünf** Stellen von Hand
 * (`DimmerViewModel`, `DimmerRulesViewModel`, `TagFreigabeUseCase`, `ShiftViewModel`,
 * `ConfigBackupUseCase`) — jede mit eigenem Log-Präfix, zwei davon wortgleich. Sie tragen aber
 * keine Formalie, sondern eine belastbare Zusicherung, und wer eine davon anfasst, lässt die
 * übrigen driften.
 *
 * DIE REIHENFOLGE IST DIE ZUSICHERUNG. „Nicht stören" hat im Modus „folgt dem Dimmer" keine eigene
 * Fensterquelle — es liest die Dimm-Zeitleiste LIVE über
 * [DimScheduleUseCase.previewTimelineWithStatus]. `dataStore.edit {}` kehrt erst nach persistiertem
 * Write zurück, das anschliessende DND-`enable()` sieht den neuen Stand also nur, wenn der Dimmer
 * vorher dran war. Am Fairphone gemessen (23.08.2026), als der Nachzug ganz fehlte: knapp drei
 * Stunden „Nicht stören" ohne Grund, weil DND auf dem alten Plan stehen blieb. In einer
 * Rufbereitschaftsnacht sind das verlorene Anrufe.
 *
 * [NonCancellable], weil hier ein Zustand HERGESTELLT wird. Mehrere Aufrufer hängen an einem
 * `viewModelScope`, und genau der stirbt, wenn der Nutzer die App direkt nach einer Änderung
 * verlässt — der Prefs-Wert wäre geschrieben, die Ketten hingen hinterher. Genau diese Falle hat
 * schon einmal eine Slider-Entprellung gerissen (Hergang im Skill `cfalarm-dimmer-und-dnd`).
 *
 * JEDER AUFRUF EINZELN GEFANGEN: Ein Fehlschlag der einen Kette darf die andere nicht mitreissen,
 * und beide tragen ihren eigenen Master-Pause-Backstop — sie sind also auch bei aktiver Pause
 * gefahrlos. Ein Fehlschlag hier darf ausserdem nie den auslösenden Vorgang (Import, Freigabe,
 * Umbenennung) als gescheitert melden: der ist zu diesem Zeitpunkt längst geschrieben.
 *
 * `dagger.Lazy`, weil `DndScheduleUseCase` seinerseits am Dimmer hängt — zyklusfrei, aber der Graph
 * soll die Kette erst bauen, wenn wirklich nacharmiert wird.
 */
@Singleton
class ZeitkettenArmierer @Inject constructor(
    private val dimSchedule: dagger.Lazy<DimScheduleUseCase>,
    private val dndSchedule: dagger.Lazy<DndScheduleUseCase>
) {
    /**
     * [anlass] steht als Präfix in der WARN-Zeile und ist die einzige Spur, aus der sich später
     * ableiten lässt, WELCHE Änderung ihre Kette nicht nachgezogen bekam. Kurz halten und in
     * Grossbuchstaben, wie die bisherigen Präfixe (`IMPORT`, `FREIGABE`, `UMBENENNUNG`).
     *
     * [dimmer] und [dnd] einzeln schaltbar: Ändert sich ausschliesslich eine DND-eigene Namensliste
     * (Rufbereitschaft, Dienstzeit-Ausnahmen), wäre ein Dimmer-`enable()` eine vollständige
     * Fensterberechnung ohne jede Wirkung. Umgekehrt gilt das NICHT — ein geändertes Dimm-Fenster
     * zieht DND immer mit.
     */
    suspend fun armiere(anlass: String, dimmer: Boolean = true, dnd: Boolean = true) {
        if (!dimmer && !dnd) return
        withContext(NonCancellable) {
            if (dimmer) {
                runCatching { dimSchedule.get().enable() }
                    .onFailure { Logger.w(LogTags.DIMMER, "⚠️ $anlass: Dimmer-Kette nicht neu armiert", it) }
            }
            if (dnd) {
                runCatching { dndSchedule.get().enable() }
                    .onFailure { Logger.w(LogTags.DND, "⚠️ $anlass: DND-Kette nicht neu armiert", it) }
            }
        }
    }
}
