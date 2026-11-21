package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import com.github.f1rlefanz.cf_alarmfortimeoffice.backup.ConfigBackup
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.TypEinstellungenUebersetzung.Altbestand
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypDimmen
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypNichtStoeren
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.HellAb
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.mockito.kotlin.mock
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Umbau Etappe 3, Paket #214 (SPEC Abschnitt 4): die heutigen Dimm-Regeln und Schalter werden
 * verlustfrei in die Einstellungen am Typ und an der festen Zeile „Ohne Kalendereintrag“ uebersetzt.
 *
 * Belegt an zwei Vorlagen im Exportformat (`ConfigBackup`):
 * - `umbau/vorlage-nachgebaut-2026-10-16.json` (im Repo, laeuft in der CI) - dieselben Faelle wie die
 *   echte Vorlage mit ERFUNDENEN Kuerzeln, dazu ein Cutoff ungleich 05:00 und ein Name in der
 *   DND-Ausnahmenliste, damit beide Uebernahmen nicht zufaellig mit einem Default uebereinstimmen.
 * - die echte Datei des Eigentuemers unter `..Projektdateien/umbau/testvorlagen/` (nur lokal). Fehlt
 *   sie, wird ihr Test per `Assume` mit Meldung UEBERSPRUNGEN, nie still gruen.
 *
 * Die Regel-Auswahl ist die ECHTE ([DimRuleUseCase.findRuleForShift] / [DimRuleUseCase.findRuleForFreeDay]),
 * nicht nachgebaut. Die Gleichwertigkeit wird ueber den echten [DimWindowResolver.buildRuleSpans]
 * gezeigt: alte Regeln gegen die aus den Typen zurueckgebauten Regeln (siehe [rueckbau]).
 */
class TypEinstellungenUebersetzungTest {

    private val auswahl = DimRuleUseCase(mock())

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // --- Die Vorlagen ------------------------------------------------------------------------

    private data class Vorlage(val config: ShiftConfig, val alt: Altbestand)

    private fun nachgebaut(): Vorlage {
        val text = checkNotNull(javaClass.classLoader?.getResource(NACHGEBAUT)) { "$NACHGEBAUT fehlt" }.readText()
        return lies(text)
    }

    /**
     * Liest eine Exportdatei wie die App ihre Stores: fehlende Schluessel mit den Defaults der Stores
     * (`dim_enabled` aus, beide DND-Schalter aus, Ausnahmen leer, Cutoff 05:00).
     */
    private fun lies(text: String): Vorlage {
        val backup = json.decodeFromString(ConfigBackup.serializer(), text)
        val config = checkNotNull(backup.shiftConfig) { "Vorlage ohne shiftConfig" }
        val s = backup.settings
        fun schalter(schluessel: String) = s[schluessel]?.value?.toBooleanStrict() ?: false
        val regelnRoh = checkNotNull(s["dim_rules"]?.value) { "Vorlage ohne dim_rules" }
        val regeln = json.decodeFromString<List<DimRule>>(regelnRoh)
        assertTrue("die Vorlage hat Regeln", regeln.isNotEmpty())
        return Vorlage(
            config,
            Altbestand(
                regeln = regeln,
                dimmenAn = schalter("dim_enabled"),
                dndSolangeGedimmt = schalter("dnd_follow_dimmer_enabled"),
                dndWaehrendEintrag = schalter("dnd_during_shift_enabled"),
                dndAusnahmen = s["dnd_shift_excluded_shifts"]?.setValue.orEmpty().toSet(),
                rufCutoffMinuten = s["dnd_oncall_cutoff_min"]?.value?.toInt() ?: STANDARD_CUTOFF
            )
        )
    }

    private fun uebersetze(v: Vorlage): ShiftConfig = TypEinstellungenUebersetzung.uebersetze(v.config, v.alt, auswahl)

    private fun ShiftConfig.typ(name: String): ShiftDefinition = definitions.single { it.name == name }

    // --- Die Faelle der Vorlage --------------------------------------------------------------

