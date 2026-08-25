package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueRuleModus
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.SunriseConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.HueRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.util.HueColorConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Der Formzustand des Regel-Editors - jetzt pruefbar, weil er als reines Datenobjekt ausserhalb
 * des Composables liegt.
 *
 * Zwei Dinge haengen daran, die vorher nur Hoffnung waren:
 *  1. **Der Rundlauf.** `rule.toFormState().toRule(...) == rule` fuer jeden Modus. Vorher lagen
 *     Hin- und Rueckweg als zwei lange Funktionen IM Composable und waren nur am Geraet pruefbar.
 *  2. **Die Ausschliesslichkeit der Modi.** [toRule] liest ausschliesslich die Felder des aktiven
 *     Modus - eine im Manuell-Modus eingestellte Helligkeit kann konstruktiv nicht in eine
 *     Szenen-Aktion lecken. Das ist der eigentliche Grund fuer das Zustandsobjekt.
 */
class HueRuleFormStateTest {

    private val lightNames = mapOf("4" to "Deckenlampe", "10" to "Schreibtischlampe")
    private val groupNames = mapOf("1" to "Wohnzimmer", "82" to "Schlafzimmer")

    private fun HueRuleFormState.baue(id: String = "rule_1") =
        toRule(id, lightNames, groupNames, emptyMap())

    // --- Rundlauf je Modus --------------------------------------------------------------------

    @Test
    fun `Rundlauf manuell mit Weisston`() {
        val form = HueRuleFormState(
            name = "Frueh hell",
            shiftPattern = "Frühdienst",
            modus = HueRuleModus.MANUELL,
            selectedLightIds = setOf("4"),
            selectedGroupIds = setOf("1"),
            on = true,
            brightness = 200,
            colorMode = ColorMode.WHITE,
            colorKelvin = 2700,
            autoOffEnabled = true,
            autoOffMinutes = 30
        )

        val zurueck = form.baue().toFormState()

        assertEquals(HueRuleModus.MANUELL, zurueck.modus)
        assertEquals(setOf("4"), zurueck.selectedLightIds)
        assertEquals(setOf("1"), zurueck.selectedGroupIds)
        assertEquals(ColorMode.WHITE, zurueck.colorMode)
        // Kelvin faehrt ueber Mired und zurueck; das ist verlustbehaftet (2700 K -> 370 Mired ->
        // 2702 K). Bestehendes Verhalten, nicht neu - und es konvergiert: der zweite Rundlauf
        // aendert nichts mehr. Deshalb eine Toleranz statt Gleichheit; wer hier auf Gleichheit
        // umstellt, muesste Kelvin zusaetzlich zum Mired-Wert speichern.
        assertEquals(2700.0, zurueck.colorKelvin.toDouble(), 5.0)
        assertEquals(
            "Der zweite Rundlauf darf nicht weiter driften",
            zurueck.colorKelvin,
            zurueck.baue().toFormState().colorKelvin
        )
        assertEquals(200, zurueck.brightness)
        assertTrue(zurueck.autoOffEnabled)
        assertEquals(30, zurueck.autoOffMinutes)
        assertNull("Manuell traegt keine Szene", zurueck.szene)
    }

    @Test
    fun `Rundlauf manuell mit Preset-Farbe`() {
        val form = HueRuleFormState(
            name = "Bunt",
            shiftPattern = "Spätdienst",
            modus = HueRuleModus.MANUELL,
            selectedGroupIds = setOf("1"),
            colorMode = ColorMode.COLOR,
            colorPreset = HueColorConverter.ColorPreset.CYAN
        )

        val zurueck = form.baue().toFormState()

        assertEquals(ColorMode.COLOR, zurueck.colorMode)
        assertEquals(HueColorConverter.ColorPreset.CYAN, zurueck.colorPreset)
    }

    @Test
    fun `Rundlauf Sonnenaufgang`() {
        val form = HueRuleFormState(
            name = "Sanft wach",
            shiftPattern = "Frühdienst",
            modus = HueRuleModus.SONNENAUFGANG,
            selectedGroupIds = setOf("82"),
            sunrise = SunriseConfig(
                enabled = true,
                durationMinutes = 20,
                startKelvin = 2000,
                endKelvin = 4500,
                endBrightness = 240,
                startBeforeAlarm = false
            )
        )

        val regel = form.baue()
        assertTrue("Die Rampe muss in der Regel landen", regel.sunrise?.enabled == true)

        val zurueck = regel.toFormState()
        assertEquals(HueRuleModus.SONNENAUFGANG, zurueck.modus)
        assertEquals(20, zurueck.sunrise.durationMinutes)
        assertEquals(4500, zurueck.sunrise.endKelvin)
        assertEquals(false, zurueck.sunrise.startBeforeAlarm)
        assertEquals(setOf("82"), zurueck.selectedGroupIds)
    }

