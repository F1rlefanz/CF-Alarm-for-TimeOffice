package com.github.f1rlefanz.cf_alarmfortimeoffice.dnd

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.qualifiers.MainDataStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Einstellungen der DND-Steuerung (im bestehenden [MainDataStore]). Zwei unabhängige, per OR
 * kombinierte Fenster-Quellen (siehe [DndScheduleUseCase]):
 * - `followDimmerEnabled`: DND an, waehrend der Dimmer dimmt (liest [com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimScheduleUseCase.previewTimeline],
 *   keine eigene Fenster-Definition - Einbahnstrasse, der Dimmer bleibt unveraendert/unwissend).
 * - `duringShiftEnabled`: DND an von Schichtbeginn bis Schichtende (Kalender-Event-Spanne, siehe
 *   [DndShiftSpanResolver]), mit expliziten Schicht-Ausnahmen ([shiftExcludedShifts]).
 *
 * Eine DRITTE, unabhaengige Einstellung [onCallShifts]/[onCallCutoffMinutes] ist KEINE dritte
 * Fenster-Quelle, sondern kappt die beiden obigen auf einen festen Cutoff an Rufbereitschafts-Tagen
 * (siehe [DndOnCallCutoffResolver]) - dieselbe [Policy] gilt bis zum Cutoff unveraendert weiter.
 *
 * [Policy] entscheidet, WAS die eine gemeinsame [android.app.AutomaticZenRule] stummschaltet -
 * gilt fuer beide Fenster-Quellen gleich (es gibt nur eine registrierte Regel, siehe
 * [DndScheduleUseCase]). Bewusst NICHT hart codiert: ein frueherer Entwurf blockierte
 * Medien/Wecker per Default ohne Nutzer-Entscheidung - das schaltete am 28.07.2026 live einen
 * laufenden Podcast stumm, ohne dass der Nutzer die Lautstaerke selbst wieder anheben konnte
 * (`allowMedia(false)` wirkt auf die Medien-Audiospur, nicht nur auf Benachrichtigungstoene).
 * Default-Haltung seither: alles, was ein Nutzer typischerweise NICHT waehrend eines Anrufs/einer
 * Nachricht stumm haben will (Wecker, Medien, System-Toene), ist per Default AUS (unberuehrt);
 * alles, was zum eigentlichen Zweck von "Nicht stoeren" gehoert (Anrufe, Nachrichten, Erinnerungen,
 * Termine), ist per Default AN.
 */
