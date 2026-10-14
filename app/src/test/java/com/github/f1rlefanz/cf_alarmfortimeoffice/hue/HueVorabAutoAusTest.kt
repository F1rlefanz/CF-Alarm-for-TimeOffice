package com.github.f1rlefanz.cf_alarmfortimeoffice.hue

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.ActionType
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueLightAction
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueSchedule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueTimeRange
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.SunriseConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.TargetType
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.HueConfiguration
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.IHueConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling.workers.SunriseStartWorker
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.HueRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.AutoOffTarget
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.BatchActionResult
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.IHueLightUseCaseAdvanced
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.LightAction
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.LightActionResult
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.LightTargets
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import kotlin.time.Duration

/**
 * Krankheitstag mitten in der laufenden Vorab-Rampe (14.10.2026): Die Rampe beginnt 05:45 fuer
 * einen Wecker um 06:00, das Licht weckt den Kranken, er tippt 05:50 „Dienst fällt aus“. Der
 * Wecker ist geloescht und feuert nie - der Weckzeit-Pfad im `AlarmReceiver`, bis dahin der
 * einzige Ort fuer das Auto-Aus, lief nie, und das Licht blieb an.
 *
 * Seitdem legt die Vorab-Rampe das Auto-Aus GLEICH MIT auf die Bridge, gezaehlt ab jetzt bis
 * Weckzeit plus Regeldauer. Feuert der Wecker doch, ersetzt der Weckzeit-Pfad den Zeitplan.
 */
class HueVorabAutoAusTest {

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

    private class FakeLightUseCase(private val sunriseGelingt: Boolean = true) : IHueLightUseCaseAdvanced {
        val autoOffCalls = mutableListOf<List<AutoOffTarget>>()

        override suspend fun scheduleBridgeAutoOff(targets: List<AutoOffTarget>, shiftName: String): Result<Int> {
            autoOffCalls += targets
            return Result.success(targets.size)
        }

        override suspend fun startSunrise(
            targetId: String, isGroup: Boolean, startKelvin: Int, endKelvin: Int,
            endBrightness: Int, durationMinutes: Int
        ): Result<Unit> = if (sunriseGelingt) Result.success(Unit) else Result.failure(Exception("Bridge weg"))

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

    /** Vorab-Sonnenaufgang (15 min) auf Gruppe 5, Auto-Aus [dauer] Minuten nach dem Wecker. */
    private fun sonnenaufgang(dauer: Int?) = HueSchedule(
        id = "r1",
        name = "Morgenlicht",
        shiftPattern = "Frühdienst",
        enabled = true,
        sunrise = SunriseConfig(enabled = true, durationMinutes = 15, startBeforeAlarm = true),
        timeRanges = listOf(
            HueTimeRange(
                actions = listOf(
                    HueLightAction(
                        targetType = TargetType.GROUP,
                        targetId = "5",
                        actionType = ActionType.TURN_ON,
                        on = true,
                        duration = dauer,
                        isGroup = true
                    )
                )
            )
        )
    )

    @Test
    fun `das Vorab-Auto-Aus endet zum selben Zeitpunkt wie das der Weckzeit`() {
        val regel = sonnenaufgang(dauer = 30)
        assertEquals(listOf(AutoOffTarget("5", true, 30)), HueRuleUseCase.autoOffTargetsOf(listOf(regel)))
        assertEquals(listOf(AutoOffTarget("5", true, 45)), HueRuleUseCase.vorabAutoAusZiele(listOf(regel), 15))
        // Verspaeteter Worker: nie ein Auto-Aus VOR der Regeldauer.
        assertEquals(listOf(AutoOffTarget("5", true, 30)), HueRuleUseCase.vorabAutoAusZiele(listOf(regel), -3))
    }

    @Test
    fun `die Vorab-Rampe legt das Auto-Aus gleich mit an`() = runTest {
        val licht = FakeLightUseCase()
        val useCase = HueRuleUseCase(FakeConfigRepository(listOf(sonnenaufgang(dauer = 30))), licht)

        useCase.executeSunrisePreAlarm("Frühdienst", minutenBisWecker = 15)

        assertEquals(listOf(listOf(AutoOffTarget("5", true, 45))), licht.autoOffCalls)
    }

    @Test
    fun `ohne Weckzeit, ohne Auto-Aus-Dauer oder ohne angegangenes Licht kein Vorab-Auto-Aus`() = runTest {
        // Auftrag aus einer aelteren Version (keine Weckzeit): wie bisher.
        FakeLightUseCase().also { licht ->
            HueRuleUseCase(FakeConfigRepository(listOf(sonnenaufgang(dauer = 30))), licht)
                .executeSunrisePreAlarm("Frühdienst", minutenBisWecker = null)
            assertTrue(licht.autoOffCalls.isEmpty())
        }
        // Rampe gescheitert: es ist nichts angegangen, das ausgehen muesste.
        FakeLightUseCase(sunriseGelingt = false).also { licht ->
            HueRuleUseCase(FakeConfigRepository(listOf(sonnenaufgang(dauer = 30))), licht)
                .executeSunrisePreAlarm("Frühdienst", minutenBisWecker = 15)
            assertTrue(licht.autoOffCalls.isEmpty())
        }
        // Regel ohne Auto-Aus: leere Zielliste - `scheduleBridgeAutoOff` legt dann nichts an.
        FakeLightUseCase().also { licht ->
            HueRuleUseCase(FakeConfigRepository(listOf(sonnenaufgang(dauer = null))), licht)
                .executeSunrisePreAlarm("Frühdienst", minutenBisWecker = 15)
            assertTrue(licht.autoOffCalls.all { it.isEmpty() })
        }
    }

    @Test
    fun `Minuten bis zum Wecker - aufgerundet, nie negativ, echte Zeit`() {
        val jetzt = LocalDateTime.of(2026, 10, 14, 5, 45, 30)
        assertEquals(15, SunriseStartWorker.minutenBisWecker(jetzt, LocalDateTime.of(2026, 10, 14, 6, 0)))
        assertEquals(0, SunriseStartWorker.minutenBisWecker(jetzt, LocalDateTime.of(2026, 10, 14, 5, 40)))
    }
}
