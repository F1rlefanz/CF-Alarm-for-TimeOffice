package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * QUELLTEXT-TEST (ohne Robolectric ist Compose hier nicht ausfuehrbar): der EventListScreen muss
 * einen Kalenderfehler selbst anzeigen, bevor er ihn leert. `MainContentScreen` mit der einzigen
 * anderen Snackbar dafuer ist in diesem Zustand nicht komponiert - ohne eigene Anzeige verschwand
 * ein Fehler bei "Aktualisieren"/"Weitere Events laden" nach 3 s ungesehen.
 */
class EventListFehleranzeigeTest {

    private val code: String =
        File("src/main/java/com/github/f1rlefanz/cf_alarmfortimeoffice/ui/screens/EventListScreen.kt")
            .readText()
            .lines()
            .filterNot { it.trimStart().startsWith("//") }
            .filterNot { it.trimStart().startsWith("*") }
            .filterNot { it.trimStart().startsWith("/*") }
            .joinToString("\n")

    @Test
    fun `Kalenderfehler wird angezeigt, bevor er geleert wird`() {
        assertTrue("Scaffold ohne SnackbarHost", code.contains("snackbarHost = { SnackbarHost("))
        val zeigen = code.indexOf("showSnackbar(")
        val leeren = code.indexOf("calendarViewModel.clearError()")
        assertTrue("Fehler wird nie gezeigt", zeigen >= 0)
        assertTrue("clearError() vor der Anzeige", leeren > zeigen)
    }
}
