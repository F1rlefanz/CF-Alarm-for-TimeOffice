package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.HueRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.MANUELLER_ALARM_SUFFIX
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.HellAb
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.Ort
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.OrtQuelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.TageslichtStand
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.naechsteWeckzeitFuer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs

/**
 * Die Urteilszeile im Hue-Editor (#140): "Nächster Wecker … → Licht geht an / entfällt".
 *
 * Sie MUSS dasselbe sagen wie die Laufzeit (`laeuftBeiTageslicht`: Weckzeit VOR dem Hell-Zeitpunkt
 * des Wecktags) - und bei jeder Luecke schweigen statt zu raten. Ein "entfällt" ohne echten
 * Hell-Zeitpunkt waere eine Behauptung, die die Laufzeit nicht einloest (dort: fail-safe = Licht).
 */
class TageslichtUrteilTest {

    private val berlin = ZoneId.of("Europe/Berlin")
    private val muenchen = Ort(48.14, 11.58, OrtQuelle.PLZ, "München")
    private val stand = TageslichtStand(muenchen, berlin)
    private val tag = LocalDate.of(2026, 10, 14) // Daemmerung ~07:00, Sonnenaufgang ~07:31

    private fun ms(h: Int, m: Int, datum: LocalDate = tag) =
        LocalDateTime.of(datum, LocalTime.of(h, m)).atZone(berlin).toInstant().toEpochMilli()

    // --- Urteil -------------------------------------------------------------------------------

    @Test
    fun `Weckzeit vor Sonnenaufgang - Licht geht an`() {
        val u = tageslichtUrteil(ms(5, 30), stand, HellAb.SONNENAUFGANG)
        assertNotNull(u)
        assertTrue(u!!.laeuft)
        assertEquals(HellAb.SONNENAUFGANG, u.ereignis)
        assertEquals(ms(5, 30), u.weckzeit)
        assertEquals("angezeigt wird in der Zone des Stands", berlin, u.zone)
        // Referenzwert Plan §3: 07:31 +-2 min
        assertTrue(abs(u.hellZeitpunkt - ms(7, 31)) <= 2 * 60_000)
    }

    @Test
    fun `Weckzeit nach Sonnenaufgang - entfaellt`() {
        assertFalse(tageslichtUrteil(ms(7, 40), stand, HellAb.SONNENAUFGANG)!!.laeuft)
    }

    @Test
    fun `Daemmerung ist die fruehere Schwelle`() {
        // 07:15 liegt nach der Daemmerung (~07:00), aber vor dem Sonnenaufgang (~07:31).
        assertFalse(tageslichtUrteil(ms(7, 15), stand, HellAb.DAEMMERUNG)!!.laeuft)
        assertTrue(tageslichtUrteil(ms(7, 15), stand, HellAb.SONNENAUFGANG)!!.laeuft)
    }

    @Test
    fun `genau zum Hell-Zeitpunkt entfaellt die Regel - wie in der Laufzeit`() {
        val hell = stand.hellZeitpunkt(tag, HellAb.SONNENAUFGANG)!!
        assertFalse(tageslichtUrteil(hell, stand, HellAb.SONNENAUFGANG)!!.laeuft)
        assertTrue(tageslichtUrteil(hell - 1, stand, HellAb.SONNENAUFGANG)!!.laeuft)
    }

    @Test
    fun `Wecker am Abend liegt nach dem Morgen des Wecktags - entfaellt`() {
        // Plan §0.9: bewusst so entschieden; die Zeile muss es ehrlich zeigen.
        assertFalse(tageslichtUrteil(ms(21, 0), stand, HellAb.SONNENAUFGANG)!!.laeuft)
    }

    @Test
    fun `die entfaellt-Zeile behauptet kein Tageslicht - sie gilt auch fuer den Abendwecker`() {
        // Derselbe String steht beim Abendwecker (21:00, stockdunkel) direkt unter dem Hinweis
        // "entfällt dann, auch wenn es draußen dunkel ist" - er darf nur den Vergleich nennen.
        val datei = listOf(File("app/src/main/res/values/strings.xml"), File("src/main/res/values/strings.xml"))
            .firstOrNull { it.isFile } ?: error("strings.xml nicht gefunden")
        val zeile = datei.readLines().single { it.contains("name=\"tageslicht_hue_naechster_aus\"") }
            .substringAfter('>').substringBefore("</string>")
        assertFalse("Kausale Hell-Behauptung: $zeile", zeile.contains("hell", ignoreCase = true))
        assertFalse("Kausale Hell-Behauptung: $zeile", zeile.contains("Tageslicht", ignoreCase = true))
        assertTrue("Vergleich mit dem Hell-Zeitpunkt fehlt: $zeile", zeile.contains("danach"))
    }

