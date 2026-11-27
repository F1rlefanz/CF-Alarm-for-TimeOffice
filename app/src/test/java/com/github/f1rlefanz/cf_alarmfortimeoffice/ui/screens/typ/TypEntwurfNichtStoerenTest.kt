package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ

import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.Blockposition
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimAnchor
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindow
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.TypDimmQuelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypDimmFenster
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypDimmen
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypNichtStoeren
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpan
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.LichtQuelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.Quelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.Spur
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.TagesleisteQuellen
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.wirksameDienstzeit
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * Der Abschnitt „Nicht stören“ der Typ-Seite (Umbau Etappe 3, #222), ohne Compose: der Entwurf traegt beide
 * Schalter und „Endet spaetestens um“ ueber Speichern, Prozesstod und die Tagesleiste; die Zeile „Endet
 * spaetestens um“ gibt es nur bei Rufbereitschaft, und ein Schalter ohne Wirkung steht nie still an.
 */
class TypEntwurfNichtStoerenTest {

    /** „Nacht davor 22:00 bis Wecker, spaetestens 07:00“. */
    private val nachtDavor = TypDimmFenster(
        DimWindow(
            startAnchor = DimAnchor.CLOCK, startClockMinutes = 22 * 60,
            endAnchor = DimAnchor.ALARM_SONST_CLOCK, endClockMinutes = 7 * 60, startAmVorabend = true
        ),
        staerke = 55
    )

    private val mitSchaltern = TypNichtStoeren(solangeGedimmt = true, waehrendEintrag = true, endetSpaetestensGespeichert = 6 * 60 + 15)

    private fun typ(
        nichtStoeren: TypNichtStoeren = TypNichtStoeren(),
        kategorie: ShiftKategorie = ShiftKategorie.DIENST,
        name: String = "F",
        dimmen: TypDimmen = TypDimmen()
    ) = ShiftDefinition(
        id = "id-$name", name = name, keywords = listOf(name), alarmTime = LocalTime.of(4, 30),
        dimmen = dimmen, nichtStoeren = nichtStoeren
    ).mitKategorie(kategorie)

    private fun TypEntwurf.mitNichtStoeren(aenderung: (NichtStoerenEntwurf) -> NichtStoerenEntwurf) =
        copy(nichtStoeren = aenderung(nichtStoeren!!))

    // ── Abnahme: der Entwurf uebersteht den Prozesstod ───────────────────────────────────────

    @Test
    fun `der Entwurf mit beiden Schaltern und Cutoff uebersteht alsSpeicherListe und ausSpeicherListe`() {
        val ausgang = TypEntwurf.aus(typ(kategorie = ShiftKategorie.RUFBEREITSCHAFT))
        // Ungespeicherte Aenderungen muessen den Prozesstod ueberleben, nicht nur der Stand beim Oeffnen.
        val entwurf = ausgang.mitNichtStoeren {
            it.copy(solangeGedimmt = true, waehrendEintrag = true, endetSpaetestensMinuten = 6 * 60 + 45)
        }

        val zurueck = TypEntwurf.ausSpeicherListe(entwurf.alsSpeicherListe())

        assertEquals(entwurf, zurueck)
        assertEquals(NichtStoerenEntwurf(true, true, 6 * 60 + 45), zurueck!!.nichtStoeren)
    }

    @Test
    fun `die Teile im Bundle sind unabhaengig voneinander`() {
        val voll = TypEntwurf.aus(typ(mitSchaltern, dimmen = TypDimmen().mitFenster(listOf(nachtDavor))))

        val nurNichtStoeren = voll.copy(dimmen = null)
        assertEquals(nurNichtStoeren, TypEntwurf.ausSpeicherListe(nurNichtStoeren.alsSpeicherListe()))
        val nurDimmen = voll.copy(nichtStoeren = null)
        assertEquals(nurDimmen, TypEntwurf.ausSpeicherListe(nurDimmen.alsSpeicherListe()))
        val keins = voll.copy(dimmen = null, nichtStoeren = null)
        assertEquals(keins, TypEntwurf.ausSpeicherListe(keins.alsSpeicherListe()))
    }

    @Test
    fun `ein gespeicherter Entwurf ohne den Teil gilt als unberuehrt, nie als beide Schalter aus`() {
        val vorher = typ(mitSchaltern)
        val alt = TypEntwurf.aus(vorher).alsSpeicherListe().take(7)

        val gelesen = TypEntwurf.ausSpeicherListe(alt)!!

        assertNull(gelesen.nichtStoeren)
        assertEquals(mitSchaltern, gelesen.alsDefinition(vorher)!!.nichtStoeren)
    }

    // ── Abnahme: „Endet spaetestens um“ nur bei Rufbereitschaft ──────────────────────────────

    @Test
    fun `Endet spaetestens um gibt es nur bei Rufbereitschaft`() {
        assertTrue(NichtStoerenAbschnittLogik.zeigtEndetSpaetestens(ShiftKategorie.RUFBEREITSCHAFT))
        ShiftKategorie.entries.filter { it != ShiftKategorie.RUFBEREITSCHAFT }.forEach {
            assertFalse(it.name, NichtStoerenAbschnittLogik.zeigtEndetSpaetestens(it))
        }
        // Die feste Zeile „Ohne Kalendereintrag“ (#223 benutzt denselben Abschnitt) hat sie auch nicht.
        assertFalse(NichtStoerenAbschnittLogik.zeigtEndetSpaetestens(null))
    }

    @Test
    fun `der Abschnitt fehlt bei Krank und Ignorieren und steht sonst`() {
        val ohne = setOf(ShiftKategorie.KRANK, ShiftKategorie.IGNORIEREN)
        ShiftKategorie.entries.forEach {
            assertEquals(it.name, it !in ohne, NichtStoerenAbschnittLogik.zeigtAbschnitt(it))
        }
    }

    @Test
    fun `ein Kategoriewechsel behaelt die Uhrzeit, auch wenn die Zeile verschwindet`() {
        val vorher = typ(mitSchaltern, kategorie = ShiftKategorie.RUFBEREITSCHAFT)
        val gewechselt = TypAbschnittLogik.kategorieWechseln(TypEntwurf.aus(vorher), ShiftKategorie.DIENST)

        val gespeichert = gewechselt.alsDefinition(vorher)!!

        assertEquals(ShiftKategorie.DIENST, gespeichert.kategorie)
        assertEquals(mitSchaltern, gespeichert.nichtStoeren)
    }

    // ── Speichern ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `Speichern der Typ-Seite schreibt Schalter und Uhrzeit in die Konfiguration und armiert neu`() {
        val vorher = typ(kategorie = ShiftKategorie.RUFBEREITSCHAFT)
        val config = ShiftConfig(definitions = listOf(vorher))
        val entwurf = TypEntwurf.aus(vorher).mitNichtStoeren {
            it.copy(solangeGedimmt = true, waehrendEintrag = true, endetSpaetestensMinuten = 6 * 60 + 15)
        }
        assertTrue(TypSeiteEntwurf.hatAenderungen(TypEntwurf.aus(vorher), entwurf))

        var geschrieben: ShiftConfig? = null
        val ergebnis = TypSeiteSpeicherung { geschrieben = it }.speichere(config, vorher.id, entwurf)

        assertEquals(SpeicherErgebnis.GESCHRIEBEN, ergebnis)
        assertEquals(mitSchaltern, geschrieben!!.definitions.single().nichtStoeren)
        // Nur so bekommt die DND-Kette die neuen Schalter mit (#218).
        assertTrue(TypDimmQuelle.dimmenOderNichtStoerenGeaendert(config, geschrieben!!))
    }

    @Test
    fun `Speichern ohne Aenderung am Abschnitt laesst auch einen ungeklemmten gespeicherten Wert stehen`() {
        // Import und Android-Backup umgehen jede Eingabepruefung; die Klemme sitzt im Lesepfad.
        val roh = Json.decodeFromString(
            TypNichtStoeren.serializer(), """{"solangeGedimmt":true,"endetSpaetestensMinuten":9999}"""
        )
        val vorher = typ(roh, kategorie = ShiftKategorie.RUFBEREITSCHAFT)

        val gespeichert = TypEntwurf.aus(vorher).copy(weckzeitText = "05:00").alsDefinition(vorher)!!

        assertEquals(roh, gespeichert.nichtStoeren)
    }

    @Test
    fun `eine neue Uhrzeit ersetzt den gespeicherten Wert`() {
        val vorher = typ(mitSchaltern, kategorie = ShiftKategorie.RUFBEREITSCHAFT)

        val gespeichert = TypEntwurf.aus(vorher).mitNichtStoeren { it.copy(endetSpaetestensMinuten = 4 * 60) }
            .alsDefinition(vorher)!!

        assertEquals(4 * 60, gespeichert.nichtStoeren.endetSpaetestensMinuten)
    }

    @Test
    fun `der Teil geht auf den gerade gespeicherten Typ - was daneben geschrieben wurde bleibt`() {
        val beimOeffnen = typ()
        val entwurf = TypEntwurf.aus(beimOeffnen).mitNichtStoeren { it.copy(solangeGedimmt = true) }
        // Inzwischen hat ein anderer Weg die Farbwaerme gesetzt.
        val jetzt = beimOeffnen.copy(dimmen = beimOeffnen.dimmen.copy(farbwaerme = 80))

        val gespeichert = entwurf.alsDefinition(jetzt)!!

        assertTrue(gespeichert.nichtStoeren.solangeGedimmt)
        assertEquals(80, gespeichert.dimmen.farbwaerme)
    }

    // ── Schreiber unter der offenen Seite (Typ-Migration) ───────────────────────────────────

    @Test
    fun `unberuehrt folgt dem Gespeicherten, sonst ueberschriebe Speichern die migrierten Schalter`() {
        val beimOeffnen = typ(kategorie = ShiftKategorie.RUFBEREITSCHAFT)
        val ausgang = TypEntwurf.aus(beimOeffnen)
        val entwurf = ausgang.copy(weckzeitText = "05:00")
        val jetzt = beimOeffnen.copy(nichtStoeren = mitSchaltern)

        val (neuerAusgang, nachgezogen) = TypSeiteEntwurf.nachgezogenNichtStoeren(ausgang, entwurf, jetzt.nichtStoeren)
        var geschrieben: ShiftConfig? = null
        TypSeiteSpeicherung { geschrieben = it }.speichere(ShiftConfig(definitions = listOf(jetzt)), jetzt.id, nachgezogen)

        assertEquals(mitSchaltern, geschrieben!!.definitions.single().nichtStoeren)
        assertEquals("05:00", nachgezogen.weckzeitText)
        assertEquals(NichtStoerenEntwurf.aus(mitSchaltern), neuerAusgang.nichtStoeren)
    }

    @Test
    fun `eine unberuehrte Seite zeigt den nachgezogenen Stand und gilt nicht als geaendert`() {
        val ausgang = TypEntwurf.aus(typ())

        val (neuerAusgang, entwurf) = TypSeiteEntwurf.nachgezogenNichtStoeren(ausgang, ausgang, mitSchaltern)

        assertEquals(NichtStoerenEntwurf.aus(mitSchaltern), entwurf.nichtStoeren)
        assertFalse(TypSeiteEntwurf.hatAenderungen(neuerAusgang, entwurf))
    }

    @Test
    fun `selbst Geaendertes bleibt, jedes Feld fuer sich`() {
        val ausgang = TypEntwurf.aus(typ())
        val nurGedimmt = ausgang.mitNichtStoeren { it.copy(solangeGedimmt = true) }
        val (_, a) = TypSeiteEntwurf.nachgezogenNichtStoeren(
            ausgang, nurGedimmt, TypNichtStoeren(solangeGedimmt = false, waehrendEintrag = true, endetSpaetestensGespeichert = 360)
        )
        assertEquals(NichtStoerenEntwurf(solangeGedimmt = true, waehrendEintrag = true, endetSpaetestensMinuten = 360), a.nichtStoeren)

        val nurEintrag = ausgang.mitNichtStoeren { it.copy(waehrendEintrag = true) }
        val (_, b) = TypSeiteEntwurf.nachgezogenNichtStoeren(ausgang, nurEintrag, TypNichtStoeren(solangeGedimmt = true))
        assertTrue(b.nichtStoeren!!.solangeGedimmt)
        assertTrue(b.nichtStoeren!!.waehrendEintrag)

        val nurUhrzeit = ausgang.mitNichtStoeren { it.copy(endetSpaetestensMinuten = 240) }
        val (_, c) = TypSeiteEntwurf.nachgezogenNichtStoeren(ausgang, nurUhrzeit, mitSchaltern)
        assertEquals(NichtStoerenEntwurf(true, true, 240), c.nichtStoeren)
    }

    @Test
    fun `ohne den Teil im Entwurf gilt nach dem Nachziehen das Gespeicherte`() {
        val ausgang = TypEntwurf.aus(typ()).copy(nichtStoeren = null)

        val (a, e) = TypSeiteEntwurf.nachgezogenNichtStoeren(ausgang, ausgang, mitSchaltern)

        assertEquals(NichtStoerenEntwurf.aus(mitSchaltern), a.nichtStoeren)
        assertEquals(NichtStoerenEntwurf.aus(mitSchaltern), e.nichtStoeren)
    }

    // ── Texte und Zugriff ───────────────────────────────────────────────────────────────────

    @Test
    fun `die Titel und Untertitel stehen wie im Prototyp`() {
        assertEquals("Nicht stören", NichtStoerenTexte.TITEL)
        assertEquals("Solange gedimmt wird", NichtStoerenTexte.GEDIMMT_TITEL)
        assertEquals("In jedem Dimm-Fenster oben – endet mit dem Wecker", NichtStoerenTexte.GEDIMMT_HINWEIS)
        assertEquals("Während des Kalendereintrags", NichtStoerenTexte.EINTRAG_TITEL)
        assertEquals("Endet spätestens um", NichtStoerenTexte.CUTOFF_TITEL)
        assertEquals("Damit du für einen Einsatz erreichbar bist.", NichtStoerenTexte.CUTOFF_HINWEIS)
    }

    @Test
    fun `der Text zu Waehrend des Kalendereintrags folgt der Kategorie`() {
        val dienst = "Von Beginn bis Ende laut Kalender – z. B. während des Nachtdienstes."
        val ganztaegig = "Bei ganztägigen Einträgen: den ganzen Tag."
        assertEquals(dienst, NichtStoerenTexte.eintragText(ShiftKategorie.DIENST))
        assertEquals(dienst, NichtStoerenTexte.eintragText(ShiftKategorie.RUFBEREITSCHAFT))
        assertEquals(dienst, NichtStoerenTexte.eintragText(ShiftKategorie.IGNORIEREN))
        listOf(ShiftKategorie.URLAUB, ShiftKategorie.FREI, ShiftKategorie.KRANK, ShiftKategorie.FORTBILDUNG).forEach {
            assertEquals(it.name, ganztaegig, NichtStoerenTexte.eintragText(it))
        }
        assertEquals("Gibt es hier nicht – an diesen Tagen steht nichts im Kalender.", NichtStoerenTexte.eintragText(null))
    }

    @Test
    fun `an der festen Zeile ist Waehrend des Kalendereintrags gesperrt und nie an`() {
        val werte = NichtStoerenEntwurf(solangeGedimmt = false, waehrendEintrag = true, endetSpaetestensMinuten = 300)
        assertFalse(NichtStoerenAbschnittLogik.gibtEintrag(null))
        assertFalse(NichtStoerenAbschnittLogik.eintragAn(werte, null))
        assertTrue(NichtStoerenAbschnittLogik.gibtEintrag(ShiftKategorie.URLAUB))
        assertTrue(NichtStoerenAbschnittLogik.eintragAn(werte, ShiftKategorie.URLAUB))
    }

    @Test
    fun `bedienbar ist der Abschnitt nur mit unterstuetztem Android und erteiltem Zugriff`() {
        assertEquals(DndZugriff.NICHT_UNTERSTUETZT, NichtStoerenAbschnittLogik.zugriff(unterstuetzt = false, erteilt = false))
        // Ein "erteilt" auf zu altem Android aendert nichts: die Funktion gibt es dort nicht.
        assertEquals(DndZugriff.NICHT_UNTERSTUETZT, NichtStoerenAbschnittLogik.zugriff(unterstuetzt = false, erteilt = true))
        assertEquals(DndZugriff.FEHLT, NichtStoerenAbschnittLogik.zugriff(unterstuetzt = true, erteilt = false))
        assertEquals(DndZugriff.ERTEILT, NichtStoerenAbschnittLogik.zugriff(unterstuetzt = true, erteilt = true))

        assertFalse(DndZugriff.NICHT_UNTERSTUETZT.bedienbar)
        assertFalse(DndZugriff.FEHLT.bedienbar)
        assertTrue(DndZugriff.ERTEILT.bedienbar)
    }

    @Test
    fun `Solange gedimmt wird steht nicht still an, wenn der Typ nie dimmt`() {
        val an = NichtStoerenEntwurf(solangeGedimmt = true, waehrendEintrag = false, endetSpaetestensMinuten = 300)
        val mitFenster = DimmenEntwurf(an = true, fenster = listOf(nachtDavor))

        assertFalse(NichtStoerenAbschnittLogik.gedimmtOhneWirkung(an, mitFenster))
        assertTrue(NichtStoerenAbschnittLogik.gedimmtOhneWirkung(an, mitFenster.copy(an = false)))
        assertTrue(NichtStoerenAbschnittLogik.gedimmtOhneWirkung(an, mitFenster.copy(fenster = emptyList())))
        // Ein ausgeschalteter Schalter steht nicht still an; ohne Dimm-Teil im Entwurf weiss die Seite nichts.
        assertFalse(NichtStoerenAbschnittLogik.gedimmtOhneWirkung(an.copy(solangeGedimmt = false), mitFenster.copy(an = false)))
        assertFalse(NichtStoerenAbschnittLogik.gedimmtOhneWirkung(an, null))
    }

    // ── Die Karte „So läuft ein Tag“ rechnet mit dem Entwurf ────────────────────────────────

    private val zone = ZoneOffset.UTC
    private val jetzt = LocalDateTime.of(2026, 11, 9, 12, 0).toInstant(zone).toEpochMilli()

    private fun quellen(config: ShiftConfig, name: String) = TagesleisteQuellen(
        typen = Quelle.Bekannt(config),
        licht = Quelle.Bekannt(LichtQuelle(konfiguriert = false, regeln = emptyList(), vorlaufMinuten = { _, _, _, _ -> null })),
        spannen = Quelle.Bekannt(
            listOf(
                ShiftSpan(
                    shiftName = name,
                    startTime = LocalDateTime.of(2026, 11, 10, 6, 0).toInstant(zone).toEpochMilli(),
                    endTime = LocalDateTime.of(2026, 11, 10, 14, 15).toInstant(zone).toEpochMilli(),
                    alarmTriggerTime = LocalDateTime.of(2026, 11, 10, 4, 30).toInstant(zone).toEpochMilli()
                )
            )
        )
    )

    /** Die Balken der Spur „Nicht stören“; [gespeicherterName] `null` = Neuanlage. */
    private fun dndBalken(
        entwurf: TypEntwurf,
        gespeicherterName: String?,
        config: ShiftConfig,
        quellen: TagesleisteQuellen = quellen(config, "F")
    ) = TagesleisteKarteLogik.rechne(entwurf, gespeicherterName, quellen, Blockposition.EINZELNER, jetzt, zone)
        .leiste.balken.filter { it.spur == Spur.NICHT_STOEREN }.map { it.vonMin to it.bisMin }

    @Test
    fun `die Leiste zeigt Nicht stoeren aus dem Entwurf, bevor gespeichert ist`() {
        val gespeichert = typ(dimmen = TypDimmen().mitFenster(listOf(nachtDavor)))
        val config = ShiftConfig(definitions = listOf(gespeichert))
        val ohne = TypEntwurf.aus(gespeichert)
        assertEquals(emptyList<Pair<Int, Int>>(), dndBalken(ohne, "F", config))

        // „Solange gedimmt wird“: 22:00 am Vortag (Minute 240) bis zum Wecker 04:30 (Minute 630).
        val gedimmt = ohne.mitNichtStoeren { it.copy(solangeGedimmt = true) }
        assertEquals(listOf(240 to 630), dndBalken(gedimmt, "F", config))

        // „Während des Kalendereintrags“: 06:00 (Minute 720) bis 14:15 (Minute 1215).
        val eintrag = ohne.mitNichtStoeren { it.copy(waehrendEintrag = true) }
        assertEquals(listOf(720 to 1215), dndBalken(eintrag, "F", config))
    }

    @Test
    fun `im Entwurf ausgeschaltet verschwindet der Balken, obwohl der Schalter noch gespeichert ist`() {
        val gespeichert = typ(TypNichtStoeren(waehrendEintrag = true))
        val config = ShiftConfig(definitions = listOf(gespeichert))
        val entwurf = TypEntwurf.aus(gespeichert)
        assertEquals(listOf(720 to 1215), dndBalken(entwurf, "F", config))

        assertEquals(emptyList<Pair<Int, Int>>(), dndBalken(entwurf.mitNichtStoeren { it.copy(waehrendEintrag = false) }, "F", config))
    }

    @Test
    fun `die Leiste kappt Nicht stoeren an der Uhrzeit des Entwurfs`() {
        // Rufdienst 15:00 bis 07:00, dazu „Abend danach“ 22:00–07:00: Minute 1680 bis 2220 der Leiste.
        val abendDanach = TypDimmFenster(
            DimWindow(
                startAnchor = DimAnchor.CLOCK, startClockMinutes = 22 * 60,
                endAnchor = DimAnchor.CLOCK, endClockMinutes = 7 * 60
            )
        )
        val gespeichert = typ(
            TypNichtStoeren(solangeGedimmt = true),
            kategorie = ShiftKategorie.RUFBEREITSCHAFT,
            name = "AD",
            dimmen = TypDimmen().mitFenster(listOf(abendDanach))
        )
        val config = ShiftConfig(definitions = listOf(gespeichert))
        val entwurf = TypEntwurf.aus(gespeichert).copy(weckzeitText = "14:00")
        val spanne = ShiftSpan(
            shiftName = "AD",
            startTime = LocalDateTime.of(2026, 11, 10, 15, 0).toInstant(zone).toEpochMilli(),
            endTime = LocalDateTime.of(2026, 11, 11, 7, 0).toInstant(zone).toEpochMilli(),
            alarmTriggerTime = LocalDateTime.of(2026, 11, 10, 14, 0).toInstant(zone).toEpochMilli()
        )

        fun balken(e: TypEntwurf) =
            dndBalken(e, "AD", config, quellen(config, "AD").copy(spannen = Quelle.Bekannt(listOf(spanne))))

        // Gespeichert „Endet spaetestens um“ 05:00 (Folgetag = Minute 2100), im Entwurf auf 06:00 (2160).
        assertEquals(listOf(1680 to 2100), balken(entwurf))
        assertEquals(listOf(1680 to 2160), balken(entwurf.mitNichtStoeren { it.copy(endetSpaetestensMinuten = 6 * 60) }))
    }

    @Test
    fun `die Neuanlage zeigt Nicht stoeren aus dem Entwurf`() {
        // Der Typ steht noch nicht in der Konfiguration: „Nicht stören“ gibt es nur im Entwurf.
        val config = ShiftConfig(definitions = listOf(typ()))
        fun balken(e: TypEntwurf) = dndBalken(e, null, config)

        // „Solange gedimmt wird“ mit „Nacht davor“: 22:00 am Vortag (Minute 240) bis zum Wecker 04:30 (Minute 630).
        val dienst = TypEntwurf.aus(typ(name = "N")).copy(dimmen = DimmenEntwurf(an = true, fenster = listOf(nachtDavor)))
        assertEquals(emptyList<Pair<Int, Int>>(), balken(dienst))
        assertEquals(listOf(240 to 630), balken(dienst.mitNichtStoeren { it.copy(solangeGedimmt = true) }))

        // „Während des Kalendereintrags“: ohne Spanne rechnet die Leiste Urlaub und Frei ganztägig. Ein neuer
        // Dienst-Typ hat dagegen noch keine Dienstzeit, dort gibt es keinen Balken.
        listOf(ShiftKategorie.URLAUB, ShiftKategorie.FREI).forEach { kategorie ->
            val ganztag = wirksameDienstzeit(kategorie, null)!!
            val entwurf = TypEntwurf.aus(typ(kategorie = kategorie, name = "U"))
                .mitNichtStoeren { it.copy(waehrendEintrag = true) }
            assertEquals(kategorie.name, listOf(ganztag.beginnMin to ganztag.endeMin), balken(entwurf))
        }
    }

    @Test
    fun `nur der eigene Typ bekommt den Entwurf, auch ueber die feste Zeile`() {
        val f = typ()
        val s = typ(name = "S")
        val config = ShiftConfig(definitions = listOf(f, s), ohneKalendereintragWie = f.id)
        val entwurf = NichtStoerenEntwurf(true, true, 300)

        // Der Typ wird nach dem GESPEICHERTEN Namen gesucht, Gross/Klein egal.
        val eigener = TagesleisteKarteLogik.einstellungenAus(config, "f", null, entwurf)
        assertEquals(entwurf, NichtStoerenEntwurf.aus(eigener.nichtStoeren))
        assertTrue(eigener.freierTag.nichtStoerenSolangeGedimmt)

        // Der Entwurf eines anderen Typs gilt nur dort: die feste Zeile „wie F“ bleibt beim Gespeicherten von F.
        val anderer = TagesleisteKarteLogik.einstellungenAus(config, "S", null, entwurf)
        assertEquals(entwurf, NichtStoerenEntwurf.aus(anderer.nichtStoeren))
        assertFalse(anderer.freierTag.nichtStoerenSolangeGedimmt)

        // Ohne den Teil im Entwurf gilt das Gespeicherte.
        val ohne = TagesleisteKarteLogik.einstellungenAus(config, "F", null)
        assertEquals(f.nichtStoeren, ohne.nichtStoeren)
        assertFalse(ohne.freierTag.nichtStoerenSolangeGedimmt)
    }
}
