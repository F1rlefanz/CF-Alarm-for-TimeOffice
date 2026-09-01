package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.f1rlefanz.cf_alarmfortimeoffice.navigation.MainTab
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.ManualAlarmCard
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.navigation.MAIN_TAB_ZIELE
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.navigation.mainTabZiel
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.DimmerTabContent
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.HomeTabContent
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.HueTabContent
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.SettingsTabContent
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.StatusTabContent
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.WeckerTabContent
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.AlarmViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.AuthViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.CalendarViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.HueViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.MasterPauseViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.text.UIText
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.ShiftViewModel
import kotlinx.coroutines.launch

/**
 * PURE, TESTBAR: entscheidet, ob ein Auth-Fehler hier als Snackbar gehoert.
 *
 * WARUM ES DIESE PRUEFUNG BRAUCHT: `authState.errors` hat zwei Leser. Solange der Nutzer
 * ANGEMELDET ist, ist dieser Bildschirm der einzige - der LoginScreen ist dann per Definition
 * nicht komponiert, und ein Fehler beim Abmelden erreichte den Nutzer bisher nur als Banner ganz
 * oben im Einstellungen-Tab, waehrend der Abmelde-Knopf am unteren Ende derselben langen Liste
 * steht: der Nutzer sah nichts. Ist er dagegen ABGEMELDET, gehoert die Meldung dem LoginScreen
 * (dort landet er als Naechstes) - dann darf hier nichts mehr aufpoppen, sonst zeigt eine
 * gerade verschwindende Oberflaeche die Nachricht des naechsten Bildschirms.
 */
