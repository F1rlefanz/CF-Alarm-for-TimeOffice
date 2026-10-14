package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.MANUELLER_ALARM_SUFFIX
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Die Tagesliste (#139, P3). Geprueft wird vor allem der Anker (Kalendertag der WECKZEIT), die
 * Auswahl des naechsten klingelnden Weckers (dieselbe wie das Ziel von `skipNextAlarm`) und dass
 * ein uebersprungener oder freigegebener Tag auch ohne Wecker eine Zeile bekommt.
 */
class WeckerTageTest {

    private val berlin: ZoneId = ZoneId.of("Europe/Berlin")
    private val utc: ZoneId = ZoneId.of("UTC")

    private fun ms(text: String, zone: ZoneId = berlin): Long =
        LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    private val jetzt = ms("2026-10-14T12:00")

    private fun alarm(
        id: Int,
        zeit: String,
        name: String = "Frühdienst",
        still: Boolean = false,
        manuell: Boolean = false,
        beginn: Long = 0,
        ende: Long = 0
    ) = AlarmInfo(
        id = id,
        shiftId = if (manuell) "manual_$id" else "shift_$id",
        shiftName = if (manuell) name + MANUELLER_ALARM_SUFFIX else name,
        triggerTime = ms(zeit),
        formattedTime = zeit.substringAfter('T'),
        eventId = if (manuell) "" else "event_$id",
        shiftStartTime = beginn,
        shiftEndTime = ende,
        isSilent = still
    )

    private fun tage(
        alarme: List<AlarmInfo> = emptyList(),
        freieTage: List<LocalDate> = emptyList(),
        skip: SkipLage = SkipLage.KEIN,
        jetzt: Long = this.jetzt,
        zone: ZoneId = berlin,
        wirkungen: Map<String, SchichtWirkung> = emptyMap()
    ) = baueWeckerTage(alarme, freieTage, skip, jetzt, zone, wirkungen)

    private fun WeckerTag.wecker() = eintraege.filterIsInstance<WeckerEintrag.Wecker>()

    // --- Leere Eingaben -------------------------------------------------------------------

    @Test
    fun `leere Eingaben ergeben eine leere Liste`() {
        assertTrue(tage().isEmpty())
    }

    @Test
    fun `nur vergangene Wecker ergeben eine leere Liste`() {
        assertTrue(tage(listOf(alarm(1, "2026-10-14T05:00"), alarm(2, "2026-10-14T12:00"))).isEmpty())
    }

    // --- Anker, Sortierung ------------------------------------------------------------------

    @Test
    fun `Tage sind sortiert und Wecker innerhalb des Tages nach Weckzeit`() {
        val liste = tage(
            listOf(
                alarm(3, "2026-10-16T05:00"),
                alarm(2, "2026-10-15T21:00", name = "Nachtdienst"),
                alarm(1, "2026-10-15T05:00")
            )
        )
        assertEquals(listOf(LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 16)), liste.map { it.datum })
        assertEquals(listOf(1, 2), liste[0].wecker().map { it.alarm.id })
        assertEquals(LocalTime.of(21, 0), liste[0].wecker()[1].uhrzeit)
    }

    @Test
    fun `Mitternacht gehoert zum neuen Tag, eine Millisekunde davor zum alten`() {
        val vorher = alarm(1, "2026-10-15T00:00").copy(triggerTime = ms("2026-10-15T00:00") - 1)
        val genau = alarm(2, "2026-10-15T00:00")
        val liste = tage(listOf(vorher, genau))
        assertEquals(LocalDate.of(2026, 10, 14), liste[0].datum)
        assertEquals(listOf(1), liste[0].wecker().map { it.alarm.id })
        assertEquals(LocalDate.of(2026, 10, 15), liste[1].datum)
        assertEquals(listOf(2), liste[1].wecker().map { it.alarm.id })
    }

    @Test
    fun `Anker ist die Weckzeit, nicht der Schichtbeginn`() {
        // Weckt 23:30, Dienst beginnt nach Mitternacht - die Zeile steht am Tag der WECKZEIT.
        val nacht = alarm(1, "2026-10-15T23:30", beginn = ms("2026-10-16T00:15"), ende = ms("2026-10-16T07:00"))
        val liste = tage(listOf(nacht))
        assertEquals(LocalDate.of(2026, 10, 15), liste.single().datum)
        assertEquals(TagesSchicht("Frühdienst", LocalTime.of(0, 15), LocalTime.of(7, 0)), liste.single().schichten.single())
    }

    @Test
    fun `Zeitzone bestimmt den Kalendertag`() {
        // 23:30 UTC ist in Berlin (Sommerzeit, UTC+2) schon 01:30 am Folgetag.
        val a = alarm(1, "2026-10-15T00:00").copy(triggerTime = ms("2026-10-15T23:30", utc))
        assertEquals(LocalDate.of(2026, 10, 15), tage(listOf(a), zone = utc).single().datum)
        val inBerlin = tage(listOf(a), zone = berlin).single()
        assertEquals(LocalDate.of(2026, 10, 16), inBerlin.datum)
        assertEquals(LocalTime.of(1, 30), inBerlin.wecker().single().uhrzeit)
    }

    @Test
    fun `unbekannte Dienstzeit bleibt null`() {
        val manuell = alarm(1, "2026-10-15T05:00", manuell = true)
        val schicht = tage(listOf(manuell)).single().schichten.single()
        assertEquals("Frühdienst$MANUELLER_ALARM_SUFFIX", schicht.name)
        assertNull(schicht.beginn)
        assertNull(schicht.ende)
    }

    // --- still / manuell / naechster -----------------------------------------------------------

    @Test
    fun `stiller und klingelnder Wecker am selben Tag - nur der klingelnde ist naechster`() {
        // Der Rufdienst-Morgen vom 12.10.2026: stiller Eintrag 05:00, eigener Wecker 05:30.
        val liste = tage(
            listOf(
                alarm(1, "2026-10-15T05:00", name = "Rufbereitschaft", still = true),
                alarm(2, "2026-10-15T05:30")
            )
        )
        val (stiller, klingelnder) = liste.single().wecker()
        assertTrue(stiller.still)
        assertFalse(stiller.naechster)
        assertFalse(stiller.nichtWeckenMoeglich)
        assertFalse(klingelnder.still)
        assertTrue(klingelnder.naechster)
        assertTrue(klingelnder.nichtWeckenMoeglich)
    }

    @Test
    fun `nur genau ein Eintrag der ganzen Liste bietet Nicht wecken an`() {
        val liste = tage(
            listOf(
                alarm(1, "2026-10-15T05:00"),
                alarm(2, "2026-10-16T05:00"),
                alarm(3, "2026-10-17T05:00", manuell = true)
            )
        )
        val alle = liste.flatMap { it.wecker() }
        assertEquals(listOf(1), alle.filter { it.nichtWeckenMoeglich }.map { it.alarm.id })
        assertEquals(listOf(1), alle.filter { it.naechster }.map { it.alarm.id })
    }

    @Test
    fun `manueller und Dienst-Wecker am selben Tag werden unterschieden`() {
        val liste = tage(
            listOf(
                alarm(1, "2026-10-15T04:30", manuell = true),
                alarm(2, "2026-10-15T05:00", beginn = ms("2026-10-15T06:00"))
            )
        )
        val (manuell, dienst) = liste.single().wecker()
        assertTrue(manuell.manuell)
        assertFalse(dienst.manuell)
        // Der manuelle ist frueher und klingelt - er ist der naechste, wie beim Knopf.
        assertTrue(manuell.naechster)
        // Gleicher reiner Name = eine Schicht im Tageskopf, mit der bekannten Dienstzeit.
        assertEquals(
            listOf(TagesSchicht("Frühdienst", LocalTime.of(6, 0), null)),
            liste.single().schichten
        )
    }

    @Test
    fun `nur stille Wecker - niemand ist naechster`() {
        val liste = tage(listOf(alarm(1, "2026-10-15T05:00", still = true)))
        assertFalse(liste.single().wecker().single().naechster)
    }

    // --- Skip ------------------------------------------------------------------------------

    @Test
    fun `Skip des einzigen Weckers - der Tag erscheint mit weckt diesmal nicht`() {
        // Der uebersprungene Wecker ist im Bestand geloescht; nur der Weckzeitpunkt bleibt.
        val zeit = ms("2026-10-15T05:00")
        val liste = tage(skip = SkipLage(aktiv = true, alarmId = 7, weckzeitpunkt = zeit))
        val tag = liste.single()
        assertEquals(LocalDate.of(2026, 10, 15), tag.datum)
        val eintrag = tag.eintraege.single() as WeckerEintrag.Uebersprungen
        assertEquals(zeit, eintrag.triggerTime)
        assertEquals(LocalTime.of(5, 0), eintrag.uhrzeit)
    }

    @Test
    fun `waehrend eines Skips bietet niemand Nicht wecken an, naechster bleibt markiert`() {
        val liste = tage(
            listOf(alarm(2, "2026-10-16T05:00")),
            skip = SkipLage(aktiv = true, alarmId = 1, weckzeitpunkt = ms("2026-10-15T05:00"))
        )
        val wecker = liste.flatMap { it.wecker() }.single()
        assertTrue(wecker.naechster)
        assertFalse(wecker.nichtWeckenMoeglich)
        assertTrue(liste[0].eintraege.single() is WeckerEintrag.Uebersprungen)
    }

    @Test
    fun `ein liegengebliebener uebersprungener Wecker erscheint nicht zusaetzlich als Weckt`() {
        val zeit = "2026-10-15T05:00"
        val liste = tage(
            listOf(alarm(1, zeit), alarm(2, "2026-10-16T05:00")),
            skip = SkipLage(aktiv = true, alarmId = 1, weckzeitpunkt = ms(zeit))
        )
        assertTrue(liste[0].eintraege.single() is WeckerEintrag.Uebersprungen)
        // Nicht der uebersprungene ist "naechster", sondern der danach - wie berechneWeckerAnzeige.
        assertTrue(liste[1].wecker().single().naechster)
    }

    @Test
    fun `nach Kennungswechsel wird der uebersprungene ueber den Weckzeitpunkt erkannt`() {
        val zeit = "2026-10-15T05:00"
        val liste = tage(
            listOf(alarm(99, zeit)),
            skip = SkipLage(aktiv = true, alarmId = 1, weckzeitpunkt = ms(zeit))
        )
        assertTrue(liste.single().eintraege.single() is WeckerEintrag.Uebersprungen)
    }

    @Test
    fun `abgelaufener Skip erscheint nicht`() {
        val liste = tage(skip = SkipLage(aktiv = true, alarmId = 1, weckzeitpunkt = ms("2026-10-14T05:00")))
        assertTrue(liste.isEmpty())
    }

    @Test
    fun `abgelaufener, noch nicht geraeumter Skip gibt Nicht wecken am naechsten Wecker frei`() {
        // Merker fuer heute 05:30 steht noch (kein Sync seitdem), jetzt ist 12:00: die Zeile
        // „weckt diesmal nicht“ ist weg - dann darf nicht zusaetzlich „Nicht wecken“ fehlen.
        val liste = tage(
            listOf(alarm(2, "2026-10-15T05:30"), alarm(3, "2026-10-16T05:30")),
            skip = SkipLage(aktiv = true, alarmId = 1, weckzeitpunkt = ms("2026-10-14T05:30"))
        )
        assertTrue(liste.flatMap { it.eintraege }.none { it is WeckerEintrag.Uebersprungen })
        val wecker = liste.flatMap { it.wecker() }
        assertTrue(wecker[0].naechster)
        assertTrue(wecker[0].nichtWeckenMoeglich)
        assertFalse(wecker[1].nichtWeckenMoeglich)
    }

    @Test
    fun `wirktNoch - aussteht, unbekannt und abgelaufen`() {
        assertTrue(SkipLage(true, 1, jetzt + 1).wirktNoch(jetzt))
        assertTrue(SkipLage(true, 1, null).wirktNoch(jetzt))
        assertFalse(SkipLage(true, 1, jetzt).wirktNoch(jetzt))
        assertFalse(SkipLage(true, 1, jetzt - 1).wirktNoch(jetzt))
        assertFalse(SkipLage.KEIN.wirktNoch(jetzt))
    }

    @Test
    fun `Skip ohne Weckzeitpunkt erzeugt keinen Eintrag, sperrt aber Nicht wecken`() {
        val liste = tage(
            listOf(alarm(2, "2026-10-16T05:00")),
            skip = SkipLage(aktiv = true, alarmId = 1, weckzeitpunkt = null)
        )
        assertEquals(1, liste.single().eintraege.size)
        assertFalse(liste.single().wecker().single().nichtWeckenMoeglich)
    }

    // --- Freigegebene Tage --------------------------------------------------------------------

    @Test
    fun `freier Tag ohne Wecker erscheint sortiert eingereiht`() {
        val liste = tage(
            listOf(alarm(1, "2026-10-15T05:00"), alarm(2, "2026-10-17T05:00")),
            freieTage = listOf(LocalDate.of(2026, 10, 16))
        )
        assertEquals(listOf(15, 16, 17), liste.map { it.datum.dayOfMonth })
        val frei = liste[1]
        assertTrue(frei.freigegeben)
        assertTrue(frei.eintraege.isEmpty())
        assertTrue(frei.kaertchen.isEmpty())
        assertFalse(liste[0].freigegeben)
    }

    @Test
    fun `freier Tag in der Vergangenheit erscheint nicht, heute schon`() {
        val liste = tage(freieTage = listOf(LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 14)))
        assertEquals(listOf(LocalDate.of(2026, 10, 14)), liste.map { it.datum })
    }

    @Test
    fun `freier Tag behaelt den manuellen Wecker, ein Dienstwecker entfaellt`() {
        val wirkungen = mapOf("Frühdienst" to wirkung(isOnCall = true, dndAn = true))
        val liste = tage(
            listOf(alarm(1, "2026-10-15T04:30", manuell = true), alarm(2, "2026-10-15T05:00")),
            freieTage = listOf(LocalDate.of(2026, 10, 15)),
            wirkungen = wirkungen
        )
        val (manuell, dienst) = liste.single().wecker()
        assertFalse(manuell.entfaelltDurchFreigabe)
        assertTrue(dienst.entfaelltDurchFreigabe)
        // Freigegeben: die Spannen sind aus Dimmer/DND/Rufdienst-Abfrage genommen - keine Kaertchen.
        assertTrue(liste.single().kaertchen.isEmpty())
    }

    // --- Kaertchen ----------------------------------------------------------------------------

    private fun wirkung(
        name: String = "Frühdienst",
        isOnCall: Boolean = false,
        dndAn: Boolean = false,
        dimmen: DimmStatus = DimmStatus.Keine,
        licht: LichtStatus = LichtStatus.Regeln(0, 0)
    ) = SchichtWirkung(
        definition = ShiftDefinition(
            id = name,
            name = name,
            keywords = emptyList(),
            alarmTime = LocalTime.of(5, 0),
            isOnCall = isOnCall
        ),
        folgen = SchichtFolgen(
            dimmen = dimmen,
            licht = licht,
            dnd = DndStatus(
                dienstzeit = if (dndAn) DienstzeitDnd.AN else DienstzeitDnd.AUS,
                nachDimmer = false,
                rufbereitschaft = false
            )
        )
    )

    @Test
    fun `alle Wirkungen ergeben alle Kaertchen in fester Reihenfolge`() {
        val w = wirkung(
            isOnCall = true,
            dndAn = true,
            dimmen = DimmStatus.Wirksam(DimmArt.EIGENE, 0),
            licht = LichtStatus.Regeln(eigene = 1, allgemeine = 0)
        )
        assertEquals(Kaertchen.entries.toList(), kaertchenFuerSchichten(listOf("Frühdienst"), mapOf("Frühdienst" to w)))
    }

    @Test
    fun `keine Wirkung, kein Kaertchen`() {
        val stumm = listOf(
            wirkung(dimmen = DimmStatus.Aus, licht = LichtStatus.NichtLesbar),
            wirkung(dimmen = DimmStatus.Wirksam(DimmArt.UNTERDRUECKT, 0), licht = LichtStatus.KeineBridge),
            wirkung(dimmen = DimmStatus.Keine, licht = LichtStatus.StilleSchicht)
        )
        stumm.forEach { assertTrue(kaertchenFuer(it).isEmpty()) }
    }

    @Test
    fun `allgemeine Dimm- und Hue-Regeln zaehlen als wirksam`() {
        val w = wirkung(
            dimmen = DimmStatus.Wirksam(DimmArt.ALLGEMEINE, 0),
            licht = LichtStatus.Regeln(eigene = 0, allgemeine = 2)
        )
        assertEquals(setOf(Kaertchen.DIMMEN, Kaertchen.LICHT), kaertchenFuer(w))
    }

    @Test
    fun `Nicht stoeren nach Dimmer allein zaehlt, Dienstzeit ausgenommen allein nicht`() {
        val basis = wirkung()
        val nachDimmer = basis.copy(folgen = basis.folgen.copy(dnd = DndStatus(DienstzeitDnd.AUS, true, false)))
        val ausgenommen = basis.copy(folgen = basis.folgen.copy(dnd = DndStatus(DienstzeitDnd.AUSGENOMMEN, false, false)))
        assertEquals(setOf(Kaertchen.NICHT_STOEREN), kaertchenFuer(nachDimmer))
        // `DndStatus.aus` ist hier false, fuer diese Schicht wirkt aber keine Quelle.
        assertTrue(kaertchenFuer(ausgenommen).isEmpty())
        val ausgenommenMitDimmer = basis.copy(folgen = basis.folgen.copy(dnd = DndStatus(DienstzeitDnd.AUSGENOMMEN, true, false)))
        assertEquals(setOf(Kaertchen.NICHT_STOEREN), kaertchenFuer(ausgenommenMitDimmer))
    }

    @Test
    fun `Rufbereitschaft kommt aus isOnCall, auch wenn beide DND-Quellen aus sind`() {
        assertEquals(setOf(Kaertchen.RUFBEREITSCHAFT), kaertchenFuer(wirkung(isOnCall = true)))
    }

    @Test
    fun `unbekannte Schicht bekommt kein Kaertchen, manueller Wecker findet seine Schicht ueber den reinen Namen`() {
        val wirkungen = mapOf("Frühdienst" to wirkung(dndAn = true))
        val unbekannt = tage(listOf(alarm(1, "2026-10-15T05:00", name = "Fortbildung")), wirkungen = wirkungen)
        assertTrue(unbekannt.single().kaertchen.isEmpty())
        val manuell = tage(listOf(alarm(1, "2026-10-15T05:00", manuell = true)), wirkungen = wirkungen)
        assertEquals(listOf(Kaertchen.NICHT_STOEREN), manuell.single().kaertchen)
    }

    @Test
    fun `zwei Schichten am Tag vereinigen ihre Kaertchen`() {
        val wirkungen = mapOf(
            "Frühdienst" to wirkung(dndAn = true),
            "Rufbereitschaft" to wirkung(name = "Rufbereitschaft", isOnCall = true)
        )
        val liste = tage(
            listOf(
                alarm(1, "2026-10-15T05:00", name = "Rufbereitschaft", still = true),
                alarm(2, "2026-10-15T05:30")
            ),
            wirkungen = wirkungen
        )
        assertEquals(listOf(Kaertchen.NICHT_STOEREN, Kaertchen.RUFBEREITSCHAFT), liste.single().kaertchen)
    }
}
