package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components.hue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Befund A (Pruefrunde 7): Die lokale Netzwerkberechtigung wurde nur an EINER Stelle angefordert.
 *
 * Das Manifest deklariert `ACCESS_LOCAL_NETWORK`, die App laeuft mit targetSdk 37 in die
 * Erzwingung hinein - aber nur der Hue-Tab kannte die Berechtigung, und auch dort nur fuer drei
 * seiner fuenf netzbeduerftigen Knoepfe. Ungegatet waren: der Lampentest (zweimal in derselben
 * Datei), Verbindungspruefung und Lampentest in den Hue-Einstellungen, der Regeltest je Regelkarte
 * und der Regeltest im Regel-Editor. Fuer den Nutzer scheitert eine solche Aktion ab Android 17
 * mit einer generischen Netzwerkmeldung - und der Systemdialog, der das Problem loesen wuerde,
 * erscheint NIE. Die Ursache ist in der App nirgends sichtbar.
 *
 * Eine Pruefung je Aufrufstelle haette denselben Fehler beim naechsten neuen Knopf wieder
 * erlaubt. Deshalb pruefen diese Tests nicht einzelne Knoepfe, sondern die STRUKTUR: Jeder Aufruf
 * einer netzbeduerftigen ViewModel-Methode aus einem Hue-Bildschirm muss innerhalb des Blocks von
 * `rememberLocalNetworkPermissionGate` stehen. Ein neuer Knopf kann die Berechtigung damit nicht
 * mehr vergessen, ohne dass dieser Test faellt.
 */
class LocalNetworkPermissionGateTest {

    /** ViewModel-Methoden, die ohne lokalen Netzwerkzugriff nicht funktionieren koennen. */
    private val netzbeduerftigeAufrufe = listOf(
        "hueViewModel.runLightTest(",
        "hueViewModel.validateBridgeConnection(",
        "hueViewModel.testRuleExecution(",
        "hueViewModel.discoverBridges(",
        "hueViewModel.setupBridge("
    )

    private val hueBildschirme = listOf(
        "ui/screens/tabs/HueTabContent.kt",
        "ui/screens/hue/HueSettingsScreen.kt",
        "ui/screens/hue/HueRuleConfigScreen.kt"
    )

    // =================================================================================
    // Die reine Entscheidung
    // =================================================================================

    @Test
    fun `unterhalb von API 37 wird kein Dialog angestossen`() {
        // Dort gibt es die Berechtigung gar nicht. Ein Launcher-Aufruf lieferte sofort
        // "verweigert" - die Aktion faende nie statt, auf JEDEM Geraet vor Android 17.
        assertFalse(braucheLokaleNetzwerkFreigabe(sdkInt = 34, istErteilt = false))
        assertFalse(braucheLokaleNetzwerkFreigabe(sdkInt = 36, istErteilt = false))
    }

    @Test
    fun `ab API 37 ohne Erteilung braucht es den Dialog`() {
        assertTrue(braucheLokaleNetzwerkFreigabe(sdkInt = 37, istErteilt = false))
        assertTrue(braucheLokaleNetzwerkFreigabe(sdkInt = 40, istErteilt = false))
    }

    @Test
    fun `eine bereits erteilte Berechtigung fragt nicht erneut`() {
        assertFalse(braucheLokaleNetzwerkFreigabe(sdkInt = 37, istErteilt = true))
    }

    @Test
    fun `die Schwelle steht bei 37 - die Berechtigung existiert erst dort`() {
        assertEquals(37, SDK_MIT_LOKALER_NETZWERKFREIGABE)
    }

    // =================================================================================
    // Die Struktur: eine gemeinsame Stelle, die ein neuer Knopf nicht vergessen kann
    // =================================================================================

    @Test
    fun `jeder Hue-Bildschirm benutzt das gemeinsame Tor`() {
        hueBildschirme.forEach { pfad ->
            val quelle = quelldatei(pfad).readText()
            assertTrue(
                "$pfad ruft netzbeduerftige Aktionen auf und muss deshalb " +
                    "rememberLocalNetworkPermissionGate benutzen",
                quelle.contains(TOR_AUFRUF)
            )
        }
    }

