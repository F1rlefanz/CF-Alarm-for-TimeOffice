package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.hue

/*
 * WERKZEUG-FALLE, teuer bezahlt am 19.08.2026: Die Aktions-Enums der aufrufenden Bildschirme
 * muessen `internal` sein, nicht `private`. Mit einem top-level `private enum` als Typargument
 * dieser generischen Funktion bricht `hiltJavaCompileDebugUnitTest` mit der irrefuehrenden
 * Meldung "[Hilt] @HiltAndroidApp base class must extend Application. Found:
 * Hilt_CFAlarmApplication" ab - die App selbst baut weiter, nur die Unit-Tests lassen sich nicht
 * mehr uebersetzen. Ein KSP-Fehler ("PSI has changed since creation") liegt darunter, kein
 * Fehler dieses Projekts. Eingegrenzt wurde es durch Halbieren des Diffs ueber acht saubere
 * Builds; `clean` und ein frischer Daemon halfen nicht.
 */
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** Die Berechtigung, ohne die ab Android 17 (API 37) kein Paket mehr ins lokale Netz darf. */
const val ACCESS_LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

/**
 * Ab dieser API-Ebene erzwingt Android die lokale Netzwerkberechtigung. Als Zahl und nicht als
 * `Build.VERSION_CODES`-Konstante, weil die Konstante erst mit einem neueren compileSdk existiert.
 */
const val SDK_MIT_LOKALER_NETZWERKFREIGABE = 37

/**
 * Reine Entscheidung: Muss vor dieser Aktion erst der Systemdialog kommen?
 *
 * Ausgelagert, damit sie ohne Geraet pruefbar ist - die Falle waere eine Abfrage, die auf
 * aelteren Ebenen faelschlich einen Dialog anstoesst (dort gibt es die Berechtigung nicht, der
 * Launcher liefert sofort "verweigert" und die Aktion faende nie statt).
 */
fun braucheLokaleNetzwerkFreigabe(sdkInt: Int, istErteilt: Boolean): Boolean =
    sdkInt >= SDK_MIT_LOKALER_NETZWERKFREIGABE && !istErteilt

/**
 * Die EINE Stelle, durch die jede Hue-NUTZERAKTION laeuft, die das lokale Netz braucht.
 *
 * WARUM ZENTRAL: Bis v1.28.0 kannte nur der Hue-Tab die Berechtigung, und auch dort nur fuer drei
 * seiner fuenf netzbeduerftigen Knoepfe. Alles Uebrige - Lampentest, Verbindungspruefung in den
 * Hue-Einstellungen, Regeltest je Regelkarte und im Regel-Editor - rief das ViewModel direkt an.
 * Folge: ab Android 17 scheitert die Aktion mit einer generischen Netzwerkmeldung, und der
 * Systemdialog, der das Problem loesen wuerde, erscheint NIE. Eine Pruefung je Aufrufstelle haette
 * denselben Fehler beim naechsten neuen Knopf wieder erlaubt; deshalb ein gemeinsames Tor.
 *
 * Die HINTERGRUNDPFADE (Weckzeit, Hue-Planer, Verbindungsaufbau beim App-Start) laufen bewusst
 * NICHT hier durch: Ohne Activity kann kein Dialog erscheinen.
 *
 * @param onMessage bekommt Nutzertexte (Verweigerung, verlorene Absicht) und zeigt sie an.
 * @param onAction fuehrt die Aktion aus - aufgerufen mit der gemerkten Absicht, entweder sofort
 *   oder nach erteilter Berechtigung. Kann die Absicht nicht mehr aufgeloest werden (z. B. eine
 *   inzwischen geloeschte Regel), meldet der Aufrufer das selbst; das Tor kennt die Fachlichkeit
 *   nicht.
 */
@Composable
fun <A : Enum<A>> rememberLocalNetworkPermissionGate(
    onMessage: (String) -> Unit,
    onAction: (action: A, arg: String?) -> Unit
): LocalNetworkPermissionGate<A> {
    val context = LocalContext.current

    // Die gemerkte Absicht als SPEICHERBARER Wert, nicht als Lambda: Waehrend der Systemdialog
    // offen steht, kann die Activity neu aufgebaut werden (Rotation - MainActivity hat weder
    // screenOrientation noch configChanges). Ein gemerktes Lambda ist danach weg, und "Zulassen"
    // fuehrte zu einem stillen No-op. Enum + optionales Id-Argument ueberleben in rememberSaveable.
    var pendingAction by rememberSaveable { mutableStateOf<A?>(null) }
    var pendingArg by rememberSaveable { mutableStateOf<String?>(null) }

    // rememberUpdatedState, damit die im Launcher gefangenen Lambdas nach einer Recomposition
    // nicht auf einen veralteten ViewModel-Zustand zeigen.
    val currentAction by rememberUpdatedState(onAction)
    val currentMessage by rememberUpdatedState(onMessage)

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val action = pendingAction
            if (action != null) {
                currentAction(action, pendingArg)
            } else {
                // Kein stiller No-op: Ohne gemerkte Absicht (z. B. Prozesstod waehrend des Dialogs)
                // muss der Nutzer erfahren, dass die Berechtigung da ist und er nur nochmal tippen
                // muss.
                currentMessage("Netzwerkzugriff erteilt. Bitte die gewünschte Aktion noch einmal antippen.")
            }
        } else {
            currentMessage("Lokaler Netzwerkzugriff verweigert. Bitte in den Android-Einstellungen erteilen.")
        }
        pendingAction = null
        pendingArg = null
    }

    return remember {
        LocalNetworkPermissionGate { action, arg ->
            val istErteilt = context.checkSelfPermission(ACCESS_LOCAL_NETWORK_PERMISSION) ==
                PackageManager.PERMISSION_GRANTED
            if (braucheLokaleNetzwerkFreigabe(Build.VERSION.SDK_INT, istErteilt)) {
                pendingAction = action
                pendingArg = arg
                launcher.launch(ACCESS_LOCAL_NETWORK_PERMISSION)
            } else {
                currentAction(action, arg)
            }
        }
    }
}

/**
 * Das von [rememberLocalNetworkPermissionGate] zurueckgegebene Tor. Aufruf per `gate(AKTION)` bzw.
 * `gate(AKTION, id)`.
 */
@Stable
class LocalNetworkPermissionGate<A : Enum<A>> internal constructor(
    private val starter: (A, String?) -> Unit
) {
    operator fun invoke(action: A, arg: String? = null) = starter(action, arg)
}
