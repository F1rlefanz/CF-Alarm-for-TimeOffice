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
 * Der Zaehler hinter dem Hinweis „Weck-Bildschirm wird verdraengt".
 *
 * WARUM INSTRUMENTIERT UND NICHT ALS UNIT-TEST: [WeckbildschirmVerdraengungPrefs] schreibt
 * SharedPreferences und braucht einen echten `Context`; das Projekt fuehrt kein Robolectric.
 *
 * WAS HIER NICHT MEHR STEHT - und warum das eine Verbesserung ist: Bis 1.39.4 trug diese Datei
 * einen zweiten, bleibenden Merker `je_verdraengt`, der entschied, OB vorgeweckt wird. Die drei
 * Tests dazu sind mit ihm gestrichen (1.39.5). Der Grund fuer das Streichen war nicht, dass sie
 * laestig waren, sondern dass das Gate im CE-Storage lag und im Direct Boot nicht lesbar war -
 * der erste Wecker nach einem naechtlichen Neustart lief ungeschuetzt. Das Vorwecken haengt jetzt
 * nur noch am Systemzustand; seine Bedingung prueft [VorweckEntscheidungTest] ohne Geraet.
 *
 * Was bleibt, ist der HINWEIS - und fuer den gilt weiterhin: er behauptet einen Zustand
 * („auf diesem Geraet passiert das"), also faellt er weg, sobald ein Wecker sauber durchlaeuft.
 *
 * Der Test schreibt in die echten App-Preferences und stellt den Ausgangszustand danach wieder her -
 * auf einem produktiv genutzten Geraet darf er keine Spur hinterlassen.
 */
@RunWith(AndroidJUnit4::class)
@SmallTest
class WeckbildschirmVerdraengungPrefsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun prefs() = context.getSharedPreferences("weckbildschirm_verdraengung", Context.MODE_PRIVATE)

    private var sicherungAnzahl = 0

    @Before
    fun sichereAusgangszustand() {
        sicherungAnzahl = prefs().getInt("anzahl_in_folge", 0)
        prefs().edit().clear().commit()
    }

    @After
    fun stelleAusgangszustandWiederHer() {
        prefs().edit()
            .putInt("anzahl_in_folge", sicherungAnzahl)
            .commit()
    }

    @Test
    fun frischesGeraetZeigtKeinenHinweis() {
        assertEquals(0, WeckbildschirmVerdraengungPrefs.anzahlInFolge(context))
        assertFalse(WeckbildschirmVerdraengungPrefs.hinweisFaellig(context))
    }

    @Test
    fun derHinweisErscheintErstAbDerSchwelle() {
        // Ein einzelner Aussetzer kann ein Systemdialog oder ein Anruf gewesen sein - der soll den
        // Nutzer nicht behelligen. Erst zwei Weckvorgaenge in Folge sind ein Zustand.
        WeckbildschirmVerdraengungPrefs.zaehleVerdraengung(context)
        assertEquals(1, WeckbildschirmVerdraengungPrefs.anzahlInFolge(context))
        assertFalse(
            "Nach EINER Verdraengung darf noch kein Hinweis stehen",
            WeckbildschirmVerdraengungPrefs.hinweisFaellig(context)
        )

        WeckbildschirmVerdraengungPrefs.zaehleVerdraengung(context)
        assertEquals(WeckbildschirmVerdraengungPrefs.SCHWELLE, WeckbildschirmVerdraengungPrefs.anzahlInFolge(context))
        assertTrue(WeckbildschirmVerdraengungPrefs.hinweisFaellig(context))
    }

    @Test
    fun einSaubererLaufNimmtDenHinweisZurueck() {
        WeckbildschirmVerdraengungPrefs.zaehleVerdraengung(context)
        WeckbildschirmVerdraengungPrefs.zaehleVerdraengung(context)
        assertTrue(WeckbildschirmVerdraengungPrefs.hinweisFaellig(context))

        WeckbildschirmVerdraengungPrefs.meldeSauberenLauf(context)

        assertEquals(
            "Der Zaehler gehoert hart zurueckgestellt, nicht heruntergezaehlt - der Hinweis " +
                "behauptet einen Zustand, keine Statistik",
            0,
            WeckbildschirmVerdraengungPrefs.anzahlInFolge(context)
        )
        assertFalse(WeckbildschirmVerdraengungPrefs.hinweisFaellig(context))
    }
}
