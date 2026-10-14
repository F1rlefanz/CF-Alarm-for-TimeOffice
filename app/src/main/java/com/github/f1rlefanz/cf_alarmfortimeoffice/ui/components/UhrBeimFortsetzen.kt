package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

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
