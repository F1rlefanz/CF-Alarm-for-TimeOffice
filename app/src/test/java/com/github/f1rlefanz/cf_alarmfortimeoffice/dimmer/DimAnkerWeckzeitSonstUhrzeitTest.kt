package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Der Ende-Anker [DimAnchor.ALARM_SONST_CLOCK]: „bis zur Weckzeit – spätestens um X".
 *
 * WARUM ES IHN GIBT (Befund 23.08.2026, am Fairphone 6 gemessen): Der Eigentuemer wachte um 08:48
 * auf und fand den Bildschirm gedimmt. Der eingebaute Nacht-Standard endet an Schicht-Tagen am
 * [DimAnchor.ALARM] — egal wie spaet der ist. Vor einem Spaetdienst mit Weckzeit 12:30 hiess
 * „Nacht-Dimmung" damit faktisch „bis mittags". Die 07:00, die er eingestellt zu haben glaubte,
 * galten nur an weckerfreien Tagen. Die Semantik, die er erwartete — MINIMUM aus Weckzeit und
 * Uhrzeit — war im Modell schlicht nicht ausdrueckbar: [DimAnchor.CLOCK] endet stur an der Uhrzeit
 * und ueberdimmt jeden frueheren Wecker, [DimAnchor.ALARM] endet stur am Wecker.
 *
 * DER ANKER ERSETZT AUSSERDEM EINE SONDERLOGIK. Der Nacht-Standard brauchte pro Tag ZWEI Fenster
 * (rueckwaerts: Nacht vor dem Wecker; vorwaerts: heutiger Abend) plus die Bedingung
 * `nextDayCoversTonight` — „lege das Vorwaerts-Fenster, AUSSER der Folgetag hat selbst einen
 * Wecker". Diese Bedingung schaut auf ein ANDERES Datum als das, fuer das gerade gerechnet wird,
 * und war die Quelle eines real reproduzierten Ausfalls (03.–05.08.2026, siehe eigener Test unten).
 * Hier entfaellt sie ersatzlos: ein CLOCK-Start-Fenster gilt fuer JEDE Kalendernacht, und die
 * Weckzeit wird in der gesamten Zeitleiste gesucht statt „im Wecker dieses Tages".
 */
class DimAnkerWeckzeitSonstUhrzeitTest {

    private val zone = ZoneId.of("Europe/Berlin")

    private fun t(datum: String, uhrzeit: String): Long =
        LocalDateTime.parse("${datum}T$uhrzeit").atZone(zone).toInstant().toEpochMilli()

    /** Das Nacht-Fenster, das den kompletten Nacht-Standard als EIN Fenster ausdrueckt. */
    private val nachtfenster = DimWindow(
        startAnchor = DimAnchor.CLOCK,
        startClockMinutes = 22 * 60,
        endAnchor = DimAnchor.ALARM_SONST_CLOCK,
        endClockMinutes = 7 * 60
    )

    private fun nacht(datum: String, weckzeiten: List<Long>): LongRange? =
        DimWindowResolver.resolveFreeWindow(
            nachtfenster,
            LocalDate.parse(datum),
            zone,
            weckzeiten.sorted()
        )

    // ---------------------------------------------------------------- der ausloesende Fall

    @Test
    fun `Spaetdienst-Weckzeit 12 30 beendet die Nacht NICHT - sie endet um 07 00`() {
        // Genau der Fall vom 23.08.2026: Nacht vom 22. auf den 23., Weckzeit 12:30.
        val spanne = nacht("2026-08-22", listOf(t("2026-08-23", "12:30")))

        assertEquals(t("2026-08-22", "22:00"), spanne!!.first)
        assertEquals(
            "Die Weckzeit liegt ausserhalb des Fensters - es endet an der Uhrzeit, nicht mittags",
            t("2026-08-23", "07:00"),
            spanne.last
        )
    }

    @Test
    fun `Fruehdienst-Weckzeit 05 30 beendet die Nacht - vor der Uhrzeit`() {
        val spanne = nacht("2026-08-21", listOf(t("2026-08-22", "05:30")))

        assertEquals(t("2026-08-21", "22:00"), spanne!!.first)
        assertEquals(t("2026-08-22", "05:30"), spanne.last)
    }

    @Test
    fun `ohne jede Weckzeit endet die Nacht an der Uhrzeit`() {
        val spanne = nacht("2026-08-22", emptyList())

        assertEquals(t("2026-08-23", "07:00"), spanne!!.last)
    }

    /**
     * Fail-safe-Richtung: Ist die Zeitleiste nicht lesbar oder leer, wird das Fenster NICHT endlos
     * dunkel, sondern endet an der Uhrzeit. „Im Zweifel hell" ist die harmlose Richtung.
     */
    @Test
    fun `leere Zeitleiste degradiert auf die Uhrzeit, nicht auf ein endloses Fenster`() {
        val spanne = DimWindowResolver.resolveFreeWindow(nachtfenster, LocalDate.parse("2026-08-22"), zone)

        assertEquals(t("2026-08-23", "07:00"), spanne!!.last)
    }

