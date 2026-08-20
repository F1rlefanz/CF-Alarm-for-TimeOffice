package com.github.f1rlefanz.cf_alarmfortimeoffice.calendar

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.qualifiers.MainDataStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Der dauerhafte Merker fuer EINEN offenen Raeumauftrag: "Der Nutzer hat den letzten Kalender
 * abgewaehlt, die kalenderbasierten Wecker sind aber noch nicht weg."
 *
 * **Warum das nicht im Arbeitsspeicher stehen darf.** Das Aufraeumen nach einer Abwahl laeuft in
 * `CalendarViewModel.clearAlarmsAfterCalendarDeselection()` unter `NonCancellable` - das schuetzt
 * gegen einen ABBRUCH der Coroutine, nicht gegen den Tod des Prozesses. Und der Fehlerzustand
 * (`CalendarUiState.deselectionCleanupFailures`) lebte ausschliesslich im ViewModel. Der
 * Wiedereinstieg haengt daneben am Uebergangs-Merker "es war einmal etwas ausgewaehlt", und der
 * ist beim naechsten App-Start per Konstruktion falsch, weil die Auswahl dann von Anfang an leer
 * ist.
 *
 * Stirbt der Prozess also zwischen Abwahl und Raeumung - Wegwischen aus der Uebersicht, ein
 * Force-Stop, "App bei Nichtnutzung pausieren" - oder scheitert das Raeumen einmal und der Nutzer
 * startet die App neu, dann sind die armierten Wecker noch da, der Auftrag und die Warnung aber
 * weg. Genau die naheliegendste Geste (Kalender abwaehlen, App wegwischen) trifft dieses Fenster.
 *
 * **Die Reihenfolge ist die eigentliche Zusicherung**: der Merker wird gesetzt, BEVOR geraeumt
 * wird, und erst nach nachweislich erfolgreichem Raeumen geloescht. Umgekehrt (erst raeumen, dann
 * merken) gaebe es weiterhin ein Fenster, in dem der Auftrag verloren geht.
 *
 * **Er bildet AUSSCHLIESSLICH eine ausdrueckliche Abwahl ab, niemals ein leeres Ladeergebnis.**
 * Gesetzt wird er erst, nachdem der Auswahl-Speicher LESBAR "nichts ausgewaehlt" gemeldet hat -
 * "leer ist keine Loeschgrundlage" gilt hier genauso wie ueberall sonst in dieser App.
 *
 * Abgearbeitet wird er in der 6h-Wartung (`AlarmMaintenanceService`), also ohne dass der Nutzer
 * die App je wieder oeffnen muss. Das Raeumen selbst laeuft ueber `syncAlarms(emptyList(), config)`
 * und schont damit manuelle Wecker (`keepManualAlarms`).
 *
 * Liegt bewusst im bestehenden [MainDataStore] - die drei Namensraeume der App bleiben getrennt,
 * ein vierter kommt dafuer nicht dazu.
 */
@Singleton
class PendingDeselectionCleanupStore @Inject constructor(
    @param:MainDataStore private val dataStore: DataStore<Preferences>
) {
    companion object {
        /**
         * Oeffentlich, damit `ConfigBackupFilter` denselben Namen ausschliessen kann, statt ein
         * Duplikat zu pflegen. Der Wert ist Laufzeitzustand DIESES Geraets und gehoert nie in
         * eine Exportdatei.
         */
        const val KEY_PENDING_SINCE_NAME = "pending_deselection_cleanup_since"

        private val KEY_PENDING_SINCE = longPreferencesKey(KEY_PENDING_SINCE_NAME)
    }

    /**
     * Seit wann ein Auftrag offen ist (Epoch-Millis), oder `null` fuer "kein Auftrag".
     *
     * `Result`, weil ein LESEFEHLER etwas anderes aussagt als "kein Auftrag": als "kein Auftrag"
     * gedeutet, faellt der Auftrag stillschweigend unter den Tisch und die verwaisten Wecker
     * bleiben fuer immer. Der Aufrufer laesst den Merker in diesem Fall stehen und versucht es im
     * naechsten Lauf erneut - stille Degradierung darf nie zur Schreibwahrheit werden.
     *
     * Der `ReplaceFileCorruptionHandler` des [MainDataStore] faengt nur eine `CorruptionException`;
     * eine IOException reicht DataStore durch - deshalb hier ein eigener Fang.
     */
    suspend fun pendingSince(): Result<Long?> = try {
        Result.success(dataStore.data.first()[KEY_PENDING_SINCE])
    } catch (e: CancellationException) {
        // Eine Cancellation ist kein Lesefehler - sie sagt nichts ueber den Auftrag aus.
        throw e
    } catch (e: Exception) {
        Logger.e(LogTags.CALENDAR, "Offener Raeumauftrag nicht lesbar", e)
        Result.failure(e)
    }

    /**
     * Haelt den Auftrag fest. Ein bereits offener Auftrag behaelt seinen urspruenglichen
     * Zeitstempel - er sagt, seit wann verwaiste Wecker moeglich sind, und ein Ueberschreiben bei
     * jedem Anlauf wuerde genau diese Information loeschen.
     */
    suspend fun markPending(now: Long = System.currentTimeMillis()): Result<Unit> = try {
        dataStore.edit { prefs ->
            if (prefs[KEY_PENDING_SINCE] == null) prefs[KEY_PENDING_SINCE] = now
        }
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.e(LogTags.CALENDAR, "Offener Raeumauftrag liess sich nicht festhalten", e)
        Result.failure(e)
    }

    /**
     * Loescht den Auftrag - NUR aufzurufen, wenn er nachweislich erledigt oder hinfaellig ist.
     *
     * Liest vorher: ohne Auftrag wird gar nicht geschrieben. Das ist kein Mikro-Optimieren, denn
     * diese Funktion laeuft bei JEDER Kalenderauswahl-Aenderung und bei jedem Wartungslauf mit -
     * ein Schreibvorgang je Aufruf wuerde die Datei ohne Anlass anfassen.
     */
    suspend fun clearIfPending(): Result<Unit> = try {
        if (dataStore.data.first()[KEY_PENDING_SINCE] != null) {
            dataStore.edit { it.remove(KEY_PENDING_SINCE) }
        }
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.e(LogTags.CALENDAR, "Erledigter Raeumauftrag liess sich nicht loeschen", e)
        Result.failure(e)
    }
}
