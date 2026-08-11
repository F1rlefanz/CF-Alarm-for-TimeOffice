package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.github.f1rlefanz.cf_alarmfortimeoffice.R
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimAccessibilityService
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AuthState
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmMaintenanceService
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.CompactButton
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.CompactOutlinedButton
import androidx.core.app.NotificationManagerCompat
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.SettingsLinkButton
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.success
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.BatteryOptimizationHelper
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.DndPermissionHelper
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.TimeOfficeHealthHelper
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.UnusedAppRestrictionsHelper
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.AuthViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.CalendarUiState
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.CalendarViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.ShiftUiState
import java.util.concurrent.TimeUnit

@Composable
fun StatusTabContent(
    authState: AuthState,
    calendarState: CalendarUiState,
    shiftState: ShiftUiState,
    calendarViewModel: CalendarViewModel?,
    authViewModel: AuthViewModel,
    onShowCalendarSelection: () -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(SpacingConstants.PADDING_SCREEN_HORIZONTAL),
        verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE)
    ) {
        Text(
            "System-Status",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        // Auth Status. Bewusst OHNE Aktions-Button: "Nicht angemeldet" ist hier ein
        // architektonisch unerreichbarer Zustand - MainActivity zeigt bei !isSignedIn bereits
        // root-seitig den Login-Screen statt MainScreen/MainContentScreen (Teil davon ist dieser
        // Status-Tab). Ein Button fuer einen Zustand, der nie sichtbar wird, waere irrefuehrender
        // toter Code.
        StatusCard(
            title = "Authentifizierung",
            isOk = authState.isSignedIn,
            details = if (authState.isSignedIn) {
                "Angemeldet als ${authState.userEmail ?: "Unbekannt"}"
            } else {
                "Nicht angemeldet"
            }
        )

        // Kalender Status: zwei echte, unterscheidbare Fehlerzustaende - je einer bekommt seine
        // eigene Aktion (nicht gleichzeitig moeglich, siehe Bedingungen unten). "Keine Kalender
        // verfuegbar" (leeres Google-Konto) bleibt ohne Button - nichts, wohin man von hier aus
        // springen koennte.
        val calendarActionLabel: String?
        val onCalendarAction: (() -> Unit)?
        when {
            !calendarState.calendarAuthorizationValid && calendarState.selectedCalendarIds.isNotEmpty() -> {
                calendarActionLabel = "Neu anmelden"
                onCalendarAction = {
                    authViewModel.requestCalendarAuthorization(context as? android.app.Activity)
                }
            }
            calendarState.selectedCalendarIds.isEmpty() -> {
                calendarActionLabel = "Kalender wählen"
                onCalendarAction = onShowCalendarSelection
            }
            else -> {
                calendarActionLabel = null
                onCalendarAction = null
            }
        }
        StatusCard(
            title = "Kalender",
            isOk = calendarState.selectedCalendarIds.isNotEmpty() && calendarState.calendarAuthorizationValid,
            details = when {
                !calendarState.calendarAuthorizationValid && calendarState.selectedCalendarIds.isNotEmpty() ->
                    "⚠️ Kalender-Autorisierung verloren - Bitte neu anmelden"
                calendarState.selectedCalendarIds.isEmpty() -> "Kein Kalender ausgewählt"
                calendarState.availableCalendars.isEmpty() -> "Keine Kalender verfügbar"
                else -> "${calendarState.selectedCalendarIds.size} Kalender ausgewählt, API-Zugriff OK"
            },
            actionLabel = calendarActionLabel,
            onAction = onCalendarAction,
            actionEnabled = !authState.calendarOps.calendarsLoading
        )

        // Schicht-Erkennung Status
        StatusCard(
            title = "Schicht-Erkennung",
            isOk = shiftState.recognizedShifts.isNotEmpty(),
            details = when {
                shiftState.recognizedShifts.isEmpty() -> "Keine Schichten erkannt"
                else -> "${shiftState.recognizedShifts.size} Schichten erkannt"
            }
        )

        // Vollbild-Berechtigung: ohne sie kommt der Weck-Screen nie hoch
        NotificationsEnabledCard()

        Spacer(modifier = Modifier.height(SpacingConstants.SPACING_MEDIUM))

        FullScreenIntentCard()

        // Akku-Ausnahme: ohne sie darf Android die 6h-Wartung und die exakten Wecker-Alarme
        // im Doze/Standby einfrieren — die zweite OS-Berechtigung, an der die Hintergrund-
        // Zuverlaessigkeit haengt, direkt neben dem Vollbild-Wecker.
        BatteryOptimizationCard()

        // "App bei Nichtnutzung pausieren": am 20.07.2026 live nachgewiesen, dass dieser
        // Schalter die App per Force-Stop killt und dabei alle gesetzten Wecker-Alarme
        // loescht — unabhaengig von der Akku-Ausnahme oben (separater Mechanismus).
        UnusedAppRestrictionsCard()

        // TimeOffice-Zuverlaessigkeit: CFAlarms Alarme haengen an einem Kalender, den TimeOffice
        // (de.pradtke.timeoffice) lokal befuellt. Live am 30.07.2026 nachgewiesen: dieselben zwei
        // OS-Einschraenkungen wie oben, aber auf TimeOffice selbst, legten den Dienstplan-Sync
        // tagelang lahm. Rendert nichts, wenn TimeOffice nicht installiert ist.
        TimeOfficeHealthCard()

        // Schicht-Dimmer: laeuft der Bedienungshilfen-Dienst? Nur dann kann das Dimm-Overlay
        // ueberhaupt erscheinen. Der Status stand frueher im Dimmer-Tab; hier neben den anderen
        // OS-Berechtigungen ist er dauerhaft ablesbar und der Dienst von einer Stelle aus aktivierbar.
        DimmerAccessibilityCard()

        // DND-Steuerung: Freigabe-Status fuer ACCESS_NOTIFICATION_POLICY, analog zur
        // Bedienungshilfen-Karte des Dimmers.
        DndPermissionCard()

        // Letzter Hintergrund-Sync (6h-Wartung: Token -> Kalender -> Wecker)
        LastSyncCard(calendarViewModel = calendarViewModel)

        // Debug-Informationen
        DebugInfoCard()
        
        // Cache-Statistiken und Offline-Status
        CacheStatusCard(calendarViewModel = calendarViewModel)
    }
}

