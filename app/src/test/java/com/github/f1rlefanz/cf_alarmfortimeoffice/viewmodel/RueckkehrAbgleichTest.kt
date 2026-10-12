package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.abgleichZeitText
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.CalendarViewModel.Companion.RUECKKEHR_FRISCH_MS
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.CalendarViewModel.Companion.sollBeiRueckkehrLaden
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Haelt die reinen Funktionen von "Neuladen bei Rueckkehr" (A, 1.48.0) fest: die Entscheidung
 * [CalendarViewModel.sollBeiRueckkehrLaden] und die Anzeige "Abgeglichen um HH:MM"
 * ([abgleichZeitText]).
 *
 * Befund 12.10.2026: drei Rueckkehrten in die App luden nichts, obwohl Google den neuen Dienst seit
 * 15:52 hatte. Die Verdrahtung (Ladeweg, Zwischenspeicher, Anzeige bleibt stehen) prueft
 * [CalendarViewModelRueckkehrTest].
 */
class RueckkehrAbgleichTest {

    private val minute = 60_000L
    private val ladebeginn = 1_760_000_000_000L

    private fun entscheide(
        jetzt: Long = ladebeginn + 10 * minute,
        hatAuswahl: Boolean = true,
        laedtGerade: Boolean = false,
        letzterLadebeginn: Long? = ladebeginn,
        letzterHintergrundAbruf: Long? = null
    ) = sollBeiRueckkehrLaden(
        jetzt = jetzt,
        hatAuswahl = hatAuswahl,
        laedtGerade = laedtGerade,
        letzterLadebeginn = letzterLadebeginn,
        letzterHintergrundAbruf = letzterHintergrundAbruf
    )

    // --- Entscheidung ------------------------------------------------------------------------

    @Test
    fun `mehr als 5 Minuten seit dem letzten Ladebeginn - neu laden`() {
        assertTrue(entscheide(jetzt = ladebeginn + 5 * minute + 1))
        assertTrue(entscheide(jetzt = ladebeginn + 3 * 60 * minute))
    }

    @Test
    fun `weniger als 5 Minuten - nicht neu laden`() {
        assertFalse(entscheide(jetzt = ladebeginn))
        assertFalse(entscheide(jetzt = ladebeginn + 4 * minute))
    }

    @Test
    fun `genau 5 Minuten gelten noch als frisch`() {
        // "laenger als 5 Minuten" (Website) - die Grenze selbst laedt nicht.
        assertFalse(entscheide(jetzt = ladebeginn + 5 * minute))
    }

    @Test
    fun `erster Start - noch nie geladen - laedt NICHT doppelt`() {
        // Den ersten Abruf macht der Startpfad (Collector der Kalenderauswahl). Auch ein
        // Hintergrund-Zeitstempel aendert daran nichts.
        assertFalse(entscheide(letzterLadebeginn = null, jetzt = ladebeginn + 60 * minute))
        assertFalse(
            entscheide(
                letzterLadebeginn = null,
                jetzt = ladebeginn + 60 * minute,
                letzterHintergrundAbruf = ladebeginn + 30 * minute
            )
        )
    }

    @Test
    fun `Hintergrund hat seit dem letzten Laden neu abgerufen - neu laden, auch innerhalb der 5 Minuten`() {
        assertTrue(
            entscheide(
                jetzt = ladebeginn + 2 * minute,
                letzterHintergrundAbruf = ladebeginn + 1 * minute
            )
        )
    }

    @Test
    fun `Hintergrund-Abruf vor dem letzten Laden - kein Grund zum Neuladen`() {
        assertFalse(
            entscheide(
                jetzt = ladebeginn + 2 * minute,
                letzterHintergrundAbruf = ladebeginn - 1 * minute
            )
        )
        // Gleichstand ist nicht "neuer".
        assertFalse(
            entscheide(
                jetzt = ladebeginn + 2 * minute,
                letzterHintergrundAbruf = ladebeginn
            )
        )
    }

    @Test
    fun `keine Kalenderauswahl - nicht laden, auch wenn es lange her ist`() {
        assertFalse(entscheide(hatAuswahl = false, jetzt = ladebeginn + 60 * minute))
        assertFalse(
            entscheide(
                hatAuswahl = false,
                jetzt = ladebeginn + 1 * minute,
                letzterHintergrundAbruf = ladebeginn + 30 * 1000L
            )
        )
    }

    @Test
    fun `laedt gerade - kein zweiter Ladevorgang`() {
        assertFalse(entscheide(laedtGerade = true, jetzt = ladebeginn + 60 * minute))
        assertFalse(
            entscheide(
                laedtGerade = true,
                jetzt = ladebeginn + 1 * minute,
                letzterHintergrundAbruf = ladebeginn + 30 * 1000L
            )
        )
    }

    @Test
    fun `zurueckgestellte Uhr - im Zweifel neu laden`() {
        // Der Ladebeginn liegt "in der Zukunft": ueber das Alter des Stands ist nichts bekannt.
        assertTrue(entscheide(jetzt = ladebeginn - 1 * minute))
    }

