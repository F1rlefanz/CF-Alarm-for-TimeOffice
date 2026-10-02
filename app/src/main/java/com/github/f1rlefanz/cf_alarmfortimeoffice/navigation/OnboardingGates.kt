package com.github.f1rlefanz.cf_alarmfortimeoffice.navigation

import com.github.f1rlefanz.cf_alarmfortimeoffice.util.BatteryOptimizationHelper

/*
 * Die Entscheidung, welches Onboarding-Gate als naechstes kommt - als REINE Funktion, ohne
 * Context, DataStore oder Compose.
 *
 * WARUM: Bis Issue #132 stand diese Entscheidung verteilt in `MainScreen` (der Unused-App-Check
 * dreimal, der TimeOffice-Check zweimal, die Kette "Unused, sonst proceedPastGates" zweimal fast
 * wortgleich) und in `NavigationViewModel.handleAuthenticationSuccess()` (fuenf Booleans).
 * Getestet war davon nur der automatische Weg; der Weg nach der Kalenderauswahl, nach der
 * Akku-Freigabe und nach den Einstellungsseiten hatte keinen einzigen Test. Hier liegt sie an
 * einer Stelle und ist ohne Geraet pruefbar (`OnboardingGatesTest`). Gelesen wird in der
 * UI-Schicht (`leseGateLage` in `MainScreen.kt`), navigiert ebenfalls dort bzw. im ViewModel.
 *
 * ACHTUNG, bewusst verhaltensgleich zum Stand vor dem Umbau - die Einstiege sind NICHT
 * symmetrisch, und das ist hier abgebildet, nicht korrigiert:
 *  - [GateEinstieg.AUTO] kennt kein OEM-Gate und endet in [GateSchritt.Nichts] (kein
 *    `scheduleNext()` an dieser Stelle; die Wartungskette stellt der Eintritt in `MainContent`).
 *  - [GateEinstieg.NACH_KALENDER] fragt NUR die Akku-Ausnahme, nicht das "Spaeter"-Flag - wer
 *    die Kalenderauswahl erneut abschliesst, bekommt das Akku-Gate erneut angeboten.
 *  - [GateEinstieg.NACH_EINSTELLUNGEN] entspricht dem frueheren `proceedPastGates()`: nur noch
 *    TimeOffice und OEM, danach fertig.
 * Wer eine dieser Asymmetrien aufhebt, aendert Verhalten (Issue #132, Schritt 2) und muss die
 * zugehoerigen Tests in `OnboardingGatesTest` bewusst umdrehen.
 */

/**
 * Was zum Zeitpunkt der Entscheidung ueber die Gates bekannt ist.
 *
 * Nicht jeder Einstieg liest jedes Feld (siehe `leseGateLage`): ein Feld, das der Einstieg nicht
 * befragt, steht auf seinem Neutralwert (`false` bzw. `null`) und wird von
 * [naechsterGateSchritt] fuer diesen Einstieg auch nicht ausgewertet.
 *
 * @property kalenderGewaehlt mindestens ein Kalender ausgewaehlt (nur [GateEinstieg.AUTO])
 * @property akkuAusnahme die App ist von der Akku-Optimierung ausgenommen
 * @property akkuAbgelehnt der Nutzer hat das Akku-Gate mit "Spaeter" verlassen (persistiert)
 * @property unusedNoetig "App bei Nichtnutzung pausieren" ist aktiv und nicht weggeklickt
 * @property timeOfficeNoetig TimeOffice ist installiert, nicht ausgenommen, Hinweis nicht weggeklickt
 * @property oemFaellig der Herstellertyp, dessen OEM-Warnscreen noch nie gezeigt wurde - `null`,
 *   wenn keiner faellig ist (Standardgeraet oder schon gezeigt)
 */