    @Test
    fun `nachgebaute Vorlage - Nachtschicht mit drei Fenstern, uebrige Typen und feste Zeile mit UNIVERSAL, Abruf mit Cutoff, Krank leer`() {
        val v = nachgebaut()
        val neu = pruefeVorlagenFaelle(v)

        // Die diskriminierenden Werte der nachgebauten Vorlage: kein Default trifft sie zufaellig.
        assertEquals(330, neu.typ("Abrufdienst").nichtStoeren.endetSpaetestensMinuten)
        assertFalse("Teildienst steht in der Ausnahmenliste", neu.typ("Teildienst").nichtStoeren.waehrendEintrag)
        assertTrue(neu.typ("Morgendienst").nichtStoeren.waehrendEintrag)
        assertTrue(neu.typ("Abrufdienst").nichtStoeren.waehrendEintrag)
    }

    @Test
    fun `echte Vorlage des Eigentuemers - dieselben Faelle und gleichwertige Spannen, falls lokal vorhanden`() {
        val kandidaten = kandidatenFuerEchteVorlage()
        val datei = kandidaten.firstOrNull { it.isFile }
        assumeTrue(
            "Echte Testvorlage nicht vorhanden (nur lokal beim Eigentuemer) - uebersprungen. Gesucht: " +
                kandidaten.joinToString { it.path },
            datei != null
        )
        val v = lies(datei!!.readText(Charsets.UTF_8))

        pruefeVorlagenFaelle(v)
        pruefeGleichwertigkeit(v)
        pruefeGleichwertigkeit(v.copy(alt = v.alt.copy(dimmenAn = false)))
    }

    /**
     * Die Faelle, die beide Vorlagen gemeinsam haben (Abnahme #214). Konkrete Werte, damit ein
     * vertauschtes Feld auffaellt, und dazu der Vergleich mit der Regel aus der Eingabe - ueber ihr
     * `shiftPattern`, NICHT ueber die Auswahl-Logik, die hier geprueft wird.
     */
    private fun pruefeVorlagenFaelle(v: Vorlage): ShiftConfig {
        val neu = uebersetze(v)
        assertEquals(TypEinstellungenUebersetzung.MODELL_STUFE, neu.modellStufe)

        val universal = v.alt.regeln.single { it.shiftPattern == DimRule.SHIFT_UNIVERSAL }
        val nachtRegel = v.alt.regeln.single { it.shiftPattern == "Nachtschicht" }
        assertTrue("Vorlage ohne FREI-Regel", v.alt.regeln.none { it.shiftPattern == DimRule.SHIFT_FREE })

        // „Nachtschicht“: drei Fenster, Staerke 55 an jedem, Waerme 40 am Typ, Blockpositionen 1:1.
        val nacht = neu.typ("Nachtschicht").dimmen
        assertEquals(3, nacht.fenster.size)
        assertEquals(nachtRegel.windows, nacht.fenster.map { it.fenster })
        assertEquals(listOf(55, 55, 55), nacht.fenster.map { it.staerke })
        assertEquals(40, nacht.farbwaerme)
        assertEquals(
            listOf(
                setOf(Blockposition.ERSTER, Blockposition.MITTLERER),
                setOf(Blockposition.LETZTER, Blockposition.EINZELNER),
                Blockposition.ALLE
            ),
            nacht.fenster.map { it.fenster.blockPositionen }
        )

        // Alle uebrigen Typen ausser Krank und die feste Zeile: das UNIVERSAL-Fenster (67, 0, Nachlauf 20).
        val uebrige = neu.definitions.filter { it.name != "Nachtschicht" && it.kategorie != ShiftKategorie.KRANK }
        assertEquals("Vorlage mit fuenf uebrigen Typen", 5, uebrige.size)
        for (d in uebrige.map { it.dimmen } + neu.ohneKalendereintrag.dimmen) {
            assertEquals(universal.windows, d.fenster.map { it.fenster })
            assertEquals(listOf(67), d.fenster.map { it.staerke })
            assertEquals(0, d.farbwaerme)
            assertEquals(20, d.fenster.single().fenster.weckzeitNachlaufMinuten)
        }

        // Krank: keine Fenster, keine Waerme, kein „Nicht stoeren“.
        val krank = neu.typ("Krank")
        assertTrue(krank.dimmen.fenster.isEmpty())
        assertNull(krank.dimmen.farbwaerme)
        assertEquals(TypNichtStoeren(), krank.nichtStoeren)

        // „Nicht stoeren“: Schalter wie gespeichert, Ausnahmen exakt, Cutoff nur an der Rufbereitschaft.
        for (typ in neu.definitions.filter { it.kategorie != ShiftKategorie.KRANK }) {
            assertEquals(v.alt.dndSolangeGedimmt, typ.nichtStoeren.solangeGedimmt)
            assertEquals(
                v.alt.dndWaehrendEintrag && typ.name !in v.alt.dndAusnahmen,
                typ.nichtStoeren.waehrendEintrag
            )
            val erwarteterCutoff =
                if (typ.kategorie == ShiftKategorie.RUFBEREITSCHAFT) v.alt.rufCutoffMinuten else STANDARD_CUTOFF
            assertEquals(erwarteterCutoff, typ.nichtStoeren.endetSpaetestensMinuten)
        }
        assertEquals("Vorlage mit einem Ruf-Typ", "Abrufdienst",
            neu.definitions.single { it.kategorie == ShiftKategorie.RUFBEREITSCHAFT }.name)
        assertEquals(v.alt.dndSolangeGedimmt, neu.ohneKalendereintrag.nichtStoerenSolangeGedimmt)

        // An bleibt an, und jedes Fenster ist deutbar.
        for (d in neu.definitions.map { it.dimmen } + neu.ohneKalendereintrag.dimmen) {
            assertEquals(v.alt.dimmenAn, d.an)
            assertEquals(0, d.unlesbareFenster)
        }

        pruefeNurNeueFelderGeaendert(v.config, neu)
        return neu
    }

