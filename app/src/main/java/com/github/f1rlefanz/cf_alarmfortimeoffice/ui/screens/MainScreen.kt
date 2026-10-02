package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.f1rlefanz.cf_alarmfortimeoffice.navigation.GateEinstieg
import com.github.f1rlefanz.cf_alarmfortimeoffice.navigation.GateLage
import com.github.f1rlefanz.cf_alarmfortimeoffice.navigation.GateSchritt
import com.github.f1rlefanz.cf_alarmfortimeoffice.navigation.MainTab
import com.github.f1rlefanz.cf_alarmfortimeoffice.navigation.NavigationState
import com.github.f1rlefanz.cf_alarmfortimeoffice.navigation.einstiegNachSpaeter
import com.github.f1rlefanz.cf_alarmfortimeoffice.navigation.naechsterGateSchritt
import com.github.f1rlefanz.cf_alarmfortimeoffice.navigation.wirdAngezeigtIn
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmMaintenanceService
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.BatteryOptimizationHelper
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.TimeOfficeHealthHelper
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.UnusedAppRestrictionsHelper
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.timing.UIConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.AlarmViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.AuthViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.CalendarViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.HueViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.MainViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.NavigationViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.ShiftViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Liest die [GateLage] fuer [einstieg] - die EINZIGE Stelle, an der die Gate-Bedingungen gelesen
 * werden (vorher stand die Unused-App-Bedingung dreimal, die TimeOffice-Bedingung zweimal im
 * Code). Entschieden wird in [naechsterGateSchritt], nicht hier.
 *
 * Bewusst LAZY und in derselben Reihenfolge wie die Kette selbst (Kalender -> Akku -> Unused ->
 * TimeOffice -> OEM): es wird nur gelesen, was der Einstieg bis zu seiner Entscheidung
 * tatsaechlich braucht.
 *  - Solange das Kalender- oder Akku-Gate offen ist, unterbleibt der asynchrone
 *    Unused-App-Check (ListenableFuture).
 *  - Nach der Kalenderauswahl ohne Akku-Ausnahme wird zuerst nur das "Spaeter"-Flag gelesen.
 *  - Der OEM-Merker wird erst gelesen, wenn TimeOffice nichts mehr will.
 *  - Ein `SPAETER_*`-Einstieg liest erst AB dem Gate hinter dem uebersprungenen (siehe
 *    [naechsterGateSchritt]) - dessen eigenes Flag spielt fuer die laufende Kette keine Rolle.
 * Ein ungelesenes Feld steht auf seinem Neutralwert und wird fuer diesen Einstieg von
 * [naechsterGateSchritt] nicht ausgewertet (siehe [GateLage]). Wer einen Einstieg ein weiteres
 * Feld befragen laesst, muss es hier auch lesen.
 *
 * @param kalenderGewaehlt nur fuer [GateEinstieg.AUTO]; die uebrigen Einstiege fragen es nicht.
 */
private suspend fun leseGateLage(
    context: Context,
    einstieg: GateEinstieg,
    kalenderGewaehlt: Boolean = true
): GateLage = when (einstieg) {
    GateEinstieg.AUTO -> {
        val akku = GateLage(
            kalenderGewaehlt = kalenderGewaehlt,
            akkuAusnahme = BatteryOptimizationHelper.isExempted(context),
            akkuAbgelehnt = BatteryOptimizationHelper.isBatteryPromptDismissed(context)
        )
        // Kurzschluss, solange Kalender- oder Akku-Gate noch OFFEN ist - dann kommt eines davon
        // zuerst, und alles Weitere waere ein unnoetiger Read (beim Unused-App-Check ein
        // Async-Call). Erledigt ist das Akku-Gate auch nach "Spaeter" (GateLage.akkuGateErledigt).
        if (!akku.kalenderGewaehlt || !akku.akkuGateErledigt) {
            akku
        } else {
            leseAbUnused(context).copy(
                akkuAusnahme = akku.akkuAusnahme,
                akkuAbgelehnt = akku.akkuAbgelehnt
            )
        }
    }

    GateEinstieg.NACH_KALENDER ->
        if (BatteryOptimizationHelper.isExempted(context)) {
            leseAbUnused(context).copy(akkuAusnahme = true)
        } else if (BatteryOptimizationHelper.isBatteryPromptDismissed(context)) {
            // "Spaeter" heisst ERLEDIGT - auch hier, nicht nur auf dem automatischen Weg.
            leseAbUnused(context).copy(akkuAbgelehnt = true)
        } else {
            GateLage(akkuAusnahme = false, akkuAbgelehnt = false)
        }

    // Der Aufrufer (Ergebnis des Akku-Dialogs) kommt nur hierher, wenn die Ausnahme erteilt ist.
    GateEinstieg.NACH_AKKU -> leseAbUnused(context).copy(akkuAusnahme = true)

    GateEinstieg.SPAETER_AKKU -> leseAbUnused(context).copy(akkuAbgelehnt = true)

    GateEinstieg.NACH_EINSTELLUNGEN, GateEinstieg.SPAETER_UNUSED -> leseAbTimeOffice(context)

    GateEinstieg.SPAETER_TIMEOFFICE -> GateLage(oemFaellig = faelligerOemTyp(context))
}

