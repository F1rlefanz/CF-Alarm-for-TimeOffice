package com.github.f1rlefanz.cf_alarmfortimeoffice.freietage

import com.github.f1rlefanz.cf_alarmfortimeoffice.Kerntest
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpan
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.experimental.categories.Category

/**
 * Reine Logik des Freigabe-Speichers: Aufraeumgrenze, Datums-Anker und der Spannen-Filter.
 *
 * #142: dazu die Mischsemantik des Kalender-Schluessels (rein) und - ausnahmsweise am echten Store
 * ueber einem DataStore im Arbeitsspeicher - das Zusammenspiel der beiden Quellen. Die Vereinigung
 * IST die Wirkung auf jeden Leser; ein Mock haette genau sie weggestubbt.
 */
class FreieTageStoreTest {

    private val berlin: ZoneId = ZoneId.of("Europe/Berlin")
    private val heute = LocalDate.of(2026, 8, 24)

    private fun spanneAm(datum: LocalDate, weckStunde: Int = 5, name: String = "Fruehschicht"): ShiftSpan {
        val weckzeit = datum.atTime(weckStunde, 30).atZone(berlin).toInstant().toEpochMilli()
        return ShiftSpan(
            shiftName = name,
            startTime = weckzeit + 30 * 60_000L,
            endTime = weckzeit + 8 * 60 * 60_000L,
            alarmTriggerTime = weckzeit
        )
    }

    // --- Aufraeumen ---

    @Test
    fun `Der VORTAG bleibt erhalten - eine ueber Mitternacht laufende Nacht darf nicht zurueckkippen`() {
        // LOOKBACK_DAYS = 1: die Fenster-Schleifen von Dimmer und DND rechnen den Vortag mit.
        // Wuerde die Freigabe um 00:00 verschwinden, ginge mitten in der Nacht "Nicht stoeren"
        // wieder an und der Bildschirm wuerde gedimmt - im Schlaf, ohne jedes Zutun.
        val behalten = FreieTageStore.prune(setOf(heute.minusDays(1).toString()), heute)
        assertEquals(setOf(heute.minusDays(1).toString()), behalten)
    }

    @Test
    fun `Aeltere Freigaben fliegen raus`() {
        val ergebnis = FreieTageStore.prune(
            setOf(
                heute.minusDays(2).toString(),
                heute.minusDays(1).toString(),
                heute.toString(),
                heute.plusDays(3).toString()
            ),
            heute
        )
        assertEquals(
            setOf(heute.minusDays(1).toString(), heute.toString(), heute.plusDays(3).toString()),
            ergebnis
        )
    }

    @Category(Kerntest::class)
    @Test
    fun `Ein unlesbarer Eintrag gilt NICHT als freigegebener Tag`() {
        // Ein Wert, den niemand deuten kann, darf nicht dazu fuehren, dass irgendetwas
        // unterdrueckt wird - die Richtung ist immer "im Zweifel wecken".
        assertEquals(emptySet<String>(), FreieTageStore.prune(setOf("kein-datum", ""), heute))
        assertNull(FreieTageStore.parseOderNull("2026-13-45"))
    }

    // --- Tages-Anker ---

    @Test
    fun `Der Tag einer Spanne kommt aus der WECKZEIT, nicht aus dem Schichtbeginn`() {
        // Spaetdienst: Wecker am 24.08. um 23,30 Uhr, Dienstbeginn erst am 25.08. um 00,00 Uhr.
        // Der Anker MUSS der 24. sein - dieselbe Ableitung wie DimWindowResolver.slotsByDate und
        // AlarmUseCase.istTagFreigegeben. Drei Stellen, ein Anker.
        val weckzeit = heute.atTime(23, 30).atZone(berlin).toInstant().toEpochMilli()
        val spanne = ShiftSpan(
            shiftName = "Nachtdienst",
            startTime = heute.plusDays(1).atStartOfDay(berlin).toInstant().toEpochMilli(),
            endTime = heute.plusDays(1).atTime(8, 0).atZone(berlin).toInstant().toEpochMilli(),
            alarmTriggerTime = weckzeit
        )
        assertEquals(heute, FreieTageStore.tagVon(spanne, berlin))
    }

    // --- Spannen-Filter ---

