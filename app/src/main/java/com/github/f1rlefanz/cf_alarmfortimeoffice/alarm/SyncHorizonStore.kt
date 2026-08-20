package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.qualifiers.MainDataStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.business.CalendarConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Der Bezugspunkt fuer die Frage "war diese Schicht beim letzten Mal ueberhaupt sichtbar?".
 *
 * ## Das Problem, gegen das dieser Merker gebaut ist
 *
 * Die App holt Termine fuer [CalendarConstants.DEFAULT_DAYS_AHEAD] Tage ab JETZT
 * (`CalendarRepository`: `timeMin = now`, `timeMax = now.plusDays(14)`). Dieses Fenster wandert
 * jeden Tag einen Tag weiter. Am Rand rutscht damit taeglich ein neuer Kalendertag herein, und der
 * Delta-Sync sieht dort voellig korrekt "ein Event, fuer das es noch keinen Alarm gibt" - der
 * Create-Zweig. Bis v1.30.0 hiess das jedes Mal "Neue Schicht erkannt".
 *
 * Am Fairphone belegt: der Nutzer bekam die Meldung "seit Tagen immer wieder" und hielt sie fuer
 * eine Dienstplan-Aenderung seines Chefs. Der `dumpsys`-Datensatz der Meldung lebte ~6 Tage und
 * wurde taeglich aktualisiert - immer mit dem jeweils neuen Randtag. Der Alarm-Bestand war dabei
 * voellig stabil ("Created: 0, Updated: 0, Deleted: 0" im Folge-Sync). Eine Meldung, die
 * regelmaessig etwas behauptet, das nicht passiert ist, wird als Rauschen gelesen - und dann
 * uebersieht der Nutzer die eine echte.
 *
 * Der einzige Daempfer davor war `isFirstSync = existingAlarms.isEmpty()` und griff nur nach einer
 * Neuinstallation.
 *
 * ## Warum ein persistenter Merker noetig ist
 *
 * Die Unterscheidung braucht einen Bezugspunkt: bis wohin reichte der Horizont beim LETZTEN Sync?
 * Aus dem Alarm-Bestand laesst sich der NICHT ableiten. Der spaeteste bekannte Alarm sagt nur,
 * wann die letzte erkannte SCHICHT liegt - hat der Nutzer am Ende des Fensters mehrere freie Tage,
 * liegt er weit vor dem Horizont. Eine echte Nachtragung des Chefs in diese Luecke wuerde dann als
 * "Horizont-Eintritt" verschwiegen. Genau die Richtung, die hier nicht passieren darf.
 *
 * ## Warum der ZEITPUNKT gespeichert wird und nicht der Horizont selbst
 *
 * Gespeichert wird der Beginn des letzten vollstaendig durchgelaufenen Syncs; der Horizont wird
 * beim Lesen mit dem AKTUELLEN [CalendarConstants.DEFAULT_DAYS_AHEAD] daraus gerechnet
 * ([horizontEndeFuer]). Wuerde stattdessen der fertige Horizont gespeichert, haette eine spaetere
 * Erhoehung der Konstante (14 -> 21 Tage) genau einen Lauf lang zur Folge, dass die schlagartig
 * sichtbar werdende Woche HINTER dem gespeicherten Alt-Horizont liegt und damit als
 * Horizont-Eintritt verschwiegen wuerde. Mit dem Zeitpunkt waechst der errechnete Alt-Horizont
 * dagegen mit - es wird gemeldet statt verschwiegen.
 *
 * Der gespeicherte Zeitpunkt liegt minimal NACH dem eigentlichen Abruf (erst holen, dann
 * synchronisieren) - der errechnete Horizont ist also um Sekunden bis Minuten zu grosszuegig.
 * Auch das ist die sichere Richtung: zu grosszuegig heisst "gilt als schon abgedeckt" heisst
 * "wird gemeldet".
 *
 * ## Geschrieben wird NUR nach einem vollstaendigen Sync
 *
 * Ein abgebrochener oder teilweise gescheiterter Lauf schiebt den Bezugspunkt nicht vor. Der
 * Merker ist eine BEHAUPTUNG ("bis hierhin haben wir alles gesehen und verarbeitet"), und die darf
 * ein unfertiger Lauf nicht aufstellen - sonst gilt ein Horizont als abgearbeitet, dessen Events
 * nie verarbeitet wurden.
 *
 * Was das im Gegenzug kostet, sei offen gesagt: ein stehen gebliebener Merker heisst ein FRUEHERER
 * Vergleichspunkt, und damit gilt eine echte Nachtragung ganz am Rand des Fensters als
 * Horizont-Eintritt und bleibt still. Solange der Merker frisch ist, ist das genau die Lage "die
 * App war zwei Tage nicht dran", die still bleiben SOLL - und der naechste vollstaendige Lauf
 * loest sie auf.
 *
 * ## Und deshalb hat der Merker ein VERFALLSDATUM ([MAX_MERKER_ALTER_MS])
 *
 * Der Satz oben stimmt nur, solange der Merker jung ist. Er altert schlecht: bei einem Alter von
 * A Tagen deckt der aus ihm errechnete Horizont nur noch die naechsten
 * ([CalendarConstants.DEFAULT_DAYS_AHEAD] - A) Tage ab, alles dahinter gilt als
 * "Horizont-Eintritt" und bleibt still. Bei A >= 14 waere der komplette Dienstplan lautlos.
 *
 * Und ein alter Merker ist keine Ausnahmelage: [merkeVollstaendigenSync] wird nur nach einem
 * VOLLSTAENDIGEN Sync gerufen - die vier Frueh-Ausstiege in `AlarmUseCase.syncAlarms()`
 * (Master-Pause, `autoAlarmEnabled = false`, leere Eventliste, kein Treffer) erreichen ihn gar
 * nicht. Zwei Wochen Urlaub mit aktiver Master-Pause reichen also aus, und danach traegt der Chef
 * einen ganz neuen Dienstplan ein, ueber den die App kein Wort verliert.
 *
 * Der Merker behauptet "beim LETZTEN Lauf reichte der Horizont bis hierhin". Ist dieser Lauf lange
 * her, weiss die App es schlicht nicht mehr - und ein zu alter Merker zaehlt wie keiner: MELDEN.
 *
 * Liegt bewusst im bestehenden [MainDataStore] - die drei Namensraeume der App bleiben getrennt.
 *
 * `open` (wie [ShiftChangeNotifier]), damit Tests eine Fake-Unterklasse bilden koennen, statt eine
 * DataStore-Attrappe bauen zu muessen.
 */