/**
 * Meldet, ob die App ueberhaupt Benachrichtigungen zeigen darf - die Voraussetzung fuer ALLES
 * daran, inklusive der Vollbild-Karte darunter.
 *
 * WARUM DIESE KARTE EXISTIERT (am Emulator am 11.08.2026 im echten Zustand gesehen): Sind
 * Benachrichtigungen blockiert, klingelt der Wecker trotzdem - der Vordergrunddienst laeuft, Ton
 * und Vibration laufen -, aber seine Benachrichtigung wird unterdrueckt UND der Full-Screen-Intent
 * abgelehnt. Der Nutzer hat damit KEINE Oberflaeche, um den Wecker zu stoppen oder zu schlummern:
 * kein Weck-Bildschirm, keine Knoepfe, nichts. Der einzige Ausweg ist "App beenden" in den
 * Systemeinstellungen. Genau dieser Zustand entsteht ohne Zutun, wenn der Nutzer die einmalige
 * Abfrage (MainActivity, beim ersten Erreichen des Hauptbereichs) ablehnt oder die Berechtigung
 * spaeter entzieht - danach fragt die App nie wieder.
 *
 * Deshalb die Karte, und deshalb steht sie VOR der Vollbild-Karte: ohne Benachrichtigungen ist
 * deren Aussage bedeutungslos.
 *
 * Wie die Karten daneben wird der Zustand bei jedem ON_RESUME frisch gelesen - der Nutzer kann ihn
 * ausserhalb der App aendern, und danach muss die Karte stimmen.
 */
@Composable
private fun NotificationsEnabledCard() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var enabled by remember {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SpacingConstants.PADDING_CARD),
            horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (enabled) Icons.Default.CheckCircle else Icons.Default.Error,
                contentDescription = null,
                modifier = Modifier.size(SpacingConstants.ICON_SIZE_LARGE),
                tint = if (enabled)
                    MaterialTheme.colorScheme.success
                else
                    MaterialTheme.colorScheme.error
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Benachrichtigungen",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (enabled) {
                        "Erlaubt — Weck-Bildschirm und Wecker-Knöpfe können erscheinen"
                    } else {
                        "⚠️ Blockiert — der Wecker klingelt dann zwar, aber ohne Weck-Bildschirm " +
                            "und ohne Knöpfe zum Stoppen oder Schlummern"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )

                if (!enabled) {
                    Spacer(Modifier.height(SpacingConstants.SPACING_SMALL))
                    SettingsLinkButton(
                        onClick = { openAppNotificationSettings(context) },
                        text = "Einstellung öffnen"
                    )
                }
            }
        }
    }
}

