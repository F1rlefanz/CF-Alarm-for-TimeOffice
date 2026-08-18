package com.github.f1rlefanz.cf_alarmfortimeoffice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Haelt die drei R8-Entscheidungen fest, die man nicht am Build sieht, sondern erst am ersten
 * Fehlerbericht eines Alpha-Testers.
 *
 * DER ABLAUF, DER DAZU GEFUEHRT HAT: Bis v1.27.0 standen in `proguard-rules.pro` zwei Regeln der
 * Form `-keep class * { ... }`. Die Klassenspezifikation `*` macht JEDE Klasse zur Keep-Wurzel -
 * R8 hat deshalb seit dem Einschalten von Minify nichts entfernt und nichts umbenannt. Die
 * Korrektur auf `-keepclasseswithmembers`/`-keepclassmembers` nimmt diese Wurzelwirkung weg und
 * haette damit als Nebenwirkung ZUM ERSTEN MAL echte Obfuskation eingeschaltet.
 *
 * Das kollidiert mit der einzigen Diagnosequelle dieser App: `last_crash.txt` und die WARN/ERROR-
 * Zeilen, die ein Tester per "Logs senden" schickt. Die Datei haelt eigens `SourceFile` und
 * `LineNumberTable` dafuer - die Zeilennummern blieben also, Klassen- und Methodennamen nicht,
 * und eine mapping.txt zum Zurueckuebersetzen wird nirgends archiviert. Deshalb `-dontobfuscate`:
 * Shrinking ja (das ist der Groessengewinn), Umbenennen nein.
 *
 * Geprueft wird die Regeldatei selbst, weil die Wirkung erst im Release-Artefakt sichtbar wird -
 * und dort niemand hinsieht, bevor es zu spaet ist.
 */
class Pruefrunde6R8RegelnTest {

    private val regeln: List<String> by lazy {
        val kandidaten = listOf(File("proguard-rules.pro"), File("app/proguard-rules.pro"))
        val datei = kandidaten.firstOrNull { it.isFile }
            ?: error("proguard-rules.pro nicht gefunden (Arbeitsverzeichnis ${File(".").absolutePath})")
        datei.readLines().map { it.trim() }
    }

    /** Eine Direktive gilt nur, wenn sie nicht auskommentiert ist. */
    private fun istAktiv(direktive: String): Boolean =
        regeln.any { it == direktive || it.startsWith("$direktive ") }

    @Test
    fun `Umbenennung bleibt aus, solange keine mapping-Datei archiviert wird`() {
        assertTrue(
            "Ohne '-dontobfuscate' benennt R8 den gesamten App-Code um. Das erste " +
                "Absturzprotokoll eines Testers enthielte dann nur noch a.b.c(SourceFile:412), " +
                "und es gibt keine archivierte mapping.txt, mit der sich das zurueckuebersetzen " +
                "liesse. Diese Zeile darf erst weg, wenn die mapping.txt je Release gesichert " +
                "wird UND ein Release-Build am Geraet durchgespielt wurde.",
            istAktiv("-dontobfuscate")
        )
    }

    @Test
    fun `Shrinking und Optimierung bleiben eingeschaltet`() {
        // Die Gegenprobe: `-dontobfuscate` darf nicht zum Einfallstor werden, die beiden anderen
        // gleich mit abzuschalten - dann waere isMinifyEnabled=true wieder eine Attrappe.
        assertFalse("-dontshrink macht isMinifyEnabled=true zur Attrappe", istAktiv("-dontshrink"))
        assertFalse("-dontoptimize war 'temporarily disabled' und bleibt aus", istAktiv("-dontoptimize"))
    }

    @Test
    fun `keine Regel macht wieder jede Klasse zur Keep-Wurzel`() {
        // Genau die Form, die R8 zwischen dem 10.08. und 18.08.2026 wirkungslos gemacht hat:
        // `-keep class * {` (mit oder ohne Modifikatoren wie ,allowobfuscation). Gemeint war in
        // beiden Faellen "nur Klassen, die diese Member HABEN" - dafuer gibt es
        // -keepclasseswithmembers bzw. -keepclassmembers.
        val wurzelRegeln = regeln.filter {
            Regex("^-keep(,[a-z]+)* +class +\\* *\\{").containsMatchIn(it)
        }
        assertTrue(
            "Diese Regeln machen JEDE Klasse zur Shrink-Wurzel und schalten R8 damit " +
                "unbemerkt ab: $wurzelRegeln",
            wurzelRegeln.isEmpty()
        )
    }
}