    /**
     * Ausser den neuen Feldern und der Stufe bleibt alles, wie es war, und die Uebersetzung
     * ueberlebt Schreiben und Lesen (Paket C speichert sie ueber `ShiftConfigRepository`).
     */
    private fun pruefeNurNeueFelderGeaendert(alt: ShiftConfig, neu: ShiftConfig) {
        assertEquals(
            alt.definitions,
            neu.definitions.map { it.copy(dimmen = TypDimmen(), nichtStoeren = TypNichtStoeren()) }
        )
        assertEquals(
            alt,
            neu.copy(
                definitions = alt.definitions,
                ohneKalendereintrag = alt.ohneKalendereintrag,
                modellStufe = alt.modellStufe
            )
        )
        assertEquals(
            "Schreiben und Lesen ergibt dasselbe",
            neu,
            json.decodeFromString(ShiftConfig.serializer(), json.encodeToString(ShiftConfig.serializer(), neu))
        )
    }

    // --- Ausgeschaltet bleibt ausgeschaltet ----------------------------------------------------

    @Test
    fun `dim_enabled aus ergibt Fenster da, aber aus`() {
        val v = nachgebaut()
        val neu = TypEinstellungenUebersetzung.uebersetze(v.config, v.alt.copy(dimmenAn = false), auswahl)

        val alle = neu.definitions.map { it.dimmen } + neu.ohneKalendereintrag.dimmen
        assertTrue("jeder Typ und die feste Zeile sind aus", alle.none { it.an })
        assertEquals(3, neu.typ("Nachtschicht").dimmen.fenster.size)
        assertEquals(1, neu.ohneKalendereintrag.dimmen.fenster.size)
        assertEquals(1, neu.typ("Morgendienst").dimmen.fenster.size)
    }

    @Test
    fun `beide DND-Schalter aus bleiben an jedem Typ und an der festen Zeile aus`() {
        val v = nachgebaut()
        val neu = TypEinstellungenUebersetzung.uebersetze(
            v.config, v.alt.copy(dndSolangeGedimmt = false, dndWaehrendEintrag = false), auswahl
        )

        assertTrue(neu.definitions.none { it.nichtStoeren.solangeGedimmt || it.nichtStoeren.waehrendEintrag })
        assertFalse(neu.ohneKalendereintrag.nichtStoerenSolangeGedimmt)
        // Der Cutoff bleibt trotzdem am Ruf-Typ: er ist eine Einstellung, kein Schalter.
        assertEquals(330, neu.typ("Abrufdienst").nichtStoeren.endetSpaetestensMinuten)
    }

