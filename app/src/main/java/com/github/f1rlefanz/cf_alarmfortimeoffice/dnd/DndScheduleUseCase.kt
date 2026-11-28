package com.github.f1rlefanz.cf_alarmfortimeoffice.dnd

import android.app.AlarmManager
import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.service.notification.Condition
import android.service.notification.ZenPolicy
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import androidx.core.net.toUri
import com.github.f1rlefanz.cf_alarmfortimeoffice.MainActivity
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindowResolver
import com.github.f1rlefanz.cf_alarmfortimeoffice.freietage.FreieTageStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause.MasterPausePrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.OhneEintragWahl
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypNichtStoeren
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IShiftConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.ExakteAlarme
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Steuert die App-eigene [AutomaticZenRule] aus ZWEI Schaltern JE TYP (SPEC E7, seit #218), per OR
 * (Vereinigung) kombiniert, ueber EINEN rollenden exakten Alarm (Muster wie [DimScheduleUseCase]).
 * Einen globalen Schalter gibt es nicht mehr: `dnd_follow_dimmer_enabled`,
 * `dnd_during_shift_enabled`, `dnd_shift_excluded_shifts` und `dnd_oncall_cutoff_min` werden nicht
 * mehr gelesen (sie bleiben liegen - kopieren statt verschieben, SPEC 4).
 *
 *  1. "Solange gedimmt wird" ([TypNichtStoeren.solangeGedimmt], an der festen Zeile
 *     `OhneEintragEinstellungen.nichtStoerenSolangeGedimmt`): liest
 *     [DimScheduleUseCase.previewTimelineWithStatus] direkt, gefiltert auf die Tage, deren Typ den
 *     Schalter an hat (Einbahnstrasse - der Dimmer bleibt unwissend von DND). Keine eigene
 *     Fenster-Definition, kein Drift-Risiko: es gibt nur eine Quelle der Wahrheit fuer "wann dimmt".
 *  2. "Waehrend des Kalendereintrags" ([TypNichtStoeren.waehrendEintrag]): die rohe
 *     Kalender-Event-Spanne jeder Schicht, deren Typ den Schalter an hat ([DndShiftSpanResolver]).
 *
 * DND ist binaer (an/aus) - anders als beim Dimmer gibt es kein "dunkelste gewinnt", die Vereinigung
 * beider Quellen reicht (irgendeine aktive Quelle => an).
 *
 * Rufbereitschaft (Kategorie RUFBEREITSCHAFT + [TypNichtStoeren.endetSpaetestensMinuten]) ist KEINE
 * dritte Fenster-Quelle mit eigener Policy, sondern kappt beide obigen Quellen als letzten Schritt
 * am On-Call-Tag ([DndOnCallCutoffResolver.clip]) - an der Uhrzeit des EIGENEN Typs; dieselbe
 * Zen-Regel gilt bis dahin unveraendert weiter.
 *
 * Nutzt bewusst eine selbst registrierte [AutomaticZenRule] statt rohem
 * `NotificationManager.setInterruptionFilter()`: die Regel ist fuer den Nutzer sichtbar unter
 * Einstellungen -> Ton -> Nicht stoeren -> Zeitplaene, mit eigener [ZenPolicy] statt der globalen,
 * manuell gesetzten Policy zu ueberschreiben - koexistiert so mit dem manuellen DND des Nutzers und
 * fremden Automatisierungen (Bixby/Tasker), statt sie zu ueberschreiben. Nur ab API 30 verfuegbar
 * (configurationActivity-Ownership ohne ConditionProviderService), siehe [isSupported].
 *
 * Was stummgeschaltet wird, kommt aus [DndPrefs.Policy]. Android konsolidiert gleichzeitig aktive
 * Zen-Regeln zugunsten der freizuegigsten - Hergang reference/dnd.md.
 */
@Singleton
class DndScheduleUseCase @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val shiftSpanStore: ShiftSpanStore,
    private val freieTageStore: FreieTageStore,
    private val dimSchedule: DimScheduleUseCase,
    private val prefs: DndPrefs,
    private val masterPausePrefs: MasterPausePrefs,
    private val shiftConfigRepository: IShiftConfigRepository
) {
    companion object {
        const val ACTION_TICK = "com.github.f1rlefanz.cf_alarmfortimeoffice.DND_SCHED_TICK"

        // NICHT 7710 (Dimmer-Tick, dimmer/DimScheduleUseCase) oder 0 (6h-Wartung,
        // AlarmMaintenanceService) wiederverwenden - eigene, unabhaengige Alarm-Kette.
        private const val REQ_DND_TICK = 7712

        /**
         * Bewusst `by lazy`: `Uri.parse()` ist im Unit-Test-JVM ein Stub, eager risse das die ganze
         * Companion-Initialisierung mit - Hergang reference/dnd.md.
         */
        private val CONDITION_ID: Uri by lazy { "condition://com.github.f1rlefanz.cf_alarmfortimeoffice/dnd".toUri() }
        private const val RULE_NAME = "CFAlarm Ruhezeit"

        /** Retry-Abstand, wenn der Alarm-Bestand gerade NICHT lesbar war (transienter Fehler). */
        private const val RETRY_MS = 15 * 60_000L

        /** Keep-alive-Abstand, wenn eine Fenster-Quelle AN ist, aber gerade kein Fenster-Rand in
         *  der Zukunft liegt (z. B. Urlaubswoche ohne Schichten). */
        private const val KEEPALIVE_MS = 6 * 60 * 60_000L

        /**
         * Naechster Tick, wenn KEIN Fenster-Rand mehr in der Zukunft liegt. Bewusst eine eigene
         * Kopie neben [DimScheduleUseCase.fallbackTick] - die beiden Ketten (eigene Request-Codes,
         * unabhaengig deaktivierbar) bleiben absichtlich getrennt.
         *
         * Ohne den Fallback reisst die Kette hier endgueltig ab: nach einer Urlaubswoche ohne
         * Schichten ist die Fensterliste leer, der letzte Tick cancelt den Alarm - und der spaeter
         * eintreffende neue Dienstplan wird von Aufrufern synchronisiert (CalendarViewModel/
         * ShiftViewModel/CalendarPreAlarmRefreshWorker), die DND NICHT nacharmieren. "Waehrend der
         * Dienstzeit" blieb dadurch bis zum naechsten Reboot wirkungslos. Ist keine Fenster-Quelle
         * aktiv, bleibt die Kette self-cleaning (`null` = Alarm abbestellen).
         */
        @VisibleForTesting
        internal fun fallbackTick(now: Long, alarmReadFailed: Boolean, anySourceEnabled: Boolean): Long? = when {
            alarmReadFailed -> now + RETRY_MS
            anySourceEnabled -> now + KEEPALIVE_MS
            else -> null
        }

        /**
         * Fenster-Zugehoerigkeit HALB OFFEN (`first <= now < last`), nicht `now in range`: ein Tick
         * exakt auf dem Fensterende liesse DND sonst bis zum naechsten Fensterstart haengen.
         * `DndScheduleTickChainTest` haelt die drei Raender fest.
         */
        @VisibleForTesting
        internal fun isActiveAt(range: LongRange, now: Long): Boolean =
            now >= range.first && now < range.last
    }

    /** Fenster-Ergebnis samt der beiden Gruende, aus denen es LEER sein kann (siehe [fallbackTick]). */
    private data class WindowSet(
        val fenster: List<DndFenster>,
        val alarmReadFailed: Boolean,
        val anySourceEnabled: Boolean
    ) {
        /** Die reinen Zeitbereiche - alles ausser der Diagnose rechnet nur damit weiter. */
        val ranges: List<LongRange> get() = fenster.map { it.range }
    }

    /** Nur ab Android 11 (API 30) - configurationActivity-Ownership ohne ConditionProviderService. */
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    suspend fun enable() {
        applyCurrentState()
        scheduleNextTransition()
    }

    /**
     * Setzt die Zen-Regel-Bedingung auf inaktiv und storniert den rollenden Tick-Alarm - registriert
     * die Regel bewusst NICHT ab ([NotificationManager.removeAutomaticZenRule]), sie bleibt sichtbar
     * und inaktiv liegen. Ein spaeteres [enable] findet sie ueber [ensureZenRule] wieder und muss
     * nichts neu anlegen.
     */
    suspend fun disable() {
        if (!isSupported()) return
        val nm = notificationManager()
        val ruleId = prefs.zenRuleIdNow()
        if (ruleId.isNotBlank()) {
            try {
                nm.setAutomaticZenRuleState(ruleId, Condition(CONDITION_ID, "", Condition.STATE_FALSE))
            } catch (e: SecurityException) {
                Logger.e(LogTags.DND, "disable() ohne Freigabe aufgerufen", e)
            }
        }
        alarmManager().cancel(buildPendingIntent())
    }

    suspend fun applyCurrentState() {
        if (!isSupported()) return
        // Zentraler Master-Pause-Backstop - Vorbild AlarmUseCase.syncAlarms(): jeder
        // DndViewModel-Setter ruft enable() UNGEGATET auf, und der rollende Tick plant sich selbst
        // nach. Ohne diesen Fangnetz-Punkt genuegt ein einziger Tap im DND-Screen waehrend der
        // Pause, um die Zen-Regel + Tick-Kette dauerhaft wieder anzuwerfen. disable() bleibt
        // bewusst UNgegatet, damit MasterPauseUseCase.pause() weiter durchkommt.
        if (masterPausePrefs.pausedNow()) {
            protokolliereZustand(null, DndDiagnostik.AusGrund.MASTER_PAUSE, 0)
            disable()
            return
        }
        val nm = notificationManager()
        if (!nm.isNotificationPolicyAccessGranted) {
            Logger.w(LogTags.DND, "Keine Freigabe fuer Benachrichtigungszugriff - DND-Tick uebersprungen")
            return
        }
        val ruleId = ensureZenRule(nm) ?: return
        val now = System.currentTimeMillis()
        val set = computeWindows()
        // Halb offen, siehe isActiveAt (dort steht das WARUM, und dort ist es getestet).
        val aktivesFenster = set.fenster.firstOrNull { isActiveAt(it.range, now) }
        val active = aktivesFenster != null
        try {
            nm.setAutomaticZenRuleState(
                ruleId,
                Condition(CONDITION_ID, "", if (active) Condition.STATE_TRUE else Condition.STATE_FALSE)
            )
            // ERST NACH dem erfolgreichen Setzen: die Zeile behauptet einen Zustand des GERAETS,
            // nicht bloss eine Absicht dieser App.
            protokolliereZustand(
                aktivesFenster,
                when {
                    set.alarmReadFailed && set.fenster.isEmpty() -> DndDiagnostik.AusGrund.LESEFEHLER
                    !set.anySourceEnabled -> DndDiagnostik.AusGrund.KEINE_QUELLE
                    set.fenster.isEmpty() -> DndDiagnostik.AusGrund.KEIN_FENSTER_TROTZ_QUELLE
                    else -> DndDiagnostik.AusGrund.AUSSERHALB
                },
                set.fenster.size
            )
        } catch (e: SecurityException) {
            Logger.e(LogTags.DND, "setAutomaticZenRuleState ohne Freigabe aufgerufen", e)
        }
    }

    /**
     * Der zuletzt PROTOKOLLIERTE Zustand - nicht der zuletzt gesetzte.
     *
     * Dient nur dazu, aus dem rollenden Tick (der auch ohne Wechsel feuert - Keep-alive alle 6 h,
     * Retry nach 15 min, dazu jeder ViewModel-Setter) eine Zeile PRO WECHSEL zu machen statt einer
     * pro Tick. Bewusst nur im Speicher: stirbt der Prozess, wird die Zeile einmal wiederholt -
     * das ist die harmlose Richtung. Sie zu persistieren hiesse, fuer eine Log-Zeile im CE-Storage
     * zu lesen und zu schreiben.
     */
    @Volatile
    private var letzteZustandszeile: String? = null

    /**
     * Schreibt die Zustandszeile ins Release-Log - aber nur, wenn sich etwas geaendert hat.
     *
     * WARN und nicht INFO, weil genau das der Zweck ist: Release-Logs tragen nur WARN und hoeher,
     * und die Frage "war die Ruhezeit gestern Nacht an?" stellt sich immer erst hinterher. Ein
     * Wechsel pro Fenstergrenze sind an einem gewoehnlichen Tag vier Zeilen; dieselbe Abwaegung
     * wie beim Vorwecken und bei visibilitySnapshot().
     */
    private fun protokolliereZustand(
        aktiv: DndFenster?,
        grund: DndDiagnostik.AusGrund,
        fensterGesamt: Int
    ) {
        val zeile = DndDiagnostik.zustandszeile(aktiv, grund, fensterGesamt, ZoneId.systemDefault())
        if (zeile == letzteZustandszeile) {
            Logger.d(LogTags.DND, "unveraendert: $zeile")
            return
        }
        letzteZustandszeile = zeile
        Logger.w(LogTags.DND, zeile)
    }

    suspend fun scheduleNextTransition() {
        if (!isSupported()) return
        val am = alarmManager()
        val pi = buildPendingIntent()
        // Master-Pause-Backstop, siehe applyCurrentState().
        if (masterPausePrefs.pausedNow()) {
            am.cancel(pi)
            Logger.d(LogTags.DND, "Master-Pause aktiv - kein DND-Tick geplant")
            return
        }
        val next = computeNextTransition(System.currentTimeMillis())
        if (next == null) {
            am.cancel(pi)
            return
        }
        ExakteAlarme.stelle(am, next, pi)
        Logger.d(LogTags.DND, "Naechster DND-Wechsel geplant: ${java.util.Date(next)}")
    }

    // --- Fenster-Berechnung ---

    private suspend fun computeWindows(): WindowSet {
        // ZUERST und STRIKT die Schicht-Konfiguration: seit #218 stehen ALLE Schalter am Typ
        // (SPEC E7), es gibt keinen globalen mehr. Ist sie nicht lesbar (vor der ersten Entsperrung
        // verweigert das Repository den Read), weiss niemand, ob ein Schalter an ist: keine
        // Ruhezeit, aber die Kette bestellt sich NICHT ab - `alarmReadFailed` plant den 15-min-Retry.
        // Dieselbe Behandlung wie der Dimmer (#217). Ein Fehler darf nie zu "kein Schalter an"
        // werden, sonst risse die Kette an einem voruebergehenden Lesefehler ab.
        val config = shiftConfigRepository.getCurrentShiftConfig().getOrElse { e ->
            Logger.w(LogTags.DND, "Schicht-Konfiguration nicht lesbar - keine Ruhezeit, Retry (${e.javaClass.simpleName})")
            return WindowSet(emptyList(), alarmReadFailed = true, anySourceEnabled = true)
        }
        val schalter = DndTypSchalter(config)
        // Kein Typ und nicht die feste Zeile hat einen der beiden Schalter an: nichts zu schalten,
        // die Kette darf sich abbestellen (self-cleaning). Der Ruf-Cutoff allein loest nichts aus,
        // er kappt nur. Jede Aenderung an einem Typ armiert neu (`ZeitkettenArmierer`).
        if (!schalter.irgendeinSchalterAn) {
            return WindowSet(emptyList(), alarmReadFailed = false, anySourceEnabled = false)
        }

        val out = mutableListOf<DndFenster>()
        var alarmReadFailed = false

        if (schalter.irgendeinSolangeGedimmt) {
            // "Solange gedimmt wird": die Dimm-Zeitleiste, gefiltert auf die Tage, deren Typ (bzw.
            // die feste Zeile) den Schalter an hat - VOR dem Vereinen, im Dimmer (`nurTage`).
            // Keine zweite Fensterlogik (Einbahnstrasse `dnd/` -> `dimmer/`).
            // previewTimelineWithStatus statt previewTimeline: der Dimmer liest Konfiguration,
            // Alarm-Bestand und Spannen INNERHALB seiner Fensterberechnung, und ein transienter
            // Lesefehler kommt dort als LEERE Fensterliste heraus - von "heute gibt es kein Fenster"
            // nicht zu unterscheiden. Ohne diesen Kanal plante DND den 6-h-Keep-alive statt des
            // 15-min-Retry: der Dimmer dimmte die Nacht nach 15 Minuten, DND kam erst morgens.
            // OHNE den Nachlauf nach der Weckzeit (DimWindow.weckzeitNachlaufMinuten): der Bildschirm
            // darf nach dem Wecken noch gedimmt bleiben, "Nicht stoeren" endet aber an der Weckzeit -
            // wer gerade geweckt wurde, soll erreichbar sein (Nutzerentscheidung 13.10.2026).
            val preview = dimSchedule.previewTimelineWithStatus(
                mitWeckzeitNachlauf = false,
                nurTage = schalter::solangeGedimmt
            )
            out += preview.intervals.map { DndFenster(it.range, DndQuelle.FOLGT_DIMMER) }
            if (preview.alarmReadFailed) alarmReadFailed = true
        }

        // Schichtspannen werden auch NUR fuer den Ruf-Cutoff geholt (ohne jeden "Waehrend des
        // Kalendereintrags") - der Cutoff kappt auch die Fenster von "Solange gedimmt wird".
        //
        // Quelle ist der ShiftSpanStore, nicht der Alarm-Bestand (der ueberlebt die Weckzeit nicht);
        // eine Spanne kennt bewusst kein `isActive` - der Dienst findet trotzdem statt.
        val spans = if (schalter.irgendeinWaehrendEintrag || schalter.hatRufTyp) {
            shiftSpanStore.spansNow().getOrElse {
                Logger.w(LogTags.DND, "Schichtspannen nicht lesbar - keine Kalendereintrag-/Ruf-Fenster, Retry")
                // Kein Fenster aus den Spannen, aber der Lesefehler wird gemerkt: sonst wuerde ein
                // einmaliger Fehler die ganze Tick-Kette abreissen (15-min-Retry statt Keep-alive).
                alarmReadFailed = true
                emptyList()
            }.let { rohe ->
                // FREIGEGEBENE TAGE: an einem freigegebenen Tag findet der Dienst nicht statt -
                // also weder Kalendereintrag-Fenster noch Ruf-Cutoff. Gefiltert wird an derselben
                // Stelle wie im Dimmer und mit derselben Funktion, damit ein Tag nicht in einem
                // der beiden Features frei ist und im anderen nicht. "Solange gedimmt wird" liest
                // ueber previewTimelineWithStatus() bereits die gefilterte Zeitleiste.
                FreieTageStore.filtereSpannen(rohe, freieTageStore.freieTageNow(), ZoneId.systemDefault())
            }
        } else {
            emptyList()
        }

        if (schalter.irgendeinWaehrendEintrag) {
            // "Waehrend des Kalendereintrags" am Typ ersetzt den globalen Schalter UND die
            // Ausnahmenliste (`dnd_during_shift_enabled`, `dnd_shift_excluded_shifts` werden nicht
            // mehr gelesen): eine Spanne zaehlt nur, wenn ihr Typ den Schalter an hat.
            val slots = spans.map {
                DndShiftSpanResolver.AlarmSlot(it.shiftName, it.startTime, it.endTime)
            }
            out += DndShiftSpanResolver.buildShiftSpans(slots, schalter::waehrendEintrag)
                .map { DndFenster(it, DndQuelle.DIENSTZEIT) }
        }

        if (schalter.hatRufTyp && out.isNotEmpty()) {
            // "Endet spaetestens um" JE Rufbereitschafts-Typ ersetzt `dnd_oncall_cutoff_min`. Jeder
            // Cutoff kappt weiter JEDES Fenster beider Schalter, das ihn echt enthaelt - erst je
            // Fenster kappen, dann (im Aktiv-Test) vereinen. Der Anker ist der Beginn der Spanne
            // (`shiftStartTime`), nicht die Weckzeit.
            val cutoffSlots = spans.map {
                DndOnCallCutoffResolver.AlarmSlot(it.shiftName, it.startTime)
            }
            val cutoffs = DndOnCallCutoffResolver.cutoffInstants(
                cutoffSlots, schalter::rufCutoffMinuten, ZoneId.systemDefault()
            )
            // clip() bildet 1:1 und reihenfolgetreu ab (es kuerzt, teilt nicht und fasst nicht
            // zusammen) - nur deshalb laesst sich die Herkunft per Index zurueckheften. Die
            // Groessenpruefung ist der Waechter dafuer: wer clip() spaeter teilen laesst, faellt
            // hier auf die ungeklippten Fenster zurueck, statt Herkunft und Zeit zu vertauschen.
            val geklippteRanges = DndOnCallCutoffResolver.clip(out.map { it.range }, cutoffs)
            val mitHerkunft = if (geklippteRanges.size == out.size) {
                out.mapIndexed { i, f ->
                    f.copy(range = geklippteRanges[i], geklippt = geklippteRanges[i] != f.range)
                }
            } else {
                Logger.w(
                    LogTags.DND,
                    "clip() bildet nicht mehr 1:1 ab - Herkunft der Fenster nicht zuordenbar"
                )
                out
            }
            return WindowSet(mitHerkunft, alarmReadFailed = alarmReadFailed, anySourceEnabled = true)
        }
        return WindowSet(out, alarmReadFailed = alarmReadFailed, anySourceEnabled = true)
    }

    /** Die Fenster eines Ticks samt Herkunft - nur fuer Tests (der Planer braucht AlarmManager). */
    @VisibleForTesting
    internal suspend fun fensterJetzt(): List<DndFenster> = computeWindows().fenster

    /** Naechster Tick-Zeitpunkt (`null` = Kette abbestellen). [VisibleForTesting], weil der
     *  eigentliche Planer darueber hinaus AlarmManager/PendingIntent braucht - beides in der
     *  Unit-Test-JVM nicht verfuegbar. */
    @VisibleForTesting
    internal suspend fun computeNextTransition(now: Long): Long? {
        val w = computeWindows()
        return w.ranges
            .flatMap { listOf(it.first, it.last) }
            .filter { it > now }
            .minOrNull()
            ?: fallbackTick(now, w.alarmReadFailed, w.anySourceEnabled)
    }

    // --- AutomaticZenRule-Verwaltung ---

    // Serialisiert die gesamte Lese-Entscheide-Schreibe-Sequenz aus ensureZenRule() ueber ALLE
    // Aufrufer hinweg (rollender Tick, Schalter-Toggles, Master-Pause, BootReceiver) - diese Klasse
    // ist ein @Singleton, also ist diese eine Mutex-Instanz tatsaechlich geteilt. Ohne sie koennen
    // zwei gleichzeitige Aufrufe beide einen leeren storedId lesen, bevor einer zurueckschreibt, und
    // dadurch zwei separate AutomaticZenRule-Instanzen beim System registrieren - eine davon wird nie
    // wieder aktualisiert/verwaist.
    private val zenRuleMutex = Mutex()

    /**
     * Liefert die registrierte Regel-ID; registriert bei Bedarf neu (Erstregistrierung ODER falls
     * der Nutzer die Regel extern - z. B. in den System-Einstellungen - geloescht hat:
     * [NotificationManager.getAutomaticZenRule] liefert dann null fuer eine gespeicherte, aber nicht
     * mehr existierende ID) und aktualisiert eine bereits bestehende Regel IMMER mit der aktuellen
     * [DndPrefs.Policy] - sonst wirkt eine Options-Aenderung des Nutzers erst nach einer
     * Neuinstallation, weil die einmal registrierte Regel ihre alte Policy sonst dauerhaft behaelt.
     */
    private suspend fun ensureZenRule(nm: NotificationManager): String? {
        // Direkter SDK_INT-Check (nicht nur ueber isSupported()) noetig, damit Lint den Aufruf
        // von buildAutomaticZenRule() (@RequiresApi R) hier als abgesichert erkennt.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return zenRuleMutex.withLock {
            val storedId = prefs.zenRuleIdNow()
            val rule = buildAutomaticZenRule()
            try {
                if (storedId.isNotBlank() && nm.getAutomaticZenRule(storedId) != null) {
                    nm.updateAutomaticZenRule(storedId, rule)
                    storedId
                } else {
                    val newId = nm.addAutomaticZenRule(rule)
                    prefs.setZenRuleId(newId)
                    newId
                }
            } catch (e: SecurityException) {
                Logger.e(LogTags.DND, "Zen-Regel-Verwaltung ohne Freigabe aufgerufen", e)
                null
            }
        }
    }

    /**
     * Baut die Zen-Regel aus der Nutzer-Policy ([DndPrefs.Policy]) - bewusst NICHTS hart codiert,
     * siehe Klassenkommentar von [DndPrefs.Policy]. Der Wecker selbst ist von `blockAlarms`
     * unabhaengig: `AlarmSoundService.setBypassDnd(true)` umgeht JEDE DND-Konfiguration, auch diese
     * hier - `blockAlarms` betrifft nur FREMDE Wecker/Alarm-Apps, die der Nutzer bewusst zulassen
     * oder stummschalten kann.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun buildAutomaticZenRule(): AutomaticZenRule {
        val component = ComponentName(context, MainActivity::class.java)
        val p = prefs.policyNow()
        val intent = resolveDndZenPolicyIntent(p)
        val policy = ZenPolicy.Builder()
            .allowRepeatCallers(p.allowRepeatCallers)
            .allowCalls(intent.allowCallsPeopleType)
            .allowMessages(intent.allowMessagesPeopleType)
            .allowConversations(intent.allowConversationsSenders)
            .allowReminders(intent.allowReminders)
            .allowEvents(intent.allowEvents)
            .allowAlarms(intent.allowAlarms)
            .allowMedia(intent.allowMedia)
            .allowSystem(intent.allowSystem)
            .build()
        return AutomaticZenRule(
            RULE_NAME,
            component,
            component,
            CONDITION_ID,
            policy,
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            true
        )
    }

    private fun notificationManager() =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun alarmManager() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun buildPendingIntent(): PendingIntent {
        val intent = Intent(context, DndScheduleReceiver::class.java).setAction(ACTION_TICK)
        return PendingIntent.getBroadcast(
            context,
            REQ_DND_TICK,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

/**
 * Die Schalter von "Nicht stoeren" am Typ (#218, SPEC E7), aus EINEM Lesestand der Konfiguration.
 *
 * Ein Schichtname wird so auf seinen Typ abgebildet wie im Dimmer (`TypDimmQuelle.fuerSchicht`):
 * der ERSTE Typ dieses Namens, Gross/Klein egal. Nur so gehoert "Solange gedimmt wird" zu demselben
 * Typ, dessen Fenster der Dimmer an diesem Tag rechnet. Ein Name, den kein Typ traegt, hat keinen
 * Schalter an.
 *
 * Die feste Zeile "Ohne Kalendereintrag" gilt an Tagen ohne Schichtspanne (frei, freigegeben). Steht
 * sie auf "wie <Typ>", zaehlt dessen Schalter - aufgeloest wie die Typ-Seite
 * ([OhneEintragWahl.gewaehlterTyp]) und wie die Dimm-Fenster dieser Tage; sonst ihr eigener.
 * "Waehrend des Kalendereintrags" hat sie nicht: an diesen Tagen steht nichts im Kalender.
 */
