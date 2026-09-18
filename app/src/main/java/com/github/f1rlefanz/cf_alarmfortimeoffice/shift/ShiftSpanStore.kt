package com.github.f1rlefanz.cf_alarmfortimeoffice.shift

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.qualifiers.MainDataStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Eine Schicht, die an einem Kalendertag stattfindet - unabhaengig davon, ob dafuer (noch) ein
 * Wecker im Alarm-Bestand steht.
 *
 * **Warum es diese Groesse ueberhaupt gibt.** Bis v1.25.1 haben `DndScheduleUseCase` (Trigger
 * "Waehrend der Dienstzeit" + Rufbereitschaft-Cutoff) und `DimScheduleUseCase` (Regel- und
 * Nacht-Fenster) ihre Schichtspannen aus `AlarmUseCase.getAllAlarms()` gezogen. Der
 * Alarm-Bestand ueberlebt die Weckzeit aber NICHT, und das ist auch richtig so: `AlarmRepository`
 * verwirft abgelaufene Alarme in beiden Ladepfaden und lehnt das Speichern eines vergangenen
 * Alarms ab - ein abgelaufener Alarm waere genau die verwaiste, armierte Leiche, gegen die die
 * uebrigen Zusicherungen dieses Projekts geschrieben sind.
 *
 * Am Emulator gemessen (14.08.2026, 20.08. 08:00 Uhr, Frueschicht 06:00-14:12, Alarm 05:30 bereits
 * gefeuert): `zen_mode=0`, Zen-Regel `STATE_FALSE` - "Waehrend der Dienstzeit" war mitten in der
 * Dienstzeit aus. Der erste `syncAlarms()` nach dem Klingeln raeumt den Alarm, und mit ihm
 * verschwand das Fenster der Schicht, die gerade laeuft.
 *
 * Deshalb die Trennung: **Ein Alarm ist ein Weckzeitpunkt, eine [ShiftSpan] ist ein Dienst.**
 *
 * [alarmTriggerTime] wird bewusst MITGEFUEHRT und ist nicht redundant: `DimWindowResolver` leitet
 * den KALENDERTAG eines Slots aus der Weckzeit ab (`DimWindowResolver.buildRuleSpans`). Ohne
 * diesen Wert waere die Tagesverankerung der Dimm-Fenster kaputt,
 * sobald der Alarm geraeumt ist - dieselbe Fehlerklasse, die schon einmal falsche Dimm-Naechte
 * erzeugt hat. Es ist die urspruenglich berechnete Weckzeit, auch wenn sie inzwischen verstrichen
 * ist oder der Nutzer den Wecker uebersprungen hat.
 *
 * Bewusste Folge: Spannen kennen **kein** `isActive` und kein "uebersprungen". Ein uebersprungener
 * oder deaktivierter Wecker aendert nichts daran, dass der Dienst stattfindet - Dimmen und
 * "Nicht stoeren" richten sich nach dem Dienst, nicht nach dem Wecker.
 */
@Serializable
data class ShiftSpan(
    val shiftName: String,
    val startTime: Long,
    val endTime: Long,
    val alarmTriggerTime: Long
)

/**
 * Persistenter Bestand der erkannten Schichtspannen im bestehenden [MainDataStore] (kein neuer
 * Namespace - die drei bestehenden bleiben getrennt).
 *
 * Geschrieben wird ausschliesslich aus `AlarmUseCase.syncAlarms()`, dem einzigen Einstiegspunkt
 * der Event->Alarm-Pipeline - inklusive der beiden Leer-Zweige ("keine Events", "keine passende
 * Schicht"). Ohne die Leer-Zweige bliebe eine alte Spanne stehen und hielte "Nicht stoeren"
 * dauerhaft an, waehrend die App "kein Dienst" anzeigt.
 */