    @Test
    fun `Ein freigegebener Tag verliert seine Schichtspanne`() {
        val spannen = listOf(spanneAm(heute), spanneAm(heute.plusDays(1)))
        val uebrig = FreieTageStore.filtereSpannen(spannen, setOf(heute), berlin)
        assertEquals(1, uebrig.size)
        assertEquals(heute.plusDays(1), FreieTageStore.tagVon(uebrig.first(), berlin))
    }

    @Test
    fun `BEIDE Schichten eines freigegebenen Tages fallen weg`() {
        // Ein Kalendertag kann zwei Schichten haben; "Tag freigeben" meint den TAG, nicht eine
        // einzelne Schicht.
        val spannen = listOf(
            spanneAm(heute, weckStunde = 5, name = "Fruehschicht"),
            spanneAm(heute, weckStunde = 13, name = "Spaetschicht")
        )
        assertTrue(FreieTageStore.filtereSpannen(spannen, setOf(heute), berlin).isEmpty())
    }

    @Test
    fun `Ohne Freigaben bleibt die Liste unveraendert`() {
        val spannen = listOf(spanneAm(heute), spanneAm(heute.plusDays(1)))
        assertEquals(spannen, FreieTageStore.filtereSpannen(spannen, emptySet(), berlin))
    }

    @Test
    fun `Die Zeitzone entscheidet ueber den Tag`() {
        // Wecker um 00,30 Uhr Berliner Zeit = 22,30 Uhr des Vortages in UTC. Wer die Zone
        // ignoriert, gibt den falschen Tag frei.
        val weckzeit = heute.atTime(0, 30).atZone(berlin).toInstant().toEpochMilli()
        val spanne = ShiftSpan("Nachtdienst", weckzeit, weckzeit + 8 * 60 * 60_000L, weckzeit)
        assertEquals(heute, FreieTageStore.tagVon(spanne, berlin))
        assertEquals(heute.minusDays(1), FreieTageStore.tagVon(spanne, ZoneId.of("UTC")))
        assertFalse(FreieTageStore.filtereSpannen(listOf(spanne), setOf(heute), ZoneId.of("UTC")).isEmpty())
    }

    // --- #142: Mischsemantik des Kalender-Schluessels ---

    private fun iso(vararg tage: LocalDate): Set<String> = tage.map { it.toString() }.toSet()

    @Test
    fun `Kalender-Tage vor heute bleiben, ab heute ersetzt der neue Stand`() {
        // Gestern bleibt (Rueckschau fuer die Nacht ueber Mitternacht), morgen war krank und ist
        // es laut neuem Stand nicht mehr, uebermorgen ist neu dazugekommen.
        val gemischt = FreieTageStore.mischeKalenderKrank(
            alt = iso(heute.minusDays(1), heute.plusDays(1)),
            neu = setOf(heute.plusDays(2)),
            heute = heute,
            horizontTag = heute.plusDays(14)
        )
        assertEquals(iso(heute.minusDays(1), heute.plusDays(2)), gemischt)
    }

    @Category(Kerntest::class)
    @Test
    fun `HEUTE wird ersetzt - verschwindet der Termin, ist heute wieder Diensttag`() {
        // Review M2: ganztaegige Termine sind bis 23,59 Uhr in jeder Lesung sichtbar. Fehlt der
        // Termin, ist er wirklich weg - und im Zweifel wird geklingelt.
        val gemischt = FreieTageStore.mischeKalenderKrank(
            alt = iso(heute),
            neu = emptySet(),
            heute = heute,
            horizontTag = heute.plusDays(14)
        )
        assertEquals(emptySet<String>(), gemischt)
    }

    @Test
    fun `Der Horizont-Tag und alles dahinter bleiben - nicht gelesen ist nicht geloescht`() {
        // #51 / Review S2: der Tag, der das Horizontende enthaelt, zaehlt als NICHT gelesen.
        val horizont = heute.plusDays(7)
        val gemischt = FreieTageStore.mischeKalenderKrank(
            alt = iso(heute.plusDays(6), horizont, horizont.plusDays(3)),
            neu = emptySet(),
            heute = heute,
            horizontTag = horizont
        )
        assertEquals(iso(horizont, horizont.plusDays(3)), gemischt)
    }

