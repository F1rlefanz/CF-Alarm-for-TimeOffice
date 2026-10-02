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
 * Diese Tests halten das Verhalten VOR dem Umbau fest, ausdruecklich inklusive seiner
 * Asymmetrien (AUTO ohne OEM, NACH_KALENDER ohne Blick auf "Spaeter"). Wer eine davon bewusst
 * aufhebt (Issue #132, Schritt 2), dreht den jeweiligen Test hier um - und nicht still einen
 * anderen.
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
    fun `AUTO kennt keinen OEM-Hinweis - ein faelliger OEM-Screen bleibt Nichts`() {
        // Heutiges Verhalten, bewusst festgehalten: den OEM-Screen gibt es nur auf den aktiven
        // Wegen. Wer "Spaeter" waehlt, sieht ihn auf dem automatischen Weg nie.
        assertEquals(GateSchritt.Nichts, auto(erledigtPerAusnahme.copy(oemFaellig = OEMType.XIAOMI)))
        assertEquals(GateSchritt.Nichts, auto(erledigtPerSpaeter.copy(oemFaellig = OEMType.HUAWEI)))
    }

    @Test
    fun `AUTO liefert nie Oem oder Fertig`() {
        for (lage in alleLagen) {
            val schritt = auto(lage)
            assertFalse("Lage $lage -> $schritt", schritt is GateSchritt.Oem)
            assertFalse("Lage $lage -> $schritt", schritt == GateSchritt.Fertig)
        }
    }

    // ---- NACH_KALENDER: Kalenderauswahl mit "Fertig" verlassen --------------------------

    @Test
    fun `NACH_KALENDER ohne Akku-Ausnahme fuehrt zum Akku-Gate`() {
        assertEquals(GateSchritt.Akku, naechsterGateSchritt(akkuOffen, GateEinstieg.NACH_KALENDER))
    }

    @Test
    fun `NACH_KALENDER fragt NUR die Ausnahme - nach Spaeter kommt das Akku-Gate erneut`() {
        // Heutige Asymmetrie zum automatischen Weg, bewusst festgehalten (Issue #132, Schritt 2
        // will sie aufheben): das "Spaeter"-Flag zaehlt hier nicht.
        assertEquals(
            GateSchritt.Akku,
            naechsterGateSchritt(erledigtPerSpaeter, GateEinstieg.NACH_KALENDER)
        )
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