/**
 * Meldet, ob die App eine Vollbild-Benachrichtigung zeigen darf — und fuehrt bei Bedarf direkt
 * in die zustaendige Systemeinstellung.
 *
 * Warum eine eigene Karte statt einer Zeile in den Empfehlungen: Seit Android 14 entzieht der
 * Play Store USE_FULL_SCREEN_INTENT nach der Installation allen Apps, die er nicht als Wecker-
 * oder Telefonie-App einstuft. Ohne die Berechtigung degradiert Android den Full-Screen-Intent
 * still zu einem Banner — der Wecker klingelt, aber der Weck-Screen kommt nie hoch, und nichts
 * weist darauf hin. Ein reiner Hinweistext ohne Absprung waere hier wertlos.
 *
 * Der Zustand wird bei jedem Aufruf frisch gelesen (kein remember), damit die Karte nach der
 * Rueckkehr aus den Einstellungen sofort umspringt.
 */
@Composable
private fun FullScreenIntentCard() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Bei jedem ON_RESUME neu pruefen: der Nutzer kann die Berechtigung ausserhalb der App
    // aendern, und danach muss die Karte stimmen.
    var canUseFsi by remember { mutableStateOf(checkFullScreenIntentAllowed(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canUseFsi = checkFullScreenIntentAllowed(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SpacingConstants.PADDING_CARD),
            horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (canUseFsi) Icons.Default.CheckCircle else Icons.Default.Error,
                contentDescription = null,
                modifier = Modifier.size(SpacingConstants.ICON_SIZE_LARGE),
                tint = if (canUseFsi)
                    MaterialTheme.colorScheme.success
                else
                    MaterialTheme.colorScheme.error
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Vollbild-Wecker",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (canUseFsi) {
                        "Der Weck-Bildschirm darf angezeigt werden"
                    } else {
                        "⚠️ Nicht erlaubt — der Wecker erscheint nur als Banner, " +
                            "der Weck-Bildschirm kommt nicht von selbst hoch"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )

                if (!canUseFsi) {
                    Spacer(Modifier.height(SpacingConstants.SPACING_SMALL))
                    SettingsLinkButton(
                        onClick = { openFullScreenIntentSettings(context) },
                        text = "Einstellung öffnen"
                    )
                }
            }
        }
    }
}

/**
 * Meldet, ob die App von der Akku-Optimierung ausgenommen ist — die Grundvoraussetzung dafuer,
 * dass die 6h-Wartung UND die exakten Wecker-Alarme im Hintergrund ueberhaupt feuern duerfen.
 *
 * Warum eine eigene Statuskarte: Ist die App NICHT ausgenommen, darf Android sie im Doze/Standby
 * einfrieren — dann werden keine neuen Schichten mehr abgeholt und der Wecker bleibt still, ohne
 * dass etwas darauf hinweist. Der Settings-Tab hat zwar eine Warnkarte, aber die verschwindet,
 * sobald man ausgenommen ist; hier ist der Zustand DAUERHAFT ablesbar (gruen = ok), genau wie
 * beim Vollbild-Wecker daneben.
 *
 * Wie [FullScreenIntentCard] wird der Zustand bei jedem ON_RESUME frisch gelesen (kein remember
 * ueber den Lebenszyklus hinweg), damit die Karte nach der Rueckkehr aus dem System-Dialog sofort
 * auf gruen springt. Der Knopf loest Androids System-Dialog aus ("Zulassen, dass die App immer im
 * Hintergrund laeuft?") — kein Einstellungs-Menue, deshalb verspricht der Text auch keinen Ablauf.
 */
@Composable
private fun BatteryOptimizationCard() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isExempt by remember { mutableStateOf(BatteryOptimizationHelper.isExempted(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isExempt = BatteryOptimizationHelper.isExempted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SpacingConstants.PADDING_CARD),
            horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isExempt) Icons.Default.CheckCircle else Icons.Default.Error,
                contentDescription = null,
                modifier = Modifier.size(SpacingConstants.ICON_SIZE_LARGE),
                tint = if (isExempt)
                    MaterialTheme.colorScheme.success
                else
                    MaterialTheme.colorScheme.error
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Akku-Ausnahme",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (isExempt) {
                        "Der Wecker darf jederzeit im Hintergrund laufen"
                    } else {
                        "⚠️ Android darf die App einfrieren — dann werden keine Schichten mehr " +
                            "abgeholt und der Wecker bleibt still"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )

                if (!isExempt) {
                    Spacer(Modifier.height(SpacingConstants.SPACING_SMALL))
                    SettingsLinkButton(
                        onClick = {
                            (context as? android.app.Activity)?.let {
                                BatteryOptimizationHelper.requestExemption(it)
                            }
                        },
                        text = "Ausnahme erlauben"
                    )
                }
            }
        }
    }
}