data class GateLage(
    val kalenderGewaehlt: Boolean = true,
    val akkuAusnahme: Boolean = false,
    val akkuAbgelehnt: Boolean = false,
    val unusedNoetig: Boolean = false,
    val timeOfficeNoetig: Boolean = false,
    val oemFaellig: BatteryOptimizationHelper.OEMType? = null
) {
    /**
     * "Spaeter" beim Akku-Gate heisst ERLEDIGT, nicht abgebrochen: die Kette geht weiter, sobald
     * die Ausnahme erteilt ODER vom Nutzer abgelehnt ist. Vorher verlangten die nachfolgenden
     * Zweige die Ausnahme selbst - wer "Spaeter" tippte, fiel aus JEDEM Zweig heraus, und der
     * Schritt "App bei Nichtnutzung pausieren" wurde ihm NIE angeboten (genau dieser Schalter hat
     * am 20.07.2026 die App force-gestoppt und alle Alarme geloescht).
     */
    val akkuGateErledigt: Boolean get() = akkuAusnahme || akkuAbgelehnt
}

/** Von wo aus die Gate-Kette (fort)gesetzt wird. */
enum class GateEinstieg {
    /** Automatisch bei jedem App-Vordergrund (Gate-Effekt in `MainScreen`). */
    AUTO,

    /** Kalenderauswahl mit "Fertig" verlassen. */
    NACH_KALENDER,

    /** Aus Androids Akku-Dialog zurueck, Ausnahme ERTEILT (sonst bleibt der Erklaerdialog). */
    NACH_AKKU,

    /** Aus einer Einstellungsseite zurueck (Unused-App bzw. TimeOffice) - frueher `proceedPastGates()`. */
    NACH_EINSTELLUNGEN
}

/** Der naechste Schritt der Gate-Kette. */
sealed interface GateSchritt {
    /**
     * Die drei Gates, die der Nutzer mit "Spaeter" (oder Zurueck) verlassen kann - und die dabei
     * ihr Dismissed-Flag schreiben MUESSEN. Eigener Untertyp, damit `ueberspringe()` in
     * `MainScreen` ueber genau diese drei erschoepfend verzweigt.
     */
    sealed interface Ueberspringbar : GateSchritt

    data object Kalender : GateSchritt
    data object Akku : Ueberspringbar
    data object Unused : Ueberspringbar
    data object TimeOffice : Ueberspringbar
    data class Oem(val typ: BatteryOptimizationHelper.OEMType) : GateSchritt

    /** Onboarding abgeschlossen: Wartungskette anstossen, dann Home. */
    data object Fertig : GateSchritt

    /** Nichts zu tun, der Nutzer bleibt, wo er ist (nur [GateEinstieg.AUTO]). */
    data object Nichts : GateSchritt
}

/**
 * Bildet [lage] und [einstieg] auf den naechsten Schritt ab. Reine Funktion - siehe den
 * Dateikopf fuer die bewusst erhaltenen Asymmetrien zwischen den Einstiegen.
 */
fun naechsterGateSchritt(lage: GateLage, einstieg: GateEinstieg): GateSchritt = when (einstieg) {
    GateEinstieg.AUTO -> when {
        !lage.kalenderGewaehlt -> GateSchritt.Kalender
        !lage.akkuGateErledigt -> GateSchritt.Akku
        lage.unusedNoetig -> GateSchritt.Unused
        lage.timeOfficeNoetig -> GateSchritt.TimeOffice
        else -> GateSchritt.Nichts
    }

    GateEinstieg.NACH_KALENDER ->
        if (!lage.akkuAusnahme) GateSchritt.Akku else nachErteilterAkkuAusnahme(lage)

    GateEinstieg.NACH_AKKU -> nachErteilterAkkuAusnahme(lage)

    GateEinstieg.NACH_EINSTELLUNGEN -> nachDenEinstellungen(lage)
}

private fun nachErteilterAkkuAusnahme(lage: GateLage): GateSchritt =
    if (lage.unusedNoetig) GateSchritt.Unused else nachDenEinstellungen(lage)

private fun nachDenEinstellungen(lage: GateLage): GateSchritt = when {
    lage.timeOfficeNoetig -> GateSchritt.TimeOffice
    lage.oemFaellig != null -> GateSchritt.Oem(lage.oemFaellig)
    else -> GateSchritt.Fertig
}
