package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmMaintenanceEntryPoint
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmMaintenanceService
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmManagerService
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.SettingsLinkButton
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme.success
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.BatteryOptimizationHelper
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.DndPermissionHelper
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.WeckbildschirmVerdraengungPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.NotificationDeliverability
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.TimeOfficeHealthHelper
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.UnusedAppRestrictionsHelper
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.theme.SpacingConstants
import dagger.hilt.android.EntryPointAccessors

/**
 * Die BERECHTIGUNGS- UND ZUSTANDSKARTEN des Status-Tabs.
 *
 * Herausgeloest aus `StatusTabContent.kt` (1389 Zeilen), weil sie eine geschlossene Gruppe
 * bilden: jede Karte liest genau EINE Geraeteeinstellung, zeigt gruen/rot und fuehrt bei Bedarf
 * in die zustaendige Systemeinstellung. Sie teilen kein Zustandsobjekt mit dem Rest des Tabs
 * (kein ViewModel-Parameter), und sie lesen ihren Zustand alle nach demselben Muster bei jedem
 * `ON_RESUME` neu - der Nutzer kann ihn ausserhalb der App aendern.
 *
 * Bewusst KEINE Verhaltensaenderung: dieselben Funktionen, dieselbe Reihenfolge im Tab,
 * dieselbe Sichtbarkeit (`internal`, damit `StatusTabContent` sie weiterhin aufrufen kann -
 * Kotlin kennt kein package-private).
 *
 * WARUM DIESE KARTEN UEBERHAUPT EXISTIEREN: jede von ihnen deckt eine Einstellung ab, die in
 * diesem Projekt nachweislich schon einmal einen Wecker verschluckt hat (Akku-Optimierung,
 * "Pause bei Nichtnutzung", blockierte Benachrichtigungen, entzogene
 * Vollbild-Berechtigung) oder eine Abhaengigkeit ausserhalb der App betrifft (TimeOffice,
 * Dimmer-Dienst, Nicht-stoeren-Zugriff). Sie sind Diagnose fuer den Nutzer, nicht Deko.
 */
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
 * DIE KARTE PRUEFT NICHT NUR DIE APP-EBENE: Android bietet dem Nutzer direkt aus einer
 * Benachrichtigung heraus einen Zwei-Tipp-Weg, den EINZELNEN Wecker-Kanal abzuschalten oder auf
 * "Lautlos" herunterzustufen. Der Wecker-Kanal braucht aber IMPORTANCE_HIGH, sonst ignoriert das
 * System den Full-Screen-Intent (siehe `AlarmSoundService.createNotificationChannel`). Bis v1.26.3
 * fragte diese Karte nur `areNotificationsEnabled()` - das bleibt in beiden Faellen `true`, und die
 * Karte meldete weiter "Erlaubt", waehrend der Weck-Bildschirm nicht mehr kam.
 *
 * Wie die Karten daneben wird der Zustand bei jedem ON_RESUME frisch gelesen - der Nutzer kann ihn
 * ausserhalb der App aendern, und danach muss die Karte stimmen.
 */
