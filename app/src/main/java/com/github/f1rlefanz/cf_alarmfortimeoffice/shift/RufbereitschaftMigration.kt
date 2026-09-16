package com.github.f1rlefanz.cf_alarmfortimeoffice.shift

import android.content.Context
import android.os.UserManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Einmalige Uebernahme der alten Rufbereitschaft-Auswahl von "Nicht stoeren" (Namensliste
 * `dnd_oncall_shifts`, bis v1.40.8) in das Flag `ShiftDefinition.isOnCall`.
 *
 * WARUM ES SIE GEBEN MUSS: Wer vor dem Umbau "AD1" als Rufbereitschaft angeklickt hatte, verloere
 * ohne diese Uebernahme lautlos den DND-Cutoff - "Nicht stoeren" bliebe in der Nacht vor der
 * Rufbereitschaft ueber 05:00 hinaus an, und die neue stuendliche Kalender-Abfrage liefe an genau
 * dem Tag nicht, fuer den sie gebaut wurde. Beides waere ein Zustand, den der Nutzer nicht
 * bestellt hat.
 *
 * KEIN EIGENER MARKER: Der Altschluessel selbst ist der Marker. Solange er existiert, ist etwas
 * zu tun; geloescht wird er erst NACH belegtem Erfolg des Speicherns. Ein halb gelaufener Versuch
 * (Prozesstod zwischen Speichern und Loeschen) laeuft beim naechsten Anlass einfach noch einmal
 * und setzt dieselben Flags erneut - idempotent. Das macht auch den Import einer ALTEN
 * Konfigurationsdatei einfach: der schreibt den Schluessel wieder in den Store, und derselbe
 * Aufruf uebernimmt ihn.
 *
 * ENTSPERRUNGS-GATE wie bei `DimmerModellMigration`: vor der ersten Entsperrung liefert der
 * CE-Store still leere Preferences (siehe `AlarmRepository`). Eine Migration darueber saehe
 * "keine Altliste" und taete nichts - harmlos, aber der Fall soll gar nicht erst als "erledigt"
 * gelten. Und `getCurrentShiftConfig()` liefe auf denselben leeren Store.
 *
 * FAIL-SAFE: Scheitert der Konfigurations-Read oder das Speichern, wird NICHTS geloescht; der
 * naechste Anlass (App-Start, 6h-Wartung) versucht es erneut. Ein Lesefehler ist nie "leer".
 */
@Singleton
class RufbereitschaftMigration @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dndPrefs: DndPrefs,
    // BEWUSST der UseCase und NICHT das Repository - derselbe Grund wie in ConfigBackupUseCase:
    // nur IShiftUseCase.saveShiftConfig() invalidiert die Caches, ueber das Repository geschrieben
    // laese der naechste Erkennungslauf die ALTEN Definitionen weiter.
    private val shiftUseCase: IShiftUseCase
) {
    companion object {
        /**
         * REIN UND TESTBAR: setzt [ShiftDefinition.isOnCall] fuer jede Definition, deren Name in
         * [altAuswahl] steht. EXAKTER Vergleich - so hat auch `DndOnCallCutoffResolver` die Liste
         * immer gelesen; ein Eintrag mit anderer Schreibweise hat vorher nie getroffen und wird
         * hier nicht nachtraeglich scharf. Definitionen, die schon markiert sind, bleiben es.
         *
         * @return die neue Liste, oder `null`, wenn kein einziges Flag zu setzen war (dann wird
         *   nicht gespeichert - kein blindes Schreiben).
         */
        internal fun uebernehme(
            definitionen: List<ShiftDefinition>,
            altAuswahl: Set<String>
        ): List<ShiftDefinition>? {
            var geaendert = false
            val neu = definitionen.map { def ->
                if (!def.isOnCall && def.name in altAuswahl) {
                    geaendert = true
                    def.copy(isOnCall = true)
                } else {
                    def
                }
            }
            return if (geaendert) neu else null
        }

        /** Siehe `DimmerModellMigration.brauchtNachImportEineMigration` - gleiche Idee. */
        internal fun brauchtNachImportEineMigration(importierteSchluessel: Set<String>): Boolean =
            DndPrefs.LEGACY_ONCALL_KEY_NAME in importierteSchluessel
    }

    private fun nutzerEntsperrt(): Boolean =
        context.getSystemService(UserManager::class.java)?.isUserUnlocked ?: true

    /**
     * Uebernimmt die Altliste, falls vorhanden. Liefert `true`, wenn dabei eine Schichtdefinition
     * geaendert wurde - der Aufrufer armiert dann die DND-Kette und die Rufbereitschafts-Abfrage
     * neu, denn beide lesen das Flag.
     */
    suspend fun migriereEinmalig(): Boolean = withContext(NonCancellable) {
        try {
            if (!nutzerEntsperrt()) {
                Logger.w(LogTags.SHIFT_CONFIG, "Rufbereitschaft-Migration verschoben - Geraet noch nicht entsperrt")
                return@withContext false
            }
            val alt = dndPrefs.legacyOnCallShiftsNow() ?: return@withContext false

            if (alt.isEmpty()) {
                // Leere Liste: nichts zu uebernehmen, der Schluessel darf weg.
                dndPrefs.clearLegacyOnCallShifts()
                return@withContext false
            }

            // Ein gescheiterter Konfigurations-Read darf NIE zur leeren Definitionsliste werden -
            // getOrThrow, und der catch unten laesst die Altliste stehen.
            val config = shiftUseCase.getCurrentShiftConfig().getOrThrow()
            val neu = uebernehme(config.definitions, alt)

            if (neu != null) {
                shiftUseCase.saveShiftConfig(config.copy(definitions = neu)).getOrThrow()
            }
            // Erst nach belegtem Erfolg. Namen ohne Definition (gewesene Schicht) verfallen mit -
            // sie haetten auch vorher nirgends getroffen.
            dndPrefs.clearLegacyOnCallShifts()

            val ohneTreffer = alt - config.definitions.map { it.name }.toSet()
            Logger.business(
                LogTags.SHIFT_CONFIG,
                "🔁 Rufbereitschaft-Auswahl in die Schichtdefinitionen uebernommen: " +
                    "${alt.size} Eintraege, ${neu?.count { it.isOnCall } ?: config.definitions.count { it.isOnCall }} " +
                    "Schichten markiert" +
                    if (ohneTreffer.isEmpty()) "" else " - ohne passende Schicht verfallen: $ohneTreffer"
            )
            neu != null
        } catch (e: Exception) {
            Logger.e(LogTags.SHIFT_CONFIG, "❌ Rufbereitschaft-Migration fehlgeschlagen - Altliste bleibt stehen, naechster Anlass versucht es erneut", e)
            false
        }
    }
}
