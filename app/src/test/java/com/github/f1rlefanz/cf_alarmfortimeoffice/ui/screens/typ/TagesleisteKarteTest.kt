package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.Blockposition
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimAnchor
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRule
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindow
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.ActionType
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueLightAction
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueSchedule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueTimeRange
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.TargetType
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.SchichttypEinordnen
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpan
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.Balken
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.DimmerQuelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.Hinweis
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.LichtQuelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.NichtStoerenQuelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.Quelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.Spur
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.Tagesleiste
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.TagesleisteQuellen
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.HellAb
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.Ort
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.OrtQuelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.TageslichtStand
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.DarkColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.max
import kotlin.math.min

/**
 * Die Rechnung der Karte „So läuft ein Tag“ (Umbau #197), ohne Compose: aus dem Entwurf der Typ-Seite und
 * den Quellen (#196) wird mit den Adaptern aus #193 bis #195 die Leiste. Alle Minuten sind Minuten ab 18:00
 * am Vortag (0..2880) und stehen als LITERALE da; der Mustertag ist ein Frühdienst 06:00 bis 14:15 mit
 * Wecker um 04:30 (Minute 630).
 */
class TagesleisteKarteTest {

    private val zone = ZoneOffset.UTC
    private val jetzt = LocalDateTime.of(2026, 11, 9, 12, 0).toInstant(zone).toEpochMilli()

    private fun span(name: String, von: LocalDateTime, bis: LocalDateTime) = ShiftSpan(
        shiftName = name,
        startTime = von.toInstant(zone).toEpochMilli(),
        endTime = bis.toInstant(zone).toEpochMilli(),
        alarmTriggerTime = von.toInstant(zone).toEpochMilli()
    )

    private val fruehSpan = span("F", LocalDateTime.of(2026, 11, 10, 6, 0), LocalDateTime.of(2026, 11, 10, 14, 15))

    private val keinDnd = DndPrefs.Toggles(followDimmerEnabled = false, duringShiftEnabled = false)

    private fun entwurf(
        name: String = "F",
        kategorie: ShiftKategorie = ShiftKategorie.DIENST,
        weckzeit: String = "04:30",
        weckerAn: Boolean = true,
        still: Boolean = false,
        isEnabled: Boolean = true
    ) = TypEntwurf(
        name = name, muster = listOf(name), weckzeitText = weckzeit, isEnabled = isEnabled, isSilent = still,
        weckerAn = weckerAn, kategorie = kategorie
    )

    private fun quellen(
        spannen: Quelle<List<ShiftSpan>> = Quelle.Bekannt(listOf(fruehSpan)),
        dimmer: Quelle<DimmerQuelle> = Quelle.Bekannt(DimmerQuelle(an = true, regeln = emptyList(), regelwahl = DimRuleUseCase(mock()))),
        nichtStoeren: Quelle<NichtStoerenQuelle> = Quelle.Bekannt(NichtStoerenQuelle(keinDnd, emptySet(), 300)),
        licht: Quelle<LichtQuelle> = Quelle.Bekannt(LichtQuelle(konfiguriert = false, regeln = emptyList(), vorlaufMinuten = { _, _, _, _ -> null }))
    ) = TagesleisteQuellen(dimmer, nichtStoeren, licht, spannen)

    private fun rechne(
        e: TypEntwurf = entwurf(),
        gespeichert: String? = "F",
        q: TagesleisteQuellen? = quellen(),
        position: Blockposition = Blockposition.EINZELNER
    ) = TagesleisteKarteLogik.rechne(e, gespeichert, q, position, jetzt, zone)

    /** "Nacht davor": von 22:00 am Vortag bis zum Wecker. */
    private val nachtDavor = DimWindow(
        startAnchor = DimAnchor.CLOCK, startClockMinutes = 22 * 60, endAnchor = DimAnchor.ALARM, endOffsetMinutes = 0
    )

    private fun dimQuelle(vararg fenster: DimWindow, name: String = "F") = Quelle.Bekannt(
        DimmerQuelle(
            an = true,
            regeln = listOf(DimRule(id = "r-$name", name = name, shiftPattern = name, windows = fenster.toList(), strength = 55)),
            regelwahl = DimRuleUseCase(mock())
        )
    )

