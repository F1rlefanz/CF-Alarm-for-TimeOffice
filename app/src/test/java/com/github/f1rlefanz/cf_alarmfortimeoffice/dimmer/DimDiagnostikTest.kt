package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimDiagnostik.AbschaltGrund
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimDiagnostik.OverlayWeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Die Dimmer-Diagnostik — was im Log stehen muss und was NICHT hineingehoert.
 *
 * HERGANG (24.08.2026): Der Eigentuemer meldete, der Bildschirm sei „mal heller und mal dunkler"
 * geworden. Die Ursache war am Ende kein App-Fehler — die UI-Automation (`uiautomator`)
 * unterdrueckt als `UiAutomation` alle anderen Bedienungshilfen-Dienste und zerstoert damit das
 * Dimm-Overlay; am Geraet belegt ueber die wechselnde SurfaceFlinger-Layer-ID bei JEDEM
 * Automations-Aufruf und die unveraenderte im Leerlauf.
 *
 * Der eigentliche Befund war, dass sich das aus dem Datei-Log **nicht rekonstruieren** liess:
 * `DimAccessibilityService` hatte keine einzige Zeile beim Verbinden, Trennen oder Abraeumen, und
 * der haeufigste Aus-Weg in `DimScheduleUseCase.applyCurrentState()` kehrte kommentarlos zurueck.
 * Man sah hinterher nur, wann gedimmt WURDE — nie, wann und warum es aufhoerte.
 *
 * Der Dienst selbst laesst sich ohne Android-Framework nicht instanziieren; pruefbar ist deshalb
 * das, was bewusst als REINE Funktion daneben liegt (dasselbe Vorgehen wie beim Datei-Log in
 * `Pruefrunde6LoggingDirectBootTest`).
 */
class DimDiagnostikTest {

    // ---------------------------------------------------------------- Schnappschuss

    @Test
    fun `der Schnappschuss traegt alle Werte, die den Vorfall erklaeren`() {
        val zeile = DimDiagnostik.overlaySnapshot(
            bound = true,
            weg = OverlayWeg.DISPLAY,
            alpha = 1f,
            lastOverlayOn = true,
            sdkInt = 36
        )

        assertEquals("bound=true, weg=DISPLAY, alpha=1.00, sollAn=true, sdk=36", zeile)
    }

    /**
     * EINE Zeile, kein mehrzeiliger Block: sie soll im Release-Log direkt neben der WARN-Meldung
     * stehen koennen — dieselbe Auflage wie bei `visibilitySnapshot()` am Weckbildschirm.
     */
    @Test
    fun `der Schnappschuss ist einzeilig`() {
        val zeile = DimDiagnostik.overlaySnapshot(false, OverlayWeg.KEINER, 0f, false, 26)

        assertFalse("Zeilenumbrueche machen die Zeile im Log unbrauchbar", zeile.contains("\n"))
    }

    /**
     * DIE WICHTIGSTE ZUSICHERUNG DIESER DATEI. WARN und ERROR landen im RELEASE-Log, und
     * Schicht- wie Regelnamen sind Nutzertexte — der Dienstplan des Nutzers gehoert dort nicht
     * hinein (dieselbe Regel wie beim Regelkonflikt-WARN in `DimWindowResolver`). Der Schnappschuss
     * nimmt deshalb ausschliesslich Flags, Zahlen und Aufzaehlungswerte entgegen: es gibt gar
     * keinen Parameter, ueber den ein Name hineingeraten koennte.
     */
    @Test
    fun `der Schnappschuss kann keine Nutzertexte enthalten`() {
        val zeile = DimDiagnostik.overlaySnapshot(true, OverlayWeg.WINDOW_MANAGER, 0.5f, true, 34)

        listOf("Nachtschicht", "Fruehschicht", "Spaetschicht", "Taeglich", "AD1").forEach { name ->
            assertFalse("'$name' darf nicht im Log landen", zeile.contains(name, ignoreCase = true))
        }
    }

    @Test
    fun `die Alpha-Rampe wird auf zwei Nachkommastellen gekuerzt`() {
        val zeile = DimDiagnostik.overlaySnapshot(true, OverlayWeg.DISPLAY, 0.123456f, true, 34)

        assertTrue("volle Gleitkomma-Genauigkeit waere Rauschen: $zeile", zeile.contains("alpha=0.12"))
    }