/**
 * Meldet, ob Android "App bei Nichtnutzung pausieren" fuer diese App aktiv haelt — ein
 * eigenstaendiger Mechanismus, unabhaengig von der Akku-Ausnahme oben (die schuetzt nur vor
 * Doze/Standby, nicht vor diesem Force-Stop-Pfad). Live am 20.07.2026 als Ursache eines
 * ausgebliebenen Weckers nachgewiesen: aktiv geht die App per Force-Stop unter, alle gesetzten
 * Alarme werden dabei lautlos geloescht.
 *
 * Der Check ist async (ListenableFuture, kein synchroner Getter wie bei Battery) - deshalb hier
 * ueber [LaunchedEffect] statt eines synchronen `remember`-Initializers, per [refreshTrigger]
 * bei jedem ON_RESUME neu angestossen.
 */
@Composable
private fun UnusedAppRestrictionsCard() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isOk by remember { mutableStateOf(true) }
    var refreshTrigger by remember { mutableStateOf(0) }

    LaunchedEffect(refreshTrigger) {
        isOk = !UnusedAppRestrictionsHelper.isRestricted(context)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshTrigger++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SpacingConstants.PADDING_CARD),
            horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isOk) Icons.Default.CheckCircle else Icons.Default.Error,
                contentDescription = null,
                modifier = Modifier.size(SpacingConstants.ICON_SIZE_LARGE),
                tint = if (isOk)
                    MaterialTheme.colorScheme.success
                else
                    MaterialTheme.colorScheme.error
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Nicht verwendete Apps",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (isOk) {
                        "\"Bei Nichtnutzung pausieren\" ist aus - der Wecker bleibt aktiv"
                    } else {
                        "⚠️ Android darf die App pausieren — dabei gehen alle gesetzten " +
                            "Wecker-Alarme verloren"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )

                if (!isOk) {
                    Spacer(Modifier.height(SpacingConstants.SPACING_SMALL))
                    SettingsLinkButton(
                        onClick = {
                            try {
                                context.startActivity(
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
                        text = "Einstellung öffnen"
                    )
                }
            }
        }
    }
}

/**
 * TimeOffice ist keine eigene CFAlarm-Funktion, sondern die vorgelagerte Datenquelle: TimeOffice
 * (de.pradtke.timeoffice) schreibt den Dienstplan lokal in einen Google-Kalender, aus dem CFAlarm
 * seine Alarme baut. Live am 30.07.2026 nachgewiesen: TimeOffice selbst war von "App bei
 * Nichtnutzung pausieren" (aktiv) UND Akku-Optimierung "Optimiert" betroffen — der Sync blieb
 * tagelang stehen, ohne dass CFAlarm selbst etwas davon gemerkt haette (die eigenen Alarme
 * funktionierten ja weiter, nur die Datenquelle war veraltet).
 *
 * ANDERS ALS [BatteryOptimizationCard]/[UnusedAppRestrictionsCard]: nur die Akku-Ausnahme ist fuer
 * ein fremdes Package pruefbar (siehe [TimeOfficeHealthHelper]) — fuer "Nicht verwendete Apps"
 * gibt es keine oeffentliche API, um den Status einer anderen App abzufragen. Diese Zeile zeigt
 * deshalb bewusst KEIN Gruen/Rot, nur einen Hinweis + denselben Aktions-Button.
 *
 * Rendert nichts, wenn TimeOffice nicht installiert ist (einmaliger Check per `remember` — der
 * Installationsstatus aendert sich nicht waehrend die App laeuft).
 */
@Composable
private fun TimeOfficeHealthCard() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val isInstalled = remember { TimeOfficeHealthHelper.isInstalled(context) }
    if (!isInstalled) return

    var isBatteryExempt by remember { mutableStateOf(TimeOfficeHealthHelper.isBatteryExempted(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isBatteryExempt = TimeOfficeHealthHelper.isBatteryExempted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(SpacingConstants.PADDING_CARD)) {
            Text(
                "TimeOffice-Zuverlässigkeit",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(SpacingConstants.SPACING_SMALL))

            Row(
                horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isBatteryExempt) Icons.Default.CheckCircle else Icons.Default.Error,
                    contentDescription = null,
                    modifier = Modifier.size(SpacingConstants.ICON_SIZE_LARGE),
                    tint = if (isBatteryExempt)
                        MaterialTheme.colorScheme.success
                    else
                        MaterialTheme.colorScheme.error
                )
                Text(
                    if (isBatteryExempt) {
                        "Akku-Optimierung: ausgenommen"
                    } else {
                        "⚠️ Akku-Optimierung: eingeschränkt — der Dienstplan-Sync kann ausbleiben"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(SpacingConstants.SPACING_SMALL))

            Row(
                horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(SpacingConstants.ICON_SIZE_LARGE),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "\"Nicht verwendete Apps\": von hier nicht prüfbar — bleiben Schichten oder " +
                        "Krankschreibungen mehrere Tage aus, hier nachsehen",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(SpacingConstants.SPACING_SMALL))
            SettingsLinkButton(
                onClick = {
                    try {
                        context.startActivity(TimeOfficeHealthHelper.createAppInfoIntent(context))
                    } catch (e: Exception) {
                        Logger.e(
                            LogTags.TIMEOFFICE_HEALTH,
                            "Failed to open TimeOffice app-info settings",
                            e
                        )
                    }
                },
                text = "TimeOffice-Einstellungen öffnen"
            )
        }
    }
}

