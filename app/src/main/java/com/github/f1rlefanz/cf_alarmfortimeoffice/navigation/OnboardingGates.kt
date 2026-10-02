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
 * Die Kette hat EINE feste Reihenfolge: Kalender -> Akku -> Unused -> TimeOffice -> OEM. Jeder
 * Einstieg ausser AUTO setzt an einer Stelle dieser Reihenfolge an und schaut nur nach VORNE.
 *
 * Bis Issue #132 (Schritt 2) waren die Einstiege nicht symmetrisch, und jede Asymmetrie hat
 * einen Nutzer um einen Schritt gebracht:
 *  - "Spaeter"/Zurueck an einem Gate fuehrte nach Home statt zum naechsten offenen Gate. Der
 *    Gate-Effekt in `MainScreen` haengt nur an Anmeldung und Kalendern - beides aendert sich
 *    dadurch nicht, also kam das naechste Gate erst beim naechsten App-Start: hoechstens EIN Gate
 *    pro Start. Heute setzen die [GateEinstieg]-Werte `SPAETER_*` die Kette sofort fort.
 *  - Den OEM-Hinweis gab es nur auf den aktiven Wegen. Wer ein Gate mit "Spaeter" verliess oder
 *    die Gates schon vor dem OEM-Hinweis durchlaufen hatte, sah ihn NIE. Heute fragt jeder
 *    Einstieg den OEM-Merker, auch [GateEinstieg.AUTO].
 *  - Nach der Kalenderauswahl zaehlte nur die Akku-Ausnahme, nicht das "Spaeter"-Flag: wer die
 *    Auswahl erneut abschloss, bekam das Akku-Gate erneut. Heute gilt auch dort
 *    [GateLage.akkuGateErledigt].
 *
 * KEINE SCHLEIFE: ein `SPAETER_*`-Einstieg schaut nur auf die Gates HINTER dem uebersprungenen.
 * Das haengt bewusst NICHT daran, dass das eben geschriebene Dismissed-Flag beim Neulesen schon
 * sichtbar ist - ein degradierter Read (leer statt Fehler) liesse dasselbe Gate sonst sofort
 * wieder erscheinen. Das Flag muss trotzdem vorher abgewartet geschrieben sein: der automatische
 * Weg liest es beim naechsten Vordergrund.
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
     * am 20.07.2026 die App force-gestoppt und alle Alarme geloescht). Gilt fuer JEDEN Einstieg,
     * der das Akku-Gate befragt (AUTO und NACH_KALENDER).
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
    NACH_EINSTELLUNGEN,

    /** Akku-Gate mit "Spaeter"/Zurueck verlassen (Flag geschrieben): weiter ab Unused. */
    SPAETER_AKKU,

    /** Unused-App-Gate mit "Spaeter"/Zurueck verlassen: weiter ab TimeOffice. */
    SPAETER_UNUSED,

    /** TimeOffice-Gate mit "Spaeter"/Zurueck verlassen: weiter mit dem OEM-Hinweis. */
    SPAETER_TIMEOFFICE
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

/** Der Einstieg, mit dem die Kette nach "Spaeter"/Zurueck an [gate] weitergeht. */
fun einstiegNachSpaeter(gate: GateSchritt.Ueberspringbar): GateEinstieg = when (gate) {
    GateSchritt.Akku -> GateEinstieg.SPAETER_AKKU
    GateSchritt.Unused -> GateEinstieg.SPAETER_UNUSED
    GateSchritt.TimeOffice -> GateEinstieg.SPAETER_TIMEOFFICE
}

/**
 * Zeigt [state] gerade [gate]? Damit setzt `ueberspringe()` in `MainScreen` die Kette nur fort,
 * solange der Nutzer noch auf dem uebersprungenen Gate steht: ein zweiter Tipp auf "Spaeter"
 * (oder Zurueck), waehrend das Flag noch geschrieben wird, darf den Nutzer nicht aus dem schon
 * erreichten NAECHSTEN Gate wieder herausnavigieren.
 */
fun GateSchritt.Ueberspringbar.wirdAngezeigtIn(state: NavigationState): Boolean = when (this) {
    GateSchritt.Akku -> state is NavigationState.BatteryExemption
    GateSchritt.Unused -> state is NavigationState.UnusedAppRestrictions
    GateSchritt.TimeOffice -> state is NavigationState.TimeOfficeHealthCheck
}

/**
 * Bildet [lage] und [einstieg] auf den naechsten Schritt ab. Reine Funktion - siehe den
 * Dateikopf fuer die Reihenfolge und warum ein `SPAETER_*`-Einstieg nie zurueckschaut.
 */
fun naechsterGateSchritt(lage: GateLage, einstieg: GateEinstieg): GateSchritt = when (einstieg) {
    GateEinstieg.AUTO -> when {
        !lage.kalenderGewaehlt -> GateSchritt.Kalender
        !lage.akkuGateErledigt -> GateSchritt.Akku
        // Kein Fertig auf dem automatischen Weg: die Wartungskette stellt der Eintritt in
        // MainContent, und ohne offenes Gate bleibt der Nutzer, wo er ist.
        else -> abUnused(lage).takeUnless { it == GateSchritt.Fertig } ?: GateSchritt.Nichts
    }

    GateEinstieg.NACH_KALENDER ->
        if (!lage.akkuGateErledigt) GateSchritt.Akku else abUnused(lage)

    GateEinstieg.NACH_AKKU, GateEinstieg.SPAETER_AKKU -> abUnused(lage)

    GateEinstieg.NACH_EINSTELLUNGEN, GateEinstieg.SPAETER_UNUSED -> abTimeOffice(lage)

    GateEinstieg.SPAETER_TIMEOFFICE -> abOem(lage)
}

private fun abUnused(lage: GateLage): GateSchritt =
    if (lage.unusedNoetig) GateSchritt.Unused else abTimeOffice(lage)

private fun abTimeOffice(lage: GateLage): GateSchritt =
    if (lage.timeOfficeNoetig) GateSchritt.TimeOffice else abOem(lage)

private fun abOem(lage: GateLage): GateSchritt =
    lage.oemFaellig?.let { GateSchritt.Oem(it) } ?: GateSchritt.Fertig
