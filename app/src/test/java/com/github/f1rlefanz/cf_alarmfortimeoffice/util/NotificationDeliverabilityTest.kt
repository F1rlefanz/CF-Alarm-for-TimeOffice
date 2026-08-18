package com.github.f1rlefanz.cf_alarmfortimeoffice.util

import com.github.f1rlefanz.cf_alarmfortimeoffice.util.NotificationDeliverability.KANAL_FEHLT
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.NotificationDeliverability.WICHTIGKEIT_HOCH
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.NotificationDeliverability.WICHTIGKEIT_KEINE
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.NotificationDeliverability.Zustellbarkeit
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.NotificationDeliverability.beurteile
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Die Kanal-Ebene der Zustellbarkeit.
 *
 * WARUM DAS GETESTET GEHOERT: Bis v1.26.3 fragte die App ausschliesslich
 * `areNotificationsEnabled()` - die APP-Ebene. Android laesst den Nutzer aber direkt aus einer
 * Benachrichtigung heraus mit zwei Tipps einen EINZELNEN Kanal abschalten oder auf "Lautlos"
 * herunterstufen. Beim Wecker-Kanal kostet das den Full-Screen-Intent (das System verlangt
 * IMPORTANCE_HIGH), waehrend die App-Ebene weiter `true` meldet - der Wecker klingelt dann ohne
 * Weck-Bildschirm, und die Status-Karte behauptet "Erlaubt".
 *
 * Die zwei Faelle, die man leicht verwechselt und die hier auseinandergehalten werden:
 *  - ein FEHLENDER Kanal ist kein blockierter (er entsteht beim ersten Post) - waere er einer,
 *    unterdrueckte die App die allererste Meldung jeder Art;
 *  - ein Kanal UNTER der geforderten Wichtigkeit ist nicht "aus", aber fuer den Wecker wertlos.
 */
class NotificationDeliverabilityTest {

    @Test
    fun `alles an, Kanal hoch - erreichbar`() {
        assertEquals(
            Zustellbarkeit.ERREICHBAR,
            beurteile(true, kanaeleUnterstuetzt = true, WICHTIGKEIT_HOCH, gruppeGesperrt = false, WICHTIGKEIT_HOCH)
        )
    }

    @Test
    fun `abgeschaltete App schlaegt alles andere`() {
        // Kein Kanal der Welt hilft, wenn die App-Ebene aus ist - und die Karte muss dorthin
        // fuehren, nicht in die Kanaleinstellungen.
        assertEquals(
            Zustellbarkeit.APP_BLOCKIERT,
            beurteile(false, kanaeleUnterstuetzt = true, WICHTIGKEIT_HOCH, gruppeGesperrt = false)
        )
    }

    @Test
    fun `einzeln abgeschalteter Kanal wird erkannt`() {
        // DER BEFUND: areNotificationsEnabled() bleibt hier true. Genau deshalb reichte die
        // App-Ebene allein nicht.
        val urteil = beurteile(
            appErlaubt = true,
            kanaeleUnterstuetzt = true,
            kanalWichtigkeit = WICHTIGKEIT_KEINE,
            gruppeGesperrt = false
        )
        assertEquals(Zustellbarkeit.KANAL_BLOCKIERT, urteil)
        assertFalse(urteil.erreicht)
    }

    @Test
    fun `auf Lautlos heruntergestufter Weckerkanal gilt als nicht erreichbar`() {
        // IMPORTANCE_LOW (2) unter der geforderten IMPORTANCE_HIGH (4): kein Heads-up, kein
        // Vollbild. Der Wecker klingelt, der Weck-Bildschirm kommt nicht.
        val urteil = beurteile(
            appErlaubt = true,
            kanaeleUnterstuetzt = true,
            kanalWichtigkeit = 2,
            gruppeGesperrt = false,
            mindestwichtigkeit = WICHTIGKEIT_HOCH
        )
        assertEquals(Zustellbarkeit.KANAL_LEISE, urteil)
        assertFalse(urteil.erreicht)
    }

