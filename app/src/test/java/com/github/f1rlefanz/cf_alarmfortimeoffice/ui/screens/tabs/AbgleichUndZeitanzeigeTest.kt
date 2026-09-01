package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Test

/**
 * Sichert die Aufraeumung von v1.38.0 ab: EIN Abgleich-Vorgang mit EINEM Namen, und zwei
 * Zeitwerte, die sagen, was sie wirklich bedeuten.
 *
 * WARUM ES DIESE TESTS BRAUCHT: Vor v1.38.0 loesten DREI verschieden beschriftete Knoepfe
 * denselben Aufruf `refreshData(forceRefresh = true)` aus - "Aktualisieren" (Kopfzeile),
 * "Neu laden" (Cache-Karte) und "Jetzt synchronisieren" (>24h-Warnung). Dazu kamen zwei
 * Bedienelemente, die fuer den Nutzer nichts taten, und ein Text, der einen Vorgang behauptete,
 * dessen Ergebnis niemand einsehen kann. Keiner dieser Widersprueche war von einem Test gedeckt.
 */
class AbgleichUndZeitanzeigeTest {

    private fun quelltext(pfad: String): String =
        File("src/main/java/com/github/f1rlefanz/cf_alarmfortimeoffice/$pfad").readText()

    /**
     * Quelltext ohne Kommentare. KDoc-Zeilen muessen mit weg: die Begruendungen in diesen Dateien
     * nennen die entfernten Methoden absichtlich beim Namen - sonst schlaegt der Test an, weil
     * jemand ordentlich dokumentiert hat, warum etwas NICHT mehr da ist.
     */
    private fun ohneKommentare(pfad: String): String =
        quelltext(pfad).lines()
            .filterNot { it.trimStart().startsWith("//") }
            .filterNot { it.trimStart().startsWith("*") }
            .filterNot { it.trimStart().startsWith("/*") }
            .joinToString("\n")

    // ---------------------------------------------------------------- Zeitabstand

    @Test
    fun `noch nie ausgefuehrt wird als solches benannt`() {
        assertEquals("Noch nie", zeitAbstandInWorten(0L))
        assertEquals("Noch nie", zeitAbstandInWorten(-1L))
    }

    @Test
    fun `ein Zeitpunkt in der Zukunft gilt als unbekannt`() {
        // Kann durch eine Zeitumstellung oder eine korrigierte Systemuhr entstehen. Lieber
        // "Unbekannt" als "Vor -3 Stunden".
        val jetzt = 1_000_000L
        assertEquals("Unbekannt", zeitAbstandInWorten(jetzt + 60_000L, jetzt))
    }

    @Test
    fun `Minuten Stunden und Tage werden richtig gestuft`() {
        val jetzt = TimeUnit.DAYS.toMillis(100)
        assertEquals("Vor 5 Minuten", zeitAbstandInWorten(jetzt - TimeUnit.MINUTES.toMillis(5), jetzt))
        assertEquals("Vor 3 Stunden", zeitAbstandInWorten(jetzt - TimeUnit.HOURS.toMillis(3), jetzt))
        assertEquals("Vor 2 Tagen", zeitAbstandInWorten(jetzt - TimeUnit.DAYS.toMillis(2), jetzt))
    }

    @Test
    fun `die Grenzen zwischen den Stufen liegen richtig`() {
        val jetzt = TimeUnit.DAYS.toMillis(100)
        // exakt eine Stunde ist bereits "Stunden", exakt ein Tag bereits "Tage"
        assertEquals("Vor 1 Stunden", zeitAbstandInWorten(jetzt - TimeUnit.HOURS.toMillis(1), jetzt))
        assertEquals("Vor 1 Tagen", zeitAbstandInWorten(jetzt - TimeUnit.DAYS.toMillis(1), jetzt))
        assertEquals("Vor 59 Minuten", zeitAbstandInWorten(jetzt - TimeUnit.MINUTES.toMillis(59), jetzt))
    }

    // ------------------------------------------------- Keine Dubletten mehr im Status-Tab

