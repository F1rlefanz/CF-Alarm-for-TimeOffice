package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Haelt die Inset-Behandlung der Compose-Wurzel fest - die Zusicherung, die kein Unit-Test
 * zeichnen kann und die man deshalb nur am Quelltext festnageln kann.
 *
 * DER ABLAUF, DER DAZU GEFUEHRT HAT: Seit Android 15 zeichnet jede App mit targetSdk >= 35
 * zwangsweise unter Status- und Navigationsleiste hindurch; ab targetSdk 36 gibt es das Opt-out
 * `android:windowOptOutEdgeToEdgeEnforcement` nicht mehr. Das Projekt steht auf targetSdk 37,
 * die Compose-Wurzel in `MainActivity` bestand aber nur aus `Surface(Modifier.fillMaxSize())`.
 * Alles innerhalb eines `Scaffold` war dadurch versorgt, alles ausserhalb nicht - und ausserhalb
 * liegen ausgerechnet die Bildschirme des Erststarts: Anmeldung, Kalender-Freigabe, die vier
 * Onboarding-Gates und die OEM-Warnung. Deren einziger Weiterweg ist ein Knopf am unteren Rand
 * ("Spaeter", "Verstanden"), der unter der Navigationsleiste lag. Wer ihn nicht traf, kam aus dem
 * Gate nicht heraus.
 *
 * Geprueft wird der Quelltext, weil die Wirkung erst am Geraet sichtbar wird - und dort erst beim
 * Erststart eines neuen Nutzers, also genau dann, wenn niemand mehr hinsieht.
 */
class RandloseDarstellungTest {

    private fun quelle(relativerPfad: String): String {
        val basis = listOf(File("app/src/main/java"), File("src/main/java"))
            .firstOrNull { it.isDirectory }
            ?: error("Quellverzeichnis nicht gefunden (Arbeitsverzeichnis ${File(".").absolutePath})")
        val datei = File(basis, "com/github/f1rlefanz/cf_alarmfortimeoffice/$relativerPfad")
        require(datei.isFile) { "Quelldatei nicht gefunden: ${datei.absolutePath}" }
        return datei.readText()
    }

    /**
     * Nur der Code, ohne Kommentarzeilen: die Kommentare benennen die Regeln absichtlich beim
     * Namen ("safeDrawingPadding() VERBRAUCHT die Insets") - wer darauf prueft, prueft den
     * Kommentar statt der Wirkung. Genau das hat die Mutationsprobe hier aufgedeckt.
     */
    private fun code(relativerPfad: String): String =
        quelle(relativerPfad).lines().filterNot { it.trimStart().startsWith("//") }
            .joinToString(System.lineSeparator())

    private val mainActivity by lazy { code("MainActivity.kt") }

    @Test
    fun `die Compose-Wurzel traegt die Inset-Behandlung`() {
        assertTrue(
            "MainActivity muss enableEdgeToEdge() aufrufen - sonst bleibt die Kontrastierung " +
                "der Systemleisten-Symbole dem Zufall des Themes ueberlassen.",
            mainActivity.contains("enableEdgeToEdge()")
        )
        assertTrue(
            "Die Compose-Wurzel in MainActivity muss safeDrawingPadding() anwenden. Ohne sie " +
                "liegt der einzige Weiterweg der Onboarding-Bildschirme unter der " +
                "Navigationsleiste, weil sie ausserhalb jedes Scaffold gerendert werden.",
            mainActivity.contains("safeDrawingPadding()")
        )
    }

