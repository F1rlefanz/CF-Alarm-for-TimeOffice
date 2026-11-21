package com.github.f1rlefanz.cf_alarmfortimeoffice.model

import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.Blockposition
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimAnchor
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindow
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftConfigDecodeResult
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.decodeShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.HellAb
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.resetToDefaultsPreservingAutoAlarm
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.typ.ShiftEditFormular
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * Umbau Etappe 3 (#213): Dimmen und „Nicht stoeren“ im gespeicherten Modell des Typs und der festen
 * Zeile, dazu die Modell-Stufe. Prueft das Lesen von Altbestand, die verlustfreie Rundreise, die
 * Revert-Richtung gegen eine eingefrorene Kopie des Modells von v1.58.0 und die Robustheit gegen
 * unbekannte Enum-Werte in den Fenstern. Json-Flags wie `ShiftConfigRepository.json`.
 */
class TypEinstellungenModellTest {

    /** Testlokale Kopie der Datenklassen von v1.58.0 (nur Felder), dem Stand VOR diesem Paket. */
    @Suppress("ClassName")
    private object V1_58_0 {
        @Serializable
        data class ShiftDefinition(
            val id: String,
            val name: String,
            val keywords: List<String>,
            @Serializable(with = LocalTimeSerializer::class)
            val alarmTime: LocalTime,
            val isEnabled: Boolean = true,
            val isSilent: Boolean = false,
            val isOnCall: Boolean = false,
            val isSickDay: Boolean = false,
            @SerialName("kategorie")
            val kategorie: String? = null,
            val weckerAn: Boolean = true
        )

        @Serializable
        data class ShiftConfig(
            val autoAlarmEnabled: Boolean = true,
            val definitions: List<ShiftDefinition> = emptyList(),
            val ohneKalendereintragWie: String? = null
        )
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Jedes Feld abweichend vom Default, sonst bliebe die Rundreise auch mit falschem Feldnamen gruen. */
    private val nacht = TypDimmFenster(
        fenster = DimWindow(
            startAnchor = DimAnchor.CLOCK,
            startClockMinutes = 22 * 60,
            startOffsetMinutes = -30,
            endAnchor = DimAnchor.ALARM_SONST_CLOCK,
            endClockMinutes = 7 * 60 + 15,
            endOffsetMinutes = 10,
            weckzeitNachlaufMinuten = 20,
            blockPositionen = setOf(Blockposition.ERSTER, Blockposition.EINZELNER),
            nachlaufBisHell = HellAb.SONNENAUFGANG,
            startNurAmWecktag = true
        ),
        staerke = 70
    )
    private val tagschlaf = TypDimmFenster(
        fenster = DimWindow(startAnchor = DimAnchor.SHIFT_END, startOffsetMinutes = 60, endAnchor = DimAnchor.CLOCK),
        staerke = 30
    )

    private val eingestellt = ShiftConfig(
        autoAlarmEnabled = false,
        definitions = listOf(
            ShiftDefinition(id = "n", name = "Nacht", keywords = listOf("N"), alarmTime = LocalTime.of(19, 0))
                .mitKategorie(ShiftKategorie.DIENST)
                .copy(
                    dimmen = TypDimmen(an = false, farbwaerme = 80).mitFenster(listOf(nacht, tagschlaf)),
                    nichtStoeren = TypNichtStoeren(solangeGedimmt = true, waehrendEintrag = true, endetSpaetestensGespeichert = 4 * 60)
                ),
            ShiftDefinition(id = "r", name = "Ruf", keywords = listOf("R"), alarmTime = LocalTime.of(5, 0))
                .mitKategorie(ShiftKategorie.RUFBEREITSCHAFT)
                .copy(isSilent = true)
        ),
        ohneKalendereintragWie = null,
        ohneKalendereintrag = OhneEintragEinstellungen(
            dimmen = TypDimmen(farbwaerme = 10).mitFenster(listOf(nacht)),
            nichtStoerenSolangeGedimmt = true
        ),
        modellStufe = 1
    )

    private fun schreiben(config: ShiftConfig) = json.encodeToString(ShiftConfig.serializer(), config)
    private fun lesen(roh: String) = json.decodeFromString(ShiftConfig.serializer(), roh)

    @Test
    fun `ein Bestand ohne die neuen Felder liest sich mit den Defaults`() {
        // Format von v1.58.0, wortgleich wie es heute im Store steht.
        val alt = """{"autoAlarmEnabled":true,"definitions":[{"id":"f","name":"Früh","keywords":["F"],""" +
            """"alarmTime":"05:30","isEnabled":true,"isSilent":false,"isOnCall":true,"isSickDay":false,""" +
            """"kategorie":"RUFBEREITSCHAFT","weckerAn":true}],"ohneKalendereintragWie":"f"}"""

        val gelesen = lesen(alt)

        val typ = gelesen.definitions.single()
        assertEquals(TypDimmen(), typ.dimmen)
        assertEquals(true, typ.dimmen.an)
        assertEquals(emptyList<TypDimmFenster>(), typ.dimmen.fenster)
        assertEquals(null, typ.dimmen.farbwaerme)
        assertEquals(TypNichtStoeren(), typ.nichtStoeren)
        assertEquals(false, typ.nichtStoeren.solangeGedimmt)
        assertEquals(false, typ.nichtStoeren.waehrendEintrag)
        assertEquals("heutiger Cutoff-Default", DndPrefs.DEFAULT_ONCALL_CUTOFF_MIN, typ.nichtStoeren.endetSpaetestensMinuten)
        assertEquals(OhneEintragEinstellungen(), gelesen.ohneKalendereintrag)
        assertEquals(0, gelesen.modellStufe)
        assertEquals("f", gelesen.ohneKalendereintragWie)
    }

    @Test
    fun `Rundreise Schreiben und Lesen ist verlustfrei`() {
        val wieder = lesen(schreiben(eingestellt))

        assertEquals(eingestellt, wieder)
        val typ = wieder.definitions.first()
        assertEquals(listOf(nacht, tagschlaf), typ.dimmen.fenster)
        assertEquals(false, typ.dimmen.an)
        assertEquals(80, typ.dimmen.farbwaerme)
        assertEquals(4 * 60, typ.nichtStoeren.endetSpaetestensMinuten)
        assertEquals(listOf(nacht), wieder.ohneKalendereintrag.dimmen.fenster)
        assertEquals(true, wieder.ohneKalendereintrag.nichtStoerenSolangeGedimmt)
        assertEquals(1, wieder.modellStufe)
    }

    @Test
    fun `der Decoder von v1_58_0 ueberliest die neuen Felder und sieht die feste Zeile nicht als Definition`() {
        val alt = json.decodeFromString(V1_58_0.ShiftConfig.serializer(), schreiben(eingestellt))

        assertEquals("keine zusaetzliche Schicht", eingestellt.definitions.size, alt.definitions.size)
        assertEquals(eingestellt.autoAlarmEnabled, alt.autoAlarmEnabled)
        eingestellt.definitions.zip(alt.definitions).forEach { (neu, gelesen) ->
            assertEquals(
                "Typ ${neu.id}",
                V1_58_0.ShiftDefinition(
                    id = neu.id, name = neu.name, keywords = neu.keywords, alarmTime = neu.alarmTime,
                    isEnabled = neu.isEnabled, isSilent = neu.isSilent, isOnCall = neu.isOnCall,
                    isSickDay = neu.isSickDay, kategorie = neu.kategorie.schluessel, weckerAn = neu.weckerAn
                ),
                gelesen
            )
        }
    }

    /**
     * Die Revert-Begruendung des Markers: schreibt die alte App zurueck, fallen Stufe und neue Felder
     * weg - die neue liest Stufe 0 (die Migration laeuft erneut) und Defaults, nichts ist unlesbar.
     */
    @Test
    fun `von v1_58_0 zurueckgeschrieben liest die neue App Stufe 0 und Defaults`() {
        val alt = json.decodeFromString(V1_58_0.ShiftConfig.serializer(), schreiben(eingestellt))

        val wieder = lesen(json.encodeToString(V1_58_0.ShiftConfig.serializer(), alt))

        assertEquals(0, wieder.modellStufe)
        assertEquals(OhneEintragEinstellungen(), wieder.ohneKalendereintrag)
        assertTrue(wieder.definitions.all { it.dimmen == TypDimmen() && it.nichtStoeren == TypNichtStoeren() })
        assertEquals(eingestellt.definitions.map { it.kategorie }, wieder.definitions.map { it.kategorie })
    }

    /**
     * #166 auf die Fenster angewandt: ein Fenster mit einem Anker, den diese Version nicht kennt
     * (spaetere Version, dann Revert), darf die Konfiguration nicht `Broken` machen - und muss beim
     * Zurueckschreiben wortgleich erhalten bleiben, auch nach einer Bearbeitung der Fenster.
     */
    @Test
    fun `ein unbekannter Enum-Wert im Fenster laesst die Konfiguration lesbar und geht beim Schreiben nicht verloren`() {
        val unbekannt = """{"fenster":{"startAnchor":"MONDAUFGANG","endAnchor":"ALARM"},"staerke":40}"""
        val roh = schreiben(eingestellt).replaceFirst(""""fenster":[""", """"fenster":[$unbekannt,""")
        assertTrue("Testaufbau: das Fenster steckt im ersten Typ", roh.contains("MONDAUFGANG"))

        val ergebnis = decodeShiftConfig(json, roh)

        assertTrue("nicht Broken: $ergebnis", ergebnis is ShiftConfigDecodeResult.Ok)
        val config = (ergebnis as ShiftConfigDecodeResult.Ok).config
        val typ = config.definitions.first()
        assertEquals("undeutbares Fenster dimmt nicht", listOf(nacht, tagschlaf), typ.dimmen.fenster)
        assertEquals(1, typ.dimmen.unlesbareFenster)
        assertEquals("der andere Typ unversehrt", eingestellt.definitions[1], config.definitions[1])

        assertTrue("Lesen und Schreiben erhaelt es", schreiben(config).contains("MONDAUFGANG"))
        val bearbeitet = typ.dimmen.mitFenster(listOf(tagschlaf))
        assertEquals(listOf(tagschlaf), bearbeitet.fenster)
        assertEquals("Bearbeiten erhaelt es", 1, bearbeitet.unlesbareFenster)
    }

    @Test
    fun `Endet spaetestens ist im Lesepfad auf den Tag geklemmt`() {
        assertEquals(24 * 60 - 1, TypNichtStoeren(endetSpaetestensGespeichert = 5000).endetSpaetestensMinuten)
        assertEquals(0, TypNichtStoeren(endetSpaetestensGespeichert = -5).endetSpaetestensMinuten)
    }

    /**
     * Neu gebaute Konfigurationen behalten die Stufe (Festlegung #213): sonst liefe die Migration
     * nach „Auf Standardwerte zuruecksetzen“ erneut und ueberschriebe die Typen aus dem Altbestand.
     * Ueber die Funktion, die der Knopf wirklich ruft (`ShiftConfigScreen`), nicht ueber einen Ersatz.
     */
    @Test
    fun `Zuruecksetzen auf Standard behaelt Modell-Stufe und Automatik-Pause`() {
        val zurueck = resetToDefaultsPreservingAutoAlarm(eingestellt)

        assertEquals(1, zurueck.modellStufe)
        assertEquals(false, zurueck.autoAlarmEnabled)
        assertEquals(ShiftConfig.getDefaultConfig().definitions, zurueck.definitions)
        assertEquals(OhneEintragEinstellungen(), zurueck.ohneKalendereintrag)
        assertEquals(null, zurueck.ohneKalendereintragWie)
    }

    /** Ueber die drei Wege, auf denen die App einen neuen Typ anlegt (Festlegung #213). */
    @Test
    fun `neue Typen bekommen die Defaults`() {
        val neue = listOf(
            ShiftEditFormular.leererTyp { "plus" },
            SchichttypEinordnen.neuerTypAusCode("ND", ShiftKategorie.DIENST) { "code" }!!,
            SchichttypEinordnen.ignorieren(eingestellt, "XY") { "ign" }!!.definitions.single { it.id == "ign" }
        )

        neue.forEach { neu ->
            assertEquals(neu.id, TypDimmen(), neu.dimmen)
            assertEquals(neu.id, TypNichtStoeren(), neu.nichtStoeren)
        }
    }
}