    /**
     * Das Format darf NICHT von der Geraete-Sprache abhaengen. Ohne `Locale.ROOT` stuende auf
     * einem deutschen Geraet `alpha=0,50` und auf einem englischen `alpha=0.50` - zwei Protokolle
     * liessen sich dann nicht mehr sauber vergleichen, und dieser Test fiele je nach
     * Build-Maschine anders aus.
     */
    @Test
    fun `das Zahlenformat haengt nicht an der Geraete-Sprache`() {
        val vorher = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            val deutsch = DimDiagnostik.overlaySnapshot(true, OverlayWeg.DISPLAY, 0.5f, true, 34)
            java.util.Locale.setDefault(java.util.Locale.US)
            val englisch = DimDiagnostik.overlaySnapshot(true, OverlayWeg.DISPLAY, 0.5f, true, 34)

            assertEquals(deutsch, englisch)
            assertTrue(deutsch.contains("alpha=0.50"))
        } finally {
            java.util.Locale.setDefault(vorher)
        }
    }

    // ---------------------------------------------------------------- Abschaltgrund

    /**
     * Die Reihenfolge der Gates ist bedeutungstragend und muss der von `applyCurrentState()`
     * entsprechen: Master-Pause schlaegt alles, dann der Hauptschalter, dann die Fensterlage.
     * Steht sie hier anders, meldet das Log einen Grund, der gar nicht der wirksame war.
     */
    @Test
    fun `Master-Pause schlaegt jeden anderen Grund`() {
        val grund = DimDiagnostik.abschaltGrund(
            masterPause = true,
            dimEnabled = true,
            regelnVorhanden = true,
            fensterAktiv = false,
            overridePausiert = true
        )

        assertEquals(AbschaltGrund.MASTER_PAUSE, grund)
    }

    @Test
    fun `ausgeschalteter Dimmer schlaegt die Fensterlage`() {
        val grund = DimDiagnostik.abschaltGrund(
            masterPause = false,
            dimEnabled = false,
            regelnVorhanden = true,
            fensterAktiv = false,
            overridePausiert = false
        )

        assertEquals(AbschaltGrund.DIMMER_AUS, grund)
    }

    @Test
    fun `ein von Hand pausiertes Fenster wird als solches gemeldet`() {
        val grund = DimDiagnostik.abschaltGrund(
            masterPause = false,
            dimEnabled = true,
            regelnVorhanden = true,
            fensterAktiv = true,
            overridePausiert = true
        )

        assertEquals(AbschaltGrund.OVERRIDE_PAUSIERT, grund)
    }

    @Test
    fun `ohne Regeln ist ein fehlendes Fenster der Normalfall`() {
        val grund = DimDiagnostik.abschaltGrund(
            masterPause = false,
            dimEnabled = true,
            regelnVorhanden = false,
            fensterAktiv = false,
            overridePausiert = false
        )

        assertEquals(AbschaltGrund.KEIN_FENSTER, grund)
    }

    /**
     * DER FALL, NACH DEM SPAETER GEFRAGT WIRD: Dimmer an, Regeln da — und trotzdem kein Fenster.
     * Das kann voellig richtig sein (Mittagszeit), ist aber auch die Signatur eines stillen
     * Ausfalls: eine Regel, die nie greift, ein leerer Schichtspannen-Speicher, eine versehentlich
     * geleerte Fensterliste.
     */
    @Test
    fun `Dimmer an plus Regeln aber kein Fenster ist der Verdachtsfall`() {
        val grund = DimDiagnostik.abschaltGrund(
            masterPause = false,
            dimEnabled = true,
            regelnVorhanden = true,
            fensterAktiv = false,
            overridePausiert = false
        )

        assertEquals(AbschaltGrund.KEIN_FENSTER_TROTZ_REGELN, grund)
    }

    // ---------------------------------------------------------------- Log-Level

    /**
     * Release-Logs fuehren nur WARN+. Genau EIN Grund gehoert dorthin — alles, was der Nutzer
     * selbst eingestellt hat, waere taeglich wiederkehrendes Rauschen und wuerde die Zeile
     * entwerten, auf die es ankommt.
     */
    @Test
    fun `nur der Verdachtsfall geht ins Release-Log`() {
        assertTrue(DimDiagnostik.istVerdaechtig(AbschaltGrund.KEIN_FENSTER_TROTZ_REGELN))

        listOf(
            AbschaltGrund.MASTER_PAUSE,
            AbschaltGrund.DIMMER_AUS,
            AbschaltGrund.KEIN_FENSTER,
            AbschaltGrund.OVERRIDE_PAUSIERT
        ).forEach { grund ->
            assertFalse("$grund ist eine Nutzerentscheidung, kein Vorfall", DimDiagnostik.istVerdaechtig(grund))
        }
    }

    /** Gegenprobe gegen ein spaeteres Ergaenzen: JEDER Grund muss eine Level-Entscheidung haben. */
    @Test
    fun `jeder Abschaltgrund ist einem Level zugeordnet`() {
        val verdaechtig = AbschaltGrund.entries.count { DimDiagnostik.istVerdaechtig(it) }

        assertEquals(
            "Genau ein Grund gehoert ins Release-Log - wer einen weiteren ergaenzt, " +
                "muss hier bewusst entscheiden",
            1, verdaechtig
        )
    }
}
