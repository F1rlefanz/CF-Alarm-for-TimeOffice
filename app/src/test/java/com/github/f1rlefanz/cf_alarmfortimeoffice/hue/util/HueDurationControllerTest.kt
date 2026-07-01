package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.util

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.GroupAction
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.GroupState
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueGroup
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueLight
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.LightState
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.IHueLightRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

/**
 * Echte Unit-Tests für [HueDurationController].
 *
 * WICHTIGE EINSCHRÄNKUNG: `scheduleRevert()` startet den Auto-Revert-Timer auf einem
 * ECHTEN `Dispatchers.IO`-Scope (`timerScope`), NICHT auf einem von `runTest`
 * kontrollierbaren virtuellen Test-Dispatcher. Das automatische Zurücksetzen nach
 * Ablauf der Dauer wird daher hier NICHT getestet (würde echte Wartezeit brauchen
 * und wäre flaky). Getestet werden die deterministischen, synchronen Pfade:
 * State-Capture, Anwenden des temporären Zustands, manuelles Zurücksetzen,
 * Timer-Cancel-Buchhaltung und Fehlerpfade.
 */
class HueDurationControllerTest {

    /** Hand-geschriebener Fake statt Mockito. Zeichnet alle Aufrufe zur Prüfung auf. */
    private class FakeHueLightRepository : IHueLightRepository {
        var lightState: LightState? = LightState(on = false, bri = 50)
        var groupState: GroupAction? = GroupAction(on = false, bri = 50)
        var controlLightResult: Result<Unit> = Result.success(Unit)
        var controlGroupResult: Result<Unit> = Result.success(Unit)
        var getLightStateResult: Result<HueLight>? = null
        var getGroupStateResult: Result<HueGroup>? = null

        val controlLightCalls = mutableListOf<LightState>()
        val controlGroupCalls = mutableListOf<GroupAction>()

        override suspend fun getLights(): Result<List<HueLight>> = Result.success(emptyList())
        override suspend fun getGroups(): Result<List<HueGroup>> = Result.success(emptyList())

        override suspend fun controlLight(
            lightId: String,
            on: Boolean?,
            brightness: Int?,
            hue: Int?,
            saturation: Int?,
            colorTemperature: Int?,
            transitionTime: Int?,
            alert: String?
        ): Result<Unit> {
            controlLightCalls.add(LightState(on = on ?: false, bri = brightness ?: 254, hue = hue, sat = saturation))
            return controlLightResult
        }

        override suspend fun controlGroup(
            groupId: String,
            on: Boolean?,
            brightness: Int?,
            hue: Int?,
            saturation: Int?,
            colorTemperature: Int?,
            transitionTime: Int?,
            alert: String?
        ): Result<Unit> {
            controlGroupCalls.add(GroupAction(on = on ?: false, bri = brightness, hue = hue, sat = saturation))
            return controlGroupResult
        }

        override suspend fun getLightState(lightId: String): Result<HueLight> {
            getLightStateResult?.let { return it }
            val state = lightState
                ?: return Result.failure(Exception("no state"))
            return Result.success(
                HueLight(
                    id = lightId,
                    name = "Test Light",
                    type = "Extended color light",
                    modelid = null,
                    manufacturername = null,
                    productname = null,
                    state = state,
                    uniqueid = "unique-$lightId"
                )
            )
        }

        override suspend fun getGroupState(groupId: String): Result<HueGroup> {
            getGroupStateResult?.let { return it }
            val action = groupState
                ?: return Result.failure(Exception("no state"))
            return Result.success(
                HueGroup(
                    id = groupId,
                    name = "Test Group",
                    type = "Room",
                    lights = listOf("1", "2"),
                    state = GroupState(any_on = action.on, all_on = action.on),
                    action = action
                )
            )
        }
    }

    private fun lightState(on: Boolean = true, bri: Int = 200) = LightState(on = on, bri = bri)
    private fun groupAction(on: Boolean = true, bri: Int? = 200) = GroupAction(on = on, bri = bri)

    // ---- setLightWithDuration: Original-Zustand wird einmalig erfasst ----

    @Test
    fun `setLightWithDuration erfasst Original-Zustand und wendet temporaeren Zustand an`() = runTest {
        val repo = FakeHueLightRepository().apply { lightState = LightState(on = false, bri = 50) }
        val controller = HueDurationController(repo)

        val result = controller.setLightWithDuration("light1", lightState(on = true, bri = 200), 1.minutes)

        assertTrue(result.isSuccess)
        assertEquals(1, repo.controlLightCalls.size)
        assertEquals(true, repo.controlLightCalls[0].on)
        assertEquals(200, repo.controlLightCalls[0].bri)

        // Original-Zustand wurde erfasst und ist über getActiveControls() sichtbar
        val info = controller.getActiveControls()
        assertTrue("light1 sollte als aktiv erfasstes Licht gelten", info.activeLights.contains("light1"))
        assertEquals(1, info.totalActiveTimers)

        controller.cleanup()
    }

