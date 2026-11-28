package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.EchteSchichtKonfiguration
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.connection.HueBridgeConnectionManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.BridgeConnectionInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueRuleModus
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueScheduleRule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.SunriseConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.IHueConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling.HueSmartScheduler
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.HueRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.TypLichtQuelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.IHueBridgeUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.IHueLightUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.IHueLightUseCaseAdvanced
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.util.HueColorConverter
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IShiftConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.ITageslichtRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.HueViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.whenever
import java.time.LocalTime

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
        assertTrue("Manuell traegt keine Szene", zurueck.szenen.isEmpty())
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
            szenen = listOf(SzenenAuswahl("wz-nacht", "Nachtlicht", "1", "Wohnzimmer")),
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
        assertEquals(listOf(SzenenAuswahl("wz-nacht", "Nachtlicht", "1", "Wohnzimmer")), zurueck.szenen)
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
            szenen = listOf(SzenenAuswahl("wz-nacht", "Nachtlicht", "1", "Wohnzimmer"))
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
            szenen = listOf(SzenenAuswahl("wz-nacht", "Nachtlicht", "1", "Wohnzimmer"))
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
            szenen = listOf(SzenenAuswahl("wz-nacht", "Nachtlicht", "1", "Wohnzimmer")),
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
            szenen = listOf(SzenenAuswahl("s", "S", "1", "W"))
        )
        assertEquals(listOf(HueRuleFormFehler.ZIEL_FEHLT), manuellOhneLampe.validate())

        val vollstaendig = HueRuleFormState(
            name = "A", shiftPattern = "B",
            modus = HueRuleModus.SZENE,
            szenen = listOf(SzenenAuswahl("s", "S", "1", "W"))
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
            szenen = listOf(SzenenAuswahl("wz-nacht", "Nachtlicht", "1", "Wohnzimmer")),
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

    // --- Mehrere Szenen ------------------------------------------------------------------------

    @Test
    fun `eine Regel darf mehrere Raeume mit je einer Szene schalten`() {
        // Die Kette darunter konnte das von Anfang an - convertRuleToLightActions laeuft ueber
        // ALLE Aktionen, autoOffTargetsOf() flatMapt und dedupliziert. Begrenzt hat allein die
        // Oberflaeche, und genau das ist hier weg.
        val form = HueRuleFormState(
            name = "Abendrunde",
            shiftPattern = "Spätdienst",
            modus = HueRuleModus.SZENE,
            szenen = listOf(
                SzenenAuswahl("wz-nacht", "Nachtlicht", "1", "Wohnzimmer"),
                SzenenAuswahl("sz-lesen", "Lesen", "82", "Schlafzimmer")
            ),
            autoOffEnabled = true,
            autoOffMinutes = 20
        )

        val regel = form.baue()
        val aktionen = regel.lightActions

        assertEquals("Je gewaehlter Szene eine Aktion", 2, aktionen.size)
        assertTrue("Beide sind Szenen-Aktionen", aktionen.all { it.isScene })
        assertTrue("Beide gehen an ihre Gruppe", aktionen.all { it.isGroup })
        assertEquals(setOf("wz-nacht", "sz-lesen"), aktionen.mapNotNull { it.sceneId }.toSet())
        assertEquals(setOf("1", "82"), aktionen.map { it.targetId }.toSet())
        assertTrue(
            "Jede traegt die Auto-Aus-Dauer - sonst bliebe ein Raum an",
            aktionen.all { it.duration == 20 }
        )

        // Und der Rundlauf haelt beide.
        val zurueck = regel.toFormState()
        assertEquals(2, zurueck.szenen.size)
        assertEquals(form.szenen, zurueck.szenen)
    }

    @Test
    fun `mehrere Szenen ergeben mehrere Auto-Aus-Ziele, eines je Raum`() {
        val form = HueRuleFormState(
            name = "Abendrunde",
            shiftPattern = "Spätdienst",
            modus = HueRuleModus.SZENE,
            szenen = listOf(
                SzenenAuswahl("wz-nacht", "Nachtlicht", "1", "Wohnzimmer"),
                SzenenAuswahl("sz-lesen", "Lesen", "82", "Schlafzimmer")
            ),
            autoOffEnabled = true,
            autoOffMinutes = 30
        )

        // Das ist die Bedingung, auf die autoOffTargetsOf() filtert: on == true UND duration > 0.
        // Faellt sie fuer eine der beiden weg, bleibt genau dieser Raum hell.
        val aktionen = form.baue().lightActions
        assertTrue(aktionen.all { it.on == true && (it.duration ?: 0) > 0 })
        assertEquals(
            "Zwei verschiedene Gruppen - der BridgeTimer benennt seine Zeitplaene danach",
            2,
            aktionen.map { it.targetId }.distinct().size
        )
    }

    @Test
    fun `ohne ausgewaehlte Szene fehlt weiterhin das Ziel`() {
        val leer = HueRuleFormState(
            name = "A", shiftPattern = "B",
            modus = HueRuleModus.SZENE,
            szenen = emptyList()
        )
        assertEquals(listOf(HueRuleFormFehler.ZIEL_FEHLT), leer.validate())
    }

    // --- Editor am Typ (Umbau #252) -------------------------------------------------------------

    @get:Rule
    val ordner = TemporaryFolder()

    private fun typ(id: String, name: String, vararg regeln: HueScheduleRule) =
        ShiftDefinition(id = id, name = name, keywords = listOf(name), alarmTime = LocalTime.of(5, 30))
            .let { it.copy(licht = it.licht.mitRegeln(regeln.toList())) }

    @Test
    fun `Ein Formular mit Typ verlangt keine Schicht`() {
        val amTyp = HueRuleFormState(typId = "s", name = "A", selectedGroupIds = setOf("1"))
        assertEquals(emptyList<HueRuleFormFehler>(), amTyp.validate())
        // Gegenprobe ohne Typ (Einstieg im Hue-Bereich): die Schicht bleibt Pflicht.
        assertEquals(listOf(HueRuleFormFehler.SCHICHT_FEHLT), amTyp.copy(typId = null).validate())
        // Name und Ziel bleiben auch am Typ Pflicht.
        assertEquals(
            listOf(HueRuleFormFehler.NAME_FEHLT, HueRuleFormFehler.ZIEL_FEHLT),
            HueRuleFormState(typId = "s").validate()
        )
    }

    @Test
    fun `Vorbelegung - die erste Regel am Typ ist der Sonnenaufgang, jede weitere Licht an zum Wecker`() {
        val erste = anfangsFormular(typ("s", "Spätdienst"), neueRegel = true)
        assertEquals("Sonnenaufgang 30 min vor Wecker", erste.name)
        assertEquals(HueRuleModus.SONNENAUFGANG, erste.modus)
        assertEquals(30, erste.sunrise.durationMinutes)
        assertTrue(erste.sunrise.startBeforeAlarm)
        assertEquals("s", erste.typId)
        assertEquals("Spätdienst", erste.shiftPattern)
        // Nur vorbelegt: ohne Ziel laesst sich noch nichts speichern.
        assertEquals(listOf(HueRuleFormFehler.ZIEL_FEHLT), erste.validate())

        val vorhandene = HueRuleFormState(shiftPattern = "Spätdienst", name = "x", selectedGroupIds = setOf("1")).baue("r1")
        val mitRegel = typ("s", "Spätdienst", vorhandene)
        val zweite = anfangsFormular(mitRegel, neueRegel = true)
        assertEquals("Licht an zum Wecker", zweite.name)
        assertEquals(HueRuleModus.MANUELL, zweite.modus)
        assertTrue(zweite.on)

        // Bearbeiten: keine Vorbelegung, die geladene Regel fuellt das Formular.
        assertEquals(
            HueRuleFormState(typId = "s", shiftPattern = "Spätdienst"),
            anfangsFormular(mitRegel, neueRegel = false)
        )
    }

    @Test
    fun `der gespeicherte Typ gewinnt ueber eine geladene oder wiederhergestellte Schicht`() {
        val geladen = HueRuleFormState(name = "x", shiftPattern = "Alter Name", selectedGroupIds = setOf("1"))

        val gelesen = geladen.amTyp(typ("s", "Spätdienst"))

        assertEquals("s", gelesen.typId)
        assertEquals("Spätdienst", gelesen.shiftPattern)
        assertEquals("Spätdienst", gelesen.baue().shiftPattern)
    }

    /** Das echte ViewModel ueber dem echten Regel-UseCase und der echten Schicht-Konfiguration. */
    private fun viewModelAn(repo: IShiftConfigRepository): HueViewModel {
        val ruleUseCase = HueRuleUseCase(
            configRepository = mock<IHueConfigRepository>(),
            lightUseCase = mock<IHueLightUseCaseAdvanced>(),
            typLicht = TypLichtQuelle.Leser { repo.getCurrentShiftConfig() },
            schichtKonfiguration = repo
        )
        val bridge = mock<IHueBridgeUseCase>()
        whenever(bridge.getDiscoveryStatus()).thenReturn(emptyFlow())
        bridge.stub { on { getBridgeConnectionInfo() } doReturn Result.success(BridgeConnectionInfo(isConnected = false)) }
        val verbindung = mock<HueBridgeConnectionManager> {
            on { connectionStatus } doReturn MutableStateFlow<HueBridgeConnectionManager.ConnectionState>(
                HueBridgeConnectionManager.ConnectionState.DISCONNECTED
            )
        }
        val wecker = mock<IAlarmUseCase> { on { activeAlarms } doReturn MutableStateFlow(emptyList<AlarmInfo>()) }
        return HueViewModel(
            bridge, mock<IHueLightUseCase>(), ruleUseCase, verbindung, mock<HueSmartScheduler>(), wecker,
            mock<ITageslichtRepository>()
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class) // Dispatchers.setMain/resetMain
    @Test
    fun `Speichern landet am Typ des Editors und meldet erst nach dem Schreiben zurueck`() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val konfiguration = EchteSchichtKonfiguration(ordner.newFolder())
        try {
            konfiguration.repo.saveShiftConfig(
                ShiftConfig(definitions = listOf(typ("f", "Frühdienst"), typ("s", "Spätdienst")))
            ).getOrThrow()
            val viewModel = viewModelAn(konfiguration.repo)
            val spaet = konfiguration.repo.getCurrentShiftConfig().getOrThrow().definitions[1]

            // Wie der Editor: Vorbelegung, Ziel waehlen, mit dem Typ lesen, speichern - zweimal mit derselben id.
            val regel = anfangsFormular(spaet, neueRegel = true).copy(selectedGroupIds = setOf("1"))
                .amTyp(spaet).baue("rule_editor")
            repeat(2) {
                val gespeichert = CompletableDeferred<Unit>()
                viewModel.speichereRegelAmTyp(regel) { gespeichert.complete(Unit) }
                withTimeout(10_000) { gespeichert.await() }
            }

            val danach = konfiguration.repo.getCurrentShiftConfig().getOrThrow().definitions
            assertEquals(listOf("rule_editor"), danach[1].licht.regeln.map { it.id })
            assertTrue("am anderen Typ nichts", danach[0].licht.regeln.isEmpty())

            // Gibt es den Typ nicht (mehr), bleibt der Rueckruf aus und der Editor zeigt den Grund.
            var zurueck = false
            viewModel.speichereRegelAmTyp(regel.copy(id = "rule_2", shiftPattern = "Weg")) { zurueck = true }
            val grund = withTimeout(10_000) { viewModel.uiState.first { it.error != null } }.error
            assertEquals("Den Schichttyp „Weg“ gibt es nicht.", grund)
            assertFalse(zurueck)
        } finally {
            konfiguration.schliessen()
            Dispatchers.resetMain()
        }
    }
}