    @Test
    fun `Ohne Horizont raeumt eine leere Liste alles ab heute`() {
        // Leere Eventliste oder Kalender-Abwahl: es gibt keinen gelesenen Bereich, also auch
        // keinen Grund, eine Krankmeldung in der Zukunft stehen zu lassen.
        val gemischt = FreieTageStore.mischeKalenderKrank(
            alt = iso(heute.minusDays(1), heute, heute.plusDays(30), heute.plusDays(90)),
            neu = emptySet(),
            heute = heute,
            horizontTag = null
        )
        assertEquals(iso(heute.minusDays(1)), gemischt)
    }

    @Test
    fun `Unlesbare und abgelaufene Kalender-Eintraege fliegen beim Mischen raus`() {
        val gemischt = FreieTageStore.mischeKalenderKrank(
            alt = setOf("kein-datum", heute.minusDays(5).toString()),
            neu = setOf(heute),
            heute = heute,
            horizontTag = heute.plusDays(14)
        )
        assertEquals(iso(heute), gemischt)
    }

    @Test
    fun `Die Aenderung nennt hinzugekommene und weggefallene Tage`() {
        val aenderung = FreieTageStore.aenderungZwischen(
            alt = iso(heute, heute.plusDays(1)),
            neu = iso(heute.plusDays(1), heute.plusDays(2))
        )
        assertEquals(setOf(heute.plusDays(2)), aenderung.hinzu)
        assertEquals(setOf(heute), aenderung.weg)
        assertTrue(aenderung.geaendert)
        assertFalse(FreieTageStore.aenderungZwischen(iso(heute), iso(heute)).geaendert)
    }

    // --- #142: die beiden Quellen am echten Store (DataStore im Arbeitsspeicher) ---
    // Der Store filtert beim Lesen gegen die ECHTE Uhr - deshalb hier `LocalDate.now()`.

    private val jetzt: LocalDate = LocalDate.now()

    @Test
    fun `freieTage liefert die Vereinigung beider Schluessel`() = runTest {
        val store = freieTageStoreImSpeicher(
            hand = setOf(jetzt.plusDays(1)),
            kalender = setOf(jetzt.plusDays(3))
        )
        assertEquals(setOf(jetzt.plusDays(1), jetzt.plusDays(3)), store.freieTageNow())
        assertEquals(setOf(jetzt.plusDays(1), jetzt.plusDays(3)), store.freieTage.first())
        assertEquals(
            FreigabeQuellen(hand = setOf(jetzt.plusDays(1)), kalender = setOf(jetzt.plusDays(3))),
            store.freigabeQuellenNow()
        )
    }

    @Test
    fun `Ein Tag in beiden Mengen bleibt frei, solange eine Quelle ihn traegt`() = runTest {
        val tag = jetzt.plusDays(2)
        val store = freieTageStoreImSpeicher(hand = setOf(tag), kalender = setOf(tag))

        // Der Termin verschwindet - die eigene Freigabe traegt den Tag weiter.
        store.setzeKalenderKrankTage(emptySet(), jetzt, jetzt.plusDays(14))
        assertEquals(setOf(tag), store.freieTageNow())

        // Kalender meldet wieder krank, der Nutzer nimmt seine Freigabe zurueck - weiter frei.
        store.setzeKalenderKrankTage(setOf(tag), jetzt, jetzt.plusDays(14))
        store.zuruecknehmen(tag)
        assertEquals(setOf(tag), store.freieTageNow())
        assertEquals(FreigabeQuellen(emptySet(), setOf(tag)), store.freigabeQuellenNow())
    }

    @Test
    fun `setzeKalenderKrankTage meldet die Aenderung und schreibt die Mischung`() = runTest {
        val store = freieTageStoreImSpeicher(kalender = setOf(jetzt.plusDays(1)))

        val aenderung = store.setzeKalenderKrankTage(setOf(jetzt.plusDays(2)), jetzt, jetzt.plusDays(14))

        assertEquals(KrankTageAenderung(hinzu = setOf(jetzt.plusDays(2)), weg = setOf(jetzt.plusDays(1))), aenderung)
        assertEquals(setOf(jetzt.plusDays(2)), store.freigabeQuellenNow().kalender)
        assertEquals(
            "Derselbe Stand noch einmal ist keine Aenderung",
            KrankTageAenderung.KEINE,
            store.setzeKalenderKrankTage(setOf(jetzt.plusDays(2)), jetzt, jetzt.plusDays(14))
        )
    }

