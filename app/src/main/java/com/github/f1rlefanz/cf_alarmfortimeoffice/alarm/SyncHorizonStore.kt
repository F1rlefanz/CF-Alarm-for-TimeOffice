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
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Der Bezugspunkt fuer die Frage "war diese Schicht beim letzten Mal ueberhaupt sichtbar?".
 *
 * ## Das Problem, gegen das dieser Merker gebaut ist
 *
 * Die App holt Termine fuer die eingestellte Vorausschau ([KalenderVorausschauPrefs], 7..90 Tage,
 * Standard 14) ab JETZT (`CalendarRepository`: `timeMin = now`, `timeMax = now + Tage`). Dieses
 * Fenster wandert jeden Tag einen Tag weiter. Am Rand rutscht damit taeglich ein neuer Kalendertag
 * herein, und der Delta-Sync sieht dort voellig korrekt "ein Event, fuer das es noch keinen Alarm
 * gibt" - der Create-Zweig. Bis v1.30.0 hiess das jedes Mal "Neue Schicht erkannt".
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
 * ## Gespeichert werden ZEITPUNKT und FENSTER des tatsaechlichen Abrufs ([SyncMerker])
 *
 * Bis v1.45 stand hier nur der Zeitpunkt, und der Horizont wurde beim Lesen mit der AKTUELLEN
 * Konstante daraus gerechnet - mit der Begruendung, eine spaetere Erhoehung der Konstante solle die
 * schlagartig sichtbare Woche MELDEN statt verschweigen. Seit die Vorausschau eine
 * Nutzereinstellung ist (#51), ist genau das falsch herum: stellt der Nutzer von 14 auf 56 Tage,
 * wuerden sechs Wochen Dienstplan auf einen Schlag als "Neue Schicht erkannt" gemeldet - eine
 * Meldungsflut ueber eine Aenderung, die er selbst ausgeloest hat, also genau das Rauschen, gegen
 * das dieser Store gebaut ist (Entscheidung des Eigentuemers, 02.10.2026).
 *
 * Deshalb steht jetzt das Fenster des ABRUFS daneben ([merkeVollstaendigenSync]), und
 * [horizontEndeFuer] rechnet mit dem GESPEICHERTEN Fenster. Fortgeschrieben wird das Fenster der
 * Liste, die der Sync wirklich verarbeitet hat - nicht die aktuelle Einstellung: hat der Nutzer
 * zwischen Abruf und Sync umgestellt, beschreibt nur der Abruf, was gesehen wurde.
 *
 * ALTBESTAND: ein Merker ohne Fensterangabe stammt aus einer Version mit festem Fenster, und das
 * war immer [ALTBESTAND_FENSTER_TAGE] (14) Tage.
 *
 * Der gespeicherte Zeitpunkt liegt minimal NACH dem eigentlichen Abruf (erst holen, dann
 * synchronisieren). Das Fenster wird deshalb aus dem Abruf-Horizont auf ganze Tage GERUNDET
 * ([fensterTageFuer]); der errechnete Horizont ist damit um hoechstens einen halben Tag ungenau,
 * im Normalfall um Sekunden.
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
 * ## Und deshalb hat der Merker ein VERFALLSDATUM ([maxMerkerAlterMs])
 *
 * Der Satz oben stimmt nur, solange der Merker jung ist. Er altert schlecht: bei einem Alter von
 * A Tagen deckt der aus ihm errechnete Horizont nur noch die naechsten (Fenster - A) Tage ab, alles
 * dahinter gilt als "Horizont-Eintritt" und bleibt still. Bei A >= Fenster waere der komplette
 * Dienstplan lautlos.
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

        /**
         * Das Fenster (Tage) der Liste, die der Sync zum Zeitpunkt [KEY_LAST_SYNC_NAME] verarbeitet
         * hat. Laufzeitzustand wie der Zeitpunkt selbst - und nur MIT ihm sinnvoll: ein importiertes
         * Fenster ohne den passenden Zeitpunkt waere eine Behauptung ueber einen fremden Abruf.
         * Ueber das Android-Backup reist es zusammen mit dem Zeitpunkt und bleibt so stimmig.
         */
        const val KEY_FENSTER_TAGE_NAME = "last_successful_sync_window_days"

        private val KEY_LAST_SYNC = longPreferencesKey(KEY_LAST_SYNC_NAME)
        private val KEY_FENSTER_TAGE = intPreferencesKey(KEY_FENSTER_TAGE_NAME)

        private const val MS_PRO_TAG = 24L * 60L * 60L * 1000L

        /**
         * Fenster eines Merkers OHNE Fensterangabe. Bewusst die Zahl und nicht
         * `CalendarConstants.DEFAULT_DAYS_AHEAD`: sie beschreibt, was FRUEHER galt, und darf sich
         * nicht mitbewegen, falls der Standard je geaendert wird.
         */
        const val ALTBESTAND_FENSTER_TAGE = 14

        /**
         * Ab welchem Alter ein Merker nicht mehr zaehlt - die HAELFTE seines Abruf-Fensters.
         *
         * WARUM GENAU DIESE DAUER: Der Merker unterdrueckt Meldungen fuer alles, was weiter als
         * (Fenster - Alter) Tage voraus liegt. Bei einem Alter von einer halben Fensterbreite haelt
         * er also gerade noch die Waage: die erste Haelfte des Fensters ist weiter geschuetzt, die
         * zweite gilt als Horizont-Eintritt. Wird er aelter, verschweigt er mehr, als er erklaert -
         * und das ist die Grenze, ab der Schweigen nicht mehr zu rechtfertigen ist.
         *
         * Nach OBEN ist die Dauer damit weit genug von jeder normalen Betriebsluecke entfernt:
         * die Wartungskette laeuft alle 6 Stunden; auch beim kleinsten erlaubten Fenster
         * ([KalenderVorausschauPrefs.MIN_TAGE] = 7) bleiben 3,5 Tage - ein Wochenende ohne Lauf
         * liegt klar innerhalb. Nach UNTEN ist sie weit genug vom vollen Fenster entfernt, ab dem
         * ein kompletter neuer Dienstplan lautlos waere.
         *
         * Bewusst als Anteil des GESPEICHERTEN Fensters formuliert und nicht als eigene Zahl.
         */
        fun maxMerkerAlterMs(fensterTage: Int): Long = fensterTage * MS_PRO_TAG / 2

        /** Bis wohin reichte das Abruf-Fenster des Syncs, den [merker] beschreibt. */
        fun horizontEndeFuer(merker: SyncMerker): Long =
            merker.syncAt + merker.fensterTage * MS_PRO_TAG

        /**
         * Das Fenster (ganze Tage, mindestens 1), das ein Abruf mit dem Horizont
         * [abrufHorizontEnde] vom Zeitpunkt [syncAt] aus noch abdeckt - gerundet, siehe
         * Klassenkommentar. Ist die Liste aelter als der Sync (der Vordergrund reicht eine Stunden
         * alte Liste weiter), wird das Fenster entsprechend kleiner - und beschreibt damit genau
         * das, was wirklich gesehen wurde.
         */
        fun fensterTageFuer(syncAt: Long, abrufHorizontEnde: Long): Int =
            ((abrufHorizontEnde - syncAt + MS_PRO_TAG / 2) / MS_PRO_TAG).toInt().coerceAtLeast(1)

        /**
         * Liegt ein Schichtbeginn HINTER dem Ende des Abruf-Fensters - also ausserhalb dessen, was
         * die uebergebene Eventliste ueberhaupt zeigen KANN?
         *
         * Die DRITTE Quelle einer fuer die Loeschfrage unvollstaendigen Liste (neben Teilerfolg und
         * Lazy-Praefix): hat der Nutzer die Vorausschau VERKLEINERT (z. B. 28 -> 7), ist die Liste
         * fuer 7 Tage vollstaendig, sagt ueber Tag 8..28 aber nichts. `isComplete` hilft dagegen
         * nicht. Ein bestehender Wecker dort ist dann kein "Termin geloescht", sondern "nicht
         * gelesen" - und wird behalten, ohne Meldung (`AlarmUseCase.syncAlarms()` Schritt 1,
         * `BootAlarmValidation`). Im Zweifel klingeln: offline gaebe es sonst eine Luecke.
         *
         * Grenze `>=`: die Kalender-API filtert `timeMax` EXKLUSIV auf den Terminbeginn - ein
         * Termin, der genau auf dem Horizont beginnt, steht nicht in der Liste.
         *
         * [abrufHorizontEnde] `null` heisst "unbekannt" - dann gilt nichts als jenseits (das
         * Verhalten von vor #51).
         */
        fun istJenseitsDesAbrufs(schichtBeginn: Long, abrufHorizontEnde: Long?): Boolean =
            abrufHorizontEnde != null && schichtBeginn >= abrufHorizontEnde

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
         * Die App war zwei Tage nicht dran? Dann liegt der Merker zwei Tage zurueck, der
         * errechnete Alt-Horizont entsprechend frueher - die zwei Tage, die auf einmal
         * hereinrutschen, sind Horizont-Eintritte und bleiben still. Genau richtig. Dasselbe gilt
         * fuer eine VERGROESSERTE Vorausschau: alles hinter dem gespeicherten Fenster ist neu
         * sichtbar, nicht neu im Dienstplan.
         *
         * War sie dagegen laenger als [maxMerkerAlterMs] nicht dran (oder lief seither nie ein
         * VOLLSTAENDIGER Sync - Master-Pause, Auto-Alarm aus, leere Eventliste erreichen das
         * Fortschreiben gar nicht), zaehlt der Merker wie keiner: es wird gemeldet. Begruendung
         * an [maxMerkerAlterMs].
         *
         * [jetzt] ist herausgezogen, damit der Aufrufer den Bezugszeitpunkt EINES Sync-Laufs
         * einsetzen kann - sonst entscheidet dieselbe Frage innerhalb eines Laufs an der Grenze
         * mal so und mal so.
         */
        fun istHorizontEintritt(
            letzterSync: SyncMerker?,
            schichtBeginn: Long,
            jetzt: Long = System.currentTimeMillis()
        ): Boolean {
            if (letzterSync == null) return false
            if (jetzt - letzterSync.syncAt > maxMerkerAlterMs(letzterSync.fensterTage)) return false
            return schichtBeginn > horizontEndeFuer(letzterSync)
        }
    }

    /**
     * Der Bezugspunkt eines VOLLSTAENDIG durchgelaufenen Syncs: wann er begann ([syncAt],
     * Epoch-Millis) und wie viele Tage die verarbeitete Liste vorausreichte ([fensterTage]).
     */
    data class SyncMerker(val syncAt: Long, val fensterTage: Int)

    /**
     * Der letzte VOLLSTAENDIG durchgelaufene Sync, oder `null` fuer "noch keiner".
     *
     * `Result`, damit der Aufrufer einen Lesefehler von "es gab noch keinen" unterscheiden KANN.
     * Beide fuehren hier zwar zur selben Entscheidung (melden), aber die Unterscheidung gehoert in
     * das Log und nicht wegdefiniert - der `ReplaceFileCorruptionHandler` des [MainDataStore]
     * faengt nur eine `CorruptionException`, eine IOException reicht DataStore durch.
     *
     * Fehlt die Fensterangabe (Altbestand) oder ist sie unplausibel, gilt
     * [ALTBESTAND_FENSTER_TAGE] - das Fenster, mit dem jeder Merker vor #51 entstand.
     */
    open suspend fun letzterVollstaendigerSync(): Result<SyncMerker?> = try {
        val prefs = dataStore.data.first()
        val syncAt = prefs[KEY_LAST_SYNC]
        Result.success(
            syncAt?.let {
                val fenster = prefs[KEY_FENSTER_TAGE]?.takeIf { tage -> tage > 0 }
                    ?: ALTBESTAND_FENSTER_TAGE
                SyncMerker(syncAt = it, fensterTage = fenster)
            }
        )
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
     * [fensterTage] ist das Fenster der verarbeiteten LISTE ([fensterTageFuer]), nicht die
     * aktuelle Einstellung. Beide in EINEM Schreibvorgang - ein Zeitpunkt mit dem Fenster eines
     * anderen Laufs waere eine falsche Behauptung.
     */
    open suspend fun merkeVollstaendigenSync(syncAt: Long, fensterTage: Int): Result<Unit> = try {
        dataStore.edit {
            it[KEY_LAST_SYNC] = syncAt
            it[KEY_FENSTER_TAGE] = fensterTage
        }
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
