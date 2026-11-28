package com.github.f1rlefanz.cf_alarmfortimeoffice.hue

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.ActionType
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueLightAction
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueSchedule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueTimeRange
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.TargetType
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.HueConfiguration
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.IHueConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.HueRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.TypLichtQuelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.TypLichtUebersetzung
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.AutoOffTarget
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.BatchActionResult
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.IHueLightUseCaseAdvanced
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.LightAction
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.LightActionResult
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.LightTargets
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypLicht
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
 * Die Regeln der alten Test-Fixtures, ueber die ECHTE Migration (#247) an Typen mit den Namen aus
 * den Regelmustern und [weitereSchichten] gehaengt (Muster `konfigAusRegeln`, TypDimmQuelleTest).
 * So pruefen die alten Hue-Faelle unveraendert, was der Nutzer nach dem Update am Typ vorfindet -
 * und nicht eine zweite, im Test nachgebaute Uebersetzung. Eine allgemeine Regel (`"ALL"`) landet
 * dabei als Kopie an jedem Typ; deren Namen muss der Test darum in [weitereSchichten] nennen.
 */
internal fun lichtAmTyp(regeln: List<HueSchedule>, vararg weitereSchichten: String): TypLichtQuelle.Leser {
    val namen = (regeln.map { it.shiftPattern }.filterNot { it.equals(HueRuleUseCase.UNIVERSAL_SHIFT_PATTERN, ignoreCase = true) } +
        weitereSchichten).distinctBy { it.lowercase() }
    val typen = ShiftConfig(
        definitions = namen.mapIndexed { i, name ->
            ShiftDefinition(id = "typ$i", name = name, keywords = listOf(name), alarmTime = LocalTime.of(5, 30))
        }
    )
    val config = TypLichtUebersetzung.uebersetze(typen, regeln).config
    return TypLichtQuelle.Leser { Result.success(config) }
}

/** Ein Hue-Regelspeicher (Altbestand), der [regeln] liefert und Schreibvorgaenge nur bucht. */
internal class AltbestandFake(regeln: List<HueSchedule> = emptyList()) : IHueConfigRepository {
    val store = regeln.toMutableList()
    override fun getConfiguration(): Flow<HueConfiguration> = flowOf(HueConfiguration())
    override suspend fun saveBridgeConfig(bridgeIp: String, username: String): Result<Unit> = Result.success(Unit)
    override suspend fun getScheduleRules(): Result<List<HueSchedule>> = Result.success(store.toList())
    override suspend fun saveScheduleRule(rule: HueSchedule): Result<Unit> = Result.success(Unit)
    override suspend fun deleteScheduleRule(ruleId: String): Result<Unit> = Result.success(Unit)
    override suspend fun updateScheduleRule(rule: HueSchedule): Result<Unit> = Result.success(Unit)
    override suspend fun updateScheduleRules(transform: (List<HueSchedule>) -> List<HueSchedule>): Result<Unit> {
        val neu = transform(store.toList())
        store.clear()
        store.addAll(neu)
        return Result.success(Unit)
    }
    override suspend fun clearBridgeConfig(): Result<Unit> = Result.success(Unit)
}

/**
 * Umbau Etappe 4 (#249): Wecker, Vorab-Sonnenaufgang und Auto-Aus nehmen die Lichtregeln vom Typ.
 * Der Hue-Regelspeicher ist in jedem Fall hier LEER oder traegt bewusst etwas anderes - sonst
 * bestuenden die Faelle auch mit der alten Quelle.
 */
class TypLichtQuelleTest {

    private class Licht : IHueLightUseCaseAdvanced {
        val geschaltet = mutableListOf<LightAction>()
        val autoAus = mutableListOf<AutoOffTarget>()

        override suspend fun scheduleBridgeAutoOff(targets: List<AutoOffTarget>, shiftName: String): Result<Int> {
            autoAus += targets
            return Result.success(targets.size)
        }

        override suspend fun executeBatchLightActions(actions: List<LightAction>): Result<BatchActionResult> {
            geschaltet += actions
            return Result.success(BatchActionResult(actions.size, actions.size, emptyList<LightActionResult>()))
        }

        override suspend fun executeActionsWithAutoRevert(actions: List<LightAction>, revertAfter: Duration) =
            executeBatchLightActions(actions)

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

    private fun regel(id: String, lampe: String, helligkeit: Int = 200, muster: String = "egal", dauer: Int? = 30) =
        HueSchedule(
            id = id,
            name = "Licht $id",
            shiftPattern = muster,
            timeRanges = listOf(
                HueTimeRange(
                    actions = listOf(
                        HueLightAction(
                            targetType = TargetType.LIGHT,
                            targetId = lampe,
                            actionType = ActionType.TURN_ON,
                            on = true,
                            brightness = helligkeit,
                            duration = dauer
                        )
                    )
                )
            )
        )

    private fun typ(id: String, name: String, vararg regeln: HueSchedule) = ShiftDefinition(
        id = id,
        name = name,
        keywords = listOf(name),
        alarmTime = LocalTime.of(5, 30),
        licht = TypLicht().mitRegeln(regeln.toList())
    )

    private fun schicht(name: String): ShiftMatch {
        val start = LocalDateTime.of(2026, 12, 1, 6, 0)
        return ShiftMatch(
            shiftDefinition = ShiftDefinition(id = "def_$name", name = name, keywords = listOf(name), alarmTime = LocalTime.of(5, 30)),
            calendarEvent = CalendarEvent(id = "e_$name", title = name, startTime = start, endTime = start.plusHours(8), calendarId = "cal"),
            calculatedAlarmTime = start.minusMinutes(30)
        )
    }

    private fun useCase(licht: Licht, altbestand: AltbestandFake = AltbestandFake(), leser: TypLichtQuelle.Leser) =
        HueRuleUseCase(altbestand, licht, typLicht = leser)

    @Test
    fun `die Regel am Typ laeuft zur Weckzeit dieses Typs, die eines anderen Typs nicht`() = runTest {
        val config = ShiftConfig(
            definitions = listOf(typ("f", "Frühdienst", regel("rf", lampe = "1")), typ("n", "Nachtdienst", regel("rn", lampe = "2")))
        )
        val licht = Licht()

        useCase(licht, leser = { Result.success(config) }).executeRulesForAlarm(schicht("Frühdienst"), LocalTime.of(5, 30)).getOrThrow()

        assertEquals("Nur die Lampe der Frueh-Regel", listOf("1"), licht.geschaltet.map { it.targetId })
        assertEquals("Auto-Aus nur fuer die Regel des Typs", listOf("1"), licht.autoAus.map { it.targetId })
    }

    @Test
    fun `Vorab-Sonnenaufgang und seine Vorlaufzeit lesen die Regel am Typ`() = runTest {
        val rampe = regel("sr", lampe = "4", dauer = null).copy(
            sunrise = com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.SunriseConfig(enabled = true, durationMinutes = 15)
        )
        val config = ShiftConfig(definitions = listOf(typ("f", "Frühdienst", rampe)))
        val uc = useCase(Licht(), leser = { Result.success(config) })

        val regeln = uc.getAllRules().getOrThrow()
        assertEquals(15, uc.getPreAlarmSunriseLeadMinutes(regeln, "Frühdienst"))
        assertEquals(null, uc.getPreAlarmSunriseLeadMinutes(regeln, "Nachtdienst"))
    }

    @Test
    fun `ein Lesefehler der Konfiguration ist ein Fehlschlag, keine leere Liste - auch kein Rueckfall auf den Altbestand`() = runTest {
        val licht = Licht()
        val altbestand = AltbestandFake(listOf(regel("alt", lampe = "9", muster = "Frühdienst")))
        val uc = useCase(licht, altbestand, leser = { Result.failure(IllegalStateException("shift_prefs unlesbar")) })

        assertTrue("getAllRules", uc.getAllRules().isFailure)
        assertTrue("findApplicableRules", uc.findApplicableRules(schicht("Frühdienst"), LocalTime.of(5, 30)).isFailure)
        assertTrue("executeRulesForAlarm", uc.executeRulesForAlarm(schicht("Frühdienst"), LocalTime.of(5, 30)).isFailure)
        assertTrue("getRule", uc.getRule("alt").isFailure)
        assertTrue("Kein Licht, auch nicht aus dem Altbestand", licht.geschaltet.isEmpty())
    }

    @Test
    fun `ohne verbundenen Leser scheitert jeder Lauf statt keine Regeln zu melden`() = runTest {
        assertTrue(HueRuleUseCase(AltbestandFake(), Licht()).getAllRules().isFailure)
    }

    @Test
    fun `eine geaenderte Regel am Typ wirkt beim naechsten Lauf ohne Neustart`() = runTest {
        var config = ShiftConfig(definitions = listOf(typ("f", "Frühdienst", regel("rf", lampe = "1", helligkeit = 100))))
        val licht = Licht()
        val uc = useCase(licht, leser = { Result.success(config) })

        uc.executeRulesForAlarm(schicht("Frühdienst"), LocalTime.of(5, 30)).getOrThrow()
        config = config.copy(
            definitions = listOf(typ("f", "Frühdienst", regel("rf", lampe = "3", helligkeit = 250)))
        )
        uc.executeRulesForAlarm(schicht("Frühdienst"), LocalTime.of(5, 30)).getOrThrow()

        assertEquals(listOf("1" to 100, "3" to 250), licht.geschaltet.map { it.targetId to it.brightness })
    }

    @Test
    fun `eine am Typ gespeicherte Kopie mit Muster ALL laeuft nur fuer diesen Typ`() = runTest {
        // Die Migration (#247) laesst `shiftPattern` der Kopien einer allgemeinen Regel als "ALL"
        // stehen. Ohne das Ueberschreiben mit dem Typnamen feuerte jede Kopie bei jedem Wecker.
        val config = ShiftConfig(
            definitions = listOf(
                typ("f", "Frühdienst", regel("kopie", lampe = "1", muster = HueRuleUseCase.UNIVERSAL_SHIFT_PATTERN)),
                typ("n", "Nachtdienst")
            )
        )
        val uc = useCase(Licht(), leser = { Result.success(config) })

        assertEquals(listOf("kopie"), uc.findApplicableRules(schicht("Frühdienst"), LocalTime.of(5, 30)).getOrThrow().map { it.id })
        assertTrue(uc.findApplicableRules(schicht("Nachtdienst"), LocalTime.of(20, 0)).getOrThrow().isEmpty())
    }

    @Test
    fun `gleichnamige Typen - der erste gewinnt`() {
        val quelle = TypLichtQuelle(
            ShiftConfig(definitions = listOf(typ("a", "Frühdienst", regel("r1", "1")), typ("b", "frühdienst", regel("r2", "2"))))
        )

        assertEquals(listOf("r1"), quelle.regeln.map { it.id })
        assertEquals(1, quelle.verdeckteRegeln)
    }

    @Test
    fun `der Nachzug einer Umbenennung prueft den Altbestand, den er schreibt - nicht die Typen`() = runTest {
        // Schreiben bleibt bis #250 am Altbestand. Laege die Nachpruefung auf getAllRules(), saehe
        // sie die Typen: steht der Typ noch auf dem alten Namen, meldete sie einen Fehlschlag,
        // obwohl der Altbestand nachgezogen ist.
        val altbestand = AltbestandFake(listOf(regel("a", lampe = "1", muster = "AD1")))
        val config = ShiftConfig(definitions = listOf(typ("t", "AD1", regel("a", lampe = "1"))))
        val uc = useCase(Licht(), altbestand, leser = { Result.success(config) })

        assertEquals(1, uc.renameShiftPattern("AD1", "Abrufdienst").getOrThrow())
        assertEquals("Abrufdienst", altbestand.store.single().shiftPattern)
    }

    @Test
    fun `die Testhilfe haengt die alten Fixtures ueber die echte Migration an die Typen`() = runTest {
        val leser = lichtAmTyp(
            listOf(regel("f", "1", muster = "Frühdienst"), regel("alle", "2", muster = HueRuleUseCase.UNIVERSAL_SHIFT_PATTERN)),
            "Nachtdienst"
        )
        val regeln = TypLichtQuelle(leser.konfiguration().getOrThrow()).regeln

        assertEquals(
            listOf("Frühdienst", "Frühdienst", "Nachtdienst"),
            regeln.map { it.shiftPattern }
        )
        assertEquals(listOf("2", "2"), regeln.filter { it.name == "Licht alle" }.map { it.timeRanges.single().actions.single().targetId })
    }
}
