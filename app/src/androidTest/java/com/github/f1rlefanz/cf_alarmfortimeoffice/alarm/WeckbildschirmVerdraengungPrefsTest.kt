package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Die beiden Merker der Verdraengung — und dass ein sauberer Lauf nur EINEN davon zuruecksetzt.
 *
 * WARUM INSTRUMENTIERT UND NICHT ALS UNIT-TEST: [WeckbildschirmVerdraengungPrefs] schreibt
 * SharedPreferences und braucht einen echten `Context`; das Projekt fuehrt kein Robolectric.
 *
 * WARUM ES DIESEN TEST GIBT: Am 04.09.2026 war der Hinweis-Zaehler zugleich das Gate fuers
 * Vorwecken. Der erste geschuetzte Lauf am Fairphone war sauber — und genau dadurch fiel der
 * Zaehler auf 0 und schaltete den Schutz fuer den naechsten Wecker wieder ab. Das Ergebnis waere
 * jeder ZWEITE Wecker ohne Bedienoberflaeche gewesen. Wer die beiden Merker wieder zu einem
 * zusammenzieht, baut genau das nach.
 *
 * Der Test schreibt in die echten App-Preferences und stellt den Ausgangszustand danach wieder her —
 * auf einem produktiv genutzten Geraet darf er keine Spur hinterlassen.
 */
@RunWith(AndroidJUnit4::class)
@SmallTest
class WeckbildschirmVerdraengungPrefsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun prefs() = context.getSharedPreferences("weckbildschirm_verdraengung", Context.MODE_PRIVATE)

    private var sicherungAnzahl = 0
    private var sicherungMerker = false

    @Before
    fun sichereAusgangszustand() {
        sicherungAnzahl = prefs().getInt("anzahl_in_folge", 0)
        sicherungMerker = prefs().getBoolean("je_verdraengt", false)
        prefs().edit().clear().commit()
    }

    @After
    fun stelleAusgangszustandWiederHer() {
        prefs().edit()
            .putInt("anzahl_in_folge", sicherungAnzahl)
            .putBoolean("je_verdraengt", sicherungMerker)
            .commit()
    }

    @Test
    fun frischesGeraetIstNichtBetroffen() {
        assertFalse(
            "Ohne jede Messung darf kein Vorwecken greifen",
            WeckbildschirmVerdraengungPrefs.jeVerdraengt(context)
        )
        assertFalse(WeckbildschirmVerdraengungPrefs.hinweisFaellig(context))
    }

    @Test
    fun einSaubererLaufLoeschtDenHinweisAberNichtDenSchutz() {
        WeckbildschirmVerdraengungPrefs.zaehleVerdraengung(context)
        WeckbildschirmVerdraengungPrefs.zaehleVerdraengung(context)
        assertEquals(2, WeckbildschirmVerdraengungPrefs.anzahlInFolge(context))
        assertTrue(WeckbildschirmVerdraengungPrefs.hinweisFaellig(context))
        assertTrue(WeckbildschirmVerdraengungPrefs.jeVerdraengt(context))

        WeckbildschirmVerdraengungPrefs.meldeSauberenLauf(context)

        assertEquals(
            "Der Hinweis-Zaehler gehoert zurueckgestellt - es passiert gerade nicht mehr",
            0,
            WeckbildschirmVerdraengungPrefs.anzahlInFolge(context)
        )
        assertFalse(WeckbildschirmVerdraengungPrefs.hinweisFaellig(context))
        assertTrue(
            "DER KERN DIESES TESTS: der saubere Lauf ist das Verdienst des Vorweckens. Faellt der " +
                "bleibende Merker mit zurueck, laeuft der naechste Wecker ungeschuetzt und wird " +
                "wieder verdraengt - jeder zweite Wecker ohne Bedienoberflaeche.",
            WeckbildschirmVerdraengungPrefs.jeVerdraengt(context)
        )
    }

    @Test
    fun derSaubereLaufSchreibtDenMerkerAuchNach() {
        // DER FALL, DER AM 04.09.2026 UM 17:33 DURCHRUTSCHTE: Bestandsgeraet, Zaehler steht,
        // Merker gibt es noch nicht. Der geschuetzte Lauf ist sauber - zaehleVerdraengung() kommt
        // also gar nicht dran, und ohne diesen Nachzug faellt mit dem Zaehler auch die
        // Migrationsbedingung weg. Der naechste Wecker liefe wieder ungeschuetzt.
        prefs().edit().putInt("anzahl_in_folge", 4).commit()
        assertTrue(WeckbildschirmVerdraengungPrefs.jeVerdraengt(context))

        WeckbildschirmVerdraengungPrefs.meldeSauberenLauf(context)

        assertEquals(0, WeckbildschirmVerdraengungPrefs.anzahlInFolge(context))
        assertTrue(
            "Der Merker muss beim Zuruecksetzen mitgeschrieben werden - sonst ist der Schutz weg, " +
                "sobald der Zaehler faellt",
            prefs().getBoolean("je_verdraengt", false)
        )
        assertTrue(WeckbildschirmVerdraengungPrefs.jeVerdraengt(context))
    }

    @Test
    fun bestandsgeraeteMitZaehlerGeltenAlsBetroffen() {
        // Migration: auf Geraeten, die vor dieser Version schon gezaehlt haben, gibt es den neuen
        // Merker noch nicht. Ohne diesen Zweig muesste dort erst wieder ein Wecker verdraengt
        // werden, bevor der Schutz ueberhaupt greift.
        prefs().edit().putInt("anzahl_in_folge", 3).commit()
        assertTrue(WeckbildschirmVerdraengungPrefs.jeVerdraengt(context))
    }
}