    @Test
    fun `setLightWithDuration schlaegt fehl wenn State-Capture scheitert und revertOnFailure=true`() = runTest {
        val repo = FakeHueLightRepository().apply { lightState = null } // getLightState() -> failure
        val controller = HueDurationController(repo)

        val result = controller.setLightWithDuration("light1", lightState(), 1.minutes, revertOnFailure = true)

        assertTrue(result.isFailure)
        // Da die Zustandserfassung fehlschlug, darf controlLight NIE aufgerufen worden sein
        assertEquals(0, repo.controlLightCalls.size)
    }

    @Test
    fun `setLightWithDuration wendet trotzdem an wenn State-Capture scheitert und revertOnFailure=false`() = runTest {
        val repo = FakeHueLightRepository().apply { lightState = null }
        val controller = HueDurationController(repo)

        val result = controller.setLightWithDuration("light1", lightState(on = true, bri = 100), 1.minutes, revertOnFailure = false)

        assertTrue(result.isSuccess)
        assertEquals(1, repo.controlLightCalls.size)

        controller.cleanup()
    }

    @Test
    fun `setLightWithDuration entfernt gespeicherten Zustand wenn controlLight fehlschlaegt und revertOnFailure=true`() = runTest {
        val repo = FakeHueLightRepository().apply {
            lightState = LightState(on = false, bri = 50)
            controlLightResult = Result.failure(Exception("bridge unreachable"))
        }
        val controller = HueDurationController(repo)

        val result = controller.setLightWithDuration("light1", lightState(), 1.minutes, revertOnFailure = true)

        assertTrue(result.isFailure)
        // Da das Anwenden fehlschlug, darf kein Original-Zustand mehr gespeichert sein
        val info = controller.getActiveControls()
        assertTrue("Kein Original-Zustand darf nach fehlgeschlagenem Control gespeichert bleiben", info.activeLights.isEmpty())
    }

    @Test
    fun `setLightWithDuration erfasst Original-Zustand nicht doppelt bei wiederholtem Aufruf`() = runTest {
        val repo = FakeHueLightRepository().apply { lightState = LightState(on = false, bri = 30) }
        val controller = HueDurationController(repo)

        controller.setLightWithDuration("light1", lightState(bri = 100), 1.minutes)
        controller.setLightWithDuration("light1", lightState(bri = 150), 1.minutes)

        // getLightState() darf nur beim ERSTEN Aufruf benutzt werden, um den Originalzustand zu holen -
        // der zweite Aufruf darf ihn nicht überschreiben. Wir prüfen das indirekt über revertLight():
        val revertResult = controller.revertLight("light1")
        assertTrue(revertResult.isSuccess)
        // Der letzte controlLight-Call beim manuellen Revert muss auf den URSPRÜNGLICHEN Zustand (bri=30) zurücksetzen
        assertEquals(30, repo.controlLightCalls.last().bri)
        assertEquals(false, repo.controlLightCalls.last().on)
    }

    // ---- revertLight / revertGroup ----

    @Test
    fun `revertLight ohne vorherigen setLightWithDuration-Aufruf schlaegt fehl`() = runTest {
        val controller = HueDurationController(FakeHueLightRepository())

        val result = controller.revertLight("unknown-light")

        assertTrue(result.isFailure)
    }

    @Test
    fun `revertLight setzt Original-Zustand zurueck und entfernt ihn aus dem Speicher`() = runTest {
        val repo = FakeHueLightRepository().apply { lightState = LightState(on = false, bri = 42) }
        val controller = HueDurationController(repo)
        controller.setLightWithDuration("light1", lightState(bri = 200), 1.minutes)

        val result = controller.revertLight("light1")

        assertTrue(result.isSuccess)
        assertEquals(42, repo.controlLightCalls.last().bri)
        assertEquals(false, repo.controlLightCalls.last().on)

        // Nach dem Revert ist kein Original-Zustand mehr gespeichert -> zweiter Revert schlägt fehl
        val secondRevert = controller.revertLight("light1")
        assertTrue(secondRevert.isFailure)
    }