/**
 * Meldet, ob der Bedienungshilfen-Dienst des Schicht-Dimmers laeuft — nur dann kann das
 * Dimm-Overlay ueberhaupt erscheinen. Anders als die Wecker-Karten ist das ein OPTIONALES
 * Feature; die Karte steht hier, damit der Dienst-Status an einer Stelle ablesbar ist und der
 * Nutzer den Dienst (nach der Play-Pflicht-Offenlegung) direkt aktivieren kann.
 *
 * Wie die Nachbarkarten wird der Zustand bei jedem ON_RESUME frisch gelesen — nach der Rueckkehr
 * aus den Bedienungshilfen-Einstellungen springt die Karte so sofort auf gruen. Android startet
 * den Dienst nicht automatisch neu, wenn der Nutzer ihn dort abschaltet.
 */
@Composable
private fun DimmerAccessibilityCard() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isActive by remember { mutableStateOf(DimAccessibilityService.isRunning()) }
    var showDisclosure by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isActive = DimAccessibilityService.isRunning()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (showDisclosure) {
        AlertDialog(
            onDismissRequest = { showDisclosure = false },
            title = { Text(stringResource(R.string.dimmer_disclosure_title)) },
            text = { Text(stringResource(R.string.dimmer_disclosure_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showDisclosure = false
                    openAccessibilitySettings(context)
                }) { Text(stringResource(R.string.dimmer_open_accessibility)) }
            },
            dismissButton = {
                TextButton(onClick = { showDisclosure = false }) {
                    Text(stringResource(R.string.dimmer_disclosure_understood))
                }
            }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive)
                MaterialTheme.colorScheme.surface
            else
                MaterialTheme.colorScheme.errorContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SpacingConstants.PADDING_CARD),
            horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isActive) Icons.Default.CheckCircle else Icons.Default.Error,
                contentDescription = null,
                modifier = Modifier.size(SpacingConstants.ICON_SIZE_LARGE),
                tint = if (isActive)
                    MaterialTheme.colorScheme.success
                else
                    MaterialTheme.colorScheme.onErrorContainer
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Schicht-Dimmer",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isActive) Color.Unspecified else MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    if (isActive) {
                        "Bedienungshilfen-Dienst aktiv — das Dimm-Overlay kann erscheinen"
                    } else {
                        "Bedienungshilfen-Dienst nicht aktiv — das Dimmen wirkt erst nach dem Aktivieren"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isActive) Color.Unspecified else MaterialTheme.colorScheme.onErrorContainer
                )

                if (!isActive) {
                    Text(
                        "⚠️ Aktiver Fehler — bitte prüfen",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(SpacingConstants.SPACING_SMALL))
                    SettingsLinkButton(
                        onClick = { showDisclosure = true },
                        text = stringResource(R.string.dimmer_open_accessibility)
                    )
                }
            }
        }
    }
}

/**
 * Freigabe-Status fuer die DND-Steuerung (ACCESS_NOTIFICATION_POLICY). Nur ab API 30 - siehe
 * [com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndScheduleUseCase.isSupported]. Refresh bei
 * ON_RESUME, analog zu [DimmerAccessibilityCard] (Rueckkehr aus den Einstellungen aktualisiert
 * sofort, kein reines `remember`-Caching).
 */
