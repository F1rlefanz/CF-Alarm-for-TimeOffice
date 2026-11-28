package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Eine stille Schicht bekommt den Vorab-Sonnenaufgang wie eine laute (#251, SPEC E8 „Lichtwecker“).
 *
 * Hergang: Bis #251 sprang der `AlarmReceiver` bei einer stillen Schicht an allen Hue-Regeln vorbei,
 * und dieser Vorab-Pfad ([HueSmartScheduler.sonnenaufgangsKandidaten] -> `SunriseStartWorker`) filterte
 * deshalb `!isSilent` (#70-Review: sonst fuhr das Licht hoch und blieb ohne Auto-Aus an). Seit #251
 * fuehrt der Receiver die Regeln auch beim stillen Wecker aus - beide Pfade gelten wieder gleich, jetzt
 * mit Licht. Ein stiller Wecker ohne Vorlauf waere ein Lichtwecker, der zur Weckzeit aufs volle Licht
 * springt.
 *
 * Mutationsprobe: mit `!it.isSilent` im Filter ist der erste Test rot.
 */
class HueSmartSchedulerStilleSchichtTest {

    private val now = LocalDateTime.of(2026, 10, 2, 20, 0)
    private val maxTime = now.plusDays(7)

    private fun alarm(id: Int, name: String, weckzeit: LocalDateTime, isSilent: Boolean = false, isActive: Boolean = true) =
        AlarmInfo(
            id = id,
            shiftId = "s$id",
            shiftName = name,
            triggerTime = weckzeit.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            formattedTime = "05:00",
            isActive = isActive,
            isSilent = isSilent
        )

    @Test
    fun `stille Schicht bekommt den Vorab-Sonnenaufgang wie eine laute`() {
        val morgen = now.plusDays(1).withHour(5)
        val kandidaten = HueSmartScheduler.sonnenaufgangsKandidaten(
            listOf(
                alarm(1, "Rufbereitschaft", morgen, isSilent = true),
                alarm(2, "Fruehschicht", morgen.plusDays(1))
            ),
            now, maxTime
        )
        assertEquals(listOf("Rufbereitschaft" to morgen, "Fruehschicht" to morgen.plusDays(1)), kandidaten)
    }

    @Test
    fun `bisherige Filter bleiben - inaktiv, vergangen und ausserhalb des Fensters fallen weg`() {
        val ok = now.plusHours(9)
        val kandidaten = HueSmartScheduler.sonnenaufgangsKandidaten(
            listOf(
                alarm(1, "Inaktiv", ok, isActive = false),
                alarm(2, "Vergangen", now.minusHours(1)),
                alarm(3, "ZuWeit", now.plusDays(8)),
                alarm(4, "Spaet", ok.plusDays(1)),
                alarm(5, "Frueh", ok)
            ),
            now, maxTime
        )
        assertEquals(listOf("Frueh" to ok, "Spaet" to ok.plusDays(1)), kandidaten)
    }
}