    private fun Tagesleiste.spur(spur: Spur) = balken.filter { it.spur == spur }

    // ── Die Abnahme-Zeilen des Issues ────────────────────────────────────────────────────────

    @Test
    fun `eine geaenderte Weckzeit im Entwurf verschiebt den Weckpunkt und die Ereignisliste`() {
        val um0430 = rechne(entwurf(weckzeit = "04:30")).leiste
        val um0530 = rechne(entwurf(weckzeit = "05:30")).leiste

        assertEquals(630, um0430.weckpunkte.single().minute)
        assertEquals(690, um0530.weckpunkte.single().minute)
        assertEquals("04:30" to "Wecker – F", um0430.ereignisse.single { it.spur == Spur.WECKER }.let { it.zeit to it.text })
        assertEquals("05:30" to "Wecker – F", um0530.ereignisse.single { it.spur == Spur.WECKER }.let { it.zeit to it.text })
    }

    @Test
    fun `eine geaenderte Weckzeit verschiebt auch das Ende des Dimmens beim Wecker`() {
        val q = quellen(dimmer = dimQuelle(nachtDavor))

        assertEquals(listOf(Balken(Spur.DIMMEN, 240, 630)), rechne(entwurf(weckzeit = "04:30"), q = q).leiste.spur(Spur.DIMMEN))
        assertEquals(listOf(Balken(Spur.DIMMEN, 240, 690)), rechne(entwurf(weckzeit = "05:30"), q = q).leiste.spur(Spur.DIMMEN))
    }

    @Test
    fun `die Legende nennt alle sechs Spuren in der Reihenfolge des Prototyps`() {
        assertEquals(
            listOf("Eintrag", "Dimmen", "Nicht stören", "Licht", "Ruf-Abfrage", "Wecker"),
            Spur.entries.map { TagesleisteTexte.spur(it) }
        )
    }

    @Test
    fun `bei Ignorieren gibt es keine Karte - bei jeder anderen Kategorie schon`() {
        assertFalse(TagesleisteKarteLogik.zeigtKarte(ShiftKategorie.IGNORIEREN))
        ShiftKategorie.entries.filter { it != ShiftKategorie.IGNORIEREN }.forEach {
            assertTrue("$it", TagesleisteKarteLogik.zeigtKarte(it))
        }
        // Und selbst wenn man sie rechnete: Ignorieren ergibt keine Leiste.
        assertEquals(Tagesleiste.LEER, rechne(entwurf(kategorie = ShiftKategorie.IGNORIEREN)).leiste)
    }

    // ── Der Schalter „Schichtdefinition aktiviert“ ───────────────────────────────────────────

    private fun hueRegel(hellAb: HellAb = HellAb.IMMER) = HueSchedule(
        id = "r1", name = "F", shiftPattern = "F", enabled = true, hellAb = hellAb,
        timeRanges = listOf(
            HueTimeRange(
                listOf(
                    HueLightAction(
                        targetType = TargetType.GROUP, targetId = "1", actionType = ActionType.TURN_ON,
                        on = true, duration = 20, isGroup = true
                    )
                )
            )
        )
    )

    /** Alles, was auf die Leiste kaeme: Spanne, Dimm-Regel und Hue-Regel mit Bridge. */
    private fun vollBestueckt() = quellen(
        dimmer = dimQuelle(nachtDavor),
        licht = Quelle.Bekannt(LichtQuelle(true, listOf(hueRegel())) { _, _, _, _ -> null })
    )

    private fun assertAusgeschaltet(anzeige: TagesleisteAnzeige) {
        assertTrue(anzeige.gelesen)
        assertTrue(anzeige.typAusgeschaltet)
        assertEquals(Tagesleiste.LEER, anzeige.leiste)
        assertEquals(emptyList<Spur>(), anzeige.unbekannt)
        // Die Laufzeit erkennt den Typ gar nicht (`ShiftRecognitionEngine`): „Im Kalender steht noch kein Termin“
        // waere falsch, denn am Kalender liegt es nicht.
        assertNull(TagesleisteTexte.leistenHinweis(anzeige.leiste.hinweis, anzeige.typNochNichtAngelegt))
    }

