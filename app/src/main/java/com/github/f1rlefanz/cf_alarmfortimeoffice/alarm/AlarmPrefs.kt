package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.qualifiers.MainDataStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Einstellungen der Schlummer-Dauer und des sanften Weckton-Anstiegs (im bestehenden
 * [MainDataStore]).
 *
 * Uebernimmt die frühere Rolle von `AlarmManagerService.SNOOZE_MINUTES` als EINE Quelle fuer
 * Vollbild-Button UND Notification-Button - aber NICHT indem beide Ausloeser diesen DataStore
 * direkt lesen. `AlarmSoundService.onStartCommand`s `ACTION_SNOOZE_ALARM`-Zweig ist bewusst ein
 * synchroner, schneller Notausgang (siehe Klassenkommentar dort) und
 * `AlarmFullScreenActivity.snoozeAlarm()` ist ebenfalls synchron - beide duerfen keine
 * DataStore-Reads bekommen. Stattdessen liest [AlarmReceiver] den Wert EINMAL pro Alarm-Feuern
 * (schon in einer Coroutine, `receiverScope.launch`) und reicht ihn als Intent-Extra
 * (`AlarmSoundService.EXTRA_SNOOZE_MINUTES`) an beide Ausloeser durch - bis dahin ist der Wert
 * synchron aus dem Intent verfuegbar.
 */