    @Test
    fun `Rundlauf Szene - beide Anker ueberleben`() {
        val form = HueRuleFormState(
            name = "Nachtlicht Frueh",
            shiftPattern = "Frühdienst",
            modus = HueRuleModus.SZENE,
            szene = SzenenAuswahl("wz-nacht", "Nachtlicht", "1", "Wohnzimmer"),
            autoOffEnabled = true,
            autoOffMinutes = 45
        )

        val regel = form.baue()
        val aktion = regel.lightActions.single()
        assertTrue(aktion.isScene)
        assertTrue("Eine Szene geht immer an eine Gruppe", aktion.isGroup)
        assertEquals("Die Zusage fuer autoOffTargetsOf()", true, aktion.on)
        assertEquals(45, aktion.duration)
        assertNull("Nichts faehrt neben der Szene mit", aktion.brightness)
        assertNull(aktion.colorTemperature)

        val zurueck = regel.toFormState()
        assertEquals(HueRuleModus.SZENE, zurueck.modus)
        assertEquals(SzenenAuswahl("wz-nacht", "Nachtlicht", "1", "Wohnzimmer"), zurueck.szene)
        assertTrue(zurueck.autoOffEnabled)
        assertEquals(45, zurueck.autoOffMinutes)
    }

    // --- Ausschliesslichkeit ------------------------------------------------------------------

    @Test
    fun `Modus-Wechsel leckt keine Helligkeit in die Szenen-Aktion`() {
        // Der Nutzer stellt erst manuell etwas ein, wechselt dann auf Szene. Die manuellen Werte
        // bleiben im Formular stehen (damit ein Zurueckwechseln sie nicht verliert) - sie duerfen
        // aber unter keinen Umstaenden in der gespeicherten Regel landen.
        val manuell = HueRuleFormState(
            name = "Test",
            shiftPattern = "Frühdienst",
            modus = HueRuleModus.MANUELL,
            selectedLightIds = setOf("4"),
            selectedGroupIds = setOf("1"),
            brightness = 250,
            colorMode = ColorMode.COLOR,
            colorPreset = HueColorConverter.ColorPreset.BLUE
        )

        val alsSzene = manuell.copy(
            modus = HueRuleModus.SZENE,
            szene = SzenenAuswahl("wz-nacht", "Nachtlicht", "1", "Wohnzimmer")
        )

        val aktion = alsSzene.baue().lightActions.single()
        assertNull(aktion.brightness)
        assertNull(aktion.hue)
        assertNull(aktion.saturation)
        assertNull(aktion.colorTemperature)
        assertEquals("Und nur EINE Aktion, nicht zusaetzlich die Lampe", "1", aktion.targetId)
    }

    @Test
    fun `Modus-Wechsel leckt keine Szene in die manuelle Regel`() {
        val alsManuell = HueRuleFormState(
            name = "Test",
            shiftPattern = "Frühdienst",
            modus = HueRuleModus.MANUELL,
            selectedGroupIds = setOf("1"),
            szene = SzenenAuswahl("wz-nacht", "Nachtlicht", "1", "Wohnzimmer")
        )

        val aktion = alsManuell.baue().lightActions.single()
        assertNull(aktion.sceneId)
        assertNull(aktion.sceneName)
    }

    @Test
    fun `Sonnenaufgang und Szene koennen nicht gleichzeitig gespeichert werden`() {
        val form = HueRuleFormState(
            name = "Test",
            shiftPattern = "Frühdienst",
            modus = HueRuleModus.SZENE,
            szene = SzenenAuswahl("wz-nacht", "Nachtlicht", "1", "Wohnzimmer"),
            sunrise = SunriseConfig(enabled = true, durationMinutes = 20)
        )

        // Strukturell ausgeschlossen: toRule() schreibt die Rampe nur im Modus SONNENAUFGANG.
        assertNull(form.baue().sunrise)
    }

    // --- Namensanker --------------------------------------------------------------------------

