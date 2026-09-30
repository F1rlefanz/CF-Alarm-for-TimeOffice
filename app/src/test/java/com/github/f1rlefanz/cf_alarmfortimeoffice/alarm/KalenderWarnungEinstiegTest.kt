package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Der WEG von der Kalender-Warnung zu der Stelle, die sie erklaert.
 *
 * HERGANG (Review 30.09.2026): Die Warnung schickt in den System-Status unter "Kalender". Ihr Tipp
 * oeffnete aber nur den Start-Intent der App - lief die App noch, kam sie mit ihrem LETZTEN Stand
 * nach vorn: der zuletzt benutzte Tab, und die Karte "Kalender" zeigte den Abruf von VOR dem
 * Ausfall ("API-Zugriff OK"). Der Nutzer hielt die Warnung fuer einen Fehlalarm, und eine zweite
 * kommt je Kalender nicht. Seit auch der Totalausfall gemeldet wird - meist fehlt dann der EINZIGE
 * Kalender -, laeuft der Hauptfall ueber genau diesen Tipp.
 *
 * Intents und Activities lassen sich ohne Framework nicht ausfuehren. Geprueft wird deshalb der
 * Quelltext der Kette, wie beim Einstieg der Dimmer-Meldung (DimBedienungshilfenWunschTest).
 */
class KalenderWarnungEinstiegTest {

    private fun quelltext(pfad: String): String =
        File("src/main/java/com/github/f1rlefanz/cf_alarmfortimeoffice/$pfad").readText()

    /** Ohne Kommentare - die Begruendungen nennen die geforderten Aufrufe absichtlich beim Namen. */
    private fun ohneKommentare(pfad: String): String =
        quelltext(pfad).lines()
            .filterNot { it.trimStart().startsWith("//") }
            .filterNot { it.trimStart().startsWith("*") }
            .filterNot { it.trimStart().startsWith("/*") }
            .joinToString("\n")

    @Test
    fun `die Warnung nennt ihr Ziel im Intent`() {
        val notifier = ohneKommentare("alarm/CalendarUnavailableNotifier.kt")

        assertTrue(
            "Ohne das Extra landet der Tipp auf dem zuletzt benutzten Tab",
            notifier.contains("MainActivity.EXTRA_EINSTIEG") &&
                notifier.contains("MainActivity.EINSTIEG_KALENDER_WARNUNG")
        )
        assertFalse(
            "Der blosse Start-Intent holt eine laufende App mit ihrem ALTEN Stand nach vorn",
            notifier.contains("getLaunchIntentForPackage")
        )
        assertTrue(
            "FLAG_ACTIVITY_SINGLE_TOP fehlt - CLEAR_TOP legt MainActivity (standard) dann neu an",
            notifier.contains("FLAG_ACTIVITY_SINGLE_TOP")
        )
    }

    @Test
    fun `MainActivity fuehrt in den System-Status und laedt den Kalender neu`() {
        val activity = ohneKommentare("MainActivity.kt")
        val zweig = Regex("""EINSTIEG_KALENDER_WARNUNG -> \{([\s\S]*?)\n {12}\}""")
            .find(activity)?.groupValues?.get(1)

        assertNotNull("MainActivity wertet den Einstieg der Kalender-Warnung nicht aus", zweig)
        assertTrue(
            "Ohne den Tab-Wechsel bleibt der Nutzer, wo er war - die Karte steht im System-Status",
            zweig!!.contains("navigateToMainWithTab(MainTab.STATUS)")
        )
        assertTrue(
            "Ohne Neuladen zeigt die Karte den Abruf von VOR dem Ausfall",
            zweig.contains("refreshData(forceRefresh = true)")
        )
    }
}
