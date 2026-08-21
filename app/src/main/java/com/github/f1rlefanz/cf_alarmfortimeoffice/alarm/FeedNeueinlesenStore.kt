package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.qualifiers.MainDataStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wann hat der abonnierte Dienstplan-Kalender zuletzt allen Terminen neue Kennungen gegeben - und
 * wie viele Wecker hat die App dabei wiedererkannt?
 *
 * @param zeitpunkt Epoch-Millis; der Beginn des Sync-Laufs, der den Wechsel gesehen hat.
 * @param anzahl wie viele Wecker in diesem einen Lauf eine neue Kennung bekamen. Immer > 0 -
 *        siehe [FeedNeueinlesenStore.merkeNeueinlesen].
 */
data class FeedNeueinlesenStand(
    val zeitpunkt: Long,
    val anzahl: Int
)

/**
 * Der Merker hinter der stillen Statuszeile "Dienstplan-Kalender zuletzt neu eingelesen".
 *
 * ## Wovon die Zeile erzaehlt
 *
 * Der Dienstplan kommt aus einem ABONNIERTEN Kalender (TimeOffice-ICS). Google liest den Feed alle
 * paar Tage neu ein und vergibt dabei ALLEN Terminen neue Event-IDs, ohne dass sich an Schicht
 * oder Weckzeit irgendetwas aendert. Seit v1.30.1 paart `AlarmUseCase.syncAlarms()` die Wecker
 * ueber Weckzeit + Schicht wieder zusammen und behaelt ihre `AlarmInfo.id` - es wird nichts
 * geloescht, nichts neu angelegt, nichts gemeldet.
 *
 * Genau richtig so, aber damit ist der Vorgang fuer den Nutzer voellig unsichtbar; sichtbar ist er
 * bisher nur als WARN-Zeile im Log ("$n Wecker haben eine neue Kalender-Kennung bekommen"). Auf die
 * Frage "woran erkenne ich das?" gab es keine Antwort im Gehaeuse der App. Dieser Merker traegt
 * die beiden Angaben, aus denen die Statuszeile gebaut wird.
 *
 * ## Geschrieben wird NUR, wenn es wirklich vorkam
 *
 * [merkeNeueinlesen] weigert sich, mit `anzahl <= 0` zu schreiben. Wuerde jeder Lauf schreiben,
 * stuende in der Statuszeile dauerhaft "heute, 0 Wecker" - eine Zeile, die eine Beobachtung
 * behauptet, die es nicht gab, und die den letzten ECHTEN Stand dabei ueberschreibt. Der Nutzer
 * will hier ablesen, wann es zuletzt passiert ist; das geht nur, wenn ein Lauf ohne
 * Kennungswechsel den Stand stehen laesst.
 *
 * ## Die Werte sind reiner Laufzeitzustand DIESES Geraets
 *
 * Beide Schluessel stehen deshalb in den `RUNTIME_KEYS` von `ConfigBackupFilter` - Begruendung
 * dort. Die Konstanten sind oeffentlich, damit der Filter denselben Namen ausschliessen kann,
 * statt ein Duplikat zu pflegen (gleiche Loesung wie bei [SyncHorizonStore]).
 *
 * Liegt bewusst im bestehenden [MainDataStore] - die drei Namensraeume der App bleiben getrennt.
 *
 * `open` (wie [SyncHorizonStore]), damit Tests eine Fake-Unterklasse bilden koennen, statt eine
 * DataStore-Attrappe bauen zu muessen.
 */
