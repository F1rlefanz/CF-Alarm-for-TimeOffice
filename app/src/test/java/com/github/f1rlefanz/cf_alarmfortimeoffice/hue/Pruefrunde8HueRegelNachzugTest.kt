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
 * Prüfrunde 8, Befund 2 - Hue-Seite: Beim Umbenennen einer Schichtdefinition muss das Regelmuster
 * mitgezogen werden.
 *
 * DER FEHLER: `HueSchedule.shiftPattern` bindet über den NAMEN der Definition, während der Editor
 * den Namen bei gleichbleibender `id` frei ändern lässt. `findApplicableRules` vergleicht gegen
 * `shift.shiftDefinition.name` - also den NEUEN Namen - und findet die Regel nach einer reinen
 * Beschriftungsänderung nicht mehr: das Licht geht am Wecktag nicht an, während die Regelliste das
 * alte Muster unverändert als aktiv anzeigt.
 */
class Pruefrunde8HueRegelNachzugTest {

    /** In-Memory-Repository mit einer echten Read-Modify-Write-Transaktion. */
    private class FakeConfigRepository(
        rules: List<HueSchedule>,
        private val schreibenScheitert: Boolean = false,
        private val schreibenWirdVerworfen: Boolean = false
    ) : IHueConfigRepository {
        val store = rules.toMutableList()

        override fun getConfiguration(): Flow<HueConfiguration> = flowOf(HueConfiguration())
        override suspend fun saveBridgeConfig(bridgeIp: String, username: String): Result<Unit> = Result.success(Unit)
        override suspend fun getScheduleRules(): Result<List<HueSchedule>> = Result.success(store.toList())
        override suspend fun saveScheduleRule(rule: HueSchedule): Result<Unit> = Result.success(Unit)
        override suspend fun deleteScheduleRule(ruleId: String): Result<Unit> = Result.success(Unit)
        override suspend fun updateScheduleRule(rule: HueSchedule): Result<Unit> = Result.success(Unit)

        override suspend fun updateScheduleRules(
            transform: (List<HueSchedule>) -> List<HueSchedule>
        ): Result<Unit> {
            val neu = transform(store.toList())
            if (schreibenScheitert) return Result.failure(IllegalStateException("Regelbestand nicht lesbar"))
            if (!schreibenWirdVerworfen) {
                store.clear()
                store.addAll(neu)
            }
            return Result.success(Unit)
        }

        override suspend fun clearConfiguration(): Result<Unit> = Result.success(Unit)
        override suspend fun clearBridgeConfig(): Result<Unit> = Result.success(Unit)
    }

    private class FakeLightUseCase : IHueLightUseCaseAdvanced {
        override suspend fun scheduleBridgeAutoOff(targets: List<AutoOffTarget>, shiftName: String): Result<Int> =
            Result.success(targets.size)