/** Liest ab dem Unused-App-Gate: Unused, sonst TimeOffice, sonst OEM. */
private suspend fun leseAbUnused(context: Context): GateLage =
    if (unusedAppGateNoetig(context)) GateLage(unusedNoetig = true) else leseAbTimeOffice(context)

/** Liest ab dem TimeOffice-Gate: TimeOffice, sonst OEM. */
private suspend fun leseAbTimeOffice(context: Context): GateLage =
    if (timeOfficeGateNoetig(context)) {
        GateLage(timeOfficeNoetig = true)
    } else {
        GateLage(oemFaellig = faelligerOemTyp(context))
    }

/** Der Herstellertyp, dessen OEM-Warnscreen noch nie gezeigt wurde - sonst `null`. */
private suspend fun faelligerOemTyp(context: Context): BatteryOptimizationHelper.OEMType? {
    val oemTyp = BatteryOptimizationHelper.getOEMType()
    return if (BatteryOptimizationHelper.shouldNavigateToOemWarningScreen(context, oemTyp)) oemTyp else null
}

private suspend fun unusedAppGateNoetig(context: Context): Boolean =
    UnusedAppRestrictionsHelper.isRestricted(context) &&
        !UnusedAppRestrictionsHelper.isDismissed(context)

/**
 * TimeOffice-Gate: CFAlarms Alarme haengen an einem Kalender, den TimeOffice lokal befuellt
 * (siehe TimeOfficeHealthHelper). Nur pruefbar (Akku-Ausnahme fuer ein fremdes Package), wenn
 * TimeOffice ueberhaupt installiert ist - sonst irrelevant fuer diesen Nutzer.
 */
private suspend fun timeOfficeGateNoetig(context: Context): Boolean =
    TimeOfficeHealthHelper.isInstalled(context) &&
        !TimeOfficeHealthHelper.isBatteryExempted(context) &&
        !TimeOfficeHealthHelper.isPromptDismissed(context)

/**
 * Setzt die Gate-Kette auf einem AKTIVEN Weg fort - nach der Kalenderauswahl, nach erteilter
 * Akku-Ausnahme, nach der Rueckkehr aus einer Einstellungsseite und nach "Spaeter"/Zurueck an
 * einem Gate (frueher `proceedPastGates()` plus zwei fast wortgleiche Kopien davor; "Spaeter"
 * fuehrte bis Issue #132 nach Home). Der automatische Weg laeuft dagegen ueber
 * [NavigationViewModel.handleAuthenticationSuccess], weil nur er den `MainContent`-Waechter
 * braucht.
 *
 * Nur die aktiven Wege enden in `Fertig` (Abschluss mit `scheduleNext()`); der automatische Weg
 * laesst den Nutzer ohne offenes Gate, wo er ist.
 */
