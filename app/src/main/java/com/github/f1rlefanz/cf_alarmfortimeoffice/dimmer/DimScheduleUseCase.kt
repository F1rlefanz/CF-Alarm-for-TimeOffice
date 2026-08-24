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
 * Berechnet die Dimm-Zeitfenster aus EINER Quelle und steuert EINEN rollenden, exakten Alarm auf
 * den nächsten Fenster-Rand (Muster wie NachtDimmer-ScheduleManager /
 * [com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmMaintenanceService]).
 *
 * Die Quelle sind die [DimRule]n: pro Kalendertag die passende Regel (Schicht-Tag →
 * Schicht/UNIVERSAL, freier Tag → FREI/UNIVERSAL; siehe [DimRuleUseCase]). CLOCK↔CLOCK-Fenster =
 * lückenlos jede Nacht, ALARM/SHIFT_END schicht-relativ, `ALARM_SONST_CLOCK` als Ende = „bis zur
 * Weckzeit, spätestens um X", leere Fensterliste = Unterdrückung. Details in
 * [DimWindowResolver.buildRuleSpans].
 *
 * Die früheren Sonderquellen „Wellness/Wind-down" und „Nacht-Standard" sind entfallen: seit dem
 * Ende-Anker [DimAnchor.ALARM_SONST_CLOCK] lässt sich beides als gewöhnliche Regel ausdrücken —
 * Wellness als `ALARM -X` → `ALARM +0`, der Nacht-Standard als `CLOCK 22:00` →
 * `ALARM_SONST_CLOCK 07:00`, das für jede Kalendernacht gilt und keinen Folgetag-Sonderfall
 * braucht. Damit gibt es nur noch EINE Stelle, an der ein Fenster entsteht, und nur noch EINEN
 * Schalter.
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
        /** Retry-Abstand, wenn der Alarm-Bestand gerade NICHT lesbar war (transienter Fehler). */
        private const val RETRY_MS = 15 * 60_000L

        /** Keep-alive-Abstand, wenn der Dimmer AN ist, aber gerade kein Fenster-Rand
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
         * z. B. Nachtdienst-Unterdrueckung) - es wird nur nachgesehen, statt aufzugeben. Ist der
         * Dimmer aus, bleibt die Kette bewusst self-cleaning (`null` = Alarm abbestellen).
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
        val anySourceEnabled: Boolean,
        /**
         * Lag mindestens EINE aktivierte Regel vor? Rein diagnostisch (siehe [meldeAbschaltung]):
         * "Dimmer an, Regeln da, trotzdem kein Fenster" ist der einzige Aus-Weg, der spaeter
         * jemanden interessiert. Der Wert faellt bei der Berechnung ohnehin an - ihn hier
         * mitzufuehren spart einen zusaetzlichen DataStore-Read allein fuers Log.
         */
        val regelnVorhanden: Boolean = false
    )

    /** Nur fuer die Messung (siehe computeWindows) - laeuft im Release-Build ins Leere. */
    private val zaehler = java.util.concurrent.atomic.AtomicInteger(0)

    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** Ist-Zustand anwenden + nächsten Wechsel planen. Self-cleaning, wenn nichts aktiv ist. */
    suspend fun enable() {
        // EIN Schnappschuss fuer beide Schritte. Das spart nicht nur die zweite, identische
        // Berechnung (am Emulator gemessen: 2 Laeufe je enable(), warm ~2 ms) - es beseitigt vor
        // allem eine kleine, echte Unstimmigkeit: bisher rechneten beide Schritte unabhaengig,
        // Millisekunden auseinander. Faellt eine Fenstergrenze genau dazwischen, sieht
        // applyCurrentState() das Fenster noch als aktiv und schaltet das Overlay EIN, waehrend
        // scheduleNextTransition() dieselbe Grenze schon als vergangen verwirft und erst die
        // NAECHSTE plant - das Overlay bliebe bis dahin an. Mit einem gemeinsamen Schnappschuss
        // kann das nicht mehr passieren.
        //
        // BEWUSST NUR HIER: Die beiden Funktionen bleiben einzeln aufrufbar und rechnen dann
        // weiterhin selbst (DimNotificationService, die Vorschau-Pfade der ViewModels rufen
        // applyCurrentState() allein). "Beide immer zusammen" gilt nur fuer enable() - das ist
        // eine dokumentierte Zusicherung, keine Nachlaessigkeit.
        val schnappschuss = applyCurrentStateIntern()
        scheduleNextTransitionIntern(schnappschuss)
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
        applyCurrentStateIntern()
    }

    /**
     * Wie [applyCurrentState], liefert aber den verwendeten Fenster-Schnappschuss zurueck, damit
     * [enable] ihn an [scheduleNextTransitionIntern] weiterreichen kann. `null` heisst: es wurde
     * gar nicht gerechnet (Master-Pause) - dann soll der Planer selbst nachsehen.
     *
     * Privat, damit die oeffentliche Schnittstelle unveraendert bleibt: [Windows] ist ein
     * Implementierungsdetail und soll es bleiben.
     */
    private suspend fun applyCurrentStateIntern(): Windows? {
        // Zentraler Master-Pause-Backstop - Vorbild AlarmUseCase.syncAlarms(): jeder
        // DimmerViewModel-/NotificationSettingsViewModel-Setter ruft enable() UNGEGATET auf, und der
        // rollende Tick plant sich selbst nach. Ohne diesen Fangnetz-Punkt genuegt eine einzige
        // Einstellungs-Aenderung waehrend der Pause, um Dimmen + Tick-Kette dauerhaft wieder
        // anzuwerfen, obwohl die UI "pausiert" anzeigt. disable() bleibt bewusst UNgegatet, damit
        // MasterPauseUseCase.pause() weiter durchkommt.
        if (masterPausePrefs.pausedNow()) {
            meldeAbschaltung(DimDiagnostik.AbschaltGrund.MASTER_PAUSE)
            // Derselbe Aufraeumpfad wie disable(), aber ohne den rollenden Alarm anzufassen.
            // ACHTUNG, keine Garantie: applyCurrentState() wird auch OHNE
            // scheduleNextTransition() gerufen (DimNotificationService, DimmerRulesViewModel,
            // die Vorschau im Regel-Editor) - "beide immer zusammen" gilt nur fuer enable().
            // Storniert wird der REQ_TICK-Alarm heute ausschliesslich von disable() bzw.
            // scheduleNextTransition(), und die Pause wird nur ueber MasterPauseUseCase.pause()
            // gesetzt, das disable() mit-ruft. Wer das Pause-Flag kuenftig woanders setzt, MUSS
            // disable() hinterherrufen - sonst bleibt der Exact-Alarm stehen und weckt das Geraet
            // weiter (wirkungslos, aber nicht kostenlos).
            prefs.setActiveOverlay(false, prefs.strengthNow(), prefs.warmthNow())
            correctionNotifier.cancel()
            // Kein Schnappschuss: bei Master-Pause wurde gar nichts gerechnet, und
            // scheduleNextTransition() hat denselben Backstop - es storniert ohnehin.
            return null
        }
        val now = System.currentTimeMillis()
        // Das ganze Ergebnis statt nur der Spannen: die Diagnostik unten braucht auch
        // `anySourceEnabled` und `regelnVorhanden`, und beide fallen bei der Berechnung ohnehin an.
        val fenster = computeWindows()
        // Aktive Spanne (überlappen mehrere, gewinnt die dunkelste – Logik in DimWindowResolver).
        val active = DimWindowResolver.activeSpan(fenster.spans, now)

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
            // BIS 24.08.2026 KEHRTE DIESER ZWEIG KOMMENTARLOS ZURUECK - der haeufigste Aus-Weg
            // ueberhaupt, und der einzige, nach dem man spaeter fragt ("warum war es hell?").
            // Der Grund wird aus bereits gelesenen Werten bestimmt, kostet also keinen
            // zusaetzlichen DataStore-Zugriff.
            meldeAbschaltung(
                DimDiagnostik.abschaltGrund(
                    masterPause = false,
                    dimEnabled = fenster.anySourceEnabled,
                    regelnVorhanden = fenster.regelnVorhanden,
                    fensterAktiv = false,
                    overridePausiert = false
                )
            )
            prefs.setActiveOverlay(false, prefs.strengthNow(), prefs.warmthNow())
            correctionNotifier.cancel()
            return fenster
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
            meldeAbschaltung(DimDiagnostik.AbschaltGrund.OVERRIDE_PAUSIERT)
            prefs.setActiveOverlay(false, prefs.strengthNow(), prefs.warmthNow())
        } else {
            prefs.setActiveOverlay(true, effectiveStrength, active.warmth)
        }

        if (prefs.correctionNotificationEnabledNow()) {
            correctionNotifier.show(effectiveStrength, active.warmth, isPaused)
        } else {
            correctionNotifier.cancel()
        }
        return fenster
    }

    /**
     * Protokolliert, WARUM das Overlay abgeschaltet wird.
     *
     * Das Level verzweigt am Verdachtsmoment, nach dem Vorbild von `visibilitySnapshot()` am
     * Weckbildschirm: Was der Nutzer selbst eingestellt hat (Pause, Hauptschalter aus, Fenster von
     * Hand pausiert) ist kein Vorfall und bleibt DEBUG - im Release-Log waere es taeglich
     * wiederkehrendes Rauschen. WARN bekommt nur der eine Fall, bei dem spaeter jemand fragen
     * wird: Dimmer AN, Regeln da, trotzdem kein Fenster. Genau der ist im Release-Log noetig,
     * weil dort nur WARN+ landet.
     *
     * Der Dienst-Zustand haengt bewusst mit dran: ein aktives Fenster auf einem NICHT gebundenen
     * Dienst sieht im Log sonst genauso aus wie ein gebundener ohne Fenster.
     */
    private fun meldeAbschaltung(grund: DimDiagnostik.AbschaltGrund) {
        val zeile = "Dimmen aus - Grund=$grund bound=${DimAccessibilityService.isRunning()}"
        if (DimDiagnostik.istVerdaechtig(grund)) {
            Logger.w(LogTags.DIMMER, zeile)
        } else {
            Logger.d(LogTags.DIMMER, zeile)
        }
    }

    /**
     * Aktive Spanne JETZT, ohne Seiteneffekt - fuer [DimNotificationService], damit der den
     * korrekten `windowEnd`-Schluessel fuer einen Override kennt, ohne die Fensterlogik zu
     * duplizieren.
     */
    suspend fun activeSpanNow(): DimWindowResolver.DimSpan? = DimWindowResolver.activeSpan(windows(), System.currentTimeMillis())

    suspend fun scheduleNextTransition() {
        scheduleNextTransitionIntern(null)
    }

    /** Wie [scheduleNextTransition], nutzt aber einen bereits vorliegenden Schnappschuss. */
    private suspend fun scheduleNextTransitionIntern(vorberechnet: Windows?) {
        val am = alarmManager()
        val pi = buildPendingIntent()
        // Master-Pause-Backstop, siehe applyCurrentState().
        if (masterPausePrefs.pausedNow()) {
            am.cancel(pi)
            Logger.d(LogTags.DIMMER, "Master-Pause aktiv - kein Dimm-Tick geplant")
            return
        }
        val next = computeNextTransition(System.currentTimeMillis(), vorberechnet)
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

    /**
     * MESSZAEHLER, nur im Debug-Build wirksam (Logger.d). Beantwortet die Frage, die sonst
     * geschaetzt werden muesste: wie oft und wie teuer wird die komplette Fenster-Zeitleiste
     * tatsaechlich gerechnet? Anlass war die Beobachtung, dass nach einer Installation DREI
     * unabhaengige Ketten (Migration, Wartung, Boot-Recovery) binnen fuenf Sekunden dasselbe
     * Ergebnis erzeugten.
     */
    private suspend fun computeWindows(): Windows {
        val begonnen = System.nanoTime()
        return computeWindowsIntern().also {
            Logger.d(
                LogTags.DIMMER,
                "computeWindows #${zaehler.incrementAndGet()} in " +
                    "${(System.nanoTime() - begonnen) / 1_000_000.0} ms " +
                    "(${it.spans.size} Spannen)"
            )
        }
    }

    private suspend fun computeWindowsIntern(): Windows {
        val anySourceEnabled = prefs.togglesNow().dimEnabled
        if (!anySourceEnabled) return Windows(emptyList(), alarmReadFailed = false, anySourceEnabled = false)

        val alarmsResult = alarmUseCase.getAllAlarms()
        if (alarmsResult.isFailure) {
            // Fail-open unveraendert: kein Dimming. Aber die Tick-Kette darf daran nicht
            // ABREISSEN - alarmReadFailed sorgt in scheduleNextTransition fuer einen Retry-Tick.
            Logger.w(LogTags.DIMMER, "Alarm-Bestand nicht lesbar - kein Dimming (fail-open)")
            return Windows(emptyList(), alarmReadFailed = true, anySourceEnabled = true)
        }
        val alarms = alarmsResult.getOrDefault(emptyList()).filter { it.isActive }

        // Schichtspannen sind seit v1.25.2 die Quelle fuer alles SCHICHT-bezogene. Der
        // Alarm-Bestand ueberlebt die Weckzeit nicht - ein SHIFT_END-verankertes Fenster
        // verschwand deshalb mitten in der Schicht, sobald der Wecker geklingelt hatte und der
        // naechste Sync ihn geraeumt hatte. Siehe ShiftSpanStore.
        val spansResult = shiftSpanStore.spansNow()
        if (spansResult.isFailure) {
            Logger.w(LogTags.DIMMER, "Schichtspannen nicht lesbar - kein Dimming (fail-open)")
            return Windows(emptyList(), alarmReadFailed = true, anySourceEnabled = true)
        }
        // FREIGEGEBENE TAGE: an einem vom Nutzer freigegebenen Tag findet der Dienst nicht statt,
        // also darf er auch keine SCHICHT-Fenster erzeugen. Gefiltert wird hier, an der Quelle. Der Tag sieht danach fuer
        // die Fensterlogik aus wie jeder andere freie Tag (FREI-Regel greift), und das ist
        // Absicht: der Nutzer HAT frei. Ein Lesefehler des Freigabe-Speichers degradiert auf
        // "keine Freigabe" - lieber einmal zuviel gedimmt als eine unerwartet helle Nacht.
        val spans = FreieTageStore.filtereSpannen(
            spannen = spansResult.getOrDefault(emptyList()),
            freieTage = freieTageStore.freieTageNow(),
            zone = zone
        )

        val out = mutableListOf<DimWindowResolver.DimSpan>()

        // DIE EINZIGE FENSTER-QUELLE: Regeln, pro Kalendertag GENAU eine (Anker-Semantik in
        // DimWindowResolver.buildRuleSpans). CLOCK<->CLOCK = jede Nacht (lueckenlos, ermoeglicht
        // "immer 22-7 ausser ND"), ALARM/SHIFT_END = schicht-relativ, ALARM_SONST_CLOCK als Ende =
        // "bis zur Weckzeit, spaetestens um X", leere Fensterliste = ND-Ausnahme.
        // Welche Regel das ist, entscheidet der Resolver seit Pruefrunde 8 aus ALLEN Schichten
        // des Tages: `ruleForShift` wird pro Schicht gefragt (an einem Tag mit Fruehdienst UND
        // Rufbereitschaft wurde die zweite Regel vorher nie gefragt). Unterdrueckung schlaegt
        // dabei alles (leere Fensterliste = ausdrueckliche Nutzerentscheidung); bei zwei
        // widersprechenden spezifischen Regeln gewinnt die Regel der fruehesten Schicht, und der
        // Konflikt geht als WARN ins Log. Wichtig fuer diese Stelle: den Tag in so einem Fall
        // einfach auszulassen waere KEIN "nur nicht dimmen" - seit dem Ein-Modell-Umbau gibt es
        // keine zweite Quelle mehr, die einspringen koennte, der Tag bliebe also ganz ohne
        // Dimmung, obwohl die Oberflaeche beide Regeln als aktiv zeigt.
        val rules = dimRuleUseCase.getAllRules()
        // `triggerTime` MUSS hier die urspruenglich berechnete Weckzeit sein, auch wenn sie
        // laengst verstrichen ist: DimWindowResolver leitet daraus den KALENDERTAG des Slots ab
        // (buildRuleSpans). Genau deshalb fuehrt ShiftSpan sie mit - mit einem Platzhalter waere
        // der Slot auf 1970 datiert und die Tagesverankerung der Dimm-Fenster kaputt.
        val slots = spans.map { DimWindowResolver.AlarmSlot(it.alarmTriggerTime, it.shiftName, it.endTime) }

        // ZEITLEISTE ALLER WECKZEITEN - nur fuer den Ende-Anker ALARM_SONST_CLOCK ("bis zur
        // Weckzeit, spaetestens um X"). Sie ist bewusst eine ZWEITE, flachere Sicht neben `slots`
        // und ersetzt diese nicht:
        //
        // - `slots` beantwortet "welche Regel gilt an diesem Kalendertag" und braucht dafuer den
        //   Schichtnamen. Die Zeitleiste beantwortet nur "wann klingelt als naechstes etwas".
        // - Deshalb duerfen hier MANUELLE Wecker mit hinein, die gar keine Schichtspanne haben.
        //   Genau die sollen ein Nacht-Fenster beenden - wer sich einen Wecker stellt, will um
        //   diese Zeit einen hellen Bildschirm. Sie in `slots` aufzunehmen waere dagegen falsch:
        //   ein manueller Wecker macht aus einem freien Tag keinen Schicht-Tag, und die
        //   Regel-Auswahl (FREI vs. Schicht) wuerde still kippen.
        // - Manuelle Wecker laufen bewusst NICHT durch den Freie-Tage-Filter: eine Tagesfreigabe
        //   streicht den DIENST, nicht einen selbst gestellten Wecker (siehe TagFreigabeUseCase,
        //   "nimmt MANUELLE Wecker aus"). Er klingelt also - und muss das Fenster beenden.
        // - `spans` ist bereits gefiltert; an einem freigegebenen Tag steht dort nichts mehr, und
        //   das Nacht-Fenster laeuft dort korrekt bis zu seiner Uhrzeit durch.
        //
        // WAS EIN MANUELLER WECKER SEIT DEM EIN-MODELL-UMBAU NICHT MEHR KANN - bewusst in Kauf
        // genommen, hier festgehalten, damit es nicht als Bug gesucht wird: Er erreicht die
        // Fensterlogik AUSSCHLIESSLICH ueber diese Zeitleiste, also nur ueber den ENDE-Anker
        // ALARM_SONST_CLOCK. Er kann ein laufendes Nacht-Fenster beenden - aber kein Fenster mehr
        // selbst AUFSPANNEN. Die alte Wellness-Quelle konnte das: sie legte um JEDE Weckzeit des
        // Alarm-Bestands ein Wind-down-Fenster, also auch um einen selbst gestellten Wecker. Als
        // Regel ausgedrueckt (Fenster `ALARM -X` -> `ALARM +0`) geht das nicht mehr, weil
        // ALARM-verankerte Regelfenster ueber `slots` aufgeloest werden und dort nur
        // SCHICHTSPANNEN stehen. Wer vor einem manuellen Wecker gedimmt haben will, deckt das
        // ueber ein Nacht-Fenster mit CLOCK-Start ab (das endet dann an genau diesem Wecker).
        // Die Alternative - manuelle Wecker in `slots` aufzunehmen - ist ausgeschlossen: sie
        // wuerde aus einem freien Tag still einen Schicht-Tag machen und die FREI-Regel aushebeln.
        val weckzeiten = (spans.map { it.alarmTriggerTime } + alarms.map { it.triggerTime })
            .distinct()
            .sorted()

        if (rules.any { it.enabled }) {
            out += DimWindowResolver.buildRuleSpans(
                alarms = slots,
                horizonDays = HORIZON_DAYS,
                today = LocalDate.now(zone),
                zone = zone,
                ruleForShift = { name -> dimRuleUseCase.findRuleForShift(name, rules) },
                ruleForFreeDay = { dimRuleUseCase.findRuleForFreeDay(rules) },
                weckzeiten = weckzeiten,
            )
        }

        return Windows(out, alarmReadFailed = false, anySourceEnabled = true, regelnVorhanden = rules.any { it.enabled })
    }

    private suspend fun computeNextTransition(now: Long, vorberechnet: Windows? = null): Long? {
        // Der Schnappschuss kommt aus enable() (siehe dort). Fehlt er - jeder Einzelaufruf von
        // scheduleNextTransition() -, wird wie bisher frisch gerechnet. Die Fenster-Grenzen werden
        // ohnehin gegen `now` gefiltert, ein Schnappschuss aus derselben Coroutine ist also nicht
        // "veraltet", sondern genau der Stand, den auch applyCurrentState() angewendet hat.
        val w = vorberechnet ?: computeWindows()
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