        override suspend fun executeBatchLightActions(actions: List<LightAction>): Result<BatchActionResult> =
            Result.success(
                BatchActionResult(
                    totalActions = actions.size,
                    successfulActions = actions.size,
                    failedActions = emptyList(),
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

    private fun rule(id: String, shiftPattern: String) = HueSchedule(
        id = id,
        name = "Regel $id",
        shiftPattern = shiftPattern,
        timeRanges = listOf(
            HueTimeRange(
                actions = listOf(
                    HueLightAction(
                        targetType = TargetType.GROUP,
                        targetId = "1",
                        actionType = ActionType.TURN_ON,
                        on = true,
                        brightness = 200,
                        isGroup = true
                    )
                )
            )
        )
    )

    private fun shift(name: String): ShiftMatch {
        val start = LocalDateTime.of(2026, 8, 20, 6, 0)
        return ShiftMatch(
            shiftDefinition = ShiftDefinition(
                id = "def",
                name = name,
                keywords = listOf(name),
                alarmTime = LocalTime.of(5, 30)
            ),
            calendarEvent = CalendarEvent(
                id = "event",
                title = name,
                startTime = start,
                endTime = start.plusHours(8),
                calendarId = "cal"
            ),
            calculatedAlarmTime = start.minusMinutes(30)
        )
    }

    @Test
    fun `Regel der umbenannten Schicht wird auf den neuen Namen umgeschrieben`() = runTest {
        val repo = FakeConfigRepository(listOf(rule("a", "AD1"), rule("b", "Frueh")))
        val useCase = HueRuleUseCase(repo, FakeLightUseCase())

        assertEquals(1, useCase.renameShiftPattern("AD1", "Abrufdienst").getOrNull())

        assertEquals("Abrufdienst", repo.store.first { it.id == "a" }.shiftPattern)
        assertEquals("Frueh", repo.store.first { it.id == "b" }.shiftPattern)
        // Die eigentliche Wirkung: die Regel greift unter dem neuen Namen wieder.
        val treffer = useCase.findApplicableRules(shift("Abrufdienst"), LocalTime.of(5, 30)).getOrThrow()
        assertEquals(listOf("a"), treffer.map { it.id })
    }

    @Test
    fun `das Universalmuster wird NICHT mitgezogen`() = runTest {
        // "ALL" meint "alle Schichten". Mitziehen wuerde die Regel auf genau eine Schicht
        // einschraenken - der Nutzer verloere das Licht bei allen uebrigen.
        val repo = FakeConfigRepository(listOf(rule("uni", "ALL"), rule("ad", "AD1")))
        val useCase = HueRuleUseCase(repo, FakeLightUseCase())

        assertEquals(1, useCase.renameShiftPattern("AD1", "Abrufdienst").getOrNull())

        assertEquals("ALL", repo.store.first { it.id == "uni" }.shiftPattern)
    }

    @Test
    fun `Nachzug ist gross-kleinschreibungsblind - wie die Regelsuche selbst`() = runTest {
        val repo = FakeConfigRepository(listOf(rule("a", "ad1")))
        val useCase = HueRuleUseCase(repo, FakeLightUseCase())

        assertEquals(1, useCase.renameShiftPattern("AD1", "Abrufdienst").getOrNull())
        assertEquals("Abrufdienst", repo.store.single().shiftPattern)
    }

    @Test
    fun `zweiter Lauf schreibt nichts mehr - idempotent`() = runTest {
        val repo = FakeConfigRepository(listOf(rule("a", "AD1")))
        val useCase = HueRuleUseCase(repo, FakeLightUseCase())

        assertEquals(1, useCase.renameShiftPattern("AD1", "Abrufdienst").getOrNull())
        assertEquals(0, useCase.renameShiftPattern("AD1", "Abrufdienst").getOrNull())
    }

    @Test
    fun `leerer Name wird abgelehnt statt blind geschrieben`() = runTest {
        val repo = FakeConfigRepository(listOf(rule("a", "AD1")))
        val useCase = HueRuleUseCase(repo, FakeLightUseCase())

        assertTrue(useCase.renameShiftPattern("AD1", "   ").isFailure)
        assertEquals("AD1", repo.store.single().shiftPattern)
    }

    @Test
    fun `gescheiterter Schreibvorgang wird gemeldet, nicht verschluckt`() = runTest {
        val repo = FakeConfigRepository(listOf(rule("a", "AD1")), schreibenScheitert = true)
        val useCase = HueRuleUseCase(repo, FakeLightUseCase())

        assertTrue(useCase.renameShiftPattern("AD1", "Abrufdienst").isFailure)
        assertEquals("AD1", repo.store.single().shiftPattern)
    }

    @Test
    fun `still verworfener Schreibvorgang wird durch die Nachpruefung entlarvt`() = runTest {
        // Der Aufruf meldet Erfolg, geschrieben wurde aber nichts. Ohne die Nachpruefung in
        // renameShiftPattern() gaebe sich die Umbenennung als vollstaendig aus, waehrend die
        // Regel weiter den Altnamen traegt.
        val repo = FakeConfigRepository(listOf(rule("a", "AD1")), schreibenWirdVerworfen = true)
        val useCase = HueRuleUseCase(repo, FakeLightUseCase())

        assertTrue(useCase.renameShiftPattern("AD1", "Abrufdienst").isFailure)
        assertEquals("AD1", repo.store.single().shiftPattern)
    }
}