private suspend fun setzeGateKetteFort(
    context: Context,
    navigationViewModel: NavigationViewModel,
    einstieg: GateEinstieg
) {
    when (val schritt = naechsterGateSchritt(leseGateLage(context, einstieg), einstieg)) {
        GateSchritt.Akku -> {
            Logger.business(LogTags.NAVIGATION, "Gate-Kette ($einstieg) -> Battery Exemption needed")
            navigationViewModel.navigateToBatteryExemption()
        }

        GateSchritt.Unused -> {
            Logger.business(LogTags.NAVIGATION, "Gate-Kette ($einstieg) -> Unused App Restrictions needed")
            navigationViewModel.navigateToUnusedAppRestrictions()
        }

        GateSchritt.TimeOffice -> {
            Logger.business(LogTags.NAVIGATION, "Gate-Kette ($einstieg) -> TimeOffice Health Check")
            navigationViewModel.navigateToTimeOfficeHealthCheck()
        }

        is GateSchritt.Oem -> {
            Logger.business(LogTags.NAVIGATION, "Gate-Kette ($einstieg) -> OEM Warning screen for ${schritt.typ}")
            BatteryOptimizationHelper.markOemWarningScreenShown(context, schritt.typ)
            navigationViewModel.navigateToOEMWarning(schritt.typ)
        }

        GateSchritt.Fertig -> {
            Logger.business(LogTags.NAVIGATION, "Gate-Kette ($einstieg) -> Onboarding complete -> Main")
            AlarmMaintenanceService.scheduleNext(context)
            navigationViewModel.navigateToMainWithTab(MainTab.HOME)
        }

        // Liefert nur der automatische Weg (GateEinstieg.AUTO), nie ein aktiver.
        GateSchritt.Kalender, GateSchritt.Nichts -> Unit
    }
}

/**
 * Schreibt das Dismissed-Flag von [gate] und WARTET, bis es geschrieben ist. Erst danach darf die
 * Kette weiterlesen: der automatische Weg (`leseGateLage(AUTO)`) liest das Flag, und kaeme er vor
 * dem Schreiben zum Zug, schickte er den Nutzer auf dasselbe Gate zurueck.
 *
 * Ein Schreibfehler haelt die Kette NICHT an (sie schaut ohnehin nur nach vorne, siehe
 * [naechsterGateSchritt]) und reisst die App nicht mit - frueher lief der Write in einem
 * unbewachten `launch`. Folge eines Fehlers ist nur, dass das Gate beim naechsten Start wieder
 * angeboten wird: ehrlich, denn "Spaeter" wurde ja nicht gespeichert.
 */