    @Test
    fun `kein netzbeduerftiger Aufruf steht ausserhalb des Tors`() {
        hueBildschirme.forEach { pfad ->
            val quelle = quelldatei(pfad).readText()
            val tor = torBlockBereich(quelle, pfad)

            netzbeduerftigeAufrufe.forEach { aufruf ->
                var ab = quelle.indexOf(aufruf)
                while (ab >= 0) {
                    assertTrue(
                        "$pfad: '$aufruf' bei Zeichen $ab steht ausserhalb des Berechtigungstors " +
                            "(${tor.first}..${tor.last}). Ab Android 17 scheitert dieser Aufruf " +
                            "mit einer generischen Netzwerkmeldung, ohne dass je der Systemdialog " +
                            "erscheint.",
                        ab in tor
                    )
                    ab = quelle.indexOf(aufruf, ab + 1)
                }
            }
        }
    }

    @Test
    fun `mindestens ein netzbeduerftiger Aufruf steht je Bildschirm im Tor`() {
        // Sonst wuerde der Test oben auch dann gruen, wenn jemand alle Aufrufe wegrefactort
        // (oder das Tor leer laesst) - dann prueft er nichts mehr.
        hueBildschirme.forEach { pfad ->
            val quelle = quelldatei(pfad).readText()
            val tor = torBlockBereich(quelle, pfad)
            val gefunden = netzbeduerftigeAufrufe.count { quelle.indexOf(it) in tor }
            assertTrue("$pfad: im Tor steht kein einziger netzbeduerftiger Aufruf", gefunden > 0)
        }
    }

    @Test
    fun `das Tor fordert genau die im Manifest deklarierte Berechtigung an`() {
        val gate = quelldatei("ui/components/hue/LocalNetworkPermissionGate.kt").readText()
        assertTrue(
            "Der Berechtigungsname muss woertlich dem Manifest entsprechen",
            gate.contains("\"android.permission.ACCESS_LOCAL_NETWORK\"")
        )
        val manifest = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
            .firstOrNull { it.exists() }
            ?: error("AndroidManifest.xml nicht gefunden (Arbeitsverzeichnis ${File(".").absolutePath})")
        assertTrue(
            "Ohne Manifest-Deklaration kann der Dialog nie erscheinen",
            manifest.readText().contains("android.permission.ACCESS_LOCAL_NETWORK")
        )
    }

    // =================================================================================
    // Hilfen
    // =================================================================================

    /**
     * Liefert den Zeichenbereich des Blocks, den `rememberLocalNetworkPermissionGate` als
     * abschliessendes Lambda bekommt - also die eine Stelle, an der die Aktionen ausgefuehrt
     * werden duerfen. Klammerzaehlung genuegt: Auch die geschweiften Klammern einer
     * String-Einsetzung sind paarweise.
     */
    private fun torBlockBereich(quelle: String, pfad: String): IntRange {
        // Bewusst der AUFRUF mit Typargument und nicht der blosse Name: Der steht auch in der
        // Import-Zeile ganz oben, und von dort aus fand die Klammerzaehlung den Rumpf der
        // Composable-Funktion - der "Torblock" war damit die ganze Datei und der Test wertlos.
        // (Genau das hat die Mutationsprobe aufgedeckt.)
        val aufruf = quelle.indexOf(TOR_AUFRUF)
        require(aufruf >= 0) { "$pfad benutzt das Berechtigungstor nicht" }
        val lambdaStart = quelle.indexOf(") {", aufruf)
        require(lambdaStart >= 0) { "$pfad: abschliessendes Lambda des Tors nicht gefunden" }
        var tiefe = 0
        var i = lambdaStart + 2
        while (i < quelle.length) {
            when (quelle[i]) {
                '{' -> tiefe++
                '}' -> {
                    tiefe--
                    if (tiefe == 0) return lambdaStart..i
                }
            }
            i++
        }
        error("$pfad: Der Block des Berechtigungstors ist nicht geschlossen")
    }

    private companion object {
        /** Die Aufrufstelle des Tors - NICHT der blosse Name, den auch die Import-Zeile traegt. */
        const val TOR_AUFRUF = "= rememberLocalNetworkPermissionGate<"
    }

    /** Findet eine Produktivquelle unabhaengig davon, ob Gradle im Modul- oder Repo-Ordner startet. */
    private fun quelldatei(relativZumPaket: String): File {
        val paket = "src/main/java/com/github/f1rlefanz/cf_alarmfortimeoffice/$relativZumPaket"
        return listOf(File(paket), File("app/$paket")).firstOrNull { it.exists() }
            ?: error("Quelldatei nicht gefunden: $paket (Arbeitsverzeichnis ${File(".").absolutePath})")
    }
}
