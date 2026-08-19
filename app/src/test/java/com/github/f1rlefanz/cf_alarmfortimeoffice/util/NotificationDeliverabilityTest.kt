package com.github.f1rlefanz.cf_alarmfortimeoffice.util

import com.github.f1rlefanz.cf_alarmfortimeoffice.util.NotificationDeliverability.KANAL_FEHLT
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.NotificationDeliverability.WICHTIGKEIT_HOCH
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.NotificationDeliverability.WICHTIGKEIT_STANDARD
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.NotificationDeliverability.mindeststufeBeschreibung
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.NotificationDeliverability.WICHTIGKEIT_KEINE
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.NotificationDeliverability.Zustellbarkeit
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.NotificationDeliverability.beurteile
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    @Test
    fun `die Wecker-Kanal-ID ist versioniert und nicht mehr die alte`() {
        // BEFUND (Pruefrunde 7): Der Kanal wurde bis v1.9.7 mit IMPORTANCE_LOW angelegt. Die
        // spaetere Anhebung auf IMPORTANCE_HIGH lief unter DERSELBEN ID - und Android aendert die
        // Importance eines bestehenden Kanals nur nach unten ("All other fields are ignored for
        // channels that already exist"). Auf jedem Bestandsgeraet blieb der Wecker-Kanal deshalb
        // LOW, das System verwarf den Full-Screen-Intent, und der Wecker klingelte ohne
        // Weck-Bildschirm und ohne Stopp-/Schlummer-Knopf.
        assertNotEquals(
            "Der Wecker-Kanal darf nicht wieder auf der unversionierten Alt-ID liegen - dort " +
                "steht auf Bestandsgeraeten IMPORTANCE_LOW, und keine Neuanlage hebt das an",
            NotificationDeliverability.ALTE_WECKER_KANAL_ID,
            NotificationDeliverability.WECKER_KANAL_ID
        )
    }

    @Test
    fun `AlarmSoundService loescht die alte Kanal-ID und nutzt sie nicht mehr`() {
        // Die Migration darf nicht bloss "neue ID nehmen" heissen: ohne das Loeschen bliebe die
        // alte Kategorie als zweiter, stummer "Schicht-Wecker" in den Systemeinstellungen stehen
        // und schickte den Nutzer bei jeder Reparatur in die wirkungslose.
        val quelle = quelldatei("service/AlarmSoundService.kt").readText()

        val alteIdZeile = quelle.lines().firstOrNull { it.contains("const val ALTER_CHANNEL_ID") }
            ?: error("ALTER_CHANNEL_ID in AlarmSoundService.kt nicht gefunden")
        assertEquals(
            "Die zu loeschende Alt-ID ist auseinandergelaufen",
            Regex("\"([^\"]+)\"").find(alteIdZeile)?.groupValues?.get(1),
            NotificationDeliverability.ALTE_WECKER_KANAL_ID
        )

        assertTrue(
            "AlarmSoundService loescht den alten Kanal nicht mehr - dann bleibt der tote " +
                "Zwilling in den Systemeinstellungen stehen",
            quelle.contains("deleteNotificationChannel(ALTER_CHANNEL_ID)")
        )
    }

    @Test
    fun `die genannte Reparaturstufe besteht die eigene Pruefung`() {
        // DER BEFUND: Die Status-Karte forderte "Standard oder hoeher", prueft aber gegen
        // WICHTIGKEIT_HOCH. Wer das woertlich befolgte, blieb in KANAL_LEISE und sah unveraendert
        // das Warndreieck. Deshalb muss die genannte Stufe per Konstruktion durchkommen.
        val anweisung = mindeststufeBeschreibung(WICHTIGKEIT_HOCH)
        assertEquals(NotificationDeliverability.BESCHREIBUNG_OBERSTE_STUFE, anweisung)
        // Gegenprobe an der Mechanik selbst: IMPORTANCE_DEFAULT faellt hier durch.
        assertEquals(
            Zustellbarkeit.KANAL_LEISE,
            beurteile(true, kanaeleUnterstuetzt = true, WICHTIGKEIT_STANDARD, false, WICHTIGKEIT_HOCH)
        )
    }

    @Test
    fun `die Reparaturanweisung nennt eine Wirkung, keinen erfundenen Stufennamen`() {
        // DER REGRESSIONSBEFUND: Die abgeloeste Fassung holte den Namen aus einer eigenen Tabelle
        // (4 -> "Hoch"). In der deutschen Wichtigkeitsliste von Android 8/9 heisst "Hoch" aber
        // genau IMPORTANCE_DEFAULT - der Wert, den dieselbe Karte als KANAL_LEISE verwirft; auf
        // neueren Versionen fehlt der Eintrag ganz. Der Nutzer haette die Anweisung befolgt und
        // waere weiter ohne Weck-Bildschirm geweckt worden. Deshalb muss die Anweisung die
        // WIRKUNG benennen, die es in jeder Version gibt.
        val anweisung = mindeststufeBeschreibung(WICHTIGKEIT_HOCH)
        assertTrue(
            "Die Anweisung benennt nicht mehr die Wirkung (\"auf dem Bildschirm eingeblendet\") - " +
                "ein blosser Stufenname ist je nach Android-Version falsch oder gar nicht da: $anweisung",
            anweisung.contains("auf dem Bildschirm")
        )
        assertFalse(
            "Die Anweisung nennt \"Hoch\" als einzustellende Stufe - das ist auf Android 8/9 " +
                "genau IMPORTANCE_DEFAULT und besteht die eigene Pruefung nicht: $anweisung",
            anweisung.contains("\"Hoch\"") || anweisung.trim() == "Hoch"
        )
    }

    @Test
    fun `wo keine Dringlichkeit gefordert ist, genuegt die niedrigste Stufe`() {
        // Dieselbe Funktion muss fuer Schichtwechsel- und Kalender-Meldungen etwas anderes sagen -
        // sonst waere sie eine als Ableitung verkleidete Konstante.
        assertEquals(
            NotificationDeliverability.BESCHREIBUNG_EINGESCHALTET,
            mindeststufeBeschreibung(WICHTIGKEIT_KEINE + 1)
        )
    }

    @Test
    fun `die Status-Karte schreibt die Reparaturstufe nicht selbst hin`() {
        // Der Text und die Pruefung duerfen nicht aus zwei Quellen kommen - genau daran ist die
        // Karte schon einmal auseinandergelaufen.
        val karte = quelldatei("ui/screens/tabs/StatusPermissionCards.kt").readText()
        assertTrue(
            "Die Karte leitet die geforderte Stufe nicht mehr aus mindeststufeBeschreibung ab",
            karte.contains("mindeststufeBeschreibung(")
        )
        assertFalse(
            "Die Karte nennt wieder \"Standard\" als Reparaturstufe - das besteht ihre eigene " +
                "Pruefung gegen WICHTIGKEIT_HOCH nicht",
            karte.contains("\\\"Standard\\\"")
        )
    }

    /** Findet eine Produktivquelle unabhaengig davon, ob Gradle im Modul- oder Repo-Ordner startet. */
    private fun quelldatei(relativZumPaket: String): File {
        val paket = "src/main/java/com/github/f1rlefanz/cf_alarmfortimeoffice/$relativZumPaket"
        return listOf(File(paket), File("app/$paket")).firstOrNull { it.exists() }
            ?: error("Quelldatei nicht gefunden: $paket (Arbeitsverzeichnis ${File(".").absolutePath})")
    }
}
