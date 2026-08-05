package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import android.content.Context
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.LocalDate
import java.time.ZoneId

/**
 * Unit-Tests fuer [DimScheduleUseCase.previewTimeline] - die einzige oeffentliche, seiteneffektfreie
 * Methode der Klasse. Sie ruft ausschliesslich die private `windows()`-Funktion auf, welche die
 * DREI Fenster-Quellen (Wellness/Regeln/Nacht-Standard, siehe Klassen-KDoc) zusammenfuehrt - die
 * eigentliche Produktions-Verdrahtung, die bisher (anders als die reine Mathematik in
 * [DimWindowResolver], siehe [DimWindowResolverTest]) komplett ungetestet war.
 *
 * Kernrisiko dieser Datei (Regressionstest): sind "Regeln" UND "Nacht-Standard" beide aktiv, prueft
 * `windows()` fuer den Nacht-Standard-Ausschluss `dimRuleUseCase.findRuleForShift(shiftName, rules)`.
 * Existiert NUR eine SHIFT_FREE-Regel (keine UNIVERSAL-Regel, keine zur Schicht passende Regel),
 * liefert `findRuleForShift` fuer eine Arbeitsschicht `null` - `rulesActive` ist zwar `true` (die
 * FREI-Regel ist aktiv), gilt aber nicht fuer DIESEN Tag. Der Nacht-Standard darf in diesem Fall
 * NICHT unterdrueckt werden.
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

    private suspend fun mockPrefs(
        toggles: DimOverlayPrefs.Toggles,
        windDownMinutes: Int = 120,
        strength: Int = 55,
        warmth: Int = 40,
        nightDefaultStartMinutes: Int = 22 * 60,
        nightDefaultFreeEndMinutes: Int = 7 * 60,
        nightDefaultExcludedShifts: Set<String> = emptySet(),
        nightDefaultStrength: Int = 60,
        nightDefaultWarmth: Int = 40,
    ): DimOverlayPrefs {
        val prefs = mock<DimOverlayPrefs>()
        whenever(prefs.togglesNow()).thenReturn(toggles)
        whenever(prefs.windDownMinutesNow()).thenReturn(windDownMinutes)
        whenever(prefs.strengthNow()).thenReturn(strength)
        whenever(prefs.warmthNow()).thenReturn(warmth)
        whenever(prefs.nightDefaultStartMinutesNow()).thenReturn(nightDefaultStartMinutes)
        whenever(prefs.nightDefaultFreeEndMinutesNow()).thenReturn(nightDefaultFreeEndMinutes)
        whenever(prefs.nightDefaultExcludedShiftsNow()).thenReturn(nightDefaultExcludedShifts)
        whenever(prefs.nightDefaultStrengthNow()).thenReturn(nightDefaultStrength)
        whenever(prefs.nightDefaultWarmthNow()).thenReturn(nightDefaultWarmth)
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
        val context = mock<Context>()
        val notifier = mock<DimCorrectionNotifier>()
        return DimScheduleUseCase(context, alarmUseCase, ruleUseCase(rules), prefs, notifier)
    }

    @Test
    fun `previewTimeline ist leer wenn alle drei Fenster-Quellen deaktiviert sind`() = runTest {
        val prefs = mockPrefs(
            DimOverlayPrefs.Toggles(wellnessEnabled = false, rulesEnabled = false, nightDefaultEnabled = false)
        )
        val useCase = sut(prefs, Result.success(emptyList()))

        assertTrue(useCase.previewTimeline().isEmpty())
    }

    @Test
    fun `previewTimeline dimmt NICHT wenn der Alarm-Bestand nicht lesbar ist (fail-open)`() = runTest {
        val prefs = mockPrefs(
            DimOverlayPrefs.Toggles(wellnessEnabled = true, rulesEnabled = false, nightDefaultEnabled = false)
        )
        val useCase = sut(prefs, Result.failure(RuntimeException("Kalender/Token kaputt")))

        assertTrue(useCase.previewTimeline().isEmpty())
    }

    @Test
    fun `Wellness allein erzeugt ein Wind-down-Fenster bis exakt zur Weckzeit`() = runTest {
        val triggerTime = atDate(today.plusDays(1), 6, 0)
        val prefs = mockPrefs(
            DimOverlayPrefs.Toggles(wellnessEnabled = true, rulesEnabled = false, nightDefaultEnabled = false),
            windDownMinutes = 60,
            strength = 55,
        )
        val useCase = sut(prefs, Result.success(listOf(alarm(shiftName = "Fruehschicht", triggerTime = triggerTime))))

        val timeline = useCase.previewTimeline()

        assertEquals(1, timeline.size)
        val window = timeline.first()
        assertEquals(triggerTime - 60 * 60_000L, window.range.first)
        assertEquals(triggerTime, window.range.last)
        assertEquals(55, window.strength)
    }

    @Test
    fun `Nacht-Standard bleibt fuer eine Arbeitsschicht aktiv wenn nur eine FREI-Regel existiert`() = runTest {
        // Regressionsrisiko: rulesEnabled=true UND nightDefaultEnabled=true, aber die einzige
        // aktive Regel passt auf freie Tage (SHIFT_FREE), nicht auf die tatsaechliche Schicht -
        // findRuleForShift("Fruehschicht", ...) muss null liefern, damit der Nacht-Standard NICHT
        // faelschlich unterdrueckt wird (rulesActive=true bedeutet nicht "gilt fuer jeden Tag").
        val shiftDay = today.plusDays(2)
        val triggerTime = atDate(shiftDay, 5, 30)
        val nightStart = atDate(shiftDay.minusDays(1), 22, 0)

        val freeRule = DimRule(
            id = "free", name = "Frei", shiftPattern = DimRule.SHIFT_FREE, enabled = true, windows = emptyList()
        )
        val prefs = mockPrefs(
            DimOverlayPrefs.Toggles(wellnessEnabled = false, rulesEnabled = true, nightDefaultEnabled = true),
            nightDefaultStrength = 60,
            nightDefaultWarmth = 40,
        )
        val useCase = sut(
            prefs,
            Result.success(listOf(alarm(shiftName = "Fruehschicht", triggerTime = triggerTime))),
            rules = listOf(freeRule),
        )

        val timeline = useCase.previewTimeline()

        assertTrue(
            "Nacht-Standard-Fenster vor der Arbeitsschicht fehlt - faelschlich durch die FREI-Regel unterdrueckt",
            timeline.any { it.range.first == nightStart && it.range.last == triggerTime && it.strength == 60 }
        )
    }

    @Test
    fun `Eine passende Schicht-Regel ueberschreibt den Nacht-Standard fuer ihren Tag`() = runTest {
        // Companion zum vorigen Test: kommt zusaetzlich eine Regel hinzu, deren shiftPattern
        // EXAKT den Schichtnamen trifft, muss findRuleForShift() jetzt diese Regel liefern - ihr
        // eigenes Fenster erscheint, und der Nacht-Standard-Bereich fuer genau diesen Tag
        // verschwindet in seiner alten (unterdrueckten) Form.
        val shiftDay = today.plusDays(2)
        val triggerTime = atDate(shiftDay, 5, 30)
        val nightStart = atDate(shiftDay.minusDays(1), 22, 0)

        val freeRule = DimRule(
            id = "free", name = "Frei", shiftPattern = DimRule.SHIFT_FREE, enabled = true, windows = emptyList()
        )
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
        val prefs = mockPrefs(
            DimOverlayPrefs.Toggles(wellnessEnabled = false, rulesEnabled = true, nightDefaultEnabled = true),
            nightDefaultStrength = 60,
            nightDefaultWarmth = 40,
        )
        val useCase = sut(
            prefs,
            Result.success(listOf(alarm(shiftName = "Fruehschicht", triggerTime = triggerTime))),
            rules = listOf(freeRule, shiftRule),
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
            "Der alte, ununterbrochene Nacht-Standard-Bereich (22 Uhr bis Weckzeit, Staerke 60) " +
                "darf nach der passenden Regel nicht mehr in dieser Form auftauchen",
            timeline.none { it.range.first == nightStart && it.range.last == triggerTime && it.strength == 60 }
        )
    }
}