    @Test
    fun `Ausnahmenliste vergleicht exakt wie DndShiftSpanResolver`() {
        val v = nachgebaut()
        val neu = TypEinstellungenUebersetzung.uebersetze(
            v.config, v.alt.copy(dndAusnahmen = setOf("teildienst", "Abrufdienst")), auswahl
        )

        // Gross/Klein zaehlt wie heute (`shiftName in excludedShifts`): "teildienst" trifft nicht.
        assertTrue(neu.typ("Teildienst").nichtStoeren.waehrendEintrag)
        assertFalse(neu.typ("Abrufdienst").nichtStoeren.waehrendEintrag)
    }

    // --- Leere Regel, FREI vor UNIVERSAL, deaktivierte Regel ------------------------------------

    @Test
    fun `leere Regel ergibt keine Fenster und wird nie durch UNIVERSAL aufgefuellt`() {
        val v = nachgebaut()
        val regeln = v.alt.regeln.map { if (it.shiftPattern == "Nachtschicht") it.copy(windows = emptyList()) else it } +
            DimRule(id = "frei_leer", name = "Frei leer", shiftPattern = DimRule.SHIFT_FREE, windows = emptyList(), warmth = 12)
        val neu = TypEinstellungenUebersetzung.uebersetze(v.config, v.alt.copy(regeln = regeln), auswahl)

        val nacht = neu.typ("Nachtschicht").dimmen
        assertTrue(nacht.fenster.isEmpty())
        assertTrue("an bleibt an - die Unterdrueckung steckt in den fehlenden Fenstern", nacht.an)
        assertEquals("Waerme der Regel 1:1", 40, nacht.farbwaerme)
        assertTrue("leere FREI-Regel: feste Zeile ohne Fenster", neu.ohneKalendereintrag.dimmen.fenster.isEmpty())
        assertEquals(12, neu.ohneKalendereintrag.dimmen.farbwaerme)
        assertEquals("die uebrigen bleiben bei UNIVERSAL", 1, neu.typ("Morgendienst").dimmen.fenster.size)
    }

    @Test
    fun `Frei-Typ bekommt FREI vor UNIVERSAL, eine eigene Regel vor FREI, ein Dienst-Typ nie FREI`() {
        val v = nachgebaut()
        val freiFenster = DimWindow(startClockMinutes = 23 * 60, endAnchor = DimAnchor.CLOCK, endClockMinutes = 9 * 60)
        val eigenesFenster = DimWindow(startClockMinutes = 21 * 60, endAnchor = DimAnchor.CLOCK, endClockMinutes = 8 * 60)
        val frei = DimRule(id = "frei", name = "Frei", shiftPattern = DimRule.SHIFT_FREE, windows = listOf(freiFenster), strength = 30, warmth = 70)
        val urlaubRegel = DimRule(id = "urlaub", name = "Urlaub", shiftPattern = "urlaub", windows = listOf(eigenesFenster), strength = 20)
        val config = v.config.copy(
            definitions = v.config.definitions +
                typ("frei", "Frei", ShiftKategorie.FREI) +
                typ("urlaub", "Urlaub", ShiftKategorie.FREI)
        )

        val mitFrei = TypEinstellungenUebersetzung.uebersetze(config, v.alt.copy(regeln = v.alt.regeln + frei + urlaubRegel), auswahl)
        assertEquals(listOf(freiFenster), mitFrei.typ("Frei").dimmen.fenster.map { it.fenster })
        assertEquals(listOf(30), mitFrei.typ("Frei").dimmen.fenster.map { it.staerke })
        assertEquals(70, mitFrei.typ("Frei").dimmen.farbwaerme)
        assertEquals("eigene Regel (Gross/Klein egal) vor FREI", listOf(eigenesFenster), mitFrei.typ("Urlaub").dimmen.fenster.map { it.fenster })
        assertEquals(listOf(freiFenster), mitFrei.ohneKalendereintrag.dimmen.fenster.map { it.fenster })
        assertEquals("Dienst-Typ bleibt bei UNIVERSAL", 67, mitFrei.typ("Morgendienst").dimmen.fenster.single().staerke)

        val ohneFrei = TypEinstellungenUebersetzung.uebersetze(config, v.alt, auswahl)
        assertEquals("ohne FREI-Regel: UNIVERSAL", 67, ohneFrei.typ("Frei").dimmen.fenster.single().staerke)
    }

