package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
import androidx.compose.material.icons.filled.PauseCircle
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.FeedNeueinlesenStand
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AuthState
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmMaintenanceService
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.CompactButton
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.CompactOutlinedButton
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.SettingsLinkButton
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.success
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.AuthViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.CalendarUiState
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.CalendarViewModel
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.ShiftUiState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun StatusTabContent(
    authState: AuthState,
    calendarState: CalendarUiState,
    shiftState: ShiftUiState,
    calendarViewModel: CalendarViewModel?,
    authViewModel: AuthViewModel,
    onShowCalendarSelection: () -> Unit,
    masterPausePaused: Boolean,
    onResumeMasterPause: () -> Unit
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

        // GANZ OBEN und nicht bei der Kalender-Karte darunter: Wenn diese Karte erscheint,
        // klingeln Wecker eines Dienstplans, den der Nutzer entfernt hat - das schlaegt jeden
        // anderen Status auf diesem Bildschirm.
        VerwaisteWeckerNachAbwahlCard(
            fehlversuche = calendarState.deselectionCleanupFailures,
            onErneutVersuchen = { calendarViewModel?.retryDeselectionCleanup() },
            erneutVersuchenMoeglich = calendarViewModel != null
        )

        // Direkt danach und vor allen Einzel-Diagnosen: Ist alles pausiert, sind saemtliche
        // Karten darunter belanglos - selbst wenn Anmeldung, Kalender und Schichterkennung gruen
        // melden, entsteht kein einziger Wecker. Jede andere dauerhaft sync-anhaltende Ursache
        // hat hier eine Karte; diese fehlte, und sie ist die einzige, aus der die App NIE von
        // allein herauslaeuft (die 6h-Wartung ist mit abgeschaltet).
        AllesPausiertCard(
            pausiert = masterPausePaused,
            onWiederAktivieren = onResumeMasterPause
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

        // Kalender Status: drei echte, unterscheidbare Fehlerzustaende - je einer bekommt seine
        // eigene Aktion (nicht gleichzeitig moeglich, siehe Bedingungen unten). "Keine Kalender
        // verfuegbar" (leeres Google-Konto) bleibt ohne Button - nichts, wohin man von hier aus
        // springen koennte.
        //
        // Der dritte Zustand (Teilerfolg) kam in v1.26.0 dazu und ist der stillste: die
        // Autorisierung ist gueltig, Termine kommen an, nur EIN Kalender antwortet nicht. Die
        // Vollstaendigkeits-Sperren halten dann jeden Alarm-Sync an - richtig, aber ohne diese
        // Karte unsichtbar. Er steht bewusst NACH der Autorisierungs-Pruefung: fallen ALLE
        // Kalender aus, ist das kein Teilerfolg, sondern der Autorisierungsfall darueber.
        val teilerfolg = calendarState.unavailableCalendarIds.isNotEmpty()
        // Wuerde "Aus Auswahl entfernen" die Auswahl LEEREN, ist es keine Bereinigung mehr,
        // sondern eine Abwahl - mit allen Folgen. Dann wird vorher gefragt (siehe Dialog unten).
        var entfernenBestaetigen by remember { mutableStateOf(false) }
        val entfernenLeertAuswahl = entfernenWuerdeAuswahlLeeren(
            ausgewaehlt = calendarState.selectedCalendarIds,
            nichtAbrufbar = calendarState.unavailableCalendarIds
        )
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
            teilerfolg && calendarViewModel != null -> {
                calendarActionLabel = "Aus Auswahl entfernen"
                onCalendarAction = {
                    if (entfernenLeertAuswahl) entfernenBestaetigen = true
                    else calendarViewModel.removeUnavailableCalendarsFromSelection()
                }
            }
            else -> {
                calendarActionLabel = null
                onCalendarAction = null
            }
        }
        StatusCard(
            title = "Kalender",
            isOk = calendarState.selectedCalendarIds.isNotEmpty() &&
                calendarState.calendarAuthorizationValid &&
                !teilerfolg,
            details = when {
                !calendarState.calendarAuthorizationValid && calendarState.selectedCalendarIds.isNotEmpty() ->
                    "⚠️ Kalender-Autorisierung verloren - Bitte neu anmelden"
                calendarState.selectedCalendarIds.isEmpty() -> "Kein Kalender ausgewählt"
                teilerfolg -> unavailableCalendarDetails(
                    unavailableIds = calendarState.unavailableCalendarIds,
                    namesById = calendarState.availableCalendars.associate { it.id to it.name }
                )
                calendarState.availableCalendars.isEmpty() -> "Keine Kalender verfügbar"
                else -> "${calendarState.selectedCalendarIds.size} Kalender ausgewählt, API-Zugriff OK"
            },
            actionLabel = calendarActionLabel,
            onAction = onCalendarAction,
            actionEnabled = !authState.calendarOps.calendarsLoading
        )

        if (entfernenBestaetigen && calendarViewModel != null) {
            LetzteAuswahlEntfernenDialog(
                onAbbrechen = { entfernenBestaetigen = false },
                onKalenderWaehlen = {
                    entfernenBestaetigen = false
                    onShowCalendarSelection()
                },
                onTrotzdemEntfernen = {
                    entfernenBestaetigen = false
                    calendarViewModel.removeUnavailableCalendarsFromSelection()
                }
            )
        }

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

        // Direkt darunter, weil es dieselbe Frage beantwortet ("was hat die App zuletzt im
        // Hintergrund getan?") - und ausdruecklich als ruhige Zeile ohne Karte, Farbe oder Icon.
        FeedNeueinlesenZeile(stand = calendarState.feedNeueinlesen)

        // Debug-Informationen
        DebugInfoCard()
        
        // Cache-Statistiken und Offline-Status
        CacheStatusCard(calendarViewModel = calendarViewModel)
    }
}



