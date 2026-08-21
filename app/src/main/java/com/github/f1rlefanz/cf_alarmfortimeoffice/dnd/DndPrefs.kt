package com.github.f1rlefanz.cf_alarmfortimeoffice.dnd

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
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
    /**
     * Zieht die beiden Schicht-AUSWAHLEN dieser Klasse auf den neuen Namen nach, wenn eine
     * Schichtdefinition UMBENANNT wurde. Liefert die Anzahl der geaenderten Listen (0, 1 oder 2),
     * oder einen Fehlschlag.
     *
     * WARUM ES DAS GEBEN MUSS: [onCallShifts] und [shiftExcludedShifts] speichern SCHICHTNAMEN,
     * waehrend der Schicht-Editor den Namen bei gleichbleibender `id` frei aendern laesst - genau
     * dieselbe Bindung ueber den Namen wie `DimRule.shiftPattern` (siehe
     * [com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRuleUseCase.renameShiftPattern],
     * Vorbild fuer Semantik und Fehlerbehandlung). Der Nachzug fuer Dimmer- und Hue-Regeln kam in
     * v1.30.0, diese beiden Listen wurden dabei uebersehen.
     *
     * Die Folge war unsichtbar und teuer: die Chips im DND-Bildschirm werden aus den AKTUELLEN
     * Definitionsnamen gebaut, der gespeicherte ALT-Name taucht dort gar nicht auf - die Auswahl
     * stand danach einfach leer da. Bei [onCallShifts] heisst das, dass der
     * Rufbereitschaft-Cutoff ([DndOnCallCutoffResolver]) nicht mehr greift: In der Nacht VOR der
     * Rufbereitschaft bleibt "Nicht stoeren" ueber 05:00 hinaus an, der Nutzer ist nicht
     * erreichbar, und nichts weist darauf hin.
     *
     * EXAKTER VERGLEICH, und genau deshalb ist auch eine reine SCHREIBWEISEN-Aenderung eine
     * Umbenennung: Beide Konsumenten pruefen Mengen-Zugehoerigkeit ohne Toleranz
     * (`it.shiftName in onCallShifts` in [DndOnCallCutoffResolver], `alarm.shiftName in
     * excludedShifts` in [DndShiftSpanResolver]). Korrigiert der Nutzer "abrufdienst" zu
     * "Abrufdienst", trifft der gespeicherte Alt-Eintrag ab sofort nie wieder - anders als bei den
     * Dimm-/Hue-REGELN, die gross-/kleinschreibungsblind vergleichen und eine solche Aenderung
     * folgenlos ueberstehen. `ShiftViewModel.planeSchichtUmbenennungen` stieg bis zum 21.08.2026
     * bei `equals(ignoreCase = true)` aus und liess genau diesen Fall liegen.
     *
     * Ein Eintrag, der sich nur in der Schreibweise vom ALTEN Namen unterscheidet ("ad1" bei einer
     * Umbenennung von "AD1"), bleibt dagegen unberuehrt: er hat auch vorher nie getroffen, und ihn
     * mitzuziehen machte aus einem toten Eintrag eine scharfe Ausnahme, die der Nutzer nie
     * eingerichtet hat.
     *
     * KEIN BLINDES SCHREIBEN: Eine Liste, die den Altnamen nicht enthaelt, wird nicht angefasst.
     * IDEMPOTENT: Ein zweiter Lauf findet den Altnamen nicht mehr vor und schreibt nicht.
     */
    suspend fun renameShiftName(oldName: String, newName: String): Result<Int> = runCatching {
        require(oldName.isNotBlank() && newName.isNotBlank()) {
            "Leerer Schichtname - DND-Auswahl wird NICHT nachgezogen ('$oldName' -> '$newName')"
        }
        if (oldName == newName) return@runCatching 0

        var geaendert = 0
        // EINE Transaktion fuer beide Listen: DataStore.edit{} liest und schreibt atomar, ein
        // gleichzeitiger Chip-Tap kann so keine der beiden Aenderungen verlieren.
        dataStore.edit { p ->
            // Zuruecksetzen im Block, nicht davor: der Transform-Block ist die einzige Stelle, die
            // wirklich zaehlt, was geschrieben wurde.
            geaendert = 0
            geaendert += p.zieheSchichtnamenNach(KEY_ONCALL_SHIFTS, oldName, newName)
            geaendert += p.zieheSchichtnamenNach(KEY_SHIFT_EXCLUDED_SHIFTS, oldName, newName)
        }

        if (geaendert > 0) {
            Logger.business(
                LogTags.DND,
                "🔁 DND-Schichtauswahl: $geaendert Liste(n) von '$oldName' auf '$newName' nachgezogen"
            )
        }
        geaendert
    }.onFailure { error ->
        // WARN+ landet auch im Release-Log: eine nicht nachgezogene Rufbereitschaft-Auswahl ist ein
        // Telefon, das in der Nacht vor dem Dienst laenger stumm bleibt als vom Nutzer eingestellt.
        Logger.e(
            LogTags.DND,
            "❌ DND-Schichtauswahl konnte nicht von '$oldName' auf '$newName' nachgezogen werden",
            error
        )
    }

    /**
     * Entfernt [name] aus beiden Schicht-AUSWAHLEN. Liefert die Anzahl der geaenderten Listen
     * (0, 1 oder 2), oder einen Fehlschlag.
     *
     * WOFUER: der BLOCKIERTE Fall einer Umbenennung - der gespeicherte Name gehoert nach einem
     * Namenstausch inzwischen einer ANDEREN Schichtdefinition (siehe
     * `ShiftViewModel.zieheRegelmusterNach`). Fuer eine Dimm-/Hue-REGEL ist Nichtstun dort ehrlich:
     * sie wird wirkungslos und steht sichtbar in ihrer Regelliste. Fuer diese beiden Listen ist
     * Nichtstun das GEGENTEIL von ehrlich - der Eintrag ist nicht tot, sondern ab sofort scharf
     * fuer die falsche Schicht, und im Bildschirm sieht er aus wie eine bewusste Auswahl.
     *
     * WARUM ENTFERNEN DIE SICHERE RICHTUNG IST - je Liste einzeln geprueft, denn die Wirkung zeigt
     * NICHT in dieselbe Richtung:
     *  - [onCallShifts]: Der stehen gelassene Eintrag beendet "Nicht stoeren" in der Nacht vor der
     *    FALSCHEN Schicht vorzeitig (Cutoff 05:00) - eine Nachtruhe, die der Nutzer nie abbestellt
     *    hat. Entfernen gibt dieser Schicht genau das zurueck, was fuer sie eingestellt war
     *    (naemlich nichts).
     *  - [shiftExcludedShifts]: Hier wirkt der Eintrag andersherum - er nimmt die falsche Schicht
     *    von "Nicht stoeren waehrend der Dienstzeit" AUS, ihr Telefon klingelt also mehr als
     *    eingestellt. Das ist zwar die harmlosere Richtung, aber es bleibt eine Einstellung, die
     *    der Nutzer fuer diese Schicht nie getroffen hat und im Bildschirm nicht als fremd erkennt.
     *
     * WAS ENTFERNEN NICHT KOSTET: Die UMBENANNTE Schicht ist in beiden Faellen ohnehin schutzlos -
     * ihr neuer Name steht nirgends in der Liste, der Alt-Eintrag half ihr also auch vorher nicht.
     * Deshalb dominiert Entfernen das Stehenlassen: es nimmt genau eine Falschzuordnung weg und
     * verliert nichts. Was der umbenannten Schicht fehlt, muss der Nutzer neu setzen - und genau
     * das sagt ihm die Meldung, samt Namen der Schicht.
     *
     * KEIN BLINDES SCHREIBEN, IDEMPOTENT: wie [renameShiftName].
     */
    suspend fun removeShiftName(name: String, partnerName: String = ""): Result<Int> = runCatching {
        require(name.isNotBlank()) {
            "Leerer Schichtname - es wird NICHTS aus der DND-Auswahl entfernt"
        }

        var geaendert = 0
        dataStore.edit { p ->
            geaendert = 0
            geaendert += p.entferneSchichtnamen(KEY_ONCALL_SHIFTS, name, partnerName)
            geaendert += p.entferneSchichtnamen(KEY_SHIFT_EXCLUDED_SHIFTS, name, partnerName)
        }

        if (geaendert > 0) {
            Logger.business(
                LogTags.DND,
                "🧹 DND-Schichtauswahl: '$name' aus $geaendert Liste(n) entfernt - der Name gehoert " +
                    "jetzt einer anderen Schicht und haette dort still gewirkt"
            )
        }
        geaendert
    }.onFailure { error ->
        // WARN+: bleibt der Eintrag stehen, wirkt eine Einstellung fuer eine Schicht, fuer die sie
        // nie getroffen wurde - und niemand sieht es.
        Logger.e(LogTags.DND, "❌ '$name' konnte nicht aus der DND-Auswahl entfernt werden", error)
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

/**
 * Ersetzt [oldName] durch [newName] in einer gespeicherten Schichtnamen-Menge. Liefert 1, wenn
 * geschrieben wurde, sonst 0 - siehe [DndPrefs.renameShiftName] fuer das Warum (exakter Vergleich,
 * kein blindes Schreiben).
 *
 * Steht als Datei-private Funktion neben der einzigen Klasse, die sie braucht. Ein gemeinsamer
 * Helfer mit dem gleichnamigen in `DimOverlayPrefs` waere eine Abstraktion ueber fuenf Zeilen und
 * zwei Paketen - die Begruendung, WARUM exakt verglichen wird, haengt dagegen am jeweiligen
 * Konsumenten und gehoert genau dorthin.
 */
private fun MutablePreferences.zieheSchichtnamenNach(
    key: Preferences.Key<Set<String>>,
    oldName: String,
    newName: String
): Int {
    val current = this[key] ?: return 0
    if (oldName !in current) return 0
    this[key] = current - oldName + newName
    return 1
}

/**
 * Entfernt [name] aus einer gespeicherten Schichtnamen-Menge. Liefert 1, wenn geschrieben wurde,
 * sonst 0 - siehe [DndPrefs.removeShiftName] fuer das Warum.
 *
 * Ein leer gewordener Schluessel wird auf die leere Menge gesetzt statt entfernt: die Leseseite
 * liest beides als leere Menge, und `remove()` wuerde beim naechsten Lesen den Unterschied
 * zwischen "nie eingestellt" und "bewusst geraeumt" einebnen.
 */
private fun MutablePreferences.entferneSchichtnamen(
    key: Preferences.Key<Set<String>>,
    name: String,
    partnerName: String = ""
): Int {
    val current = this[key] ?: return 0
    if (name !in current) return 0
    // TAUSCHFALL: Stehen BEIDE Namen in der Liste, ist ihr Inhalt nach dem Tausch weiterhin exakt
    // richtig - die Liste meint beide Schichten, und welcher Name zu welcher gehoert, ist ihr egal.
    // Hier zu raeumen wuerde eine korrekte Einstellung zerstoeren: der On-Call-Cutoff griffe danach
    // fuer KEINE der beiden Schichten mehr, und "Nicht stoeren" bliebe in der Nacht vor dem Dienst
    // ueber den Cutoff hinaus an - genau der Schaden, gegen den der Nachzug gebaut ist.
    if (partnerName.isNotBlank() && partnerName in current) return 0
    this[key] = current - name
    return 1
}
