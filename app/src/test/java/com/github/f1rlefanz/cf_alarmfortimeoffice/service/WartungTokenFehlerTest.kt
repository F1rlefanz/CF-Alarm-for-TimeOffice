package com.github.f1rlefanz.cf_alarmfortimeoffice.service

import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager.TokenException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Haelt fest, wie die 6h-Wartung auf einen fehlgeschlagenen Token-Abruf reagiert.
 *
 * DER ECHTE VORFALL, gegen den diese Tests stehen (Fairphone, 09.09.2026, 11:48): der Token war
 * abgelaufen, `GoogleAuthUtil.getToken()` warf `java.io.IOException: NetworkError`, und eine
 * Sekunde spaeter meldete der Netzbeobachter `hasInternet: false`. Die Wartung poste daraufhin
 * "Anmeldung erforderlich - bitte oeffne die App und melde dich an". Um 17:48 rotierte derselbe
 * Weg den Token anstandslos (Rotation #282, 4 Wecker in Sync): Es gab nie ein Anmeldeproblem.
 */
class WartungTokenFehlerTest {

    private fun netzfehler() = TokenException.RefreshFailed(
        "Google refresh failed: NetworkError",
        IOException("NetworkError")
    )

    // ---- Einstufung ----------------------------------------------------------------------

    @Test
    fun `IOException im Ursachenpfad ist voruebergehend`() {
        assertEquals(WartungTokenFehler.Art.VORUEBERGEHEND, WartungTokenFehler.einstufe(netzfehler()))
    }

    @Test
    fun `doppelt gewickelte IOException wird noch gefunden`() {
        // refresh() wickelt den Fehlschlag aus refreshViaGooglePlayServices ein zweites Mal ein -
        // die IOException liegt dann zwei Ebenen tief.
        val doppelt = TokenException.RefreshFailed("Google refresh failed: NetworkError", netzfehler())
        assertEquals(WartungTokenFehler.Art.VORUEBERGEHEND, WartungTokenFehler.einstufe(doppelt))
    }

    @Test
    fun `RefreshFailed ohne Netzursache verlangt Anmeldung`() {
        // Der GoogleAuthUtil-Vertrag: was keine IOException ist, ist nicht voruebergehend.
        val endgueltig = TokenException.RefreshFailed("Google refresh failed: Empty token received")
        assertEquals(WartungTokenFehler.Art.ANMELDUNG, WartungTokenFehler.einstufe(endgueltig))
    }

    @Test
    fun `fehlendes und abgelaufenes Token verlangen Anmeldung`() {
        assertEquals(
            WartungTokenFehler.Art.ANMELDUNG,
            WartungTokenFehler.einstufe(TokenException.NoTokenAvailable("Authorization required"))
        )
        assertEquals(
            WartungTokenFehler.Art.ANMELDUNG,
            WartungTokenFehler.einstufe(TokenException.AuthorizationExpired("Re-authorization required"))
        )
        assertEquals(
            WartungTokenFehler.Art.ANMELDUNG,
            WartungTokenFehler.einstufe(TokenException.ConsentRequired("Zustimmung entzogen"))
        )
        assertEquals(
            WartungTokenFehler.Art.ANMELDUNG,
            WartungTokenFehler.einstufe(TokenException.SecurityViolation("Token rotation invalid"))
        )
    }

    @Test
    fun `Speicherfehler schickt niemanden zur Anmeldung`() {
        // Tink oder DataStore kaputt - eine Neuanmeldung repariert daran nichts, und die
        // Aufforderung dazu waere dieselbe Fehldiagnose wie beim Funkloch.
        assertEquals(
            WartungTokenFehler.Art.VORUEBERGEHEND,
            WartungTokenFehler.einstufe(TokenException.StorageFailed("Failed to save token"))
        )
    }

    @Test
    fun `unbekannter Fehler behauptet nichts ueber die Anmeldung`() {
        assertEquals(WartungTokenFehler.Art.VORUEBERGEHEND, WartungTokenFehler.einstufe(IllegalStateException("?")))
        assertEquals(WartungTokenFehler.Art.VORUEBERGEHEND, WartungTokenFehler.einstufe(null))
    }

    @Test
    fun `im Kreis zeigende Ursachenkette terminiert`() {
        // Fremde Bibliotheken koennen so etwas liefern; eine Endlosschleife im Wartungslauf waere
        // teuer. Der Test wuerde bei einem Rueckfall gar nicht mehr fertig.
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        assertFalse(WartungTokenFehler.istNetzursache(a))
    }

    // ---- Entprellung ---------------------------------------------------------------------

    @Test
    fun `erster Netzaussetzer meldet nichts`() {
        val e = WartungTokenFehler.entscheide(netzfehler(), zaehlerVorher = 0, bereitsGemeldet = false)

        assertNull("Ein einzelnes Funkloch ist keine Meldung wert", e.meldung)
        assertEquals(1, e.neuerZaehler)
        assertFalse(e.neuBereitsGemeldet)
    }

    @Test
    fun `zweiter Netzaussetzer in Folge meldet - aber nicht als Anmeldeproblem`() {
        val e = WartungTokenFehler.entscheide(netzfehler(), zaehlerVorher = 1, bereitsGemeldet = false)

        assertNotNull(e.meldung)
        assertEquals("Kalender-Synchronisation gestört", e.meldung?.titel)
        assertTrue(e.neuBereitsGemeldet)
    }

    @Test
    fun `dieselbe Stoerung meldet sich nicht alle sechs Stunden erneut`() {
        val e = WartungTokenFehler.entscheide(netzfehler(), zaehlerVorher = 5, bereitsGemeldet = true)

        assertNull(e.meldung)
        assertTrue(e.neuBereitsGemeldet)
    }

    @Test
    fun `ein echtes Anmeldeproblem meldet sofort und bei jedem Lauf`() {
        val erster = WartungTokenFehler.entscheide(
            TokenException.NoTokenAvailable("Authorization required"),
            zaehlerVorher = 0,
            bereitsGemeldet = false
        )
        assertEquals("Anmeldung erforderlich", erster.meldung?.titel)

        // Auch wenn schon gemeldet wurde: der Zustand besteht fort, bis der Nutzer handelt.
        val spaeter = WartungTokenFehler.entscheide(
            TokenException.NoTokenAvailable("Authorization required"),
            zaehlerVorher = 4,
            bereitsGemeldet = true
        )
        assertEquals("Anmeldung erforderlich", spaeter.meldung?.titel)
    }

    // ---- Netz-Nachholung -----------------------------------------------------------------

    @Test
    fun `Netzursache fordert eine Nachholung an`() {
        val e = WartungTokenFehler.entscheide(netzfehler(), zaehlerVorher = 0, bereitsGemeldet = false)
        assertTrue(e.netzNachholen)
    }

    @Test
    fun `ohne Netzursache wird nichts nachgeholt`() {
        // Sonst wartete der Auftrag auf eine Bedingung, die laengst erfuellt ist, liefe sofort und
        // scheiterte an derselben Ursache - eine enge Schleife.
        listOf(
            TokenException.StorageFailed("Failed to save token"),
            TokenException.NoTokenAvailable("Authorization required"),
            IllegalStateException("?")
        ).forEach { fehler ->
            val e = WartungTokenFehler.entscheide(fehler, zaehlerVorher = 0, bereitsGemeldet = false)
            assertFalse("$fehler darf keine Nachholung ausloesen", e.netzNachholen)
        }
    }

    @Test
    fun `die Nachholung ist gedeckelt`() {
        // Ein Anschluss ohne echten Internetzugang (Captive Portal) erfuellt die Bedingung
        // "Netz verfuegbar" dauerhaft. Ohne Deckel weckte das Geraet sich selbst im Minutentakt.
        val letzte = WartungTokenFehler.entscheide(
            netzfehler(),
            zaehlerVorher = WartungTokenFehler.MAX_NETZ_NACHHOLVERSUCHE - 1,
            bereitsGemeldet = true
        )
        assertTrue(letzte.netzNachholen)

        val darueber = WartungTokenFehler.entscheide(
            netzfehler(),
            zaehlerVorher = WartungTokenFehler.MAX_NETZ_NACHHOLVERSUCHE,
            bereitsGemeldet = true
        )
        assertFalse("Danach bleibt nur die regulaere 6h-Kette", darueber.netzNachholen)
    }
}
