package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Haelt fest, WANN die Wecker-Tagesliste neu rechnet (Review #139).
 *
 * DER FEHLER: Die Liste hing per `remember` nur an Bestand, freien Tagen, Skip-Lage und Wirkungen.
 * Klingelte der „nächste“ Wecker und wurde beendet, blieb er bis zum Sync im Bestand - und in der
 * Liste mit dem Knopf „Nicht wecken“. Ein Tipp darauf lehnt `AlarmViewModel.skipNextAlarm()` ab und
 * erneuert dabei NUR `nextRingingAlarmTriggerTime` (dass das Feld sich aendert, haelt
 * `UeberspringenZielVerstrichenTest` fest). Ohne dieses Feld als Schluessel rechnete die Liste nicht
 * neu: jeder weitere Tipp wurde wieder abgelehnt, und der Hinweis „Die Liste zeigt jetzt den
 * nächsten Wecker“ stimmte nicht.
 *
 * Geprueft wird der Quelltext, weil die Wirkung nur in einer laufenden Komposition sichtbar ist.
 */
class TageslisteNeuberechnungTest {

    private fun code(relativerPfad: String): String {
        val basis = listOf(File("app/src/main/java"), File("src/main/java"))
            .firstOrNull { it.isDirectory }
            ?: error("Quellverzeichnis nicht gefunden (Arbeitsverzeichnis ${File(".").absolutePath})")
        val datei = File(basis, "com/github/f1rlefanz/cf_alarmfortimeoffice/$relativerPfad")
        require(datei.isFile) { "Quelldatei nicht gefunden: ${datei.absolutePath}" }
        // Ohne Kommentarzeilen: der Kommentar nennt die Schluessel absichtlich beim Namen.
        return datei.readLines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
    }

    /** Die Schluesselliste des `remember`, das `baueWeckerTage` aufruft. */
    private fun schluesselDerTagesliste(): String {
        val quelle = code("ui/screens/tabs/WeckerTabContent.kt")
        val aufruf = quelle.indexOf("baueWeckerTage(")
        require(aufruf >= 0) { "baueWeckerTage-Aufruf nicht gefunden" }
        val remember = quelle.lastIndexOf("remember(", aufruf)
        require(remember >= 0) { "remember vor baueWeckerTage nicht gefunden" }
        return quelle.substring(remember, aufruf)
    }

    @Test
    fun `die Liste rechnet neu, wenn das ViewModel den naechsten Wecker erneuert`() {
        assertTrue(
            "Ohne nextRingingAlarmTriggerTime als Schluessel bleibt nach einem abgelehnten " +
                "„Nicht wecken“ der verstrichene Wecker mit dem Knopf stehen.",
            schluesselDerTagesliste().contains("alarmState.nextRingingAlarmTriggerTime")
        )
    }

    @Test
    fun `die Liste rechnet neu, wenn der Nutzer in die App zurueckkehrt`() {
        val quelle = code("ui/screens/tabs/WeckerTabContent.kt")
        val variable = Regex("""val (\w+) = uhrBeimFortsetzen\(\)""").find(quelle)?.groupValues?.get(1)
        require(variable != null) { "uhrBeimFortsetzen() wird nicht gelesen" }
        assertTrue(
            "Der Zeitpunkt des letzten Fortsetzens muss Schluessel der Liste sein.",
            Regex("""\b$variable\b""").containsMatchIn(schluesselDerTagesliste())
        )
    }
}
