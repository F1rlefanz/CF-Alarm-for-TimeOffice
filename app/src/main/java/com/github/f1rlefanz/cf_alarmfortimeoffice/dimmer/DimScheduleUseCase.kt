package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.VisibleForTesting
import com.github.f1rlefanz.cf_alarmfortimeoffice.freietage.FreieTageStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause.MasterPausePrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Berechnet die Dimm-Zeitfenster aus DREI unabhängigen Quellen und steuert EINEN rollenden,
 * exakten Alarm auf den nächsten Fenster-Rand (Muster wie NachtDimmer-ScheduleManager /
 * [com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmMaintenanceService]):
 *
 *  1. Wellness (global): `[Weckzeit − Wind-down, Weckzeit]` je aktivem Alarm.
 *  2. Regeln: pro Kalendertag die passende [DimRule] (Schicht-Tag → Schicht/UNIVERSAL, freier Tag
 *     → FREI/UNIVERSAL; siehe [DimRuleUseCase]). CLOCK↔CLOCK-Fenster = lückenlos jede Nacht,
 *     ALARM/SHIFT_END schicht-relativ, leere Fensterliste = Unterdrückung. Details in
 *     [DimWindowResolver.buildRuleSpans].
 *  3. Nacht-Standard (seit v1.17.0, [DimOverlayPrefs.Toggles.nightDefaultEnabled]): ab einer festen
 *     Uhrzeit bis zum naechsten Wecker (bzw. bis zu einer zweiten festen Uhrzeit an Tagen ohne
 *     Alarm) - OHNE dass dafuer eine Regel angelegt werden muss. Ausgeschlossen sind explizit
 *     markierte Schichten ([DimOverlayPrefs.nightDefaultExcludedShifts]) UND jeder Tag, den eine
 *     vorhandene Regel (Punkt 2) ohnehin schon abdeckt. Details in
 *     [DimWindowResolver.buildDefaultNightSpans].
 *
 * Overlay ist an, wenn `now` in irgendeinem Fenster liegt (Vereinigung). Fail-open: lässt sich
 * der Alarm-Bestand nicht lesen, wird NICHT gedimmt.
 */