    @Test
    fun `zuruecknehmenAlle fasst nur die Hand-Freigaben an`() = runTest {
        val a = jetzt.plusDays(1)
        val b = jetzt.plusDays(2)
        val c = jetzt.plusDays(3)
        val store = freieTageStoreImSpeicher(hand = setOf(a, b, c), kalender = setOf(b))

        store.zuruecknehmenAlle(setOf(a, b))

        assertEquals(FreigabeQuellen(hand = setOf(c), kalender = setOf(b)), store.freigabeQuellenNow())
    }

    @Test
    fun `aufraeumen prunt beide Schluessel`() = runTest {
        // Review S5: Aufraeumen mit einem SPAETEREN Stichtag - "heute" ist fuer den Lese-Filter noch
        // gueltig, verschwindet er trotzdem, hat aufraeumen() ihn aus BEIDEN Schluesseln entfernt.
        val store = freieTageStoreImSpeicher(
            hand = setOf(jetzt, jetzt.plusDays(5)),
            kalender = setOf(jetzt, jetzt.plusDays(5))
        )

        store.aufraeumen(jetzt.plusDays(5))

        assertEquals(
            FreigabeQuellen(hand = setOf(jetzt.plusDays(5)), kalender = setOf(jetzt.plusDays(5))),
            store.freigabeQuellenNow()
        )
    }

    // --- #142: zeitgebundene Krank-Termine - abgelaufen ist nicht geloescht ---

    @Test
    fun `HEUTE bleibt, wenn sein zeitgebundener Krank-Termin schon vorbei ist`() {
        // Der Abruf beginnt bei jetzt: ein heute beendeter Termin fehlt in jeder spaeteren Lesung.
        val gemischt = FreieTageStore.mischeKalenderKrank(
            alt = iso(heute),
            neu = emptySet(),
            heute = heute,
            horizontTag = heute.plusDays(14),
            heuteAbgelaufen = true
        )
        assertEquals(iso(heute), gemischt)
    }

    @Test
    fun `Ein gemerktes Ende zaehlt nur, wenn es heute und schon vorbei ist`() {
        val ende = heute.atTime(16, 0).toString()
        assertTrue(FreieTageStore.heuteAbgelaufen(setOf(ende), heute.atTime(17, 0)))
        assertFalse("Noch nicht vorbei - fehlt der Termin, ist er geloescht", FreieTageStore.heuteAbgelaufen(setOf(ende), heute.atTime(10, 0)))
        assertFalse("Ein Ende von gestern sagt ueber heute nichts", FreieTageStore.heuteAbgelaufen(setOf(ende), heute.plusDays(1).atTime(17, 0)))
        assertFalse(FreieTageStore.heuteAbgelaufen(setOf("kein-datum"), heute.atTime(17, 0)))
    }

    @Test
    fun `Zeitgebundener Krank-Termin heute 8 bis 16 Uhr - der Sync um 17 Uhr laesst heute frei`() = runTest {
        val store = freieTageStoreImSpeicher()
        val ende = jetzt.atTime(16, 0)

        store.setzeKalenderKrankTage(setOf(jetzt), jetzt, jetzt.plusDays(14), setOf(ende), jetzt.atTime(7, 0))
        // 17 Uhr: der Termin ist vorbei und fehlt im Abruf (timeMin = jetzt).
        val aenderung = store.setzeKalenderKrankTage(emptySet(), jetzt, jetzt.plusDays(14), emptySet(), jetzt.atTime(17, 0))

        assertEquals(KrankTageAenderung.KEINE, aenderung)
        assertEquals(setOf(jetzt), store.freigabeQuellenNow().kalender)
        // Und auch der Lauf danach weiss es noch.
        store.setzeKalenderKrankTage(emptySet(), jetzt, jetzt.plusDays(14), emptySet(), jetzt.atTime(20, 0))
        assertEquals(setOf(jetzt), store.freigabeQuellenNow().kalender)
    }

    @Test
    fun `Zeitgebundener Krank-Termin, der VOR seinem Ende verschwindet, gibt heute wieder zurueck`() = runTest {
        val store = freieTageStoreImSpeicher()

        store.setzeKalenderKrankTage(setOf(jetzt), jetzt, jetzt.plusDays(14), setOf(jetzt.atTime(16, 0)), jetzt.atTime(7, 0))
        store.setzeKalenderKrankTage(emptySet(), jetzt, jetzt.plusDays(14), emptySet(), jetzt.atTime(10, 0))

        assertTrue("Geloescht ist geloescht - im Zweifel klingeln", store.freigabeQuellenNow().kalender.isEmpty())
    }

