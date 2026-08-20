package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Haelt fest, dass kein bleibender Hinweis den gemeinsamen Meldungskanal blockiert.
 *
 * DER FEHLER, DER DAZU GEFUEHRT HAT: Der Hinweis "die Wecker des abgewaehlten Kalenders liessen
 * sich nicht entfernen" lief als Snackbar mit `SnackbarDuration.Indefinite` auf dem GEMEINSAMEN
 * `SnackbarHostState` von `MainContentScreen` - dem einzigen Indefinite-Aufruf der App.
 * `SnackbarHostState.showSnackbar` serialisiert ueber einen Mutex: solange diese eine Snackbar
 * stand (und sie ging ausschliesslich per Aktion oder Wischen weg), suspendierten ALLE uebrigen
 * Snackbar-Kanaele desselben Hosts - Kalender-, Schicht-, Wecker- und Auth-Fehler. Deren
 * `clearError()`-Aufrufe stehen hinter dem `showSnackbar` und liefen damit ebenfalls nicht: die
 * Fehler blieben ungeleert im State stehen und wurden auch spaeter nicht mehr gemeldet. Ein
 * einziger bleibender Hinweis legte so die gesamte Fehlermeldung der App still.
 *
 * DIE LOESUNG, die dieser Test festnagelt: Bleibende ZUSTAENDE zeigt diese App als Karte im
 * Status-Tab (Kalender-Teilerfolg, fehlende Berechtigungen, Akku-Ausnahme, Dimmer-Dienst) -
 * fluechtige EREIGNISSE als Snackbar. Der Hinweis auf verwaiste Wecker ist ein Zustand und
 * gehoert deshalb in eine Karte.
 *
 * Geprueft wird der Quelltext, weil die Wirkung nur am laufenden Compose-Host sichtbar wird - und
 * dort erst dann, wenn zwei Meldungen zusammentreffen, also genau im seltenen Fall.
 */
class Pruefrunde8BleibenderHinweisBlockiertNichtTest {

    private fun quelle(relativerPfad: String): String {
        val basis = listOf(File("app/src/main/java"), File("src/main/java"))
            .firstOrNull { it.isDirectory }
            ?: error("Quellverzeichnis nicht gefunden (Arbeitsverzeichnis ${File(".").absolutePath})")
        val datei = File(basis, "com/github/f1rlefanz/cf_alarmfortimeoffice/$relativerPfad")
        require(datei.isFile) { "Quelldatei nicht gefunden: ${datei.absolutePath}" }
        return datei.readText()
    }

    /**
     * Nur der Code, ohne Kommentarzeilen: die Kommentare an beiden Stellen benennen den
     * abgeschafften Mechanismus absichtlich beim Namen ("stand hier eine Snackbar mit
     * SnackbarDuration.Indefinite"). Wer darauf prueft, prueft den Kommentar statt der Wirkung.
     */
    private fun code(relativerPfad: String): String =
        quelle(relativerPfad).lines().filterNot { it.trimStart().startsWith("//") }
            .joinToString(System.lineSeparator())

    private val mainContentScreen by lazy { code("ui/screens/MainContentScreen.kt") }
    private val statusTab by lazy { code("ui/screens/tabs/StatusTabContent.kt") }

    @Test
    fun `keine Snackbar dieses Bildschirms bleibt unbegrenzt stehen`() {
        assertFalse(
            "MainContentScreen darf keine Snackbar mit SnackbarDuration.Indefinite zeigen: sie " +
                "haelt den Mutex des gemeinsamen SnackbarHostState, bis der Nutzer sie wegtippt " +
                "oder wegwischt - solange erreicht ihn KEINE andere Meldung mehr, und deren " +
                "clearError() laeuft ebenfalls nicht.",
            mainContentScreen.contains("SnackbarDuration.Indefinite")
        )
    }

    @Test
    fun `der Hinweis auf verwaiste Wecker steht stattdessen als Karte im Status-Tab`() {
        // Er darf nicht einfach verschwinden: bis zu 14 Tage lang koennen Wecker eines
        // entfernten Dienstplans klingeln. Der Zustand muss bleibend sichtbar sein - nur eben
        // dort, wo diese App bleibende Zustaende zeigt.
        assertTrue(
            "Der Status-Tab muss den Text des gescheiterten Aufraeumens zeigen",
            statusTab.contains("DESELECTION_CLEANUP_FAILED_MESSAGE")
        )
        assertTrue(
            "Ohne den Knopf \"Erneut versuchen\" ist die Karte eine Sackgasse - der zweite " +
                "Anlauf ist der einzige Weg, der die verwaisten Wecker wirklich raeumt.",
            statusTab.contains("DESELECTION_CLEANUP_RETRY_ACTION")
        )
        assertFalse(
            "Zwei Fassungen desselben Hinweises waeren eine zu viel - MainContentScreen darf " +
                "ihn nicht zusaetzlich melden.",
            mainContentScreen.contains("DESELECTION_CLEANUP_FAILED_MESSAGE")
        )
    }
}