@Composable
private fun DndPermissionCard() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isSupported = remember { DndPermissionHelper.isFeatureSupported() }

    var isGranted by remember {
        mutableStateOf(isSupported && DndPermissionHelper.isGranted(context))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && isSupported) {
                isGranted = DndPermissionHelper.isGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SpacingConstants.PADDING_CARD),
            horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (!isSupported || isGranted) Icons.Default.CheckCircle else Icons.Default.Error,
                contentDescription = null,
                modifier = Modifier.size(SpacingConstants.ICON_SIZE_LARGE),
                tint = if (!isSupported || isGranted) MaterialTheme.colorScheme.success else MaterialTheme.colorScheme.error
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.dnd_status_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    when {
                        !isSupported -> stringResource(R.string.dnd_unsupported)
                        isGranted -> stringResource(R.string.dnd_status_ok)
                        else -> stringResource(R.string.dnd_status_missing)
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                if (isSupported && !isGranted) {
                    Spacer(Modifier.height(SpacingConstants.SPACING_SMALL))
                    SettingsLinkButton(
                        onClick = { DndPermissionHelper.requestAccess(context) },
                        text = stringResource(R.string.dnd_permission_grant)
                    )
                }
            }
        }
    }
}

private fun openAccessibilitySettings(context: android.content.Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    } catch (e: Exception) {
        Logger.e(LogTags.UI, "❌ Bedienungshilfen-Einstellungen nicht erreichbar", e)
    }
}

private fun checkFullScreenIntentAllowed(context: android.content.Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    } else {
        // < API 34: Die Berechtigung wird mit der Installation gewaehrt und nicht entzogen.
        true
    }

/**
 * Fuehrt auf die Benachrichtigungs-Einstellungen DIESER App. Bewusst nicht die
 * Laufzeit-Berechtigungsabfrage: die zeigt Android nach einer Ablehnung gar nicht mehr an, der Weg
 * ueber die Einstellungen ist dann der einzige, der wirklich funktioniert.
 */
private fun openAppNotificationSettings(context: android.content.Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        )
    } catch (e: Exception) {
        Logger.e(LogTags.UI, "❌ Benachrichtigungs-Einstellungen nicht erreichbar", e)
    }
}

private fun openFullScreenIntentSettings(context: android.content.Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
    try {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                "package:${context.packageName}".toUri()
            )
        )
    } catch (e: Exception) {
        Logger.e(LogTags.UI, "❌ Einstellung für Vollbild-Benachrichtigungen nicht erreichbar", e)
    }
}

@Composable
private fun StatusCard(
    title: String,
    isOk: Boolean,
    details: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    actionEnabled: Boolean = true
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SpacingConstants.PADDING_CARD),
            horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isOk) Icons.Default.CheckCircle else Icons.Default.Error,
                contentDescription = null,
                modifier = Modifier.size(SpacingConstants.ICON_SIZE_LARGE),
                tint = if (isOk)
                    MaterialTheme.colorScheme.success
                else
                    MaterialTheme.colorScheme.error
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    details,
                    style = MaterialTheme.typography.bodyMedium
                )

                if (!isOk && actionLabel != null && onAction != null) {
                    Spacer(Modifier.height(SpacingConstants.SPACING_SMALL))
                    SettingsLinkButton(onClick = onAction, text = actionLabel, enabled = actionEnabled)
                }
            }
        }
    }
}

