package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import com.github.f1rlefanz.cf_alarmfortimeoffice.navigation.MainTab
import com.github.f1rlefanz.cf_alarmfortimeoffice.navigation.NavigationAction
import com.github.f1rlefanz.cf_alarmfortimeoffice.navigation.NavigationState
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.BatteryOptimizationHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NavigationViewModel hat keine injizierten Dependencies (kein Context/DataStore) - reine,
 * direkt testbare State-Machine-Logik. Deckt zwei Bereiche ab:
 *  - handleNavigationAction(): jede NavigationAction erzeugt den erwarteten NavigationState,
 *    inkl. NavigateBackToMain, das den returnToTab aus dem jeweiligen Ausgangszustand liest.
 *  - handleAuthenticationSuccess(): die vier Gate-Zweige sind als if/else-if verkettet - nur
 *    EINER darf pro Aufruf feuern (Reihenfolge: CalendarSelection -> BatteryExemption ->
 *    UnusedAppRestrictions -> TimeOfficeHealthCheck), und nur solange der aktuelle Zustand
 *    MainContent ist (kein Wegnavigieren aus einem bereits offenen Screen).
 */
class NavigationViewModelTest {

    private fun newViewModel() = NavigationViewModel()

    // ---- handleNavigationAction --------------------------------------------------------

    @Test
    fun `NavigateToCalendarSelection setzt CalendarSelection mit korrektem returnToTab`() {
        val vm = newViewModel()
        vm.handleNavigationAction(NavigationAction.NavigateToCalendarSelection(MainTab.STATUS))
        assertEquals(NavigationState.CalendarSelection(MainTab.STATUS), vm.navigationState.value)
    }

    @Test
    fun `NavigateToShiftConfig setzt ShiftConfig`() {
        val vm = newViewModel()
        vm.handleNavigationAction(NavigationAction.NavigateToShiftConfig(MainTab.WECKER))
        assertEquals(NavigationState.ShiftConfig(MainTab.WECKER), vm.navigationState.value)
    }

    @Test
    fun `NavigateToTimeOfficeHealthCheck setzt TimeOfficeHealthCheck`() {
        val vm = newViewModel()
        vm.handleNavigationAction(NavigationAction.NavigateToTimeOfficeHealthCheck(MainTab.HOME))
        assertEquals(NavigationState.TimeOfficeHealthCheck(MainTab.HOME), vm.navigationState.value)
    }

    @Test
    fun `NavigateToOEMWarning setzt OEMWarning mit oemType und returnToTab`() {
        val vm = newViewModel()
        vm.handleNavigationAction(
            NavigationAction.NavigateToOEMWarning(BatteryOptimizationHelper.OEMType.XIAOMI, MainTab.SETTINGS)
        )
        assertEquals(
            NavigationState.OEMWarning(BatteryOptimizationHelper.OEMType.XIAOMI, MainTab.SETTINGS),
            vm.navigationState.value
        )
    }

    @Test
    fun `NavigateBackToMain aus TimeOfficeHealthCheck kehrt zum gespeicherten returnToTab zurueck`() {
        val vm = newViewModel()
        vm.handleNavigationAction(NavigationAction.NavigateToTimeOfficeHealthCheck(MainTab.STATUS))
        vm.handleNavigationAction(NavigationAction.NavigateBackToMain)
        assertEquals(NavigationState.MainContent(MainTab.STATUS), vm.navigationState.value)
    }

    @Test
    fun `NavigateBackToMain ohne bekannten returnToTab faellt auf HOME zurueck`() {
        val vm = newViewModel()
        // MainContent selbst hat keinen returnToTab-Zweig im when -> else-Fallback greift.
        vm.handleNavigationAction(NavigationAction.NavigateBackToMain)
        assertEquals(NavigationState.MainContent(MainTab.HOME), vm.navigationState.value)
    }

    @Test
    fun `ChangeTab wechselt den Tab wenn im MainContent`() {
        val vm = newViewModel()
        vm.handleNavigationAction(NavigationAction.ChangeTab(MainTab.HUE))
        assertEquals(NavigationState.MainContent(MainTab.HUE), vm.navigationState.value)
    }

    @Test
    fun `ChangeTab wird ignoriert wenn nicht im MainContent`() {
        val vm = newViewModel()
        vm.handleNavigationAction(NavigationAction.NavigateToShiftConfig(MainTab.WECKER))
        vm.handleNavigationAction(NavigationAction.ChangeTab(MainTab.HUE))
        // Bleibt im ShiftConfig-Screen, der Tab-Wechsel-Versuch aendert nichts.
        assertEquals(NavigationState.ShiftConfig(MainTab.WECKER), vm.navigationState.value)
    }