    @Test
    fun `deaktivierte Regel zaehlt nicht, ohne jede Regel bleibt der Typ ohne Fenster und Waerme`() {
        val v = nachgebaut()
        val regeln = v.alt.regeln.map { if (it.shiftPattern == "Nachtschicht") it.copy(enabled = false) else it }
        val neu = TypEinstellungenUebersetzung.uebersetze(v.config, v.alt.copy(regeln = regeln), auswahl)
        assertEquals("deaktiviert: UNIVERSAL greift", 67, neu.typ("Nachtschicht").dimmen.fenster.single().staerke)

        val keine = TypEinstellungenUebersetzung.uebersetze(v.config, v.alt.copy(regeln = emptyList()), auswahl)
        for (d in keine.definitions.map { it.dimmen } + keine.ohneKalendereintrag.dimmen) {
            assertTrue(d.fenster.isEmpty())
            assertNull(d.farbwaerme)
        }
    }

    @Test
    fun `Ignorieren bekommt wie Krank weder Fenster noch Nicht stoeren`() {
        val v = nachgebaut()
        val config = v.config.copy(definitions = v.config.definitions + typ("ign", "Ignoriert", ShiftKategorie.IGNORIEREN))
        val neu = TypEinstellungenUebersetzung.uebersetze(config, v.alt.copy(dndAusnahmen = emptySet()), auswahl)

        val ign = neu.typ("Ignoriert")
        assertTrue(ign.dimmen.fenster.isEmpty())
        assertNull(ign.dimmen.farbwaerme)
        assertEquals(TypNichtStoeren(), ign.nichtStoeren)
        // Gegenprobe: derselbe Lauf hat den Dienst-Typen sehr wohl Fenster und DND gegeben.
        assertEquals(1, neu.typ("Morgendienst").dimmen.fenster.size)
        assertTrue(neu.typ("Morgendienst").nichtStoeren.waehrendEintrag)
    }

    @Test
    fun `jedes Fensterfeld wird 1 zu 1 kopiert, auch Wecktag-Start und Hell ab`() {
        val v = nachgebaut()
        val fenster = listOf(
            DimWindow(
                startAnchor = DimAnchor.CLOCK, startClockMinutes = 16 * 60, startOffsetMinutes = -45,
                endAnchor = DimAnchor.ALARM, endClockMinutes = 5 * 60, endOffsetMinutes = 15,
                weckzeitNachlaufMinuten = 7, blockPositionen = setOf(Blockposition.MITTLERER),
                nachlaufBisHell = HellAb.SONNENAUFGANG, startNurAmWecktag = true
            ),
            DimWindow(
                startAnchor = DimAnchor.ALARM, startOffsetMinutes = -90,
                endAnchor = DimAnchor.ALARM_SONST_CLOCK, endClockMinutes = 6 * 60 + 30,
                weckzeitNachlaufMinuten = 25, nachlaufBisHell = HellAb.DAEMMERUNG
            )
        )
        val regeln = v.alt.regeln.map { if (it.shiftPattern == "Nachtschicht") it.copy(windows = fenster, strength = 81) else it }
        val neu = TypEinstellungenUebersetzung.uebersetze(v.config, v.alt.copy(regeln = regeln), auswahl)

        assertEquals(fenster, neu.typ("Nachtschicht").dimmen.fenster.map { it.fenster })
        assertEquals(listOf(81, 81), neu.typ("Nachtschicht").dimmen.fenster.map { it.staerke })
        pruefeNurNeueFelderGeaendert(v.config, neu)
    }

    // --- Idempotenz --------------------------------------------------------------------------