    // ---------------------------------------------------------------- Auswahl in der Zeitleiste

    @Test
    fun `von mehreren Weckzeiten im Fenster gewinnt die FRUEHESTE`() {
        val spanne = nacht(
            "2026-08-21",
            listOf(
                t("2026-08-22", "06:30"),
                t("2026-08-22", "05:30"),
                t("2026-08-22", "04:15")
            )
        )

        assertEquals(t("2026-08-22", "04:15"), spanne!!.last)
    }

    @Test
    fun `eine Weckzeit VOR dem Fensterstart wird ignoriert`() {
        // 14:30 am Vortag - der Wecker der Nachmittagsschicht, laengst vorbei.
        val spanne = nacht("2026-08-21", listOf(t("2026-08-21", "14:30")))

        assertEquals(t("2026-08-22", "07:00"), spanne!!.last)
    }

    @Test
    fun `eine Weckzeit GENAU auf dem Fensterstart schrumpft das Fenster nicht auf null`() {
        val spanne = nacht("2026-08-21", listOf(t("2026-08-21", "22:00")))

        assertEquals(
            "Eine Weckzeit auf dem Start darf keine stille Dimm-Luecke erzeugen",
            t("2026-08-22", "07:00"),
            spanne!!.last
        )
    }

    @Test
    fun `eine Weckzeit GENAU auf der Uhrzeit-Schranke aendert nichts`() {
        val spanne = nacht("2026-08-21", listOf(t("2026-08-22", "07:00")))

        assertEquals(t("2026-08-22", "07:00"), spanne!!.last)
    }

    // ---------------------------------------------------------------- die ersetzte Sonderlogik

    /**
     * Der real reproduzierte Ausfall, gegen den `nextDayCoversTonight` gebaut wurde: S2-Wecker
     * 14:30 am 3.8. → Tag ohne Termin am 4.8. → Fruehschicht 5:30 am 5.8. Die Nacht vom 3. auf den
     * 4. fiel damals komplett durch, weil der Skip fuer den Folgetag annahm, ein Wecker am
     * UEBERnaechsten Tag decke sie schon ab. Mit diesem Anker gibt es kein Fensterpaar mehr — jede
     * Kalendernacht bekommt genau ein Fenster, und jedes findet seine Weckzeit selbst.
     */
    @Test
    fun `drei aufeinanderfolgende Naechte werden lueckenlos und je richtig beendet`() {
        val weckzeiten = listOf(
            t("2026-08-03", "14:30"), // S2 am 3.8.
            t("2026-08-05", "05:30")  // Fruehschicht am 5.8.
        )

        val nachtAufDen4 = nacht("2026-08-03", weckzeiten)
        val nachtAufDen5 = nacht("2026-08-04", weckzeiten)

        assertEquals(t("2026-08-03", "22:00"), nachtAufDen4!!.first)
        assertEquals(
            "Kein Wecker in dieser Nacht - Ende an der Uhrzeit (frueher fiel sie ganz durch)",
            t("2026-08-04", "07:00"),
            nachtAufDen4.last
        )

        assertEquals(t("2026-08-04", "22:00"), nachtAufDen5!!.first)
        assertEquals(t("2026-08-05", "05:30"), nachtAufDen5.last)
    }

    // ---------------------------------------------------------------- Zeitrechnung

    /**
     * DST-Vorspringen in der Nacht 28./29.03.2026 (02:00 → 03:00). Die Schranke muss die echte
     * WANDUHRZEIT 07:00 treffen, nicht „Start + fixe Millis" — sonst laege sie eine Stunde daneben
     * und das Dimmen (und ueber Modus 1 auch DND) waere an diesem Tag verschoben.
     */
    @Test
    fun `die Uhrzeit-Schranke ist echte Wanduhrzeit, auch am DST-Vorspringen-Tag`() {
        val spanne = nacht("2026-03-28", emptyList())

        assertEquals(t("2026-03-28", "22:00"), spanne!!.first)
        assertEquals(t("2026-03-29", "07:00"), spanne.last)
        // 22:00 -> 07:00 sind an diesem Tag nur ACHT echte Stunden, nicht neun.
        assertEquals(8 * 3_600_000L, spanne.last - spanne.first)
    }

    @Test
    fun `die Uhrzeit-Schranke stimmt auch am DST-Zurueckspringen-Tag`() {
        val spanne = nacht("2026-10-24", emptyList())

        assertEquals(t("2026-10-24", "22:00"), spanne!!.first)
        assertEquals(t("2026-10-25", "07:00"), spanne.last)
        assertEquals(10 * 3_600_000L, spanne.last - spanne.first)
    }