/**
 * Zeigt an, dass nach einer Kalender-Abwahl Wecker dieses Dienstplans stehengeblieben sind.
 *
 * WARUM EINE KARTE UND KEINE SNACKBAR (die Fassung, die das hier ersetzt): Der Hinweis lief als
 * `SnackbarDuration.Indefinite` auf dem GEMEINSAMEN SnackbarHostState von MainContentScreen -
 * dem einzigen Indefinite-Aufruf der App. `showSnackbar` serialisiert ueber einen Mutex: solange
 * diese eine Snackbar stand (und sie geht nur per Aktion oder Wischen weg), suspendierten ALLE
 * uebrigen Snackbar-Kanaele desselben Hosts, und die `clearError()`-Aufrufe hinter ihnen liefen
 * ebenfalls nicht - ein Kalender-, Schicht- oder Wecker-Fehler erreichte den Nutzer gar nicht
 * mehr und blieb dazu ungeleert im State stehen. Ein bleibender Hinweis darf die uebrigen
 * Meldungen nicht blockieren; die App zeigt bleibende Zustaende ohnehin ueberall sonst als Karte
 * im Status-Tab (Kalender-Teilerfolg, fehlende Berechtigungen, Akku-Ausnahme).
 *
 * SIE VERSCHWINDET VON SELBST, sobald der Zustand aufgeloest ist - geraeumt, Automatik aus,
 * Master-Pause oder wieder ein Kalender ausgewaehlt (siehe `resolveDeselectionCleanupFailure`).
 * Ein Wegtippen gibt es bewusst nicht: solange die Wecker klingeln koennen, hat die Karte etwas
 * zu sagen.
 *
 * @param erneutVersuchenMoeglich ohne CalendarViewModel gibt es niemanden, der den zweiten
 *   Anlauf ausfuehren koennte - dann bleibt der Knopf abgeblendet statt nur so zu tun.
 */
