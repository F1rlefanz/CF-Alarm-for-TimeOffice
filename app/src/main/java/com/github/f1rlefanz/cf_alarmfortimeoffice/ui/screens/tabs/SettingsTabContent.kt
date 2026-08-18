package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs

// PHASE 2 CLEANUP: ShiftViewModel import removed (unused parameter)
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.CompactButton
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.CompactOutlinedButton
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.ErrorMessage
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.warning
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.BatteryOptimizationHelper
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.AuthViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.ConfigBackupViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.MasterPauseViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.NotificationSettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTabContent(
    authViewModel: AuthViewModel,
    onShowCalendarSelection: () -> Unit,
    onShowDndSettings: () -> Unit
) {
    val context = LocalContext.current
    // collectAsStateWithLifecycle, nicht collectAsState: Es sind reine Anzeige-Zustaende, an deren
    // Sammeln kein Seiteneffekt haengt. Zwei der Quellen sind ausserdem
    // `stateIn(SharingStarted.WhileSubscribed(5_000))` ueber DataStore-Fluesse
    // (NotificationSettingsViewModel, MasterPauseViewModel) - mit collectAsState laeuft das Abo
    // weiter, solange die Composition lebt, also auch im Hintergrund, und der 5s-Timeout wird zur
    // Attrappe. Gleiche Begruendung wie im DimmerTabContent.
    val authState by authViewModel.uiState.collectAsStateWithLifecycle()
    val notificationSettingsViewModel: NotificationSettingsViewModel = hiltViewModel()
    val notificationState by notificationSettingsViewModel.uiState.collectAsStateWithLifecycle()
    val masterPauseViewModel: MasterPauseViewModel = hiltViewModel()
    val masterPausePaused by masterPauseViewModel.paused.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(SpacingConstants.PADDING_SCREEN_HORIZONTAL),
        verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE)
    ) {
        // Fehleranzeige am Anfang des Contents
        authState.error?.let { errorMessage ->
            ErrorMessage(
                message = errorMessage,
                onDismiss = { authViewModel.clearError() }
            )
        }

        Text(
            "Einstellungen",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        // Kalender-Einstellungen
        Card(
            modifier = Modifier.fillMaxWidth(),
            onClick = onShowCalendarSelection
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(SpacingConstants.PADDING_CARD),
                horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.CalendarMonth,
                    // dekorativ: Text daneben sagt es bereits ("Kalender auswählen")
                    contentDescription = null,
                    modifier = Modifier.size(SpacingConstants.ICON_SIZE_STANDARD),
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Kalender auswählen",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "Wähle die Kalender für Schichterkennung",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    // dekorativ: reines Weiter-Zeichen, die ganze Karte ist das bedienbare
                    // Element und traegt ihre Beschriftung selbst
                    Icons.AutoMirrored.Default.KeyboardArrowRight,
                    contentDescription = null
                )
            }
        }

        // 🔧 STUFE 2 FIX: Calendar Authorization/Re-Authorization Card
        // Shows when user needs to authorize Calendar OR re-authorize due to invalid token
        if (authState.userAuth.isSignedIn &&
            (!authState.calendarOps.hasSelectedCalendars || authState.calendarOps.needsTokenReauthorization)
        ) {

            // Dynamically adapt card appearance based on state
            val needsReauth = authState.calendarOps.needsTokenReauthorization
            // CTA-Karte: weiße Fläche mit rotem Akzent (statt getöntem Container).
            val cardColor = MaterialTheme.colorScheme.surface
            val iconColor = MaterialTheme.colorScheme.primary
            val titleText = if (needsReauth) {
                "Kalender-Zugriff erneuern"
            } else {
                "Calendar-Berechtigung"
            }
            val descriptionText = if (needsReauth) {
                "Token abgelaufen - Kalender-Zugriff erneut autorisieren"
            } else {
                "Kalender-Zugriff autorisieren für Schichterkennung"
            }
            val icon = if (needsReauth) {
                Icons.Default.Refresh
            } else {
                Icons.Default.Security
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    // CRITICAL FIX: Pass Activity context for permission dialog
                    val activity = context as? android.app.Activity
                    authViewModel.requestCalendarAuthorization(activity)
                },
                colors = CardDefaults.cardColors(
                    containerColor = cardColor
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(SpacingConstants.PADDING_CARD),
                    horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        icon,
                        // dekorativ: titleText/descriptionText daneben nennen denselben Zustand
                        // ("Kalender-Zugriff erneuern" bzw. "Calendar-Berechtigung")
                        contentDescription = null,
                        modifier = Modifier.size(SpacingConstants.ICON_SIZE_STANDARD),
                        tint = iconColor
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            titleText,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            descriptionText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (authState.calendarOps.calendarsLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = iconColor
                        )
                    } else {
                        Icon(
                            Icons.Default.Lock,
                            // dekorativ: descriptionText daneben sagt bereits, dass der Zugriff
                            // autorisiert bzw. erneuert werden muss
                            contentDescription = null,
                            tint = iconColor
                        )
                    }
                }
            }
        }

        // Nicht stören (DND) - eigener, getrennter Bereich (nicht im Dimmer-Tab)
        Card(
            modifier = Modifier.fillMaxWidth(),
            onClick = onShowDndSettings
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(SpacingConstants.PADDING_CARD),
                horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.DoNotDisturbOn,
                    // dekorativ: Text daneben sagt es bereits ("Nicht stören")
                    contentDescription = null,
                    modifier = Modifier.size(SpacingConstants.ICON_SIZE_STANDARD),
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Nicht stören",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "Schaltet Nicht-stören automatisch nach Schicht",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    // dekorativ: reines Weiter-Zeichen, die ganze Karte ist das bedienbare
                    // Element und traegt ihre Beschriftung selbst
                    Icons.AutoMirrored.Default.KeyboardArrowRight,
                    contentDescription = null
                )
            }
        }

        // Benachrichtigungen (Schicht-Aenderung + Dimmer-Korrektur)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(SpacingConstants.PADDING_CARD),
                verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.NotificationsActive,
                        // dekorativ: Ueberschrift daneben sagt es bereits ("Benachrichtigungen")
                        contentDescription = null,
                        modifier = Modifier.size(SpacingConstants.ICON_SIZE_STANDARD),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Benachrichtigungen",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Schicht-Änderung",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "Benachrichtigt, wenn TimeOffice eine Schicht ändert/hinzufügt/entfernt",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = notificationState.shiftChangeNotificationEnabled,
                        onCheckedChange = { notificationSettingsViewModel.setShiftChangeNotificationEnabled(it) }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Kalender nicht abrufbar",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "Warnt, wenn ein gewählter Kalender dauerhaft nicht mehr antwortet — " +
                                "dann entstehen keine neuen Wecker mehr",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = notificationState.calendarUnavailableNotificationEnabled,
                        onCheckedChange = { notificationSettingsViewModel.setCalendarUnavailableNotificationEnabled(it) }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Dimmer-Korrektur",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "Zeigt Heller/Dunkler/Pause, solange der Schicht-Dimmer aktiv ist",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = notificationState.dimCorrectionNotificationEnabled,
                        onCheckedChange = { notificationSettingsViewModel.setDimCorrectionNotificationEnabled(it) }
                    )
                }
            }
        }

        // 🔄 Phase 1: Sync-Intervall and Offline-Puffer cards removed
        // Fixed 6h maintenance interval via AlarmMaintenanceService
        // Fixed 14-day lookahead with 7-day buffer check

        // Akku-Status & OEM-Warnungen (die "Letzter Sync"-Anzeige liegt jetzt im Status-Tab)

        // 1. Battery Exemption Warning
        if (!BatteryOptimizationHelper.isExempted(context)) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                onClick = {
                    val activity = context as? android.app.Activity
                    if (activity != null) {
                        BatteryOptimizationHelper.requestExemption(activity)
                    }
                }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(SpacingConstants.PADDING_CARD),
                    horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Warning,
                        // dekorativ: der Titel daneben beginnt selbst mit "⚠️" - eine zusaetzliche
                        // Beschreibung liesse den Screenreader die Warnung doppelt vorlesen
                        contentDescription = null,
                        modifier = Modifier.size(SpacingConstants.ICON_SIZE_STANDARD),
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "⚠️ Akku-Optimierung aktiv",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            "App nicht von Akku-Optimierung ausgenommen. Wecker könnten ausfallen.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    Icon(
                        Icons.Default.Settings,
                        // dekorativ: der Kartentext traegt die Aussage. BEWUSST NICHT "Einstellungen
                        // oeffnen" - die Karte loest Androids Systemdialog aus, keine
                        // Einstellungsliste (siehe CLAUDE.md, "Der Akku-Onboarding-Screen darf keine
                        // Einstellungen versprechen").
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        // 3. OEM-specific warning card (persistent)
        val oemType = BatteryOptimizationHelper.getOEMType()
        if (BatteryOptimizationHelper.shouldShowOEMWarning(oemType)) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                onClick = { BatteryOptimizationHelper.openOEMHelpUrl(context, oemType) }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(SpacingConstants.PADDING_CARD),
                    horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Warning,
                        // Zustand, der sonst nur in der Farbe steckt: der Kartentext daneben nennt
                        // keinen Warnhinweis, die Warnfarbe allein erreicht Screenreader nicht.
                        contentDescription = "Warnung",
                        modifier = Modifier.size(SpacingConstants.ICON_SIZE_STANDARD),
                        tint = MaterialTheme.colorScheme.warning
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "${BatteryOptimizationHelper.getOEMDisplayName(oemType)}-Geräte erfordern Extra-Schritte",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "Für maximale Zuverlässigkeit weitere Einstellungen prüfen",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        // Kein reines Weiter-Zeichen: die Karte verlaesst die App und oeffnet die
                        // Hilfeseite des Herstellers im Browser - das steht sonst nirgends im Text.
                        contentDescription = "Öffnet die Hilfeseite im Browser",
                        tint = MaterialTheme.colorScheme.warning
                    )
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = SpacingConstants.SPACING_SMALL))

        // KONFIGURATION SICHERN. Loest, was in der Testphase wiederholt Handarbeit war: nach jeder
        // Neuinstallation alles neu eintragen. Bewusst unabhaengig von Googles Auto-Backup - diese
        // Datei funktioniert immer, auch bei Neuinstallation auf demselben Geraet, und laesst sich
        // an eine Kollegin weitergeben.
        Text(
            "Konfiguration",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        ConfigBackupCard()

        HorizontalDivider(modifier = Modifier.padding(vertical = SpacingConstants.SPACING_SMALL))

        // Account-Bereich
        Text(
            "Account",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        // Abmelden
        Card(
            modifier = Modifier.fillMaxWidth(),
            onClick = { authViewModel.signOut() }, // MEMORY LEAK FIX: No context parameter needed
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(SpacingConstants.PADDING_CARD),
                horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Logout,
                    // dekorativ: Text daneben sagt es bereits ("Abmelden")
                    contentDescription = null,
                    modifier = Modifier.size(SpacingConstants.ICON_SIZE_STANDARD),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    "Abmelden",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        // Alles pausieren (Master-Pause fuer laengere Abwesenheit)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(SpacingConstants.PADDING_CARD),
                horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Hintergrunddienste pausieren",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        "Pausiert ALLES: Wecker, Dimmer, Nicht-stören und Hue-Automatik, inklusive der 6h-Wartung selbst. Für längere Abwesenheit. Kein Wecker klingelt, bis du hier wieder aktivierst.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                Switch(
                    checked = masterPausePaused,
                    onCheckedChange = { masterPauseViewModel.setPaused(it) }
                )
            }
        }
    }
}