    @Test
    fun `der Wecktag ist der Kalendertag in der Zone des Stands`() {
        val morgen = tag.plusDays(1)
        val u = tageslichtUrteil(ms(5, 30, morgen), stand, HellAb.SONNENAUFGANG)!!
        assertEquals(stand.hellZeitpunkt(morgen, HellAb.SONNENAUFGANG), u.hellZeitpunkt)
    }

    // --- Luecken: keine Zeile ------------------------------------------------------------------

    @Test
    fun `IMMER - keine Zeile`() {
        assertNull(tageslichtUrteil(ms(5, 30), stand, HellAb.IMMER))
    }

    @Test
    fun `ohne Weckzeit - keine Zeile`() {
        assertNull(tageslichtUrteil(null, stand, HellAb.SONNENAUFGANG))
    }

    @Test
    fun `Stand noch nicht geladen - keine Zeile`() {
        assertNull(tageslichtUrteil(ms(5, 30), null, HellAb.SONNENAUFGANG))
    }

    @Test
    fun `ohne Ort - keine Zeile`() {
        assertNull(tageslichtUrteil(ms(5, 30), TageslichtStand.ohneOrt(berlin), HellAb.SONNENAUFGANG))
    }

    @Test
    fun `kein Ereignis an diesem Tag - keine Zeile statt entfaellt`() {
        // Tromsoe, Polarnacht: kein Sonnenaufgang -> die Regel laeuft (fail-safe), die Zeile schweigt.
        val tromsoe = TageslichtStand(Ort(69.65, 18.96, OrtQuelle.STANDORT, null), berlin)
        val dez = LocalDate.of(2026, 12, 21)
        assertNull(tageslichtUrteil(ms(10, 0, dez), tromsoe, HellAb.SONNENAUFGANG))
    }

    // --- naechsteWeckzeitFuer ------------------------------------------------------------------

    private fun alarm(
        id: Int,
        schicht: String,
        trigger: Long,
        aktiv: Boolean = true,
        still: Boolean = false
    ) = AlarmInfo(
        id = id, shiftId = schicht, shiftName = schicht, triggerTime = trigger,
        formattedTime = "", isActive = aktiv, isSilent = still
    )

    private val jetzt = ms(4, 0)

    @Test
    fun `naechster passender Wecker - der frueheste kuenftige`() {
        val alarme = listOf(
            alarm(1, "Frühdienst", ms(5, 30, tag.plusDays(2))),
            alarm(2, "Frühdienst", ms(5, 30, tag.plusDays(1))),
            alarm(3, "Spätdienst", ms(5, 0))
        )
        assertEquals(ms(5, 30, tag.plusDays(1)), naechsteWeckzeitFuer(alarme, "Frühdienst", jetzt))
    }

    @Test
    fun `vergangene, inaktive und stille Wecker zaehlen nicht`() {
        val alarme = listOf(
            alarm(1, "Frühdienst", ms(3, 0)),
            alarm(2, "Frühdienst", ms(5, 0), aktiv = false),
            alarm(3, "Frühdienst", ms(5, 15), still = true),
            alarm(4, "Frühdienst", ms(5, 30))
        )
        assertEquals(ms(5, 30), naechsteWeckzeitFuer(alarme, "Frühdienst", jetzt))
    }

    @Test
    fun `manueller Wecker zaehlt ueber den reinen Schichtnamen`() {
        val alarme = listOf(alarm(1, "Frühdienst$MANUELLER_ALARM_SUFFIX", ms(5, 30)))
        assertEquals(ms(5, 30), naechsteWeckzeitFuer(alarme, "frühdienst", jetzt))
    }

    @Test
    fun `das Universalmuster trifft jeden Wecker`() {
        val alarme = listOf(alarm(1, "Spätdienst", ms(12, 0)), alarm(2, "Frühdienst", ms(5, 30)))
        assertEquals(ms(5, 30), naechsteWeckzeitFuer(alarme, HueRuleUseCase.UNIVERSAL_SHIFT_PATTERN, jetzt))
    }

    @Test
    fun `ohne Muster oder ohne passenden Wecker - null`() {
        val alarme = listOf(alarm(1, "Frühdienst", ms(5, 30)))
        assertNull(naechsteWeckzeitFuer(alarme, "", jetzt))
        assertNull(naechsteWeckzeitFuer(alarme, "Nachtdienst", jetzt))
    }

    // --- Formatierung der Weckzeit -------------------------------------------------------------

    @Test
    fun `Weckzeit kurz - Wochentag ohne Punkt und Uhrzeit am Stueck`() {
        // 14.10.2026 ist ein Mittwoch; geschuetztes Leerzeichen dazwischen.
        assertEquals("Mi 05:30", weckzeitKurz(ms(5, 30), berlin))
        assertEquals("07:31", uhrzeitKurz(ms(7, 31), berlin))
    }
}
