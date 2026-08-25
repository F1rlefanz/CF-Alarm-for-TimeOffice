package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.BridgeSchedule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.BridgeTimer
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueGroup
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueLight
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueScene
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.IHueLightRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.AutoOffTarget
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ein geschlummerter Wecker darf keinen ZWEITEN Auto-Aus-Timer auf der Bridge hinterlassen.
 *
 * Warum das zaehlt: Ein Snooze armiert denselben `AlarmReceiver` erneut
 * (`AlarmManagerService.armSnooze`), und dessen `onReceive` durchlaeuft den vollen Weckpfad
 * inklusive `executeHueRulesForAlarm()`. Die Hue-Regel laeuft also ein zweites Mal, und mit ihr
 * `scheduleBridgeAutoOff()`. Ohne das Aufraeumen davor laegen danach ZWEI Timer auf der Bridge -
 * und der aeltere, laengst laufende schaltet mitten im naechsten Weckvorgang aus. Genau dagegen
 * steht `clearOwnBridgeSchedules()`.
 *
 * Die zweite Haelfte ist genauso wichtig: Auf der Bridge des Nutzers liegen FREMDE Zeitplaene
 * (real: zwei "Hue dimmer switch 1"). Wer beim Aufraeumen pauschal loescht, nimmt dem Nutzer
 * seine eigenen Automatisierungen weg. Angefasst wird ausschliesslich, was
 * [BridgeTimer.NAME_PREFIX] traegt.
 *
 * Gegenprobe am Geraet (25.08.2026, echte Bridge BSB002): Ein Weckvorgang legte genau einen
 * `CFAlarm Auto-Off G5` an, `autodelete` raeumte ihn nach dem Ausloesen ab, und beide
 * Dimmer-Schalter-Zeitplaene standen davor wie danach unveraendert da.
 */
class HueAutoOffSnoozeStackingTest {

    /**
     * Eine Bridge, die sich merkt, was auf ihr liegt - inklusive der fremden Zeitplaene, die es
     * dort real gibt.
     */
    private class FakeBridge : IHueLightRepository {
        val zeitplaene = linkedMapOf(
            "5" to BridgeSchedule(name = "Hue dimmer switch 1", localtime = "PT00:00:10"),
            "6" to BridgeSchedule(name = "Hue dimmer switch 1", localtime = "PT00:00:10")
        )
        var naechsteId = 10
        val angelegt = mutableListOf<String>()
        val geloescht = mutableListOf<String>()

        override suspend fun getBridgeSchedules(): Result<Map<String, BridgeSchedule>> =
            Result.success(zeitplaene.toMap())

        override suspend fun deleteBridgeSchedule(scheduleId: String): Result<Unit> {
            geloescht += scheduleId
            zeitplaene.remove(scheduleId)
            return Result.success(Unit)
        }

        override suspend fun createBridgeSchedule(
            name: String,
            description: String,
            resourcePath: String,
            method: String,
            body: Map<String, Any>,
            localtime: String,
            autodelete: Boolean
        ): Result<String> {
            angelegt += name
            val id = (naechsteId++).toString()
            zeitplaene[id] =
                BridgeSchedule(name = name, description = description, localtime = localtime)
            return Result.success(id)
        }

        /** Was nach dem Lauf noch als UNSERER Zeitplan auf der Bridge liegt. */
        fun eigene(): List<BridgeSchedule> =
            zeitplaene.values.filter { BridgeTimer.isOwnSchedule(it) }

        fun fremde(): List<BridgeSchedule> =
            zeitplaene.values.filterNot { BridgeTimer.isOwnSchedule(it) }

        // Von scheduleBridgeAutoOff nicht benutzt:
        override suspend fun getLights(): Result<List<HueLight>> = Result.success(emptyList())
        override suspend fun getGroups(): Result<List<HueGroup>> = Result.success(emptyList())
        override suspend fun getScenes(): Result<List<HueScene>> = Result.success(emptyList())
        override suspend fun applyScene(groupId: String, sceneId: String): Result<Unit> =
            Result.success(Unit)