@Singleton
class AlarmPrefs @Inject constructor(
    @param:MainDataStore private val dataStore: DataStore<Preferences>
) {
    companion object {
        private val KEY_SNOOZE_MINUTES = intPreferencesKey("snooze_minutes")

        /** Default-Schlummer-Dauer in Minuten - identisch zum frueheren SNOOZE_MINUTES-Default. */
        const val DEFAULT_SNOOZE_MINUTES = 5

        /**
         * Sinnvolle Grenzen, geklemmt beim LESEN und beim SCHREIBEN.
         *
         * `0` oder negativ waere kein "kurzer Schlummer", sondern ein Schlummer-Alarm in der
         * Vergangenheit: er feuert sofort wieder, und der Wecker laesst sich nicht mehr
         * wegdruecken. Der Wert kommt nicht nur aus der eigenen UI - er liegt im `settings`-Store,
         * also auch im Android-Backup und in der Konfigurationsdatei (Export/Import). Beides sind
         * Wege, auf denen ein Wert von einem anderen Geraet, einer aelteren Version oder aus einer
         * von Hand bearbeiteten Datei hereinkommt. Der Import prueft den Bereich zwar selbst
         * (`ConfigBackupFilter.rangeRejection`), aber der Lesepfad ist die letzte Linie und die
         * einzige, die auch fuer das Android-Backup gilt - genauso wie `DimOverlayPrefs` jeden
         * seiner Werte beim Lesen klemmt.
         */
        const val MIN_SNOOZE_MINUTES = 1
        const val MAX_SNOOZE_MINUTES = 120

        // ---- Sanfter Weckton-Anstieg -------------------------------------------------------
        //
        // WARUM ES DIESE EINSTELLUNGEN GIBT: Ton und Lautstaerke des Weckers kommen vollstaendig
        // aus den Android-Einstellungen (Standard-Alarmton, Alarm-Regler) - bewusst, denn eine
        // eigene Auswahl daneben waere eine zweite Wahrheit ohne Gegenwert. Das EINE, was Android
        // dort nicht anbietet, ist ein sanfter Anstieg. Genau der wird hier konfiguriert, und
        // sonst nichts: der Anstieg SKALIERT die eingestellte Lautstaerke, er ersetzt sie nicht.

        private val KEY_ANSTIEG_AKTIV = booleanPreferencesKey("weckton_anstieg_aktiv")
        private val KEY_ANSTIEG_SEKUNDEN = intPreferencesKey("weckton_anstieg_sekunden")
        private val KEY_ANSTIEG_START_PROZENT = intPreferencesKey("weckton_anstieg_start_prozent")

        /**
         * AUS als Vorgabe - und das ist eine Entscheidung, keine Bequemlichkeit.
         *
         * Wer die App aktualisiert, hat sich an sein Weckverhalten gewoehnt. Eine stille
         * Umstellung auf "startet leise" waere eine Aenderung am Wecker selbst, die niemand
         * bestellt hat und die man erst bemerkt, wenn man verschlafen hat.
         */
        const val DEFAULT_ANSTIEG_AKTIV = false

        /** Vorgabe, sobald der Nutzer den Anstieg einschaltet. */
        const val DEFAULT_ANSTIEG_SEKUNDEN = 30
        const val DEFAULT_ANSTIEG_START_PROZENT = 15

        /**
         * Grenzen, geklemmt beim LESEN und beim SCHREIBEN - aus demselben Grund wie bei der
         * Schlummer-Dauer (Android-Backup, Export/Import, aeltere Versionen), aber mit einem
         * zusaetzlichen: hier haengt die Weckwirkung selbst daran.
         *
         * Die OBERgrenze der Dauer ist der eigentliche Schutz. Ein Anstieg ueber viele Minuten
         * ist kein sanfter Wecker mehr, sondern ein spaeterer - wer um 04:30 zur Fruehschicht
         * muss, verliert die Zeit ersatzlos. 120 s sind laut Erfahrungswerten deutlich mehr, als
         * ein Anstieg braucht, und trotzdem eine Grenze, hinter der die Weckzeit noch die
         * Weckzeit ist.
         *
         * Der Startpegel darf 100 sein (dann ist der Anstieg wirkungslos, aber harmlos) und nie
         * 0: `0` bliebe bis zum letzten Schritt stumm und wuerde dann schlagartig laut - siehe
         * [LautstaerkeAnstieg.MIN_STARTPEGEL].
         */
        const val MIN_ANSTIEG_SEKUNDEN = 5
        const val MAX_ANSTIEG_SEKUNDEN = 120
        const val MIN_ANSTIEG_START_PROZENT = 1
        const val MAX_ANSTIEG_START_PROZENT = 100
    }

    /**
     * Das `.catch` steht HINTER dem `.map` (die Reihenfolge ist tragend, siehe CLAUDE.md,
     * "Persistenz") und ist der einzige Grund, warum ein Lesefehler hier keinen stummen Wecker
     * mehr erzeugt.
     *
     * Der Hergang, gefunden in der Pruefrunde vom 18.08.2026: [snoozeMinutesNow] ist ein
     * `first()` auf diesem Flow, und `AlarmReceiver.startAlarmSoundService()` liest den Wert als
     * ERSTE Anweisung in demselben `try`, das auch `startForegroundService()` umschliesst. Der
     * `ReplaceFileCorruptionHandler` des `settings`-Stores faengt nur Korruption; eine
     * IOException (voller Speicher, EACCES, transienter Lesefehler) reicht DataStore in den Flow
     * durch. Sie flog also aus dem Read heraus, das `catch` darunter loggte sie - und
     * `startForegroundService()` wurde nie erreicht: kein Ton, keine Vibration, keine
     * Notification, kein Vollbild. Bei jedem Alarm, bis "App-Daten loeschen".
     *
     * Die Direct-Boot-Ursache derselben Stelle war bereits per `userUnlocked`-Gate geschlossen
     * (Kommentar in `AlarmReceiver`), die IO-Ursache blieb offen - dasselbe Ergebnis, anderer
     * Weg.
     *
     * Degradiert wird auf [DEFAULT_SNOOZE_MINUTES], nicht auf "gar nicht wecken". Die Richtung
     * ist bewusst gewaehlt: ein Wecker mit der Standard-Schlummerdauer ist ein winziger Fehler,
     * ein Wecker, der nicht klingelt, der schwerste denkbare. Im Zweifel klingeln.
     */
    val snoozeMinutes: Flow<Int> = dataStore.data
        .map {
            (it[KEY_SNOOZE_MINUTES] ?: DEFAULT_SNOOZE_MINUTES)
                .coerceIn(MIN_SNOOZE_MINUTES, MAX_SNOOZE_MINUTES)
        }
        .catch { e ->
            Logger.e(
                LogTags.ALARM,
                "Schlummerdauer nicht lesbar - degradiert auf $DEFAULT_SNOOZE_MINUTES Minuten, " +
                    "der Wecker klingelt trotzdem",
                e
            )
            emit(DEFAULT_SNOOZE_MINUTES)
        }

    suspend fun snoozeMinutesNow(): Int = snoozeMinutes.first()

    suspend fun setSnoozeMinutes(v: Int) = dataStore.edit {
        it[KEY_SNOOZE_MINUTES] = v.coerceIn(MIN_SNOOZE_MINUTES, MAX_SNOOZE_MINUTES)
    }

    // ---- Sanfter Weckton-Anstieg -----------------------------------------------------------

    /**
     * Die drei Werte des Anstiegs als EIN Flow.
     *
     * Zusammen und nicht einzeln, weil sie zusammen gelesen werden: `AlarmReceiver` holt sie
     * einmal pro Alarm-Feuern und reicht sie als Intent-Extras an den `AlarmSoundService` durch -
     * derselbe Weg wie bei der Schlummer-Dauer und aus demselben Grund (der Service darf im
     * Weckpfad keinen DataStore-Read bekommen). Drei einzelne `first()`-Aufrufe waeren drei
     * Gelegenheiten zu scheitern, wo eine reicht.
     *
     * Das `.catch` steht HINTER dem `.map` (die Reihenfolge ist tragend, siehe CLAUDE.md,
     * "Persistenz"). Degradiert wird auf [WecktonAnstieg.AUS], also auf volle Lautstaerke ab der
     * ersten Sekunde: Ein Lesefehler darf den Wecker lauter machen, niemals leiser.
     */
    val wecktonAnstieg: Flow<WecktonAnstieg> = dataStore.data
        .map { prefs ->
            WecktonAnstieg(
                aktiv = prefs[KEY_ANSTIEG_AKTIV] ?: DEFAULT_ANSTIEG_AKTIV,
                sekunden = (prefs[KEY_ANSTIEG_SEKUNDEN] ?: DEFAULT_ANSTIEG_SEKUNDEN)
                    .coerceIn(MIN_ANSTIEG_SEKUNDEN, MAX_ANSTIEG_SEKUNDEN),
                startProzent = (prefs[KEY_ANSTIEG_START_PROZENT] ?: DEFAULT_ANSTIEG_START_PROZENT)
                    .coerceIn(MIN_ANSTIEG_START_PROZENT, MAX_ANSTIEG_START_PROZENT)
            )
        }
        .catch { e ->
            Logger.e(
                LogTags.ALARM,
                "Weckton-Anstieg nicht lesbar - der Wecker klingelt ohne Anstieg, also sofort laut",
                e
            )
            emit(WecktonAnstieg.AUS)
        }

    suspend fun wecktonAnstiegNow(): WecktonAnstieg = wecktonAnstieg.first()

    suspend fun setAnstiegAktiv(v: Boolean) = dataStore.edit {
        it[KEY_ANSTIEG_AKTIV] = v
    }

    suspend fun setAnstiegSekunden(v: Int) = dataStore.edit {
        it[KEY_ANSTIEG_SEKUNDEN] = v.coerceIn(MIN_ANSTIEG_SEKUNDEN, MAX_ANSTIEG_SEKUNDEN)
    }

    suspend fun setAnstiegStartProzent(v: Int) = dataStore.edit {
        it[KEY_ANSTIEG_START_PROZENT] = v.coerceIn(MIN_ANSTIEG_START_PROZENT, MAX_ANSTIEG_START_PROZENT)
    }
}