    @Test
    fun `eine Eingabe mit gesetzter Modell-Stufe kommt unveraendert zurueck`() {
        val v = nachgebaut()
        val einmal = uebersetze(v)
        assertEquals(TypEinstellungenUebersetzung.MODELL_STUFE, einmal.modellStufe)
        assertTrue("der erste Lauf hat uebersetzt", einmal.typ("Nachtschicht").dimmen.fenster.isNotEmpty())
        assertSame(einmal, TypEinstellungenUebersetzung.uebersetze(einmal, v.alt, auswahl))

        // Auch wenn sich der Altbestand inzwischen geaendert hat: die Stufe entscheidet, nicht die Eingabe.
        val schonMigriert = v.config.copy(modellStufe = TypEinstellungenUebersetzung.MODELL_STUFE)
        assertSame(schonMigriert, TypEinstellungenUebersetzung.uebersetze(schonMigriert, v.alt, auswahl))
    }

    // --- Gleichwertigkeit ueber den echten Resolver ----------------------------------------------

    @Test
    fun `Gleichwertigkeit - alte Regeln und zurueckgebaute Typen ergeben ueber 14 Tage dieselben Spannen`() {
        val v = nachgebaut()
        pruefeGleichwertigkeit(v)
        pruefeGleichwertigkeit(v.copy(alt = v.alt.copy(dimmenAn = false)))
    }

    /**
     * `buildRuleSpans` ueber 14 Tage mit erfundenen Spannen: einmal mit den ALTEN Regeln und der
     * echten Auswahl, einmal mit den aus den Typen zurueckgebauten Regeln. Hoechstens ein Eintrag je
     * Tag - zwei Eintraege an einem Tag ohne eigene Regel sind die benannte Ausnahme (#214), ebenso
     * die Frei-Kategorie (die Vorlagen haben keinen Frei-Typ). Der Zeitraum enthaelt die
     * Zeitumstellung am 25.10.2026.
     */
    private fun pruefeGleichwertigkeit(v: Vorlage) {
        val neu = uebersetze(v)
        val zone = ZoneId.of("Europe/Berlin")
        val heute = LocalDate.of(2026, 10, 19)

        val typen = v.config.definitions.filter {
            it.isEnabled && it.kategorie != ShiftKategorie.KRANK && it.kategorie != ShiftKategorie.IGNORIEREN
        }
        assertTrue("keine Frei-Kategorie in der Vorlage", typen.none { it.kategorie == ShiftKategorie.FREI })
        val blockTyp = typen.single { it.name == "Nachtschicht" }
        val andere = typen - blockTyp

        // Ein Block aus drei Naechten, ein Einzeltag, ein Zweierblock; freie Tage dazwischen; jeder
        // andere Typ mindestens einmal. Tag -1 zaehlt mit (LOOKBACK), Tag 14 als Nachbar des letzten.
        val plan = mutableMapOf<Int, ShiftDefinition>()
        listOf(1, 2, 3, 7, 11, 12).forEach { plan[it] = blockTyp }
        val freieTage = setOf(0, 4, 8, 13)
        val einzeltage = (-1..14).filter { it !in plan && it !in freieTage }
        assertTrue("jeder Typ kommt vor", einzeltage.size >= andere.size)
        einzeltage.forEachIndexed { i, tag -> plan[tag] = andere[i % andere.size] }

        val slots = plan.toSortedMap().map { (tag, typ) ->
            val weckzeit = heute.plusDays(tag.toLong()).atTime(typ.alarmTime).atZone(zone)
            val beginn = weckzeit.plusMinutes(75)
            DimWindowResolver.AlarmSlot(
                triggerTime = weckzeit.toInstant().toEpochMilli(),
                shiftName = typ.name,
                shiftEndTime = beginn.plusHours(8).toInstant().toEpochMilli()
            )
        }
        val weckzeiten = slots.map { it.triggerTime }

        fun spannen(fuerSchicht: (String) -> DimRule?, fuerFreienTag: () -> DimRule?) =
            DimWindowResolver.buildRuleSpans(
                alarms = slots, horizonDays = 14, today = heute, zone = zone,
                ruleForShift = fuerSchicht, ruleForFreeDay = fuerFreienTag, weckzeiten = weckzeiten
            )

        // Alt: mit `dim_enabled` aus rechnet der Dimmer heute gar nicht (DimScheduleUseCase).
        val alt = if (!v.alt.dimmenAn) emptyList() else spannen(
            { auswahl.findRuleForShift(it, v.alt.regeln) },
            { auswahl.findRuleForFreeDay(v.alt.regeln) }
        )
        val zurueckgebaut = spannen(
            { name -> neu.definitions.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { rueckbau(it.dimmen, it.name) } },
            { rueckbau(neu.ohneKalendereintrag.dimmen, DimRule.SHIFT_FREE) }
        )

        assertEquals(alt, zurueckgebaut)
        if (v.alt.dimmenAn) {
            val nachtRegel = v.alt.regeln.single { it.shiftPattern == "Nachtschicht" }
            assertTrue("die Nacht-Regel wirkt", alt.any { it.strength == nachtRegel.strength && it.warmth == nachtRegel.warmth })
            val universal = v.alt.regeln.single { it.shiftPattern == DimRule.SHIFT_UNIVERSAL }
            assertTrue("UNIVERSAL wirkt", alt.any { it.strength == universal.strength && it.warmth == universal.warmth })
        }
    }