@Composable
private fun VerwaisteWeckerNachAbwahlCard(
    fehlversuche: Int,
    onErneutVersuchen: () -> Unit,
    erneutVersuchenMoeglich: Boolean
) {
    if (fehlversuche <= 0) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
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
                    imageVector = Icons.Default.Error,
                    // dekorativ: der Titel daneben benennt den Zustand in Worten
                    contentDescription = null,
                    modifier = Modifier.size(SpacingConstants.ICON_SIZE_LARGE),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    "Wecker eines abgewählten Kalenders",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }

            Text(
                CalendarViewModel.DESELECTION_CLEANUP_FAILED_MESSAGE,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )

            CompactButton(
                onClick = onErneutVersuchen,
                text = CalendarViewModel.DESELECTION_CLEANUP_RETRY_ACTION,
                modifier = Modifier.fillMaxWidth(),
                enabled = erneutVersuchenMoeglich
            )
        }
    }
}

/**
 * Nutzertexte der Master-Pause-Karte. Als Konstanten, damit ein Test genau sie festhalten kann -
 * der Text IST hier die Zusicherung: er muss die FOLGE benennen (kein Wecker klingelt) und den
 * AUSWEG, und beides ohne Fachbegriff.
 */
internal const val ALLES_PAUSIERT_TITEL: String = "Alles pausiert"

/**
 * Warum der Satz ueber die weiterlaufende Schichtanzeige darin steht: genau daran ist der Zustand
 * bisher unbemerkt geblieben. Die Schichterkennung laeuft unabhaengig weiter, Home zeigt also
 * brav die naechste Schicht - und der Nutzer schliesst daraus, der Wecker dazu stehe auch.
 */
internal const val ALLES_PAUSIERT_TEXT: String =
    "Du hast alle Hintergrunddienste pausiert. Es wird kein Wecker gestellt und keiner klingelt — " +
        "auch nicht für Schichten, die die App weiterhin anzeigt. Dimmen, \"Nicht stören\" und " +
        "die Hue-Automatik ruhen ebenfalls. Das bleibt so, bis du hier auf " +
        "\"Alles wieder aktivieren\" tippst oder im Einstellungen-Tab den Schalter " +
        "\"Hintergrunddienste pausieren\" ausschaltest."

internal const val ALLES_PAUSIERT_AKTION: String = "Alles wieder aktivieren"

/**
 * Zeigt an, dass die Master-Pause laeuft - und hebt sie auf Wunsch gleich hier auf.
 *
 * WARUM MIT KNOPF UND NICHT NUR ALS HINWEIS: Der Nutzer kommt in diesen Tab, WEIL er sucht,
 * warum kein Wecker kommt. Ihn dann in einen anderen Tab zu schicken, ist ein unnoetiger Schritt
 * in genau dem Moment, in dem er die Antwort gefunden hat. Der Weg ueber den Schalter in den
 * Einstellungen bleibt daneben bestehen und wird im Text benannt - beide fuehren ueber dasselbe
 * `MasterPauseViewModel.setPaused(false)`.
 *
 * DIE KARTE SELBST SCHREIBT NICHTS. Sie rendert nur; erst der ausdrueckliche Tap loest
 * `resume()` aus.
 */
