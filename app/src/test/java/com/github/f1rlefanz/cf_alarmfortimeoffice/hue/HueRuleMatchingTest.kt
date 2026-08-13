package com.github.f1rlefanz.cf_alarmfortimeoffice.hue

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.ActionType
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueLightAction
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueSchedule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueTimeRange
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.TargetType
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.HueConfiguration
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.IHueConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.HueRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.AutoOffTarget
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.BatchActionResult
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.IHueLightUseCaseAdvanced
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.LightAction
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.LightActionResult
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.LightTargets
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftMatch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.time.Duration

/**
 * Haelt die Regel-Auswahl fuer Hue fest: EXAKTER Definitionsname oder das Universalmuster "ALL" -
 * KEIN Keyword-Vergleich, KEINE Teiltreffer.
 *
 * WARUM DAS FESTGENAGELT GEHOERT: Die Spaetschicht traegt das Keyword "S". Ein
 * `keywords.any { shiftName.contains(it) }` (so stand es einmal im AlarmReceiver) laesst "S" auf
 * "S2", "Nachtschicht" und "Zwischendienst" passen - die S2-Regel feuerte nie, die
 * Spaetschicht-Regel bei fast jeder Schicht. Am Emulator gegen die echte Standardkonfiguration
 * reproduziert (v1.11.0). Lange behauptete der KDoc ueber `autoOffTargetsOf()` weiterhin,
 * `findApplicableRules` matche "auch ueber Keywords" - eine veraltete Begruendung, die genau dazu
 * verleitet, diese Fehlerfamilie "wiederherzustellen". Dieser Test macht den Rueckbau sichtbar.
 *
 * Der zweite Teil haelt die eigentliche Begruendung fuer `autoOffTargetsOf()` fest: Es filtert
 * NICHT nach Schichtnamen, weil eine UNIVERSAL-Regel dabei ihr Auto-Aus verloere.
 */
class HueRuleMatchingTest {

    private class FakeConfigRepository(private val rules: List<HueSchedule>) : IHueConfigRepository {
        override fun getConfiguration(): Flow<HueConfiguration> = flowOf(HueConfiguration())
        override suspend fun saveBridgeConfig(bridgeIp: String, username: String): Result<Unit> = Result.success(Unit)
        override suspend fun getScheduleRules(): Result<List<HueSchedule>> = Result.success(rules)
        override suspend fun saveScheduleRule(rule: HueSchedule): Result<Unit> = Result.success(Unit)
        override suspend fun deleteScheduleRule(ruleId: String): Result<Unit> = Result.success(Unit)
        override suspend fun updateScheduleRule(rule: HueSchedule): Result<Unit> = Result.success(Unit)
        override suspend fun clearConfiguration(): Result<Unit> = Result.success(Unit)
        override suspend fun clearBridgeConfig(): Result<Unit> = Result.success(Unit)
    }

    private class FakeLightUseCase : IHueLightUseCaseAdvanced {
        val autoOffCalls = mutableListOf<Pair<List<AutoOffTarget>, String>>()

        override suspend fun scheduleBridgeAutoOff(targets: List<AutoOffTarget>, shiftName: String): Result<Int> {
            autoOffCalls += targets to shiftName
            return Result.success(targets.size)
        }

        override suspend fun executeBatchLightActions(actions: List<LightAction>): Result<BatchActionResult> =
            Result.success(
                BatchActionResult(
                    totalActions = actions.size,
                    successfulActions = actions.size,
                    failedActions = emptyList<LightActionResult>(),
                    overallSuccess = true
                )
            )

        override suspend fun executeActionsWithAutoRevert(
            actions: List<LightAction>,
            revertAfter: Duration
        ): Result<BatchActionResult> = executeBatchLightActions(actions)

        override suspend fun startSunrise(
            targetId: String,
            isGroup: Boolean,
            startKelvin: Int,
            endKelvin: Int,
            endBrightness: Int,
            durationMinutes: Int
        ): Result<Unit> = Result.success(Unit)

        override suspend fun getAllLightTargets(): Result<LightTargets> = Result.success(LightTargets())
        override suspend fun executeLightAction(action: LightAction): Result<LightActionResult> =
            Result.success(LightActionResult(true, action.targetId))
        override suspend fun flashLight(lightId: String): Result<Unit> = Result.success(Unit)
    }

    private fun rule(
        name: String,
        shiftPattern: String,
        enabled: Boolean = true,
        targetId: String = "1",
        durationMinutes: Int? = null
    ): HueSchedule = HueSchedule(
        id = "rule_$name",
        name = name,
        shiftPattern = shiftPattern,
        enabled = enabled,
        timeRanges = listOf(
            HueTimeRange(
                actions = listOf(
                    HueLightAction(
                        targetType = TargetType.GROUP,
                        targetId = targetId,
                        actionType = ActionType.TURN_ON,
                        on = true,
                        brightness = 200,
                        duration = durationMinutes,
                        isGroup = true
                    )
                )
            )
        )
    )

