package com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste

import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.Blockposition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.RufbereitschaftAbfrage
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Die Tagesleiste (Umbau #193): Eintrag, Wecker und Ruf-Abfrage rechnen, Dimmen/"Nicht stoeren"/Licht
 * kommen als Bereiche herein. Alle Minuten sind Minuten ab 18:00 am Vortag (0..2880); die Erwartungen
 * stehen als LITERALE da, die Helfer unten sind nur Lesehilfe (Mustertag 00:00 = 360).
 */
class TagesleisteTest {

    private val berlin = ZoneId.of("Europe/Berlin")

    private fun vortag(stunde: Int, minute: Int = 0) = (stunde - 18) * 60 + minute
    private fun tag(stunde: Int, minute: Int = 0) = 360 + stunde * 60 + minute
    private fun folgetag(stunde: Int, minute: Int = 0) = 1800 + stunde * 60 + minute

    private val frueh = Dienstzeit(LocalTime.of(6, 0), 495, ganztaegig = false)
    private val nacht = Dienstzeit(LocalTime.of(20, 45), 615, ganztaegig = false)
    private val ruf = Dienstzeit(LocalTime.of(7, 0), 1440, ganztaegig = false)

    private fun eingabe(
        kategorie: ShiftKategorie = ShiftKategorie.DIENST,
        name: String = "F",
        weckerAn: Boolean = true,
        weckzeit: LocalTime? = LocalTime.of(4, 30),
        still: Boolean = false,
        dienstzeit: Dienstzeit? = frueh,
        blockposition: Blockposition = standardBlockposition(kategorie),
        dimmen: List<DimBereich> = emptyList(),
        nichtStoeren: List<DndBereich> = emptyList(),
        licht: List<LichtBereich> = emptyList(),
        rufCutoffMinuten: Int = 300
    ) = TagesleisteEingabe(
        name, kategorie, weckerAn, weckzeit, still, dienstzeit, blockposition,
        dimmen, nichtStoeren, licht, rufCutoffMinuten
    )

    private fun Tagesleiste.balkenVon(spur: Spur) = balken.filter { it.spur == spur }.map { it.vonMin to it.bisMin }

    private fun Tagesleiste.ereignisseVon(spur: Spur) = ereignisse.filter { it.spur == spur }.map { it.zeit to it.text }

    // ── Eintrag und Fenster ──────────────────────────────────────────────────────────────────

    @Test
    fun `Nachtdienst ueber Mitternacht liegt richtig im Fenster`() {
        val leiste = tagesleiste(eingabe(name = "N", dienstzeit = nacht, weckzeit = LocalTime.of(17, 30)))

        // 20:45 = 360 + 1245; Ende 07:00 am Folgetag = 1800 + 420.
        assertEquals(listOf(Balken(Spur.EINTRAG, 1605, 2220)), leiste.balken)
        assertEquals(listOf(Weckpunkt(1410, still = false)), leiste.weckpunkte)
        assertEquals(
            listOf("17:30" to "Wecker – N", "20:45" to "N beginnt", "Folgetag 07:00" to "N endet"),
            leiste.ereignisse.map { it.zeit to it.text }
        )
        assertNull(leiste.hinweis)
    }

    @Test
    fun `ein Wecker vor Mitternacht fuer einen Dienst kurz nach Mitternacht steht am Vortag`() {
        // Weckzeit 23:30, Beginn 00:30: weckzeitpunkt() (#192) rechnet den Wecker auf den Vortag.
        val leiste = tagesleiste(
            eingabe(name = "N0", dienstzeit = Dienstzeit(LocalTime.of(0, 30), 450, false), weckzeit = LocalTime.of(23, 30))
        )

        assertEquals(listOf(Weckpunkt(330, still = false)), leiste.weckpunkte)
        assertEquals("Vortag 23:30" to "Wecker – N0", leiste.ereignisse.first().let { it.zeit to it.text })
        assertEquals(listOf(Balken(Spur.EINTRAG, 390, 840)), leiste.balken)
    }

    @Test
    fun `ganztaegig liegt ebenso im Fenster - der Wecker weckt am Tag selbst`() {
        val urlaub = tagesleiste(
            eingabe(kategorie = ShiftKategorie.URLAUB, name = "U", dienstzeit = null, weckzeit = LocalTime.of(13, 0))
        )

        assertEquals(listOf(Balken(Spur.EINTRAG, 360, 1800)), urlaub.balken)
        assertEquals(listOf(Weckpunkt(1140, still = false)), urlaub.weckpunkte)
        assertEquals(
            listOf("00:00" to "U (ganztägig)", "13:00" to "Wecker – U"),
            urlaub.ereignisse.map { it.zeit to it.text }
        )
        assertNull(urlaub.hinweis)

        // Ein Google-Ganztagstermin eines Dienst-Typs: dieselbe Gestalt, derselbe Wecker.
        val ganztags = tagesleiste(eingabe(dienstzeit = Dienstzeit(LocalTime.MIDNIGHT, 1440, ganztaegig = true), weckzeit = LocalTime.of(13, 0)))
        assertEquals(listOf(Weckpunkt(1140, still = false)), ganztags.weckpunkte)
        assertEquals(listOf(Balken(Spur.EINTRAG, 360, 1800)), ganztags.balken)
    }

    @Test
    fun `ein zeitgebundener Termin 00-00 bis 00-00 weckt wie die Erkennung - kein Ganztagstermin`() {
        // Weckzeit 13:00 nach dem Beginn 00:00, Vorlauf 11 h: weckzeitpunkt() legt ihn auf den Vortag
        // 13:00 (Minute -300) - ausserhalb des Fensters. Die Leiste behauptet keinen Wecker am Tag.
        val leiste = tagesleiste(
            eingabe(dienstzeit = Dienstzeit(LocalTime.MIDNIGHT, 1440, ganztaegig = false), weckzeit = LocalTime.of(13, 0))
        )

        assertEquals(listOf(Balken(Spur.EINTRAG, 360, 1800)), leiste.balken)
        assertTrue(leiste.weckpunkte.isEmpty())
    }

    @Test
    fun `ohne Spanne gibt es keine Eintrag-Spur, aber den Hinweis`() {
        val dienst = tagesleiste(eingabe(dienstzeit = null))

        assertTrue(dienst.balken.none { it.spur == Spur.EINTRAG })
        assertTrue(dienst.balken.isEmpty())
        assertEquals(Hinweis.KEIN_TERMIN_IM_KALENDER, dienst.hinweis)
        // Der Wecker ist trotzdem zu sehen: wer die Weckzeit aendert, soll sie wandern sehen.
        assertEquals(listOf(Weckpunkt(630, still = false)), dienst.weckpunkte)

        val rufOhne = tagesleiste(eingabe(kategorie = ShiftKategorie.RUFBEREITSCHAFT, name = "AD", dienstzeit = null))
        assertEquals(Hinweis.KEIN_TERMIN_IM_KALENDER, rufOhne.hinweis)
        assertTrue(rufOhne.balken.isEmpty())
        assertTrue(rufOhne.rufAbfragen.isEmpty())

        // Frei, Urlaub, Fortbildung und Krank brauchen keine Spanne: ganztaegig, ohne Hinweis.
        listOf(ShiftKategorie.FREI, ShiftKategorie.URLAUB, ShiftKategorie.FORTBILDUNG, ShiftKategorie.KRANK).forEach {
            val l = tagesleiste(eingabe(kategorie = it, dienstzeit = null))
            assertNull("$it", l.hinweis)
            assertEquals("$it", listOf(Balken(Spur.EINTRAG, 360, 1800)), l.balken)
        }
    }

    @Test
    fun `Ignorieren hat keine Leiste, auch mit Bereichen`() {
        val leiste = tagesleiste(
            eingabe(
                kategorie = ShiftKategorie.IGNORIEREN, dienstzeit = null,
                dimmen = listOf(DimBereich(tag(22), tag(23), 50)),
                nichtStoeren = listOf(DndBereich(tag(22), tag(23), DndQuelle.DIMM_FENSTER))
            )
        )

        assertEquals(Tagesleiste.LEER, leiste)
    }

    @Test
    fun `Krank zeigt den Eintrag, aber nie einen Wecker`() {
        val leiste = tagesleiste(eingabe(kategorie = ShiftKategorie.KRANK, dienstzeit = null, weckzeit = LocalTime.of(5, 0)))

        assertEquals(listOf(Balken(Spur.EINTRAG, 360, 1800)), leiste.balken)
        assertTrue(leiste.weckpunkte.isEmpty())
    }

    // ── Wecker ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `eine geaenderte Entwurfs-Weckzeit verschiebt den Weckpunkt`() {
        val um0430 = tagesleiste(eingabe(weckzeit = LocalTime.of(4, 30)))
        val um0515 = tagesleiste(eingabe(weckzeit = LocalTime.of(5, 15)))

        assertEquals(listOf(Weckpunkt(630, false)), um0430.weckpunkte)
        assertEquals(listOf(Weckpunkt(675, false)), um0515.weckpunkte)
        assertEquals("04:30" to "Wecker – F", um0430.ereignisseVon(Spur.WECKER).single())
        assertEquals("05:15" to "Wecker – F", um0515.ereignisseVon(Spur.WECKER).single())
    }

    @Test
    fun `ein stummer Wecker heisst Stummer Wecker und bleibt ein Punkt`() {
        val leiste = tagesleiste(eingabe(still = true))

        assertEquals(listOf(Weckpunkt(630, still = true)), leiste.weckpunkte)
        assertEquals("04:30" to "Stummer Wecker – F", leiste.ereignisseVon(Spur.WECKER).single())
    }

    @Test
    fun `kein Wecker bei Wecker aus, ungueltiger Weckzeit und ausserhalb des Fensters`() {
        assertTrue(tagesleiste(eingabe(weckerAn = false)).weckpunkte.isEmpty())
        assertTrue(tagesleiste(eingabe(weckzeit = null)).weckpunkte.isEmpty())
        // Der Eintrag bleibt, auch wenn der Wecker fehlt.
        assertEquals(listOf(Balken(Spur.EINTRAG, 720, 1215)), tagesleiste(eingabe(weckerAn = false)).balken)

        assertEquals(630, weckpunktMin(ShiftKategorie.DIENST, true, LocalTime.of(4, 30), frueh))
        assertNull(weckpunktMin(ShiftKategorie.IGNORIEREN, true, LocalTime.of(4, 30), frueh))
        assertNull(weckpunktMin(ShiftKategorie.KRANK, true, LocalTime.of(4, 30), null))
    }

    // ── Ruf-Abfrage ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `das Ruf-Raster gleicht der Folge aus naechsteAbfrage`() {
        // 21:30 ist KEINE volle Stunde: "Beginn + k * 1 h" und "jede Wanduhr-Stunde" fallen hier auseinander.
        val von = LocalDateTime.of(2026, 8, 12, 21, 30)
        val span = ShiftSpan(
            "AD",
            von.atZone(berlin).toInstant().toEpochMilli(),
            von.plusMinutes(570).atZone(berlin).toInstant().toEpochMilli(),
            alarmTriggerTime = 0L
        )
        val dz = dienstzeitAus(listOf(span), "AD", span.startTime - 3_600_000L, berlin)!!

        val leiste = tagesleiste(eingabe(kategorie = ShiftKategorie.RUFBEREITSCHAFT, name = "AD", dienstzeit = dz))

        val folge = folgeDerKette(span, dz)
        assertEquals(folge, leiste.rufAbfragen)
        // Und gegen Literale: 21:30, 22:30 ... 06:30 am Folgetag.
        assertEquals(10, leiste.rufAbfragen.size)
        assertEquals(1650, leiste.rufAbfragen.first())
        assertEquals(2190, leiste.rufAbfragen.last())
        assertEquals(listOf(1650 to 2220), leiste.balkenVon(Spur.RUF_ABFRAGE))
    }

    @Test
    fun `das Ende selbst gehoert nicht mehr zum Raster`() {
        // 22:00 bis 06:00: das Ende liegt genau auf einem Rasterpunkt. Die Kette fragt dort nicht
        // mehr ab (`kandidat < endTime`), die Leiste zeigt ihn deshalb auch nicht.
        val von = LocalDateTime.of(2026, 8, 12, 22, 0)
        val span = ShiftSpan(
            "AD",
            von.atZone(berlin).toInstant().toEpochMilli(),
            von.plusHours(8).atZone(berlin).toInstant().toEpochMilli(),
            alarmTriggerTime = 0L
        )
        val dz = dienstzeitAus(listOf(span), "AD", span.startTime - 3_600_000L, berlin)!!

        val leiste = tagesleiste(eingabe(kategorie = ShiftKategorie.RUFBEREITSCHAFT, name = "AD", dienstzeit = dz))

        assertEquals(folgeDerKette(span, dz), leiste.rufAbfragen)
        // 22:00 ... 05:00 = acht Punkte; 06:00 (Minute 2160) ist das Ende und faellt weg.
        assertEquals(listOf(1680, 1740, 1800, 1860, 1920, 1980, 2040, 2100), leiste.rufAbfragen)
    }

    /**
     * Die Folge der echten Kette ueber [span]: erster Termin der Beginn, danach stuendlich, ab dem
     * Ende keiner mehr - jeweils als Minute der Leiste.
     */
    private fun folgeDerKette(span: ShiftSpan, dz: Dienstzeit): List<Int> {
        val folge = mutableListOf<Int>()
        var jetzt = span.startTime - 120_000L
        while (true) {
            val naechste = RufbereitschaftAbfrage.naechsteAbfrage(listOf(span), setOf(span.shiftName), jetzt) ?: break
            folge += dz.beginnMin + ((naechste - span.startTime) / 60_000L).toInt()
            jetzt = naechste
        }
        return folge
    }

    @Test
    fun `nur Rufbereitschaft fragt stuendlich ab`() {
        val dienst = tagesleiste(eingabe(kategorie = ShiftKategorie.DIENST, dienstzeit = ruf))
        val urlaub = tagesleiste(eingabe(kategorie = ShiftKategorie.URLAUB, dienstzeit = null))

        assertTrue(dienst.rufAbfragen.isEmpty())
        assertTrue(urlaub.rufAbfragen.isEmpty())
        assertTrue(dienst.balken.none { it.spur == Spur.RUF_ABFRAGE })
    }

    // ── "Nicht stoeren" ──────────────────────────────────────────────────────────────────────

    @Test
    fun `Nicht stoeren wird vereint - aneinanderstossend und ueberlappend, getrennt bei einer Luecke`() {
        val leiste = tagesleiste(
            eingabe(
                nichtStoeren = listOf(
                    DndBereich(tag(23), tag(23, 45), DndQuelle.DIMM_FENSTER),
                    DndBereich(tag(22), tag(23, 30), DndQuelle.KALENDEREINTRAG),
                    DndBereich(tag(20), tag(22), DndQuelle.DIMM_FENSTER),
                    DndBereich(folgetag(1), folgetag(2), DndQuelle.DIMM_FENSTER)
                )
            )
        )

        assertEquals(listOf(1560 to 1785, 1860 to 1920), leiste.balkenVon(Spur.NICHT_STOEREN))
        assertEquals(
            listOf(
                "20:00" to "„Nicht stören“ an (Dimm-Fenster)",
                "23:45" to "„Nicht stören“ aus",
                "Folgetag 01:00" to "„Nicht stören“ an (Dimm-Fenster)",
                "Folgetag 02:00" to "„Nicht stören“ aus"
            ),
            leiste.ereignisseVon(Spur.NICHT_STOEREN)
        )
    }

    @Test
    fun `Nicht stoeren endet bei Rufbereitschaft um 05-00 - erst kappen, dann vereinen`() {
        // Der Cutoff liegt am Folgetag 05:00 (Beginn 07:00 liegt NACH 05:00 -> Folgetag).
        // Bereich A laeuft ueber den Cutoff, Bereich B beginnt erst um 06:00: sie bleiben getrennt.
        // Erst vereinen und dann kappen liesse beide zu einem Stueck bis 05:00 verschmelzen.
        val leiste = tagesleiste(
            eingabe(
                kategorie = ShiftKategorie.RUFBEREITSCHAFT, name = "AD", dienstzeit = ruf,
                nichtStoeren = listOf(
                    DndBereich(tag(22), folgetag(6), DndQuelle.DIMM_FENSTER),
                    DndBereich(folgetag(6), folgetag(8), DndQuelle.DIMM_FENSTER)
                )
            )
        )

        assertEquals(listOf(1680 to 2100, 2160 to 2280), leiste.balkenVon(Spur.NICHT_STOEREN))
        assertEquals(
            listOf(
                "22:00" to "„Nicht stören“ an (Dimm-Fenster)",
                "Folgetag 05:00" to "„Nicht stören“ endet spätestens jetzt – erreichbar sein (Rufbereitschaft)",
                "Folgetag 06:00" to "„Nicht stören“ an (Dimm-Fenster)",
                "Folgetag 08:00" to "„Nicht stören“ aus"
            ),
            leiste.ereignisseVon(Spur.NICHT_STOEREN)
        )
    }

    @Test
    fun `der Cutoff rollt auf den Folgetag, wenn der Beginn nach ihm liegt - und bleibt am selben Tag davor`() {
        // Beginn 03:00 liegt VOR dem Cutoff 05:00: derselbe Tag, Minute 360 + 300 = 660.
        val frueherBeginn = tagesleiste(
            eingabe(
                kategorie = ShiftKategorie.RUFBEREITSCHAFT, name = "AD",
                dienstzeit = Dienstzeit(LocalTime.of(3, 0), 1440, false),
                nichtStoeren = listOf(DndBereich(tag(0), tag(8), DndQuelle.KALENDEREINTRAG))
            )
        )
        assertEquals(listOf(360 to 660), frueherBeginn.balkenVon(Spur.NICHT_STOEREN))

        // Genau auf dem Cutoff beginnend (05:00): "nach ihm" -> Folgetag, der Bereich bleibt ganz.
        val gleichzeitig = tagesleiste(
            eingabe(
                kategorie = ShiftKategorie.RUFBEREITSCHAFT, name = "AD",
                dienstzeit = Dienstzeit(LocalTime.of(5, 0), 1440, false),
                nichtStoeren = listOf(DndBereich(tag(0), tag(8), DndQuelle.KALENDEREINTRAG))
            )
        )
        assertEquals(listOf(360 to 840), gleichzeitig.balkenVon(Spur.NICHT_STOEREN))
    }

    @Test
    fun `der Cutoff-Text steht nur, wenn er einen Bereich wirklich beendet`() {
        val ohneKuerzung = tagesleiste(
            eingabe(
                kategorie = ShiftKategorie.RUFBEREITSCHAFT, name = "AD", dienstzeit = ruf,
                nichtStoeren = listOf(DndBereich(folgetag(6), folgetag(8), DndQuelle.DIMM_FENSTER))
            )
        )
        assertTrue(ohneKuerzung.ereignisse.none { it.text.contains("spätestens") })

        val ohneDnd = tagesleiste(eingabe(kategorie = ShiftKategorie.RUFBEREITSCHAFT, name = "AD", dienstzeit = ruf))
        assertTrue(ohneDnd.ereignisse.none { it.text.contains("spätestens") })

        // Schliesst direkt an den gekappten Bereich ein zweiter an (Cutoff 06:00), laeuft "Nicht
        // stoeren" weiter - dann darf auch kein "endet spaetestens jetzt" dastehen.
        val nahtlos = tagesleiste(
            eingabe(
                kategorie = ShiftKategorie.RUFBEREITSCHAFT, name = "AD", dienstzeit = ruf, rufCutoffMinuten = 360,
                nichtStoeren = listOf(
                    DndBereich(tag(22), folgetag(7), DndQuelle.DIMM_FENSTER),
                    DndBereich(folgetag(6), folgetag(8), DndQuelle.KALENDEREINTRAG)
                )
            )
        )
        assertEquals(listOf(1680 to 2280), nahtlos.balkenVon(Spur.NICHT_STOEREN))
        assertTrue(nahtlos.ereignisse.none { it.text.contains("spätestens") })
    }

    @Test
    fun `ohne Rufbereitschaft kappt nichts`() {
        val leiste = tagesleiste(
            eingabe(nichtStoeren = listOf(DndBereich(tag(22), folgetag(6), DndQuelle.DIMM_FENSTER)))
        )

        assertEquals(listOf(1680 to 2160), leiste.balkenVon(Spur.NICHT_STOEREN))
    }

    // ── Dimmen, Licht, Fenster, Blockposition ────────────────────────────────────────────────

    @Test
    fun `Bereiche werden auf das Fenster gekappt, ihre Ereignisse nur wenn sie im Fenster liegen`() {
        val leiste = tagesleiste(
            eingabe(
                dimmen = listOf(
                    DimBereich(vortag(15), tag(7), 50),
                    DimBereich(tag(22), 3000, 30),
                    DimBereich(2900, 3000, 20),
                    DimBereich(-200, -100, 20)
                )
            )
        )

        assertEquals(listOf(0 to 780, 1680 to 2880), leiste.balkenVon(Spur.DIMMEN))
        assertEquals(
            listOf("07:00" to "Dimmen endet (F)", "22:00" to "Dimmen beginnt – 30 % (F)"),
            leiste.ereignisseVon(Spur.DIMMEN)
        )
    }

    @Test
    fun `leere und umgekehrte Bereiche werden uebergangen`() {
        val leiste = tagesleiste(
            eingabe(
                dimmen = listOf(DimBereich(500, 500, 50), DimBereich(600, 500, 50)),
                nichtStoeren = listOf(DndBereich(500, 500, DndQuelle.DIMM_FENSTER)),
                licht = listOf(LichtBereich(700, 600, LichtArt.AN))
            )
        )

        assertTrue(leiste.balken.none { it.spur != Spur.EINTRAG })
    }

    @Test
    fun `Dimmen nennt Staerke und Wecker als Grund des Endes`() {
        val leiste = tagesleiste(eingabe(dimmen = listOf(DimBereich(tag(20), folgetag(4, 30), 70, endetBeimWecker = true))))

        assertEquals(
            listOf("20:00" to "Dimmen beginnt – 70 % (F)", "Folgetag 04:30" to "Dimmen endet (F) – der Wecker klingelt"),
            leiste.ereignisseVon(Spur.DIMMEN)
        )
    }

    @Test
    fun `Licht - Sonnenaufgang endet am Wecker ohne Aus-Zeile, Licht an hat beide`() {
        val leiste = tagesleiste(
            eingabe(
                licht = listOf(
                    LichtBereich(tag(4), tag(4, 30), LichtArt.SONNENAUFGANG),
                    LichtBereich(tag(4, 30), tag(5), LichtArt.AN)
                )
            )
        )

        assertEquals(listOf(600 to 630, 630 to 660), leiste.balkenVon(Spur.LICHT))
        assertEquals(
            listOf("04:00" to "Licht: Sonnenaufgang beginnt", "04:30" to "Licht an", "05:00" to "Licht aus"),
            leiste.ereignisseVon(Spur.LICHT)
        )
    }

    @Test
    fun `die Blockposition waehlt, welche Bereiche an diesem Tag gelten`() {
        val nurErster = setOf(Blockposition.ERSTER)
        val bereiche = eingabe(
            dimmen = listOf(DimBereich(tag(20), tag(23), 50, positionen = nurErster), DimBereich(tag(21), tag(22), 40)),
            nichtStoeren = listOf(DndBereich(tag(20), tag(23), DndQuelle.DIMM_FENSTER, positionen = nurErster))
        )

        val mittendrin = tagesleiste(bereiche.copy(blockposition = Blockposition.MITTLERER))
        assertEquals(listOf(1620 to 1680), mittendrin.balkenVon(Spur.DIMMEN))
        assertTrue(mittendrin.balkenVon(Spur.NICHT_STOEREN).isEmpty())

        val erster = tagesleiste(bereiche.copy(blockposition = Blockposition.ERSTER))
        assertEquals(listOf(1560 to 1740, 1620 to 1680), erster.balkenVon(Spur.DIMMEN))
        assertEquals(listOf(1560 to 1740), erster.balkenVon(Spur.NICHT_STOEREN))
    }

    @Test
    fun `die Vorgabe der Blockposition - mittendrin bei Dienst und Rufbereitschaft, sonst einzeln`() {
        ShiftKategorie.entries.forEach {
            val erwartet = if (it == ShiftKategorie.DIENST || it == ShiftKategorie.RUFBEREITSCHAFT) {
                Blockposition.MITTLERER
            } else {
                Blockposition.EINZELNER
            }
            assertEquals("$it", erwartet, standardBlockposition(it))
        }
        assertEquals(Blockposition.MITTLERER, eingabe(kategorie = ShiftKategorie.DIENST).blockposition)
        assertEquals(Blockposition.EINZELNER, eingabe(kategorie = ShiftKategorie.URLAUB).blockposition)
    }

    @Test
    fun `Ereignisse sind nach Zeit sortiert und tragen Vortag und Folgetag vor der Uhrzeit`() {
        assertEquals("Vortag 18:00", zeitText(0))
        assertEquals("Vortag 23:59", zeitText(359))
        assertEquals("00:00", zeitText(360))
        assertEquals("23:59", zeitText(1799))
        assertEquals("Folgetag 00:00", zeitText(1800))
        assertEquals("Folgetag 17:59", zeitText(2879))

        val leiste = tagesleiste(
            eingabe(
                dimmen = listOf(DimBereich(vortag(22), folgetag(4, 30), 50)),
                licht = listOf(LichtBereich(tag(4), tag(4, 30), LichtArt.SONNENAUFGANG))
            )
        )
        assertEquals(leiste.ereignisse.map { it.minute }.sorted(), leiste.ereignisse.map { it.minute })
        assertEquals("Vortag 22:00", leiste.ereignisse.first().zeit)
    }
}