@Singleton
class ShiftSpanStore @Inject constructor(
    @param:MainDataStore private val dataStore: DataStore<Preferences>
) {
    companion object {
        private val KEY_SHIFT_SPANS = stringPreferencesKey(KEY_SHIFT_SPANS_NAME)

        /** Oeffentlich, damit `ConfigBackupFormat` denselben Namen ausschliesst statt eines Duplikats. */
        const val KEY_SHIFT_SPANS_NAME = "shift_spans"

        /**
         * Rueckschau beim Aufraeumen. Mindestens `DimWindowResolver.LOOKBACK_DAYS`: eine am
         * Vorabend begonnene Spanne muss nach dem Datumswechsel noch da sein, sonst haelt die
         * naechste Neuberechnung nach 00:00 die laufende Nacht fuer "kein Fenster".
         *
         * DREI Tage statt einem seit der Blockposition (18.09.2026): ob ein Schicht-Tag der
         * erste, ein mittlerer oder der letzte einer Folge ist, liest der Resolver an seinen
         * NACHBARTAGEN ab. Die Fenster eines Tages werden aber bis in den Folgetag hinein
         * ausgewertet (Nachtdienst: der Vormittagsschlaf liegt am Tag danach) - zu dem Zeitpunkt
         * muss der VORTAG des Schicht-Tages noch im Bestand sein, also eine Spanne, die bis zu
         * zwei Tage zurueckliegt. Mit 24 h wurde der letzte von drei Nachtdiensten am Morgen
         * danach zum "einzelnen", und das Fenster "nur am letzten Tag" fiel weg.
         */
        const val RETENTION_MS = 3 * 24 * 60 * 60 * 1000L

        /**
         * Welche Spannen behalten werden. Reine Funktion, damit die Rueckschau-Grenze testbar ist,
         * ohne einen echten DataStore hochzuziehen (Projektkonvention: reine Logik wird getestet,
         * duenne Android-Wrapper nicht).
         */
        internal fun prune(spans: List<ShiftSpan>, now: Long): List<ShiftSpan> =
            spans.filter { it.endTime > now - RETENTION_MS }

        /**
         * Mischt den frischen Kalenderstand [neu] mit dem bisherigen Bestand [alt]: fuer alles,
         * was noch laeuft oder bevorsteht, ist [neu] die einzige Wahrheit (ein gestrichener oder
         * verschobener Dienst darf nicht als Rest zurueckbleiben); BEENDETE Spannen aus [alt]
         * bleiben dagegen erhalten, solange [prune] sie behaelt.
         *
         * WARUM: Der Kalender-Abruf beginnt bei "jetzt" (`CalendarRepository`, `timeMin = now`)
         * und liefert beendete Dienste nicht mehr. Ein reiner Vollersatz vergass sie mit dem
         * naechsten Sync - fuer Dimmer und DND war das lange gleichgueltig, weil ein beendeter
         * Dienst kein Fenster mehr aufspannt. Fuer die Blockposition ist er aber der NACHBAR,
         * an dem sich entscheidet, ob heute der erste oder der letzte Tag eines Blocks ist.
         * Reine Funktion, damit die Regel testbar ist.
         */
        internal fun mische(alt: List<ShiftSpan>, neu: List<ShiftSpan>, now: Long): List<ShiftSpan> {
            val beendeteAlte = alt.filter { it.endTime <= now && it !in neu }
            return prune(neu + beendeteAlte, now)
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * **Ein Lesefehler ist NICHT dieselbe Aussage wie "keine Schicht".** Eine leere Liste heisst
     * "heute kein Dienst" (Dimmen/DND aus), ein Fehlschlag heisst "ich weiss es nicht" - und der
     * muss beim Aufrufer als solcher ankommen, damit die Tick-Kette einen kurzen Retry plant
     * statt sechs Stunden zu schlafen. Genau diese Unterscheidung fehlte dem alten Alarm-Pfad an
     * der Dimmer-DND-Grenze und liess "Nicht stoeren" nach einem transienten Fehler bis zum
     * Morgen aus.
     *
     * Der `ReplaceFileCorruptionHandler` des [MainDataStore] faengt nur eine `CorruptionException`;
     * eine IOException reicht DataStore durch - deshalb hier ein eigener Fang. Ein nicht
     * dekodierbarer Wert wird ebenfalls als Fehlschlag gemeldet und NICHT als leere Liste
     * degradiert: stille Degradierung darf nie zur Schreibwahrheit werden.
     */
    suspend fun spansNow(): Result<List<ShiftSpan>> = try {
        val raw = dataStore.data.first()[KEY_SHIFT_SPANS]
        if (raw.isNullOrBlank()) {
            Result.success(emptyList())
        } else {
            Result.success(json.decodeFromString<List<ShiftSpan>>(raw))
        }
    } catch (e: CancellationException) {
        // Eine Cancellation ist kein Lesefehler - sie sagt nichts ueber den Bestand aus und darf
        // nicht als "nicht lesbar" gedeutet werden (gleiche Haltung wie SafeExecutor).
        throw e
    } catch (e: Exception) {
        Logger.e(LogTags.SHIFT, "Schichtspannen nicht lesbar", e)
        Result.failure(e)
    }

    /**
     * Uebernimmt den frischen Kalenderstand: laufende und kuenftige Spannen werden vollstaendig
     * ersetzt (damit kein Rest einer geloeschten Schicht zurueckbleibt), BEENDETE Spannen des
     * bisherigen Bestands bleiben bis zur Rueckschau-Grenze [RETENTION_MS] erhalten - siehe
     * [mische]. Ein nicht dekodierbarer Altbestand wird dabei nicht zum Fehler: dann zaehlt nur
     * der frische Stand, und die Blockposition ist fuer ein paar Tage ungenau statt der Dimmer
     * dauerhaft ohne Spannen.
     */
    suspend fun replaceAll(spans: List<ShiftSpan>, now: Long = System.currentTimeMillis()) {
        var kept = spans
        dataStore.edit { prefs ->
            val alt = prefs[KEY_SHIFT_SPANS]?.takeIf { it.isNotBlank() }?.let { raw ->
                runCatching { json.decodeFromString<List<ShiftSpan>>(raw) }.getOrElse { emptyList() }
            }.orEmpty()
            kept = mische(alt = alt, neu = spans, now = now)
            prefs[KEY_SHIFT_SPANS] = json.encodeToString(kept)
        }
        Logger.d(LogTags.SHIFT, "Schichtspannen gespeichert: ${kept.size} (${spans.size} frisch, Rest beendete aus dem Altbestand)")
    }
}
