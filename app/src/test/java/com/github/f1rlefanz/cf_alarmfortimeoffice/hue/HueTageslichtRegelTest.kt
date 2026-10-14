package com.github.f1rlefanz.cf_alarmfortimeoffice.hue

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.ActionType
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueLightAction
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueSchedule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueTimeRange
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.SunriseConfig
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
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.laeuftBeiTageslicht
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.time.Duration

/**
 * Hue-Regel "nur wenn es draussen dunkel ist" (#140), Laufzeitteil.
 *
 * Verglichen wird die WECKZEIT mit dem Hell-Zeitpunkt des WECKTAGS. Jede Luecke (kein Ort, keine
 * Weckzeit, kein Ereignis, [HellAb.IMMER]) heisst: die Regel laeuft - im Zweifel Licht.
 * Referenz: Muenchen, 14.10.2026 - Daemmerung ca. 07:00, Sonnenaufgang ca. 07:31 (CEST).
 */
class HueTageslichtRegelTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val tag: LocalDate = LocalDate.of(2026, 10, 14)
    private val muenchen = TageslichtStand(Ort(48.14, 11.58, OrtQuelle.PLZ, "München"), zone)

    private fun um(stunde: Int, minute: Int): Long =
        tag.atTime(stunde, minute).atZone(zone).toInstant().toEpochMilli()

    private class FesteQuelle(private val stand: TageslichtStand) : TageslichtQuelle {
        var abfragen = 0
        override suspend fun stand(): TageslichtStand {
            abfragen++
            return stand
        }
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
        val batches = mutableListOf<List<LightAction>>()
        val sonnenaufgaenge = mutableListOf<String>()
        val autoOffCalls = mutableListOf<List<AutoOffTarget>>()

        override suspend fun scheduleBridgeAutoOff(targets: List<AutoOffTarget>, shiftName: String): Result<Int> {
            autoOffCalls += targets
            return Result.success(targets.size)
        }

        override suspend fun startSunrise(
            targetId: String, isGroup: Boolean, startKelvin: Int, endKelvin: Int,
            endBrightness: Int, durationMinutes: Int
        ): Result<Unit> {
            sonnenaufgaenge += targetId
            return Result.success(Unit)
        }

        override suspend fun executeBatchLightActions(actions: List<LightAction>): Result<BatchActionResult> {
            batches += actions
            return Result.success(BatchActionResult(actions.size, actions.size, emptyList<LightActionResult>()))
        }

        override suspend fun executeActionsWithAutoRevert(
            actions: List<LightAction>,
            revertAfter: Duration
        ): Result<BatchActionResult> = executeBatchLightActions(actions)

        override suspend fun getAllLightTargets(): Result<LightTargets> = Result.success(LightTargets())
        override suspend fun executeLightAction(action: LightAction): Result<LightActionResult> =
            Result.success(LightActionResult(true, action.targetId))
        override suspend fun flashLight(lightId: String): Result<Unit> = Result.success(Unit)
    }

    private fun aktion(ziel: String) = HueLightAction(
        targetType = TargetType.GROUP,
        targetId = ziel,
        actionType = ActionType.TURN_ON,
        on = true,
        brightness = 200,
        duration = 30,
        isGroup = true
    )

    private fun regel(id: String, hellAb: HellAb, muster: String = "Frühdienst") = HueSchedule(
        id = id,
        name = "Regel $id",
        shiftPattern = muster,
        timeRanges = listOf(HueTimeRange(actions = listOf(aktion("g_$id")))),
        hellAb = hellAb
    )

    private fun sonnenaufgangsRegel(id: String, hellAb: HellAb) = regel(id, hellAb).copy(
        sunrise = SunriseConfig(enabled = true, durationMinutes = 15, startBeforeAlarm = true)
    )

    private fun shift(name: String = "Frühdienst"): ShiftMatch {
        val start = LocalDateTime.of(2026, 10, 14, 6, 0)
        return ShiftMatch(
            shiftDefinition = ShiftDefinition(
                id = "def_$name",
                name = name,
                keywords = listOf(name),
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

    private fun useCase(
        regeln: List<HueSchedule>,
        licht: FakeLightUseCase = FakeLightUseCase(),
        quelle: TageslichtQuelle = FesteQuelle(muenchen)
    ) = HueRuleUseCase(FakeConfigRepository(regeln), licht, quelle)

    // --- laeuftBeiTageslicht ---------------------------------------------------------------

    @Test
    fun `vor dem Hell-Zeitpunkt laeuft die Regel, ab ihm nicht mehr`() {
        val r = regel("r", HellAb.SONNENAUFGANG)
        val hell = muenchen.hellZeitpunkt(tag, HellAb.SONNENAUFGANG)!!
        assertTrue(r.laeuftBeiTageslicht(hell - 1, muenchen))
        assertFalse(r.laeuftBeiTageslicht(hell, muenchen))
        assertFalse(r.laeuftBeiTageslicht(hell + 60_000, muenchen))
    }

    @Test
    fun `grobe Faelle Muenchen - Daemmerung schraenkt frueher ein als Sonnenaufgang`() {
        val daemmerung = regel("d", HellAb.DAEMMERUNG)
        val sonne = regel("s", HellAb.SONNENAUFGANG)
        assertTrue(daemmerung.laeuftBeiTageslicht(um(5, 30), muenchen))
        assertTrue(sonne.laeuftBeiTageslicht(um(5, 30), muenchen))
        // 07:15 - nach der Daemmerung (~07:00), vor dem Sonnenaufgang (~07:31)
        assertFalse(daemmerung.laeuftBeiTageslicht(um(7, 15), muenchen))
        assertTrue(sonne.laeuftBeiTageslicht(um(7, 15), muenchen))
        assertFalse(daemmerung.laeuftBeiTageslicht(um(8, 0), muenchen))
        assertFalse(sonne.laeuftBeiTageslicht(um(8, 0), muenchen))
    }

    @Test
    fun `IMMER und kein Ort heissen keine Einschraenkung`() {
        assertTrue(regel("i", HellAb.IMMER).laeuftBeiTageslicht(um(12, 0), muenchen))
        assertTrue(regel("s", HellAb.SONNENAUFGANG).laeuftBeiTageslicht(um(12, 0), TageslichtStand.ohneOrt(zone)))
    }

    @Test
    fun `kein Ereignis an dem Tag heisst keine Einschraenkung - Polartag in Tromsoe`() {
        val tromsoe = TageslichtStand(Ort(69.65, 18.96, OrtQuelle.STANDORT, null), ZoneId.of("Europe/Oslo"))
        val mittsommer = LocalDate.of(2026, 6, 21).atTime(12, 0).atZone(ZoneId.of("Europe/Oslo")).toInstant().toEpochMilli()
        assertNull(tromsoe.hellZeitpunkt(LocalDate.of(2026, 6, 21), HellAb.SONNENAUFGANG))
        assertTrue(regel("s", HellAb.SONNENAUFGANG).laeuftBeiTageslicht(mittsommer, tromsoe))
    }

    // --- findApplicableRules / executeRulesForAlarm ----------------------------------------

    @Test
    fun `findApplicableRules filtert mit Weckzeit, ohne Weckzeit nicht`() = runTest {
        val regeln = listOf(regel("immer", HellAb.IMMER), regel("dunkel", HellAb.SONNENAUFGANG))

        val mit = useCase(regeln).findApplicableRules(shift(), LocalTime.of(8, 0), weckzeit = um(8, 0))
        assertEquals(listOf("immer"), mit.getOrThrow().map { it.id })

        val frueh = useCase(regeln).findApplicableRules(shift(), LocalTime.of(5, 30), weckzeit = um(5, 30))
        assertEquals(listOf("immer", "dunkel"), frueh.getOrThrow().map { it.id })

        val ohne = useCase(regeln).findApplicableRules(shift(), LocalTime.of(8, 0))
        assertEquals(listOf("immer", "dunkel"), ohne.getOrThrow().map { it.id })
    }

    @Test
    fun `ohne Tageslicht-Regel wird der Ort gar nicht erst gelesen`() = runTest {
        val quelle = FesteQuelle(muenchen)
        useCase(listOf(regel("immer", HellAb.IMMER)), quelle = quelle)
            .findApplicableRules(shift(), LocalTime.of(8, 0), weckzeit = um(8, 0))
        assertEquals(0, quelle.abfragen)
    }

    @Test
    fun `TageslichtQuelle OHNE - alles laeuft`() = runTest {
        val regeln = listOf(regel("d", HellAb.DAEMMERUNG), regel("s", HellAb.SONNENAUFGANG))
        val result = useCase(regeln, quelle = TageslichtQuelle.OHNE)
            .findApplicableRules(shift(), LocalTime.of(12, 0), weckzeit = um(12, 0))
        assertEquals(listOf("d", "s"), result.getOrThrow().map { it.id })
    }

    @Test
    fun `eine werfende Quelle schraenkt nichts ein`() = runTest {
        val kaputt = object : TageslichtQuelle {
            override suspend fun stand(): TageslichtStand = throw IllegalStateException("kaputt")
        }
        val result = useCase(listOf(regel("s", HellAb.SONNENAUFGANG)), quelle = kaputt)
            .findApplicableRules(shift(), LocalTime.of(12, 0), weckzeit = um(12, 0))
        assertEquals(listOf("s"), result.getOrThrow().map { it.id })
    }

    @Test
    fun `executeRulesForAlarm - entfallene Regel schaltet nichts und plant kein Auto-Aus`() = runTest {
        val licht = FakeLightUseCase()
        val result = useCase(listOf(regel("s", HellAb.SONNENAUFGANG)), licht)
            .executeRulesForAlarm(shift(), LocalTime.of(8, 0), weckzeit = um(8, 0))
        assertEquals(0, result.getOrThrow().rulesExecuted)
        assertTrue(licht.batches.isEmpty())
        assertTrue(licht.autoOffCalls.all { it.isEmpty() })
    }

    @Test
    fun `executeRulesForAlarm - vor Sonnenaufgang laeuft die Regel normal`() = runTest {
        val licht = FakeLightUseCase()
        val result = useCase(listOf(regel("s", HellAb.SONNENAUFGANG)), licht)
            .executeRulesForAlarm(shift(), LocalTime.of(5, 30), weckzeit = um(5, 30))
        assertEquals(1, result.getOrThrow().rulesExecuted)
        assertEquals(listOf("g_s"), licht.batches.flatten().map { it.targetId })
    }

    // --- Sonnenaufgangs-Modus: ganz oder gar nicht -----------------------------------------

    @Test
    fun `Sonnenaufgangs-Regel nach Sonnenaufgang - kein Vorlauf, keine Rampe, kein Finalisieren`() = runTest {
        val regeln = listOf(sonnenaufgangsRegel("rampe", HellAb.SONNENAUFGANG))
        val licht = FakeLightUseCase()
        val uc = useCase(regeln, licht)

        assertNull(uc.getPreAlarmSunriseLeadMinutes(regeln, "Frühdienst", um(8, 0), muenchen))
        assertEquals(15, uc.getPreAlarmSunriseLeadMinutes(regeln, "Frühdienst", um(5, 30), muenchen))

        val vorab = uc.executeSunrisePreAlarm("Frühdienst", minutenBisWecker = 15, weckzeit = um(8, 0))
        assertEquals(0, vorab.getOrThrow().rulesExecuted)
        assertTrue(licht.sonnenaufgaenge.isEmpty())
        assertTrue(licht.autoOffCalls.isEmpty())

        val wecker = uc.executeRulesForAlarm(shift(), LocalTime.of(8, 0), weckzeit = um(8, 0))
        assertEquals(0, wecker.getOrThrow().rulesExecuted)
        assertTrue(licht.batches.isEmpty())
    }

    @Test
    fun `Sonnenaufgangs-Regel vor Sonnenaufgang - Rampe laeuft samt Vorab-Auto-Aus`() = runTest {
        val licht = FakeLightUseCase()
        val uc = useCase(listOf(sonnenaufgangsRegel("rampe", HellAb.SONNENAUFGANG)), licht)

        val vorab = uc.executeSunrisePreAlarm("Frühdienst", minutenBisWecker = 15, weckzeit = um(5, 30))
        assertEquals(1, vorab.getOrThrow().rulesExecuted)
        assertEquals(listOf("g_rampe"), licht.sonnenaufgaenge)
        assertEquals(listOf(listOf(AutoOffTarget("g_rampe", true, 45))), licht.autoOffCalls)
    }

    @Test
    fun `Vorlauf ohne Weckzeit oder ohne Stand filtert nicht - alte Auftraege laufen wie bisher`() = runTest {
        val regeln = listOf(sonnenaufgangsRegel("rampe", HellAb.SONNENAUFGANG))
        val licht = FakeLightUseCase()
        val uc = useCase(regeln, licht)

        assertEquals(15, uc.getPreAlarmSunriseLeadMinutes(regeln, "Frühdienst"))
        assertEquals(15, uc.getPreAlarmSunriseLeadMinutes(regeln, "Frühdienst", um(8, 0), null))

        uc.executeSunrisePreAlarm("Frühdienst", minutenBisWecker = null)
        assertEquals(listOf("g_rampe"), licht.sonnenaufgaenge)
    }

    @Test
    fun `tageslichtStand reicht die Quelle durch`() = runTest {
        assertEquals(muenchen.ort, useCase(emptyList()).tageslichtStand().ort)
        assertNull(useCase(emptyList(), quelle = TageslichtQuelle.OHNE).tageslichtStand().ort)
    }
}