    @Test
    fun `die Insets liegen INNERHALB des faerbenden Surface`() {
        val surface = mainActivity.indexOf("Surface(")
        val insets = mainActivity.indexOf("safeDrawingPadding()")
        assertTrue("Surface der Compose-Wurzel nicht gefunden", surface >= 0)
        assertTrue(
            "safeDrawingPadding() gehoert INNERHALB des Surface, nicht an dessen eigenen " +
                "Modifier: sonst faerbt der Theme-Hintergrund nicht mehr bis unter die " +
                "Systemleisten und dort blitzt das helle Fenster-Hintergrundbild durch.",
            insets > surface
        )
        assertTrue(
            "Der Modifier des Wurzel-Surface muss reines fillMaxSize() bleiben.",
            mainActivity.contains("modifier = Modifier.fillMaxSize(),")
        )
    }

    @Test
    fun `keine zweite Polsterung unterhalb der Wurzel`() {
        // safeDrawingPadding() an der Wurzel VERBRAUCHT die Insets; ein zweiter Aufruf weiter
        // unten waere wirkungslos, ein handgemachtes systemBarsPadding() dagegen doppelte
        // Polsterung - der uebliche Folgefehler dieser Korrektur.
        //
        // MainContentScreen steht seit v1.38.0 mit auf der Liste, obwohl es ein Scaffold HAT:
        // mit dem Wegfall der unteren Navigationsleiste und der App-Titelzeile schrumpfte sein
        // innerPadding auf fast nichts, und genau dann ist jemand versucht, ein
        // statusBarsPadding() "nachzuruesten". Die Schublade traegt aus demselben Grund
        // ausdruecklich WindowInsets(0, 0, 0, 0).
        val doppelt = listOf(
            "ui/components/PermissionOnboardingScreen.kt",
            "ui/components/LoadingScreen.kt",
            "ui/screens/OEMWarningScreen.kt",
            "ui/screens/LoginScreen.kt",
            "ui/screens/CalendarAuthorizationScreen.kt",
            "ui/screens/MainContentScreen.kt"
        ).filter { pfad ->
            val text = code(pfad)
            text.contains("safeDrawingPadding") ||
                text.contains("systemBarsPadding") ||
                text.contains("navigationBarsPadding") ||
                text.contains("statusBarsPadding")
        }
        assertEquals(
            "Diese Bildschirme polstern zusaetzlich zur Wurzel: $doppelt",
            emptyList<String>(),
            doppelt
        )
    }

    @Test
    fun `Bildschirme mit Knopf am unteren Rand haben einen Scroll-Ausweg`() {
        // Insets allein reichen nicht: bleibt der Inhalt bei grosser Schriftskalierung laenger
        // als der Bildschirm, ist der Weiterweg trotzdem unerreichbar, wenn nicht gescrollt
        // werden kann. Betrifft die Bildschirme, deren einziger Weiterweg unten sitzt.
        listOf(
            "ui/screens/OEMWarningScreen.kt" to "Verstanden",
            "ui/screens/LoginScreen.kt" to "Mit Google anmelden",
            "ui/components/PermissionOnboardingScreen.kt" to "Später",
            "ui/screens/CalendarAuthorizationScreen.kt" to "Kalender-Zugriff erlauben"
        ).forEach { (pfad, knopf) ->
            assertTrue(
                "$pfad fuehrt ueber den Knopf \"$knopf\" weiter, laesst sich aber nicht " +
                    "scrollen - bei grosser Schrift ist der Knopf dann nicht erreichbar.",
                code(pfad).contains("verticalScroll(")
            )
        }
    }

    @Test
    fun `die OEM-Warnung verteilt keinen Restplatz mehr in der Scroll-Spalte`() {
        // `weight(1f)` in einer scrollbaren Column ist kein Schoenheitsfehler, sondern ein
        // Absturz beim Messen (unendliche Hoehe, kein verteilbarer Restplatz).
        val oem = code("ui/screens/OEMWarningScreen.kt")
        assertTrue("OEMWarningScreen muss scrollbar sein", oem.contains("verticalScroll("))
        assertEquals(
            "OEMWarningScreen darf in der scrollbaren Column keinen Restplatz mehr verteilen.",
            emptyList<String>(),
            oem.lines().filter { it.contains(".weight(") }
        )
    }
}
