package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.Duration as JavaDuration
import java.util.TimeZone

/**
 * Unit-Tests fuer die Startverzoegerung der Hue-Worker ueber die Sommerzeit-Umstellung.
 *
 * Hintergrund (v1.26.2, Befund 5 der Pruefrunde 4): [HueSmartScheduler] rechnete die Verzoegerung
 * als `JavaDuration.between()` zweier LocalDateTime - eine WANDUHR-Differenz. `setInitialDelay()`
 * erwartet aber real verstreichende Millisekunden. Zweimal im Jahr weichen die beiden um genau
 * eine Stunde voneinander ab.
 *
 * Warum das nicht kosmetisch ist: betroffen sind der `SunriseStartWorker` (die Lichtrampe vor der
 * Weckzeit) und der `PreAlarmHealthCheckWorker` (die Bridge-Pruefung davor). Faellt die Rampe hinter
 * die Weckzeit, ist sie wirkungslos - das Licht geht am Wecktag gar nicht an.
 *
 * DIESER FALL IST AM GERAET NICHT ERREICHBAR, ohne die Systemuhr um Monate vorzustellen; das
 * zerlegt die OAuth-Sitzung und den eingerichteten Emulator-Zustand. Deshalb hier, ohne Android.
 *
 * Jeder Test vergleicht zusaetzlich mit der ALTEN Rechnung und haelt die Differenz fest - damit
 * belegt ist, dass der Test den Fehler ueberhaupt sehen wuerde (Mutationsprobe im Test selbst).
 */
class HueSmartSchedulerDelayTest {

    private lateinit var vorher: TimeZone

    @Before
    fun setUp() {
        vorher = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(vorher)
    }

    private fun alteRechnungMillis(von: LocalDateTime, bis: LocalDateTime): Long =
        JavaDuration.between(von, bis).toMillis()

    @Test
    fun `Herbstumstellung - die reale Dauer ist eine Stunde LAENGER als die Wanduhrdifferenz`() {
        // In der Nacht zum 25.10.2026 wird 03-00 CEST zu 02-00 CET: die Nacht hat 25 Stunden.
        val von = LocalDateTime.of(2026, 10, 24, 20, 0)
        val bis = LocalDateTime.of(2026, 10, 25, 6, 0)

        val real = HueSmartScheduler.realeVerzoegerungMillis(von, bis)

        assertEquals("11 echte Stunden trotz 10 Stunden auf der Wanduhr", 11 * 3600_000L, real)
        assertEquals(
            "Die alte Rechnung lag genau eine Stunde daneben - sonst pruefte dieser Test nichts",
            3600_000L,
            real - alteRechnungMillis(von, bis)
        )
    }

    @Test
    fun `Fruehjahrsumstellung - die reale Dauer ist eine Stunde KUERZER als die Wanduhrdifferenz`() {
        // In der Nacht zum 29.03.2026 wird 02-00 CET zu 03-00 CEST: die Nacht hat 23 Stunden.
        val von = LocalDateTime.of(2026, 3, 28, 20, 0)
        val bis = LocalDateTime.of(2026, 3, 29, 6, 0)

        val real = HueSmartScheduler.realeVerzoegerungMillis(von, bis)

        assertEquals("9 echte Stunden trotz 10 Stunden auf der Wanduhr", 9 * 3600_000L, real)
        assertEquals(
            "Die alte Rechnung lag genau eine Stunde daneben - sonst pruefte dieser Test nichts",
            -3600_000L,
            real - alteRechnungMillis(von, bis)
        )
    }

    @Test
    fun `ohne Umstellung dazwischen sind beide Rechnungen gleich`() {
        // Der Normalfall - er soll sich durch den Fix NICHT geaendert haben.
        val von = LocalDateTime.of(2026, 8, 18, 12, 45)
        val bis = LocalDateTime.of(2026, 8, 20, 12, 20)

        val real = HueSmartScheduler.realeVerzoegerungMillis(von, bis)

        assertEquals((2 * 24 * 60 - 25) * 60_000L, real)
        assertEquals(alteRechnungMillis(von, bis), real)
    }

    @Test
    fun `eine bereits verstrichene Zielzeit ergibt eine negative Dauer`() {
        // Der Aufrufer filtert das vorher weg; die Rechnung selbst darf hier nicht ueberlaufen
        // oder auf 0 klemmen, sonst wuerde ein verpasster Termin sofort feuern.
        val von = LocalDateTime.of(2026, 8, 18, 12, 45)
        val bis = LocalDateTime.of(2026, 8, 18, 12, 15)

        assertEquals(-30 * 60_000L, HueSmartScheduler.realeVerzoegerungMillis(von, bis))
    }
}
