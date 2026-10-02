package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Eine stille Schicht bekommt keinen Vorab-Sonnenaufgang.
 *
 * Hergang (#70-Review): die Statuszeile je Schicht sagt bei einer stillen Schicht „Licht: aus
 * (stille Schicht)", weil der `AlarmReceiver` dort alle Hue-Regeln ueberspringt. Der Vorab-Pfad
 * ([HueSmartScheduler.sonnenaufgangsKandidaten] -> `SunriseStartWorker`) filterte aber nur nach
 * `isActive` - das Licht fuhr vor der Weckzeit trotzdem hoch und blieb ohne Auto-Aus an.
 *
 * Mutationsprobe: ohne `!it.isSilent` im Filter liefert der erste Test zwei Kandidaten statt
 * einem.
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
    fun `stille Schicht plant keinen Vorab-Sonnenaufgang`() {
        val morgen = now.plusDays(1).withHour(5)
        val kandidaten = HueSmartScheduler.sonnenaufgangsKandidaten(
            listOf(
                alarm(1, "Rufbereitschaft", morgen, isSilent = true),
                alarm(2, "Fruehschicht", morgen.plusDays(1))
            ),
            now, maxTime
        )
        assertEquals(listOf("Fruehschicht" to morgen.plusDays(1)), kandidaten)
    }

    @Test
    fun `nur stille Schichten ergibt keine Kandidaten`() {
        val kandidaten = HueSmartScheduler.sonnenaufgangsKandidaten(
            listOf(alarm(1, "Rufbereitschaft", now.plusHours(9), isSilent = true)),
            now, maxTime
        )
        assertEquals(emptyList<Pair<String, LocalDateTime>>(), kandidaten)
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