    @Test
    fun `setGroupWithDuration und revertGroup funktionieren analog zu Light`() = runTest {
        val repo = FakeHueLightRepository().apply { groupState = GroupAction(on = false, bri = 77) }
        val controller = HueDurationController(repo)

        val setResult = controller.setGroupWithDuration("group1", groupAction(bri = 220), 1.minutes)
        assertTrue(setResult.isSuccess)
        assertEquals(220, repo.controlGroupCalls.last().bri)

        val revertResult = controller.revertGroup("group1")
        assertTrue(revertResult.isSuccess)
        assertEquals(77, repo.controlGroupCalls.last().bri)
        assertEquals(false, repo.controlGroupCalls.last().on)
    }

    // ---- cancelRevert / cancelAllReverts / getActiveControls Buchhaltung ----

    @Test
    fun `cancelRevert auf unbekannter ID ist ein sicheres No-Op`() {
        val controller = HueDurationController(FakeHueLightRepository())

        controller.cancelRevert("does-not-exist") // darf keine Exception werfen

        assertEquals(0, controller.getActiveControls().totalActiveTimers)
    }

    @Test
    fun `getActiveControls meldet aktive Lichter und Gruppen getrennt`() = runTest {
        val repo = FakeHueLightRepository().apply {
            lightState = LightState(on = false, bri = 10)
            groupState = GroupAction(on = false, bri = 10)
        }
        val controller = HueDurationController(repo)

        controller.setLightWithDuration("light1", lightState(), 1.minutes)
        controller.setGroupWithDuration("group1", groupAction(), 1.minutes)

        val info = controller.getActiveControls()
        assertEquals(listOf("light1"), info.activeLights)
        assertEquals(listOf("group1"), info.activeGroups)
        assertEquals(2, info.totalActiveTimers)
        // Gruppen-Timer werden intern unter "group_<id>" verwaltet
        assertTrue(info.pendingReverts.contains("light1"))
        assertTrue(info.pendingReverts.contains("group_group1"))

        controller.cleanup()
    }

    @Test
    fun `cancelAllReverts leert alle aktiven Timer`() = runTest {
        val repo = FakeHueLightRepository().apply { lightState = LightState(on = false, bri = 10) }
        val controller = HueDurationController(repo)

        controller.setLightWithDuration("light1", lightState(), 1.minutes)
        controller.setLightWithDuration("light2", lightState(), 1.minutes)
        assertEquals(2, controller.getActiveControls().totalActiveTimers)

        controller.cancelAllReverts()

        assertEquals(0, controller.getActiveControls().totalActiveTimers)
        // Original-Zustände selbst bleiben aber (nur Timer werden gecancelt) gespeichert
        assertTrue(controller.getActiveControls().activeLights.containsAll(listOf("light1", "light2")))

        controller.cleanup()
    }

    @Test
    fun `cleanup leert Original-Zustaende und Timer vollstaendig`() = runTest {
        val repo = FakeHueLightRepository().apply { lightState = LightState(on = false, bri = 10) }
        val controller = HueDurationController(repo)
        controller.setLightWithDuration("light1", lightState(), 1.minutes)

        controller.cleanup()

        val info = controller.getActiveControls()
        assertTrue(info.activeLights.isEmpty())
        assertTrue(info.activeGroups.isEmpty())
        assertTrue(info.pendingReverts.isEmpty())
        assertEquals(0, info.totalActiveTimers)
    }

    // ---- Companion-Factory-Methoden ----

    @Test
    fun `createAlarmLightState liefert eingeschaltetes Licht mit geclampter Helligkeit`() {
        val state = HueDurationController.createAlarmLightState(brightness = 999)

        assertTrue(state.on)
        // brightness.coerceIn(1, 254) -> 999 wird auf 254 begrenzt
        assertEquals(254, state.bri)
        assertEquals("none", state.alert)
        assertEquals(10, state.transitiontime)
    }

    @Test
    fun `createAlarmLightState clampt zu niedrige Helligkeit auf Minimum`() {
        val state = HueDurationController.createAlarmLightState(brightness = -5)

        assertEquals(1, state.bri)
    }

    @Test
    fun `createAlarmGroupAction verwendet WARM_WHITE Farbpreset standardmaessig`() {
        val action = HueDurationController.createAlarmGroupAction()
        val warmWhite = HueColorConverter.getPresetColor(HueColorConverter.ColorPreset.WARM_WHITE)

        assertTrue(action.on)
        assertEquals(warmWhite.hue, action.hue)
        assertEquals(warmWhite.saturation, action.sat)
    }

    @Test
    fun `createPulsingLightState verwendet lselect Alert fuer Dauerpulsieren`() {
        val state = HueDurationController.createPulsingLightState()

        assertEquals("lselect", state.alert)
        assertEquals(254, state.bri)
    }
}
