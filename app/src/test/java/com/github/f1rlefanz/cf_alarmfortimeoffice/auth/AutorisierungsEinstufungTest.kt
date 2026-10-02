package com.github.f1rlefanz.cf_alarmfortimeoffice.auth

import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager.AutorisierungsEinstufung
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager.AutorisierungsEinstufung.Ausgang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException

/**
 * Haelt die Einstufung der AuthorizationClient-Antworten fest. Kern ist die am Emulator gemessene
 * Falle (Spike 30.09.2026, S3): offline kommt nach geleertem Cache KEIN Fehler, sondern ein Erfolg
 * mit `hasResolution = true` - dieselbe Form wie eine entzogene Zustimmung.
 */
class AutorisierungsEinstufungTest {

    /** Steht fuer `ApiException(Status(code))` - die echte Klasse braucht die Play-Dienste. */
    private class FakeApiException(val code: Int) : Exception("ApiException $code")

    private val statusCode: (Throwable) -> Int? = { (it as? FakeApiException)?.code }

    private fun fehler(t: Throwable, netz: Boolean = true) =
        AutorisierungsEinstufung.ausFehler(t, netz, statusCode)

    // --- Erfolgreiche Aufrufe ---

    @Test
    fun `Token ohne Resolution ist ein Token`() {
        assertEquals(
            Ausgang.Token("abc"),
            AutorisierungsEinstufung.ausErgebnis(hatResolution = false, accessToken = "abc", netzValidiert = true)
        )
    }

    @Test
    fun `Token aus dem Cache gilt auch offline`() {
        // S3 mit gefuelltem Cache: das Token kommt offline weiter.
        assertEquals(
            Ausgang.Token("abc"),
            AutorisierungsEinstufung.ausErgebnis(hatResolution = false, accessToken = "abc", netzValidiert = false)
        )
    }

    @Test
    fun `Resolution OHNE Netz ist voruebergehend - die gemessene Falle`() {
        val ausgang = AutorisierungsEinstufung.ausErgebnis(hatResolution = true, accessToken = null, netzValidiert = false)
        assertTrue(ausgang is Ausgang.Voruebergehend)
    }

    @Test
    fun `Resolution MIT Netz verlangt Zustimmung`() {
        val ausgang = AutorisierungsEinstufung.ausErgebnis(hatResolution = true, accessToken = null, netzValidiert = true)
        assertTrue(ausgang is Ausgang.ZustimmungNoetig)
    }

    @Test
    fun `Resolution gewinnt auch gegen ein mitgeliefertes Token`() {
        val ausgang = AutorisierungsEinstufung.ausErgebnis(hatResolution = true, accessToken = "abc", netzValidiert = true)
        assertTrue(ausgang is Ausgang.ZustimmungNoetig)
    }

    @Test
    fun `leeres Token mit Netz ist endgueltig, ohne Netz voruebergehend`() {
        assertTrue(AutorisierungsEinstufung.ausErgebnis(false, "", true) is Ausgang.Endgueltig)
        assertTrue(AutorisierungsEinstufung.ausErgebnis(false, null, true) is Ausgang.Endgueltig)
        assertTrue(AutorisierungsEinstufung.ausErgebnis(false, " ", false) is Ausgang.Voruebergehend)
    }

    // --- Gescheiterte Aufrufe ---

    @Test
    fun `Netz-, Timeout- und Verbindungs-Statuscodes sind voruebergehend - auch MIT Netz`() {
        for (code in listOf(7, 8, 14, 15, 16, 17, 19, 20, 21, 22)) {
            assertTrue("Code $code", fehler(FakeApiException(code)) is Ausgang.Voruebergehend)
        }
    }

    @Test
    fun `Tasks-await wickelt in ExecutionException - die Kette wird durchlaufen`() {
        assertTrue(fehler(ExecutionException(FakeApiException(7))) is Ausgang.Voruebergehend)
        assertTrue(fehler(ExecutionException(FakeApiException(4))) is Ausgang.ZustimmungNoetig)
    }

    @Test
    fun `TimeoutException, IOException und Unterbrechung sind voruebergehend`() {
        assertTrue(fehler(TimeoutException()) is Ausgang.Voruebergehend)
        assertTrue(fehler(RuntimeException(IOException("x"))) is Ausgang.Voruebergehend)
        assertTrue(fehler(InterruptedException()) is Ausgang.Voruebergehend)
    }

    @Test
    fun `SIGN_IN_REQUIRED und RESOLUTION_REQUIRED verlangen MIT Netz Zustimmung`() {
        assertTrue(fehler(FakeApiException(4)) is Ausgang.ZustimmungNoetig)
        assertTrue(fehler(FakeApiException(6)) is Ausgang.ZustimmungNoetig)
    }

    @Test
    fun `ohne Netz ist nichts endgueltig - weder Zustimmung noch unbekannter Code`() {
        assertTrue(fehler(FakeApiException(4), netz = false) is Ausgang.Voruebergehend)
        assertTrue(fehler(FakeApiException(10), netz = false) is Ausgang.Voruebergehend)
        assertTrue(fehler(IllegalStateException("x"), netz = false) is Ausgang.Voruebergehend)
    }

    @Test
    fun `unbekannte Fehler und Codes sind mit Netz endgueltig - wie frueher GoogleAuthException`() {
        assertTrue(fehler(FakeApiException(10)) is Ausgang.Endgueltig) // DEVELOPER_ERROR
        assertTrue(fehler(IllegalStateException("x")) is Ausgang.Endgueltig)
    }

    /** Ein Name, den R8 im Release zu `a` machen wuerde - er darf nicht im Grund auftauchen. */
    private class SehrEigenerAutorisierungsFehler(meldung: String?) : RuntimeException(meldung)

    @Test
    fun `unbekannter Fehler nennt die Meldung, nicht den Klassennamen`() {
        // Issue #54: seit R8 umbenennt, waere ein simpleName fuer den Nutzer nur ein Kuerzel.
        val ausgang = fehler(SehrEigenerAutorisierungsFehler("Konto gesperrt"))
        assertTrue(ausgang is Ausgang.Endgueltig)
        val grund = (ausgang as Ausgang.Endgueltig).grund
        assertFalse(grund, grund.contains("SehrEigenerAutorisierungsFehler"))
        assertTrue(grund, grund.contains("Konto gesperrt"))
    }

    @Test
    fun `unbekannter Fehler ohne Meldung bekommt einen festen Text`() {
        for (meldung in listOf(null, "", "   ")) {
            val grund = (fehler(SehrEigenerAutorisierungsFehler(meldung)) as Ausgang.Endgueltig).grund
            assertEquals("Unbekannter Fehler bei der Google-Autorisierung", grund)
        }
    }

    @Test
    fun `eine im Kreis zeigende Ursachenkette haengt nicht`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        assertTrue(fehler(a) is Ausgang.Endgueltig)
    }
}