    @Test
    fun `der Status-Tab loest den Abgleich nur noch an EINER Stelle aus`() {
        // "Jetzt synchronisieren" in der >24h-Warnung bleibt bewusst: es erscheint genau dann,
        // wenn etwas im Argen liegt, und ist dort die naheliegende Handlung. Was verschwunden
        // ist, ist die Dublette "Neu laden" in der Netz-Karte.
        val treffer = Regex("""refreshData\(forceRefresh = true\)""")
            .findAll(ohneKommentare("ui/screens/tabs/StatusTabContent.kt")).count()
        assertEquals(
            "Der Status-Tab ruft refreshData mehrfach - die Dublette aus der Netz-Karte ist zurueck?",
            1,
            treffer
        )
    }

    @Test
    fun `die Kopfzeile traegt keinen Abgleich-Knopf mehr`() {
        // Der Knopf sass als blosser Kreispfeil oben rechts und sah aus, als betreffe er den
        // ganzen Bildschirm; ein Symbol kann nicht sagen, WAS es neu laedt.
        //
        // Geprueft wird das SYMBOL, nicht der Aufruf: refreshData() steht weiterhin in
        // MainContentScreen und gehoert auch dorthin - dort wird verdrahtet, welches ViewModel
        // der Tab-Inhalt ruft. Verschwunden ist nur die Bedienoberflaeche in der Kopfzeile.
        val kopf = ohneKommentare("ui/screens/MainContentScreen.kt")
        assertFalse(
            "In der Kopfzeile steht wieder ein Aktualisieren-Symbol",
            kopf.contains("Icons.Filled.Refresh") || kopf.contains("Aktualisieren")
        )
    }

    @Test
    fun `der Abgleich-Knopf nennt den Google Kalender`() {
        // Die App ist nur die Schnittstelle: sie gleicht mit dem GOOGLE KALENDER ab, nicht mit
        // TimeOffice. Ohne diese Unterscheidung sucht der Nutzer einen fehlenden Dienst in der
        // App statt im Dienstplan.
        val home = quelltext("ui/screens/tabs/HomeTabContent.kt")
        assertTrue(
            "Der Abgleich-Knopf nennt den Google Kalender nicht mehr",
            home.contains("Mit Google Kalender abgleichen")
        )
        assertTrue(
            "Der Erklaertext nennt TimeOffice nicht mehr - dann fehlt die Abgrenzung",
            home.contains("TimeOffice")
        )
    }

    // ------------------------------------------------- Nichts Wirkungsloses mehr in der Karte

    @Test
    fun `die wirkungslosen Cache-Bedienelemente sind weg`() {
        val status = ohneKommentare("ui/screens/tabs/StatusTabContent.kt")
        assertFalse(
            "getCacheStats() ist zurueck - die Methode wirft ihr Ergebnis weg und aendert nichts " +
                "Sichtbares (im Release landet nicht einmal das Log irgendwo)",
            status.contains("getCacheStats(")
        )
        assertFalse(
            "clearEventCache() ist zurueck - wirkt ohne jede Rueckmeldung und ist doppelt zum " +
                "Abgleich, der den Cache ohnehin verwirft",
            status.contains("clearEventCache(")
        )
        assertFalse(
            "Der Text behauptet wieder eine Log-Ausgabe, die der Nutzer nicht einsehen kann",
            status.contains("in Log ausgegeben")
        )
    }

    @Test
    fun `die Zeitkarte benennt beide Werte und ihre Bedeutung`() {
        val status = quelltext("ui/screens/tabs/StatusTabContent.kt")
        assertTrue(
            "Der reine Wartungs-Zeitstempel steht wieder ohne Einordnung da - er wird auch bei " +
                "einem UEBERSPRUNGENEN Lauf gestempelt und ist als Frische-Signal wertlos",
            status.contains("Auch wenn dabei nichts zu tun war.")
        )
        assertTrue(
            "Der zweite Wert (letzter echter Terminabruf) fehlt",
            status.contains("Termine zuletzt abgerufen")
        )
    }
}
