package com.github.f1rlefanz.cf_alarmfortimeoffice.navigation

import com.github.f1rlefanz.cf_alarmfortimeoffice.util.BatteryOptimizationHelper.OEMType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Die Gate-Entscheidung als reine Funktion ([naechsterGateSchritt]). Vor Issue #132 war davon
 * nur der automatische Weg getestet - der Weg nach der Kalenderauswahl, nach der Akku-Freigabe
 * und nach den Einstellungsseiten (frueher `proceedPastGates()` in `MainScreen`) hatte keinen
 * einzigen Test.
 *
 * Schritt 1 des Umbaus hielt das alte Verhalten samt seiner Asymmetrien fest; Schritt 2
 * (Issue #132) hat drei davon bewusst aufgehoben, und die zugehoerigen Tests sind hier
 * umgedreht:
 *  - "Spaeter"/Zurueck setzt die Kette sofort fort (`SPAETER_*`), statt nach Home zu fuehren;
 *  - der OEM-Hinweis kommt auch auf dem automatischen Weg;
 *  - nach der Kalenderauswahl zaehlt "Spaeter" beim Akku-Gate als ERLEDIGT.
 * Dazu die Zusicherung, dass ein uebersprungenes Gate in derselben Kette NIE wiederkommt - auch
 * dann nicht, wenn sein Dismissed-Flag beim Neulesen (noch) nicht sichtbar ist.
 */
class OnboardingGatesTest {

    private val erledigtPerAusnahme = GateLage(akkuAusnahme = true)
    private val erledigtPerSpaeter = GateLage(akkuAusnahme = false, akkuAbgelehnt = true)
    private val akkuOffen = GateLage(akkuAusnahme = false, akkuAbgelehnt = false)

    /** Alle Kombinationen der sechs Eingangsgroessen (OEM: keiner faellig oder XIAOMI). */
    private val alleLagen: List<GateLage> = buildList {
        val bools = listOf(false, true)
        for (kalender in bools) for (ausnahme in bools) for (abgelehnt in bools)
            for (unused in bools) for (timeOffice in bools) for (oem in listOf(null, OEMType.XIAOMI)) {
                add(GateLage(kalender, ausnahme, abgelehnt, unused, timeOffice, oem))
            }
    }

    private fun auto(lage: GateLage) = naechsterGateSchritt(lage, GateEinstieg.AUTO)

    private val ueberspringbare = listOf(GateSchritt.Akku, GateSchritt.Unused, GateSchritt.TimeOffice)

    /** Position in der festen Kettenreihenfolge Kalender -> Akku -> Unused -> TimeOffice -> OEM. */
    private fun rang(schritt: GateSchritt): Int = when (schritt) {
        GateSchritt.Kalender -> 0
        GateSchritt.Akku -> 1
        GateSchritt.Unused -> 2
        GateSchritt.TimeOffice -> 3
        is GateSchritt.Oem -> 4
        GateSchritt.Fertig, GateSchritt.Nichts -> 5
    }

    // ---- AUTO: automatischer Weg bei jedem App-Vordergrund ------------------------------

    @Test
    fun `AUTO ohne Kalender fuehrt zuerst zur Kalenderauswahl`() {
        val lage = GateLage(
            kalenderGewaehlt = false, akkuAusnahme = false, unusedNoetig = true,
            timeOfficeNoetig = true, oemFaellig = OEMType.XIAOMI
        )
        assertEquals(GateSchritt.Kalender, auto(lage))
    }

    @Test
    fun `AUTO mit offenem Akku-Gate fuehrt zum Akku-Gate, auch wenn spaetere Gates warten`() {
        assertEquals(
            GateSchritt.Akku,
            auto(akkuOffen.copy(unusedNoetig = true, timeOfficeNoetig = true))
        )
    }

    @Test
    fun `AUTO - Spaeter beim Akku-Gate heisst ERLEDIGT, die Kette geht zu Unused weiter`() {
        // REGRESSION: frueher verlangten die nachfolgenden Zweige die Ausnahme selbst. Wer
        // "Spaeter" tippte, fiel aus jedem Zweig heraus und bekam "App bei Nichtnutzung
        // pausieren" NIE angeboten - genau der Schalter, der am 20.07.2026 alle Alarme geloescht hat.
        assertEquals(GateSchritt.Unused, auto(erledigtPerSpaeter.copy(unusedNoetig = true)))
    }

    @Test
    fun `AUTO - Spaeter beim Akku-Gate heisst ERLEDIGT, auch fuer das TimeOffice-Gate`() {
        assertEquals(GateSchritt.TimeOffice, auto(erledigtPerSpaeter.copy(timeOfficeNoetig = true)))
    }

    @Test
    fun `AUTO - Spaeter und erteilte Ausnahme fuehren in JEDER Lage zum selben Schritt`() {
        for (lage in alleLagen) {
            assertEquals(
                "Lage $lage",
                auto(lage.copy(akkuAusnahme = true, akkuAbgelehnt = false)),
                auto(lage.copy(akkuAusnahme = false, akkuAbgelehnt = true))
            )
        }
    }

    @Test
    fun `AUTO - Unused kommt vor TimeOffice`() {
        assertEquals(
            GateSchritt.Unused,
            auto(erledigtPerAusnahme.copy(unusedNoetig = true, timeOfficeNoetig = true))
        )
    }

    @Test
    fun `AUTO mit erledigtem Akku-Gate und nur TimeOffice offen fuehrt zu TimeOffice`() {
        assertEquals(GateSchritt.TimeOffice, auto(erledigtPerAusnahme.copy(timeOfficeNoetig = true)))
    }

    @Test
    fun `AUTO mit allem erledigt tut nichts`() {
        assertEquals(GateSchritt.Nichts, auto(erledigtPerAusnahme))
        assertEquals(GateSchritt.Nichts, auto(erledigtPerSpaeter))
    }

    @Test
    fun `AUTO zeigt einen faelligen OEM-Hinweis, sobald kein Gate davor offen ist`() {
        // UMGEDREHT in Issue #132, Schritt 2: bis dahin gab es den OEM-Screen nur auf den aktiven
        // Wegen, und wer "Spaeter" waehlte, sah ihn auf dem automatischen Weg nie.
        assertEquals(GateSchritt.Oem(OEMType.XIAOMI), auto(erledigtPerAusnahme.copy(oemFaellig = OEMType.XIAOMI)))
        assertEquals(GateSchritt.Oem(OEMType.HUAWEI), auto(erledigtPerSpaeter.copy(oemFaellig = OEMType.HUAWEI)))
    }

    @Test
    fun `AUTO - OEM kommt erst nach Akku, Unused und TimeOffice`() {
        val lage = erledigtPerSpaeter.copy(oemFaellig = OEMType.SAMSUNG)
        assertEquals(GateSchritt.Unused, auto(lage.copy(unusedNoetig = true, timeOfficeNoetig = true)))
        assertEquals(GateSchritt.TimeOffice, auto(lage.copy(timeOfficeNoetig = true)))
        assertEquals(GateSchritt.Akku, auto(akkuOffen.copy(oemFaellig = OEMType.SAMSUNG)))
    }

    @Test
    fun `AUTO liefert nie Fertig`() {
        // Die Wartungskette stellt der Eintritt in MainContent; ohne offenes Gate bleibt der
        // Nutzer, wo er ist.
        for (lage in alleLagen) {
            val schritt = auto(lage)
            assertFalse("Lage $lage -> $schritt", schritt == GateSchritt.Fertig)
        }
    }

    @Test
    fun `AUTO mit erledigtem Akku-Gate entspricht NACH_AKKU, nur Fertig heisst dort Nichts`() {
        for (lage in alleLagen.filter { it.kalenderGewaehlt && it.akkuGateErledigt }) {
            val aktiv = naechsterGateSchritt(lage, GateEinstieg.NACH_AKKU)
            val erwartet = if (aktiv == GateSchritt.Fertig) GateSchritt.Nichts else aktiv
            assertEquals("Lage $lage", erwartet, auto(lage))
        }
    }

    // ---- NACH_KALENDER: Kalenderauswahl mit "Fertig" verlassen --------------------------

    @Test
    fun `NACH_KALENDER ohne Akku-Ausnahme fuehrt zum Akku-Gate`() {
        assertEquals(GateSchritt.Akku, naechsterGateSchritt(akkuOffen, GateEinstieg.NACH_KALENDER))
    }

    @Test
    fun `NACH_KALENDER - Spaeter beim Akku-Gate heisst ERLEDIGT, das Akku-Gate kommt nicht erneut`() {
        // UMGEDREHT in Issue #132, Schritt 2: bis dahin fragte dieser Einstieg nur die Ausnahme,
        // und wer die Kalenderauswahl erneut abschloss, bekam das Akku-Gate trotz "Spaeter"
        // erneut angeboten.
        val e = GateEinstieg.NACH_KALENDER
        assertEquals(GateSchritt.Fertig, naechsterGateSchritt(erledigtPerSpaeter, e))
        assertEquals(GateSchritt.Unused, naechsterGateSchritt(erledigtPerSpaeter.copy(unusedNoetig = true), e))
        assertEquals(GateSchritt.TimeOffice, naechsterGateSchritt(erledigtPerSpaeter.copy(timeOfficeNoetig = true), e))
        assertEquals(GateSchritt.Oem(OEMType.OPPO), naechsterGateSchritt(erledigtPerSpaeter.copy(oemFaellig = OEMType.OPPO), e))
    }

    @Test
    fun `NACH_KALENDER - Spaeter und erteilte Ausnahme fuehren in JEDER Lage zum selben Schritt`() {
        val e = GateEinstieg.NACH_KALENDER
        for (lage in alleLagen) {
            assertEquals(
                "Lage $lage",
                naechsterGateSchritt(lage.copy(akkuAusnahme = true, akkuAbgelehnt = false), e),
                naechsterGateSchritt(lage.copy(akkuAusnahme = false, akkuAbgelehnt = true), e)
            )
        }
    }

    @Test
    fun `NACH_KALENDER mit Ausnahme geht die Kette weiter - Unused, TimeOffice, OEM, Fertig`() {
        val e = GateEinstieg.NACH_KALENDER
        assertEquals(GateSchritt.Unused, naechsterGateSchritt(erledigtPerAusnahme.copy(unusedNoetig = true, timeOfficeNoetig = true), e))
        assertEquals(GateSchritt.TimeOffice, naechsterGateSchritt(erledigtPerAusnahme.copy(timeOfficeNoetig = true, oemFaellig = OEMType.SAMSUNG), e))
        assertEquals(GateSchritt.Oem(OEMType.SAMSUNG), naechsterGateSchritt(erledigtPerAusnahme.copy(oemFaellig = OEMType.SAMSUNG), e))
        assertEquals(GateSchritt.Fertig, naechsterGateSchritt(erledigtPerAusnahme, e))
    }

    @Test
    fun `NACH_KALENDER mit Ausnahme entspricht in jeder Lage NACH_AKKU`() {
        for (lage in alleLagen.filter { it.akkuAusnahme }) {
            assertEquals(
                "Lage $lage",
                naechsterGateSchritt(lage, GateEinstieg.NACH_AKKU),
                naechsterGateSchritt(lage, GateEinstieg.NACH_KALENDER)
            )
        }
    }

    @Test
    fun `NACH_KALENDER fragt nicht nach dem Kalender`() {
        // Der Nutzer kommt gerade aus der Kalenderauswahl; dorthin zurueck waere eine Schleife.
        for (lage in alleLagen) {
            val schritt = naechsterGateSchritt(lage, GateEinstieg.NACH_KALENDER)
            assertFalse("Lage $lage -> $schritt", schritt == GateSchritt.Kalender)
            assertFalse("Lage $lage -> $schritt", schritt == GateSchritt.Nichts)
        }
    }

    // ---- NACH_AKKU: aus Androids Akku-Dialog zurueck, Ausnahme erteilt ------------------

    @Test
    fun `NACH_AKKU - Unused, TimeOffice, OEM, Fertig in dieser Reihenfolge`() {
        val e = GateEinstieg.NACH_AKKU
        val lage = erledigtPerAusnahme
        assertEquals(GateSchritt.Unused, naechsterGateSchritt(lage.copy(unusedNoetig = true, timeOfficeNoetig = true, oemFaellig = OEMType.ONEPLUS), e))
        assertEquals(GateSchritt.TimeOffice, naechsterGateSchritt(lage.copy(timeOfficeNoetig = true, oemFaellig = OEMType.ONEPLUS), e))
        assertEquals(GateSchritt.Oem(OEMType.ONEPLUS), naechsterGateSchritt(lage.copy(oemFaellig = OEMType.ONEPLUS), e))
        assertEquals(GateSchritt.Fertig, naechsterGateSchritt(lage, e))
    }

    @Test
    fun `NACH_AKKU wertet die Akku-Felder nicht aus`() {
        // Der Aufrufer kommt nur mit erteilter Ausnahme hierher (sonst bleibt der Erklaerdialog).
        for (lage in alleLagen) {
            assertEquals(
                "Lage $lage",
                naechsterGateSchritt(lage.copy(akkuAusnahme = true), GateEinstieg.NACH_AKKU),
                naechsterGateSchritt(lage.copy(akkuAusnahme = false, akkuAbgelehnt = false), GateEinstieg.NACH_AKKU)
            )
        }
    }

    // ---- NACH_EINSTELLUNGEN: frueher proceedPastGates() ---------------------------------

    @Test
    fun `NACH_EINSTELLUNGEN - TimeOffice vor OEM vor Fertig`() {
        val e = GateEinstieg.NACH_EINSTELLUNGEN
        assertEquals(GateSchritt.TimeOffice, naechsterGateSchritt(GateLage(timeOfficeNoetig = true, oemFaellig = OEMType.VIVO), e))
        assertEquals(GateSchritt.Oem(OEMType.VIVO), naechsterGateSchritt(GateLage(oemFaellig = OEMType.VIVO), e))
        assertEquals(GateSchritt.Fertig, naechsterGateSchritt(GateLage(), e))
    }

    @Test
    fun `NACH_EINSTELLUNGEN fragt weder Akku noch Unused`() {
        // proceedPastGates() pruefte nur TimeOffice und OEM - auch wer aus der Unused-Seite
        // zurueckkommt, ohne den Schalter umzulegen, wird nicht erneut dorthin geschickt.
        for (lage in alleLagen) {
            assertEquals(
                "Lage $lage",
                naechsterGateSchritt(GateLage(timeOfficeNoetig = lage.timeOfficeNoetig, oemFaellig = lage.oemFaellig), GateEinstieg.NACH_EINSTELLUNGEN),
                naechsterGateSchritt(lage, GateEinstieg.NACH_EINSTELLUNGEN)
            )
        }
    }

    // ---- SPAETER_*: ein Gate mit "Spaeter"/Zurueck verlassen ----------------------------

    @Test
    fun `einstiegNachSpaeter ordnet jedem ueberspringbaren Gate seinen Einstieg zu`() {
        assertEquals(GateEinstieg.SPAETER_AKKU, einstiegNachSpaeter(GateSchritt.Akku))
        assertEquals(GateEinstieg.SPAETER_UNUSED, einstiegNachSpaeter(GateSchritt.Unused))
        assertEquals(GateEinstieg.SPAETER_TIMEOFFICE, einstiegNachSpaeter(GateSchritt.TimeOffice))
    }

    @Test
    fun `SPAETER_AKKU geht sofort zum naechsten offenen Gate - Unused, TimeOffice, OEM, Fertig`() {
        // Bis Issue #132 fuehrte "Spaeter" nach Home; das naechste Gate kam erst beim naechsten
        // App-Start (hoechstens ein Gate pro Start).
        val e = GateEinstieg.SPAETER_AKKU
        val lage = erledigtPerSpaeter
        assertEquals(GateSchritt.Unused, naechsterGateSchritt(lage.copy(unusedNoetig = true, timeOfficeNoetig = true, oemFaellig = OEMType.REALME), e))
        assertEquals(GateSchritt.TimeOffice, naechsterGateSchritt(lage.copy(timeOfficeNoetig = true, oemFaellig = OEMType.REALME), e))
        assertEquals(GateSchritt.Oem(OEMType.REALME), naechsterGateSchritt(lage.copy(oemFaellig = OEMType.REALME), e))
        assertEquals(GateSchritt.Fertig, naechsterGateSchritt(lage, e))
    }

    @Test
    fun `SPAETER_UNUSED geht sofort weiter - TimeOffice, OEM, Fertig`() {
        val e = GateEinstieg.SPAETER_UNUSED
        assertEquals(GateSchritt.TimeOffice, naechsterGateSchritt(GateLage(timeOfficeNoetig = true, oemFaellig = OEMType.VIVO), e))
        assertEquals(GateSchritt.Oem(OEMType.VIVO), naechsterGateSchritt(GateLage(oemFaellig = OEMType.VIVO), e))
        assertEquals(GateSchritt.Fertig, naechsterGateSchritt(GateLage(), e))
    }

    @Test
    fun `SPAETER_TIMEOFFICE zeigt noch den OEM-Hinweis, sonst Fertig`() {
        // Bis Issue #132 sah den OEM-Hinweis NIE, wer das TimeOffice-Gate mit "Spaeter" verliess.
        val e = GateEinstieg.SPAETER_TIMEOFFICE
        assertEquals(GateSchritt.Oem(OEMType.ONEPLUS), naechsterGateSchritt(GateLage(oemFaellig = OEMType.ONEPLUS), e))
        assertEquals(GateSchritt.Fertig, naechsterGateSchritt(GateLage(), e))
    }

    @Test
    fun `nach Spaeter kommt in KEINER Lage dasselbe oder ein frueheres Gate`() {
        // Die Zusicherung gegen die Endlosschleife - bewusst auch fuer Lagen, in denen das
        // eben geschriebene Dismissed-Flag beim Neulesen NICHT sichtbar ist (unusedNoetig bzw.
        // timeOfficeNoetig noch true, akkuAbgelehnt noch false): ein degradierter Read darf das
        // uebersprungene Gate nicht zurueckholen.
        for (gate in ueberspringbare) for (lage in alleLagen) {
            val schritt = naechsterGateSchritt(lage, einstiegNachSpaeter(gate))
            assertTrue("$gate, Lage $lage -> $schritt", rang(schritt) > rang(gate))
            assertFalse("$gate, Lage $lage -> $schritt", schritt == GateSchritt.Nichts)
        }
    }

    @Test
    fun `eine Kette aus lauter Spaeter endet, jedes Gate hoechstens einmal`() {
        // Simuliert den Nutzer, der jedes Gate wegklickt - und das mit einer Lage, die sich
        // NICHT aendert (keines der Flags wird beim Neulesen sichtbar). Die Kette muss trotzdem
        // enden, ohne ein Gate zweimal zu zeigen.
        for (lage in alleLagen.filter { it.kalenderGewaehlt }) {
            val gezeigt = mutableListOf<GateSchritt>()
            var schritt = auto(lage)
            while (true) {
                val gate = schritt as? GateSchritt.Ueberspringbar ?: break
                assertFalse("Lage $lage: $gate doppelt in $gezeigt", gate in gezeigt)
                gezeigt += gate
                assertTrue("Lage $lage: Kette endet nicht ($gezeigt)", gezeigt.size <= ueberspringbare.size)
                schritt = naechsterGateSchritt(lage, einstiegNachSpaeter(gate))
            }
            assertTrue(
                "Lage $lage endet in $schritt",
                schritt is GateSchritt.Oem || schritt == GateSchritt.Fertig || schritt == GateSchritt.Nichts
            )
        }
    }

    @Test
    fun `wirdAngezeigtIn erkennt genau den Screen des Gates`() {
        val zustaende = listOf(
            NavigationState.MainContent(),
            NavigationState.CalendarSelection(),
            NavigationState.BatteryExemption(),
            NavigationState.UnusedAppRestrictions(),
            NavigationState.TimeOfficeHealthCheck(),
            NavigationState.OEMWarning(OEMType.XIAOMI)
        )
        assertEquals(
            listOf(NavigationState.BatteryExemption()),
            zustaende.filter { GateSchritt.Akku.wirdAngezeigtIn(it) }
        )
        assertEquals(
            listOf(NavigationState.UnusedAppRestrictions()),
            zustaende.filter { GateSchritt.Unused.wirdAngezeigtIn(it) }
        )
        assertEquals(
            listOf(NavigationState.TimeOfficeHealthCheck()),
            zustaende.filter { GateSchritt.TimeOffice.wirdAngezeigtIn(it) }
        )
    }

    // ---- uebergreifend --------------------------------------------------------------------

    @Test
    fun `akkuGateErledigt ist Ausnahme ODER Spaeter`() {
        assertTrue(erledigtPerAusnahme.akkuGateErledigt)
        assertTrue(erledigtPerSpaeter.akkuGateErledigt)
        assertTrue(GateLage(akkuAusnahme = true, akkuAbgelehnt = true).akkuGateErledigt)
        assertFalse(akkuOffen.akkuGateErledigt)
    }

    @Test
    fun `die drei ueberspringbaren Gates sind genau Akku, Unused und TimeOffice`() {
        // ueberspringe() in MainScreen verzweigt erschoepfend ueber diesen Untertyp - jedes davon
        // MUSS beim Ueberspringen sein Dismissed-Flag schreiben. Kalender und OEM sind keine
        // "Spaeter"-Gates (OEM schliesst wie "Verstanden" ab).
        val alle: List<GateSchritt> = listOf(
            GateSchritt.Kalender, GateSchritt.Akku, GateSchritt.Unused, GateSchritt.TimeOffice,
            GateSchritt.Oem(OEMType.XIAOMI), GateSchritt.Fertig, GateSchritt.Nichts
        )
        assertEquals(
            listOf(GateSchritt.Akku, GateSchritt.Unused, GateSchritt.TimeOffice),
            alle.filterIsInstance<GateSchritt.Ueberspringbar>()
        )
    }
}