        override suspend fun controlLight(
            lightId: String, on: Boolean?, brightness: Int?, hue: Int?,
            saturation: Int?, colorTemperature: Int?, transitionTime: Int?, alert: String?
        ): Result<Unit> = Result.success(Unit)

        override suspend fun controlGroup(
            groupId: String, on: Boolean?, brightness: Int?, hue: Int?,
            saturation: Int?, colorTemperature: Int?, transitionTime: Int?, alert: String?
        ): Result<Unit> = Result.success(Unit)

        override suspend fun getLightState(lightId: String): Result<HueLight> =
            Result.failure(UnsupportedOperationException())

        override suspend fun getGroupState(groupId: String): Result<HueGroup> =
            Result.failure(UnsupportedOperationException())
    }

    /** Das Ziel einer Szenenregel: die GRUPPE, denn zu einer Szene gibt es keinen Gegenbefehl. */
    private val szenenZiel = listOf(AutoOffTarget(targetId = "5", isGroup = true, delayMinutes = 30))

    @Test
    fun `Wecker und danach Snooze hinterlassen genau EINEN Auto-Aus-Timer`() = runTest {
        val bridge = FakeBridge()
        val useCase = HueLightUseCase(bridge)

        // 1. Der Weckvorgang.
        useCase.scheduleBridgeAutoOff(szenenZiel, "Frühschicht")
        assertEquals("Nach dem Wecken genau ein eigener Timer", 1, bridge.eigene().size)

        // 2. Der Nutzer schlummert - derselbe AlarmReceiver laeuft erneut.
        useCase.scheduleBridgeAutoOff(szenenZiel, "Frühschicht")

        assertEquals(
            "Nach dem Snooze immer noch genau EIN Timer - sonst schaltet der aeltere zu frueh aus",
            1,
            bridge.eigene().size
        )
        assertEquals("Zweimal angelegt", 2, bridge.angelegt.size)
        assertEquals("Genau der erste wurde geloescht", 1, bridge.geloescht.size)
    }

    @Test
    fun `fremde Zeitplaene bleiben unangetastet`() = runTest {
        val bridge = FakeBridge()
        val useCase = HueLightUseCase(bridge)

        useCase.scheduleBridgeAutoOff(szenenZiel, "Frühschicht")
        useCase.scheduleBridgeAutoOff(szenenZiel, "Frühschicht")

        assertEquals(
            "Die beiden Dimmer-Schalter-Zeitplaene gehoeren dem Nutzer, nicht uns",
            2,
            bridge.fremde().size
        )
        assertTrue(
            "Kein fremder Zeitplan darf geloescht worden sein",
            bridge.geloescht.none { it == "5" || it == "6" }
        )
    }

    @Test
    fun `ohne Auto-Aus entsteht gar kein Zeitplan - und es wird auch nichts geraeumt`() = runTest {
        val bridge = FakeBridge()
        val useCase = HueLightUseCase(bridge)

        val ergebnis = useCase.scheduleBridgeAutoOff(emptyList(), "Frühschicht")

        assertEquals(0, ergebnis.getOrThrow())
        assertTrue(bridge.angelegt.isEmpty())
        assertTrue(
            "Ohne Ziel darf der Aufraeumlauf gar nicht erst starten",
            bridge.geloescht.isEmpty()
        )
        assertEquals(2, bridge.fremde().size)
    }

    @Test
    fun `der Zeitplan trifft die Gruppe der Szene, mit dem relativen Timer`() = runTest {
        val bridge = FakeBridge()
        HueLightUseCase(bridge).scheduleBridgeAutoOff(szenenZiel, "Frühschicht")

        val angelegt = bridge.eigene().single()
        assertEquals("CFAlarm Auto-Off G5", angelegt.name)
        // Relativer Timer, keine absolute Zeit: er zaehlt auf der Uhr der BRIDGE herunter und ist
        // damit immun gegen Zeitzone und Drift zwischen Handy und Bridge.
        assertEquals("PT00:30:00", angelegt.localtime)
    }
}
