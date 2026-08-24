package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import android.content.Context
import com.github.f1rlefanz.cf_alarmfortimeoffice.masterpause.MasterPausePrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpan
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.freietage.keineFreienTage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.LocalDate
import java.time.ZoneId

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

    /** Echte [DimRuleUseCase]-Auswahl-Logik gegen ein gemocktes Repository. */
    private suspend fun ruleUseCase(rules: List<DimRule>): DimRuleUseCase {
        val repo = mock<DimRuleRepository>()
        whenever(repo.getRules()).thenReturn(rules)
        return DimRuleUseCase(repo)
    }

    private suspend fun sut(
        prefs: DimOverlayPrefs,
        alarms: Result<List<AlarmInfo>>,
        rules: List<DimRule> = emptyList(),
    ): DimScheduleUseCase {
        val alarmUseCase = mock<IAlarmUseCase>()
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
        val context = mock<Context>()
        val notifier = mock<DimCorrectionNotifier>()
        // Master-Pause-Backstop (in applyCurrentState/scheduleNextTransition): fuer die reine
        // Fenster-Vorschau hier immer "nicht pausiert".
        val masterPausePrefs = mock<MasterPausePrefs>()
        whenever(masterPausePrefs.pausedNow()).thenReturn(false)
        return DimScheduleUseCase(context, alarmUseCase, spanStore, keineFreienTage(), ruleUseCase(rules), prefs, notifier, masterPausePrefs)
    }

    @Test
    fun `previewTimeline ist leer wenn der Dimmer ausgeschaltet ist`() = runTest {
        // Uebersetzt aus "alle drei Fenster-Quellen deaktiviert": es gibt nur noch einen Schalter,
        // und der schaltet die Regeln aus - vorhandene, aktivierte Regeln duerfen dann NICHTS tun.
        val triggerTime = atDate(today.plusDays(1), 6, 0)
        val prefs = mockPrefs(dimEnabled = false)
        val useCase = sut(
            prefs,
            Result.success(listOf(alarm(shiftName = "Fruehschicht", triggerTime = triggerTime))),
            rules = listOf(nachtRegel()),
        )

        assertTrue(useCase.previewTimeline().isEmpty())
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