@Composable
internal fun NotificationsEnabledCard() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var zustand by remember { mutableStateOf(weckerZustellbarkeit(context)) }
    val enabled = zustand.erreicht
    // Wortgleich mit dem, was in den Systemeinstellungen steht - der Text darf keine Bezeichnung
    // erfinden, die der Nutzer dort nicht findet.
    val weckerKanalName = stringResource(R.string.alarm_channel_name)
    // Die Reparaturanweisung wird ABGELEITET, nicht hingeschrieben - und sie nennt eine WIRKUNG
    // statt eines Stufennamens: bis v1.29.0 stand hier fest "Standard oder hoeher", danach kurz
    // ein aus einer eigenen Tabelle geholtes "Hoch". Beides schickte den Nutzer auf
    // IMPORTANCE_DEFAULT - in der deutschen Liste von Android 8/9 heisst "Hoch" genau dieser Wert,
    // den dieselbe Karte als zu niedrig verwirft, und auf neueren Versionen gibt es den Eintrag
    // gar nicht. Wer der Anweisung folgte, sah unveraendert das Warndreieck und wurde weiter ohne
    // Weck-Bildschirm geweckt.
    val geforderteStufe = NotificationDeliverability.mindeststufeBeschreibung(
        NotificationDeliverability.WICHTIGKEIT_HOCH
    )

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                zustand = weckerZustellbarkeit(context)
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
                // dekorativ: der Text daneben sagt den Zustand ausdruecklich ("Erlaubt — …" bzw.
                // "⚠️ Blockiert — …"), das Icon spiegelt ihn nur
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
                    // Jeder Fall benennt AUSDRUECKLICH, was abgeschaltet ist - "blockiert" allein
                    // schickt den Nutzer in die falschen Einstellungen, wenn nur der eine Kanal
                    // betroffen ist.
                    when (zustand) {
                        NotificationDeliverability.Zustellbarkeit.ERREICHBAR ->
                            "Erlaubt — Weck-Bildschirm und Wecker-Knöpfe können erscheinen"

                        NotificationDeliverability.Zustellbarkeit.APP_BLOCKIERT ->
                            "⚠️ Blockiert — der Wecker klingelt dann zwar, aber ohne Weck-Bildschirm " +
                                "und ohne Knöpfe zum Stoppen oder Schlummern"

                        NotificationDeliverability.Zustellbarkeit.KANAL_BLOCKIERT ->
                            "⚠️ Die Kategorie \"$weckerKanalName\" ist abgeschaltet — der Wecker " +
                                "klingelt dann zwar, aber ohne Weck-Bildschirm und ohne Knöpfe zum " +
                                "Stoppen oder Schlummern. Sie muss wieder eingeschaltet werden."

                        NotificationDeliverability.Zustellbarkeit.GRUPPE_BLOCKIERT ->
                            "⚠️ Die Gruppe, in der \"$weckerKanalName\" liegt, ist abgeschaltet — " +
                                "der Wecker klingelt dann zwar, aber ohne Weck-Bildschirm und ohne " +
                                "Knöpfe zum Stoppen oder Schlummern."

                        NotificationDeliverability.Zustellbarkeit.KANAL_LEISE ->
                            "⚠️ Die Kategorie \"$weckerKanalName\" steht zu niedrig — dann kommt " +
                                "der Weck-Bildschirm nicht mehr von selbst hoch. Sie muss " +
                                "$geforderteStufe."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )

                if (!enabled) {
                    Spacer(Modifier.height(SpacingConstants.SPACING_SMALL))
                    SettingsLinkButton(
                        // Bei einem Kanal-Problem direkt in dessen Einstellungen: die App-Ebene
                        // ist dort in Ordnung, und der Nutzer muesste sich sonst selbst durch die
                        // Kategorienliste suchen. Bei APP_BLOCKIERT/GRUPPE_BLOCKIERT bleibt es bei
                        // der App-Uebersicht - dort liegen beide Schalter.
                        onClick = {
                            when (zustand) {
                                NotificationDeliverability.Zustellbarkeit.KANAL_BLOCKIERT,
                                NotificationDeliverability.Zustellbarkeit.KANAL_LEISE ->
                                    openChannelNotificationSettings(
                                        context,
                                        NotificationDeliverability.WECKER_KANAL_ID
                                    )

                                else -> openAppNotificationSettings(context)
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
 * Meldet, ob die App eine Vollbild-Benachrichtigung zeigen darf — und fuehrt bei Bedarf direkt
 * in die zustaendige Systemeinstellung.
 *
 * Warum eine eigene Karte statt einer Zeile in den Empfehlungen: Seit Android 14 entzieht der
 * Play Store USE_FULL_SCREEN_INTENT nach der Installation allen Apps, die er nicht als Wecker-
 * oder Telefonie-App einstuft. Ohne die Berechtigung degradiert Android den Full-Screen-Intent
 * still zu einem Banner — der Wecker klingelt, aber der Weck-Screen kommt nie hoch, und nichts
 * weist darauf hin. Ein reiner Hinweistext ohne Absprung waere hier wertlos.
 *
 * Der Zustand wird bei jedem ON_RESUME neu gelesen (`remember` + `DisposableEffect`, siehe unten),
 * damit die Karte nach der Rueckkehr aus den Einstellungen sofort umspringt. NICHT "kein
 * remember": der Code benutzt eines. Der frueher hier stehende Satz verleitete dazu, den
 * ON_RESUME-Refresh als redundant zu entfernen ("liest doch bei jedem Aufruf neu") - danach fror
 * die Karte auf ihrem Startwert ein und behauptete eine Berechtigung, die es nicht mehr gibt.
 */
@Composable
internal fun FullScreenIntentCard() {
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
                // dekorativ: der Text daneben sagt den Zustand ausdruecklich
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
 * Sagt dem Nutzer, dass der Weckbildschirm beim Klingeln verdraengt wird - und was er dagegen tun
 * kann.
 *
 * WARUM ES DIESE KARTE GIBT: Auf dem Fairphone 6 (Android 16) startet die herstellereigene
 * Gesichtsentsperrung als gewoehnliche Activity und draengt den Weckbildschirm hinter den
 * Sperrbildschirm. Der Wecker klingelt weiter, laesst sich aber ohne Entsperren weder stoppen noch
 * schlummern. Am 29.08.2026 mit der vorinstallierten Google Uhr gegengeprueft - es trifft jede
 * Wecker-App auf diesem Geraet.
 *
 * WARUM ES DEN HINWEIS NEBEN DER ABHILFE GIBT: Vier Messlaeufe haben belegt, dass sich der
 * Full-Screen-Intent nicht NACHREICHEN laesst (weder ueber eine zweite Notification noch als
 * Update, auch nicht ohne Verzoegerung). Der Satz "app-seitig ist nichts zu gewinnen", der hier
 * stand, war daraus zu weit verallgemeinert: er galt fuers Nachreichen, nicht fuer den ZEITPUNKT
 * des ersten Postens. Genau dort setzt das Vorwecken an (seit 1.39.3, ohne Geraete-Unterscheidung
 * seit 1.39.5). Der Hinweis bleibt daneben bestehen - er meldet, wenn es TROTZ Vorwecken
 * passiert, und ist die einzige Stelle, an der der Nutzer davon erfaehrt.
 *
 * WARUM KEIN KNOPF ZUR EINSTELLUNG: Es gibt keine. Die Gesichtsentsperrung des FP6 kennt nur
 * "einlernen" und "loeschen" - ein Schalter existiert nicht (im Fairphone-Forum unabhaengig
 * bestaetigt). Ein Knopf, der ins Nichts fuehrt, waere schlimmer als keiner.
 *
 * Die Karte erscheint erst nach [WeckbildschirmVerdraengungPrefs.SCHWELLE] Weckvorgaengen in Folge
 * und verschwindet von allein, sobald wieder einer sauber durchlaeuft.
 */
@Composable
internal fun WeckbildschirmVerdraengtCard() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Bei jedem ON_RESUME neu lesen: der Zaehler aendert sich waehrend eines Weckvorgangs,
    // also waehrend diese Karte nicht sichtbar ist.
    var faellig by remember { mutableStateOf(WeckbildschirmVerdraengungPrefs.hinweisFaellig(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                faellig = WeckbildschirmVerdraengungPrefs.hinweisFaellig(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (!faellig) return

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
                imageVector = Icons.Default.Error,
                // dekorativ: der Text daneben sagt den Zustand ausdruecklich
                contentDescription = null,
                modifier = Modifier.size(SpacingConstants.ICON_SIZE_LARGE),
                tint = MaterialTheme.colorScheme.error
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Weck-Bildschirm wird verdrängt",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Beim Klingeln erschien der Weck-Bildschirm kurz und verschwand wieder. " +
                        "Der Wecker lief weiter — zum Stoppen oder Schlummern musstest du die " +
                        "Benachrichtigung aufklappen oder das Gerät entsperren.\n\n" +
                        "Ursache ist die Gesichtsentsperrung deines Geräts: sie legt sich über " +
                        "den Weck-Bildschirm. Das betrifft jede Wecker-App, auch die " +
                        "vorinstallierte Uhr — es liegt nicht an dieser App.\n\n" +
                        "Diese App steuert dagegen: sie weckt den Bildschirm kurz vorher selbst, " +
                        "damit der Weck-Bildschirm oben bleibt. Das greift bei jedem Wecker, auch " +
                        "beim ersten nach einem Neustart.\n\n" +
                        "Wenn es weiter passiert, hilft nur: das eingelernte Gesicht in den " +
                        "Geräte-Einstellungen unter „Entsperrung per Gesichtserkennung“ " +
                        "löschen. Der Fingerabdruck ist nicht betroffen.\n\n" +
                        "Dieser Hinweis verschwindet von selbst, sobald wieder ein Wecker " +
                        "normal durchläuft.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

/**
 * Was die Exact-Alarm-Karte anzeigen soll.
 *
 * [AUSGEBLENDET] heisst: die Berechtigung ist da UND kann auf dieser Android-Version gar nicht
 * fehlen — eine dauerhaft gruene Karte waere dort reines Rauschen.
 */
internal enum class ExaktAlarmKartenZustand { AUSGEBLENDET, ERTEILT, ENTZOGEN }

/**
 * Reine Entscheidung, damit sie ohne Android pruefbar ist.
 *
 * WELCHER ABLAUF GING KAPUTT: Auf API 31/32 haengt das Stellen exakter Alarme an
 * SCHEDULE_EXACT_ALARM ("Alarme & Erinnerungen"), und der Nutzer - oder ein
 * Hersteller-"Akku-Assistent" - darf das jederzeit abschalten. Das System loescht in dem Moment
 * ALLE exakt gestellten Alarme (setAlarmClock, setExact, setExactAndAllowWhileIdle): jeden
 * Schicht-Wecker, jeden schwebenden
 * Snooze und den einen Alarm, an dem die 6h-Wartung haengt. Einen Broadcast gibt es dabei nicht.
 * Bis zu dieser Runde war das der einzige weckerkritische Zustand ohne Karte und ohne
 * Onboarding-Gate: die App zeigte weiter ihre Alarmliste aus dem Repository und sah gesund aus.
 *
 * DIE REIHENFOLGE DER ZWEIGE IST TRAGEND: "fehlt" wird auf JEDER API-Stufe gezeigt, nicht nur
 * auf 31/32. Ab API 33 traegt die App USE_EXACT_ALARM, das bei Installation erteilt und nicht
 * entziehbar ist - aber genau dasselbe galt fuer USE_FULL_SCREEN_INTENT, bis der Play Store es
 * ab Android 14 nachtraeglich wieder entzog. Eine fehlende Berechtigung wegzublenden, weil sie
 * "nicht fehlen kann", ist die Annahme, die diese App schon einmal einen Weck-Bildschirm gekostet
 * hat.
 */
internal fun exaktAlarmKartenZustand(
    sdkInt: Int,
    darfExakteAlarme: Boolean
): ExaktAlarmKartenZustand = when {
    !darfExakteAlarme -> ExaktAlarmKartenZustand.ENTZOGEN
    // Nur dort, wo der Zustand kippen KANN, ist er dauerhaft ablesbar (gleiche Haltung wie bei
    // der Akku-Karte darunter).
    sdkInt >= Build.VERSION_CODES.S && sdkInt < Build.VERSION_CODES.TIRAMISU ->
        ExaktAlarmKartenZustand.ERTEILT
    else -> ExaktAlarmKartenZustand.AUSGEBLENDET
}

/**
 * Muss die Wartungskette neu angestossen werden?
 *
 * Der Entzug loescht den Alarm, an dem die Kette haengt; die Wieder-Erteilung stellt ihn NICHT
 * wieder her, und Androids Erteilungs-Broadcast wird beim Entzug ausdruecklich nicht gesendet.
 * Diese Karte ist damit einer der wenigen Orte, die den Uebergang "war weg, ist wieder da"
 * ueberhaupt sehen - und sie sieht ihn im Vordergrund, wo ein Vordergrunddienst starten darf.
 *
 * Bewusst NUR beim Uebergang: bei jedem ON_RESUME einen Wartungslauf zu starten waere ein
 * Dauer-Anstoss ohne Anlass.
 */
internal fun brauchtWiederanlaufNachErteilung(
    vorher: ExaktAlarmKartenZustand,
    nachher: ExaktAlarmKartenZustand
): Boolean = vorher == ExaktAlarmKartenZustand.ENTZOGEN && nachher != ExaktAlarmKartenZustand.ENTZOGEN

/**
 * Laeuft gerade eine Master-Pause?
 *
 * Der Wiederanlauf stellt Wecker HER - waehrend einer Pause waere das genau der Zustand, den
 * `pause()` beseitigt hat. Der `BootReceiver` gated seine Wiederherstellung (inklusive
 * `restorePendingSnoozes()`) aus demselben Grund und ueber denselben Spiegel: `MasterPausePrefs`
 * liegt im CE-Storage und ist nur suspendierend lesbar, der Device-Protected-Spiegel liefert
 * denselben Zustand synchron. Bei einem Lesefehler meldet er "nicht pausiert" - im Zweifel wecken.
 */
private fun masterPauseAktiv(context: Context): Boolean = try {
    EntryPointAccessors
        .fromApplication(context.applicationContext, AlarmMaintenanceEntryPoint::class.java)
        .directBootAlarmStore()
        .isPausedNow()
} catch (e: Exception) {
    Logger.w(LogTags.MAINTENANCE, "Pausen-Spiegel nicht lesbar - Wiederanlauf laeuft trotzdem", e)
    false
}

private fun darfExakteAlarme(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
    } else {
        true
    }

/**
 * Meldet, ob die App exakte Alarme stellen darf — siehe [exaktAlarmKartenZustand] fuer den
 * Ablauf, der ohne diese Karte unsichtbar blieb.
 *
 * Wie die Karten daneben wird der Zustand bei jedem ON_RESUME frisch gelesen. Der Uebergang
 * "wieder erteilt" stellt zusaetzlich her, was der Entzug geloescht hat, und zwar genau in dem
 * Umfang, den der Kartentext verspricht: einen ERZWUNGENEN Wartungslauf (nur der erreicht
 * `syncAlarms()` und armiert die Schicht-Wecker erneut) und `restorePendingSnoozes()` fuer einen
 * schwebenden Schlummer. Ein manueller Wecker laesst sich nirgends rekonstruieren - er steht
 * deshalb im Text als Aufgabe des Nutzers.
 */
@Composable
internal fun ExactAlarmPermissionCard() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var zustand by remember {
        mutableStateOf(exaktAlarmKartenZustand(Build.VERSION.SDK_INT, darfExakteAlarme(context)))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val neu = exaktAlarmKartenZustand(
                    Build.VERSION.SDK_INT,
                    darfExakteAlarme(context)
                )
                if (brauchtWiederanlaufNachErteilung(zustand, neu) && !masterPauseAktiv(context)) {
                    Logger.w(
                        LogTags.MAINTENANCE,
                        "⚠️ Exact-Alarm-Berechtigung wieder erteilt - Wartungskette und " +
                            "schwebende Schlummer werden wiederhergestellt (der Entzug hatte " +
                            "beide geloescht)"
                    )
                    // forceSync = true ist hier PFLICHT, nicht Geschmack: der Entzug loescht nur
                    // die AlarmManager-Eintraege, der Repository-Bestand bleibt stehen. Das
                    // regulaere Lade-Gate (MaintenanceLoadDecision.shouldLoadEvents) saehe also
                    // Zukunftsalarme, frische Daten und einen ausreichenden Puffer - es
                    // uebersprang das Laden, der Lauf endete VOR syncAlarms(), und genau
                    // syncAlarms() ist der einzige Ort, der Bestandsalarme wieder armiert. Die
                    // beiden Schwesterstellen mit demselben Zweck (BootReceiver,
                    // TimezoneChangeReceiver) uebergeben aus demselben Grund true.
                    AlarmMaintenanceService.start(context, forceSync = true)

                    // Der schwebende Schlummer haengt an einem EIGENEN Merker und an keinem
                    // Kalender-Event; syncAlarms() kennt ihn nicht. Ohne diesen Aufruf kam er
                    // ueberhaupt nur durch einen Geraeteneustart zurueck (einziger Aufrufer war
                    // der BootReceiver) - der Kartentext haette also einen Wecker versprochen,
                    // den es nicht gibt. Bewusst synchron: die Funktion liest einen kleinen
                    // Device-Protected-Merker und stellt hoechstens eine Handvoll Alarme; ein
                    // ausgelagerter Aufruf haenge am Lebenszyklus dieser Karte und koennte
                    // mitten in der Wiederherstellung abgebrochen werden.
                    val schlummer = AlarmManagerService.restorePendingSnoozes(context)
                    if (schlummer > 0) {
                        Logger.w(
                            LogTags.MAINTENANCE,
                            "😴 $schlummer schwebende(r) Schlummer nach Wieder-Erteilung wiederhergestellt"
                        )
                    }
                }
                zustand = neu
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (zustand == ExaktAlarmKartenZustand.AUSGEBLENDET) return

    val erteilt = zustand == ExaktAlarmKartenZustand.ERTEILT

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
                imageVector = if (erteilt) Icons.Default.CheckCircle else Icons.Default.Error,
                // dekorativ: der Text daneben sagt den Zustand ausdruecklich
                contentDescription = null,
                modifier = Modifier.size(SpacingConstants.ICON_SIZE_LARGE),
                tint = if (erteilt) {
                    MaterialTheme.colorScheme.success
                } else {
                    MaterialTheme.colorScheme.error
                }
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Alarme & Erinnerungen",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (erteilt) {
                        "Wecker werden auf die Minute genau gestellt"
                    } else {
                        // Der Text nennt genau das, was die Wieder-Erteilung wirklich ausloest —
                        // Schicht-Wecker ueber einen erzwungenen Wartungslauf, den Schlummer
                        // ueber restorePendingSnoozes(). Ein MANUELL angelegter Wecker wird
                        // nirgends nachgestellt (syncAlarms schont ihn nur), deshalb steht er
                        // ausdruecklich als Aufgabe des Nutzers da: eine Anzeige, die einen
                        // Wecker ankuendigt, den es nicht gibt, ist die gefaehrlichste Variante.
                        "⚠️ Android hat beim Abschalten ALLE gestellten Wecker geloescht — auch " +
                            "einen laufenden Schlummer und die Hintergrund-Wartung. Erlaube die " +
                            "Berechtigung wieder: Schicht-Wecker und Schlummer holt die App dann " +
                            "umgehend zurück (für die Schicht-Wecker braucht sie kurz Netz). " +
                            "Einen manuell angelegten Wecker musst du selbst neu stellen."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )

                if (!erteilt) {
                    Spacer(Modifier.height(SpacingConstants.SPACING_SMALL))
                    SettingsLinkButton(
                        onClick = { oeffneExactAlarmEinstellung(context) },
                        text = "Einstellung öffnen"
                    )
                }
            }
        }
    }
}

/**
 * Fuehrt in die zustaendige Systemeinstellung.
 *
 * Der Sonderbildschirm ACTION_REQUEST_SCHEDULE_EXACT_ALARM existiert erst ab API 31; faellt er
 * aus (manche Hersteller-ROMs kennen ihn nicht), bleibt der App-Detailbildschirm als Weg. Ein
 * Knopf, der ins Leere fuehrt, waere schlimmer als keiner - deshalb beide Stufen in try/catch.
 */
private fun oeffneExactAlarmEinstellung(context: Context) {
    val ziele = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    "package:${context.packageName}".toUri()
                )
            )
        }
        add(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                "package:${context.packageName}".toUri()
            )
        )
    }

    for (intent in ziele) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: Exception) {
            Logger.w(LogTags.MAINTENANCE, "Einstellungsbildschirm nicht erreichbar: ${intent.action}", e)
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
internal fun BatteryOptimizationCard() {
    // ZWEI Karten aus diesem Aufruf, und das ist kein Versehen: die Exact-Alarm-Berechtigung ist
    // der Zwilling der Akku-Ausnahme, nicht ein beliebiger Nachbar. Auf API 31/32 ersetzt die
    // Akku-Ausnahme die Berechtigung sogar ("unless the app is exempt from battery restrictions",
    // AlarmManager-Doku), und AlarmManagerService.checkAlarmPermissions() bewertet beide zusammen
    // zu EINEM AlarmPermissionLevel. Sie gehoeren nebeneinander. Die Exact-Alarm-Karte rendert
    // ausserdem nichts, wo die Berechtigung strukturell nie fehlen kann - dort entsteht also
    // nicht einmal ein Abstand.
    ExactAlarmPermissionCard()

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
                // dekorativ: der Text daneben sagt den Zustand ausdruecklich
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
internal fun UnusedAppRestrictionsCard() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isOk by remember { mutableStateOf(true) }
    // mutableIntStateOf statt mutableStateOf(0): kein Autoboxing des Zaehlers (Delegat-Nutzung
    // unveraendert - `refreshTrigger++` und der LaunchedEffect-Key bleiben, wie sie sind).
    var refreshTrigger by remember { mutableIntStateOf(0) }

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
                // dekorativ: der Text daneben sagt den Zustand ausdruecklich
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
internal fun TimeOfficeHealthCard() {
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
                    // dekorativ: "Akku-Optimierung: ausgenommen" bzw. "⚠️ … eingeschränkt — …"
                    // steht direkt daneben
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
                    // dekorativ: Hinweis-Icon zum Erklaertext daneben, kein eigener Zustand
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
                        context.startActivity(TimeOfficeHealthHelper.createAppInfoIntent())
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
 *
 * @param aktivierungsAnfragen zaehlt die Einstiege aus der Dimmer-Benachrichtigung „Dimmt nicht —
 *   Bedienungshilfen-Dienst ist aus" (siehe `StatusTabContent`). Jeder Anstieg oeffnet die
 *   Offenlegung, ohne dass die Karte einen Verbrauch zurueckmelden muesste. Laeuft der Dienst
 *   inzwischen doch, passiert nichts — die Meldung hat sich dann von selbst erledigt.
 */
@Composable
internal fun DimmerAccessibilityCard(
    aktivierungsAnfragen: Int,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isActive by remember { mutableStateOf(DimAccessibilityService.isRunning()) }
    // rememberSaveable: eine Drehung darf die Offenlegung nicht wegnehmen — sie ist der einzige
    // Weg zum Aktivieren, und ein erneuter Einstieg aus der Benachrichtigung kommt nach der
    // Drehung bewusst nicht noch einmal (siehe MainActivity.onCreate).
    var showDisclosure by rememberSaveable { mutableStateOf(false) }

    // Der Nutzer hat die Benachrichtigung angetippt, um zu aktivieren - dann steht hier die
    // Pflicht-Offenlegung, und der Knopf dahinter fuehrt direkt auf die Seite dieses Dienstes.
    LaunchedEffect(aktivierungsAnfragen) {
        if (aktivierungsAnfragen > 0 && !DimAccessibilityService.isRunning()) {
            showDisclosure = true
        }
    }

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
        modifier = modifier.fillMaxWidth(),
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
                // dekorativ: "Bedienungshilfen-Dienst aktiv/nicht aktiv — …" steht daneben
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
                    // KEIN "Aktiver Fehler", wenn der Nutzer den Dimmer nie eingeschaltet hat -
                    // dann ist der Dienst schlicht nicht gebraucht. Der Status-Tab ist die
                    // Diagnosflaeche fuer "warum kam kein Wecker"; eine dauerhaft rote Karte, die
                    // einen Fehler behauptet, der nicht eingetreten ist, entwertet genau die roten
                    // Karten daneben, an denen der Wecker wirklich haengt.
                    Text(
                        "Wird gebraucht, sobald du den Schicht-Dimmer benutzt",
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
internal fun DndPermissionCard() {
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
                // dekorativ: der Statustext daneben (dnd_status_ok / dnd_status_missing /
                // dnd_unsupported) sagt den Zustand ausdruecklich
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

/**
 * Fuehrt in die Bedienungshilfen-Liste. Mehr ist von einer normalen App aus nicht erreichbar -
 * und das ist gemessen, nicht vermutet.
 *
 * WARUM NICHT AUF DIE DETAILSEITE DIESES DIENSTES:
 * `android.settings.ACCESSIBILITY_DETAILS_SETTINGS` (ab Android 11) fuehrt zwar dorthin, ist fuer
 * diese App aber dauerhaft unerreichbar - nicht nur auf manchen Geraeten. Die Ziel-Activity
 * `Settings$AccessibilityDetailsSettingsActivity` traegt in AOSP seit Android 11, also seit es die
 * Aktion ueberhaupt gibt, `android:permission="android.permission.OPEN_ACCESSIBILITY_DETAILS_SETTINGS"`;
 * die Berechtigung steht auf `signature|installer` und ist dort ausdruecklich als „Not for use by
 * third-party applications" (`@hide`) gekennzeichnet. Eine nicht plattformsignierte App kann sie
 * NIE halten. Am 05.09.2026 an BEIDEN Geraeten gemessen (Fairphone 6 / Android 16 und Emulator /
 * API 36): die Aktion loest sauber auf die Settings-Activity auf und wird dann mit
 * „Permission Denial ... requires OPEN_ACCESSIBILITY_DETAILS_SETTINGS" abgewiesen.
 * **Wer den Direktsprung wieder einbaut, baut einen Zweig, der garantiert immer nur seinen
 * Rueckfall erreicht - und dabei bei JEDEM Tipp eine sinnlose Zeile ins Release-Log schreibt.**
 *
 * WARUM AUCH KEIN HERVORHEBEN DES EINTRAGS: der uebliche Kniff dafuer ist
 * `:settings:fragment_args_key` (in AOSP `SettingsActivity.EXTRA_FRAGMENT_ARG_KEY`) mit der flach
 * geschriebenen Kennung des Dienstes. Aus der Shell gestartet wirkt er auch - der Eintrag steht
 * dann markiert unter „Downloaded apps", nachgemessen im A/B und ueber 20 s stabil. **Aus DIESER
 * App heraus wirkt er nicht**, und daran lag es an nichts, was sich am Intent aendern liesse: mit
 * und ohne Argument-Buendel, mit und ohne `FLAG_ACTIVITY_NEW_TASK`, mit frisch geleerter
 * Einstellungen-App - immer ohne Hervorhebung, waehrend `dumpsys activity activities` fuer beide
 * Wege denselben Intent zeigt (`act=...ACCESSIBILITY_SETTINGS flg=0x10000000 xflg=0x4`, „has
 * extras"). Der Unterschied ist der Aufrufer selbst; AOSP liest den Schluessel primaer aus den
 * Fragment-Argumenten und nur hinter einem Feature-Flag aus dem Intent
 * (`SettingsPreferenceFragment.onCreateAdapter`).
 *
 * Deshalb steht hier der schlichte Aufruf ohne Extra: ein Zusatz, der im einzigen Kontext, in dem
 * er laeuft, nachweislich nichts bewirkt, ist kein Sicherheitsnetz, sondern Ballast mit einer
 * Erklaerung daneben, die etwas verspricht. Wer es erneut versucht, misst zuerst - und zwar aus
 * der App, nicht aus der Shell.
 */
private fun openAccessibilitySettings(context: android.content.Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        )
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
 * Zustellbarkeit der WECKER-Benachrichtigung: App-Ebene, Kanal und Kanalgruppe in einem Urteil.
 *
 * [NotificationDeliverability.WICHTIGKEIT_HOCH] als Mindestmass ist kein Schoenheitswunsch: das
 * System ignoriert den Full-Screen-Intent, wenn der Kanal darunter liegt (siehe
 * `AlarmSoundService.createNotificationChannel`) - der Wecker klingelt dann ohne Weck-Bildschirm.
 */
private fun weckerZustellbarkeit(context: android.content.Context): NotificationDeliverability.Zustellbarkeit =
    NotificationDeliverability.bestimme(
        context = context,
        kanalId = NotificationDeliverability.WECKER_KANAL_ID,
        mindestwichtigkeit = NotificationDeliverability.WICHTIGKEIT_HOCH
    )

/**
 * Fuehrt direkt in die Einstellungen EINES Kanals. Nur sinnvoll, wenn die App-Ebene in Ordnung
 * ist - sonst zeigt Android die Kanalseite ausgegraut. Scheitert der Absprung (OEM ohne diese
 * Activity), bleibt die App-Uebersicht der Rueckfallweg.
 */
private fun openChannelNotificationSettings(context: android.content.Context, channelId: String) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
        )
    } catch (e: Exception) {
        Logger.e(LogTags.UI, "❌ Kanal-Einstellungen nicht erreichbar, fallback auf App-Ebene", e)
        openAppNotificationSettings(context)
    }
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
