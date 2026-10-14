package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.MANUELLER_ALARM_SUFFIX
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.SkipLage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Die Karte "Nächster Wecker" der Uebersicht (#139, P4): reine Funktionen, feste Zone und fester
 * Bezugstag - die Karte darf nie an der Uhr des Testrechners haengen.
 */
class NaechsterWeckerKarteTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    // Di 13.10.2026, 20:10
    private val heute = LocalDate.of(2026, 10, 13)
    private val jetzt = millis(2026, 10, 13, 20, 10)

    private fun millis(j: Int, m: Int, t: Int, h: Int, min: Int): Long =
        LocalDateTime.of(j, m, t, h, min).atZone(zone).toInstant().toEpochMilli()

    private fun alarm(
        id: Int,
        trigger: Long,
        name: String = "Frühdienst",
        start: Long = 0,
        ende: Long = 0,
        still: Boolean = false,
        shiftId: String = "frueh"
    ) = AlarmInfo(
        id = id, shiftId = shiftId, shiftName = name, triggerTime = trigger, formattedTime = "x",
        shiftStartTime = start, shiftEndTime = ende, isSilent = still
    )

    private fun anzeige(
        alarme: List<AlarmInfo>,
        naechster: Long?,
        pause: Boolean = false,
        laedt: Boolean = false,
        uebersprungen: Int? = null
    ) = naechsterWeckerAnzeige(
        masterPausePaused = pause,
        activeAlarms = alarme,
        hasActiveAlarms = alarme.isNotEmpty(),
        isLoading = laedt,
        naechsterKlingelnderZeitpunkt = naechster,
        skippedAlarmId = uebersprungen,
        jetzt = jetzt,
        zone = zone,
        heute = heute
    )

    // ---- Zweige -------------------------------------------------------------------------------

    @Test
    fun `der naechste Wecker nennt Tag, Uhrzeit, Schicht und Dienstzeit`() {
        val t = millis(2026, 10, 14, 5, 30)
        val a = alarm(1, t, start = millis(2026, 10, 14, 6, 0), ende = millis(2026, 10, 14, 14, 12))

        val ergebnis = anzeige(listOf(a), naechster = t)

        assertEquals(
            NaechsterWeckerAnzeige.Naechster(
                tag = "Morgen, Mi 14.10.",
                uhrzeit = "05:30",
                schicht = "Frühdienst${TRENNER}06:00–14:12"
            ),
            ergebnis
        )
    }

    @Test
    fun `ein verstrichener naechster Wecker wird mit der Uhr von jetzt nachgewaehlt`() {
        // Heute 05:30 hat geklingelt und steht bis zum Sync im Bestand; der gespeicherte Wert aus
        // der letzten Emission nennt ihn noch. Die Karte muss morgen 05:30 zeigen.
        val heuteFrueh = millis(2026, 10, 13, 5, 30)
        val morgen = millis(2026, 10, 14, 5, 30)
        val ergebnis = anzeige(listOf(alarm(1, heuteFrueh), alarm(2, morgen)), naechster = heuteFrueh)
        assertEquals(weckerTagText(LocalDate.of(2026, 10, 14), heute), (ergebnis as NaechsterWeckerAnzeige.Naechster).tag)
    }

    @Test
    fun `die Nachwahl laesst den uebersprungenen Eintrag aus - wie das ViewModel`() {
        val heuteFrueh = millis(2026, 10, 13, 5, 30)
        val morgen = millis(2026, 10, 14, 5, 30)
        val uebermorgen = millis(2026, 10, 15, 5, 30)
        val ergebnis = anzeige(
            listOf(alarm(1, heuteFrueh), alarm(2, morgen), alarm(3, uebermorgen)),
            naechster = heuteFrueh,
            uebersprungen = 2
        )
        assertEquals(weckerTagText(LocalDate.of(2026, 10, 15), heute), (ergebnis as NaechsterWeckerAnzeige.Naechster).tag)
    }

    @Test
    fun `verstrichen und nichts mehr anstehend heisst kein Wecker`() {
        val heuteFrueh = millis(2026, 10, 13, 5, 30)
        assertEquals(
            NaechsterWeckerAnzeige.KeinWecker(stilleEintraege = 0),
            anzeige(listOf(alarm(1, heuteFrueh)), naechster = heuteFrueh)
        )
    }

    @Test
    fun `die Master-Pause hat Vorrang - auch vor einem angezeigten naechsten Wecker`() {
        val t = millis(2026, 10, 14, 5, 30)
        // Der Bestand ist waehrend der Pause veraltet - "05:30" waere eine Weckzeit, die nie kommt.
        assertEquals(NaechsterWeckerAnzeige.Pausiert, anzeige(listOf(alarm(1, t)), naechster = t, pause = true))
        assertEquals(NaechsterWeckerAnzeige.Pausiert, anzeige(emptyList(), naechster = null, pause = true, laedt = true))
    }

    @Test
    fun `stille Eintraege zaehlen nicht als naechster Wecker - und verschwinden nicht`() {
        val still = alarm(1, millis(2026, 10, 14, 5, 0), name = "AD1", still = true)
        // VM liefert dann keinen klingelnden Zeitpunkt.
        val ergebnis = anzeige(listOf(still), naechster = null)

        assertEquals(NaechsterWeckerAnzeige.KeinWecker(stilleEintraege = 1), ergebnis)
        // Ein stiller Zeitanker sieht NICHT aus wie "nichts gestellt".
        assertTrue(keinWeckerText(1).contains("stiller Eintrag"))
        assertFalse(keinWeckerText(1) == keinWeckerText(0))
    }

    @Test
    fun `liegt ein stiller Eintrag VOR dem klingelnden, nennt die Karte den klingelnden`() {
        val still = alarm(1, millis(2026, 10, 14, 5, 0), name = "AD1", still = true)
        val laut = alarm(2, millis(2026, 10, 14, 5, 30), name = "AD1$MANUELLER_ALARM_SUFFIX", shiftId = "manual_ad1_x")

        val ergebnis = anzeige(listOf(still, laut), naechster = laut.triggerTime)

        ergebnis as NaechsterWeckerAnzeige.Naechster
        assertEquals("05:30", ergebnis.uhrzeit)
        // Der manuelle Wecker: Name ohne "(Manuell)", Herkunft als eigener Teil.
        assertEquals("AD1${TRENNER}von dir gestellt", ergebnis.schicht)
    }

    @Test
    fun `ein stiller Eintrag zur selben Zeit liefert NICHT die Schicht des klingelnden`() {
        val t = millis(2026, 10, 14, 5, 30)
        val still = alarm(1, t, name = "Rufdienst", still = true)
        val laut = alarm(2, t, name = "Frühdienst")

        val ergebnis = anzeige(listOf(still, laut), naechster = t) as NaechsterWeckerAnzeige.Naechster

        assertEquals("Frühdienst", ergebnis.schicht)
    }

    @Test
    fun `fehlt der Eintrag zum Zeitpunkt, bleibt die Weckzeit stehen - nur ohne Schicht`() {
        val t = millis(2026, 10, 15, 14, 30)
        val anderer = alarm(1, millis(2026, 10, 16, 5, 30))

        val ergebnis = anzeige(listOf(anderer), naechster = t) as NaechsterWeckerAnzeige.Naechster

        assertEquals("14:30", ergebnis.uhrzeit)
        assertEquals("Do 15.10.", ergebnis.tag)
        assertNull(ergebnis.schicht)
    }

    @Test
    fun `leer und laden`() {
        assertEquals(NaechsterWeckerAnzeige.KeinWecker(0), anzeige(emptyList(), naechster = null))
        assertEquals(NaechsterWeckerAnzeige.Laedt, anzeige(emptyList(), naechster = null, laedt = true))
        assertEquals("Kein Wecker gestellt.", keinWeckerText(0))
    }

    @Test
    fun `verstrichene stille Eintraege werden nicht mitgezaehlt`() {
        val vorbei = alarm(1, millis(2026, 10, 13, 5, 0), still = true)
        val kommend = alarm(2, millis(2026, 10, 14, 5, 0), still = true)

        assertEquals(
            NaechsterWeckerAnzeige.KeinWecker(stilleEintraege = 1),
            anzeige(listOf(vorbei, kommend), naechster = null)
        )
        assertTrue(keinWeckerText(2).contains("2 stille Einträge"))
    }

    // ---- Tag und Schichtzeile ------------------------------------------------------------------

    @Test
    fun `heute, morgen, spaeter - Wochentag ohne Punkt, Datum mit Punkt`() {
        assertEquals("Heute, Di 13.10.", weckerTagText(heute, heute))
        assertEquals("Morgen, Mi 14.10.", weckerTagText(heute.plusDays(1), heute))
        assertEquals("So 18.10.", weckerTagText(LocalDate.of(2026, 10, 18), heute))
        assertEquals("Mo 19.10.", weckerTagText(LocalDate.of(2026, 10, 19), heute))
    }

    @Test
    fun `der Tag haengt an der Weckzeit, nicht am Schichtbeginn`() {
        // Nachtdienst: weckt 23:30, beginnt nach Mitternacht - derselbe Anker wie "Dienst fällt
        // aus" (Weckzeit), sonst nennt die Karte einen anderen Tag als die Wecker-Liste.
        val t = millis(2026, 10, 14, 23, 30)
        val a = alarm(1, t, name = "Nacht", start = millis(2026, 10, 15, 0, 15), ende = millis(2026, 10, 15, 7, 0))

        val ergebnis = anzeige(listOf(a), naechster = t) as NaechsterWeckerAnzeige.Naechster

        assertEquals("Morgen, Mi 14.10.", ergebnis.tag)
        assertEquals("Nacht${TRENNER}00:15–07:00", ergebnis.schicht)
    }

    @Test
    fun `unbekannte Dienstzeit wird nicht erfunden`() {
        assertEquals("Frühdienst", schichtZeile(alarm(1, jetzt), zone))
        assertEquals(
            "Frühdienst${TRENNER}ab 06:00",
            schichtZeile(alarm(1, jetzt, start = millis(2026, 10, 14, 6, 0)), zone)
        )
        assertNull(schichtZeile(alarm(1, jetzt, name = " "), zone))
    }

    @Test
    fun `innerhalb eines Teils gibt es kein normales Leerzeichen - Umbruch nur hinter dem Trenner`() {
        val zeile = schichtZeile(
            alarm(1, jetzt, name = "F", start = millis(2026, 10, 14, 6, 0), shiftId = "manual_f"),
            zone
        )!!
        // Alle normalen Leerzeichen stehen direkt hinter einem Trenner-Punkt.
        val teile = zeile.split(TRENNER)
        assertTrue(teile.none { it.contains(' ') })
    }

    // ---- Zusatz-Hinweis ------------------------------------------------------------------------

    private val skipMorgen = SkipLage(aktiv = true, alarmId = 7, weckzeitpunkt = millis(2026, 10, 14, 5, 30))

    private fun zusatz(pause: Boolean, automatik: Boolean, skip: SkipLage) =
        naechsterWeckerZusatz(masterPausePaused = pause, autoAlarmEnabled = automatik, skip = skip, jetzt = jetzt)

    @Test
    fun `hoechstens EIN Zusatz - Pause vor Automatik vor Nicht wecken`() {
        // Die Pause bringt Text und Ausweg in ihrem eigenen Zweig mit.
        assertNull(zusatz(pause = true, automatik = false, skip = skipMorgen))
        assertEquals(AUTOMATIK_AUS_ZUSATZ, zusatz(pause = false, automatik = false, skip = skipMorgen))
        assertEquals(NICHT_WECKEN_AKTIV_ZUSATZ, zusatz(pause = false, automatik = true, skip = skipMorgen))
        assertNull(zusatz(pause = false, automatik = true, skip = SkipLage.KEIN))
    }

    @Test
    fun `ein abgelaufener, noch nicht geraeumter Skip verweist nicht mehr auf Doch wecken`() {
        // Die Liste hat dafuer keine Zeile mehr - der Satz zeigte auf einen Knopf, den es nicht gibt.
        val abgelaufen = skipMorgen.copy(weckzeitpunkt = millis(2026, 10, 13, 5, 30))
        assertNull(zusatz(pause = false, automatik = true, skip = abgelaufen))
    }

    @Test
    fun `der Nicht-wecken-Zusatz nennt die Beschriftungen wortgleich`() {
        assertTrue(NICHT_WECKEN_AKTIV_ZUSATZ.contains("„$ALLE_GESTELLTEN_WECKER“"))
        assertTrue(NICHT_WECKEN_AKTIV_ZUSATZ.contains("„Nicht wecken“"))
        assertTrue(NICHT_WECKEN_AKTIV_ZUSATZ.contains("„Doch wecken“"))
        // Die alten Namen sind seit #139 weg.
        assertFalse(NICHT_WECKEN_AKTIV_ZUSATZ.contains("berspring"))
    }
}
