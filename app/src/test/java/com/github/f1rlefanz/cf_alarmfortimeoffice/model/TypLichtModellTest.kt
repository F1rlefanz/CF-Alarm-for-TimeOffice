package com.github.f1rlefanz.cf_alarmfortimeoffice.model

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.ActionType
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueColor
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueLightAction
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueRuleModus
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueScheduleRule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueTimeRange
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.SunriseConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.TargetType
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.modus
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftConfigDecodeResult
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.decodeShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.HellAb
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.resetToDefaultsPreservingAutoAlarm
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * Umbau Etappe 4 (#246, SPEC E8/Abschnitt 4): die Lichtregeln am Schichttyp ([ShiftDefinition.licht])
 * und der eigene Migrations-Marker [ShiftConfig.lichtStufe]. Prueft das Lesen von Altbestand, die
 * verlustfreie Rundreise (Szene, Sonnenaufgang) und die Robustheit gegen unbekannte Enum-Werte. Die
 * Revert-Richtung gegen die eingefrorene Kopie des Modells steht in [AltDecoderKompatibilitaetTest].
 * Json-Flags wie `ShiftConfigRepository.json`.
 */
class TypLichtModellTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Jedes Feld abweichend vom Default, sonst bliebe die Rundreise auch mit falschem Feldnamen gruen. */
    private val szene = HueScheduleRule(
        id = "regel_szene",
        name = "Szene Wohnzimmer",
        shiftPattern = "Frühschicht",
        enabled = false,
        timeRanges = listOf(
            HueTimeRange(
                actions = listOf(
                    HueLightAction(
                        targetType = TargetType.GROUP,
                        targetId = "3",
                        targetName = "Wohnzimmer",
                        actionType = ActionType.TURN_ON,
                        on = true,
                        transitionTime = 40,
                        duration = 25,
                        isGroup = true,
                        sceneId = "Abc123xyz",
                        sceneName = "Entspannen"
                    )
                )
            )
        ),
        priority = 2,
        hellAb = HellAb.DAEMMERUNG,
        ausBeiHell = HellAb.SONNENAUFGANG
    )

    private val sonnenaufgang = HueScheduleRule(
        id = "regel_sonne",
        name = "Sonnenaufgang Nachttisch",
        shiftPattern = "",
        timeRanges = listOf(
            HueTimeRange(
                actions = listOf(
                    HueLightAction(
                        targetType = TargetType.LIGHT,
                        targetId = "7",
                        targetName = "Nachttisch",
                        actionType = ActionType.TURN_OFF,
                        on = false,
                        brightness = 200,
                        hue = 12000,
                        saturation = 140,
                        colorTemperature = 370,
                        color = HueColor(hue = 11, saturation = 22, xy = listOf(0.3f, 0.4f), rgb = "#FFAA00"),
                        transitionTime = 3,
                        duration = 30
                    )
                )
            )
        ),
        priority = 1,
        sunrise = SunriseConfig(
            enabled = true,
            durationMinutes = 30,
            startKelvin = 2200,
            endKelvin = 4500,
            endBrightness = 200,
            startBeforeAlarm = false
        ),
        hellAb = HellAb.SONNENAUFGANG
    )

    private val frueh = ShiftDefinition(id = "f", name = "Früh", keywords = listOf("F"), alarmTime = LocalTime.of(5, 30))
        .mitKategorie(ShiftKategorie.DIENST)
        .copy(licht = TypLicht().mitRegeln(listOf(szene, sonnenaufgang)))

    private val spaet = ShiftDefinition(id = "s", name = "Spät", keywords = listOf("S"), alarmTime = LocalTime.of(12, 0))
        .mitKategorie(ShiftKategorie.DIENST)

    private val eingestellt = ShiftConfig(
        autoAlarmEnabled = false,
        definitions = listOf(frueh, spaet),
        modellStufe = 1,
        lichtStufe = 1
    )

    private fun schreiben(config: ShiftConfig) = json.encodeToString(ShiftConfig.serializer(), config)
    private fun lesen(roh: String) = json.decodeFromString(ShiftConfig.serializer(), roh)

    /** Die gespeicherte Rohliste, wie sie im Store landet - der einzige Blick auf undeutbare Regeln. */
    private fun gespeicherteRegeln(licht: TypLicht): List<JsonElement> =
        json.encodeToJsonElement(TypLicht.serializer(), licht).jsonObject.getValue("regeln").jsonArray

    @Test
    fun `ein Bestand ohne die neuen Felder liest sich mit den Defaults`() {
        // Format nach Etappe 3 (#213), wortgleich wie es heute im Store steht.
        val alt = """{"autoAlarmEnabled":true,"definitions":[{"id":"f","name":"Früh","keywords":["F"],""" +
            """"alarmTime":"05:30","isEnabled":true,"isSilent":false,"isOnCall":false,"isSickDay":false,""" +
            """"kategorie":"DIENST","weckerAn":true,"dimmen":{"an":false,"fenster":[],"farbwaerme":30},""" +
            """"nichtStoeren":{"solangeGedimmt":true,"waehrendEintrag":false,"endetSpaetestensMinuten":300}}],""" +
            """"ohneKalendereintragWie":null,"ohneKalendereintrag":{"dimmen":{"an":true,"fenster":[],""" +
            """"farbwaerme":null},"nichtStoerenSolangeGedimmt":false},"modellStufe":1}"""

        val gelesen = lesen(alt)

        val typ = gelesen.definitions.single()
        assertEquals(TypLicht(), typ.licht)
        assertEquals(emptyList<HueScheduleRule>(), typ.licht.regeln)
        assertEquals(0, typ.licht.unlesbareRegeln)
        assertEquals(0, gelesen.lichtStufe)
        assertEquals("die Stufe von Etappe 3 bleibt unberuehrt", 1, gelesen.modellStufe)
        assertEquals("Etappe-3-Felder lesen sich weiter", 30, typ.dimmen.farbwaerme)
    }

    @Test
    fun `Rundreise Schreiben und Lesen ist verlustfrei, auch mit Szene und Sonnenaufgang`() {
        val wieder = lesen(schreiben(eingestellt))

        assertEquals(eingestellt, wieder)
        val regeln = wieder.definitions.first().licht.regeln
        assertEquals(listOf(szene, sonnenaufgang), regeln)
        // Die Testregeln stehen wirklich fuer die beiden Betriebsarten, die das Issue nennt.
        assertEquals(listOf(HueRuleModus.SZENE, HueRuleModus.SONNENAUFGANG), regeln.map { it.modus })
        assertEquals(TypLicht(), wieder.definitions[1].licht)
        assertEquals(1, wieder.lichtStufe)
        assertEquals(1, wieder.modellStufe)
    }

    /** E2: ein Wechsel der Kategorie behaelt die Einstellungen - auch das Licht. */
    @Test
    fun `ein Wechsel der Kategorie behaelt die Lichtregeln`() {
        assertEquals(frueh.licht, frueh.mitKategorie(ShiftKategorie.FREI).licht)
    }

    /**
     * #166 auf die Regeln angewandt: eine Regel mit einem Wert, den diese Version nicht kennt (spaetere
     * Version, dann Revert; `ZONE` gab es bis #19 wirklich), darf die Konfiguration nicht `Broken`
     * machen - und muss beim Zurueckschreiben wortgleich erhalten bleiben, auch nach einer Bearbeitung.
     */
    @Test
    fun `eine Regel mit unbekanntem Enum-Wert laesst die Konfiguration lesbar und geht beim Schreiben nicht verloren`() {
        val basis = json.encodeToString(HueScheduleRule.serializer(), szene)
        val varianten = listOf(
            """"targetType":"GROUP"""" to """"targetType":"ZONE"""",
            """"actionType":"TURN_ON"""" to """"actionType":"DIMMEN"""",
            """"hellAb":"DAEMMERUNG"""" to """"hellAb":"MONDAUFGANG"""",
            """"ausBeiHell":"SONNENAUFGANG"""" to """"ausBeiHell":"MITTAG""""
        ).map { (alt, neu) ->
            assertTrue("Testaufbau: $alt steht in der Regel", basis.contains(alt))
            basis.replace(alt, neu)
        }
        // Jede Variante ist fuer den STRENGEN Decoder wirklich unlesbar - sonst prueft der Test nichts.
        varianten.forEach { v ->
            assertThrows(v, IllegalArgumentException::class.java) {
                json.decodeFromString(HueScheduleRule.serializer(), v)
            }
        }
        val ohneUnbekannte = eingestellt.copy(
            definitions = listOf(frueh.copy(licht = TypLicht().mitRegeln(listOf(sonnenaufgang))), spaet)
        )
        val roh = schreiben(ohneUnbekannte).replaceFirst(""""regeln":[""", """"regeln":[${varianten.joinToString(",")},""")
        assertTrue("Testaufbau: die Regeln stecken im ersten Typ", roh.contains("MONDAUFGANG"))

        val ergebnis = decodeShiftConfig(json, roh)

        assertTrue("nicht Broken: $ergebnis", ergebnis is ShiftConfigDecodeResult.Ok)
        val config = (ergebnis as ShiftConfigDecodeResult.Ok).config
        val licht = config.definitions.first().licht
        assertEquals("undeutbare Regeln laufen nicht", listOf(sonnenaufgang), licht.regeln)
        assertEquals(4, licht.unlesbareRegeln)
        assertEquals("der andere Typ unversehrt", spaet, config.definitions[1])

        val erwartetRoh = varianten.map { json.parseToJsonElement(it) }
        val geschrieben = lesen(schreiben(config)).definitions.first().licht
        assertTrue("Lesen und Schreiben erhaelt sie wortgleich", gespeicherteRegeln(geschrieben).containsAll(erwartetRoh))

        val bearbeitet = licht.mitRegeln(listOf(szene))
        assertEquals(listOf(szene), bearbeitet.regeln)
        assertEquals("Bearbeiten erhaelt sie", 4, bearbeitet.unlesbareRegeln)
        assertEquals(
            "deutbare zuerst, undeutbare wortgleich angehaengt",
            listOf(json.encodeToJsonElement(HueScheduleRule.serializer(), szene)) + erwartetRoh,
            gespeicherteRegeln(bearbeitet)
        )
    }

    /**
     * Neu gebaute Konfigurationen behalten die Licht-Stufe (Festlegung #246, Muster #213): sonst liefe
     * die Licht-Migration nach „Auf Standardwerte zuruecksetzen“ erneut. Ueber die Funktion, die der
     * Knopf wirklich ruft (`ShiftConfigScreen`).
     */
    @Test
    fun `Zuruecksetzen auf Standard behaelt die Licht-Stufe`() {
        val zurueck = resetToDefaultsPreservingAutoAlarm(eingestellt.copy(lichtStufe = 3))

        assertEquals(3, zurueck.lichtStufe)
        assertEquals(1, zurueck.modellStufe)
        assertEquals(false, zurueck.autoAlarmEnabled)
        assertEquals(ShiftConfig.getDefaultConfig().definitions, zurueck.definitions)
    }
}