@Singleton
class DndPrefs @Inject constructor(
    @param:MainDataStore private val dataStore: DataStore<Preferences>
) {
    companion object {
        private val KEY_FOLLOW_DIMMER = booleanPreferencesKey("dnd_follow_dimmer_enabled")
        private val KEY_DURING_SHIFT = booleanPreferencesKey("dnd_during_shift_enabled")
        private val KEY_SHIFT_EXCLUDED_SHIFTS = stringSetPreferencesKey("dnd_shift_excluded_shifts")
        private val KEY_ONCALL_SHIFTS = stringSetPreferencesKey("dnd_oncall_shifts")
        private val KEY_ONCALL_CUTOFF_MIN = intPreferencesKey("dnd_oncall_cutoff_min")
        private val KEY_ZEN_RULE_ID = stringPreferencesKey("dnd_zen_rule_id")

        /** Default-Cutoff 05:00 - siehe Plan-Kontext (Rufbereitschaft, frueh erreichbar). */
        const val DEFAULT_ONCALL_CUTOFF_MIN = 5 * 60

        private val KEY_BLOCK_CALLS = booleanPreferencesKey("dnd_policy_block_calls")
        private val KEY_ALLOW_REPEAT_CALLERS = booleanPreferencesKey("dnd_policy_allow_repeat_callers")
        private val KEY_BLOCK_MESSAGES = booleanPreferencesKey("dnd_policy_block_messages")
        private val KEY_BLOCK_CONVERSATIONS = booleanPreferencesKey("dnd_policy_block_conversations")
        private val KEY_BLOCK_REMINDERS = booleanPreferencesKey("dnd_policy_block_reminders")
        private val KEY_BLOCK_EVENTS = booleanPreferencesKey("dnd_policy_block_events")
        private val KEY_BLOCK_SYSTEM = booleanPreferencesKey("dnd_policy_block_system")
        private val KEY_BLOCK_MEDIA = booleanPreferencesKey("dnd_policy_block_media")
        private val KEY_BLOCK_ALARMS = booleanPreferencesKey("dnd_policy_block_alarms")
    }

    /** Die zwei Fenster-Quellen-Schalter. */
    data class Toggles(val followDimmerEnabled: Boolean, val duringShiftEnabled: Boolean)

    /** Was die gemeinsame Zen-Regel stummschaltet - siehe Klassenkommentar fuer die Defaults. */
    data class Policy(
        val blockCalls: Boolean = true,
        val allowRepeatCallers: Boolean = true,
        val blockMessages: Boolean = true,
        val blockConversations: Boolean = true,
        val blockReminders: Boolean = true,
        val blockEvents: Boolean = true,
        val blockSystem: Boolean = false,
        val blockMedia: Boolean = false,
        val blockAlarms: Boolean = false
    )

    /**
     * ALLE Lese-Flows dieser Klasse gehen hierueber, keiner mehr direkt auf `dataStore.data` -
     * dasselbe Muster wie [com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimOverlayPrefs]
     * auf demselben Store, und aus demselben Grund.
     *
     * Bis zum 18.08.2026 war jeder der sechs Flows hier ein blankes `dataStore.data.map{}` ohne
     * ein einziges `.catch`; die Klasse importierte `catch` nicht einmal. Der
     * `ReplaceFileCorruptionHandler` des `settings`-Stores faengt nur Korruption, eine
     * IOException (voller Speicher, EACCES, transienter Lesefehler) reicht DataStore in den Flow
     * durch - und alle sechs `*Now()`-Accessoren sind `first()` genau darauf. Der Wurf traf
     * damit zwei Stellen:
     *
     * 1. Den DND-Tick: `computeWindows()` kam nicht bis zu seinem Rueckgabewert, also wurde
     *    weder `setAutomaticZenRuleState()` gerufen noch der naechste Tick armiert. Die
     *    vorhandene Retry-Mechanik greift dort nicht - sie deckt Lesefehler ab, die als `Result`
     *    zurueckkommen, nicht solche, die geworfen werden. "Nicht stoeren" blieb im alten
     *    Zustand haengen, bis die 6h-Wartung die Kette neu armierte: bis zu sechs Stunden
     *    stummgeschaltetes Telefon - oder eine Nacht ganz ohne. Das bricht die Zusicherung
     *    "Die Tick-Kette darf nicht abreissen".
     * 2. Die Oberflaeche: derselbe Wurf beim Oeffnen des DND-Tabs.
     *
     * Degradiert wird auf LEERE Preferences, also auf die Defaults jedes einzelnen Flows -
     * beide Toggles `false`, "Nicht stoeren" also AUS. Das ist die fail-safe Richtung: ein
     * unerwartet stummgeschaltetes Telefon ist schlimmer als ein unerwartet klingelndes, und die
     * Regel dieser App lautet im Zweifel klingeln.
     *
     * Und die Notlage-Leere wird nicht zur Schreibwahrheit (CLAUDE.md, "Persistenz"): jeder
     * Setter unten geht ueber `dataStore.edit{}` mit eigenem Read, keiner speist einen dieser
     * Flows zurueck.
     */
    private val safeData: Flow<Preferences> = dataStore.data
        .catch { e ->
            Logger.e(
                LogTags.DND,
                "DND-Einstellungen nicht lesbar - degradiert auf Defaults (\"Nicht stoeren\" aus)",
                e
            )
            emit(emptyPreferences())
        }

    val toggles: Flow<Toggles> = safeData.map { p ->
        Toggles(
            followDimmerEnabled = p[KEY_FOLLOW_DIMMER] ?: false,
            duringShiftEnabled = p[KEY_DURING_SHIFT] ?: false
        )
    }

    val policy: Flow<Policy> = safeData.map { p ->
        Policy(
            blockCalls = p[KEY_BLOCK_CALLS] ?: true,
            allowRepeatCallers = p[KEY_ALLOW_REPEAT_CALLERS] ?: true,
            blockMessages = p[KEY_BLOCK_MESSAGES] ?: true,
            blockConversations = p[KEY_BLOCK_CONVERSATIONS] ?: true,
            blockReminders = p[KEY_BLOCK_REMINDERS] ?: true,
            blockEvents = p[KEY_BLOCK_EVENTS] ?: true,
            blockSystem = p[KEY_BLOCK_SYSTEM] ?: false,
            blockMedia = p[KEY_BLOCK_MEDIA] ?: false,
            blockAlarms = p[KEY_BLOCK_ALARMS] ?: false
        )
    }

    /** Schichtnamen, fuer die "Waehrend der Dienstzeit" NICHT gilt (z. B. Rufbereitschaft). */
    val shiftExcludedShifts: Flow<Set<String>> = safeData.map {
        it[KEY_SHIFT_EXCLUDED_SHIFTS] ?: emptySet()
    }

    /** Schichtnamen, die als Rufbereitschaft gelten (z. B. "AD1") - siehe [DndOnCallCutoffResolver]. */
    val onCallShifts: Flow<Set<String>> = safeData.map { it[KEY_ONCALL_SHIFTS] ?: emptySet() }

    /** Cutoff-Uhrzeit an Rufbereitschafts-Tagen, in Minuten seit Mitternacht. Default 05:00. */
    /**
     * Geklemmt auf einen echten Tageszeitpunkt: [DndOnCallCutoffResolver] rechnet
     * `LocalTime.ofSecondOfDay(cutoffMinutes * 60L)`, und das wirft bei negativem Wert oder ab
     * 1440 eine `DateTimeException` - der DND-Tick wuerde dann bei JEDEM Lauf sterben. Der Wert
     * liegt im `settings`-Store, kommt also auch aus dem Android-Backup und der
     * Konfigurationsdatei, nicht nur aus der eigenen UI.
     */
    val onCallCutoffMinutes: Flow<Int> = safeData.map {
        (it[KEY_ONCALL_CUTOFF_MIN] ?: DEFAULT_ONCALL_CUTOFF_MIN).coerceIn(0, 24 * 60 - 1)
    }

    /** Die einmal registrierte [android.app.AutomaticZenRule]-ID; leer = noch nicht registriert. */
    val zenRuleId: Flow<String> = safeData.map { it[KEY_ZEN_RULE_ID] ?: "" }

    suspend fun togglesNow(): Toggles = toggles.first()
    suspend fun policyNow(): Policy = policy.first()
    suspend fun shiftExcludedShiftsNow(): Set<String> = shiftExcludedShifts.first()
    suspend fun onCallShiftsNow(): Set<String> = onCallShifts.first()
    suspend fun onCallCutoffMinutesNow(): Int = onCallCutoffMinutes.first()
    suspend fun zenRuleIdNow(): String = zenRuleId.first()

    suspend fun setFollowDimmerEnabled(v: Boolean) = dataStore.edit { it[KEY_FOLLOW_DIMMER] = v }
    suspend fun setDuringShiftEnabled(v: Boolean) = dataStore.edit { it[KEY_DURING_SHIFT] = v }
    /** Atomarer Toggle statt Read-Modify-Write im Aufrufer - DataStore.edit{} serialisiert
     * konkurrierende Transaktionen, ein Doppel-Tap auf zwei Chips verliert so keine Aenderung mehr. */
    suspend fun toggleShiftExcludedShift(shiftName: String) = dataStore.edit { p ->
        val current = p[KEY_SHIFT_EXCLUDED_SHIFTS] ?: emptySet()
        p[KEY_SHIFT_EXCLUDED_SHIFTS] = if (shiftName in current) current - shiftName else current + shiftName
    }

    /** Siehe [toggleShiftExcludedShift] - gleiches Muster fuer die Rufbereitschaft-Chips. */
    suspend fun toggleOnCallShift(shiftName: String) = dataStore.edit { p ->
        val current = p[KEY_ONCALL_SHIFTS] ?: emptySet()
        p[KEY_ONCALL_SHIFTS] = if (shiftName in current) current - shiftName else current + shiftName
    }
    suspend fun setOnCallCutoffMinutes(v: Int) = dataStore.edit { it[KEY_ONCALL_CUTOFF_MIN] = v }
    suspend fun setZenRuleId(v: String) = dataStore.edit { it[KEY_ZEN_RULE_ID] = v }

    suspend fun setBlockCalls(v: Boolean) = dataStore.edit { it[KEY_BLOCK_CALLS] = v }
    suspend fun setAllowRepeatCallers(v: Boolean) = dataStore.edit { it[KEY_ALLOW_REPEAT_CALLERS] = v }
    suspend fun setBlockMessages(v: Boolean) = dataStore.edit { it[KEY_BLOCK_MESSAGES] = v }
    suspend fun setBlockConversations(v: Boolean) = dataStore.edit { it[KEY_BLOCK_CONVERSATIONS] = v }
    suspend fun setBlockReminders(v: Boolean) = dataStore.edit { it[KEY_BLOCK_REMINDERS] = v }
    suspend fun setBlockEvents(v: Boolean) = dataStore.edit { it[KEY_BLOCK_EVENTS] = v }
    suspend fun setBlockSystem(v: Boolean) = dataStore.edit { it[KEY_BLOCK_SYSTEM] = v }
    suspend fun setBlockMedia(v: Boolean) = dataStore.edit { it[KEY_BLOCK_MEDIA] = v }
    suspend fun setBlockAlarms(v: Boolean) = dataStore.edit { it[KEY_BLOCK_ALARMS] = v }
}
