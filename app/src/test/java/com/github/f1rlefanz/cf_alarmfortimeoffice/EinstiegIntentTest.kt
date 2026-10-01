package com.github.f1rlefanz.cf_alarmfortimeoffice


import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Der gemeinsame Intent, mit dem eine Benachrichtigung die App an eine bestimmte Stelle schickt
 * ([MainActivity.einstiegIntent]), und die zwei Stellen, an denen MainActivity ihn auswertet.
 *
 * HERGANG (Review 30.09.2026, am Emulator nachgestellt): Die Kalender-Warnung oeffnete die App mit
 * einem expliziten Intent OHNE `ACTION_MAIN`/`CATEGORY_LAUNCHER`. Lief noch kein Task, wurde dieser
 * Intent dessen Basis-Intent - und jeder spaetere Start ueber das Launcher-Symbol legte eine WEITERE
 * MainActivity obendrauf (Android startet eine neue Instanz, wenn der Launcher-Intent nicht
 * filtergleich mit dem Basis-Intent ist; Extras zaehlen dabei nicht). Gemessen: zwei
 * MainActivity-Eintraege im selben Task. Zurueck auf der Uebersicht zeigte dann die alte Instanz,
 * statt die App zu verlassen. Die Dimmer-Meldung war genauso gebaut.
 *
 * Intents lassen sich ohne Framework nicht ausfuehren; geprueft wird der Quelltext.
 */
class EinstiegIntentTest {

    private fun ohneKommentare(pfad: String): String =
        File("src/main/java/com/github/f1rlefanz/cf_alarmfortimeoffice/$pfad").readText().lines()
            .filterNot { it.trimStart().startsWith("//") }
            .filterNot { it.trimStart().startsWith("*") }
            .filterNot { it.trimStart().startsWith("/*") }
            .joinToString("\n")

    private fun einstiegIntentRumpf(): String {
        val activity = ohneKommentare("MainActivity.kt")
        val start = activity.indexOf("fun einstiegIntent(")
        assertTrue("MainActivity.einstiegIntent() fehlt", start >= 0)
        val ende = activity.indexOf("\n\n", start).let { if (it < 0) activity.length else it }
        return activity.substring(start, ende)
    }

    @Test
    fun `der Einstieg ist filtergleich mit dem Launcher-Start`() {
        val rumpf = einstiegIntentRumpf()

        assertTrue(
            "Ohne ACTION_MAIN legt ein spaeterer Launcher-Start eine zweite MainActivity an",
            rumpf.contains("Intent.ACTION_MAIN")
        )
        assertTrue(
            "Ohne CATEGORY_LAUNCHER legt ein spaeterer Launcher-Start eine zweite MainActivity an",
            rumpf.contains("Intent.CATEGORY_LAUNCHER")
        )
    }

    @Test
    fun `der Einstieg wirft eine laufende App nicht weg und traegt sein Ziel`() {
        val rumpf = einstiegIntentRumpf()

        assertTrue(
            "FLAG_ACTIVITY_SINGLE_TOP fehlt - CLEAR_TOP legt MainActivity (standard) dann neu an",
            rumpf.contains("FLAG_ACTIVITY_SINGLE_TOP") && rumpf.contains("FLAG_ACTIVITY_CLEAR_TOP")
        )
        assertTrue("Ohne das Extra weiss MainActivity nicht, wohin", rumpf.contains("EXTRA_EINSTIEG"))
    }

    @Test
    fun `beide Benachrichtigungen bauen ihren Intent ueber den gemeinsamen Einstieg`() {
        val kalender = ohneKommentare("alarm/CalendarUnavailableNotifier.kt")
        val dimmer = ohneKommentare("dimmer/DimCorrectionNotifier.kt")

        assertTrue(
            "Die Kalender-Warnung baut ihren Intent selbst - der naechste vergisst MAIN/LAUNCHER",
            kalender.contains("MainActivity.einstiegIntent(context, MainActivity.EINSTIEG_KALENDER_WARNUNG)")
        )
        assertTrue(
            "Die Dimmer-Meldung baut ihren Intent selbst - der naechste vergisst MAIN/LAUNCHER",
            dimmer.contains("MainActivity.einstiegIntent(context, MainActivity.EINSTIEG_DIMMER_BEDIENUNGSHILFEN)")
        )
    }

    @Test
    fun `ein Kaltstart wertet den Einstieg aus, eine Wiederherstellung nicht`() {
        val activity = ohneKommentare("MainActivity.kt")

        assertTrue(
            "onCreate wertet den Einstieg nicht (oder auch nach Drehung/Wiederherstellung) aus",
            Regex("""if \(savedInstanceState == null\) \{\s*verarbeiteEinstieg\(intent\)\s*\}""")
                .containsMatchIn(activity)
        )
    }

    @Test
    fun `eine laufende App wertet den neuen Einstieg aus`() {
        val activity = ohneKommentare("MainActivity.kt")

        assertTrue(
            "onNewIntent muss setIntent UND danach verarbeiteEinstieg rufen",
            Regex(
                """override fun onNewIntent\(intent: Intent\) \{\s*super\.onNewIntent\(intent\)\s*""" +
                    """setIntent\(intent\)\s*verarbeiteEinstieg\(intent\)\s*\}"""
            ).containsMatchIn(activity)
        )
    }
}