@Composable
private fun AllesPausiertCard(
    pausiert: Boolean,
    onWiederAktivieren: () -> Unit
) {
    if (!pausiert) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
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
                    imageVector = Icons.Default.PauseCircle,
                    // dekorativ: der Titel daneben benennt den Zustand in Worten
                    contentDescription = null,
                    modifier = Modifier.size(SpacingConstants.ICON_SIZE_LARGE),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    ALLES_PAUSIERT_TITEL,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }

            Text(
                ALLES_PAUSIERT_TEXT,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )

            CompactButton(
                onClick = onWiederAktivieren,
                text = ALLES_PAUSIERT_AKTION,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Der Text der stillen Zeile "Dienstplan-Kalender zuletzt neu eingelesen" - PURE UND TESTBAR, wie
 * die `noShiftExplanation`-Familie in HomeTabContent.
 *
 * ## Was er erzaehlt, und warum ueberhaupt
 *
 * Der Dienstplan kommt aus einem ABONNIERTEN Kalender. Alle paar Tage liest Google den Feed neu
 * ein und gibt jedem Termin eine neue Kennung, ohne dass sich an Schicht oder Weckzeit etwas
 * aendert. Die App erkennt ihre Wecker daran wieder (gleiche Schicht, gleiche Weckzeit = derselbe
 * Wecker) und laesst sie unangetastet - voellig geraeuschlos, absichtlich ohne Benachrichtigung.
 * Der Nutzer hat trotzdem gefragt: "woran erkenne ich das?" Diese Zeile ist die Antwort und sonst
 * nichts.
 *
 * ## Drei Dinge muss der Satz leisten
 *
 * 1. OHNE FACHBEGRIFF. Keine "Event-ID", keine "Kennung", kein "Feed", kein "Sync" - der Nutzer
 *    ist Schichtarbeiter und kein Kalender-Entwickler. Es steht da, was passiert ist: der Kalender
 *    wurde neu eingelesen, die Wecker wurden wiedererkannt und neu zugeordnet.
 * 2. DIE FOLGE BENENNEN, und zwar die beruhigende: am Dienstplan hat sich dadurch NICHTS geaendert.
 *    Ohne diesen Halbsatz liest sich "11 Wecker neu zugeordnet" wie eine Aenderung am Dienstplan -
 *    genau die Fehldeutung, die die App an anderer Stelle schon einmal ausgeloest hat, als
 *    taeglich "Neue Schicht erkannt" fuer den jeweils neuen Randtag erschien.
 * 3. SINGULAR UND PLURAL. "1 Wecker" statt "1 Wecker*innen"-Kauderwelsch; ein falscher Plural in
 *    einer beruhigenden Zeile untergraebt genau die Ruhe, die sie stiften soll.
 *
 * Datum bewusst kurz (Tag und Monat, "22.08."): das Jahr traegt hier keine Auskunft, und der
 * Vorgang liegt naturgemaess wenige Tage zurueck.
 *
 * @return `null`, wenn es noch nie vorkam - dann gibt es auch keine Zeile.
 */
internal fun feedNeueinlesenHinweis(
    stand: FeedNeueinlesenStand?,
    zone: ZoneId = ZoneId.systemDefault()
): String? {
    if (stand == null) return null
    // Backstop gegen einen unsinnigen Stand: eine Zeile mit "0 Wecker" oder einem Datum aus dem
    // Jahr 1970 waere schlechter als gar keine. Der Store laesst so etwas gar nicht erst entstehen.
    if (stand.anzahl <= 0 || stand.zeitpunkt <= 0L) return null

    val datum = DateTimeFormatter.ofPattern("dd.MM.", Locale.GERMANY)
        .withZone(zone)
        .format(Instant.ofEpochMilli(stand.zeitpunkt))
    val wecker = if (stand.anzahl == 1) "1 Wecker" else "${stand.anzahl} Wecker"

    return "Dienstplan-Kalender zuletzt neu eingelesen: $datum, $wecker wiedererkannt und neu " +
        "zugeordnet. Am Dienstplan hat sich dadurch nichts geändert."
}

/**
 * Rendert [feedNeueinlesenHinweis] - eine ruhige Zeile, kein Ereignis.
 *
 * BEWUSST KEINE KARTE, KEIN ICON, KEINE WARNFARBE: hier ist nichts kaputt und nichts zu tun. Alle
 * uebrigen Elemente dieses Tabs melden einen Zustand, der Aufmerksamkeit braucht; diese Zeile ist
 * reine Auskunft und muss sich optisch klar davon unterscheiden, sonst sucht der Nutzer ein
 * Problem, das es nicht gibt. Aus demselben Grund gibt es dazu keine Benachrichtigung.
 */
@Composable
private fun FeedNeueinlesenZeile(stand: FeedNeueinlesenStand?) {
    val text = feedNeueinlesenHinweis(stand) ?: return

    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SpacingConstants.PADDING_CARD)
    )
}

