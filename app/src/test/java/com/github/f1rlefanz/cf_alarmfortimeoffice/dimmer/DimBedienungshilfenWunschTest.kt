package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Der WEG von der Meldung „Dimmt nicht — Bedienungshilfen-Dienst ist aus" bis zu dem Schalter,
 * der sie aufloest.
 *
 * HERGANG (04.09.2026): Die Meldung sagte „Zum Aktivieren tippen", und der Tipp oeffnete die App
 * — irgendwo, auf dem zuletzt benutzten Tab. Die Karte, die den Dienst aktivieren laesst, steht
 * im Status-Tab unterhalb von sechs anderen; wer der Aufforderung folgte, landete also in einer
 * Liste und musste selbst suchen. Am Ende derselben Kette wartete der zweite Bruch: der Knopf
 * der Karte oeffnete die Liste ALLER Bedienungshilfen, in der der Eintrag der App zwischen
 * Systemdiensten steht. Eine Meldung mit einem Ausweg, den man suchen muss, ist nur die halbe
 * Meldung.
 *
 * WARUM DER SPRUNG NICHT DIREKT IN DIE EINSTELLUNGEN GEHT: Vor dem Aktivieren eines
 * Bedienungshilfen-Dienstes steht die Play-Pflicht-Offenlegung. Sie zeigt die Karte — deshalb
 * fuehrt die Benachrichtigung zur KARTE und nicht an ihr vorbei. Genau diese Reihenfolge sichern
 * die Tests unten ab; sie ist der Grund, warum der Weg zwei Stationen hat statt einer.
 *
 * Composables und Android-Intents lassen sich ohne Framework nicht ausfuehren. Pruefbar ist
 * deshalb der Quelltext der Kette (dasselbe Vorgehen wie in `AbgleichUndZeitanzeigeTest`) — plus
 * das eine Glied, das bewusst als reines Kotlin-Objekt daneben liegt.
 */
class DimBedienungshilfenWunschTest {

    private fun quelltext(pfad: String): String =
        File("src/main/java/com/github/f1rlefanz/cf_alarmfortimeoffice/$pfad").readText()

    /**
     * Quelltext ohne Kommentare. Die Begruendungen in diesen Dateien nennen die geforderten
     * Aufrufe absichtlich beim Namen — ohne diesen Filter waere jeder Test schon dadurch gruen,
     * dass jemand ordentlich dokumentiert hat.
     */
    private fun ohneKommentare(pfad: String): String =
        quelltext(pfad).lines()
            .filterNot { it.trimStart().startsWith("//") }
            .filterNot { it.trimStart().startsWith("*") }
            .filterNot { it.trimStart().startsWith("/*") }
            .joinToString("\n")

    @After
    fun raeumeAuf() {
        // Prozessweites Objekt: ein stehengebliebener Wunsch wuerde in den naechsten Test lecken.
        DimBedienungshilfenWunsch.verbrauchen()
    }

    // ---------------------------------------------------------------- das Signal selbst

    @Test
    fun `ohne Einstieg aus der Benachrichtigung steht kein Wunsch an`() {
        assertFalse(
            "Ein Wunsch, den niemand gestellt hat, wuerde die Offenlegung beim blossen Oeffnen des Status-Tabs aufpoppen lassen",
            DimBedienungshilfenWunsch.offen.value
        )
    }

    @Test
    fun `ein gestellter Wunsch steht an, bis er verbraucht wird`() {
        DimBedienungshilfenWunsch.stellen()
        assertTrue(DimBedienungshilfenWunsch.offen.value)

        // Zweimal tippen darf nichts kaputtmachen - der Wunsch bleibt EIN Wunsch.
        DimBedienungshilfenWunsch.stellen()
        assertTrue(DimBedienungshilfenWunsch.offen.value)

        DimBedienungshilfenWunsch.verbrauchen()
        assertFalse(
            "Nach dem Verbrauchen darf derselbe Wunsch nicht ein zweites Mal wirken",
            DimBedienungshilfenWunsch.offen.value
        )
    }

    // ---------------------------------------------------------------- Station 1: die Meldung

    @Test
    fun `die Benachrichtigung nennt ihr Ziel im Intent`() {
        val notifier = ohneKommentare("dimmer/DimCorrectionNotifier.kt")

        assertTrue(
            "Ohne das Extra landet der Tipp auf dem zuletzt benutzten Tab - die Karte, um die es " +
                "geht, bleibt ungesehen",
            notifier.contains("MainActivity.EXTRA_EINSTIEG") &&
                notifier.contains("MainActivity.EINSTIEG_DIMMER_BEDIENUNGSHILFEN")
        )
    }