@Singleton
class DimScheduleUseCase @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val alarmUseCase: IAlarmUseCase,
    private val shiftSpanStore: ShiftSpanStore,
    private val freieTageStore: FreieTageStore,
    private val dimRuleUseCase: DimRuleUseCase,
    private val prefs: DimOverlayPrefs,
    private val correctionNotifier: DimCorrectionNotifier,
    private val masterPausePrefs: MasterPausePrefs
) {
    companion object {
        const val ACTION_TICK = "com.github.f1rlefanz.cf_alarmfortimeoffice.DIM_SCHED_TICK"
        private const val REQ_TICK = 7710
        private const val HORIZON_DAYS = 14
        private const val MIN_MS = 60_000L

        /** Retry-Abstand, wenn der Alarm-Bestand gerade NICHT lesbar war (transienter Fehler). */
        private const val RETRY_MS = 15 * 60_000L

        /** Keep-alive-Abstand, wenn mindestens eine Quelle AN ist, aber gerade kein Fenster-Rand
         *  in der Zukunft liegt (z. B. Urlaubswoche ohne Schichten). */
        private const val KEEPALIVE_MS = 6 * 60 * 60_000L

        /**
         * Naechster Tick, wenn KEIN Fenster-Rand mehr in der Zukunft liegt. Ohne diesen Fallback
         * reisst die rollende Tick-Kette an dieser Stelle endgueltig ab und kann sich nicht selbst
         * wiederbeleben - sie haengt dann komplett an einem externen `enable()`. Real relevant:
         * nach einer Urlaubswoche ohne Schichten ist die Fensterliste leer, der letzte Tick
         * cancelt den Alarm; der neue Dienstplan wird spaeter von Aufrufern synchronisiert
         * (CalendarViewModel/ShiftViewModel/CalendarPreAlarmRefreshWorker), die Dimmer/DND
         * NICHT nacharmieren.
         *
         * Aendert NICHTS an der Bedeutung einer leeren Fensterliste (= heute wird nicht gedimmt,
         * z. B. Nachtdienst-Unterdrueckung) - es wird nur nachgesehen, statt aufzugeben. Ist gar
         * keine Quelle aktiv, bleibt die Kette bewusst self-cleaning (`null` = Alarm abbestellen).
         */
        @VisibleForTesting
        internal fun fallbackTick(now: Long, alarmReadFailed: Boolean, anySourceEnabled: Boolean): Long? = when {
            alarmReadFailed -> now + RETRY_MS
            anySourceEnabled -> now + KEEPALIVE_MS
            else -> null
        }
    }

    /** Fenster-Ergebnis samt der beiden Gruende, aus denen es LEER sein kann (siehe [fallbackTick]). */
    private data class Windows(
        val spans: List<DimWindowResolver.DimSpan>,
        val alarmReadFailed: Boolean,
        val anySourceEnabled: Boolean
    )

    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** Ist-Zustand anwenden + nächsten Wechsel planen. Self-cleaning, wenn nichts aktiv ist. */
    suspend fun enable() {
        applyCurrentState()
        scheduleNextTransition()
    }

    /**
     * Räumt den laufenden Overlay-Zustand + die Korrektur-Notification weg und storniert den
     * rollenden [REQ_TICK]-Alarm - symmetrisch zu [enable]. Rührt bewusst KEINEN
     * [DimOverlayPrefs.Toggles]-Wert an, damit ein späteres [enable] die exakt vorherige
     * Konfiguration wiederherstellt.
     */
    suspend fun disable() {
        prefs.setActiveOverlay(false, prefs.strengthNow(), prefs.warmthNow())
        correctionNotifier.cancel()
        alarmManager().cancel(buildPendingIntent())
    }

    /**
     * Wendet den Ist-Zustand an UND die Dimmer-Korrektur-Notification (Feature C), falls per
     * [DimOverlayPrefs.correctionNotificationEnabled] aktiviert. Ein evtl. gesetzter
     * [DimOverlayPrefs.Override] (Heller/Dunkler/Pause) wird nur angewendet, solange er noch zur
     * gerade aktiven Fenster-Spanne gehört ([DimWindowResolver.isOverrideStale]) - ein stale
     * gewordener Override wird hier automatisch weggeräumt, kein zusätzlicher Timer nötig (der
     * ohnehin rollende Tick ruft diese Funktion an jeder Fenstergrenze neu auf).
     */
    suspend fun applyCurrentState() {
        // Zentraler Master-Pause-Backstop - Vorbild AlarmUseCase.syncAlarms(): jeder
        // DimmerViewModel-/NotificationSettingsViewModel-Setter ruft enable() UNGEGATET auf, und der
        // rollende Tick plant sich selbst nach. Ohne diesen Fangnetz-Punkt genuegt eine einzige
        // Einstellungs-Aenderung waehrend der Pause, um Dimmen + Tick-Kette dauerhaft wieder
        // anzuwerfen, obwohl die UI "pausiert" anzeigt. disable() bleibt bewusst UNgegatet, damit
        // MasterPauseUseCase.pause() weiter durchkommt.
        if (masterPausePrefs.pausedNow()) {
            Logger.d(LogTags.DIMMER, "Master-Pause aktiv - Dimmen ausgesetzt")
            // Derselbe Aufraeumpfad wie disable(), aber ohne den rollenden Alarm anzufassen.
            // ACHTUNG, keine Garantie: applyCurrentState() wird auch OHNE
            // scheduleNextTransition() gerufen (DimNotificationService, DimmerRulesViewModel,
            // DimmerViewModel.previewDim) - "beide immer zusammen" gilt nur fuer enable().
            // Storniert wird der REQ_TICK-Alarm heute ausschliesslich von disable() bzw.
            // scheduleNextTransition(), und die Pause wird nur ueber MasterPauseUseCase.pause()
            // gesetzt, das disable() mit-ruft. Wer das Pause-Flag kuenftig woanders setzt, MUSS
            // disable() hinterherrufen - sonst bleibt der Exact-Alarm stehen und weckt das Geraet
            // weiter (wirkungslos, aber nicht kostenlos).
            prefs.setActiveOverlay(false, prefs.strengthNow(), prefs.warmthNow())
            correctionNotifier.cancel()
            return
        }
        val now = System.currentTimeMillis()
        // Aktive Spanne (überlappen mehrere, gewinnt die dunkelste – Logik in DimWindowResolver).
        val active = DimWindowResolver.activeSpan(windows(), now)

        // Serialisiert gegen DimNotificationService's eigenes Read-Modify-Write auf denselben
        // Override-Zustand - siehe DimOverlayPrefs.withOverrideLock.
        val (override, stale) = prefs.withOverrideLock {
            val ov = prefs.overrideNow()
            val isStale = DimWindowResolver.isOverrideStale(active?.range?.last, active?.strength, ov.windowEnd, ov.windowStrength)
            if (isStale && (ov.strengthDelta != 0 || ov.paused || ov.windowEnd != 0L)) {
                prefs.clearOverride()
            }
            ov to isStale
        }

        if (active == null) {
            prefs.setActiveOverlay(false, prefs.strengthNow(), prefs.warmthNow())
            correctionNotifier.cancel()
            return
        }

        val effectiveDelta = if (stale) 0 else override.strengthDelta
        val isPaused = !stale && override.paused
        val effectiveStrength = DimWindowResolver.applyStrengthDelta(active.strength, effectiveDelta, DimOverlayPrefs.STRENGTH_MAX)

        // Diagnostik fuer "hat gedimmt/nicht gedimmt tatsaechlich stattgefunden": isRunning() ist
        // der einzige echte Bound-Status des Accessibility-Dienstes. Ohne diese Zeile ist im
        // Nachhinein aus dem Log nicht rekonstruierbar, ob ein aktives Fenster auf einen NICHT
        // gebundenen Dienst traf (z. B. ECM-Restricted-Settings nach Sideload).
        Logger.d(
            LogTags.DIMMER,
            "Dimm-Fenster aktiv: strength=$effectiveStrength warmth=${active.warmth} paused=$isPaused " +
                "accessibilityServiceBound=${DimAccessibilityService.isRunning()}"
        )

        if (isPaused) {
            // paused=true => gar kein Overlay berechnen (nicht nur strength=0 - der Nutzer hat
            // das Dimmen bewusst ausgesetzt).
            prefs.setActiveOverlay(false, prefs.strengthNow(), prefs.warmthNow())
        } else {
            prefs.setActiveOverlay(true, effectiveStrength, active.warmth)
        }

        if (prefs.correctionNotificationEnabledNow()) {
            correctionNotifier.show(effectiveStrength, active.warmth, isPaused)
        } else {
            correctionNotifier.cancel()
        }
    }

    /**
     * Aktive Spanne JETZT, ohne Seiteneffekt - fuer [DimNotificationService], damit der den
     * korrekten `windowEnd`-Schluessel fuer einen Override kennt, ohne die Fensterlogik zu
     * duplizieren.
     */
    suspend fun activeSpanNow(): DimWindowResolver.DimSpan? = DimWindowResolver.activeSpan(windows(), System.currentTimeMillis())

    suspend fun scheduleNextTransition() {
        val am = alarmManager()
        val pi = buildPendingIntent()
        // Master-Pause-Backstop, siehe applyCurrentState().
        if (masterPausePrefs.pausedNow()) {
            am.cancel(pi)
            Logger.d(LogTags.DIMMER, "Master-Pause aktiv - kein Dimm-Tick geplant")
            return
        }
        val next = computeNextTransition(System.currentTimeMillis())
        if (next == null) {
            am.cancel(pi)
            return
        }
        val canBeExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.canScheduleExactAlarms()
        } else {
            true
        }
        if (canBeExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        }
        Logger.d(LogTags.DIMMER, "Naechster Dimm-Wechsel geplant: ${java.util.Date(next)}")
    }

    // --- Fenster-Berechnung (reine Zeitmathematik in DimWindowResolver) ---

    /**
     * Vorschau samt dem Grund, aus dem sie LEER sein kann. Ohne [alarmReadFailed] ist ein
     * transienter Lesefehler des Alarm-Bestands von "heute gibt es einfach kein Fenster" nicht zu
     * unterscheiden - und genau diese Unterscheidung braucht DND-Modus 1 ("Schlaf-Fenster folgt dem
     * Dimmer"), um seinen 15-Minuten-Retry-Tick statt des 6-Stunden-Keep-alive zu planen. Der
     * Dimmer selbst holt sich den Retry ueber [computeNextTransition]/[fallbackTick]; ohne diesen
     * Kanal erholte sich der Dimmer nach 15 Minuten, DND aber erst nach 6 Stunden - die laufende
     * Nacht wurde gedimmt, blieb aber ohne "Nicht stoeren".
     */
    data class TimelinePreview(
        val intervals: List<DimWindowResolver.ResolvedInterval>,
        val alarmReadFailed: Boolean
    )

    /** Siehe [previewTimeline] - identische Berechnung, zusaetzlich mit dem Lesefehler-Status. */
    suspend fun previewTimelineWithStatus(): TimelinePreview {
        val now = System.currentTimeMillis()
        val w = computeWindows()
        return TimelinePreview(
            intervals = DimWindowResolver.mergeToTimeline(w.spans).filter { it.range.last > now },
            alarmReadFailed = w.alarmReadFailed
        )
    }

    /** Vorschau fuer die UI (siehe [DimWindowResolver.mergeToTimeline]) - identische Berechnung wie
     * der echte Scheduler, aber ohne jeden Seiteneffekt. Bereits BEENDETE Abschnitte werden
     * ausgeblendet: die Fenster-Quellen rechnen bewusst einen Kalendertag zurueck (siehe
     * `DimWindowResolver.LOOKBACK_DAYS`), damit eine ueber Mitternacht laufende Nacht nach dem
     * Datumswechsel nicht verschwindet - fuer "die naechsten Abschnitte" ist die Vornacht aber
     * Rauschen. Fuer DND-Modus 1 macht der Filter keinen Unterschied: dessen Aktiv-Test ist ebenso
     * halb offen (`first <= now < last`, siehe `DndScheduleUseCase.isActiveAt`), ein hier
     * herausgefiltertes Intervall (`range.last <= now`) faellt dort also genauso durch. */
    suspend fun previewTimeline(): List<DimWindowResolver.ResolvedInterval> =
        previewTimelineWithStatus().intervals

    private suspend fun windows(): List<DimWindowResolver.DimSpan> = computeWindows().spans

    private suspend fun computeWindows(): Windows {
        val toggles = prefs.togglesNow()
        val anySourceEnabled = toggles.wellnessEnabled || toggles.rulesEnabled || toggles.nightDefaultEnabled
        if (!anySourceEnabled) return Windows(emptyList(), alarmReadFailed = false, anySourceEnabled = false)

        val alarmsResult = alarmUseCase.getAllAlarms()
        if (alarmsResult.isFailure) {
            // Fail-open unveraendert: kein Dimming. Aber die Tick-Kette darf daran nicht
            // ABREISSEN - alarmReadFailed sorgt in scheduleNextTransition fuer einen Retry-Tick.
            Logger.w(LogTags.DIMMER, "Alarm-Bestand nicht lesbar - kein Dimming (fail-open)")
            return Windows(emptyList(), alarmReadFailed = true, anySourceEnabled = true)
        }
        val alarms = alarmsResult.getOrDefault(emptyList()).filter { it.isActive }

        // Schichtspannen sind seit v1.25.2 die Quelle fuer alles SCHICHT-bezogene (Regel-Fenster,
        // Nacht-Standard). Der Alarm-Bestand ueberlebt die Weckzeit nicht - ein SHIFT_END-
        // verankertes Fenster verschwand deshalb mitten in der Schicht, sobald der Wecker
        // geklingelt hatte und der naechste Sync ihn geraeumt hatte. Siehe ShiftSpanStore.
        //
        // Die WELLNESS-Quelle unten bleibt bewusst am echten Alarm-Bestand: sie dimmt vor der
        // Weckzeit, ihr Fenster ist nach dem Klingeln ohnehin vorbei.
        val spansResult = shiftSpanStore.spansNow()
        if (spansResult.isFailure) {
            Logger.w(LogTags.DIMMER, "Schichtspannen nicht lesbar - kein Dimming (fail-open)")
            return Windows(emptyList(), alarmReadFailed = true, anySourceEnabled = true)
        }
        // FREIGEGEBENE TAGE: an einem vom Nutzer freigegebenen Tag findet der Dienst nicht statt,
        // also darf er auch keine SCHICHT-Fenster erzeugen. Gefiltert wird hier, an der Quelle -
        // damit Regel-Fenster UND Nacht-Standard dieselbe Wahrheit sehen. Der Tag sieht danach fuer
        // die Fensterlogik aus wie jeder andere freie Tag (FREI-Regel, Nacht-Standard), und das ist
        // Absicht: der Nutzer HAT frei. Ein Lesefehler des Freigabe-Speichers degradiert auf
        // "keine Freigabe" - lieber einmal zuviel gedimmt als eine unerwartet helle Nacht.
        val spans = FreieTageStore.filtereSpannen(
            spannen = spansResult.getOrDefault(emptyList()),
            freieTage = freieTageStore.freieTageNow(),
            zone = zone
        )

        val out = mutableListOf<DimWindowResolver.DimSpan>()
        val gStrength = prefs.strengthNow()
        val gWarmth = prefs.warmthNow()

        // 1. Wellness (global): Wind-down vor jeder Weckzeit – mit der globalen Intensität.
        if (toggles.wellnessEnabled) {
            val windDownMs = prefs.windDownMinutesNow() * MIN_MS
            alarms.forEach {
                out += DimWindowResolver.DimSpan((it.triggerTime - windDownMs)..it.triggerTime, gStrength, gWarmth)
            }
        }

        // 2. Regeln: pro Kalendertag GENAU eine Regel (Anker-Semantik in
        //    DimWindowResolver.buildRuleSpans). CLOCK↔CLOCK = jede Nacht (lückenlos, ermöglicht
        //    „immer 22–7 außer ND"), ALARM/SHIFT_END = schicht-relativ, leere Fensterliste = ND-Ausnahme.
        //    Welche Regel das ist, entscheidet der Resolver seit Pruefrunde 8 aus ALLEN Schichten
        //    des Tages: `ruleForShift` wird pro Schicht gefragt (an einem Tag mit Fruehdienst UND
        //    Rufbereitschaft wurde die zweite Regel vorher nie gefragt). Unterdrueckung schlaegt
        //    dabei alles (leere Fensterliste = ausdrueckliche Nutzerentscheidung); bei zwei
        //    widersprechenden spezifischen Regeln gewinnt die Regel der fruehesten Schicht, und der
        //    Konflikt geht als WARN ins Log. Wichtig fuer diese Stelle: den Tag in so einem Fall
        //    einfach auszulassen waere KEIN "nur nicht dimmen" - Punkt 3 unten nimmt jeden von einer
        //    Regel getroffenen Tag aus dem Nacht-Standard heraus, der Tag bliebe also ganz ohne
        //    Dimm-Quelle, obwohl die Oberflaeche beide Regeln als aktiv zeigt.
        val rules = if (toggles.rulesEnabled) dimRuleUseCase.getAllRules() else emptyList()
        val rulesActive = toggles.rulesEnabled && rules.any { it.enabled }
        // `triggerTime` MUSS hier die urspruenglich berechnete Weckzeit sein, auch wenn sie
        // laengst verstrichen ist: DimWindowResolver leitet daraus den KALENDERTAG des Slots ab
        // (buildRuleSpans/buildDefaultNightSpans). Genau deshalb fuehrt ShiftSpan sie mit - mit
        // einem Platzhalter waere der Slot auf 1970 datiert und die Tagesverankerung der
        // Dimm-Fenster kaputt.
        val slots = spans.map { DimWindowResolver.AlarmSlot(it.alarmTriggerTime, it.shiftName, it.endTime) }
        if (rulesActive) {
            out += DimWindowResolver.buildRuleSpans(
                alarms = slots,
                horizonDays = HORIZON_DAYS,
                today = LocalDate.now(zone),
                zone = zone,
                ruleForShift = { name -> dimRuleUseCase.findRuleForShift(name, rules) },
                ruleForFreeDay = { dimRuleUseCase.findRuleForFreeDay(rules) },
            )
        }

        // 3. Nacht-Standard: ab fester Uhrzeit bis zum naechsten Wecker, ohne dass dafuer eine
        //    Regel angelegt werden muss. Ausgeschlossen sind: explizit vom Nutzer markierte
        //    Schichten (Toggle an der Karte) UND - falls "Regeln" aktiv ist - jeder Tag, den Punkt 2
        //    ohnehin schon abdeckt (rulesActive steuert dieselbe Ausschliesslichkeit wie dort).
        //    Dieses Praedikat wird vom Resolver seit Pruefrunde 8 fuer JEDE Schicht des Tages
        //    aufgerufen; ein Treffer schliesst den ganzen Tag aus. Die Signatur bleibt bewusst
        //    "ein Name je Aufruf" - die Buendelung gehoert in den Resolver, nicht hierher.
        if (toggles.nightDefaultEnabled) {
            val excludedShifts = prefs.nightDefaultExcludedShiftsNow()
            out += DimWindowResolver.buildDefaultNightSpans(
                alarms = slots,
                horizonDays = HORIZON_DAYS,
                today = LocalDate.now(zone),
                zone = zone,
                startClockMinutes = prefs.nightDefaultStartMinutesNow(),
                freeDayEndClockMinutes = prefs.nightDefaultFreeEndMinutesNow(),
                strength = prefs.nightDefaultStrengthNow(),
                warmth = prefs.nightDefaultWarmthNow(),
                isExcluded = { shiftName ->
                    if (shiftName != null) {
                        shiftName in excludedShifts || (rulesActive && dimRuleUseCase.findRuleForShift(shiftName, rules) != null)
                    } else {
                        rulesActive && dimRuleUseCase.findRuleForFreeDay(rules) != null
                    }
                },
            )
        }
        return Windows(out, alarmReadFailed = false, anySourceEnabled = true)
    }

    private suspend fun computeNextTransition(now: Long): Long? {
        val w = computeWindows()
        return w.spans
            .flatMap { listOf(it.range.first, it.range.last) }
            .filter { it > now }
            .minOrNull()
            ?: fallbackTick(now, w.alarmReadFailed, w.anySourceEnabled)
    }

    private fun alarmManager() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun buildPendingIntent(): PendingIntent {
        val intent = Intent(context, DimScheduleReceiver::class.java).setAction(ACTION_TICK)
        return PendingIntent.getBroadcast(
            context,
            REQ_TICK,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
