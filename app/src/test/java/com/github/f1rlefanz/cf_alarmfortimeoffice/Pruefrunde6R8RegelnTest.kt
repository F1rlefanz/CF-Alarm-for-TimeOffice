package com.github.f1rlefanz.cf_alarmfortimeoffice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Haelt die R8-Entscheidungen fest, die man nicht am Build sieht, sondern erst am ersten
 * Fehlerbericht eines Alpha-Testers - oder an der Ablehnung durch Play.
 *
 * DER ABLAUF, DER DAZU GEFUEHRT HAT: Bis v1.27.0 standen in `proguard-rules.pro` zwei Regeln der
 * Form `-keep class * { ... }`. Die Klassenspezifikation `*` macht JEDE Klasse zur Keep-Wurzel -
 * R8 hat deshalb seit dem Einschalten von Minify nichts entfernt und nichts umbenannt. Mit der
 * Korrektur kam `-dontobfuscate` dazu, weil es damals keine archivierte mapping.txt gab.
 *
 * SEIT ISSUE #54 IST ES UMGEKEHRT: Play verlangt ab Februar 2027 bei mehr als 10 MB DEX je
 * mindestens 25 % Obfuskation, Optimierung und Shrinking; mit `-dontobfuscate` stand die
 * Obfuskation bei 0,01 % (gemessen 01.10.2026, DEX 10,1 MB). Die mapping.txt ist inzwischen
 * doppelt gesichert (im Bundle eingebettet + CI-Artefakt), zurueckuebersetzt wird mit R8-Retrace.
 * Umbenennen ist damit Pflicht - und jede Regel, die einen zur Laufzeit per NAMEN gesuchten
 * Bestandteil haelt, wird tragend.
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
    fun `Umbenennung ist an - Play verlangt sie ab 10 MB DEX`() {
        assertFalse(
            "'-dontobfuscate' druckt die Obfuskation auf 0 %. Play lehnt ab Februar 2027 ein " +
                "Bundle mit mehr als 10 MB DEX und weniger als 25 % Obfuskation ab (Issue #54). " +
                "Die mapping.txt ist im Bundle eingebettet und als CI-Artefakt gesichert; " +
                "zurueckuebersetzt wird mit R8-Retrace (Befehl in proguard-rules.pro).",
            istAktiv("-dontobfuscate")
        )
    }

    @Test
    fun `Zeilennummern bleiben im Stacktrace - ohne sie hilft auch Retrace nicht`() {
        // Retrace bildet umbenannte Namen zurueck, aber die Zeile kann es nur aufloesen, wenn
        // LineNumberTable erhalten ist. SourceFile haelt `(SourceFile:412)` statt `(Unknown Source)`.
        val attribute = regeln
            .filter { it.startsWith("-keepattributes ") }
            .flatMap { it.removePrefix("-keepattributes ").split(',').map(String::trim) }
        assertTrue("SourceFile fehlt in -keepattributes", "SourceFile" in attribute)
        assertTrue("LineNumberTable fehlt in -keepattributes", "LineNumberTable" in attribute)
    }

    @Test
    fun `Regeln fuer per Namen gesuchte Bestandteile stehen weiter`() {
        // Ohne -dontobfuscate halten NUR noch diese Regeln die Namen, die zur Laufzeit gesucht
        // werden. Faellt eine, bricht der Release-Build still - kein Unit-Test sieht das.
        val tragend = mapOf(
            // Gson: Feldname = JSON-Schluessel der Hue-Antworten.
            "-keep class com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.** { *; }" to
                "Hue-Antworten kaemen leer an (Gson liest Feldnamen)",
            // WorkManager speichert den Klassennamen des Workers in seiner Datenbank.
            "-keepnames class * extends androidx.work.ListenableWorker" to
                "eingeplante Worker liessen sich nach einem Update nicht mehr finden"
        )
        for ((regel, folge) in tragend) {
            assertTrue("Regel fehlt: '$regel' - $folge", regel in regeln)
        }

        // google-http-client: `@Key` ohne Wert nimmt den Feldnamen als JSON-Schluessel. Die
        // Member-Zeile muss in einem -keepclassmembers OHNE allowobfuscation stehen.
        val keyZeile = regeln.indexOf("@com.google.api.client.util.Key <fields>;")
        assertTrue(
            "Kalender-Events kaemen leer an: keine Keep-Regel fuer @Key-Felder",
            keyZeile > 0 && regeln[keyZeile - 1] == "-keepclassmembers class * {"
        )
    }

    @Test
    fun `Shrinking und Optimierung bleiben eingeschaltet`() {
        // Alle drei R8-Stufen zaehlen fuer Play einzeln (je >= 25 %), und mit einer dieser
        // Zeilen waere isMinifyEnabled=true wieder eine Attrappe.
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