    @Test
    fun `dismissBatteryPrompt navigiert direkt nach Home`() {
        val vm = newViewModel()
        vm.handleNavigationAction(NavigationAction.NavigateToBatteryExemption(MainTab.HOME))
        vm.dismissBatteryPrompt()
        assertEquals(NavigationState.MainContent(MainTab.HOME), vm.navigationState.value)
    }

    // ---- handleAuthenticationSuccess: Gate-Reihenfolge & Exklusivitaet -------------------

    @Test
    fun `keine Kalender ausgewaehlt fuehrt zu CalendarSelection`() {
        val vm = newViewModel()
        vm.handleAuthenticationSuccess(
            hasSelectedCalendars = false,
            hasBatteryExemption = false,
            batteryPromptDismissed = false,
            needsUnusedAppRestrictionsPrompt = false,
            needsTimeOfficeHealthPrompt = false
        )
        assertEquals(NavigationState.CalendarSelection(MainTab.HOME), vm.navigationState.value)
    }

    @Test
    fun `Kalender vorhanden aber keine Akku-Ausnahme und nicht dismissed fuehrt zu BatteryExemption`() {
        val vm = newViewModel()
        vm.handleAuthenticationSuccess(
            hasSelectedCalendars = true,
            hasBatteryExemption = false,
            batteryPromptDismissed = false,
            needsUnusedAppRestrictionsPrompt = false,
            needsTimeOfficeHealthPrompt = false
        )
        assertEquals(NavigationState.BatteryExemption(MainTab.HOME), vm.navigationState.value)
    }

    @Test
    fun `Akku-Prompt dismissed unterdrueckt BatteryExemption-Zweig`() {
        val vm = newViewModel()
        vm.handleAuthenticationSuccess(
            hasSelectedCalendars = true,
            hasBatteryExemption = false,
            batteryPromptDismissed = true,
            needsUnusedAppRestrictionsPrompt = false,
            needsTimeOfficeHealthPrompt = false
        )
        // Kein Zweig trifft, weil hier auch die beiden nachfolgenden Gates nichts wollen ->
        // Zustand bleibt unveraendert MainContent(HOME).
        assertEquals(NavigationState.MainContent(MainTab.HOME), vm.navigationState.value)
    }

    @Test
    fun `Akku-Prompt dismissed OHNE Ausnahme laesst die nachfolgenden Gates trotzdem feuern`() {
        val vm = newViewModel()
        vm.handleAuthenticationSuccess(
            hasSelectedCalendars = true,
            hasBatteryExemption = false,   // Ausnahme NICHT erteilt
            batteryPromptDismissed = true, // ...aber mit "Spaeter" erledigt
            needsUnusedAppRestrictionsPrompt = true,
            needsTimeOfficeHealthPrompt = false
        )
        // REGRESSION: Zweig 3 und 4 verlangten frueher beide hasBatteryExemption. Wer "Spaeter"
        // tippte, fiel damit aus JEDEM Zweig heraus (Zweig 2 durch das Dismissed-Flag, Zweig 3/4
        // durch die fehlende Ausnahme) - der Schritt "App bei Nichtnutzung pausieren" wurde ihm
        // NIE angeboten, obwohl genau dieser Android-Schalter die App am 20.07.2026 nachweislich
        // force-gestoppt und dabei alle AlarmManager-Alarme geloescht hat. Das Akku-Gate
        // abzulehnen ist eine Aussage ueber die Akku-Ausnahme, keine ueber die unabhaengigen
        // Gates dahinter.
        assertEquals(NavigationState.UnusedAppRestrictions(MainTab.HOME), vm.navigationState.value)
    }

    @Test
    fun `Akku-Prompt dismissed OHNE Ausnahme laesst auch das TimeOffice-Gate feuern`() {
        val vm = newViewModel()
        vm.handleAuthenticationSuccess(
            hasSelectedCalendars = true,
            hasBatteryExemption = false,
            batteryPromptDismissed = true,
            needsUnusedAppRestrictionsPrompt = false,
            needsTimeOfficeHealthPrompt = true
        )
        assertEquals(NavigationState.TimeOfficeHealthCheck(MainTab.HOME), vm.navigationState.value)
    }

    @Test
    fun `offenes Akku-Gate hat weiterhin Vorrang vor den nachfolgenden Gates`() {
        val vm = newViewModel()
        vm.handleAuthenticationSuccess(
            hasSelectedCalendars = true,
            hasBatteryExemption = false,
            batteryPromptDismissed = false, // noch NICHT erledigt
            needsUnusedAppRestrictionsPrompt = true,
            needsTimeOfficeHealthPrompt = true
        )
        // Die Kette bleibt eine Kette: solange das Akku-Gate offen ist, kommt es zuerst.
        assertEquals(NavigationState.BatteryExemption(MainTab.HOME), vm.navigationState.value)
    }

