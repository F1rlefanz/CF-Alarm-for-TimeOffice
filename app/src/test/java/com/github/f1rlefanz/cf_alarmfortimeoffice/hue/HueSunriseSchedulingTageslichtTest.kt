package com.github.f1rlefanz.cf_alarmfortimeoffice.hue

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.ActionType
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueLightAction
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueSchedule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueTimeRange
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.SunriseConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.TargetType
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling.HueSmartScheduler
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.HueRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.IHueLightUseCaseAdvanced
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.IHueConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.HellAb
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.Ort
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.OrtQuelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.TageslichtStand
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Planung der Vorab-Sonnenaufgaenge mit Tageslicht-Bedingung (#140): `scheduleSunriseStarts`
 * selbst ist privat und WorkManager-gebunden - geprueft wird der reine Teil, den es benutzt:
 * [HueSmartScheduler.sonnenaufgangsKandidaten] -> [HueSmartScheduler.weckzeitMillis] ->
 * `getPreAlarmSunriseLeadMinutes(..., weckzeit, stand)`. Ein Wecker NACH Sonnenaufgang bekommt
 * keinen Vorlauf, also keinen Worker.
 */
class HueSunriseSchedulingTageslichtTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val muenchen = TageslichtStand(Ort(48.14, 11.58, OrtQuelle.PLZ, "München"), zone)

    private val regeln = listOf(
        HueSchedule(
            id = "rampe",
            name = "Morgenlicht",
            shiftPattern = "Frühdienst",
            sunrise = SunriseConfig(enabled = true, durationMinutes = 20, startBeforeAlarm = true),
            timeRanges = listOf(
                HueTimeRange(
                    listOf(HueLightAction(TargetType.GROUP, "5", actionType = ActionType.TURN_ON, on = true, isGroup = true))
                )
            ),
            hellAb = HellAb.SONNENAUFGANG
        )
    )

    private val ruleUseCase = HueRuleUseCase(mock<IHueConfigRepository>(), mock<IHueLightUseCaseAdvanced>())

    private fun wecker(id: Int, um: LocalDateTime) = AlarmInfo(
        id = id,
        shiftId = "fd",
        shiftName = "Frühdienst",
        triggerTime = um.atZone(zone).toInstant().toEpochMilli(),
        formattedTime = um.toLocalTime().toString()
    )

    @Test
    fun `nur der Wecker vor Sonnenaufgang bekommt einen Vorlauf`() {
        val jetzt = LocalDateTime.of(2026, 10, 13, 22, 0)
        // Weit weg vom Sonnenaufgang (~07:31 MESZ), damit die Geraetezone des Testrechners nicht kippt.
        val frueh = LocalDateTime.of(2026, 10, 14, 3, 0)
        val spaet = LocalDateTime.of(2026, 10, 15, 12, 0)
        val kandidaten = HueSmartScheduler.sonnenaufgangsKandidaten(
            listOf(wecker(1, frueh), wecker(2, spaet)), jetzt, jetzt.plusDays(3)
        )

        val vorlauf = kandidaten.map { (schicht, weckzeit) ->
            ruleUseCase.getPreAlarmSunriseLeadMinutes(
                regeln, schicht, HueSmartScheduler.weckzeitMillis(weckzeit), muenchen
            )
        }

        assertEquals(listOf(20, null), vorlauf)
    }

    @Test
    fun `ohne Ort plant jeder Kandidat wie bisher`() {
        val weckzeit = LocalDateTime.of(2026, 10, 15, 12, 0)
        assertEquals(
            20,
            ruleUseCase.getPreAlarmSunriseLeadMinutes(
                regeln, "Frühdienst", HueSmartScheduler.weckzeitMillis(weckzeit), TageslichtStand.ohneOrt(zone)
            )
        )
    }

    @Test
    fun `weckzeitMillis ist die Ortszeit in der Geraetezone`() {
        val weckzeit = LocalDateTime.of(2026, 10, 25, 2, 30) // Tag der Zeitumstellung
        assertEquals(
            weckzeit.atZone(ZoneId.of("Europe/Berlin")).toInstant().toEpochMilli(),
            HueSmartScheduler.weckzeitMillis(weckzeit, ZoneId.of("Europe/Berlin"))
        )
    }
}