@Composable
private fun CacheStatusCard(calendarViewModel: CalendarViewModel?) {
    val context = LocalContext.current
    var cacheStats by remember { mutableStateOf("Cache-Statistiken laden...") }

    // KEIN derivedStateOf: Hier stand `remember { derivedStateOf { !isNetworkAvailable(context) } }`.
    // `derivedStateOf` invalidiert ausschliesslich, wenn ein im Block GELESENER Snapshot-State sich
    // aendert — `isNetworkAvailable()` liest aber den ConnectivityManager, keinen Snapshot-State.
    // Der Wert wurde also genau einmal berechnet und blieb fuer die gesamte Lebensdauer der
    // Composition stehen: Flugmodus an, Karte behauptet weiter "Online - Cache aktiv". In einem
    // Screen, dessen Zweck die Diagnose von "warum kam kein Wecker" ist, ist das genau in dem
    // Moment eine Falschaussage, in dem sie zaehlt.
    //
    // Bewusst der NetworkCallback statt des ON_RESUME-Musters der Nachbarkarten: Der gemeldete Fall
    // ist "Flugmodus an, WAEHREND der Tab offen ist" — ON_RESUME feuert dabei nie. Der Callback
    // deckt beides ab, deshalb bleibt es bei EINEM Mechanismus statt zwei.
    var isOffline by remember { mutableStateOf(!isNetworkAvailable(context)) }
    DisposableEffect(context) {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            private fun refresh() {
                isOffline = !isNetworkAvailable(context)
            }

            override fun onAvailable(network: Network) = refresh()
            override fun onLost(network: Network) = refresh()
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) = refresh()
        }
        // Der Callback ist reine Diagnose-Anzeige: schlaegt die Registrierung fehl, bleibt es beim
        // Startwert, statt den Status-Tab mitzunehmen.
        val registered = try {
            connectivityManager?.registerDefaultNetworkCallback(callback)
            connectivityManager != null
        } catch (_: Exception) {
            false
        }
        onDispose {
            if (registered) {
                try {
                    connectivityManager?.unregisterNetworkCallback(callback)
                } catch (_: Exception) {
                    // bereits abgemeldet - nichts zu tun
                }
            }
        }
    }

    // Nur einmal laden, nicht bei jeder Recomposition
    LaunchedEffect(calendarViewModel) {
        calendarViewModel?.let { viewModel ->
            try {
                viewModel.getCacheStats()
                cacheStats = "Cache-Statistiken in Log ausgegeben"
            } catch (_: Exception) {
                cacheStats = "Cache-Statistiken nicht verfügbar"
            }
        } ?: run {
            cacheStats = "Cache-Statistiken nicht verfügbar (kein ViewModel)"
        }
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(SpacingConstants.PADDING_CARD),
            verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isOffline) Icons.Default.CloudOff else Icons.Default.Storage,
                    contentDescription = null,
                    modifier = Modifier.size(SpacingConstants.ICON_SIZE_LARGE),
                    tint = if (isOffline)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.success
                )
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (isOffline) "Offline-Modus" else "Cache-Status",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (isOffline) "Offline - verwende gespeicherte Daten" else "Online - Cache aktiv",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                
                // Cache Actions
                if (calendarViewModel != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
                    ) {
                        IconButton(
                            onClick = { 
                                calendarViewModel.getCacheStats()
                            }
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Cache-Stats aktualisieren",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
            
            HorizontalDivider()
            
            // Cache Statistics
            Text(
                "Cache-Details:",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                cacheStats,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            // Cache Actions Row
            if (calendarViewModel != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
                ) {
                    // Zwei weight(1f)-Buttons in einer Kartenzeile: genau der Fall, fuer den es
                    // CompactActionButton gibt. Mit der Standard-ContentPadding (24dp pro Seite)
                    // bleiben auf einem 360dp-Geraet nur ~96dp fuer die Schrift — bei groesserer
                    // Systemschrift bricht Compose "Cache leeren" mitten im Wort um (dasselbe
                    // Symptom, das im HueSettingsScreen als "Bea/rbei/ten" gemeldet wurde).
                    CompactOutlinedButton(
                        onClick = { calendarViewModel.clearEventCache() },
                        text = "Cache leeren",
                        modifier = Modifier.weight(1f)
                    )

                    CompactButton(
                        onClick = { calendarViewModel.refreshData(forceRefresh = true) },
                        text = "Neu laden",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun LastSyncCard(calendarViewModel: CalendarViewModel?) {
    val context = LocalContext.current
    var lastMaintenanceTime by remember { mutableStateOf(0L) }

    // Wartungszeit laden und alle 30s aktualisieren
    LaunchedEffect(Unit) {
        lastMaintenanceTime = AlarmMaintenanceService.getLastMaintenanceTime(context)
        while (true) {
            kotlinx.coroutines.delay(30_000)
            lastMaintenanceTime = AlarmMaintenanceService.getLastMaintenanceTime(context)
        }
    }

    val timeSinceLastMaintenance = if (lastMaintenanceTime > 0) {
        System.currentTimeMillis() - lastMaintenanceTime
    } else {
        -1L
    }

    val lastMaintenanceText = when {
        lastMaintenanceTime == 0L -> "Noch nie ausgeführt"
        timeSinceLastMaintenance < 0 -> "Unbekannt"
        timeSinceLastMaintenance < TimeUnit.HOURS.toMillis(1) ->
            "Vor ${TimeUnit.MILLISECONDS.toMinutes(timeSinceLastMaintenance)} Minuten"
        timeSinceLastMaintenance < TimeUnit.DAYS.toMillis(1) ->
            "Vor ${TimeUnit.MILLISECONDS.toHours(timeSinceLastMaintenance)} Stunden"
        else ->
            "Vor ${TimeUnit.MILLISECONDS.toDays(timeSinceLastMaintenance)} Tagen"
    }

    val statusColor = when {
        lastMaintenanceTime == 0L -> MaterialTheme.colorScheme.tertiary
        timeSinceLastMaintenance < TimeUnit.HOURS.toMillis(12) -> MaterialTheme.colorScheme.primary
        timeSinceLastMaintenance < TimeUnit.HOURS.toMillis(24) -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SpacingConstants.PADDING_CARD),
            horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_LARGE),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier.size(SpacingConstants.ICON_SIZE_STANDARD),
                tint = statusColor
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Letzter Sync",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    lastMaintenanceText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = statusColor
                )
                if (timeSinceLastMaintenance > TimeUnit.HOURS.toMillis(24)) {
                    Text(
                        "⚠️ Langer Zeitraum - bitte prüfen",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    if (calendarViewModel != null) {
                        Spacer(Modifier.height(SpacingConstants.SPACING_SMALL))
                        SettingsLinkButton(
                            onClick = { calendarViewModel.refreshData(forceRefresh = true) },
                            text = "Jetzt synchronisieren"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DebugInfoCard() {
    val context = LocalContext.current
    var showEmailSuccess by remember { mutableStateOf(false) }
    var showEmailError by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var deleteResultMessage by remember { mutableStateOf<String?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(SpacingConstants.PADDING_CARD),
            verticalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)
        ) {
            Text(
                "Debug-Informationen",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            // Logging-Beschreibung
            Text(
                "Wie funktioniert das Logging?",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Die App schreibt Logs nun täglich in separate Dateien (z.B. debug_logs_2026-07-12.txt) und behält diese für genau 8 Tage, um eine vollständige Woche abbilden zu können. Ältere Dateien werden automatisch bereinigt. Beim Versenden werden alle vorhandenen Dateien angehängt.",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Hinweis: Die Logs enthalten Diagnosedaten (Gerätemodell, App-Version, Zeitstempel, App-Ereignisse). Beim Senden öffnet sich der Teilen-Dialog – vorausgefüllt als E-Mail an cfischer@csj.de, oder du wählst eine andere App.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            // Log-Datei Info
            com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogEmailUtil.getLogFileInfo(context)?.let { info ->
                HorizontalDivider()
                Text(
                    "Log-Datei:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    info,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            
            // Button zum E-Mail-Versand
            Button(
                onClick = {
                    val result = com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogEmailUtil.sendLogFileViaEmail(context)
                    if (result.isSuccess) {
                        showEmailSuccess = true
                    } else {
                        showEmailError = result.exceptionOrNull()?.message ?: "Unbekannter Fehler"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogEmailUtil.hasLogFile(context)
            ) {
                Icon(
                    imageVector = Icons.Default.Storage,
                    contentDescription = null,
                    modifier = Modifier.size(SpacingConstants.ICON_SIZE_MEDIUM)
                )
                Spacer(modifier = Modifier.width(SpacingConstants.SPACING_SMALL))
                Text("Logs an Entwickler senden")
            }
            
            // Erfolgs-/Fehlermeldungen
            if (showEmailSuccess) {
                Text(
                    "✅ E-Mail-App geöffnet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(3000)
                    showEmailSuccess = false
                }
            }

            showEmailError?.let { error ->
                Text(
                    "❌ $error",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(5000)
                    showEmailError = null
                }
            }

            // Button zum manuellen Aufraeumen - bewusst NICHT an den Versand oben gekoppelt
            // (siehe LogEmailUtil.deleteOldLogs-Doku): die heutige Datei bleibt garantiert
            // erhalten, unabhaengig davon, wann am Tag getippt wird.
            OutlinedButton(
                onClick = { showDeleteConfirm = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteSweep,
                    contentDescription = null,
                    modifier = Modifier.size(SpacingConstants.ICON_SIZE_MEDIUM)
                )
                Spacer(modifier = Modifier.width(SpacingConstants.SPACING_SMALL))
                Text("Alte Logs löschen")
            }

            deleteResultMessage?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(3000)
                    deleteResultMessage = null
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Alte Logs löschen?") },
            text = {
                Text("Löscht alle Log-Dateien außer der von heute. Die heutige, noch aktive Datei bleibt erhalten.")
            },
            confirmButton = {
                TextButton(onClick = {
                    val deleted = com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogEmailUtil.deleteOldLogs(context)
                    deleteResultMessage = "🗑️ $deleted Datei(en) gelöscht"
                    showDeleteConfirm = false
                }) {
                    Text("Löschen")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Abbrechen")
                }
            }
        )
    }
}

/**
 * Überprüft die Netzwerkverbindung
 */
private fun isNetworkAvailable(context: Context): Boolean {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false

        networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } else {
        @Suppress("DEPRECATION")
        val activeNetworkInfo = connectivityManager.activeNetworkInfo
        @Suppress("DEPRECATION")
        activeNetworkInfo?.isConnectedOrConnecting == true
    }
}