@Singleton
open class FeedNeueinlesenStore @Inject constructor(
    @param:MainDataStore private val dataStore: DataStore<Preferences>
) {
    companion object {
        /** Zeitpunkt des letzten beobachteten Kennungswechsels (Epoch-Millis). */
        const val KEY_ZEITPUNKT_NAME = "last_feed_reread_at"

        /** Wie viele Wecker dabei wiedererkannt wurden. */
        const val KEY_ANZAHL_NAME = "last_feed_reread_count"

        private val KEY_ZEITPUNKT = longPreferencesKey(KEY_ZEITPUNKT_NAME)
        private val KEY_ANZAHL = intPreferencesKey(KEY_ANZAHL_NAME)

        /**
         * Beide Werte gehoeren zusammen; ein halber Stand ist keiner. Fehlt einer von beiden (ein
         * abgebrochener Schreibvorgang, ein von Hand zusammengesetzter DataStore), gilt der Stand
         * als "noch nie vorgekommen" und es erscheint keine Zeile - lieber gar keine Auskunft als
         * eine Zeile mit einem erfundenen Datum oder einer erfundenen Zahl.
         */
        private fun lies(prefs: Preferences): FeedNeueinlesenStand? {
            val zeitpunkt = prefs[KEY_ZEITPUNKT] ?: return null
            val anzahl = prefs[KEY_ANZAHL] ?: return null
            if (zeitpunkt <= 0L || anzahl <= 0) return null
            return FeedNeueinlesenStand(zeitpunkt, anzahl)
        }
    }

    /**
     * Der zuletzt gemerkte Stand als Flow (`null` = noch nie vorgekommen), damit die Statuszeile
     * ohne Zutun nachzieht, wenn waehrend eines geoeffneten Status-Tabs ein Sync laeuft.
     *
     * REIHENFOLGE VON `.map` UND `.catch` IST TRAGEND: `.catch` steht HINTER `.map`, sonst faengt
     * es nur Fehler des Upstreams und nicht die der Umwandlung.
     *
     * DEGRADATIONSRICHTUNG, BEWUSST GEWAEHLT: ein Lesefehler ergibt `null` = keine Zeile. Das ist
     * hier ausdruecklich erlaubt und kein Verstoss gegen "stille Degradierung darf nie zur
     * Schreibwahrheit werden": dieser Wert wird NIE gelesen, um ihn veraendert zurueckzuschreiben -
     * [merkeNeueinlesen] setzt beide Felder unabhaengig vom vorherigen Inhalt. Die Notlage-Leere
     * kann also nichts ueberschreiben. Und eine ausbleibende Auskunftszeile ist folgenlos, waehrend
     * eine erfundene Zeile eine Beobachtung behaupten wuerde, die es nicht gab.
     */
    open fun beobachte(): Flow<FeedNeueinlesenStand?> = dataStore.data
        .map { lies(it) }
        .catch { e ->
            Logger.e(LogTags.ALARM, "Merker 'Kalender neu eingelesen' nicht lesbar - die Statuszeile bleibt aus", e)
            emit(null)
        }
        // PFLICHT, nicht Kosmetik: der Merker liegt im GETEILTEN @MainDataStore, und
        // `dataStore.data` emittiert den ganzen Schnappschuss bei JEDEM Schreibvorgang irgendeines
        // Schluessels darin - ein einziger Sync-Lauf schreibt `active_alarms` dutzendfach. Ohne
        // diese Zeile bekaeme der Beobachter im CalendarViewModel dutzende identische Emissionen,
        // und jede davon wuerde dort ein noch offenes, gebatchtes UI-Update verwerfen (die Falle
        // ist in derselben Datei ausdruecklich dokumentiert). Der Merker aendert sich in
        // Wirklichkeit alle paar TAGE einmal.
        .distinctUntilChanged()

    /**
     * Haelt fest, dass der Feed neu eingelesen wurde und dabei [anzahl] Wecker wiedererkannt
     * wurden.
     *
     * NUR MIT [anzahl] > 0: ein Lauf ohne Kennungswechsel darf den letzten Stand NICHT
     * ueberschreiben (siehe Klassenkommentar). Der Aufrufer fragt das bereits ab; der Backstop hier
     * ist der zweite Riegel, damit ein kuenftiger zweiter Aufrufer die Zusicherung nicht
     * versehentlich aushebelt. Er meldet Erfolg, weil nichts schiefgegangen ist - es gab nur
     * nichts festzuhalten.
     *
     * [zeitpunkt] ist der BEGINN des Sync-Laufs, der den Wechsel gesehen hat - derselbe
     * Bezugszeitpunkt, mit dem der Lauf auch sonst rechnet.
     */
    open suspend fun merkeNeueinlesen(
        anzahl: Int,
        zeitpunkt: Long = System.currentTimeMillis()
    ): Result<Unit> {
        if (anzahl <= 0) return Result.success(Unit)
        return try {
            dataStore.edit { prefs ->
                prefs[KEY_ZEITPUNKT] = zeitpunkt
                prefs[KEY_ANZAHL] = anzahl
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nicht fatal: der alte Stand bleibt stehen, der naechste Kennungswechsel schreibt
            // erneut. Der Aufrufer (AlarmUseCase) darf daran nicht scheitern - die Wecker sind die
            // Hauptsache, diese Zeile ist eine Auskunft.
            Logger.w(LogTags.ALARM, "Merker 'Kalender neu eingelesen' liess sich nicht schreiben", e)
            Result.failure(e)
        }
    }
}