    @Test
    fun `der Tipp wirft eine laufende App nicht weg`() {
        val notifier = ohneKommentare("dimmer/DimCorrectionNotifier.kt")

        // FLAG_ACTIVITY_CLEAR_TOP allein legt MainActivity (Start-Modus `standard`) neu an,
        // statt ihr onNewIntent zu geben - der Nutzer verlaere seinen Stand.
        assertTrue(
            "FLAG_ACTIVITY_SINGLE_TOP fehlt - CLEAR_TOP legt MainActivity dann neu an",
            notifier.contains("FLAG_ACTIVITY_SINGLE_TOP")
        )
    }

    @Test
    fun `die Benachrichtigung springt NICHT an der Offenlegung vorbei`() {
        val notifier = ohneKommentare("dimmer/DimCorrectionNotifier.kt")

        assertFalse(
            "Die Benachrichtigung fuehrt direkt in die Bedienungshilfen-Einstellungen - damit " +
                "faellt die Play-Pflicht-Offenlegung aus, die nur die Karte zeigt",
            notifier.contains("ACCESSIBILITY")
        )
    }

    // ---------------------------------------------------------------- Station 2: der Einstieg

    @Test
    fun `MainActivity setzt den Einstieg in Navigation um`() {
        val activity = ohneKommentare("MainActivity.kt")

        assertTrue(
            "MainActivity wertet das Einstiegs-Extra nicht aus - die Benachrichtigung fuehrt " +
                "dann wieder irgendwohin",
            activity.contains("EINSTIEG_DIMMER_BEDIENUNGSHILFEN ->")
        )
        assertTrue(
            "Der Wunsch wird nicht gestellt - die Karte zeigt ihre Offenlegung dann nicht",
            activity.contains("DimBedienungshilfenWunsch.stellen()")
        )
        assertTrue(
            "Ohne den Tab-Wechsel bleibt der Nutzer, wo er war - die Karte steht im Status-Tab",
            activity.contains("navigateToMainWithTab(MainTab.STATUS)")
        )
    }

    @Test
    fun `eine laufende App bekommt das neue Ziel auch wirklich zu sehen`() {
        val activity = ohneKommentare("MainActivity.kt")

        // Ohne setIntent() liefert getIntent() weiter den Start-Intent; dieselbe Falle wie am
        // Weckbildschirm (AlarmFullScreenActivity.onNewIntent).
        assertTrue(
            "onNewIntent fehlt oder ruft setIntent nicht - der Tipp auf die Benachrichtigung " +
                "bliebe bei laufender App wirkungslos",
            Regex("""override fun onNewIntent\(intent: Intent\)[\s\S]{0,200}?setIntent\(intent\)""")
                .containsMatchIn(activity)
        )
    }

    // ---------------------------------------------------------------- Station 3: die Karte

    @Test
    fun `der Status-Tab loest den Wunsch ein statt ihn nur zu lesen`() {
        val statusTab = ohneKommentare("ui/screens/tabs/StatusTabContent.kt")

        assertTrue(
            "Der Wunsch wird nicht verbraucht - er wirkte beim naechsten Oeffnen des Status-Tabs erneut",
            statusTab.contains("DimBedienungshilfenWunsch.verbrauchen()")
        )
        assertTrue(
            "Ohne Bildlauf steht die Karte weiter unterhalb des sichtbaren Bereichs - genau die " +
                "Suche, die dieser Weg abschaffen soll",
            statusTab.contains("animateScrollTo")
        )
        assertTrue(
            "Die Karte erfaehrt nichts von der Anfrage und zeigt die Offenlegung nicht",
            statusTab.contains("aktivierungsAnfragen = kartenAnfragen")
        )
    }

    @Test
    fun `der Bildlauf wird nachgefuehrt, solange sich die Karten darueber setzen`() {
        val statusTab = ohneKommentare("ui/screens/tabs/StatusTabContent.kt")

        // Das Ziel steht, sobald die Karte EINMAL vermessen ist. Die Karten darueber
        // (UnusedAppRestrictionsCard, TimeOfficeHealthCard) fuellen sich aber asynchron und
        // rendern zeitweise gar nichts; faellt eine weg, rueckt die Karte im Inhalt nach oben
        // und der einmal angefahrene Stand rollt zu weit.
        assertTrue(
            "Nach dem Anfahren wird nicht mehr nachgemessen - ein Kartenwegfall darueber laesst " +
                "den Bildlauf zu weit stehen (Issue #68)",
            Regex(
                """animateScrollTo\(ziel\)[\s\S]{0,600}?withTimeoutOrNull\([\s\S]{0,400}?scrollState\.scrollTo\("""
            ).containsMatchIn(statusTab)
        )
        assertTrue(
            "Das Nachfuehren ist zeitlich unbegrenzt - ein Inhalt, der sich nie beruhigt, " +
                "behielte den Bildlauf fuer immer",
            statusTab.contains("KARTE_NACHFUEHREN_MS")
        )
        assertTrue(
            "Ein eigener Bildlauf des Nutzers beendet das Nachfuehren nicht - es risse ihn zurueck",
            statusTab.contains("scrollState.value != gerollt")
        )
    }

