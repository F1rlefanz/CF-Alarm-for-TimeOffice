package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.qualifiers.MainDataStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.business.CalendarConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wie viele Tage die App im Kalender vorausschaut (#51) - im bestehenden [MainDataStore].
 *
 * ## Warum eine eigene Einstellung und NICHT ein Feld in `ShiftConfig`
 *
 * Die alte Schicht-Konfiguration kannte einmal `ShiftConfig.daysAhead`, und gespeichertes JSON kann
 * das Feld noch tragen (`ShiftConfigSerializationTest`). Ein neues Feld gleichen Sinns dort hiesse:
 * ein Alt-JSON belebt still einen vergessenen Wert wieder. Ausserdem liefe jede Aenderung dann ueber
 * `ShiftViewModel.observeExternalConfigChanges()` - den Weg fuer FREMDE Schreiber, mit Regelnachzug
 * und Alarm-Sync auf der alten Liste. Der Horizont ist keine Schicht-Eigenschaft, sondern eine
 * Eigenschaft des ABRUFS.
 *
 * ## Degradationsrichtung: der Ersatzwert 14, und er wird NIE zurueckgeschrieben
 *
 * Ist der Wert nicht lesbar (IOException, falscher Typ, unplausibler Wert aus einem Backup), gilt
 * [STANDARD_TAGE] - das Fenster, mit dem die App bis v1.45 immer lief. Geschrieben wird dieser
 * Ersatz nirgends: "Stille Degradierung darf nie zur Schreibwahrheit werden" (CLAUDE.md,
 * Persistenz). Eine ausdruecklich gespeicherte 56 bleibt also nach einem voruebergehenden Lesefehler
 * erhalten, statt beim naechsten Schreibvorgang durch die Notlage-14 ersetzt zu werden.
 *
 * Warum 14 und nicht z. B. die Untergrenze: ein KLEINERES Fenster als das tatsaechlich gespeicherte
 * ist die vorsichtige Seite (es wird nichts geloescht, was dahinter liegt - siehe
 * `AlarmUseCase.syncAlarms()`, Schritt 1), und 14 ist der Wert, den jeder Bestandsnutzer kennt.
 *
 * ## Grenzen 7..90 Tage ([MIN_TAGE], [MAX_TAGE])
 *
 * - **7 unten:** Hue plant 7 Tage voraus; das Hoechstalter des Sync-Merkers (halbes Fenster,
 *   [SyncHorizonStore]) liegt dann bei 3,5 Tagen und deckt ein Wochenende ohne Wartungslauf noch ab.
 * - **90 oben:** ein voller Privatkalender bleibt weit unter der Seiten-Notbremse
 *   (`EVENTS_PER_API_PAGE` x `MAX_EVENT_PAGES_PER_CALENDAR` = 2500 Termine je Kalender); wird sie
 *   erreicht, gilt der Abruf als FEHLER und der Sync steht still. Wer mehr will, muss zuerst die
 *   Notbremse anpassen.
 *
 * ## Kein Lesen beim Bau des Objektgraphen
 *
 * [tage] ist ein GETTER, kein beim Konstruieren belegtes Feld: die Klasse haengt (ueber den
 * `AlarmUseCase`) am Graphen, der auch im Direct-Boot-Prozess entsteht. Ein CE-Read vor der ersten
 * Entsperrung liefert still LEERE Preferences - und DataStore haelt dieses Leer dann fuer den GANZEN
 * Store im Prozess (CLAUDE.md, Persistenz). Gelesen wird erst, wenn ein Kalenderabruf laeuft, und der
 * setzt ein Token voraus, also einen entsperrten Nutzer.
 *
 * `open`, damit Tests eine Fake-Unterklasse bilden koennen (wie [SyncHorizonStore]).
 */
@Singleton
open class KalenderVorausschauPrefs @Inject constructor(
    @param:MainDataStore private val dataStore: DataStore<Preferences>
) {
    companion object {
        /**
         * Oeffentlich, damit `ConfigBackupFilter` den Plausibilitaetsbereich unter DEMSELBEN Namen
         * fuehrt, statt ein Duplikat zu pflegen. Die Einstellung reist mit (Export und
         * Android-Backup) - sie ist eine Nutzerentscheidung, kein Laufzeitzustand.
         */
        const val KEY_TAGE_NAME = "kalender_vorausschau_tage"

        private val KEY_TAGE = intPreferencesKey(KEY_TAGE_NAME)

        /** Untergrenze in Tagen - Begruendung im Klassenkommentar. */
        const val MIN_TAGE = 7

        /** Obergrenze in Tagen - Begruendung im Klassenkommentar. */
        const val MAX_TAGE = 90

        /** Standard UND Ersatzwert bei einem Lesefehler: das bisherige feste Fenster. */
        const val STANDARD_TAGE = CalendarConstants.DEFAULT_DAYS_AHEAD

        /** Die Schnellwahl der Einstellungskarte (2, 4, 6, 8 Wochen). */
        val SCHNELLWAHL_TAGE: List<Int> = listOf(14, 28, 42, 56)

        fun istGueltig(tage: Int): Boolean = tage in MIN_TAGE..MAX_TAGE

        /**
         * REINE FUNKTION: was aus einem gespeicherten Wert wird. Fehlt er oder liegt er ausserhalb
         * der Grenzen (Backup einer kuenftigen Version, von Hand bearbeitete Datei), gilt
         * [STANDARD_TAGE] - bewusst NICHT geklemmt: eine 400 auf 90 zu biegen, waere eine
         * Behauptung ueber den Nutzerwillen, die niemand getroffen hat.
         */
        internal fun ausGespeichertemWert(wert: Int?): Int =
            if (wert != null && istGueltig(wert)) wert else STANDARD_TAGE

        /**
         * REINE FUNKTION: Urteil ueber eine Eingabe aus dem Zahlenfeld.
         *
         * Ungueltiges wird mit Text ABGELEHNT, nicht still auf die Grenze gesetzt: wer "120"
         * eintippt und danach "90" sieht, glaubt an einen Bedienfehler oder an einen
         * gespeicherten Wert, den es nie gab.
         */
        fun pruefeEingabe(eingabe: String): EingabeUrteil {
            val text = eingabe.trim()
            if (text.isEmpty()) {
                return EingabeUrteil.Abgelehnt("Bitte eine Zahl von $MIN_TAGE bis $MAX_TAGE eingeben.")
            }
            val zahl = text.toIntOrNull()
                ?: return EingabeUrteil.Abgelehnt("Nur ganze Tage, z. B. 28.")
            return when {
                zahl < MIN_TAGE -> EingabeUrteil.Abgelehnt(
                    "Mindestens $MIN_TAGE Tage."
                )
                zahl > MAX_TAGE -> EingabeUrteil.Abgelehnt(
                    "Höchstens $MAX_TAGE Tage."
                )
                else -> EingabeUrteil.Gueltig(zahl)
            }
        }
    }

    /** Ergebnis von [pruefeEingabe]. */
    sealed interface EingabeUrteil {
        data class Gueltig(val tage: Int) : EingabeUrteil
        data class Abgelehnt(val grund: String) : EingabeUrteil
    }

    /**
     * Die eingestellte Vorausschau in Tagen. Wirft nie (ausser bei Abbruch der Coroutine).
     *
     * `.catch` steht HINTER dem `.map` (CLAUDE.md, Persistenz: die Reihenfolge ist tragend) und
     * faengt so auch einen Fehler beim Auswerten. Ein Wert falschen Typs (z. B. ein Long aus einer
     * fremden Datei) wird schon im `map` zum Ersatzwert, damit ein Beobachter danach weiterlebt.
     */
    open val tage: Flow<Int>
        get() = dataStore.data
            .map { prefs ->
                val gespeichert = try {
                    prefs[KEY_TAGE]
                } catch (e: ClassCastException) {
                    Logger.w(LogTags.CALENDAR, "Kalender-Vorausschau hat einen unerwarteten Typ - es gilt $STANDARD_TAGE", e)
                    null
                }
                ausGespeichertemWert(gespeichert)
            }
            .catch { e ->
                if (e is CancellationException) throw e
                Logger.e(
                    LogTags.CALENDAR,
                    "Kalender-Vorausschau nicht lesbar - es gilt $STANDARD_TAGE Tage (wird NICHT gespeichert)",
                    e
                )
                emit(STANDARD_TAGE)
            }
            .distinctUntilChanged()

    /** Einmaliges Lesen - EINMAL pro Abruf, nie getrennt fuer Abruf und Sync. */
    open suspend fun tageNow(): Int = tage.first()

    /**
     * Speichert eine neue Vorausschau. Werte ausserhalb von [MIN_TAGE]..[MAX_TAGE] werden
     * abgelehnt (`Result.failure`), nicht geklemmt - die Oberflaeche prueft vorher mit
     * [pruefeEingabe]; dies ist die letzte Linie.
     */
    open suspend fun setTage(tage: Int): Result<Unit> {
        if (!istGueltig(tage)) {
            return Result.failure(IllegalArgumentException("Vorausschau $tage ausserhalb $MIN_TAGE..$MAX_TAGE"))
        }
        return try {
            dataStore.edit { it[KEY_TAGE] = tage }
            Logger.business(LogTags.CALENDAR, "📅 Kalender-Vorausschau auf $tage Tage gesetzt")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(LogTags.CALENDAR, "Kalender-Vorausschau liess sich nicht speichern", e)
            Result.failure(e)
        }
    }
}