private suspend fun schreibeUebersprungen(context: Context, gate: GateSchritt.Ueberspringbar) {
    try {
        when (gate) {
            GateSchritt.Akku -> BatteryOptimizationHelper.setBatteryPromptDismissed(context)
            GateSchritt.Unused -> UnusedAppRestrictionsHelper.setDismissed(context)
            GateSchritt.TimeOffice -> TimeOfficeHealthHelper.setPromptDismissed(context)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w(LogTags.NAVIGATION, "Dismissed-Flag fuer $gate nicht geschrieben - Gate kommt beim naechsten Start wieder", e)
    }
}

@Composable
fun MainScreen(
    authViewModel: AuthViewModel,
    calendarViewModel: CalendarViewModel,
    shiftViewModel: ShiftViewModel,
    alarmViewModel: AlarmViewModel,
    mainViewModel: MainViewModel,
    navigationViewModel: NavigationViewModel,
    hueViewModel: HueViewModel
) {
    val context = LocalContext.current
    // Scope für den (jetzt suspend) OEM-Warndialog, dessen "shown"-Flag im DataStore liegt
    val coroutineScope = rememberCoroutineScope()

    // collectAsStateWithLifecycle, nicht collectAsState: Diese vier Zustaende steuern
    // ausschliesslich Vordergrund-Verhalten (Screen-Auswahl, BackHandler, die Gate-Kette im
    // LaunchedEffect unten). Unterhalb von STARTED pausiert das Sammeln - genau richtig hier,
    // denn der Gate-Effect startet Activities (Akku-Ausnahme, Settings) bzw. navigiert, und
    // beides ist aus dem Hintergrund ohnehin nicht erlaubt. Alle vier Quellen sind StateFlows
    // (konfliert bzw. SharingStarted.Lazily), beim Zurueckkehren kommt also sofort der aktuelle
    // Wert an; es geht kein Zustand verloren.
    val authState by authViewModel.uiState.collectAsStateWithLifecycle()
    val calendarState by calendarViewModel.uiState.collectAsStateWithLifecycle()
    val mainState by mainViewModel.uiState.collectAsStateWithLifecycle()
    val navigationState by navigationViewModel.navigationState.collectAsStateWithLifecycle()

    // AUTHENTICATION & CALENDAR LOADING - Stable dependencies only
    LaunchedEffect(
        authState.isSignedIn,
        mainState.hasSelectedCalendars,
        calendarState.availableCalendars.size,
        calendarState.isLoading
    ) {
        if (!authState.isSignedIn) return@LaunchedEffect

        // DEBOUNCING: Stabilization time for simultaneous events
        delay(UIConstants.UI_STABILITY_DELAY_MS)

        Logger.d(
            LogTags.UI,
            "Processing auth-based side effects: calendars=${calendarState.availableCalendars.size}, hasSelected=${mainState.hasSelectedCalendars}, loading=${calendarState.isLoading}"
        )

        // PERFORMANCE: Prevent operations during loading
        if (calendarState.isLoading) {
            Logger.d(
                LogTags.UI,
                "Calendar operation already in progress, skipping duplicate side effect"
            )
            return@LaunchedEffect
        }

        // CALENDAR DATA: Load only if really needed
        //
        // WICHTIG: NICHT nachladen, wenn der letzte Versuch mit einem Fehler endete.
        // Dieser Effect haengt an calendarState.isLoading. Schlaegt das Laden fehl, springt
        // isLoading zurueck auf false -> Effect laeuft erneut -> Liste immer noch leer ->
        // refreshData() -> isLoading true -> ... Eine Endlosschleife, die bei jedem Durchlauf
        // erneut die Google-API anfragt. Genau das passierte bei einem 401 (totes Token):
        // Log vom 14.07. zeigt "Loading calendar data due to empty calendar list" im
        // Sekundentakt.
        //
        // Der Fehler steht bereits in der UI; der Nutzer loest den naechsten Versuch bewusst
        // aus (Pull-to-Refresh / Neuanmeldung).
        if (calendarState.availableCalendars.isEmpty() && calendarState.error == null) {
            Logger.d(LogTags.UI, "Loading calendar data due to empty calendar list")
            calendarViewModel.refreshData()
        } else if (calendarState.error != null) {
            Logger.d(
                LogTags.UI,
                "Skipping calendar reload - last attempt failed: ${calendarState.error}"
            )
        }

        // NAVIGATION: Handle after data operations complete
        if (calendarState.availableCalendars.isNotEmpty()) {
            delay(100) // Minimal delay for UI stability
            val lage = leseGateLage(
                context,
                GateEinstieg.AUTO,
                kalenderGewaehlt = mainState.hasSelectedCalendars
            )
            val schritt = naechsterGateSchritt(lage, GateEinstieg.AUTO)
            // Den OEM-Merker erst schreiben, wenn der Screen wirklich angesteuert wurde - der
            // MainContent-Waechter im ViewModel kann ablehnen, und ein Merker ohne gezeigten
            // Hinweis hiesse: er kommt nie.
            if (navigationViewModel.handleAuthenticationSuccess(schritt) && schritt is GateSchritt.Oem) {
                BatteryOptimizationHelper.markOemWarningScreenShown(context, schritt.typ)
            }
        }
    }

    // Onboarding-Abschluss an genau einer Stelle: Wartungskette anstossen, dann Home. Sowohl
    // "Verstanden" als auch der Zurueck-Weg muessen das tun - wer hier nur navigiert, laesst
    // einen Nutzer ohne 6h-Wartung zurueck und damit ohne neu angelegte Wecker.
    // scheduleNext() ist idempotent (ein Request-Code, ein PendingIntent), ein zweiter Aufruf
    // ersetzt den bestehenden Alarm also nur.
    val finishOnboarding: () -> Unit = {
        Logger.business(LogTags.NAVIGATION, "OEM Warning acknowledged -> Main")
        com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmMaintenanceService.scheduleNext(
            context
        )
        navigationViewModel.navigateToMainWithTab(MainTab.HOME)
    }

    // "Spaeter" an genau einer Stelle - BackHandler und der "Spaeter"-Knopf jedes Gates rufen
    // hierher. Jedes der drei Gates MUSS dabei sein Dismissed-Flag schreiben: sonst schickt der
    // automatische Weg den Nutzer beim naechsten Vordergrund sofort zurueck, und Zurueck saehe
    // aus, als passiere nichts.
    //
    // Danach geht die Kette SOFORT zum naechsten offenen Gate weiter (Issue #132) - frueher
    // fuehrte "Spaeter" nach Home, und das naechste Gate kam erst beim naechsten App-Start. Die
    // Reihenfolge ist tragend: erst das Flag ABGEWARTET schreiben, dann weiterlesen. Dass das
    // uebersprungene Gate nicht sofort wiederkommt, sichert zusaetzlich die Kette selbst (ein
    // SPAETER_*-Einstieg schaut nur nach vorne, siehe naechsterGateSchritt).
    //
    // Weitergemacht wird nur, solange noch genau dieses Gate angezeigt wird: ein zweiter Tipp
    // auf "Spaeter" oder Zurueck, waehrend das Flag noch geschrieben wird, startet eine zweite
    // Coroutine - die darf den Nutzer nicht aus dem schon erreichten naechsten Gate holen.
    fun ueberspringe(gate: GateSchritt.Ueberspringbar) {
        Logger.business(LogTags.NAVIGATION, "Gate $gate vom Nutzer uebersprungen (Spaeter/Zurueck)")
        coroutineScope.launch {
            schreibeUebersprungen(context, gate)
            if (gate.wirdAngezeigtIn(navigationViewModel.navigationState.value)) {
                setzeGateKetteFort(context, navigationViewModel, einstiegNachSpaeter(gate))
            }
        }
    }

    // ANDROID-ZURUECK: Die App navigiert ueber einen eigenen NavigationState, nicht ueber
    // Navigation-Compose - es gibt also keinen Backstack, der Zurueck von allein eine Ebene
    // hoch fuehren wuerde. Ohne BackHandler landet jeder Druck beim Default der Activity und
    // beendet die App: aus "Kalender-Events" sprang der Nutzer direkt auf den Android-Home-
    // screen. Wer einen neuen NavigationState ergaenzt, muss ihn hier mitbedenken; der
    // else-Zweig faengt jeden Unterscreen ab, die Sonderfaelle stehen davor.
    //
    // Auf dem Home-Tab bleibt der Handler bewusst AUS: dort ist Zurueck tatsaechlich "App
    // verlassen", und das erledigt der Systemdefault inkl. Predictive-Back-Animation besser
    // als jeder Nachbau.
    val onHomeTab = (navigationState as? NavigationState.MainContent)?.selectedTab == MainTab.HOME
    BackHandler(enabled = !onHomeTab) {
        when (val state = navigationState) {
            // Ein Nicht-Home-Tab ist eine Ebene tiefer: Zurueck fuehrt auf Home, nicht aus der
            // App (Android-Konvention fuer Bottom-Navigation).
            is NavigationState.MainContent -> navigationViewModel.changeTab(MainTab.HOME)

            // Zurueck heisst bei allen drei Gates dasselbe wie "Spaeter": ein blosses
            // navigateBackToMain() wuerde handleAuthenticationSuccess() sofort wieder hierher
            // schicken. Bei Unused-App und TimeOffice gilt das auch, obwohl der Nutzer dort ggf.
            // nichts geaendert hat (reiner Settings-Screen, kein Bestaetigungs-Dialog) - Zurueck
            // wirkt wie "Spaeter", NICHT wie "Verstanden".
            is NavigationState.BatteryExemption -> ueberspringe(GateSchritt.Akku)
            is NavigationState.UnusedAppRestrictions -> ueberspringe(GateSchritt.Unused)
            is NavigationState.TimeOfficeHealthCheck -> ueberspringe(GateSchritt.TimeOffice)

            // Wie "Verstanden" - siehe finishOnboarding.
            is NavigationState.OEMWarning -> finishOnboarding()

            // Alle uebrigen Unterscreens, auch HueRuleConfig/DimmerRuleConfig mit ihren zwei
            // Einstiegspfaden (cameFromSettingsList): der Rueckweg ist in navigateBackFrom()
            // EINMAL aufgeloest - dieselbe Aufloesung, die auch Zurueck-Pfeil und Speichern der
            // Regel-Editoren unten nehmen.
            else -> navigationViewModel.navigateBackFrom(state)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        when (val state = navigationState) {
            is NavigationState.ShiftConfig -> {
                ShiftConfigScreen(
                    shiftViewModel = shiftViewModel,
                    onNavigateBack = { navigationViewModel.navigateBackToMain() }
                )
            }

            is NavigationState.CalendarSelection -> {
                CalendarSelectionScreen(
                    calendarViewModel = calendarViewModel,
                    onRequestAuthorization = {
                        // Der einzige Weg aus "Token verworfen": Zustimmungsdialog anstossen.
                        // Braucht die Activity, sonst kann Google keinen Dialog zeigen.
                        authViewModel.requestCalendarAuthorization(context as? android.app.Activity)
                    },
                    onDone = {
                        // UNDISPATCHED: die Akku-Ausnahme wird synchron gelesen, die Kette
                        // startet also noch im selben Tipp. Seit Issue #132 zaehlt hier auch
                        // "Spaeter" beim Akku-Gate als ERLEDIGT - dafuer wird das Flag aus dem
                        // DataStore gelesen (kurz suspendierend), bevor das Akku-Gate kommt.
                        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                            setzeGateKetteFort(context, navigationViewModel, GateEinstieg.NACH_KALENDER)
                        }
                    },
                    onCancel = {
                        navigationViewModel.navigateBackToMain()
                    }
                )
            }

            is NavigationState.BatteryExemption -> {
                var showEducationalDialog by remember { mutableStateOf(false) }

                val batteryExemptionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { _ ->
                    val isExempted = BatteryOptimizationHelper.isExempted(context)
                    Logger.d(LogTags.BATTERY, "Battery exemption result: $isExempted")

                    if (isExempted) {
                        coroutineScope.launch {
                            setzeGateKetteFort(context, navigationViewModel, GateEinstieg.NACH_AKKU)
                        }
                    } else {
                        showEducationalDialog = true
                    }
                }

                if (showEducationalDialog) {
                    BatteryEducationalDialog(
                        onDismiss = { showEducationalDialog = false }
                    )
                }

                BatteryOnboardingScreen(
                    // Hiess frueher onComplete - der Name log: der Knopf "Warum ist das noetig?"
                    // schliesst nichts ab, er erklaert.
                    onExplain = {
                        showEducationalDialog = true
                    },
                    onSkip = { ueberspringe(GateSchritt.Akku) },
                    onRequestExemption = {
                        try {
                            // BatteryLife unterdrueckt: Lint haelt jedes
                            // ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS fuer einen Verstoss
                            // gegen die Play-Store-Richtlinie. Hier feuert der Intent
                            // ausschliesslich auf den Knopf "Akku-Freigabe erteilen" im
                            // Akku-Gate; entschieden wird im Systemdialog, den Android zeigt
                            // (siehe KDoc von BatteryOnboardingScreen). Nichts davon laeuft
                            // automatisch oder im Hintergrund. Fuer eine Wecker-App ist das der
                            // Unterschied zwischen klingeln und still bleiben: eingefroren holt
                            // die App keine neuen Schichten mehr.
                            // Unterdrueckung bewusst hier, nicht in app/lint.xml (dort wirkungslos).
                            @Suppress("BatteryLife")
                            val intent =
                                android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                    .apply {
                                        data = "package:${context.packageName}".toUri()
                                    }
                            batteryExemptionLauncher.launch(intent)
                        } catch (e: Exception) {
                            Logger.e(
                                LogTags.BATTERY,
                                "Failed to request battery exemption, opening settings",
                                e
                            )
                            try {
                                val intent =
                                    android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                batteryExemptionLauncher.launch(intent)
                            } catch (e2: Exception) {
                                Logger.e(LogTags.BATTERY, "Failed to open battery settings", e2)
                            }
                        }
                    }
                )
            }

            is NavigationState.UnusedAppRestrictions -> {
                val unusedAppRestrictionsLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { _ ->
                    // Kein strukturiertes Ergebnis (reiner Settings-Screen) - immer neu pruefen,
                    // was als naechstes kommt (TimeOffice, OEM-Screen oder fertig).
                    coroutineScope.launch {
                        setzeGateKetteFort(context, navigationViewModel, GateEinstieg.NACH_EINSTELLUNGEN)
                    }
                }

                UnusedAppRestrictionsOnboardingScreen(
                    onOpenSettings = {
                        try {
                            unusedAppRestrictionsLauncher.launch(
                                UnusedAppRestrictionsHelper.createSettingsIntent(context)
                            )
                        } catch (e: Exception) {
                            Logger.e(
                                LogTags.UNUSED_APP_RESTRICTIONS,
                                "Failed to open unused-app-restrictions settings",
                                e
                            )
                        }
                    },
                    onSkip = { ueberspringe(GateSchritt.Unused) }
                )
            }

            is NavigationState.TimeOfficeHealthCheck -> {
                val timeOfficeSettingsLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { _ ->
                    // Kein strukturiertes Ergebnis (reiner Settings-Screen einer fremden App) -
                    // immer neu pruefen, was als naechstes kommt (TimeOffice, OEM-Screen oder
                    // fertig).
                    coroutineScope.launch {
                        setzeGateKetteFort(context, navigationViewModel, GateEinstieg.NACH_EINSTELLUNGEN)
                    }
                }

                TimeOfficeHealthOnboardingScreen(
                    onOpenSettings = {
                        try {
                            timeOfficeSettingsLauncher.launch(
                                TimeOfficeHealthHelper.createAppInfoIntent()
                            )
                        } catch (e: Exception) {
                            Logger.e(
                                LogTags.TIMEOFFICE_HEALTH,
                                "Failed to open TimeOffice app-info settings",
                                e
                            )
                        }
                    },
                    onSkip = { ueberspringe(GateSchritt.TimeOffice) }
                )
            }

            is NavigationState.OEMWarning -> {
                OEMWarningScreen(
                    oemType = state.oemType,
                    onComplete = finishOnboarding
                )
            }

            is NavigationState.EventList -> {
                EventListScreen(
                    calendarViewModel = calendarViewModel,
                    onBack = { navigationViewModel.navigateBackToMain() }
                )
            }

            is NavigationState.HueRuleConfig -> {
                // Zurueck UND Speichern fuehren zum tatsaechlichen Einstiegspunkt (Hue-Tab oder
                // HueSettings, siehe cameFromSettingsList) - aufgeloest in navigateBackFrom(),
                // derselben Stelle wie der BackHandler oben. Der gerenderte Zustand wird
                // uebergeben, damit ein zweiter Tipp auf Speichern zum selben Ziel fuehrt.
                val zurueckZumEinstieg: () -> Unit = { navigationViewModel.navigateBackFrom(state) }
                com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.HueRuleConfigScreen(
                    ruleId = state.ruleId,
                    hueViewModel = hueViewModel,
                    shiftViewModel = shiftViewModel,
                    onNavigateBack = zurueckZumEinstieg,
                    onSaveComplete = zurueckZumEinstieg
                )
            }

            is NavigationState.HueSettings -> {
                com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.hue.HueSettingsScreen(
                    hueViewModel = hueViewModel,
                    onNavigateBack = { navigationViewModel.navigateBackToMain() },
                    onEditRule = { ruleId ->
                        navigationViewModel.navigateToHueRuleConfig(
                            ruleId = ruleId,
                            fromTab = state.returnToTab,
                            cameFromSettingsList = true
                        )
                    },
                    onCreateNewRule = {
                        navigationViewModel.navigateToHueRuleConfig(
                            fromTab = state.returnToTab,
                            cameFromSettingsList = true
                        )
                    }
                )
            }

            is NavigationState.DimmerSettings -> {
                com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.dimmer.DimmerSettingsScreen(
                    onNavigateBack = { navigationViewModel.navigateBackToMain() },
                    onEditRule = { ruleId ->
                        navigationViewModel.navigateToDimmerRuleConfig(
                            ruleId = ruleId,
                            fromTab = state.returnToTab,
                            cameFromSettingsList = true
                        )
                    },
                    onCreateRule = {
                        navigationViewModel.navigateToDimmerRuleConfig(
                            fromTab = state.returnToTab,
                            cameFromSettingsList = true
                        )
                    }
                )
            }

            is NavigationState.DimmerRuleConfig -> {
                // Gleiche Semantik wie HueRuleConfig oben - aktuell fuehrt nur der Pfad ueber
                // DimmerSettings hierher (cameFromSettingsList defaultet auf true), aber
                // navigateBackFrom() deckt auch einen kuenftigen Direktpfad korrekt ab.
                val zurueckZumEinstieg: () -> Unit = { navigationViewModel.navigateBackFrom(state) }
                com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.dimmer.DimmerRuleConfigScreen(
                    ruleId = state.ruleId,
                    onNavigateBack = zurueckZumEinstieg,
                    onSaveComplete = zurueckZumEinstieg
                )
            }

            is NavigationState.MainContent -> {
                // DIE 6h-WARTUNGSKETTE WIRD HIER GESTELLT, NICHT AN DEN GATE-AUSGAENGEN.
                //
                // Bis v1.26.2 stand scheduleNext() nur in zwei der Ausgaenge (proceedPastGates(),
                // heute setzeGateKetteFort(), und finishOnboarding()). Wer ein Gate mit "Spaeter" oder Zurueck verliess - ein
                // ausdruecklich vorgesehener, persistierter Weg - bekam die Kette NIE gestellt.
                // Danach entstanden Alarme nur noch, solange der Nutzer die App selbst oeffnete;
                // neue Schichten aus dem Dienstplan wurden nicht verweckert, und erst ein Reboot
                // reparierte den Zustand. Genau der Betriebsfall, den diese App nicht verlangt.
                //
                // An den Ausgaengen einzeln nachzuziehen waere die fragile Loesung gewesen: der
                // naechste neue Ausgang vergisst es wieder. Hier kommt JEDER Weg vorbei, und
                // scheduleNext() ist idempotent (ein Request-Code, ein PendingIntent) - ein
                // zweiter Aufruf ersetzt den bestehenden Alarm nur. Der Schluessel `Unit` laesst
                // es genau einmal je Eintritt in den Hauptbereich laufen, nicht bei jeder
                // Rekomposition.
                LaunchedEffect(Unit) {
                    AlarmMaintenanceService.scheduleNext(context)
                    Logger.d(LogTags.NAVIGATION, "6h-Wartungskette beim Eintritt in den Hauptbereich gestellt")
                }

                MainContentScreen(
                    authViewModel = authViewModel,
                    calendarViewModel = calendarViewModel,
                    shiftViewModel = shiftViewModel,
                    alarmViewModel = alarmViewModel,
                    hueViewModel = hueViewModel,
                    selectedTab = state.selectedTab,
                    onSelectedTabChange = { tab -> navigationViewModel.changeTab(tab) },
                    onShowShiftConfig = { navigationViewModel.navigateToShiftConfig(state.selectedTab) },
                    onShowCalendarSelection = {
                        navigationViewModel.navigateToCalendarSelection(
                            state.selectedTab
                        )
                    },
                    onShowEventList = { navigationViewModel.navigateToEventList(state.selectedTab) },
                    // Direkter Einstieg vom Hue-Tab (kein HueSettings dazwischen) -
                    // cameFromSettingsList = false, damit Zurueck/Speichern spaeter wieder
                    // hierher fuehrt statt auf die nie geoeffnete Regel-Liste.
                    onShowHueRuleConfig = {
                        navigationViewModel.navigateToHueRuleConfig(
                            fromTab = state.selectedTab,
                            cameFromSettingsList = false
                        )
                    },
                    onShowHueSettings = { navigationViewModel.navigateToHueSettings() },
                    onShowDimmerSettings = { navigationViewModel.navigateToDimmerSettings() },
                    onShowDimmerPreview = { navigationViewModel.navigateToDimmerPreview() },
                    onShowDndSettings = { navigationViewModel.navigateToDndSettings() }
                )
            }

            is NavigationState.DimmerPreview -> {
                com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.dimmer.DimmerPreviewScreen(
                    onNavigateBack = { navigationViewModel.navigateBackToMain() }
                )
            }

            is NavigationState.DndSettings -> {
                com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.dnd.DndSettingsScreen(
                    onNavigateBack = { navigationViewModel.navigateBackToMain() }
                )
            }
        }
    }
}