    @Test
    fun `ein ausgeschalteter Typ zeigt keinen Ablauf - der Schalter wirkt sofort`() {
        assertAusgeschaltet(rechne(entwurf(isEnabled = false), q = vollBestueckt()))

        // Derselbe Entwurf eingeschaltet: Weckpunkt, Dimmen und Licht sind da.
        val an = rechne(entwurf(isEnabled = true), q = vollBestueckt())
        assertFalse(an.typAusgeschaltet)
        assertEquals(630, an.leiste.weckpunkte.single().minute)
        assertEquals(listOf(Balken(Spur.DIMMEN, 240, 630)), an.leiste.spur(Spur.DIMMEN))
        assertEquals(listOf(Balken(Spur.LICHT, 630, 650)), an.leiste.spur(Spur.LICHT))
    }

    @Test
    fun `ein ausgeschalteter Typ braucht keine Quelle - unlesbar und noch nicht gelesen sagen nichts`() {
        assertAusgeschaltet(rechne(entwurf(isEnabled = false), q = null))
        assertAusgeschaltet(rechne(entwurf(isEnabled = false), q = quellen(spannen = Quelle.Unbekannt)))
        assertAusgeschaltet(
            rechne(
                entwurf(isEnabled = false),
                q = TagesleisteQuellen(Quelle.Unbekannt, Quelle.Unbekannt, Quelle.Unbekannt, Quelle.Unbekannt)
            )
        )
    }

    @Test
    fun `der Hinweis eines ausgeschalteten Typs verspricht kein Mitrechnen`() {
        ShiftKategorie.entries.forEach { kategorie ->
            val text = TagesleisteTexte.hinweis(kategorie, typAusgeschaltet = true)
            assertEquals("$kategorie", TagesleisteTexte.AUSGESCHALTET, text)
            assertFalse("$kategorie", text.contains("rechnet sofort mit"))
        }
    }

    @Test
    fun `eine Neuanlage ist nicht ausgeschaltet - ausser der Nutzer legt den Schalter im Entwurf um`() {
        val ausPlus = TypEntwurf.aus(ShiftEditFormular.leererTyp())
        val ausFundCode = TypEntwurf.aus(SchichttypEinordnen.neuerTypAusCode("F", ShiftKategorie.DIENST))

        assertFalse(rechne(ausPlus, gespeichert = null).typAusgeschaltet)
        assertFalse(rechne(ausFundCode, gespeichert = null).typAusgeschaltet)
        assertTrue(rechne(ausFundCode.copy(isEnabled = false), gespeichert = null).typAusgeschaltet)
    }

    // ── Tageslicht: dieselbe Bedingung wie zur Laufzeit, keine zweite Rechnung ────────────────

    private val berlin = TageslichtStand(Ort(52.52, 13.40, OrtQuelle.PLZ, null), ZoneId.of("Europe/Berlin"))

    /** Minute des Tages, zu der es in Berlin am Tag von [jetzt] (9.11.2026) hell wird: rund 07:15. */
    private fun sonnenaufgangBerlin(): Int =
        Instant.ofEpochMilli(berlin.hellZeitpunkt(LocalDate.of(2026, 11, 9), HellAb.SONNENAUFGANG)!!)
            .atZone(berlin.zone).toLocalTime().let { it.hour * 60 + it.minute }

    @Test
    fun `Licht nur bei Dunkelheit - mit Ort entfaellt es bei einem Wecker im Hellen, ohne Ort bleibt es`() {
        val sonnenaufgang = sonnenaufgangBerlin()
        assertTrue("04:30 liegt vor dem Sonnenaufgang", 4 * 60 + 30 < sonnenaufgang)
        assertTrue("10:00 liegt nach dem Sonnenaufgang", sonnenaufgang < 10 * 60)
        val licht = Quelle.Bekannt(LichtQuelle(true, listOf(hueRegel(HellAb.SONNENAUFGANG))) { _, _, _, _ -> null })
        val spaetSpan = span("F", LocalDateTime.of(2026, 11, 10, 14, 0), LocalDateTime.of(2026, 11, 10, 22, 0))
        val frueh = quellen(licht = licht)
        val spaet = quellen(spannen = Quelle.Bekannt(listOf(spaetSpan)), licht = licht)

        assertEquals(listOf(Balken(Spur.LICHT, 630, 650)), rechne(q = frueh.copy(tageslicht = berlin)).leiste.spur(Spur.LICHT))
        assertEquals(
            emptyList<Balken>(),
            rechne(entwurf(weckzeit = "10:00"), q = spaet.copy(tageslicht = berlin)).leiste.spur(Spur.LICHT)
        )
        // Ohne Ort wird die Bedingung nicht behauptet (fail-safe wie zur Laufzeit).
        assertEquals(listOf(Balken(Spur.LICHT, 960, 980)), rechne(entwurf(weckzeit = "10:00"), q = spaet).leiste.spur(Spur.LICHT))
    }