/**
 * PURE, TESTBAR: Bliebe nach "Aus Auswahl entfernen" KEIN Kalender mehr ausgewaehlt?
 *
 * WARUM DAS EINE EIGENE FRAGE IST: Der Knopf ist als Bereinigung gedacht - "der Kalender ist weg,
 * nimm ihn aus der Auswahl". Trifft er den letzten ausgewaehlten Kalender, tut er etwas ganz
 * anderes: er loest eine Abwahl aus, und die raeumt seit v1.29.3 alle kalenderbasierten Wecker der
 * naechsten zwei Wochen samt der Dienstzeit-Fenster fuer Dimmer und "Nicht stoeren". Der Anlass
 * ist dabei haeufig voruebergehend (Server- oder Freigabestoerung), also etwas, das von allein
 * vergeht - deshalb darf dieser Fall nicht mit demselben beilaeufigen Tippen passieren wie das
 * Entfernen eines von mehreren Kalendern.
 *
 * Verglichen wird gegen die AKTUELL ausgewaehlten IDs und nicht gegen eine gemerkte Anzahl: die
 * Liste der nicht abrufbaren Kalender stammt aus dem letzten Ladevorgang, die Auswahl kann sich
 * seither geaendert haben.
 */
internal fun entfernenWuerdeAuswahlLeeren(
    ausgewaehlt: Set<String>,
    nichtAbrufbar: Set<String>
): Boolean = ausgewaehlt.isNotEmpty() &&
    nichtAbrufbar.isNotEmpty() &&
    ausgewaehlt.all { it in nichtAbrufbar }

/**
 * Nutzertexte der Rueckfrage. Als Konstanten, damit ein Test genau sie festhalten kann - der Text
 * IST hier die Zusicherung: er muss die Folge benennen und den harmlosen Weg zuerst anbieten.
 */
internal const val ENTFERNEN_LEERT_AUSWAHL_TITEL: String = "Danach wäre kein Kalender ausgewählt"

internal const val ENTFERNEN_LEERT_AUSWAHL_TEXT: String =
    "Dieser Kalender ist deine einzige Schichtquelle. Entfernst du ihn, werden alle Wecker der " +
        "nächsten zwei Wochen gelöscht, und der Dimmer sowie \"Nicht stören\" schalten nicht mehr " +
        "nach deinen Dienstzeiten. Selbst gestellte Wecker bleiben.\n\n" +
        "Dass ein Kalender gerade nicht abrufbar ist, liegt oft an einer vorübergehenden Störung " +
        "oder einer entzogenen Freigabe. Dann lohnt sich Abwarten: solange du nichts entfernst, " +
        "bleiben deine bestehenden Wecker erhalten."

/** Der harmlose Weg - steht im Dialog an erster Stelle. */
internal const val ENTFERNEN_LEERT_AUSWAHL_ALTERNATIVE: String = "Anderen Kalender wählen"

internal const val ENTFERNEN_LEERT_AUSWAHL_BESTAETIGEN: String = "Trotzdem entfernen"

internal const val ENTFERNEN_LEERT_AUSWAHL_ABBRECHEN: String = "Abbrechen"

/**
 * Die Rueckfrage vor dem Entfernen des LETZTEN ausgewaehlten Kalenders.
 *
 * Der bestaetigende Knopf ist bewusst der unauffaellige (TextButton) und der harmlose Ausweg der
 * hervorgehobene: der Zustand, aus dem heraus getippt wird, sieht nach einem Defekt aus, ist aber
 * meistens voruebergehend.
 */
