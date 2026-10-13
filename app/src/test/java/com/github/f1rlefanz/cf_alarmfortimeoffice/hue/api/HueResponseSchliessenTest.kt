package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.api

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Befund B (Pruefrunde 7): OkHttp-Antworten wurden auf dem Nicht-2xx-Pfad nie geschlossen.
 *
 * Auf dem Erfolgspfad schliesst `response.body.string()` die Antwort selbst - auf dem Fehlerpfad
 * las hier aber NIEMAND den Body: `HueApiClient` protokollierte nur Code und Meldung,
 * `discoverBridgesOnline` und `HueNUpnpDiscoveryService` warfen sofort. Eine ungelesene Antwort
 * haelt ihre Verbindung im Pool fest, bis der Garbage Collector sie einsammelt. Der Schaden ist
 * durch OkHttps GC-Netz begrenzt - der Fix ist billig, und genau dieser Pfad wird bei einer
 * zickenden Bridge oft durchlaufen (jede 6h-Wartung, jeder Weckvorgang).
 *
 * Geprueft wird strukturell, nicht ueber einen echten Server: Ein Test mit MockWebServer braeuchte
 * eine neue Testabhaengigkeit, und die Zusicherung ist ohnehin eine Schreibweise - JEDE
 * `execute()`-Stelle im Hue-Netzpfad fuehrt ihre Antwort ueber `use { }`, ebenso jedes
 * `onResponse` eines `enqueue`-Callbacks (seit #138 der Weg des HueApiClient).
 */
class HueResponseSchliessenTest {

    private val netzquellen = listOf(
        "hue/api/HueApiClient.kt",
        "hue/discovery/HueNUpnpDiscoveryService.kt"
    )

    @Test
    fun `jede execute-Stelle fuehrt ihre Antwort ueber use`() {
        netzquellen.forEach { pfad ->
            val quelle = quelldatei(pfad).readText()
            var ab = quelle.indexOf(".execute()")
            var gefunden = 0
            while (ab >= 0) {
                gefunden++
                val danach = quelle.substring(ab + ".execute()".length)
                assertTrue(
                    "$pfad: Die Antwort bei Zeichen $ab wird nicht ueber use { } gefuehrt - auf " +
                        "dem Fehlerpfad liest niemand den Body, die Verbindung bleibt bis zum GC " +
                        "belegt. Gefunden: ${danach.take(40)}",
                    danach.startsWith(".use {") || danach.startsWith(".use{")
                )
                ab = quelle.indexOf(".execute()", ab + 1)
            }
            // Der abbrechbare Weg (#138, HueApiClient): `enqueue` mit Callback. Dort muss jede
            // `onResponse` ihre Antwort ueber `response.use` fuehren, bevor die naechste
            // Funktion beginnt - sonst bleibt auch eine NACH dem Abbruch eintreffende Antwort offen.
            var cb = quelle.indexOf("override fun onResponse(")
            while (cb >= 0) {
                gefunden++
                val rumpf = quelle.substring(cb + 1).let { r ->
                    val ende = r.indexOf("override fun ").takeIf { it >= 0 } ?: r.length
                    r.substring(0, ende)
                }
                assertTrue(
                    "$pfad: onResponse bei Zeichen $cb fuehrt die Antwort nicht ueber response.use { }",
                    rumpf.contains("response.use {") || rumpf.contains("response.use{")
                )
                cb = quelle.indexOf("override fun onResponse(", cb + 1)
            }
            assertTrue("$pfad enthaelt keinen HTTP-Aufruf mehr - Test prueft nichts", gefunden > 0)
        }
    }

    /** Findet eine Produktivquelle unabhaengig davon, ob Gradle im Modul- oder Repo-Ordner startet. */
    private fun quelldatei(relativZumPaket: String): File {
        val paket = "src/main/java/com/github/f1rlefanz/cf_alarmfortimeoffice/$relativZumPaket"
        return listOf(File(paket), File("app/$paket")).firstOrNull { it.exists() }
            ?: error("Quelldatei nicht gefunden: $paket (Arbeitsverzeichnis ${File(".").absolutePath})")
    }
}