    @Test
    fun `Batterie ok aber Unused-App-Restrictions noetig fuehrt zu UnusedAppRestrictions`() {
        val vm = newViewModel()
        vm.handleAuthenticationSuccess(
            hasSelectedCalendars = true,
            hasBatteryExemption = true,
            batteryPromptDismissed = false,
            needsUnusedAppRestrictionsPrompt = true,
            needsTimeOfficeHealthPrompt = true
        )
        // UnusedAppRestrictions-Zweig kommt VOR dem TimeOffice-Zweig - obwohl beide Flags true
        // sind, darf nur der erste feuern.
        assertEquals(NavigationState.UnusedAppRestrictions(MainTab.HOME), vm.navigationState.value)
    }

    @Test
    fun `alle frueheren Gates erledigt und TimeOffice-Check noetig fuehrt zu TimeOfficeHealthCheck`() {
        val vm = newViewModel()
        vm.handleAuthenticationSuccess(
            hasSelectedCalendars = true,
            hasBatteryExemption = true,
            batteryPromptDismissed = false,
            needsUnusedAppRestrictionsPrompt = false,
            needsTimeOfficeHealthPrompt = true
        )
        assertEquals(NavigationState.TimeOfficeHealthCheck(MainTab.HOME), vm.navigationState.value)
    }

    @Test
    fun `Bestandsnutzer der frueheren Gates schon vor dem Feature durchlaufen hat erreicht TimeOfficeHealthCheck`() {
        // Regression fuer den gemeldeten Bug: ein Nutzer, der Battery+UnusedAppRestrictions
        // schon lange erledigt hat, muss trotzdem ueber handleAuthenticationSuccess() (nicht nur
        // proceedPastGates()) den TimeOffice-Check erreichen.
        val vm = newViewModel()
        vm.handleAuthenticationSuccess(
            hasSelectedCalendars = true,
            hasBatteryExemption = true,
            batteryPromptDismissed = true,
            needsUnusedAppRestrictionsPrompt = false,
            needsTimeOfficeHealthPrompt = true
        )
        assertEquals(NavigationState.TimeOfficeHealthCheck(MainTab.HOME), vm.navigationState.value)
    }

    @Test
    fun `alle Gates erledigt fuehrt zu keiner Auto-Navigation`() {
        val vm = newViewModel()
        vm.handleAuthenticationSuccess(
            hasSelectedCalendars = true,
            hasBatteryExemption = true,
            batteryPromptDismissed = false,
            needsUnusedAppRestrictionsPrompt = false,
            needsTimeOfficeHealthPrompt = false
        )
        assertEquals(NavigationState.MainContent(MainTab.HOME), vm.navigationState.value)
    }

    @Test
    fun `Auto-Navigation greift nicht wenn bereits in einem Unterscreen`() {
        val vm = newViewModel()
        vm.handleNavigationAction(NavigationAction.NavigateToShiftConfig(MainTab.WECKER))
        vm.handleAuthenticationSuccess(
            hasSelectedCalendars = true,
            hasBatteryExemption = true,
            batteryPromptDismissed = false,
            needsUnusedAppRestrictionsPrompt = false,
            needsTimeOfficeHealthPrompt = true
        )
        // isMainContent()-Guard in jedem Zweig verhindert das Wegnavigieren aus einem bereits
        // offenen Screen (z.B. waehrend der Nutzer manuell im ShiftConfig ist).
        assertEquals(NavigationState.ShiftConfig(MainTab.WECKER), vm.navigationState.value)
    }

    @Test
    fun `TimeOffice-Gate feuert nicht, solange das Akku-Gate noch offen ist`() {
        val vm = newViewModel()
        // Diese Pruefung hielt frueher das GEGENTEIL fest ("loest nichts aus" bei
        // batteryPromptDismissed = true) und schrieb damit einen Fehler als gewollt fest. Ihre
        // Begruendung war nachweislich falsch: MainScreen berechnete needsTimeOfficeHealthPrompt
        // nie mit einer Akku-Bedingung, lieferte genau diese Kombination also sehr wohl - und
        // dann fiel der Nutzer aus jedem Zweig heraus. Richtig ist die Kette: nur ein OFFENES
        // Akku-Gate hat Vorrang, ein mit "Spaeter" erledigtes nicht (siehe die Tests oben).
        vm.handleAuthenticationSuccess(
            hasSelectedCalendars = true,
            hasBatteryExemption = false,
            batteryPromptDismissed = false,
            needsUnusedAppRestrictionsPrompt = false,
            needsTimeOfficeHealthPrompt = true
        )
        assertEquals(NavigationState.BatteryExemption(MainTab.HOME), vm.navigationState.value)
    }

    @Test
    fun `navigationState startet als MainContent HOME`() {
        val vm = newViewModel()
        assertTrue(vm.navigationState.value is NavigationState.MainContent)
        assertEquals(MainTab.HOME, (vm.navigationState.value as NavigationState.MainContent).selectedTab)
    }
}
