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
         * Rueckschau beim Aufraeumen. Entspricht `DimWindowResolver.LOOKBACK_DAYS`: eine am
         * Vorabend begonnene Spanne muss nach dem Datumswechsel noch da sein, sonst haelt die
         * naechste Neuberechnung nach 00:00 die laufende Nacht fuer "kein Fenster".
         */
        const val RETENTION_MS = 24 * 60 * 60 * 1000L

        /**
         * Welche Spannen behalten werden. Reine Funktion, damit die Rueckschau-Grenze testbar ist,
         * ohne einen echten DataStore hochzuziehen (Projektkonvention: reine Logik wird getestet,
         * duenne Android-Wrapper nicht).
         */
        internal fun prune(spans: List<ShiftSpan>, now: Long): List<ShiftSpan> =
            spans.filter { it.endTime > now - RETENTION_MS }
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
     * Ersetzt den Bestand vollstaendig und entfernt dabei Spannen, deren Ende laenger als
     * [RETENTION_MS] zurueckliegt. Vollersatz statt Read-Modify-Write, weil `syncAlarms()` ohnehin
     * immer den kompletten erkannten Stand liefert - damit kann kein Rest einer geloeschten
     * Schicht zurueckbleiben.
     */
    suspend fun replaceAll(spans: List<ShiftSpan>, now: Long = System.currentTimeMillis()) {
        val kept = prune(spans, now)
        dataStore.edit { it[KEY_SHIFT_SPANS] = json.encodeToString(kept) }
        Logger.d(LogTags.SHIFT, "Schichtspannen gespeichert: ${kept.size} (${spans.size - kept.size} abgelaufen verworfen)")
    }
}