    @Test
    fun `der Bridge-Name gewinnt, der gespeicherte rettet den Rest`() {
        val form = HueRuleFormState(
            name = "Test",
            shiftPattern = "Frühdienst",
            modus = HueRuleModus.MANUELL,
            // "4" kennt die Bridge, "99" nicht.
            selectedLightIds = setOf("4", "99")
        )

        val aktionen = form.toRule("r1", lightNames, groupNames, mapOf("99" to "Alte Lampe"))
            .lightActions.associateBy { it.targetId }

        assertEquals("Deckenlampe", aktionen.getValue("4").targetName)
        assertEquals(
            "Ohne den Rueckfall waere der einzige Anker dieser Aktion geloescht",
            "Alte Lampe",
            aktionen.getValue("99").targetName
        )
    }

    // --- Validierung --------------------------------------------------------------------------

    @Test
    fun `validate meldet genau das Fehlende - je Modus`() {
        assertEquals(
            listOf(
                HueRuleFormFehler.NAME_FEHLT,
                HueRuleFormFehler.SCHICHT_FEHLT,
                HueRuleFormFehler.ZIEL_FEHLT
            ),
            HueRuleFormState().validate()
        )

        // Im Szenen-Modus zaehlt eine Lampenauswahl NICHT als Ziel - und umgekehrt.
        val szeneOhneSzene = HueRuleFormState(
            name = "A", shiftPattern = "B",
            modus = HueRuleModus.SZENE,
            selectedLightIds = setOf("4")
        )
        assertEquals(listOf(HueRuleFormFehler.ZIEL_FEHLT), szeneOhneSzene.validate())

        val manuellOhneLampe = HueRuleFormState(
            name = "A", shiftPattern = "B",
            modus = HueRuleModus.MANUELL,
            szene = SzenenAuswahl("s", "S", "1", "W")
        )
        assertEquals(listOf(HueRuleFormFehler.ZIEL_FEHLT), manuellOhneLampe.validate())

        val vollstaendig = HueRuleFormState(
            name = "A", shiftPattern = "B",
            modus = HueRuleModus.SZENE,
            szene = SzenenAuswahl("s", "S", "1", "W")
        )
        assertTrue(vollstaendig.validate().isEmpty())
    }

    // --- Rotation ----------------------------------------------------------------------------

    @Test
    fun `der Saver ueberlebt eine vollbestueckte Instanz`() {
        // Eine Data-Class-Gleichheit faengt JEDES vergessene Feld - genau deshalb serialisiert der
        // Saver das ganze Objekt, statt 19 Einzelfelder von Hand aufzuzaehlen.
        val voll = HueRuleFormState(
            name = "Alles gesetzt",
            shiftPattern = HueRuleUseCase.UNIVERSAL_SHIFT_PATTERN,
            enabled = false,
            modus = HueRuleModus.SZENE,
            selectedLightIds = setOf("4", "10"),
            selectedGroupIds = setOf("1"),
            szene = SzenenAuswahl("wz-nacht", "Nachtlicht", "1", "Wohnzimmer"),
            on = false,
            brightness = 77,
            colorMode = ColorMode.COLOR,
            colorKelvin = 3300,
            colorPreset = HueColorConverter.ColorPreset.PINK,
            sunrise = SunriseConfig(true, 25, 2100, 4400, 233, false),
            autoOffEnabled = true,
            autoOffMinutes = 99,
            showValidationErrors = true
        )

        val roh = formStateJson.encodeToString(voll)
        val zurueck = formStateJson.decodeFromString<HueRuleFormState>(roh)

        assertEquals(voll, zurueck)
    }

    @Test
    fun `das Universalmuster ueberlebt den Rundlauf`() {
        // Der Sentinel "ALL" darf beim Speichern nicht zu einem Schichtnamen werden - sonst
        // ueberschreibt der naechste Speichervorgang eine Universal-Regel unbemerkt.
        val form = HueRuleFormState(
            name = "Immer",
            shiftPattern = HueRuleUseCase.UNIVERSAL_SHIFT_PATTERN,
            modus = HueRuleModus.MANUELL,
            selectedGroupIds = setOf("1")
        )

        val zurueck = form.baue().toFormState()

        assertEquals(HueRuleUseCase.UNIVERSAL_SHIFT_PATTERN, zurueck.shiftPattern)
        assertTrue(isUniversalShiftPattern(zurueck.shiftPattern))
    }
}