    @Test
    fun `Dimm-Nachlauf bis hell - mit Ort endet er beim Sonnenaufgang, ohne Ort laeuft er voll`() {
        val sonnenaufgang = sonnenaufgangBerlin()
        assertTrue("Der Sonnenaufgang liegt im Nachlauf 07:00 bis 08:00", 7 * 60 < sonnenaufgang && sonnenaufgang < 8 * 60)
        val fenster = DimWindow(
            startAnchor = DimAnchor.CLOCK, startClockMinutes = 22 * 60,
            endAnchor = DimAnchor.ALARM_SONST_CLOCK, endClockMinutes = 9 * 60,
            weckzeitNachlaufMinuten = 60, nachlaufBisHell = HellAb.SONNENAUFGANG
        )
        val spanne = span("F", LocalDateTime.of(2026, 11, 10, 8, 0), LocalDateTime.of(2026, 11, 10, 16, 0))
        val q = quellen(spannen = Quelle.Bekannt(listOf(spanne)), dimmer = dimQuelle(fenster))

        val ohneOrt = rechne(entwurf(weckzeit = "07:00"), q = q, position = Blockposition.MITTLERER).leiste.spur(Spur.DIMMEN)
        val mitOrt = rechne(entwurf(weckzeit = "07:00"), q = q.copy(tageslicht = berlin), position = Blockposition.MITTLERER)
            .leiste.spur(Spur.DIMMEN)

        // Der Wecker des Folgetags (Minute 1800 = dessen 0:00): 07:00 ist 2220, der volle Nachlauf endet 2280.
        assertEquals(2280, ohneOrt.single().bisMin)
        assertEquals(1800 + sonnenaufgang, mitOrt.single().bisMin)
    }

    // ── Lesefehler sagen es, statt leer zu bleiben (#196) ────────────────────────────────────

    @Test
    fun `solange nichts gelesen ist, steht keine Leiste da - und keine Spur gilt als unbekannt`() {
        val anzeige = rechne(q = null)
        assertFalse(anzeige.gelesen)
        assertEquals(emptyList<Spur>(), anzeige.unbekannt)
    }

    @Test
    fun `unlesbare Hue-Regeln machen nur die Licht-Spur unbekannt - alles andere wird gerechnet`() {
        val anzeige = rechne(q = quellen(licht = Quelle.Unbekannt))

        assertTrue(anzeige.gelesen)
        assertEquals(listOf(Spur.LICHT), anzeige.unbekannt)
        assertEquals(630, anzeige.leiste.weckpunkte.single().minute)
        assertEquals(listOf(Balken(Spur.EINTRAG, 360 + 360, 360 + 855)), anzeige.leiste.spur(Spur.EINTRAG))
    }

    @Test
    fun `unlesbare Spannen machen jede Spur unbekannt - und behaupten keinen fehlenden Termin`() {
        val anzeige = rechne(q = quellen(spannen = Quelle.Unbekannt))

        assertEquals(Spur.entries, anzeige.unbekannt)
        assertEquals(Tagesleiste.LEER, anzeige.leiste)
        assertNull("Nicht lesbar ist nicht 'kein Termin'", anzeige.leiste.hinweis)
    }