    /**
     * Der Rueckbau eines Typs in eine spezifische Regel - die Semantik, die Paket D umsetzt: ohne
     * wirksames Fenster (leer oder `an = false`) unterdrueckt der Typ seinen Tag (leere Regel). Liest
     * nur die gedeuteten Fenster; ungleiche Staerken kann eine Regel nicht tragen, dann scheitert er.
     */
    private fun rueckbau(d: TypDimmen, muster: String): DimRule {
        if (!d.an || d.fenster.isEmpty()) {
            return DimRule(id = "rueckbau_$muster", name = muster, shiftPattern = muster, windows = emptyList())
        }
        val staerke = d.fenster.map { it.staerke }.distinct().single()
        return DimRule(
            id = "rueckbau_$muster",
            name = muster,
            shiftPattern = muster,
            windows = d.fenster.map { it.fenster },
            strength = staerke,
            warmth = checkNotNull(d.farbwaerme) { "Fenster ohne Waerme" }
        )
    }

    // --- Hilfen ------------------------------------------------------------------------------

    private fun typ(id: String, name: String, kategorie: ShiftKategorie) =
        ShiftDefinition(id = id, name = name, keywords = listOf(id.uppercase()), alarmTime = LocalTime.of(8, 0))
            .mitKategorie(kategorie)

    /**
     * Wo die echte Vorlage liegen kann - dieselbe Suche wie `UmbauTestvorlageTest`: jeder
     * uebergeordnete Ordner und, weil der Umbau in Worktrees laeuft, der Hauptbaum, auf den die
     * `.git`-DATEI eines Worktrees zeigt.
     */
    private fun kandidatenFuerEchteVorlage(): List<File> {
        val wurzeln = mutableListOf<File>()
        var ordner: File? = File(checkNotNull(System.getProperty("user.dir"))).absoluteFile
        while (ordner != null) {
            wurzeln += ordner
            val git = File(ordner, ".git")
            if (git.isFile) {
                val gitdir = git.readLines().firstOrNull { it.startsWith("gitdir:") }
                    ?.removePrefix("gitdir:")?.trim()?.replace('\\', '/')
                val hauptbaum = gitdir?.substringBefore("/.git/worktrees/", missingDelimiterValue = "")
                if (!hauptbaum.isNullOrEmpty()) wurzeln += File(hauptbaum)
            }
            ordner = ordner.parentFile
        }
        return wurzeln.distinct().map { File(it, ECHTE_VORLAGE) }
    }

    private companion object {
        const val NACHGEBAUT = "umbau/vorlage-nachgebaut-2026-10-16.json"
        const val ECHTE_VORLAGE = "..Projektdateien/umbau/testvorlagen/konfiguration-eigentuemer-2026-10-16.json"

        /** Der Default des Ruf-Cutoffs (05:00), gelesen aus dem Modell statt aus `dnd/`. */
        val STANDARD_CUTOFF = TypNichtStoeren().endetSpaetestensMinuten
    }
}
