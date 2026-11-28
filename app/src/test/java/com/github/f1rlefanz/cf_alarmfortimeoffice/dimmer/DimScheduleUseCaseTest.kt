package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.TageslichtQuelle
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.github.f1rlefanz.cf_alarmfortimeoffice.freietage.FreieTageStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.freietage.freieTageStoreMit
import com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause.MasterPausePrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.OhneEintragEinstellungen
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypDimmFenster
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypDimmen
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpan
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.freietage.keineFreienTage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Fuehrt [block] mit gemocktem AlarmManager und PendingIntent aus: `scheduleNextTransition()` und
 * `enable()` brauchen beide, und `PendingIntent.getBroadcast` ist im JVM-Test nur ein Stub (#217).
 * Der Intent wird mitgemockt: `setAction()` liefert im Stub `null`, und Kotlin prueft das.
 * Statische und Konstruktor-Mocks gelten nur auf diesem Thread - `runTest` bleibt darauf.
 */
internal suspend fun <T> mitDimmTick(block: suspend (context: Context, am: AlarmManager, pi: PendingIntent) -> T): T {
    val am = mock<AlarmManager>()
    val context = mock<Context>()
    whenever(context.getSystemService(Context.ALARM_SERVICE)).thenReturn(am)
    val pi = mock<PendingIntent>()
    Mockito.mockConstruction(Intent::class.java) { intent, _ ->
        whenever(intent.setAction(anyOrNull())).thenReturn(intent)
    }.use {
        Mockito.mockStatic(PendingIntent::class.java).use { statisch ->
            statisch.`when`<PendingIntent> {
                PendingIntent.getBroadcast(anyOrNull(), anyInt(), anyOrNull(), anyInt())
            }.thenReturn(pi)
            return block(context, am, pi)
        }
    }
}

/**
 * Unit-Tests fuer [DimScheduleUseCase.previewTimeline] - die einzige oeffentliche,
 * seiteneffektfreie Methode der Klasse. Sie ruft ausschliesslich die private `windows()`-Funktion
 * auf, also die Produktions-Verdrahtung zwischen Schalter, Regelauswahl und der reinen Mathematik
 * in [DimWindowResolver] (siehe [DimWindowResolverTest]).
 *
 * SEIT DEM EIN-MODELL-UMBAU gibt es nur noch EINE Fenster-Quelle: die Regeln. Die frueheren
 * Sonderquellen "Wellness/Wind-down" und "Nacht-Standard" sind entfallen; was sie konnten, wird
 * hier als gewoehnliche Regel ausgedrueckt - Wellness als Fenster `ALARM -60` -> `ALARM +0`, der
 * Nacht-Standard als UNIVERSAL-Fenster `CLOCK 22:00` -> `ALARM_SONST_CLOCK 07:00`. Genau in dieser
 * uebersetzten Form bleiben die alten Regressionsfaelle erhalten.
 *
 * `DimScheduleUseCase` zieht `ZoneId.systemDefault()`/`LocalDate.now()` intern (keine injizierbare
 * Uhr) - die Tests rechnen deshalb relativ zur echten aktuellen Zeitzone/zum echten heutigen Datum,
 * statt eine feste Zone/ein festes Datum anzunehmen.
 */
class DimScheduleUseCaseTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val today: LocalDate = LocalDate.now(zone)

    private fun atDate(date: LocalDate, hour: Int, minute: Int): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun alarm(
        id: Int = 1,
        shiftName: String,
        triggerTime: Long,
        shiftEndTime: Long = 0L,
        isActive: Boolean = true,
    ): AlarmInfo = AlarmInfo(
        id = id,
        shiftId = "shift_$id",
        shiftName = shiftName,
        triggerTime = triggerTime,
        formattedTime = "",
        isActive = isActive,
        shiftEndTime = shiftEndTime,
    )

    /** Der Nacht-Standard als gewoehnliche Regel: jede Kalendernacht ab 22:00 bis zur Weckzeit,
     * spaetestens 07:00. EIN Fenster je Nacht, kein Folgetag-Sonderfall. */
    private fun nachtRegel(strength: Int = 60, warmth: Int = 40) = DimRule(
        id = "nacht",
        name = "Nacht",
        shiftPattern = DimRule.SHIFT_UNIVERSAL,
        enabled = true,
        windows = listOf(
            DimWindow(
                startAnchor = DimAnchor.CLOCK, startClockMinutes = 22 * 60,
                endAnchor = DimAnchor.ALARM_SONST_CLOCK, endClockMinutes = 7 * 60
            )
        ),
        strength = strength,
        warmth = warmth,
    )

    private suspend fun mockPrefs(
        dimEnabled: Boolean,
        strength: Int = 55,
        warmth: Int = 40,
    ): DimOverlayPrefs {
        val prefs = mock<DimOverlayPrefs>()
        whenever(prefs.togglesNow()).thenReturn(DimOverlayPrefs.Toggles(dimEnabled = dimEnabled))
        whenever(prefs.strengthNow()).thenReturn(strength)
        whenever(prefs.warmthNow()).thenReturn(warmth)
        return prefs
    }

    /**
     * Seit #217 liest der Scheduler die Fenster AM TYP. Die Regel-Fixtures dieser Tests werden
     * deshalb ueber die echte Migration auf Typen mit den Schichtnamen der Fixtures uebersetzt
     * ([konfigAusRegeln]) - die alten Faelle pruefen so, was nach dem Update am Typ steht.
     */
    private suspend fun sut(
        prefs: DimOverlayPrefs,
        alarms: Result<List<AlarmInfo>>,
        rules: List<DimRule> = emptyList(),
        konfig: Result<ShiftConfig> =
            Result.success(konfigAusRegeln(rules, alarms.getOrNull().orEmpty().map { it.shiftName })),
        freieTage: FreieTageStore = keineFreienTage(),
        context: Context = mock(),
        alarmUseCase: IAlarmUseCase = mock(),
    ): DimScheduleUseCase {
        whenever(alarmUseCase.getAllAlarms()).thenReturn(alarms)
        // Seit v1.25.2 speisen sich die Regel-Fenster aus den Schichtspannen statt aus dem
        // Alarm-Bestand (der ueberlebt die Weckzeit nicht). Fuer die Tests werden sie aus
        // denselben Fixtures abgeleitet - inklusive des Fehlerfalls, damit der Fail-open-Pfad
        // weiterhin geprueft wird.
        val spanStore = mock<ShiftSpanStore>()
        whenever(spanStore.spansNow()).thenReturn(
            alarms.map { list ->
                list.map { ShiftSpan(it.shiftName, it.shiftStartTime, it.shiftEndTime, it.triggerTime) }
            }
        )
        val notifier = mock<DimCorrectionNotifier>()
        // Master-Pause-Backstop (in applyCurrentState/scheduleNextTransition): fuer die reine
        // Fenster-Vorschau hier immer "nicht pausiert".
        val masterPausePrefs = mock<MasterPausePrefs>()
        whenever(masterPausePrefs.pausedNow()).thenReturn(false)
        return DimScheduleUseCase(context, alarmUseCase, spanStore, freieTage, konfigRepo(konfig), prefs, notifier, masterPausePrefs, TageslichtQuelle.OHNE)
    }

    /**
     * BEWUSSTE VERHALTENSAENDERUNG (#217, SPEC E7 "kein globaler Hauptschalter"): Hiess bis #217
     * "previewTimeline ist leer wenn der Dimmer ausgeschaltet ist" und erwartete das Gegenteil.
     * `dim_enabled` wird nicht mehr gelesen; "aus" steht seit der Migration als `an = false` am
     * Typ (naechster Test). Ein alter Schalter darf die Fenster am Typ nicht mehr abschalten -
     * sonst gaebe es zwei Stellen, die "aus" sagen, und eine davon haette keine Oberflaeche mehr.
     */
    @Test
    fun `dim_enabled aus hat keine Wirkung mehr - die Fenster am Typ dimmen`() = runTest {
        val triggerTime = atDate(today.plusDays(1), 6, 0)
        mitDimmTick { context, am, pi ->
            val useCase = sut(
                mockPrefs(dimEnabled = false),
                Result.success(listOf(alarm(shiftName = "Fruehschicht", triggerTime = triggerTime))),
                rules = listOf(nachtRegel()),
                context = context,
            )

            assertTrue(
                "Die Nacht vor der Schicht fehlt - der alte Hauptschalter wirkt noch",
                useCase.previewTimeline().any { it.range.first == atDate(today, 22, 0) && it.range.last == triggerTime }
            )
            useCase.scheduleNextTransition()
            verify(am, never()).cancel(any<PendingIntent>())
            verify(am).setExactAndAllowWhileIdle(eq(AlarmManager.RTC_WAKEUP), anyLong(), eq(pi))
        }
    }

    @Test
    fun `ein Typ mit an = false dimmt nicht`() = runTest {
        // "Ausgeschaltet bleibt ausgeschaltet" (SPEC 4): die Migration traegt `dim_enabled = false`
        // als `an = false` an jeden Typ und die feste Zeile - die Fenster bleiben, wirken aber nicht.
        val triggerTime = atDate(today.plusDays(1), 6, 0)
        val alarms = listOf(alarm(shiftName = "Fruehschicht", triggerTime = triggerTime))
        val preview = sut(
            mockPrefs(dimEnabled = true),
            Result.success(alarms),
            konfig = Result.success(konfigAusRegeln(listOf(nachtRegel()), listOf("Fruehschicht"), dimmenAn = false)),
        ).previewTimelineWithStatus()

        assertTrue(preview.intervals.isEmpty())
        assertFalse(preview.alarmReadFailed)
    }

    @Test
    fun `ohne wirksames Fenster am Typ bestellt sich die Kette ab`() = runTest {
        mitDimmTick { context, am, pi ->
            sut(
                mockPrefs(dimEnabled = true),
                Result.success(listOf(alarm(shiftName = "Fruehschicht", triggerTime = atDate(today.plusDays(1), 6, 0)))),
                konfig = Result.success(konfigAusRegeln(emptyList(), listOf("Fruehschicht"))),
                context = context,
            ).scheduleNextTransition()

            verify(am).cancel(pi)
        }
    }

    @Test
    fun `unlesbare Konfiguration - kein Fenster, Retry statt Abbestellen, kein weiterer Read`() = runTest {
        val alarmUseCase = mock<IAlarmUseCase>()
        mitDimmTick { context, am, pi ->
            sut(
                mockPrefs(dimEnabled = true),
                Result.success(emptyList()),
                konfig = Result.failure(IllegalStateException("vor der ersten Entsperrung")),
                context = context,
                alarmUseCase = alarmUseCase,
            ).scheduleNextTransition()

            verify(am, never()).cancel(any<PendingIntent>())
            verify(am).setExactAndAllowWhileIdle(eq(AlarmManager.RTC_WAKEUP), anyLong(), eq(pi))
        }
        // Vor der Konfiguration wird kein weiterer Speicher gelesen (CE-Store vor der Entsperrung).
        verify(alarmUseCase, never()).getAllAlarms()
    }

    @Test
    fun `ein freigegebener Tag bekommt die Fenster der festen Zeile statt die des Typs`() = runTest {
        val tag = today.plusDays(2)
        val triggerTime = atDate(tag, 5, 30)
        val konfig = ShiftConfig(
            definitions = listOf(
                ShiftDefinition(
                    id = "f", name = "Fruehschicht", keywords = listOf("F"), alarmTime = LocalTime.of(5, 30),
                    dimmen = TypDimmen().mitFenster(
                        listOf(
                            TypDimmFenster(
                                DimWindow(startAnchor = DimAnchor.ALARM, startOffsetMinutes = -60, endAnchor = DimAnchor.ALARM),
                                staerke = 70
                            )
                        )
                    )
                )
            ),
            ohneKalendereintrag = OhneEintragEinstellungen(
                dimmen = TypDimmen().mitFenster(
                    listOf(
                        TypDimmFenster(
                            DimWindow(
                                startAnchor = DimAnchor.CLOCK, startClockMinutes = 21 * 60,
                                endAnchor = DimAnchor.CLOCK, endClockMinutes = 23 * 60
                            ),
                            staerke = 30
                        )
                    )
                )
            )
        )
        val alarms = Result.success(listOf(alarm(shiftName = "Fruehschicht", triggerTime = triggerTime)))
        val typFenster = triggerTime - 60 * 60_000L

        val normal = sut(mockPrefs(dimEnabled = true), alarms, konfig = Result.success(konfig)).previewTimeline()
        assertTrue("Gegenprobe: ohne Freigabe dimmt der Typ", normal.any { it.range.first == typFenster && it.strength == 70 })
        assertTrue(normal.none { it.range.first == atDate(tag, 21, 0) })

        val frei = sut(
            mockPrefs(dimEnabled = true), alarms, konfig = Result.success(konfig), freieTage = freieTageStoreMit(setOf(tag))
        ).previewTimeline()
        assertTrue(frei.none { it.range.first == typFenster })
        assertTrue(frei.any { it.range.first == atDate(tag, 21, 0) && it.range.last == atDate(tag, 23, 0) && it.strength == 30 })
    }

    @Test
    fun `previewTimeline dimmt NICHT wenn der Alarm-Bestand nicht lesbar ist (fail-open)`() = runTest {
        val prefs = mockPrefs(dimEnabled = true)
        val useCase = sut(prefs, Result.failure(RuntimeException("Kalender/Token kaputt")), rules = listOf(nachtRegel()))

        assertTrue(useCase.previewTimeline().isEmpty())
    }

    @Test
    fun `Ein ALARM-verankertes Regel-Fenster reicht bis exakt zur Weckzeit (frueher Wellness)`() = runTest {
        // Uebersetzt aus "Wellness allein erzeugt ein Wind-down-Fenster": der Wind-down war
        // `[Weckzeit - X, Weckzeit]`, als Regel ist das ein Fenster ALARM -60 -> ALARM +0.
        val triggerTime = atDate(today.plusDays(1), 6, 0)
        val windDownRegel = DimRule(
            id = "wind", name = "Wind-down", shiftPattern = DimRule.SHIFT_UNIVERSAL, enabled = true,
            windows = listOf(
                DimWindow(
                    startAnchor = DimAnchor.ALARM, startOffsetMinutes = -60,
                    endAnchor = DimAnchor.ALARM, endOffsetMinutes = 0
                )
            ),
            strength = 55,
            warmth = 40,
        )
        val prefs = mockPrefs(dimEnabled = true)
        val useCase = sut(
            prefs,
            Result.success(listOf(alarm(shiftName = "Fruehschicht", triggerTime = triggerTime))),
            rules = listOf(windDownRegel),
        )

        val timeline = useCase.previewTimeline()

        assertEquals(1, timeline.size)
        val window = timeline.first()
        assertEquals(triggerTime - 60 * 60_000L, window.range.first)
        assertEquals(triggerTime, window.range.last)
        assertEquals(55, window.strength)
    }

    @Test
    fun `Eine FREI-Regel unterdrueckt an einem Schicht-Tag NICHT die UNIVERSAL-Nachtregel`() = runTest {
        // Uebersetzt aus "Nacht-Standard bleibt fuer eine Arbeitsschicht aktiv, wenn nur eine
        // FREI-Regel existiert". Die Regressionsabsicht ist unveraendert: dass ueberhaupt eine
        // Regel aktiv ist, heisst nicht, dass sie fuer DIESEN Tag gilt. Der Anker ist deshalb die
        // Nacht DES SCHICHT-TAGS - an ihm entscheidet findRuleForShift(), und dort muss die
        // UNIVERSAL-Nachtregel gewinnen, nicht die FREI-Regel.
        //
        // Die Nacht DAVOR gehoert dagegen dem freien Vortag; dass die FREI-Regel sie unterdrueckt,
        // ist im Ein-Modell richtig und ausdruecklich mitgeprueft.
        val shiftDay = today.plusDays(2)
        val triggerTime = atDate(shiftDay, 5, 30)

        val freeRule = DimRule(
            id = "free", name = "Frei", shiftPattern = DimRule.SHIFT_FREE, enabled = true, windows = emptyList()
        )
        val prefs = mockPrefs(dimEnabled = true)
        val useCase = sut(
            prefs,
            Result.success(listOf(alarm(shiftName = "Fruehschicht", triggerTime = triggerTime))),
            rules = listOf(freeRule, nachtRegel()),
        )

        val timeline = useCase.previewTimeline()

        assertTrue(
            "Die Nacht des Schicht-Tags fehlt - die FREI-Regel hat faelschlich fuer einen " +
                "Schicht-Tag gegolten",
            timeline.any {
                it.range.first == atDate(shiftDay, 22, 0) &&
                    it.range.last == atDate(shiftDay.plusDays(1), 7, 0) && it.strength == 60
            }
        )
        assertTrue(
            "Der freie Vortag hat eine FREI-Regel mit leerer Fensterliste - das ist eine " +
                "ausdrueckliche Unterdrueckung und muss wirken",
            timeline.none { it.range.first == atDate(shiftDay.minusDays(1), 22, 0) }
        )
    }

    @Test
    fun `Eine passende Schicht-Regel ueberschreibt die UNIVERSAL-Nachtregel fuer ihren Tag`() = runTest {
        // Companion zum vorigen Test (uebersetzt aus "Eine passende Schicht-Regel ueberschreibt den
        // Nacht-Standard"): kommt eine Regel hinzu, deren shiftPattern EXAKT den Schichtnamen
        // trifft, gewinnt sie fuer diesen Tag komplett - nicht additiv. Ihr eigenes Fenster
        // erscheint, das UNIVERSAL-Nachtfenster dieses Tages verschwindet.
        val shiftDay = today.plusDays(2)
        val triggerTime = atDate(shiftDay, 5, 30)
        val nightStart = atDate(shiftDay.minusDays(1), 22, 0)

        val shiftRule = DimRule(
            id = "fs", name = "Fruehschicht-Regel", shiftPattern = "Fruehschicht", enabled = true,
            windows = listOf(
                DimWindow(
                    startAnchor = DimAnchor.ALARM, startOffsetMinutes = -60,
                    endAnchor = DimAnchor.ALARM, endOffsetMinutes = 0
                )
            ),
            strength = 70,
            warmth = 50,
        )
        val prefs = mockPrefs(dimEnabled = true)
        val useCase = sut(
            prefs,
            Result.success(listOf(alarm(shiftName = "Fruehschicht", triggerTime = triggerTime))),
            rules = listOf(nachtRegel(), shiftRule),
        )

        val timeline = useCase.previewTimeline()

        assertTrue(
            "Die eigene Regel-Spanne der Schicht fehlt in der Zeitleiste",
            timeline.any {
                it.range.first == triggerTime - 60 * 60_000L && it.range.last == triggerTime &&
                    it.strength == 70 && it.warmth == 50
            }
        )
        assertTrue(
            "Das UNIVERSAL-Nachtfenster (22 Uhr bis Weckzeit, Staerke 60) darf an einem Tag mit " +
                "passender Schicht-Regel nicht mehr auftauchen",
            timeline.none { it.range.first == nightStart && it.range.last == triggerTime && it.strength == 60 }
        )
    }
}
