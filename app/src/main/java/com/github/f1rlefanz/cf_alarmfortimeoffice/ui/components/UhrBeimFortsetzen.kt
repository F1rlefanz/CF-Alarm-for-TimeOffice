package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.Schlummern
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.schwebendesSchlummern
import kotlinx.coroutines.delay

/**
 * Die Uhrzeit des letzten `ON_RESUME` - als Compose-Zustand, damit Anzeigen, die mit der Uhr
 * rechnen, beim Zurueckkehren in die App neu rechnen.
 *
 * WARUM: Die Wecker-Anzeigen ("Nächster Wecker", die Tagesliste) rechnen nur bei einer Aenderung
 * ihrer Eingaben neu; einen Ticker gibt es nicht. Ein Wecker, der klingelt und beendet wird,
 * aendert den Bestand nicht (er steht bis zum naechsten Sync darin). Lebt der Prozess weiter,
 * kehrt der Nutzer um 07:00 in eine unveraenderte Komposition zurueck - und sah noch den
 * 05:30-Wecker als naechsten.
 *
 * Wer den Wert liest, wird bei jedem Fortsetzen neu komponiert; als `remember`-Schluessel erzwingt
 * er die Neuberechnung.
 */
@Composable
internal fun uhrBeimFortsetzen(): Long {
    val lifecycleOwner = LocalLifecycleOwner.current
    var zeitpunkt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    DisposableEffect(lifecycleOwner) {
        val beobachter = LifecycleEventObserver { _, ereignis ->
            if (ereignis == Lifecycle.Event.ON_RESUME) zeitpunkt = System.currentTimeMillis()
        }
        lifecycleOwner.lifecycle.addObserver(beobachter)
        onDispose { lifecycleOwner.lifecycle.removeObserver(beobachter) }
    }
    return zeitpunkt
}

/** Was die Wecker-Anzeigen zum Rechnen brauchen: die Uhr und die schwebenden Snoozes. */
internal data class WeckerStand(val jetzt: Long, val schlummern: List<Schlummern>)

/**
 * Uhr und schwebende Snoozes fuer "Nächster Wecker" und die Tagesliste - neu gelesen beim
 * Fortsetzen, bei jeder Aenderung von [alarmeSchluessel] und EINMAL kurz nach dem naechsten
 * Wechsel (Weckzeit [naechsterWecker] oder Ende eines Schlummerns).
 *
 * WARUM DER TICK: Bleibt die App im Vordergrund, waehrend der Wecker als Benachrichtigung klingelt
 * (kein Vollbild, kein ON_RESUME), zeigte "Nächster Wecker" danach weiter den gerade geklingelten
 * 05:30, und vom Schlummern stand nirgends etwas (Emulator, 14.10.2026). Bewusst kein
 * Dauer-Ticker: ein Wechsel, ein Tick, nur innerhalb eines Tages.
 *
 * Der Snooze-Merker liegt in Device-Protected SharedPreferences und hat keinen Beobachter; ein
 * Schlummern, das waehrend der sichtbaren App gedrueckt wird, erscheint erst beim naechsten Anlass
 * (Fortsetzen, Bestandsaenderung, Tick).
 */
@Composable
internal fun rememberWeckerStand(alarmeSchluessel: Any?, naechsterWecker: Long?): WeckerStand {
    val context = LocalContext.current
    var getickt by remember { mutableLongStateOf(0L) }
    val stand = maxOf(uhrBeimFortsetzen(), getickt)
    val schlummern = remember(stand, alarmeSchluessel) {
        schwebendesSchlummern(context, System.currentTimeMillis())
    }
    val jetzt = maxOf(stand, System.currentTimeMillis())
    val wechsel = listOfNotNull(
        naechsterWecker?.takeIf { it > jetzt },
        schlummern.minOfOrNull { it.bis }?.takeIf { it > jetzt }
    ).minOrNull()
    LaunchedEffect(wechsel, stand) {
        val ziel = wechsel ?: return@LaunchedEffect
        val warten = ziel - System.currentTimeMillis()
        if (warten in 1..TICK_HORIZONT_MS) {
            delay(warten + TICK_NACHLAUF_MS)
            getickt = System.currentTimeMillis()
        }
    }
    return WeckerStand(jetzt, schlummern.filter { it.bis > jetzt })
}

private const val TICK_HORIZONT_MS = 24 * 60 * 60 * 1000L

/** Kurz NACH dem Wechsel, damit `triggerTime > jetzt` sicher kippt. */
private const val TICK_NACHLAUF_MS = 1_000L