    // ---------------------------------------------------------------- Abgrenzung

    /**
     * Der Anker ist ausdruecklich KEIN Start-Anker. Die Oberflaeche bietet ihn dort nicht an; faende
     * er sich trotzdem in Daten (Import, Handbearbeitung), verhaelt er sich am Start wie CLOCK,
     * statt das Fenster verschwinden zu lassen.
     */
    @Test
    fun `am START verhaelt sich der Anker wie eine feste Uhrzeit`() {
        val w = DimWindow(
            startAnchor = DimAnchor.ALARM_SONST_CLOCK,
            startClockMinutes = 22 * 60,
            endAnchor = DimAnchor.ALARM,
            endOffsetMinutes = 0
        )

        val spanne = DimWindowResolver.resolveShiftWindow(
            w,
            alarmEpoch = t("2026-08-22", "05:30"),
            shiftEndEpoch = 0L,
            zone = zone
        )

        assertEquals(t("2026-08-21", "22:00"), spanne!!.first)
        assertEquals(t("2026-08-22", "05:30"), spanne.last)
    }

    /** Ein Fenster, dessen Ende nicht hinter dem Start liegt, bleibt `null` - unveraendert. */
    @Test
    fun `ein Fenster ohne Dauer bleibt null`() {
        val w = DimWindow(
            startAnchor = DimAnchor.CLOCK,
            startClockMinutes = 7 * 60,
            endAnchor = DimAnchor.ALARM_SONST_CLOCK,
            endClockMinutes = 7 * 60
        )

        // Start 07:00, Schranke rollt auf den Folgetag 07:00 - 24 h, also gueltig. Erst ein Wecker
        // exakt am Start koennte es zusammenfallen lassen, und genau das ist ausgeschlossen.
        val spanne = DimWindowResolver.resolveFreeWindow(w, LocalDate.parse("2026-08-22"), zone)
        assertEquals(t("2026-08-23", "07:00"), spanne!!.last)
    }

    /** SHIFT_END-Start mit dem neuen Ende: der ND-Vormittagsschlaf, begrenzt durch den naechsten Wecker. */
    @Test
    fun `Schichtende-Start mit Weckzeit-Ende - der Nachtdienst-Vormittagsschlaf`() {
        val w = DimWindow(
            startAnchor = DimAnchor.SHIFT_END,
            startOffsetMinutes = 0,
            endAnchor = DimAnchor.ALARM_SONST_CLOCK,
            endClockMinutes = 14 * 60
        )

        // ND endet Di 06:00; am selben Tag um 13:00 klingelt der Wecker fuer den naechsten ND.
        val spanne = DimWindowResolver.resolveShiftWindow(
            w,
            alarmEpoch = t("2026-08-24", "19:00"),
            shiftEndEpoch = t("2026-08-25", "06:00"),
            zone = zone,
            weckzeiten = listOf(t("2026-08-25", "13:00"))
        )

        assertEquals(t("2026-08-25", "06:00"), spanne!!.first)
        assertEquals(
            "Der Schlaf endet am naechsten Wecker, nicht erst um 14:00",
            t("2026-08-25", "13:00"),
            spanne.last
        )
    }

    /** Gegenprobe zum vorigen: ohne Wecker laeuft derselbe Schlaf bis zur Uhrzeit. */
    @Test
    fun `Schichtende-Start ohne naechsten Wecker laeuft bis zur Uhrzeit`() {
        val w = DimWindow(
            startAnchor = DimAnchor.SHIFT_END,
            startOffsetMinutes = 0,
            endAnchor = DimAnchor.ALARM_SONST_CLOCK,
            endClockMinutes = 14 * 60
        )

        val spanne = DimWindowResolver.resolveShiftWindow(
            w,
            alarmEpoch = t("2026-08-24", "19:00"),
            shiftEndEpoch = t("2026-08-25", "06:00"),
            zone = zone,
            weckzeiten = emptyList()
        )

        assertEquals(t("2026-08-25", "14:00"), spanne!!.last)
    }

    /** Ein SHIFT_END-Anker ohne bekanntes Schichtende bleibt `null` - unveraendert. */
    @Test
    fun `unbekanntes Schichtende bleibt null, auch mit dem neuen Ende`() {
        val w = DimWindow(
            startAnchor = DimAnchor.SHIFT_END,
            startOffsetMinutes = 0,
            endAnchor = DimAnchor.ALARM_SONST_CLOCK,
            endClockMinutes = 14 * 60
        )

        assertNull(
            DimWindowResolver.resolveShiftWindow(
                w,
                alarmEpoch = t("2026-08-24", "19:00"),
                shiftEndEpoch = 0L,
                zone = zone
            )
        )
    }
}