@Singleton
open class SyncHorizonStore @Inject constructor(
    @param:MainDataStore private val dataStore: DataStore<Preferences>
) {
    companion object {
        /**
         * Oeffentlich, damit `ConfigBackupFilter` denselben Namen ausschliessen kann, statt ein
         * Duplikat zu pflegen. Der Wert ist Laufzeitzustand DIESES Geraets (wann lief hier zuletzt
         * ein Sync) und gehoert nie in eine Exportdatei.
         */
        const val KEY_LAST_SYNC_NAME = "last_successful_sync_at"

        private val KEY_LAST_SYNC = longPreferencesKey(KEY_LAST_SYNC_NAME)

        private const val MS_PRO_TAG = 24L * 60L * 60L * 1000L

        /**
         * Ab welchem Alter ein Merker nicht mehr zaehlt - die HAELFTE des Abruf-Fensters.
         *
         * WARUM GENAU DIESE DAUER: Der Merker unterdrueckt Meldungen fuer alles, was weiter als
         * ([CalendarConstants.DEFAULT_DAYS_AHEAD] - Alter) Tage voraus liegt. Bei einem Alter von
         * einer halben Fensterbreite haelt er also gerade noch die Waage: die erste Haelfte des
         * Fensters ist weiter geschuetzt, die zweite gilt als Horizont-Eintritt. Wird er aelter,
         * verschweigt er mehr, als er erklaert - und das ist die Grenze, ab der Schweigen nicht
         * mehr zu rechtfertigen ist.
         *
         * Nach OBEN ist die Dauer damit weit genug von jeder normalen Betriebsluecke entfernt:
         * die Wartungskette laeuft alle 6 Stunden, ein ausgeschaltetes oder tief schlafendes
         * Geraet ueber ein Wochenende bleibt also klar innerhalb. Nach UNTEN ist sie weit genug
         * von den 14 Tagen entfernt, ab denen ein kompletter neuer Dienstplan lautlos waere.
         *
         * Waechst [CalendarConstants.DEFAULT_DAYS_AHEAD], waechst diese Grenze mit - sie ist
         * bewusst als Anteil des Fensters formuliert und nicht als eigene Zahl.
         */
        val MAX_MERKER_ALTER_MS = CalendarConstants.DEFAULT_DAYS_AHEAD * MS_PRO_TAG / 2

        /** Bis wohin reichte das Abruf-Fenster eines Syncs, der zum Zeitpunkt [syncAt] lief. */
        fun horizontEndeFuer(syncAt: Long): Long =
            syncAt + CalendarConstants.DEFAULT_DAYS_AHEAD * MS_PRO_TAG

        /**
         * Ist [schichtBeginn] lediglich neu in das wandernde Abruf-Fenster gerutscht - also beim
         * letzten Sync noch gar nicht sichtbar gewesen?
         *
         * **Degradationsrichtung, bewusst gewaehlt: im Zweifel MELDEN.** [letzterSync] ist `null`,
         * wenn es noch keinen Merker gibt (erster Lauf nach dem Update) ODER wenn der Merker sich
         * nicht lesen laesst. Beides ergibt hier `false` = "keine Horizont-Erklaerung, also echte
         * Aenderung" - der Nutzer bekommt die Meldung. Eine ueberfluessige Meldung ist laestig; eine
         * verschwiegene echte Dienstplan-Aenderung kostet das Vertrauen in die App und im
         * schlimmsten Fall die Schicht. Stille Degradierung darf auch hier nicht zur Wahrheit
         * werden.
         *
         * Die App war zwei Tage nicht dran? Dann liegt [letzterSync] zwei Tage zurueck, der
         * errechnete Alt-Horizont entsprechend frueher - die zwei Tage, die auf einmal
         * hereinrutschen, sind Horizont-Eintritte und bleiben still. Genau richtig.
         *
         * War sie dagegen laenger als [MAX_MERKER_ALTER_MS] nicht dran (oder lief seither nie ein
         * VOLLSTAENDIGER Sync - Master-Pause, Auto-Alarm aus, leere Eventliste erreichen das
         * Fortschreiben gar nicht), zaehlt der Merker wie keiner: es wird gemeldet. Begruendung
         * an [MAX_MERKER_ALTER_MS].
         *
         * [jetzt] ist herausgezogen, damit der Aufrufer den Bezugszeitpunkt EINES Sync-Laufs
         * einsetzen kann - sonst entscheidet dieselbe Frage innerhalb eines Laufs an der Grenze
         * mal so und mal so.
         */
        fun istHorizontEintritt(
            letzterSync: Long?,
            schichtBeginn: Long,
            jetzt: Long = System.currentTimeMillis()
        ): Boolean {
            if (letzterSync == null) return false
            if (jetzt - letzterSync > MAX_MERKER_ALTER_MS) return false
            return schichtBeginn > horizontEndeFuer(letzterSync)
        }
    }

    /**
     * Beginn des letzten VOLLSTAENDIG durchgelaufenen Syncs (Epoch-Millis), oder `null` fuer
     * "noch keiner".
     *
     * `Result`, damit der Aufrufer einen Lesefehler von "es gab noch keinen" unterscheiden KANN.
     * Beide fuehren hier zwar zur selben Entscheidung (melden), aber die Unterscheidung gehoert in
     * das Log und nicht wegdefiniert - der `ReplaceFileCorruptionHandler` des [MainDataStore]
     * faengt nur eine `CorruptionException`, eine IOException reicht DataStore durch.
     */
    open suspend fun letzterVollstaendigerSync(): Result<Long?> = try {
        Result.success(dataStore.data.first()[KEY_LAST_SYNC])
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.e(LogTags.ALARM, "Sync-Horizont nicht lesbar - Schichtaenderungen werden vorsichtshalber gemeldet", e)
        Result.failure(e)
    }

    /**
     * Schiebt den Bezugspunkt vor. NUR aufrufen, wenn der Sync vollstaendig durchgelaufen ist
     * (kein uebersprungenes Event, keine Exception) - siehe Klassenkommentar.
     *
     * [syncAt] ist der BEGINN des Laufs, nicht sein Ende: die Events wurden vorher geholt.
     */
    open suspend fun merkeVollstaendigenSync(syncAt: Long): Result<Unit> = try {
        dataStore.edit { it[KEY_LAST_SYNC] = syncAt }
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Nicht fatal: der alte Bezugspunkt bleibt stehen, der naechste Lauf versucht es erneut.
        // Der Aufrufer (AlarmUseCase) darf daran nicht scheitern - die Wecker sind die Hauptsache.
        Logger.w(LogTags.ALARM, "Sync-Horizont liess sich nicht fortschreiben", e)
        Result.failure(e)
    }
}
