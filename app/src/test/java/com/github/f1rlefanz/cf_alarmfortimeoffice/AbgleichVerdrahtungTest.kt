package com.github.f1rlefanz.cf_alarmfortimeoffice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Die VERDRAHTUNG der Abgleich-Korrekturen aus 1.48.0 - die reinen Funktionen dahinter
 * (`WartungsKettenPlanung.zielTermin`, `CalendarViewModel.sollBeiRueckkehrLaden`) sind eigens
 * getestet, aber ein Umbau, der ihre Aufrufstellen verliert, liesse alle diese Tests gruen.
 *
 * Befund 12.10.2026 (Fairphone): Ein Dienst stand ab 15:52 bei Google, die App zeigte ihn erst um
 * 19:25. Zwei Ursachen lagen in der App: (1) jedes Oeffnen schob den faelligen 6h-Lauf auf
 * "jetzt + 6 h" - der 20:17-Lauf kam nie; (2) eine Rueckkehr in die laufende App lud nichts.
 * Beides haengt an genau den Stellen, die dieser Test festhaelt (Review 1.48.0).
 *
 * Quelltext-Pruefung im Stil von `EinstiegIntentTest`: diese Stellen leben in einer Activity und in
 * Compose-Effekten, die ein JVM-Test nicht ausfuehren kann.
 */
class AbgleichVerdrahtungTest {

    private fun quelltext(pfad: String): String {
        val relativ = "src/main/java/com/github/f1rlefanz/cf_alarmfortimeoffice/$pfad"
        return listOf(File(relativ), File("app/$relativ")).firstOrNull { it.isFile }?.readText()
            ?: error("$relativ nicht gefunden (Arbeitsverzeichnis ${File(".").absolutePath})")
    }

    private fun alleQuellen(): Map<String, String> {
        val wurzel = listOf(
            File("src/main/java"),
            File("app/src/main/java")
        ).first { it.isDirectory }
        return wurzel.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .associate { it.relativeTo(wurzel).invariantSeparatorsPath to it.readText() }
    }

    /** Aufrufe ohne Kommentarzeilen - KDoc nennt `scheduleNext()` oft beim Namen. */
    private fun aufrufzeilen(text: String, muster: Regex): List<String> =
        text.lines()
            .map { it.trim() }
            .filterNot { it.startsWith("*") || it.startsWith("//") || it.startsWith("/*") }
            .filter { muster.containsMatchIn(it) }

    @Test
    fun `MainScreen behaelt an allen drei Eintritten den frueheren Wartungstermin`() {
        val mainScreen = quelltext("ui/screens/MainScreen.kt")
        val aufrufe = aufrufzeilen(mainScreen, Regex("""scheduleNext\("""))

        assertEquals(
            "MainScreen stellt die 6h-Kette an drei Stellen (Eintritt in den Hauptbereich, " +
                "finishOnboarding, GateSchritt.Fertig) - gefunden: $aufrufe",
            3,
            aufrufe.size
        )
        aufrufe.forEach { zeile ->
            assertTrue(
                "Ohne frueherenTerminBehalten = true schiebt jedes Oeffnen der App den faelligen " +
                    "Lauf wieder um 6 h (Befund 12.10.2026): $zeile",
                zeile.contains("frueherenTerminBehalten = true")
            )
        }
    }

    @Test
    fun `nur MainScreen behaelt den Termin - jeder Hintergrund-Planer rechnet ab jetzt`() {
        // Ein Lauf, der gerade stattgefunden hat (Wartungs-finally, Boot, Wachhund, Pause-Ende),
        // plant den naechsten ab JETZT. Behielte er einen alten Termin, verschoebe sich nichts -
        // aber die Unterscheidung "Eintritt in die App" vs. "Lauf" waere verwischt.
        val behalter = alleQuellen()
            .filter { (_, text) ->
                aufrufzeilen(text, Regex("""frueherenTerminBehalten = true""")).isNotEmpty()
            }
            .keys
        assertEquals(
            setOf("com/github/f1rlefanz/cf_alarmfortimeoffice/ui/screens/MainScreen.kt"),
            behalter
        )
    }

    @Test
    fun `MainActivity prueft den Rueckkehr-Abgleich ab STARTED und bei jedem neuen Hintergrund-Abruf`() {
        val activity = quelltext("MainActivity.kt")
        val block = activity.substringAfter("private fun beobachteRueckkehrUndHintergrundAbruf(", "")
            .substringBefore("private fun pruefeRueckkehrAbgleich(")
        assertTrue("beobachteRueckkehrUndHintergrundAbruf fehlt", block.isNotEmpty())
        assertTrue(
            "Der Abgleich gehoert an STARTED (onResume feuert auch nach Berechtigungsdialogen)",
            block.contains("repeatOnLifecycle(Lifecycle.State.STARTED)")
        )
        assertTrue(
            "Ohne die Beobachtung zeigt die Uebersicht nach den Abgleichen von \"TimeOffice öffnen\" " +
                "den alten Stand, solange die App offen bleibt",
            block.contains("AlarmMaintenanceService.beobachteLetztenTerminabruf(")
        )
        assertTrue(block.contains("pruefeRueckkehrAbgleich(wiederhergestellt)"))

        assertTrue(
            "onCreate muss die Beobachtung starten - mit dem Wiederherstellungs-Merker",
            activity.contains("beobachteRueckkehrUndHintergrundAbruf(wiederhergestellt = savedInstanceState != null)")
        )

        val pruefung = activity.substringAfter("private fun pruefeRueckkehrAbgleich(", "")
            .substringBefore("\n    }")
        assertTrue(
            "Nach einem Konfigurationswechsel im Hintergrund ist der Lazy der neuen Activity leer - " +
                "ohne `|| wiederhergestellt` laedt genau diese Rueckkehr nichts",
            pruefung.contains("calendarViewModelLazy.isInitialized() || wiederhergestellt")
        )
        assertTrue(pruefung.contains("calendarViewModel.aktualisiereBeiRueckkehr()"))
    }

    @Test
    fun `der Knopf TimeOffice oeffnen plant die Abgleiche erst nach dem Oeffnen`() {
        val karte = quelltext("ui/screens/tabs/StatusPermissionCards.kt")
        val knopf = karte.substringAfter("val geoeffnet = try {", "").substringBefore("Text(TIMEOFFICE_OEFFNEN)")
        assertTrue("Knopf-Handler nicht gefunden", knopf.isNotEmpty())
        assertTrue(knopf.contains("context.startActivity(startIntent)"))
        assertTrue(knopf.contains("if (geoeffnet) TimeOfficeNachfassAbgleich.plane(context)"))
    }
}