    @Test
    fun `eine unlesbare Dimm-Quelle macht Dimmen und Nicht stoeren unbekannt`() {
        val anzeige = rechne(q = quellen(dimmer = Quelle.Unbekannt))

        assertEquals(listOf(Spur.DIMMEN, Spur.NICHT_STOEREN), anzeige.unbekannt)
        assertEquals(emptyList<Balken>(), anzeige.leiste.spur(Spur.DIMMEN) + anzeige.leiste.spur(Spur.NICHT_STOEREN))
        assertEquals(630, anzeige.leiste.weckpunkte.single().minute)
    }

    @Test
    fun `eine unlesbare Nicht-stoeren-Quelle laesst Dimmen stehen`() {
        val anzeige = rechne(q = quellen(dimmer = dimQuelle(nachtDavor), nichtStoeren = Quelle.Unbekannt))

        assertEquals(listOf(Spur.NICHT_STOEREN), anzeige.unbekannt)
        assertEquals(listOf(Balken(Spur.DIMMEN, 240, 630)), anzeige.leiste.spur(Spur.DIMMEN))
    }

    // ── Verdrahtung der Quellen ──────────────────────────────────────────────────────────────

    @Test
    fun `Hue-Regeln und Bridge kommen als Licht-Spur auf die Leiste`() {
        val regel = HueSchedule(
            id = "r1", name = "F", shiftPattern = "F", enabled = true,
            timeRanges = listOf(
                HueTimeRange(
                    listOf(
                        HueLightAction(
                            targetType = TargetType.GROUP, targetId = "1", actionType = ActionType.TURN_ON,
                            on = true, duration = 20, isGroup = true
                        )
                    )
                )
            )
        )
        val mitBridge = quellen(licht = Quelle.Bekannt(LichtQuelle(true, listOf(regel)) { _, _, _, _ -> null }))
        val ohneBridge = quellen(licht = Quelle.Bekannt(LichtQuelle(false, listOf(regel)) { _, _, _, _ -> null }))

        assertEquals(listOf(Balken(Spur.LICHT, 630, 650)), rechne(q = mitBridge).leiste.spur(Spur.LICHT))
        assertEquals(emptyList<Balken>(), rechne(q = ohneBridge).leiste.spur(Spur.LICHT))
    }

    @Test
    fun `Nicht stoeren waehrend des Eintrags kommt aus den Schaltern der Quelle`() {
        val an = quellen(nichtStoeren = Quelle.Bekannt(NichtStoerenQuelle(DndPrefs.Toggles(false, true), emptySet(), 300)))

        assertEquals(listOf(Balken(Spur.NICHT_STOEREN, 360 + 360, 360 + 855)), rechne(q = an).leiste.spur(Spur.NICHT_STOEREN))
        assertEquals(emptyList<Balken>(), rechne(q = quellen()).leiste.spur(Spur.NICHT_STOEREN))
    }

    @Test
    fun `der Ruf-Cutoff der Quelle kappt Nicht stoeren - nicht die Vorgabe 05 Uhr`() {
        val ruf = span("AD", LocalDateTime.of(2026, 11, 10, 21, 0), LocalDateTime.of(2026, 11, 11, 7, 0))
        val toggles = DndPrefs.Toggles(followDimmerEnabled = false, duringShiftEnabled = true)
        fun dnd(cutoffMinuten: Int) = rechne(
            e = entwurf(name = "AD", kategorie = ShiftKategorie.RUFBEREITSCHAFT, weckerAn = false),
            gespeichert = "AD",
            q = quellen(
                spannen = Quelle.Bekannt(listOf(ruf)),
                nichtStoeren = Quelle.Bekannt(NichtStoerenQuelle(toggles, emptySet(), cutoffMinuten))
            )
        ).leiste.spur(Spur.NICHT_STOEREN)

        // Beginn 21:00 am Mustertag = Minute 1620; der Cutoff liegt am Folgetag: 05:00 = 2100, 06:00 = 2160.
        assertEquals(listOf(Balken(Spur.NICHT_STOEREN, 1620, 2100)), dnd(300))
        assertEquals(listOf(Balken(Spur.NICHT_STOEREN, 1620, 2160)), dnd(360))
    }