    @Test
    fun `derselbe leise Kanal genuegt, wo keine Dringlichkeit gefordert ist`() {
        // Schichtwechsel- und Kalender-Warnung brauchen kein Vollbild - "ueberhaupt an" reicht.
        assertEquals(
            Zustellbarkeit.ERREICHBAR,
            beurteile(true, kanaeleUnterstuetzt = true, kanalWichtigkeit = 2, gruppeGesperrt = false)
        )
    }

    @Test
    fun `gesperrte Kanalgruppe wird erkannt`() {
        assertEquals(
            Zustellbarkeit.GRUPPE_BLOCKIERT,
            beurteile(true, kanaeleUnterstuetzt = true, WICHTIGKEIT_HOCH, gruppeGesperrt = true)
        )
    }

    @Test
    fun `ein noch nicht angelegter Kanal ist NICHT blockiert`() {
        // Die Degradationsrichtung dieses Helfers: im Zweifel erreichbar. Wuerde ein fehlender
        // Kanal als blockiert gelten, unterdrueckte die App genau die erste Meldung - also die,
        // die den Kanal ueberhaupt erst anlegt.
        val urteil = beurteile(
            appErlaubt = true,
            kanaeleUnterstuetzt = true,
            kanalWichtigkeit = KANAL_FEHLT,
            gruppeGesperrt = false,
            mindestwichtigkeit = WICHTIGKEIT_HOCH
        )
        assertEquals(Zustellbarkeit.ERREICHBAR, urteil)
        assertTrue(urteil.erreicht)
    }

    @Test
    fun `vor API 26 ist die App-Ebene die ganze Wahrheit`() {
        assertEquals(
            Zustellbarkeit.ERREICHBAR,
            beurteile(true, kanaeleUnterstuetzt = false, KANAL_FEHLT, gruppeGesperrt = false, WICHTIGKEIT_HOCH)
        )
        assertEquals(
            Zustellbarkeit.APP_BLOCKIERT,
            beurteile(false, kanaeleUnterstuetzt = false, KANAL_FEHLT, gruppeGesperrt = false)
        )
    }

    @Test
    fun `die Wecker-Kanal-ID stimmt mit AlarmSoundService ueberein`() {
        // ZWEITE STELLE, ABSICHTLICH ABGESICHERT: AlarmSoundService haelt die ID als privates
        // companion-const, das sich nicht importieren laesst. Waere sie hier falsch, pruefte die
        // Status-Karte einen Kanal, den es nicht gibt - und meldete dank der Degradationsrichtung
        // ("fehlender Kanal = erreichbar") ausgerechnet dann dauerhaft "Erlaubt", wenn der echte
        // Wecker-Kanal abgeschaltet ist. Der Test faellt, sobald jemand eine der beiden aendert.
        val quelle = quelldatei("service/AlarmSoundService.kt")
        val zeile = quelle.readLines().firstOrNull { it.contains("const val CHANNEL_ID") }
            ?: error("CHANNEL_ID in AlarmSoundService.kt nicht gefunden - wurde sie umbenannt?")
        val id = Regex("\"([^\"]+)\"").find(zeile)?.groupValues?.get(1)
        assertEquals(
            "Kanal-ID der Wecker-Benachrichtigung ist auseinandergelaufen",
            id,
            NotificationDeliverability.WECKER_KANAL_ID
        )
    }

    /** Findet eine Produktivquelle unabhaengig davon, ob Gradle im Modul- oder Repo-Ordner startet. */
    private fun quelldatei(relativZumPaket: String): File {
        val paket = "src/main/java/com/github/f1rlefanz/cf_alarmfortimeoffice/$relativZumPaket"
        return listOf(File(paket), File("app/$paket")).firstOrNull { it.exists() }
            ?: error("Quelldatei nicht gefunden: $paket (Arbeitsverzeichnis ${File(".").absolutePath})")
    }
}
