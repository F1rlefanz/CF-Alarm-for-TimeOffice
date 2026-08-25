package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueRuleModus
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueScheduleRule
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.ErrorMessage
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.LoadingScreen
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.hue.rememberLocalNetworkPermissionGate
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.cards.ActionConfigCard
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.cards.AutoOffCard
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.cards.RuleBasicInfoCard
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.cards.RuleModeCard
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.cards.RulePreviewCard
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.cards.SceneSelectionCard
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.cards.ShiftPatternCard
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.cards.SunriseConfigCard
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.cards.TargetSelectionCard
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.HueViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.ShiftViewModel

/**
 * Die einzige netzbeduerftige Aktion dieses Bildschirms: "Regel testen" schaltet echte Lampen.
 * Enum statt Lambda, damit die Absicht einen Activity-Neuaufbau waehrend des offenen
 * Berechtigungsdialogs ueberlebt - siehe [rememberLocalNetworkPermissionGate].
 */
internal enum class HueRuleConfigNetzAktion { RULE_TEST }

/**
 * Hue Regel-Konfiguration.
 *
 * AUFBAU: Der gesamte Formularzustand liegt in EINEM [HueRuleFormState] (siehe dort, warum das
 * kein Selbstzweck ist), der Umbau von und zu einer Regel in dessen reinen Funktionen
 * `toRule`/`toFormState`/`validate`. Hier bleibt nur noch die Verdrahtung.
 *
 * Die Karten liegen im Unterpaket `cards/`, geteilte Hilfen in `HueRuleConfigHelpers.kt`.
 * Welche Karten sichtbar sind, entscheidet allein [HueRuleFormState.modus] - die drei
 * Betriebsarten schliessen sich aus, und der Umschalter dafuer ist die [RuleModeCard].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HueRuleConfigScreen(
    ruleId: String?,
    hueViewModel: HueViewModel,
    shiftViewModel: ShiftViewModel,
    onNavigateBack: () -> Unit,
    onSaveComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    // collectAsStateWithLifecycle statt collectAsState: beide Flows speisen ausschliesslich diesen
    // Bildschirm. Das Sammeln pausiert damit unterhalb von STARTED - im Hintergrund gibt es hier
    // nichts nachzuhalten, und ein `stateIn(WhileSubscribed)`-Timeout weiter unten kann ueberhaupt
    // erst ablaufen. Kein Seiteneffekt haengt daran (die einmaligen Meldungen laufen ueber den
    // LaunchedEffect auf `userMessages`, den das nicht beruehrt).
    val uiState by hueViewModel.uiState.collectAsStateWithLifecycle()
    val shiftState by shiftViewModel.uiState.collectAsStateWithLifecycle()
    val toastContext = LocalContext.current

    // Surfaces one-shot messages from the ViewModel (e.g. the "Auto-Aus verkürzt" hint after
    // "Regel testen") as a Toast.
    LaunchedEffect(hueViewModel) {
        hueViewModel.userMessages.collect { message ->
            Toast.makeText(toastContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    // DER gesamte Formularzustand, in einem einzigen rememberSaveable.
    //
    // rememberSaveable und NICHT remember: MainActivity ist weder auf eine Orientierung
    // festgenagelt (kein screenOrientation) noch faengt sie Konfigurationswechsel ab (kein
    // configChanges) - jede Rotation zerstoert die Activity und baut sie neu auf. Mit `remember`
    // waeren alle Eingaben kommentarlos weg, sobald das Geraet beim Tippen gedreht oder abgelegt
    // wird - ebenso bei Prozesstod im Hintergrund und bei "Aktivitaeten nicht behalten".
    var form by rememberSaveable(stateSaver = HueRuleFormStateSaver) {
        mutableStateOf(HueRuleFormState())
    }

    // Merkt sich, fuer welche Regel das Formular schon einmal aus der gespeicherten Regel gefuellt
    // wurde - und muss deshalb selbst saveable sein. Ohne diesen Waechter waere das
    // rememberSaveable oben wirkungslos: Nach einer Rotation laedt LaunchedEffect(ruleId) die
    // Regel erneut, und der LaunchedEffect(uiState.editingRule) darunter wuerde die gerade
    // wiederhergestellten, ungespeicherten Eingaben mit den ALTEN Werten ueberschreiben.
    var initializedForRuleId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(ruleId) {
        if (ruleId != null) hueViewModel.loadRuleForEditing(ruleId) else hueViewModel.clearEditingRule()
    }

    // Ziele laden, wenn der Bildschirm oeffnet (nur, wenn noch nichts da ist).
    LaunchedEffect(Unit) {
        val ziele = uiState.lightTargets
        if (ziele.lights.isEmpty() && ziele.groups.isEmpty() && ziele.scenes.isEmpty()) {
            hueViewModel.refreshLightTargets()
        }
    }

    LaunchedEffect(uiState.editingRule) {
        uiState.editingRule?.let { rule ->
            if (initializedForRuleId == rule.id) return@LaunchedEffect
            initializedForRuleId = rule.id
            form = rule.toFormState()
        }
    }

    DisposableEffect(Unit) {
        onDispose { hueViewModel.clearEditingRule() }
    }

    val availableShiftPatterns = remember(shiftState.currentShiftConfig) {
        shiftState.currentShiftConfig?.definitions?.filter { it.isEnabled }?.map { it.name } ?: emptyList()
    }

    fun baueRegel(id: String): HueScheduleRule = form.toRule(
        id = id,
        lightNames = uiState.lightTargets.lights.associate { it.id to it.name },
        groupNames = uiState.lightTargets.groups.associate { it.id to it.name },
        // Rueckfall auf die bereits gespeicherten Namen: Beim Bearbeiten einer Regel, deren Ids
        // auf DIESER Bridge unbekannt sind, liefert die Bridge-Liste keinen Namen - ein blosses
        // `bridgeName` wuerde beim Speichern genau den Anker loeschen, der die Regel rettet.
        storedNames = uiState.editingRule?.lightActions
            ?.mapNotNull { action -> action.targetName?.let { action.targetId to it } }
            ?.toMap()
            .orEmpty()
    )

    // Der Regeltest schaltet echte Lampen und braucht deshalb ab Android 17 den lokalen
    // Netzwerkzugriff. Ohne dieses Tor scheiterte er mit einer generischen Netzwerkmeldung, ohne
    // dass je der Systemdialog erschien. Die Testregel wird bewusst ERST HIER gebaut, aus dem
    // aktuellen Formularzustand: Der ist rememberSaveable, ueberlebt also den Activity-Neuaufbau
    // waehrend des Dialogs - eine vorher gebaute Regel waere danach weg.
    val gate = rememberLocalNetworkPermissionGate<HueRuleConfigNetzAktion>(
        onMessage = { hueViewModel.setError(it) }
    ) { _, _ ->
        val testRule = baueRegel("test_${System.currentTimeMillis()}")
            .copy(name = form.name.ifBlank { "Test-Regel" })
        hueViewModel.testRuleExecution(testRule)
    }

    // Nur die nicht zuordenbaren Ziele DIESER Regel. Bei einer neuen Regel gibt es keine.
    val unresolvedFuerRegel = if (ruleId == null) emptyList()
    else uiState.unresolvedTargets.filter { it.ruleId == ruleId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (ruleId != null) "Regel bearbeiten" else "Neue Regel",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            if (form.validate().isEmpty()) {
                                val rule = baueRegel(ruleId ?: HueScheduleRule.generateId())
                                if (ruleId != null) hueViewModel.updateRule(rule)
                                else hueViewModel.createRule(rule)
                                onSaveComplete()
                            } else {
                                form = form.copy(showValidationErrors = true)
                            }
                        }
                    ) {
                        Text("Speichern", fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            uiState.error?.let { error ->
                item {
                    ErrorMessage(message = error, onDismiss = { hueViewModel.clearError() })
                }
            }

            item {
                RuleBasicInfoCard(
                    ruleName = form.name,
                    onRuleNameChange = { form = form.copy(name = it) },
                    isEnabled = form.enabled,
                    onEnabledChange = { form = form.copy(enabled = it) },
                    showValidationErrors = form.showValidationErrors
                )
            }

            item {
                ShiftPatternCard(
                    selectedShiftPattern = form.shiftPattern,
                    onShiftPatternChange = { form = form.copy(shiftPattern = it) },
                    availableShiftPatterns = availableShiftPatterns,
                    showValidationErrors = form.showValidationErrors
                )
            }

            item {
                RuleModeCard(
                    modus = form.modus,
                    onModusChange = { form = form.copy(modus = it) }
                )
            }

            // Ab hier haengt alles am Modus. Die drei Betriebsarten schliessen sich aus - eine
            // Szene bringt Helligkeit und Farbe selbst mit, eine Rampe erzeugt sie ueber die
            // Zeit, manuell stellt sie der Nutzer ein. Deshalb steht immer genau EIN Zielblock
            // da, statt Karten zu entkernen oder Felder auszugrauen.
            when (form.modus) {
                HueRuleModus.SZENE -> item {
                    SceneSelectionCard(
                        lightTargets = uiState.lightTargets,
                        ausgewaehlt = form.szene,
                        onAuswahlChange = { form = form.copy(szene = it) },
                        onRefreshTargets = { hueViewModel.refreshLightTargets() },
                        showValidationErrors = form.showValidationErrors,
                        unresolvedTargets = unresolvedFuerRegel
                    )
                }

                HueRuleModus.MANUELL -> {
                    item { ZielAuswahl(form, uiState, hueViewModel, unresolvedFuerRegel) { form = it } }
                    item {
                        ActionConfigCard(
                            targetOn = form.on,
                            targetBrightness = form.brightness,
                            colorMode = form.colorMode,
                            colorKelvin = form.colorKelvin,
                            colorPreset = form.colorPreset,
                            onTargetOnChange = { form = form.copy(on = it) },
                            onTargetBrightnessChange = { form = form.copy(brightness = it) },
                            onColorModeChange = { form = form.copy(colorMode = it) },
                            onColorKelvinChange = { form = form.copy(colorKelvin = it) },
                            onColorPresetChange = { form = form.copy(colorPreset = it) }
                        )
                    }
                }

                HueRuleModus.SONNENAUFGANG -> {
                    item { ZielAuswahl(form, uiState, hueViewModel, unresolvedFuerRegel) { form = it } }
                    item {
                        SunriseConfigCard(
                            durationMinutes = form.sunrise.durationMinutes,
                            startKelvin = form.sunrise.startKelvin,
                            endKelvin = form.sunrise.endKelvin,
                            endBrightness = form.sunrise.endBrightness,
                            startBeforeAlarm = form.sunrise.startBeforeAlarm,
                            onDurationChange = { form = form.copy(sunrise = form.sunrise.copy(durationMinutes = it)) },
                            onStartKelvinChange = { form = form.copy(sunrise = form.sunrise.copy(startKelvin = it)) },
                            onEndKelvinChange = { form = form.copy(sunrise = form.sunrise.copy(endKelvin = it)) },
                            onEndBrightnessChange = { form = form.copy(sunrise = form.sunrise.copy(endBrightness = it)) },
                            onStartBeforeAlarmChange = { form = form.copy(sunrise = form.sunrise.copy(startBeforeAlarm = it)) }
                        )
                    }
                }
            }

            // Auto-Aus ist querschnittlich: es betrifft, was auch immer die Lichter angeschaltet
            // hat - die manuelle Aktion, die Rampe ODER die Szene. Deshalb eine eigene Karte
            // hinter allen dreien. Nur sichtbar, wenn die Regel ueberhaupt etwas anschaltet;
            // bei einer reinen Ausschalt-Regel gibt es nichts nachzuschalten.
            if (form.schaltetEin) {
                item {
                    AutoOffCard(
                        autoOffEnabled = form.autoOffEnabled,
                        autoOffMinutes = form.autoOffMinutes,
                        sunriseActive = form.modus == HueRuleModus.SONNENAUFGANG,
                        // Bei einer Szene trifft das Aus den GANZEN Raum, nicht nur die Lampen
                        // der Szene - es gibt keinen Gegenbefehl zu einer Szene, die einzige
                        // ehrliche Ruecknahme ist "Raum aus". Das muss dabeistehen.
                        szenenRaumName = form.szene?.groupName?.takeIf { form.modus == HueRuleModus.SZENE },
                        onAutoOffEnabledChange = { form = form.copy(autoOffEnabled = it) },
                        onAutoOffMinutesChange = { form = form.copy(autoOffMinutes = it) }
                    )
                }
            }

            item {
                RulePreviewCard(
                    form = form,
                    onTestRule = { gate(HueRuleConfigNetzAktion.RULE_TEST) }
                )
            }
        }

        if (uiState.isLoading) {
            LoadingScreen()
        }
    }
}

/**
 * Die Lampen-/Gruppenauswahl teilen sich die Modi MANUELL und SONNENAUFGANG. Ausgelagert, damit
 * der Aufruf nicht zweimal wortgleich im `when` steht - laufen die beiden Aufrufe je
 * auseinander, waehlt derselbe Nutzer in zwei Modi aus zwei verschiedenen Listen.
 */
@Composable
private fun ZielAuswahl(
    form: HueRuleFormState,
    uiState: com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.HueUiState,
    hueViewModel: HueViewModel,
    unresolvedTargets: List<com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.UnresolvedRuleTarget>,
    onFormChange: (HueRuleFormState) -> Unit
) {
    TargetSelectionCard(
        lightTargets = uiState.lightTargets,
        selectedLightIds = form.selectedLightIds,
        selectedGroupIds = form.selectedGroupIds,
        onLightSelectionChange = { onFormChange(form.copy(selectedLightIds = it)) },
        onGroupSelectionChange = { onFormChange(form.copy(selectedGroupIds = it)) },
        onRefreshTargets = { hueViewModel.refreshLightTargets() },
        showValidationErrors = form.showValidationErrors,
        unresolvedTargets = unresolvedTargets
    )
}
