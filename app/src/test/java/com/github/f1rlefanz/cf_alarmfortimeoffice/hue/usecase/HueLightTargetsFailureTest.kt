package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.BridgeSchedule
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
import java.io.IOException

/**
 * Haelt fest, WANN "keine Lampen" eine Aussage ueber die Bridge ist und wann ein Fehler.
 *
 * Der Fehler, den das verhindert: `HueApiClient.getLights()`/`getGroups()` erkennen die
 * V1-Fehlerhuelle (die Bridge antwortet bei entzogenem Whitelist-Eintrag mit HTTP 200 und
 * `[{"error":{"type":1,"description":"unauthorized user"}}]`) und werfen deshalb - aber der
 * einzige Konsument, [HueLightUseCase.getAllLightTargets], fing dieses Failure ab und machte
 * daraus `Result.success(LightTargets(leer, leer))`. Der Nutzer sah damit weiterhin genau die
 * leere Lampenliste bzw. "Keine Lampen gefunden", die der Waechter beseitigen sollte - ohne
 * jeden Hinweis, dass die Bridge neu gekoppelt werden muss. Und `refreshLightTargets()`
 * ueberschrieb dabei sogar eine vorher korrekt geladene Liste mit einer leeren.
 *
 * Die Gegenprobe gehoert dazu: der TEILERFOLG darf NICHT mit hochgehen. Eine Bridge ohne Gruppen
 * ist voellig normal - dann muessen die Lampen trotzdem nutzbar bleiben.
 */
class HueLightTargetsFailureTest {

    private class FakeLightRepository(
        private val lights: Result<List<HueLight>>,
        private val groups: Result<List<HueGroup>>
    ) : IHueLightRepository {
        override suspend fun getLights(): Result<List<HueLight>> = lights
        override suspend fun getGroups(): Result<List<HueGroup>> = groups

        // Von getAllLightTargets nicht benutzt:
        override suspend fun controlLight(
            lightId: String,
            on: Boolean?,
            brightness: Int?,
            hue: Int?,
            saturation: Int?,
            colorTemperature: Int?,
            transitionTime: Int?,
            alert: String?
        ): Result<Unit> = Result.success(Unit)

        override suspend fun controlGroup(
            groupId: String,
            on: Boolean?,
            brightness: Int?,
            hue: Int?,
            saturation: Int?,
            colorTemperature: Int?,
            transitionTime: Int?,
            alert: String?
        ): Result<Unit> = Result.success(Unit)

        override suspend fun getLightState(lightId: String): Result<HueLight> =
            Result.failure(UnsupportedOperationException())

        override suspend fun getGroupState(groupId: String): Result<HueGroup> =
            Result.failure(UnsupportedOperationException())

        override suspend fun createBridgeSchedule(
            name: String,
            description: String,
            resourcePath: String,
            method: String,
            body: Map<String, Any>,
            localtime: String,
            autodelete: Boolean
        ): Result<String> = Result.success("1")

        override suspend fun getBridgeSchedules(): Result<Map<String, BridgeSchedule>> =
            Result.success(emptyMap())

        override suspend fun deleteBridgeSchedule(scheduleId: String): Result<Unit> = Result.success(Unit)
    }

    private fun light(id: String) = HueLight(
        id = id,
        name = "Lampe $id",
        type = "Extended color light",
        modelid = null,
        manufacturername = null,
        productname = null,
        state = LightState(on = false),
        uniqueid = "uid-$id"
    )

    private fun group(id: String) = HueGroup(
        id = id,
        name = "Gruppe $id",
        type = "Room",
        lights = listOf("1"),
        state = GroupState(any_on = false, all_on = false),
        action = GroupAction(on = false)
    )

    private fun useCase(
        lights: Result<List<HueLight>>,
        groups: Result<List<HueGroup>>
    ) = HueLightUseCase(FakeLightRepository(lights, groups))

    /** DER Fehlerfall: Whitelist-Eintrag weg - beide Abfragen scheitern. */
    @Test
    fun `scheitern beide Abfragen wird der Fehler durchgereicht`() = runTest {
        val unauthorized = IOException("Bridge rejected the request (type 1): unauthorized user")

        val result = useCase(
            lights = Result.failure(unauthorized),
            groups = Result.failure(unauthorized)
        ).getAllLightTargets()

        assertTrue(
            "Ein Totalausfall darf nicht als Erfolg mit leerer Liste getarnt werden - das war der Bug",
            result.isFailure
        )
        assertTrue(
            "Die Beschreibung der Bridge muss bis nach oben durchkommen: ${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()?.message?.contains("unauthorized user") == true
        )
    }

    /** Gegenprobe 1: eine Bridge ohne Gruppen ist normal - die Lampen bleiben nutzbar. */
    @Test
    fun `scheitert nur die Gruppen-Abfrage bleiben die Lampen nutzbar`() = runTest {
        val result = useCase(
            lights = Result.success(listOf(light("4"), light("5"))),
            groups = Result.failure(IOException("groups endpoint hiccup"))
        ).getAllLightTargets()

        assertTrue("Teilerfolg bleibt bewusst ein Erfolg", result.isSuccess)
        assertEquals(2, result.getOrThrow().lights.size)
        assertTrue(result.getOrThrow().groups.isEmpty())
    }

    /** Gegenprobe 2: umgekehrt genauso. */
    @Test
    fun `scheitert nur die Lampen-Abfrage bleiben die Gruppen nutzbar`() = runTest {
        val result = useCase(
            lights = Result.failure(IOException("lights endpoint hiccup")),
            groups = Result.success(listOf(group("1")))
        ).getAllLightTargets()

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow().groups.size)
    }

    /**
     * Gegenprobe 3: eine erreichbare, aber leer konfigurierte Bridge ist KEIN Fehler - sonst
     * waere aus dem Fix die umgekehrte Luege geworden.
     */
    @Test
    fun `eine leere aber erreichbare Bridge ist ein Erfolg`() = runTest {
        val result = useCase(
            lights = Result.success(emptyList()),
            groups = Result.success(emptyList())
        ).getAllLightTargets()

        assertTrue("Erreichbar, nur nichts konfiguriert - das ist eine Aussage, kein Fehler", result.isSuccess)
        assertTrue(result.getOrThrow().lights.isEmpty())
        assertTrue(result.getOrThrow().groups.isEmpty())
    }
}