    @Test
    fun `die Karte zeigt auf Anfrage die Offenlegung und nicht die Einstellung`() {
        val karten = ohneKommentare("ui/screens/tabs/StatusPermissionCards.kt")

        assertTrue(
            "Die Anfrage aus der Benachrichtigung oeffnet die Offenlegung nicht",
            Regex("""LaunchedEffect\(aktivierungsAnfragen\)[\s\S]{0,300}?showDisclosure = true""")
                .containsMatchIn(karten)
        )
        // Die eigentliche Zusicherung: es gibt genau EINEN Weg in die Einstellungen, und er
        // beginnt hinter der Offenlegung. Waere daneben ein zweiter (etwa aus dem Effekt oben),
        // koennte die Anfrage aus der Benachrichtigung an ihr vorbeilaufen.
        assertEquals(
            "Es gibt mehr als einen Weg in die Bedienungshilfen-Einstellungen - einer davon " +
                "kommt an der Play-Pflicht-Offenlegung vorbei",
            1,
            Regex("""openAccessibilitySettings\(context\)""").findAll(karten).count()
        )
        assertTrue(
            "Der Sprung in die Einstellungen haengt nicht mehr am Bestaetigen der Offenlegung",
            Regex("""showDisclosure = false\s+openAccessibilitySettings\(context\)""")
                .containsMatchIn(karten)
        )
    }

    // ---------------------------------------------------------------- Station 4: die Einstellung

    @Test
    fun `der Knopf fuehrt ueber die oeffentliche Aktion in die Bedienungshilfen`() {
        val karten = ohneKommentare("ui/screens/tabs/StatusPermissionCards.kt")

        assertTrue(
            "Der Weg in die Bedienungshilfen laeuft nicht mehr ueber die oeffentliche Aktion",
            karten.contains("Settings.ACTION_ACCESSIBILITY_SETTINGS")
        )
    }

    /**
     * Der uebliche Kniff, den eigenen Eintrag in der Liste hervorzuheben, ist
     * `:settings:fragment_args_key`. Aus der Shell gestartet wirkt er - aus DIESER App nicht.
     *
     * Am 05.09.2026 am Emulator durchgemessen: mit und ohne Argument-Buendel
     * (`:settings:show_fragment_args`), mit und ohne `FLAG_ACTIVITY_NEW_TASK`, mit frisch
     * geleerter Einstellungen-App - immer ohne Hervorhebung, waehrend `dumpsys activity
     * activities` fuer App- und Shell-Start denselben Intent zeigt. Der Unterschied ist der
     * Aufrufer; AOSP liest den Schluessel primaer aus den Fragment-Argumenten und nur hinter
     * einem Feature-Flag aus dem Intent.
     *
     * Deshalb bleibt der Zusatz draussen: er bewirkt im einzigen Kontext, in dem er laeuft,
     * nichts - und eine Erklaerung daneben wuerde etwas versprechen, das der Nutzer nicht bekommt.
     * Wer es erneut versucht, misst zuerst AUS DER APP, nicht aus der Shell.
     */
    @Test
    fun `der wirkungslose Hervorhebungs-Zusatz bleibt draussen`() {
        val karten = ohneKommentare("ui/screens/tabs/StatusPermissionCards.kt")

        assertTrue(
            "':settings:fragment_args_key' ist wieder im Code - aus dieser App heraus bewirkt " +
                "er nachweislich nichts; erst messen (aus der App!), dann einbauen",
            !karten.contains("fragment_args_key")
        )
    }

    /**
     * Der Direktsprung auf die Detailseite ist fuer diese App dauerhaft unerreichbar, nicht nur
     * auf manchen Geraeten: `Settings$AccessibilityDetailsSettingsActivity` traegt in AOSP seit
     * Android 11 `android:permission="android.permission.OPEN_ACCESSIBILITY_DETAILS_SETTINGS"`,
     * und die Berechtigung ist `signature|installer` und `@hide` ("Not for use by third-party
     * applications"). Am 05.09.2026 an Fairphone 6 (Android 16) und Emulator (API 36) gemessen:
     * SecurityException, Permission Denial.
     *
     * Wer sie zurueckholt, baut einen Zweig, der immer nur seinen eigenen Rueckfall erreicht -
     * und dabei bei jedem Tipp eine sinnlose Zeile ins Release-Log schreibt.
     */
    @Test
    fun `der unerreichbare Direktsprung auf die Detailseite bleibt draussen`() {
        val karten = ohneKommentare("ui/screens/tabs/StatusPermissionCards.kt")

        assertTrue(
            "ACCESSIBILITY_DETAILS_SETTINGS ist wieder im Code - diese Aktion kann diese App " +
                "nicht starten (signature|installer), sie erreicht immer nur ihren Rueckfall",
            !karten.contains("ACCESSIBILITY_DETAILS_SETTINGS")
        )
    }
}
