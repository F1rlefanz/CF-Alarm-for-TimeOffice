package com.github.f1rlefanz.cf_alarmfortimeoffice.hue

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.ActionType
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueLightAction
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueSchedule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueTimeRange
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.SunriseConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.TargetType
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.HueConfiguration
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.IHueConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.FruehAus
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.HueRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.hellNachWeckzeit
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.AutoOffTarget
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.BatchActionResult
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.IHueLightUseCaseAdvanced
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.LightAction
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.LightActionResult
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.LightTargets
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.kuerzeAutoAus
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftMatch
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.HellAb
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.Ort
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.OrtQuelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.TageslichtQuelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.TageslichtStand
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.time.Duration

/**
 * „Auto-Aus spaetestens bei Tageslicht“ (#143): Das Licht geht zum FRUEHEREN von Auto-Aus-Dauer
 * und Hell-Zeitpunkt des Wecktags aus - aber nur, wenn es erst NACH der Weckzeit hell wird, nie
 * vor dem Rampenende, und bei jeder Luecke (kein Ort, keine Weckzeit) nach der Dauer.
 * Referenz: Muenchen, 14.10.2026 - Daemmerung ca. 07:00, Sonnenaufgang ca. 07:31 (CEST).
 */
class HueAutoAusBeiHellTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val tag: LocalDate = LocalDate.of(2026, 10, 14)
    private val muenchen = TageslichtStand(Ort(48.14, 11.58, OrtQuelle.PLZ, "München"), zone)
    private val daemmerung = muenchen.hellZeitpunkt(tag, HellAb.DAEMMERUNG)!!
    private val sonnenaufgang = muenchen.hellZeitpunkt(tag, HellAb.SONNENAUFGANG)!!

    private fun um(stunde: Int, minute: Int): Long =
        tag.atTime(stunde, minute).atZone(zone).toInstant().toEpochMilli()

    /** Bewusst UNABHAENGIG von der Laufzeit nachgerechnet: aufgerundete Minuten. */
    private fun minuten(ab: Long, bis: Long): Int = Math.ceil((bis - ab) / 60_000.0).toInt()

    private class FesteQuelle(private val stand: TageslichtStand) : TageslichtQuelle {
        override suspend fun stand(): TageslichtStand = stand
    }

    private class FakeConfigRepository(private val rules: List<HueSchedule>) : IHueConfigRepository {
        override fun getConfiguration(): Flow<HueConfiguration> = flowOf(HueConfiguration())
        override suspend fun saveBridgeConfig(bridgeIp: String, username: String): Result<Unit> = Result.success(Unit)
        override suspend fun getScheduleRules(): Result<List<HueSchedule>> = Result.success(rules)
        override suspend fun saveScheduleRule(rule: HueSchedule): Result<Unit> = Result.success(Unit)
        override suspend fun deleteScheduleRule(ruleId: String): Result<Unit> = Result.success(Unit)
        override suspend fun updateScheduleRule(rule: HueSchedule): Result<Unit> = Result.success(Unit)
        override suspend fun updateScheduleRules(transform: (List<HueSchedule>) -> List<HueSchedule>): Result<Unit> = Result.success(Unit)
        override suspend fun clearBridgeConfig(): Result<Unit> = Result.success(Unit)
    }

    private class FakeLightUseCase : IHueLightUseCaseAdvanced {
        val autoOffCalls = mutableListOf<List<AutoOffTarget>>()

        override suspend fun scheduleBridgeAutoOff(targets: List<AutoOffTarget>, shiftName: String): Result<Int> {
            autoOffCalls += targets
            return Result.success(targets.size)
        }

        override suspend fun startSunrise(
            targetId: String, isGroup: Boolean, startKelvin: Int, endKelvin: Int,
            endBrightness: Int, durationMinutes: Int
        ): Result<Unit> = Result.success(Unit)

        override suspend fun executeBatchLightActions(actions: List<LightAction>): Result<BatchActionResult> =
            Result.success(BatchActionResult(actions.size, actions.size, emptyList<LightActionResult>()))

        override suspend fun executeActionsWithAutoRevert(
            actions: List<LightAction>,
            revertAfter: Duration
        ): Result<BatchActionResult> = executeBatchLightActions(actions)

        override suspend fun getAllLightTargets(): Result<LightTargets> = Result.success(LightTargets())
        override suspend fun executeLightAction(action: LightAction): Result<LightActionResult> =
            Result.success(LightActionResult(true, action.targetId))
        override suspend fun flashLight(lightId: String): Result<Unit> = Result.success(Unit)
    }

    private fun regel(
        dauer: Int,
        ausBeiHell: HellAb,
        sunrise: SunriseConfig? = null
    ) = HueSchedule(
        id = "r",
        name = "Morgenlicht",
        shiftPattern = "Frühdienst",
        sunrise = sunrise,
        timeRanges = listOf(
            HueTimeRange(
                actions = listOf(
                    HueLightAction(
                        targetType = TargetType.GROUP,
                        targetId = "5",
                        actionType = ActionType.TURN_ON,
                        on = true,
                        brightness = 200,
                        duration = dauer,
                        isGroup = true
                    )
                )
            )
        ),
        ausBeiHell = ausBeiHell
    )

    private fun ziel(minuten: Int) = listOf(AutoOffTarget("5", true, minuten))

    private fun amWecker(weckzeit: Long) = FruehAus(jetzt = weckzeit, weckzeit = weckzeit, stand = muenchen)

    private fun shift(): ShiftMatch {
        val start = LocalDateTime.of(2026, 10, 14, 7, 0)
        return ShiftMatch(
            shiftDefinition = ShiftDefinition(
                id = "def_fd",
                name = "Frühdienst",
                keywords = listOf("Frühdienst"),
                alarmTime = LocalTime.of(6, 30)
            ),
            calendarEvent = CalendarEvent(
                id = "event_fd",
                title = "Frühdienst",
                startTime = start,
                endTime = start.plusHours(8),
                calendarId = "cal"
            ),
            calculatedAlarmTime = start.minusMinutes(30)
        )
    }

    // --- die reine Rechnung ----------------------------------------------------------------

    @Test
    fun `kuerzeAutoAus - frueheres gewinnt, nie vor Rampenende, nie unter einer Minute`() {
        assertEquals(60, kuerzeAutoAus(nachDauer = 60, rampenEnde = 0, bisHell = null))
        assertEquals(30, kuerzeAutoAus(nachDauer = 60, rampenEnde = 0, bisHell = 30))
        assertEquals(60, kuerzeAutoAus(nachDauer = 60, rampenEnde = 0, bisHell = 90))
        assertEquals(20, kuerzeAutoAus(nachDauer = 50, rampenEnde = 20, bisHell = 5))
        assertEquals(1, kuerzeAutoAus(nachDauer = 30, rampenEnde = 0, bisHell = -10))
    }

    @Test
    fun `hellNachWeckzeit - nur ein Hell-Zeitpunkt NACH der Weckzeit zaehlt`() {
        assertEquals(sonnenaufgang, hellNachWeckzeit(HellAb.SONNENAUFGANG, um(6, 0), muenchen))
        assertNull(hellNachWeckzeit(HellAb.SONNENAUFGANG, sonnenaufgang, muenchen))
        assertNull(hellNachWeckzeit(HellAb.SONNENAUFGANG, um(9, 0), muenchen))
        assertNull(hellNachWeckzeit(HellAb.IMMER, um(6, 0), muenchen))
        assertNull(hellNachWeckzeit(HellAb.SONNENAUFGANG, um(6, 0), TageslichtStand.ohneOrt(zone)))
    }

    // --- autoOffTargetsOf ------------------------------------------------------------------

    @Test
    fun `wird es vor Ende der Dauer hell, geht das Licht dann aus`() {
        val weckzeit = um(6, 30)
        val regeln = listOf(regel(dauer = 120, ausBeiHell = HellAb.DAEMMERUNG))
        assertEquals(ziel(minuten(weckzeit, daemmerung)), HueRuleUseCase.autoOffTargetsOf(regeln, amWecker(weckzeit)))
        assertTrue(minuten(weckzeit, daemmerung) in 20..40)
    }

    @Test
    fun `wird es erst nach Ende der Dauer hell, gilt die Dauer`() {
        val regeln = listOf(regel(dauer = 10, ausBeiHell = HellAb.SONNENAUFGANG))
        assertEquals(ziel(10), HueRuleUseCase.autoOffTargetsOf(regeln, amWecker(um(6, 30))))
    }

    @Test
    fun `ist es zur Weckzeit schon hell, gilt die Dauer`() {
        val regeln = listOf(regel(dauer = 60, ausBeiHell = HellAb.SONNENAUFGANG))
        assertEquals(ziel(60), HueRuleUseCase.autoOffTargetsOf(regeln, amWecker(um(8, 0))))
    }

    @Test
    fun `ohne Ort, ohne Zeitbasis oder bei IMMER gilt die Dauer`() {
        val weckzeit = um(6, 30)
        val regeln = listOf(regel(dauer = 120, ausBeiHell = HellAb.DAEMMERUNG))
        assertEquals(ziel(120), HueRuleUseCase.autoOffTargetsOf(regeln))
        assertEquals(
            ziel(120),
            HueRuleUseCase.autoOffTargetsOf(regeln, FruehAus(weckzeit, weckzeit, TageslichtStand.ohneOrt(zone)))
        )
        assertEquals(
            ziel(120),
            HueRuleUseCase.autoOffTargetsOf(listOf(regel(dauer = 120, ausBeiHell = HellAb.IMMER)), amWecker(weckzeit))
        )
    }

    @Test
    fun `eine Rampe ab Weckzeit wird nie mittendrin ausgeschaltet`() {
        // Rampe 06:50-07:20 (30 min ab Weckzeit), Daemmerung ca. 07:00 liegt mittendrin.
        val weckzeit = um(6, 50)
        val rampe = SunriseConfig(enabled = true, durationMinutes = 30, startBeforeAlarm = false)
        val regeln = listOf(regel(dauer = 60, ausBeiHell = HellAb.DAEMMERUNG, sunrise = rampe))
        assertEquals(ziel(30), HueRuleUseCase.autoOffTargetsOf(regeln, amWecker(weckzeit)))
    }

    @Test
    fun `Snooze nach dem Hellwerden - aus nach einer Minute statt nach der vollen Dauer`() {
        // Wecker 06:30 vor der Daemmerung, zweites Feuern 07:40 schon im Hellen.
        val f = FruehAus(jetzt = um(7, 40), weckzeit = um(6, 30), stand = muenchen)
        assertEquals(ziel(1), HueRuleUseCase.autoOffTargetsOf(listOf(regel(dauer = 60, ausBeiHell = HellAb.DAEMMERUNG)), f))
    }

    @Test
    fun `Vorab-Rampe - gezaehlt ab jetzt, endet auf derselben Minute wie der Weckzeit-Pfad`() {
        // Rampe 15 min VOR dem Wecker 06:30, gestartet 06:15. Weckzeit-Pfad: aus bei Daemmerung.
        val weckzeit = um(6, 30)
        val rampe = SunriseConfig(enabled = true, durationMinutes = 15, startBeforeAlarm = true)
        val regeln = listOf(regel(dauer = 120, ausBeiHell = HellAb.DAEMMERUNG, sunrise = rampe))
        val vorab = HueRuleUseCase.vorabAutoAusZiele(regeln, 15, FruehAus(um(6, 15), weckzeit, muenchen))
        assertEquals(ziel(minuten(um(6, 15), daemmerung)), vorab)
        assertEquals(ziel(minuten(weckzeit, daemmerung)), HueRuleUseCase.autoOffTargetsOf(regeln, amWecker(weckzeit)))
        // Ohne Kuerzung bleibt es beim bisherigen Rechenweg.
        assertEquals(ziel(135), HueRuleUseCase.vorabAutoAusZiele(regeln, 15))
    }

    // --- die Pfade bis zur Bridge ----------------------------------------------------------

    @Test
    fun `Weckzeit-Pfad legt den gekuerzten Timer an`() = runTest {
        val weckzeit = um(6, 30)
        val licht = FakeLightUseCase()
        val uc = HueRuleUseCase(
            FakeConfigRepository(listOf(regel(dauer = 120, ausBeiHell = HellAb.DAEMMERUNG))),
            licht,
            FesteQuelle(muenchen)
        ).apply { jetzt = { weckzeit } }

        uc.executeRulesForAlarm(shift(), LocalTime.of(6, 30), weckzeit = weckzeit).getOrThrow()

        assertEquals(listOf(ziel(minuten(weckzeit, daemmerung))), licht.autoOffCalls)
    }

    @Test
    fun `Weckzeit-Pfad ohne Weckzeit - Dauer wie bisher`() = runTest {
        val licht = FakeLightUseCase()
        HueRuleUseCase(
            FakeConfigRepository(listOf(regel(dauer = 120, ausBeiHell = HellAb.DAEMMERUNG))),
            licht,
            FesteQuelle(muenchen)
        ).executeRulesForAlarm(shift(), LocalTime.of(6, 30), weckzeit = null).getOrThrow()

        assertEquals(listOf(ziel(120)), licht.autoOffCalls)
    }

    @Test
    fun `Vorab-Pfad legt den gekuerzten Timer an`() = runTest {
        val weckzeit = um(6, 30)
        val rampe = SunriseConfig(enabled = true, durationMinutes = 15, startBeforeAlarm = true)
        val licht = FakeLightUseCase()
        val uc = HueRuleUseCase(
            FakeConfigRepository(listOf(regel(dauer = 120, ausBeiHell = HellAb.DAEMMERUNG, sunrise = rampe))),
            licht,
            FesteQuelle(muenchen)
        ).apply { jetzt = { um(6, 15) } }

        uc.executeSunrisePreAlarm("Frühdienst", minutenBisWecker = 15, weckzeit = weckzeit).getOrThrow()

        assertEquals(listOf(ziel(minuten(um(6, 15), daemmerung))), licht.autoOffCalls)
    }

    // --- Bestand ---------------------------------------------------------------------------

    @Test
    fun `eine gespeicherte Regel ohne das Feld liest sich als nur nach Dauer`() {
        val json = Json { ignoreUnknownKeys = true }
        val alt = """{"id":"r","name":"Alt","shiftPattern":"Frühdienst","timeRanges":[]}"""
        assertEquals(HellAb.IMMER, json.decodeFromString<HueSchedule>(alt).ausBeiHell)
    }
}