/**
 * Die Einstellung des sanften Weckton-Anstiegs, wie sie beim Feuern gilt.
 *
 * @param aktiv Ist der Anstieg eingeschaltet?
 * @param sekunden Dauer bis zur vollen Lautstaerke.
 * @param startProzent Anfangspegel in Prozent der eingestellten Alarm-Lautstaerke.
 */
data class WecktonAnstieg(
    val aktiv: Boolean,
    val sekunden: Int,
    val startProzent: Int
) {
    /**
     * Dauer fuer [LautstaerkeAnstieg.pegel] - `0`, solange der Anstieg aus ist. Damit gibt es nur
     * EINE Stelle, an der "aus" in "keine Dauer" uebersetzt wird, statt eines `if (aktiv)` an
     * jedem Nutzungsort.
     */
    val dauerMs: Long get() = if (aktiv) sekunden * 1000L else 0L

    /** Startpegel als Faktor fuer `MediaPlayer.setVolume()`. */
    val startAnteil: Float get() = startProzent / 100f

    companion object {
        /**
         * Kein Anstieg: volle Lautstaerke ab der ersten Sekunde. Das Ziel jeder Degradation und
         * der Zustand im Direct Boot, wo der DataStore nicht lesbar ist.
         */
        val AUS = WecktonAnstieg(
            aktiv = false,
            sekunden = AlarmPrefs.DEFAULT_ANSTIEG_SEKUNDEN,
            startProzent = AlarmPrefs.DEFAULT_ANSTIEG_START_PROZENT
        )
    }
}