    @Test
    fun `Ganztaegiger Krank-Termin heute, der verschwindet, gibt heute wieder zurueck`() = runTest {
        // Review M2 bleibt: ganztaegig ist er bis 23:59 in jeder Lesung sichtbar.
        val store = freieTageStoreImSpeicher(kalender = setOf(jetzt))

        store.setzeKalenderKrankTage(emptySet(), jetzt, jetzt.plusDays(14), emptySet(), jetzt.atTime(LocalTime.of(17, 0)))

        assertTrue(store.freigabeQuellenNow().kalender.isEmpty())
    }

    // --- #142: vorgemerkte Krankmeldungen ---

    @Test
    fun `Vorgemerkte Krankmeldung bleibt bis zur Quittung und faellt mit dem Krank-Tag weg`() = runTest {
        val a = jetzt.plusDays(1)
        val b = jetzt.plusDays(2)
        val store = freieTageStoreImSpeicher(kalender = setOf(a, b))

        store.vormerkenKrankMeldung(setOf(a, b))
        assertEquals(setOf(a, b), store.offeneKrankMeldungNow(jetzt))

        // b ist wieder Diensttag - fuer ihn gibt es nichts mehr zu melden.
        store.setzeKalenderKrankTage(setOf(a), jetzt, jetzt.plusDays(14))
        assertEquals(setOf(a), store.offeneKrankMeldungNow(jetzt))

        store.quittiereKrankMeldung(setOf(a))
        assertTrue(store.offeneKrankMeldungNow(jetzt).isEmpty())
    }

    // --- #142: gemerkte Weckzeit-Anker ---

    @Test
    fun `Ein gemerkter Anker haelt seinen Tag, solange die Quelle Krank-Tag bleibt`() = runTest {
        val montag = jetzt.plusDays(1)
        val dienstag = jetzt.plusDays(2)
        val store = freieTageStoreImSpeicher()

        store.setzeKalenderKrankTage(setOf(montag, dienstag), jetzt, jetzt.plusDays(14), anker = setOf(KrankAnker(montag, dienstag)))
        // Der Wecker, aus dem der Anker stammte, ist geloescht - der Lauf kennt nur noch den Dienstag.
        val aenderung = store.setzeKalenderKrankTage(setOf(dienstag), jetzt, jetzt.plusDays(14))

        assertEquals(KrankTageAenderung.KEINE, aenderung)
        assertEquals(setOf(montag, dienstag), store.freigabeQuellenNow().kalender)

        // Der Krank-Termin ist weg: der Anker faellt mit, nichts bleibt haengen.
        store.setzeKalenderKrankTage(emptySet(), jetzt, jetzt.plusDays(14))
        assertTrue(store.freigabeQuellenNow().kalender.isEmpty())
        store.setzeKalenderKrankTage(setOf(dienstag), jetzt, jetzt.plusDays(14))
        assertEquals("Der Anker ist vergessen", setOf(dienstag), store.freigabeQuellenNow().kalender)
    }

    @Test
    fun `Anker mit Quelle hinter dem Horizont bleibt - nicht gelesen ist nicht geloescht`() {
        val horizont = heute.plusDays(5)
        val anker = KrankAnker(horizont.minusDays(1), horizont)
        val alt = setOf(FreieTageStore.ankerAlsText(anker))
        // Die Quelle steht unangetastet im gemischten Schluessel (ab dem Horizont-Tag bleibt der alte Stand).
        assertEquals(setOf(anker), FreieTageStore.mischeAnker(alt, emptySet(), setOf(horizont.toString()), heute))
        assertTrue(FreieTageStore.mischeAnker(alt, emptySet(), emptySet(), heute).isEmpty())
    }

    @Test
    fun `pruneAnker verwirft Verstrichenes und Unlesbares`() {
        val gestern = FreieTageStore.ankerAlsText(KrankAnker(heute.minusDays(1), heute))
        val vorgestern = FreieTageStore.ankerAlsText(KrankAnker(heute.minusDays(2), heute.minusDays(1)))
        assertEquals(setOf(gestern), FreieTageStore.pruneAnker(setOf(gestern, vorgestern, "kaputt", "2026-08-24>x"), heute))
    }
}
