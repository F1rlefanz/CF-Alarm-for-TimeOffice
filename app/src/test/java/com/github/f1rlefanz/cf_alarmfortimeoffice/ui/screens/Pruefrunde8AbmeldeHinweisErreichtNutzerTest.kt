package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Der Meldeweg fuer einen Auth-Fehler, der den ANGEMELDETEN Nutzer trifft - praktisch immer ein
 * gescheitertes Abmelden.
 *
 * WARUM DIESER TEST: `authState.errors` hatte nur zwei Leser, und keiner erreichte den Nutzer
 * dort, wo er in diesem Moment steht. Der Abmelde-Knopf liegt im Einstellungen-Tab; scheitert das
 * Abmelden, bleibt der Nutzer angemeldet und sieht weiter genau diesen Tab. Der LoginScreen ist
 * dann per Definition nicht komponiert, und das Fehler-Banner des Einstellungen-Tabs sitzt ganz
 * OBEN in einer langen Scroll-Liste, waehrend der Abmelde-Knopf ganz unten steht - der Nutzer sah
 * nichts.
 *
 * WEGGEFALLEN: Diese Klasse pruefte frueher zusaetzlich die Texte einer Warnkarte fuer den
 * Zustand "angemeldet, aber alle Wecker geloescht". Die Karte gibt es nicht mehr - `signOut()`
 * meldet inzwischen zuerst ab und raeumt erst danach auf, es gibt also keinen Rueckbau mehr zu
 * bewerben (Begruendung im KDoc von `AuthViewModel.signOut`).
 *
 * KORRIGIERT (Welle 5/6): Hier stand, ein gescheitertes Abmelden fasse "gar nichts an". Das
 * stimmte nie - das Kalender-Token ist dann bereits verworfen, und geraeumt wird in beiden
 * Zweigen. Seit Welle 6 fuehrt dieser Zweig den Nutzer ausserdem auf den
 * Kalender-Autorisierungsbildschirm, dessen `InlineErrorCard` dasselbe `authState.error` zeigt.
 * Der hier gepruefte Weg bleibt trotzdem der richtige fuer alle uebrigen Auth-Fehler eines
 * angemeldeten Nutzers mit gueltigem Token (z. B. ein Wurf aus `signOutLocally()`).
 */
class Pruefrunde8AbmeldeHinweisErreichtNutzerTest {

    @Test
    fun `ein Auth-Fehler beim angemeldeten Nutzer wird gemeldet`() {
        // Genau die Lage nach einem gescheiterten Abmelden: angemeldet geblieben, Fehler gesetzt.
        assertEquals(
            "Abmelden fehlgeschlagen",
            authFehlerFuerSnackbar("Abmelden fehlgeschlagen", istAngemeldet = true)
        )
    }

    @Test
    fun `beim abgemeldeten Nutzer meldet dieser Bildschirm nichts`() {
        // Dann gehoert die Meldung dem LoginScreen - dort landet der Nutzer als Naechstes, und
        // dort steht auch der Hinweis auf moeglicherweise stehengebliebene Wecker. Wuerde die
        // gerade verschwindende Oberflaeche sie auch zeigen, saehe der Nutzer die Nachricht des
        // naechsten Bildschirms auf dem alten.
        assertNull(authFehlerFuerSnackbar("Abmelden fehlgeschlagen", istAngemeldet = false))
    }

    @Test
    fun `ohne Fehler wird nichts gemeldet`() {
        // Kein Hinweis, der bei JEDEM Abmelden erscheint.
        assertNull(authFehlerFuerSnackbar(null, istAngemeldet = true))
        assertNull(authFehlerFuerSnackbar("   ", istAngemeldet = true))
    }
}
