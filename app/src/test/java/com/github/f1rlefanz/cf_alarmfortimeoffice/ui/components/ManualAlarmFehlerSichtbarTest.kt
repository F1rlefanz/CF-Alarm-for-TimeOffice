package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * DIE MELDUNG MUSS IN GENAU DEM ZUSTAND ERSCHEINEN, FUER DEN SIE GESCHRIEBEN WURDE.
 *
 * `ManualAlarmCard` teilt sich in zwei Zweige: "Manueller Alarm aktiv" (Name, Uhrzeit, Loeschen)
 * und die Anlege-Oberflaeche. Der Fehlerteil stand nur im ZWEITEN - also ausgerechnet dort, wo kein
 * manueller Wecker existiert. Unsichtbar blieben damit die beiden Meldungen, die genau im ersten
 * Zustand entstehen:
 *
 * - "Der Wecker ist gestellt und klingelt - aber er liess sich nicht dauerhaft speichern": wird
 *   gesetzt, NACHDEM der Wecker im Bestand liegt. Die Karte sagte daneben unbeirrt
 *   "Manueller Alarm aktiv - Alarm: 05:00", und der Nutzer erfuhr nie, dass sein Wecker den
 *   naechsten Neustart nicht ueberlebt.
 * - Ein fehlgeschlagener Loeschversuch: der Wecker bleibt bestehen, der Knopf federt zurueck,
 *   niemand sagt warum.
 *
 * Geprueft wird deshalb die STRUKTUR und nicht ein einzelner Text: eine Compose-Oberflaeche laesst
 * sich in diesem Modul nicht rendern (kein Robolectric, keine Compose-Test-Regel), aber die Frage
 * "steht die Fehleranzeige in BEIDEN Zweigen?" ist am Quelltext eindeutig zu beantworten - und ein
 * neuer dritter Zweig faellt hier ebenfalls auf.
 */
class ManualAlarmFehlerSichtbarTest {

    private val quelle: String by lazy { quelldatei("ui/components/ManualAlarmCard.kt").readText() }

    @Test
    fun `der Zweig mit aktivem Wecker zeigt den Fehlerteil`() {
        // OHNE DEN FIX faellt genau dieser Test: der Aufruf stand ausschliesslich im else-Zweig.
        assertTrue(
            "Die Warnung 'nicht dauerhaft gespeichert' entsteht, waehrend ein manueller Wecker " +
                "existiert - ohne Fehlerteil in diesem Zweig ist sie unsichtbar",
            aktivZweig().contains(FEHLER_AUFRUF)
        )
    }

    @Test
    fun `der Anlege-Zweig zeigt den Fehlerteil weiterhin`() {
        // Gegenprobe: das Verschieben darf den urspruenglichen Ort nicht leerraeumen - dort
        // erscheinen die Meldungen des Anlegens (weggefallene Schicht, nicht stellbarer Wecker).
        assertTrue(anlegeZweig().contains(FEHLER_AUFRUF))
    }

    @Test
    fun `der Fehlerteil existiert genau einmal als Baustein`() {
        // Zwei Kopien waeren die Rueckkehr der Falle in neuer Form: die naechste Aenderung landet
        // in einer davon.
        assertTrue(
            "Der Fehlerteil gehoert in EINEN privaten Composable, den beide Zweige aufrufen",
            quelle.split("private fun $FEHLER_BAUSTEIN(").size == 2
        )
    }

    // =================================================================================
    // Quelltext-Zerlegung
    // =================================================================================

    /** Der Rumpf von `if (manualAlarmState.hasActiveManualAlarm) { … }`. */
    private fun aktivZweig(): String {
        val start = quelle.indexOf(VERZWEIGUNG)
        require(start >= 0) { "Die Verzweigung der Karte wurde umbenannt: $VERZWEIGUNG" }
        val block = blockAb(quelle.indexOf('{', start))
        return quelle.substring(block.first, block.last + 1)
    }

    /** Der Rumpf des zugehoerigen `else { … }`. */
    private fun anlegeZweig(): String {
        val start = quelle.indexOf(VERZWEIGUNG)
        val aktiv = blockAb(quelle.indexOf('{', start))
        val elseWort = quelle.indexOf("else", aktiv.last)
        require(elseWort in 0..(aktiv.last + 10)) { "Auf den Aktiv-Zweig folgt kein else-Zweig" }
        val block = blockAb(quelle.indexOf('{', elseWort))
        return quelle.substring(block.first, block.last + 1)
    }

    /** Klammer-Bilanz ab der oeffnenden Klammer bei [oeffnend]. */
    private fun blockAb(oeffnend: Int): IntRange {
        require(oeffnend >= 0) { "Keine oeffnende Klammer gefunden" }
        var tiefe = 0
        var i = oeffnend
        while (i < quelle.length) {
            when (quelle[i]) {
                '{' -> tiefe++
                '}' -> {
                    tiefe--
                    if (tiefe == 0) return oeffnend..i
                }
            }
            i++
        }
        error("Der Block ab Position $oeffnend ist nicht geschlossen")
    }

    /** Findet die Produktivquelle unabhaengig davon, ob Gradle im Modul- oder Repo-Ordner startet. */
    private fun quelldatei(relativZumPaket: String): File {
        val paket = "src/main/java/com/github/f1rlefanz/cf_alarmfortimeoffice/$relativZumPaket"
        return listOf(File(paket), File("app/$paket")).firstOrNull { it.exists() }
            ?: error("Quelldatei nicht gefunden: $paket (Arbeitsverzeichnis ${File(".").absolutePath})")
    }

    private companion object {
        const val VERZWEIGUNG = "if (manualAlarmState.hasActiveManualAlarm) {"
        const val FEHLER_BAUSTEIN = "ManualAlarmFehlerHinweis"
        const val FEHLER_AUFRUF = "$FEHLER_BAUSTEIN("
    }
}
