package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Einmal-Signal: „Der Nutzer hat die Dimmer-Benachrichtigung angetippt, um den
 * Bedienungshilfen-Dienst zu aktivieren."
 *
 * WOZU: Die Benachrichtigung „Dimmt nicht — Bedienungshilfen-Dienst ist aus" (siehe
 * [DimCorrectionNotifier]) fuehrt bewusst NICHT direkt in die Android-Einstellungen, sondern in
 * die App — davor gehoert die Play-Pflicht-Offenlegung, die die Karte im Status-Tab zeigt. Damit
 * das ein Ausweg und keine Schnitzeljagd ist, muss die Oberflaeche erfahren, WESHALB die App
 * gerade geoeffnet wurde: nur so kann sie zur richtigen Karte rollen und die Offenlegung
 * anbieten. Ohne dieses Signal landet der Nutzer irgendwo im Status-Tab, waehrend die Karte, die
 * er sucht, unterhalb von sechs anderen steht.
 *
 * WARUM EIN PROZESSWEITES OBJEKT UND KEIN DURCHGEREICHTER PARAMETER: Die Karte liest den
 * Dienst-Zustand ohnehin direkt an der Quelle ([DimAccessibilityService.isRunning]) statt ihn
 * durch drei Composables zu schleusen, die mit dem Dimmer nichts zu tun haben; dieses Signal
 * nimmt denselben Weg. Ein ViewModel-Feld waere die Alternative gewesen — es haette
 * `MainScreen` und `MainContentScreen` je zwei Parameter mehr gegeben, ohne dass einer der
 * beiden davon etwas wuesste.
 *
 * EINMAL heisst einmal: [verbrauchen] wird von der ersten Stelle gerufen, die das Signal
 * auswertet (dem Status-Tab). Bleibt es stehen, weil der Nutzer den Status-Tab gar nicht
 * erreicht (Onboarding-Gate, Login), verfaellt es spaetestens mit dem Prozess — es gibt also
 * keinen Zustand, der sich hier ansammeln koennte.
 */
object DimBedienungshilfenWunsch {

    private val _offen = MutableStateFlow(false)

    /** `true`, solange ein Aktivierungswunsch auf seine Auswertung wartet. */
    val offen: StateFlow<Boolean> = _offen.asStateFlow()

    /** Aus dem Einstieg heraus gerufen (siehe `MainActivity.verarbeiteEinstieg`). */
    fun stellen() {
        _offen.value = true
    }

    /** Von der auswertenden Oberflaeche gerufen, bevor sie handelt. */
    fun verbrauchen() {
        _offen.value = false
    }
}