    @Test
    fun `die Dienstzeit kommt aus der Spanne des GESPEICHERTEN Namens - ein Entwurfsname findet nichts`() {
        // Umbenennen im Entwurf: Spanne, Dimm- und Hue-Regeln hängen noch am alten Namen (der Nachzug läuft erst beim Speichern).
        val umbenannt = rechne(entwurf(name = "Frueh"), gespeichert = "F").leiste
        assertEquals(listOf(Balken(Spur.EINTRAG, 720, 1215)), umbenannt.spur(Spur.EINTRAG))
        assertNull(umbenannt.hinweis)

        // Neuanlage: der Typ ist nicht gespeichert, also gibt es keine Spanne (die Karte formuliert den Hinweis um: siehe unten).
        val neu = rechne(entwurf(name = "F"), gespeichert = null).leiste
        assertEquals(emptyList<Balken>(), neu.spur(Spur.EINTRAG))
        assertEquals(Hinweis.KEIN_TERMIN_IM_KALENDER, neu.hinweis)
    }

    @Test
    fun `ein noch nicht angelegter Typ behauptet nichts ueber den Kalender - ein angelegter ohne Spanne schon`() {
        // Der Banner nennt den Code „aus dem Kalender“: „Im Kalender steht noch kein Termin“ widerspraeche ihm.
        val neu = rechne(entwurf(name = "F"), gespeichert = null)
        assertEquals(true, neu.typNochNichtAngelegt)
        assertEquals(TagesleisteTexte.NEUER_TYP_OHNE_EINTRAG, TagesleisteTexte.leistenHinweis(neu.leiste.hinweis, neu.typNochNichtAngelegt))
        assertEquals(false, TagesleisteTexte.NEUER_TYP_OHNE_EINTRAG.contains("Kalender"))

        // Ein gespeicherter Typ ohne Spanne im Bestand: dort stimmt der Satz ueber den Kalender.
        val angelegt = rechne(entwurf(name = "ZZ"), gespeichert = "ZZ")
        assertEquals(false, angelegt.typNochNichtAngelegt)
        assertEquals(Hinweis.KEIN_TERMIN_IM_KALENDER.text, TagesleisteTexte.leistenHinweis(angelegt.leiste.hinweis, angelegt.typNochNichtAngelegt))
        assertEquals(null, TagesleisteTexte.leistenHinweis(null, true))
    }

    @Test
    fun `ein neuer Typ ohne Namen heisst Neuer Typ - der Titel hat nie leere Anfuehrungszeichen`() {
        val leer = entwurf(name = "  ")
        assertEquals("Neuer Typ", TagesleisteKarteLogik.anzeigeName(null, leer))
        assertEquals("Wecker – Neuer Typ", rechne(leer, gespeichert = null).leiste.ereignisse.single { it.spur == Spur.WECKER }.text)
        // Getippt gewinnt, gespeichert gewinnt vor getippt.
        assertEquals("X", TagesleisteKarteLogik.anzeigeName(null, entwurf(name = " X ")))
        assertEquals("F", TagesleisteKarteLogik.anzeigeName("F", entwurf(name = "Frueh")))
    }

    // ── Blockposition ────────────────────────────────────────────────────────────────────────

    @Test
    fun `die Chips der Blockposition gibt es nur bei Dienst und Rufbereitschaft`() {
        assertEquals(
            setOf(ShiftKategorie.DIENST, ShiftKategorie.RUFBEREITSCHAFT),
            ShiftKategorie.entries.filter { TagesleisteKarteLogik.zeigtBlockwahl(it) }.toSet()
        )
        assertEquals(
            listOf("Erster Tag im Block", "Mittendrin", "Letzter", "Einzeln"),
            TagesleisteKarteLogik.BLOCKPOSITIONEN.map { TagesleisteTexte.block(it) }
        )
    }

    @Test
    fun `ohne Wahl gilt die Vorgabe der Kategorie - eine Wahl nur, wo es die Chips gibt`() {
        val k = TagesleisteKarteLogik
        assertEquals(Blockposition.MITTLERER, k.wirksameBlockposition(ShiftKategorie.DIENST, null))
        assertEquals(Blockposition.ERSTER, k.wirksameBlockposition(ShiftKategorie.DIENST, Blockposition.ERSTER))
        assertEquals(Blockposition.EINZELNER, k.wirksameBlockposition(ShiftKategorie.FREI, null))
        // Wer die Kategorie von Dienst auf Frei wechselt, behaelt keine unsichtbare Wahl.
        assertEquals(Blockposition.EINZELNER, k.wirksameBlockposition(ShiftKategorie.FREI, Blockposition.LETZTER))
    }