internal fun authFehlerFuerSnackbar(
    fehler: String?,
    istAngemeldet: Boolean
): String? =
    if (istAngemeldet) fehler?.takeIf { it.isNotBlank() } else null

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainContentScreen(
    authViewModel: AuthViewModel,
    calendarViewModel: CalendarViewModel,
    shiftViewModel: ShiftViewModel,
    alarmViewModel: AlarmViewModel,
    hueViewModel: HueViewModel,
    selectedTab: MainTab,
    onSelectedTabChange: (MainTab) -> Unit,
    onShowShiftConfig: () -> Unit,
    onShowCalendarSelection: () -> Unit,
    onShowEventList: () -> Unit,
    onShowHueRuleConfig: () -> Unit,
    onShowHueSettings: () -> Unit,
    onShowDimmerSettings: () -> Unit,
    onShowDimmerPreview: () -> Unit,
    onShowDndSettings: () -> Unit
) {
    val context = LocalContext.current
    // collectAsStateWithLifecycle, nicht collectAsState: Alles hier ist reiner Bildschirm-
    // Zustand (Tab-Inhalte, Snackbar-Fehler) - unterhalb von STARTED gibt es nichts zu
    // zeichnen, also darf das Sammeln pausieren. Wichtig fuer snoozeMinutes: der Flow ist
    // stateIn(SharingStarted.WhileSubscribed(5_000)) auf den DataStore; mit collectAsState
    // lief der Timeout nie ab, solange die Composition lebte. Der Wecker haengt NICHT daran -
    // die Schlummer-Dauer beim Feuern liest AlarmReceiver einmal pro Alarm direkt aus
    // AlarmPrefs (siehe CLAUDE.md); dieser Flow speist nur die Anzeige im Wecker-Tab.
    val authState by authViewModel.uiState.collectAsStateWithLifecycle()
    val calendarState by calendarViewModel.uiState.collectAsStateWithLifecycle()
    val shiftState by shiftViewModel.uiState.collectAsStateWithLifecycle()
    val alarmState by alarmViewModel.uiState.collectAsStateWithLifecycle()
    val skipState by alarmViewModel.skipState.collectAsStateWithLifecycle()
    val tagFreigabeState by alarmViewModel.tagFreigabeState.collectAsStateWithLifecycle()
    val manualAlarmState by alarmViewModel.manualAlarmState.collectAsStateWithLifecycle() // NEU
    val snoozeMinutes by alarmViewModel.snoozeMinutes.collectAsStateWithLifecycle()
    // EINE Sammelstelle fuer die Master-Pause, von hier an drei Tabs verteilt (Home, Wecker,
    // Status). WARUM UEBERHAUPT: Die Pause loescht alle Wecker, stoppt die 6h-Wartung, Dimmer,
    // "Nicht stoeren" und Hue - und war bis dahin ausschliesslich am Schalter ganz unten im
    // Einstellungen-Tab abzulesen. Wer nach dem Urlaub Home und Wecker prueft, sah dort "Keine
    // aktiven Alarme" ohne Grund und "Automatische Alarme: an" - und schloss daraus, die Wecker
    // entstuenden noch. Aus diesem Zustand laeuft die App NIE von allein heraus.
    //
    // Hier oben und nicht in den Tabs: derselbe Flow wuerde sonst mehrfach abonniert, und die
    // Tab-Inhalte bleiben reine Zustandsempfaenger (testbar ohne Hilt).
    // collectAsStateWithLifecycle aus demselben Grund wie oben - der Flow ist
    // stateIn(WhileSubscribed(5_000)) auf den DataStore.
    val masterPauseViewModel: MasterPauseViewModel = hiltViewModel()
    val masterPausePaused by masterPauseViewModel.paused.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    // Manueller Alarm ist die Ausnahme, nicht der Normalfall -> aus der Home-Hauptflaeche in ein
    // per FAB geoeffnetes Bottom-Sheet ausgelagert (Home bleibt auf die Uebersicht fokussiert).
    var showManualAlarmSheet by remember { mutableStateOf(false) }

    // FEHLER-SICHTBARKEIT: Lade-/Sync-Fehler der Kern-Pipeline nicht mehr still verschlucken -
    // in einer Wecker-App darf der Nutzer einen Leerzustand nicht faelschlich fuer die Wahrheit
    // halten. Fehler werden als Snackbar gezeigt und danach geleert (sonst blieben sie in State
    // haengen und die Snackbar liesse sich nicht erneut ausloesen).
    LaunchedEffect(calendarState.error) {
        calendarState.error?.let { msg ->
            val result = snackbarHostState.showSnackbar(
                message = msg,
                actionLabel = "Wiederholen",
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) {
                // MUSS ueber calendarViewModel laufen: Der Fehler kommt aus dessen
                // CalendarUiState, und nur dieses rendert Home. Der frueher hier gerufene
                // mainViewModel.forceRefreshCalendarEvents() schrieb ausschliesslich in den
                // CalendarStateHolder - eine Einbahnstrasse zum ShiftViewModel, aus der die
                // CalendarUiState nie liest. Der Retry lud also brav nach, ohne dass Home je
                // etwas davon sah, und ein erneutes Scheitern wurde dort nur geloggt statt
                // gemeldet: "Wiederholen" fuehrte zurueck in denselben stummen Zustand.
                // refreshData() aktualisiert beides - UiState UND StateHolder.
                calendarViewModel.refreshData(forceRefresh = true)
            }
            calendarViewModel.clearError()
        }
    }
    // Verwaiste Wecker nach einer Kalender-Abwahl melden sich NICHT hier, sondern als bleibende
    // Karte im Status-Tab (`VerwaisteWeckerNachAbwahlCard`). Vorher stand hier eine Snackbar mit
    // `SnackbarDuration.Indefinite` - der einzige Indefinite-Aufruf der App. `showSnackbar`
    // serialisiert ueber einen Mutex: solange sie stand (und sie ging nur per Aktion oder
    // Wischen weg), suspendierten alle uebrigen Kanaele dieses Hosts, und die `clearError()`
    // dahinter liefen ebenfalls nicht - Kalender-, Schicht- und Wecker-Fehler erreichten den
    // Nutzer gar nicht mehr. Ein bleibender Hinweis gehoert in eine Karte, nicht in den
    // gemeinsamen Meldungskanal.
    LaunchedEffect(shiftState.error) {
        shiftState.error?.let { msg ->
            snackbarHostState.showSnackbar(message = msg, duration = SnackbarDuration.Long)
            shiftViewModel.clearError()
        }
    }
    // Regel-Nachzug beim Umbenennen einer Schicht: eigener Kanal neben `error`, siehe
    // ShiftUiState.regelNachzugHinweis. Hierher kommt er, wenn die Umbenennung NICHT ueber den
    // Schicht-Editor kam, sondern ueber einen fremden Schreiber (Konfigurations-Import,
    // Ruecksicherung) - dann steht der Nutzer in den Einstellungen und nicht im
    // ShiftConfigScreen, der denselben Hinweis als bleibende Karte zeigt. Beide Screens schliessen
    // sich gegenseitig aus, es meldet also immer genau einer.
    LaunchedEffect(shiftState.regelNachzugHinweis) {
        shiftState.regelNachzugHinweis?.let { msg ->
            snackbarHostState.showSnackbar(message = msg, duration = SnackbarDuration.Long)
            shiftViewModel.clearRegelNachzugHinweis()
        }
    }
    LaunchedEffect(alarmState.error) {
        alarmState.error?.let { msg ->
            snackbarHostState.showSnackbar(message = msg, duration = SnackbarDuration.Long)
            alarmViewModel.clearError()
        }
    }
    // Auth-Fehler, waehrend der Nutzer angemeldet ist - praktisch immer ein gescheitertes
    // Abmelden. Er steht dann im Einstellungen-Tab, wo der Abmelde-Knopf ganz unten liegt; das
    // Fehler-Banner dieses Tabs sitzt aber ganz OBEN in einer langen Scroll-Liste und damit
    // ausserhalb des Bildes. Die Snackbar meldet dort, wo er wirklich hinsieht.
    // BEWUSST OHNE clearError(): das Banner im Einstellungen-Tab bleibt die nachlesbare Fassung
    // und wird vom Nutzer weggetippt. Wer hier leert, nimmt ihm die zweite Chance, es zu lesen.
    LaunchedEffect(authState.errors.error, authState.isSignedIn) {
        authFehlerFuerSnackbar(
            fehler = authState.errors.error,
            istAngemeldet = authState.isSignedIn
        )?.let { msg ->
            snackbarHostState.showSnackbar(message = msg, duration = SnackbarDuration.Long)
        }
    }

    // NAVIGATION IN EINER SCHUBLADE STATT IN EINER UNTEREN LEISTE (seit v1.38.0).
    //
    // WARUM: Sechs Ziele passen nicht in eine Material-3-Navigationsleiste - die ist fuer drei
    // bis fuenf gebaut. Auf dem Geraet des Eigentuemers (hochgestellte Anzeigegroesse, 320 dp
    // Breite) blieben 53,3 dp pro Fach, waehrend die aktive Pille hinter dem Symbol 64 dp breit
    // ist: das erste Element wurde links angeschnitten, benachbarte Pillen ueberschnitten sich,
    // und die Beschriftungen kuerzten zu "Dimm..." und "Einste...".
    //
    // WARUM DIE SCHUBLADE HIER LIEGT UND NICHT IN MainScreen: Die vier Onboarding-Gates
    // (BatteryExemption, UnusedAppRestrictions, TimeOfficeHealthCheck, OEMWarning) sind Zweige
    // DESSELBEN `when` wie dieser Bildschirm - sie ERSETZEN ihn. Eine Ebene hoeher liesse sich
    // per Wischgeste mitten aus einem Gate herausnavigieren, ohne dass dessen Dismissed-Flag
    // geschrieben wird; handleAuthenticationSuccess() wuerfe den Nutzer beim naechsten Durchlauf
    // zurueck ins Gate. Hier ist die Schublade waehrend der Gates schlicht nicht komponiert -
    // deshalb braucht `gesturesEnabled` auch keine Einschraenkung.
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val schubladenScope = rememberCoroutineScope()
    val ziel = mainTabZiel(selectedTab)

    // Die Kopfzeile klappt beim Runterscrollen weg und kommt bei der kleinsten
    // Aufwaertsbewegung zurueck. Sie ersetzt ZWEI frueher gleichzeitig sichtbare Zeilen: die
    // gepinnte App-Titelzeile ("CF-Alarm for TimeOffice", ~64 dp, scrollte nie weg) und die
    // Ueberschrift, die jeder Tab-Inhalt zusaetzlich selbst setzte (~40 dp) - zusammen rund
    // 15 % der Hoehe dafuer, dem Nutzer zu sagen, was er selbst angetippt hat.
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            // ZWINGEND die Ueberladung MIT `drawerState`: nur sie registriert intern den
            // PredictiveBackHandler(enabled = drawerState.isOpen) (Material3 1.4.0,
            // NavigationDrawer.kt:633 -> :643 -> :955). ModalNavigationDrawer selbst behandelt
            // Zurueck NICHT, und die parameterlose ModalDrawerSheet-Ueberladung (:590) auch
            // nicht. Das ist kein Schoenheitsfehler: auf dem Home-Tab ist der BackHandler in
            // MainScreen bewusst AUS (dort soll Zurueck die App verlassen) - mit der falschen
            // Ueberladung wuerde ein Zurueck bei offener Schublade also die App beenden.
            ModalDrawerSheet(
                drawerState = drawerState,
                // Die Compose-Wurzel in MainActivity hat die Insets bereits mit
                // safeDrawingPadding() VERBRAUCHT. Hier nichts aufschlagen - das waere die
                // doppelte Polsterung, gegen die RandloseDarstellungTest gebaut ist.
                windowInsets = WindowInsets(0, 0, 0, 0)
            ) {
                // Scrollbar, weil sechs Eintraege plus Kopf bei 320 dp und grosser Schrift
                // hoeher werden koennen als der Bildschirm.
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Spacer(modifier = Modifier.height(12.dp))
                    // Der Name der App steht jetzt hier - einmal, auf Wunsch sichtbar -
                    // statt auf jedem Bildschirm eine gepinnte Zeile zu belegen.
                    Text(
                        text = UIText.APP_TITLE,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp)
                    )
                    HorizontalDivider()
                    MAIN_TAB_ZIELE.forEach { eintrag ->
                        NavigationDrawerItem(
                            // dekorativ: das Label daneben traegt den Namen. Die alten
                            // NavigationBarItems hatten contentDescription == label und liessen
                            // TalkBack damit jedes Ziel doppelt vorlesen.
                            icon = { Icon(eintrag.icon, contentDescription = null) },
                            label = { Text(eintrag.titel) },
                            selected = eintrag.tab == selectedTab,
                            onClick = {
                                schubladenScope.launch { drawerState.close() }
                                onSelectedTabChange(eintrag.tab)
                            },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                        )
                    }
                }
            }
        }
    ) {
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(ziel.titel) },
                navigationIcon = {
                    IconButton(onClick = { schubladenScope.launch { drawerState.open() } }) {
                        // Nicht dekorativ: das Symbol ist der einzige Traeger dieser Aktion.
                        Icon(Icons.Filled.Menu, contentDescription = "Navigation öffnen")
                    }
                },
                actions = {
                    if (selectedTab == MainTab.HOME) {
                        IconButton(
                            onClick = {
                                // Gleicher Grund wie beim Snackbar-Retry oben: der frueher hier
                                // gerufene mainViewModel.forceRefreshCalendarEvents() schrieb nur
                                // in den CalendarStateHolder. Der "Aktualisieren"-Knopf lud damit
                                // zwar Events (die Schichterkennung bekam sie ueber den
                                // StateHolder), aber Home zeigte weder Ladeanzeige noch die neue
                                // Liste noch einen Fehler - er wirkte wie ein toter Knopf.
                                calendarViewModel.refreshData(forceRefresh = true)
                            }
                        ) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = "Aktualisieren",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                scrollBehavior = scrollBehavior
            )
        },
        floatingActionButton = {
            // Nur im Home-Tab: manuellen Alarm als Sekundaer-Aktion anbieten.
            if (selectedTab == MainTab.HOME) {
                ExtendedFloatingActionButton(
                    onClick = { showManualAlarmSheet = true },
                    // dekorativ: der Knopftext "Manueller Alarm" daneben sagt es bereits -
                    // eine eigene Beschreibung liesse den Screenreader die Aktion doppelt lesen
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Manueller Alarm") }
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            // Tab-Inhalt mit eigener Fehlerbehandlung
            when (selectedTab) {
                MainTab.HOME -> {
                    HomeTabContent(
                        calendarState = calendarState,
                        shiftState = shiftState,
                        alarmState = alarmState,
                        skipState = skipState,
                        masterPausePaused = masterPausePaused,
                        onNavigateToWecker = { onSelectedTabChange(MainTab.WECKER) },
                        onShowEventList = onShowEventList,
                        onReauthorize = {
                            authViewModel.requestCalendarAuthorization(context as? android.app.Activity)
                        }
                    )
                }
                MainTab.WECKER -> {
                    WeckerTabContent(
                        shiftState = shiftState,
                        alarmState = alarmState,
                        skipState = skipState,
                        tagFreigabeState = tagFreigabeState,
                        snoozeMinutes = snoozeMinutes,
                        masterPausePaused = masterPausePaused,
                        onUpdateShiftConfig = shiftViewModel::updateShiftConfig,
                        onSkipNextAlarm = alarmViewModel::skipNextAlarm,
                        // Nach dem Aufheben MUSS der Alarm-Bestand neu aus dem Kalender
                        // aufgebaut werden - skipNextAlarm() hat ihn geloescht. refreshData()
                        // ist der bereits erprobte Weg dorthin (inkl. Vollstaendigkeits-Sperre
                        // und Race-Guard); der Callback laeuft erst, wenn das Flag weg ist.
                        onCancelSkip = {
                            // forceRefresh = true ist PFLICHT, nicht Vorsicht: refreshData(false)
                            // macht ausschliesslich einen checkTokenValidity() und laedt weder
                            // Events noch synchronisiert es. Am Emulator gemessen (18.08.2026) -
                            // der Merker verschwand, der geloeschte Wecker kam aber nicht zurueck.
                            alarmViewModel.cancelSkip {
                                calendarViewModel.refreshData(forceRefresh = true)
                            }
                        },
                        onTagFreigeben = alarmViewModel::tagFreigeben,
                        // Wie beim Aufheben des Ueberspringens: der Bestand muss neu aus dem
                        // Kalender aufgebaut werden, denn die Freigabe hat die Wecker dieses
                        // Tages geloescht. forceRefresh = true ist auch hier Pflicht -
                        // refreshData(false) macht nur einen Token-Check und laedt nichts.
                        onFreigabeZuruecknehmen = { tag ->
                            alarmViewModel.freigabeZuruecknehmen(tag) {
                                calendarViewModel.refreshData(forceRefresh = true)
                            }
                        },
                        onShowShiftConfig = onShowShiftConfig,
                        onSnoozeMinutesChange = alarmViewModel::setSnoozeMinutes
                    )
                }
                MainTab.STATUS -> {
                    StatusTabContent(
                        authState = authState,
                        calendarState = calendarState,
                        shiftState = shiftState,
                        calendarViewModel = calendarViewModel,
                        authViewModel = authViewModel,
                        onShowCalendarSelection = onShowCalendarSelection,
                        masterPausePaused = masterPausePaused,
                        // Der Tap ist der ausdrueckliche Nutzerwille - erst er schreibt.
                        onResumeMasterPause = { masterPauseViewModel.setPaused(false) }
                    )
                }
                MainTab.SETTINGS -> {
                    SettingsTabContent(
                        authViewModel = authViewModel,
                        onShowCalendarSelection = onShowCalendarSelection,
                        onShowDndSettings = onShowDndSettings
                    )
                }
                MainTab.HUE -> {
                    HueTabContent(
                        hueViewModel = hueViewModel,
                        onNavigateToRuleConfig = onShowHueRuleConfig,
                        onNavigateToSettings = onShowHueSettings
                    )
                }
                MainTab.DIMMER -> {
                    DimmerTabContent(onNavigateToRules = onShowDimmerSettings, onNavigateToPreview = onShowDimmerPreview)
                }
            }
        }
    }
    }

    // Bottom-Sheet fuer den manuellen Alarm (per FAB im Home-Tab geoeffnet).
    if (showManualAlarmSheet) {
        ModalBottomSheet(
            onDismissRequest = { showManualAlarmSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            ManualAlarmCard(
                manualAlarmState = manualAlarmState,
                onSelectDate = alarmViewModel::selectManualAlarmDate,
                onSelectShift = alarmViewModel::selectManualAlarmShift,
                onCreate = alarmViewModel::createManualAlarm,
                onDelete = alarmViewModel::deleteManualAlarm,
                onClearError = alarmViewModel::clearManualAlarmError,
                onNavigateToSettings = {
                    showManualAlarmSheet = false
                    onShowShiftConfig()
                }
            )
        }
    }
}