    @Test
    fun `die Schwelle ist 5 Minuten - und die Website sagt dasselbe`() {
        assertEquals(5 * 60 * 1000L, RUECKKEHR_FRISCH_MS)

        // docs/troubleshooting.html#abgleich verspricht dem Nutzer die Zahl woertlich. Wer die
        // Konstante aendert, muss den Text dort mitziehen.
        val relativ = "docs/troubleshooting.html"
        val seite = listOf(File("../$relativ"), File(relativ)).firstOrNull { it.isFile }
            ?: error("$relativ nicht gefunden (Arbeitsverzeichnis ${File(".").absolutePath})")
        val text = seite.readText()
        val abschnitt = text.substringAfter("id=\"abgleich\"", missingDelimiterValue = "")
            .substringBefore("</section>")
        assertTrue("Abschnitt id=\"abgleich\" fehlt auf der Website", abschnitt.isNotEmpty())
        assertTrue(
            "Die Website muss dieselbe Schwelle nennen wie RUECKKEHR_FRISCH_MS",
            abschnitt.contains("länger als ${RUECKKEHR_FRISCH_MS / 60_000} Minuten")
        )
    }

    // --- Anzeige "Abgeglichen um" --------------------------------------------------------------

    private val berlin: ZoneId = ZoneId.of("Europe/Berlin")

    private fun millis(jahr: Int, monat: Int, tag: Int, stunde: Int, minute: Int, zone: ZoneId = berlin) =
        LocalDateTime.of(jahr, monat, tag, stunde, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `heute - nur die Uhrzeit`() {
        assertEquals(
            "Abgeglichen um 19:41",
            abgleichZeitText(millis(2026, 10, 12, 19, 41), zone = berlin, heute = LocalDate.of(2026, 10, 12))
        )
        // Fuehrende Null bleibt (HH:mm).
        assertEquals(
            "Abgeglichen um 07:05",
            abgleichZeitText(millis(2026, 10, 12, 7, 5), zone = berlin, heute = LocalDate.of(2026, 10, 12))
        )
    }

    @Test
    fun `frueherer Tag - mit Datum, damit gestern nicht wie heute aussieht`() {
        assertEquals(
            "Abgeglichen am 11.10. um 23:59",
            abgleichZeitText(millis(2026, 10, 11, 23, 59), zone = berlin, heute = LocalDate.of(2026, 10, 12))
        )
        assertEquals(
            "Abgeglichen am 05.01. um 08:00",
            abgleichZeitText(millis(2026, 1, 5, 8, 0), zone = berlin, heute = LocalDate.of(2026, 10, 12))
        )
    }

    private fun quelltext(pfad: String): String {
        val relativ = "src/main/java/com/github/f1rlefanz/cf_alarmfortimeoffice/$pfad"
        return listOf(File(relativ), File("app/$relativ")).firstOrNull { it.isFile }?.readText()
            ?: error("$relativ nicht gefunden (Arbeitsverzeichnis ${File(".").absolutePath})")
    }

    @Test
    fun `die Karte Kalender-Events zeigt den Abgleich-Zeitpunkt wirklich an`() {
        // Die Website sagt: "Wann zuletzt abgeglichen wurde, zeigt die Uebersicht in der Karte
        // Kalender-Events". Ein Feld, das niemand rendert, macht daraus eine Behauptung ueber eine
        // Anzeige, die es nicht gibt (Skill cfalarm-ui-und-navigation).
        val home = quelltext("ui/screens/tabs/HomeTabContent.kt")
        assertTrue(
            "HomeTabContent rendert calendarState.letzterAbgleich nicht mehr",
            Regex("""calendarState\.letzterAbgleich\?\.let \{ zeitpunkt ->\s*Text\(\s*abgleichZeitText\(zeitpunkt\)""")
                .containsMatchIn(home)
        )
    }

    @Test
    fun `der Erklaertext unter dem Abgleich-Knopf nennt, wann TimeOffice eintraegt`() {
        // Befund 12.10.2026: TimeOffice schrieb den gemeldeten Dienst erst beim Oeffnen in den
        // Kalender. Ohne den Satz sucht man den Fehler in CF-Alarm.
        val home = quelltext("ui/screens/tabs/HomeTabContent.kt")
        assertTrue(
            home.contains("TimeOffice trägt eine Änderung erst ein, wenn du TimeOffice öffnest.")
        )
    }

    @Test
    fun `der Kalendertag zaehlt in der Zone des Geraets, nicht in UTC`() {
        // 22:30 UTC am 11.10. ist 00:30 am 12.10. in Berlin - also "heute".
        val utcSpaetabends = millis(2026, 10, 11, 22, 30, zone = ZoneId.of("UTC"))
        assertEquals(
            "Abgeglichen um 00:30",
            abgleichZeitText(utcSpaetabends, zone = berlin, heute = LocalDate.of(2026, 10, 12))
        )
    }
}