/**
 * Export und Import der Konfiguration ueber den System-Dateidialog.
 *
 * WARUM ES DAS GIBT: In der Testphase kostete jede Neuinstallation Handarbeit - Schichtdefinitionen,
 * Hue-Regeln, Dimmer-Regeln und DND-Einstellungen von Hand neu eintragen. Googles Auto-Backup ist
 * eine andere, zweite Sache: es greift nur bei einem Geraetewechsel, nur wenn der Nutzer es hat und
 * nur wenn Google es ausfuehrt. Diese Datei funktioniert immer - auch auf demselben Geraet - und
 * laesst sich weitergeben.
 *
 * DER IMPORT FRAGT VORHER. Er ueberschreibt die aktuelle Konfiguration und setzt anschliessend die
 * Wecker neu; es gibt kein Undo. Deshalb erst die Rueckfrage, dann der Dateidialog.
 *
 * ABSICHTLICH NICHT IN DER DATEI: Anmeldung, Tokens, Tink-Keyset, Kalenderauswahl und die
 * Hue-Bridge-Zugangsdaten. Der Nutzer erfaehrt das im Kartentext UND nach dem Import - sonst wartet
 * er auf Wecker, deren Voraussetzungen er fuer mitgebracht haelt.
 */
@Composable
private fun ConfigBackupCard() {
    val viewModel: ConfigBackupViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showImportConfirmation by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { viewModel.exportTo(it) } }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importFrom(it) } }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SpacingConstants.PADDING_CARD),
            verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
        ) {
            Text(
                "Exportieren / Importieren",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                "Sichert Schichttypen, Hue-Regeln, Dimmer-Regeln und \"Nicht stören\" als Datei. " +
                    "Anmeldung, Kalenderauswahl und die Hue-Bridge-Verbindung sind absichtlich " +
                    "nicht enthalten — die richtest du auf jedem Gerät selbst ein.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)) {
                // CompactActionButton: zwei geteilte Buttons in einer Kartenzeile - mit
                // ButtonDefaults.ContentPadding (24dp je Seite) bricht Compose hier mitten im Wort.
                CompactButton(
                    onClick = { exportLauncher.launch(viewModel.suggestedFileName()) },
                    text = "Exportieren",
                    modifier = Modifier.weight(1f),
                    enabled = !state.busy
                )
                CompactOutlinedButton(
                    onClick = { showImportConfirmation = true },
                    text = "Importieren",
                    modifier = Modifier.weight(1f),
                    enabled = !state.busy
                )
            }
        }
    }

    if (showImportConfirmation) {
        AlertDialog(
            onDismissRequest = { showImportConfirmation = false },
            title = { Text("Konfiguration importieren?") },
            text = {
                Text(
                    "Der Import überschreibt deine aktuellen Schichttypen, Hue-Regeln, " +
                        "Dimmer-Regeln und \"Nicht stören\"-Einstellungen und setzt danach alle " +
                        "Wecker neu. Das lässt sich nicht rückgängig machen — exportiere vorher, " +
                        "wenn du den aktuellen Stand behalten willst."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showImportConfirmation = false
                        // Bewusst breit: manche Dateimanager liefern JSON als application/octet-stream
                        // oder text/plain aus, eine reine application/json-Filterung liesse die
                        // eigene Exportdatei dann ausgegraut erscheinen.
                        importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                    }
                ) { Text("Datei wählen") }
            },
            dismissButton = {
                TextButton(onClick = { showImportConfirmation = false }) { Text("Abbrechen") }
            }
        )
    }

    state.result?.let { message ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissResult() },
            title = { Text(if (state.isError) "Fehlgeschlagen" else "Fertig") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissResult() }) { Text("OK") }
            }
        )
    }
}