    @Test
    fun `die gewaehlte Blockposition filtert die Dimm-Fenster`() {
        val nurAmErsten = nachtDavor.copy(blockPositionen = setOf(Blockposition.ERSTER))
        val q = quellen(dimmer = dimQuelle(nurAmErsten))

        assertEquals(listOf(Balken(Spur.DIMMEN, 240, 630)), rechne(q = q, position = Blockposition.ERSTER).leiste.spur(Spur.DIMMEN))
        assertEquals(emptyList<Balken>(), rechne(q = q, position = Blockposition.MITTLERER).leiste.spur(Spur.DIMMEN))
    }

    // ── Texte ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `der Hinweis verspricht das Mitrechnen nur, wo darunter der Wecker steht`() {
        val mitWecker = TagesleisteTexte.hinweis(ShiftKategorie.DIENST)
        assertEquals("Von 18 Uhr am Vortag bis 18 Uhr am Folgetag. Ändere unten den Wecker – die Leiste rechnet sofort mit.", mitWecker)

        // Krank hat keine Karte „Wecker“: dort ist unten nichts einstellbar.
        assertEquals("Von 18 Uhr am Vortag bis 18 Uhr am Folgetag.", TagesleisteTexte.hinweis(ShiftKategorie.KRANK))
    }

    @Test
    fun `der Titel nennt den Namen in deutschen Anfuehrungszeichen`() {
        assertEquals("So läuft ein Tag „Frühschicht“", TagesleisteTexte.titel("Frühschicht"))
    }

    @Test
    fun `eine unlesbare Spur wird beim Namen genannt`() {
        assertEquals(
            "Gerade nicht lesbar: Dimmen, Licht. Diese Spuren fehlen hier – das heißt nicht, dass dort nichts geschieht.",
            TagesleisteTexte.nichtLesbar(listOf(Spur.DIMMEN, Spur.LICHT))
        )
    }

    // ── Farben ───────────────────────────────────────────────────────────────────────────────

    private fun kontrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    @Test
    fun `im Dunkelmodus hat jede Spurfarbe Kontrast zur Leiste und zur Karte`() {
        Spur.entries.forEach { spur ->
            val farbe = LeistenFarben.farbe(spur, dunkel = true)
            listOf("Leiste" to DarkColorScheme.surfaceContainer, "Karte" to DarkColorScheme.surface).forEach { (wo, grund) ->
                val k = kontrast(farbe, grund)
                assertTrue("$spur auf $wo: Kontrast %.2f < 3".format(k), k >= 3.0)
            }
        }
    }

    @Test
    fun `hell sind es die Farben des Prototyps`() {
        val erwartet = mapOf(
            Spur.EINTRAG to 0xFF2D2A28, Spur.DIMMEN to 0xFF6B3FA0, Spur.NICHT_STOEREN to 0xFF2F6FB0,
            Spur.LICHT to 0xFFE39B1B, Spur.RUF_ABFRAGE to 0xFF4E2A6B, Spur.WECKER to 0xFFE2001A
        )
        erwartet.forEach { (spur, farbe) -> assertEquals("$spur", Color(farbe), LeistenFarben.farbe(spur, dunkel = false)) }
    }

    @Test
    fun `die Frage beim Verlassen ueber den Link nennt den Bereich Wecker statt zurueck`() {
        assertTrue(TypSeiteTexte.VERWERFEN_WOCHE_TEXT.contains("Öffnest du den Bereich „Wecker“"))
        assertFalse(TypSeiteTexte.VERWERFEN_WOCHE_TEXT.contains("zurück"))
        assertTrue(TypSeiteTexte.neuerTypVerwerfenText(null, zurWoche = true).contains("Öffnest du den Bereich „Wecker“"))
        // Der Standardweg bleibt, wie er war.
        assertTrue(TypSeiteTexte.neuerTypVerwerfenText(null).contains("Gehst du zurück"))
    }
}