internal class DndTypSchalter(config: ShiftConfig) {

    private val typen: List<ShiftDefinition> = config.definitions

    private val festeZeileSolangeGedimmt: Boolean =
        OhneEintragWahl.gewaehlterTyp(config)?.nichtStoeren?.solangeGedimmt
            ?: config.ohneKalendereintrag.nichtStoerenSolangeGedimmt

    private fun typ(schicht: String): ShiftDefinition? =
        typen.firstOrNull { it.name.equals(schicht, ignoreCase = true) }

    /** Hat irgendein Typ oder die feste Zeile "Solange gedimmt wird" an? */
    val irgendeinSolangeGedimmt: Boolean =
        festeZeileSolangeGedimmt || typen.any { it.nichtStoeren.solangeGedimmt }

    /** Hat irgendein Typ "Waehrend des Kalendereintrags" an? */
    val irgendeinWaehrendEintrag: Boolean = typen.any { it.nichtStoeren.waehrendEintrag }

    /** `anySourceEnabled` der Tick-Kette: irgendein Typ oder die feste Zeile hat einen der beiden Schalter an. */
    val irgendeinSchalterAn: Boolean get() = irgendeinSolangeGedimmt || irgendeinWaehrendEintrag

    /** Gibt es einen Rufbereitschafts-Typ (dann werden die Spannen fuer den Cutoff gebraucht)? */
    val hatRufTyp: Boolean = typen.any { it.kategorie == ShiftKategorie.RUFBEREITSCHAFT }

    /** Folgt "Nicht stoeren" an dem Tag dieser Herkunft dem Dimmer? */
    fun solangeGedimmt(herkunft: DimWindowResolver.SpanHerkunft): Boolean = when (herkunft) {
        is DimWindowResolver.SpanHerkunft.Schicht -> typ(herkunft.name)?.nichtStoeren?.solangeGedimmt == true
        DimWindowResolver.SpanHerkunft.OhneSchicht -> festeZeileSolangeGedimmt
    }

    /** Hat der Typ dieser Schicht "Waehrend des Kalendereintrags" an? */
    fun waehrendEintrag(schicht: String): Boolean = typ(schicht)?.nichtStoeren?.waehrendEintrag == true

    /** "Endet spaetestens um" (Minuten) des Rufbereitschafts-Typs dieser Schicht; `null` = kein Ruf-Typ. */
    fun rufCutoffMinuten(schicht: String): Int? =
        typ(schicht)?.takeIf { it.kategorie == ShiftKategorie.RUFBEREITSCHAFT }?.nichtStoeren?.endetSpaetestensMinuten
}