@Composable
private fun LetzteAuswahlEntfernenDialog(
    onAbbrechen: () -> Unit,
    onKalenderWaehlen: () -> Unit,
    onTrotzdemEntfernen: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onAbbrechen,
        title = { Text(ENTFERNEN_LEERT_AUSWAHL_TITEL) },
        text = { Text(ENTFERNEN_LEERT_AUSWAHL_TEXT) },
        confirmButton = {
            Button(onClick = onKalenderWaehlen) { Text(ENTFERNEN_LEERT_AUSWAHL_ALTERNATIVE) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(SpacingConstants.SPACING_SMALL)) {
                TextButton(onClick = onAbbrechen) { Text(ENTFERNEN_LEERT_AUSWAHL_ABBRECHEN) }
                TextButton(onClick = onTrotzdemEntfernen) {
                    Text(
                        ENTFERNEN_LEERT_AUSWAHL_BESTAETIGEN,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    )
}

/**
 * PURE, TESTBAR: formuliert den Teilerfolg fuer die Kalender-Karte.
 *
 * Zwei Dinge muessen darin stehen, sonst ist die Meldung wertlos:
 *  - WELCHER Kalender. "Ein Kalender ist nicht abrufbar" laesst sich nicht abwaehlen. Der Name
 *    kommt aus [namesById]; die Kalenderliste ist paginiert, der Eintrag kann also fehlen - dann
 *    bleibt nur die Anzahl, und das ist ehrlicher als ein geratener Name.
 *  - Die FOLGE. Der Zustand selbst sagt dem Nutzer nichts; dass deswegen keine neuen Wecker mehr
 *    entstehen, ist die eigentliche Nachricht. Und der Zusatz "bestehende bleiben" gehoert
 *    dazu - sonst liest sich die Karte, als seien die Wecker schon weg.
 */
internal fun unavailableCalendarDetails(
    unavailableIds: Set<String>,
    namesById: Map<String, String>
): String {
    val folge = " — solange werden keine neuen Wecker angelegt (bestehende bleiben). " +
        "Ist der Kalender dauerhaft weg, hier aus der Auswahl entfernen."

    val namen = unavailableIds.mapNotNull { namesById[it] }.sorted()

    val wer = when {
        namen.size == unavailableIds.size && namen.size == 1 ->
            "⚠️ \"${namen.first()}\" ist zurzeit nicht abrufbar"
        namen.size == unavailableIds.size ->
            "⚠️ ${unavailableIds.size} Kalender sind zurzeit nicht abrufbar (${namen.joinToString(", ") { "\"$it\"" }})"
        unavailableIds.size == 1 ->
            "⚠️ Ein ausgewählter Kalender ist zurzeit nicht abrufbar"
        else ->
            "⚠️ ${unavailableIds.size} ausgewählte Kalender sind zurzeit nicht abrufbar"
    }
    return wer + folge
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
                // dekorativ: `details` daneben benennt den Zustand bereits in Worten
                // (z. B. "Nicht angemeldet", "Kein Kalender ausgewählt")
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
                    // dekorativ: "Offline-Modus"/"Cache-Status" samt Erklaerzeile steht daneben
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
    // mutableLongStateOf statt mutableStateOf(0L): kein Autoboxing des Zeitstempels bei jedem
    // 30s-Tick (Delegat-Nutzung unveraendert).
    var lastMaintenanceTime by remember { mutableLongStateOf(0L) }

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
                // dekorativ: "Letzter Sync" und der Abstand in Worten stehen daneben, die
                // Warnung bei >24h zusaetzlich als eigener Text
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
                    // dekorativ: die Knopfbeschriftung daneben sagt es bereits
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
                    // dekorativ: die Knopfbeschriftung daneben sagt es bereits
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

    // Kein SDK_INT-Zweig mehr: minSdk ist 26, der frühere else-Zweig (deprecated
    // activeNetworkInfo, nur < API 23) war unerreichbar.
    val activeNetwork = connectivityManager.activeNetwork ?: return false
    val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false

    return networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