    private fun shift(name: String): ShiftMatch {
        val start = LocalDateTime.of(2026, 8, 6, 6, 0)
        return ShiftMatch(
            shiftDefinition = ShiftDefinition(
                id = "def_$name",
                name = name,
                keywords = listOf("S", name),
                alarmTime = LocalTime.of(5, 30)
            ),
            calendarEvent = CalendarEvent(
                id = "event_$name",
                title = name,
                startTime = start,
                endTime = start.plusHours(8),
                calendarId = "cal"
            ),
            calculatedAlarmTime = start.minusMinutes(30)
        )
    }

    private fun useCase(rules: List<HueSchedule>, light: FakeLightUseCase = FakeLightUseCase()) =
        HueRuleUseCase(FakeConfigRepository(rules), light)

    @Test
    fun `exakter Definitionsname trifft, unabhaengig von Gross-Kleinschreibung`() = runTest {
        val result = useCase(listOf(rule("Licht Frueh", "frühschicht")))
            .findApplicableRules(shift("Frühschicht"), LocalTime.of(5, 30))

        assertEquals(listOf("Licht Frueh"), result.getOrThrow().map { it.name })
    }

    /** Das einbuchstabige Keyword "S" darf NICHT auf "S2" passen. */
    @Test
    fun `einbuchstabiges Muster trifft keine andere Schicht`() = runTest {
        val result = useCase(listOf(rule("Licht Spaet", "S")))
            .findApplicableRules(shift("S2"), LocalTime.of(14, 30))

        assertTrue(
            "Muster 'S' auf Schicht 'S2': genau die Fehlerfamilie, die nie zurueckkommen darf",
            result.getOrThrow().isEmpty()
        )
    }

    @Test
    fun `Teiltreffer trifft nicht`() = runTest {
        val result = useCase(listOf(rule("Licht Frueh", "Frueh")))
            .findApplicableRules(shift("Fruehschicht"), LocalTime.of(5, 30))

        assertTrue("Kein contains-Vergleich - nur exakte Namen", result.getOrThrow().isEmpty())
    }

    @Test
    fun `Universalmuster ALL trifft jede Schicht`() = runTest {
        val useCase = useCase(listOf(rule("Flurlicht", "ALL")))

        assertEquals(1, useCase.findApplicableRules(shift("Frühschicht"), LocalTime.of(5, 30)).getOrThrow().size)
        assertEquals(1, useCase.findApplicableRules(shift("Nachtschicht"), LocalTime.of(20, 0)).getOrThrow().size)
    }

    /**
     * Seit v1.24.0 bietet der Regel-Editor das Universalmuster selbst an ("Alle Schichten",
     * `ShiftPatternCard`); vorher war es nur ueber eine von Hand gebaute Regel erreichbar.
     *
     * Der Test setzt bewusst [HueRuleUseCase.UNIVERSAL_SHIFT_PATTERN] ein - genau den Wert, den
     * die UI schreibt - statt eines zweiten Literals "ALL". Liefen UI-Konstante und Abgleich je
     * auseinander, entstuende eine Regel, die der Editor als ausgewaehlt anzeigt, die aber nie
     * feuert: fuer Licht hinnehmbar, als Muster in dieser App nicht.
     */
    @Test
    fun `im Editor gesetztes Universalmuster trifft jede Schicht`() = runTest {
        val useCase = useCase(listOf(rule("Flurlicht", HueRuleUseCase.UNIVERSAL_SHIFT_PATTERN)))

        assertEquals(1, useCase.findApplicableRules(shift("S2"), LocalTime.of(14, 30)).getOrThrow().size)
        assertEquals(1, useCase.findApplicableRules(shift("Zwischendienst"), LocalTime.of(9, 0)).getOrThrow().size)
        assertEquals(1, useCase.findApplicableRules(shift("IMCF"), LocalTime.of(5, 30)).getOrThrow().size)
    }

    @Test
    fun `deaktivierte Regel trifft nicht`() = runTest {
        val result = useCase(listOf(rule("Licht Frueh", "Frühschicht", enabled = false)))
            .findApplicableRules(shift("Frühschicht"), LocalTime.of(5, 30))

        assertTrue(result.getOrThrow().isEmpty())
    }

    /**
     * Die eigentliche Begruendung dafuer, dass `autoOffTargetsOf()` NICHT nach Schichtnamen
     * filtert: Das `shiftPattern` einer UNIVERSAL-Regel ist per Definition NICHT der
     * Schichtname - ein zweiter Filter wuerde ihr das Auto-Aus nehmen und das Licht anlassen.
     */
    @Test
    fun `Auto-Aus einer UNIVERSAL-Regel geht nicht verloren`() = runTest {
        val light = FakeLightUseCase()
        val rules = listOf(
            rule("Licht Frueh", "Frühschicht", targetId = "1", durationMinutes = 30),
            rule("Flurlicht", "ALL", targetId = "2", durationMinutes = 45)
        )

        useCase(rules, light).executeRulesForAlarm(shift("Frühschicht"), LocalTime.of(5, 30))

        val (targets, shiftName) = light.autoOffCalls.single()
        assertEquals("Frühschicht", shiftName)
        assertEquals(
            "Beide Regeln wurden ausgewaehlt - also brauchen beide ihr Auto-Aus",
            setOf("1" to 30, "2" to 45),
            targets.map { it.targetId to it.delayMinutes }.toSet()
        )
    }
}
