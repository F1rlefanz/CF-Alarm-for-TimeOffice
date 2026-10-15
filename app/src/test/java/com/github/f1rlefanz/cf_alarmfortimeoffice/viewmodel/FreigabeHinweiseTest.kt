package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * #142: die Rueckmeldung zu „Dienst fällt aus“ - je Text ein Fall.
 *
 * Der schaerfste Punkt ist Review S6: ein Text darf nur versprechen, was die Wiederherstellung
 * wirklich erreicht hat. Ist der gescheiterte Tag laut Kalender krank, klingelt dort NICHTS, und
 * "diese Wecker klingeln" waere eine Luege ueber einen Wecker.
 */
class FreigabeHinweiseTest {

    // Mo 12.10.2026 bis Fr 16.10.2026.
    private val mo: LocalDate = LocalDate.of(2026, 10, 12)
    private val di: LocalDate = mo.plusDays(1)
    private val mi: LocalDate = mo.plusDays(2)
    private val fr: LocalDate = mo.plusDays(4)

    private fun hinweis(
        von: LocalDate = mo,
        bis: LocalDate = fr,
        freigegebenBis: LocalDate? = null,
        geloeschteWecker: Int = 0,
        ueberspringenAufgehoben: Boolean = false,
        fehlerTag: LocalDate? = null,
        wiederherstellung: Wiederherstellung? = null
    ) = freigabeHinweis(von, bis, freigegebenBis, geloeschteWecker, ueberspringenAufgehoben, fehlerTag, wiederherstellung)

    // --- Erfolg, ein Tag ---

    @Test
    fun `Einzeltag ohne Ueberspringen bleibt still`() {
        assertNull(hinweis(von = mo, bis = mo, freigegebenBis = mo, geloeschteWecker = 1))
    }

    @Test
    fun `Einzeltag mit aufgehobenem Ueberspringen sagt das`() {
        assertEquals(
            "„Nicht wecken“ für diesen Tag ist damit zurückgenommen – es wird nicht mehr gebraucht.",
            hinweis(von = mo, bis = mo, freigegebenBis = mo, geloeschteWecker = 1, ueberspringenAufgehoben = true)
        )
    }

    // --- Erfolg, Zeitraum ---

    @Test
    fun `Zeitraum mit mehreren Weckern`() {
        assertEquals(
            "Dienst fällt aus bis Fr 16.10. – 4 Wecker entfallen, eigene Wecker bleiben.",
            hinweis(freigegebenBis = fr, geloeschteWecker = 4)
        )
    }

    @Test
    fun `Zeitraum mit einem Wecker`() {
        assertEquals(
            "Dienst fällt aus bis Fr 16.10. – 1 Wecker entfällt, eigene Wecker bleiben.",
            hinweis(freigegebenBis = fr, geloeschteWecker = 1)
        )
    }

    @Test
    fun `Zeitraum ohne Wecker meldet sich trotzdem`() {
        // Die Wirkung eines Zeitraums liegt ausserhalb des Bildschirms - eine Rueckmeldung ist
        // Pflicht, auch wenn (noch) kein Wecker betroffen war. Kein doppelter Punkt hinter dem Datum.
        assertEquals(
            "Dienst fällt aus bis Fr 16.10. Eigene Wecker bleiben.",
            hinweis(freigegebenBis = fr, geloeschteWecker = 0)
        )
    }

    @Test
    fun `Zeitraum mit aufgehobenem Ueberspringen haengt den Satz an`() {
        assertEquals(
            "Dienst fällt aus bis Fr 16.10. – 2 Wecker entfallen, eigene Wecker bleiben. " +
                "„Nicht wecken“ ist damit zurückgenommen – es wird nicht mehr gebraucht.",
            hinweis(freigegebenBis = fr, geloeschteWecker = 2, ueberspringenAufgehoben = true)
        )
    }

    // --- Fehlschlag ohne freigegebenen Tag: Einzeltag ---

    @Test
    fun `Einzeltag - nichts geaendert`() {
        assertEquals(
            "„Dienst fällt aus“ hat nicht geklappt – an den Weckern hat sich nichts geändert. Bitte noch einmal versuchen.",
            hinweis(von = mo, bis = mo, fehlerTag = mo, wiederherstellung = Wiederherstellung.NichtsGeaendert)
        )
    }

    @Test
    fun `Einzeltag - Wecker klingeln wie geplant`() {
        assertEquals(
            "„Dienst fällt aus“ hat nicht geklappt. Die Wecker dieses Tages klingeln wie geplant.",
            hinweis(von = mo, bis = mo, fehlerTag = mo, wiederherstellung = Wiederherstellung.KlingelnWieGeplant)
        )
    }

    @Test
    fun `Einzeltag - Wiederherstellen misslungen`() {
        assertEquals(
            "„Dienst fällt aus“ hat nicht geklappt, und 2 Wecker dieses Tages stehen jetzt nicht mehr – bitte in der Liste im Bereich „Wecker“ prüfen.",
            hinweis(von = mo, bis = mo, fehlerTag = mo, wiederherstellung = Wiederherstellung.Misslungen(2))
        )
    }

    @Test
    fun `Einzeltag - Markierung steht noch`() {
        assertEquals(
            "„Dienst fällt aus“ hat nicht geklappt und ließ sich nicht sauber zurücknehmen. Bitte in der Liste im Bereich „Wecker“ prüfen, ob die Wecker dieses Tages noch stehen.",
            hinweis(von = mo, bis = mo, fehlerTag = mo, wiederherstellung = Wiederherstellung.MarkierungStehtNoch)
        )
    }

    @Test
    fun `Einzeltag - laut Kalender krank verspricht kein Klingeln`() {
        assertEquals(
            "„Dienst fällt aus“ hat nicht geklappt. Der Tag ist laut Kalender krankgemeldet – es klingelt trotzdem kein Wecker aus dem Dienstplan.",
            hinweis(von = mo, bis = mo, fehlerTag = mo, wiederherstellung = Wiederherstellung.KalenderKrank)
        )
    }

    // --- Fehlschlag am ERSTEN Tag eines Zeitraums ---

    @Test
    fun `Erster Tag im Zeitraum gescheitert - Zusatz fuer die Tage danach`() {
        assertEquals(
            "„Dienst fällt aus“ hat nicht geklappt. Die Wecker dieses Tages klingeln wie geplant. " +
                "Für die Tage danach hat sich nichts geändert.",
            hinweis(fehlerTag = mo, wiederherstellung = Wiederherstellung.KlingelnWieGeplant)
        )
    }

    @Test
    fun `Erster Tag im Zeitraum - nichts geaendert braucht keinen Zusatz`() {
        // "an den Weckern hat sich nichts geaendert" gilt schon fuer alle Tage.
        assertEquals(
            "„Dienst fällt aus“ hat nicht geklappt – an den Weckern hat sich nichts geändert. Bitte noch einmal versuchen.",
            hinweis(fehlerTag = mo, wiederherstellung = Wiederherstellung.NichtsGeaendert)
        )
    }

    // --- Teilerfolg ---

    @Test
    fun `Teilerfolg - diese Wecker klingeln`() {
        assertEquals(
            "Nur bis Di 13.10. freigegeben – ab Mi 14.10. hat es nicht geklappt, diese Wecker klingeln. Bitte noch einmal versuchen.",
            hinweis(freigegebenBis = di, geloeschteWecker = 2, fehlerTag = mi, wiederherstellung = Wiederherstellung.KlingelnWieGeplant)
        )
        assertEquals(
            "Nur bis Di 13.10. freigegeben – ab Mi 14.10. hat es nicht geklappt, diese Wecker klingeln. Bitte noch einmal versuchen.",
            hinweis(freigegebenBis = di, geloeschteWecker = 2, fehlerTag = mi, wiederherstellung = Wiederherstellung.NichtsGeaendert)
        )
    }

    @Test
    fun `Teilerfolg - Wiederherstellen misslungen, Tage danach klingeln`() {
        assertEquals(
            "Nur bis Di 13.10. freigegeben – ab Mi 14.10. hat es nicht geklappt. 1 Wecker vom Mi 14.10. stehen jetzt nicht mehr – bitte in der Liste im Bereich „Wecker“ prüfen. Die Wecker danach klingeln.",
            hinweis(freigegebenBis = di, fehlerTag = mi, wiederherstellung = Wiederherstellung.Misslungen(1))
        )
    }

    @Test
    fun `Teilerfolg - Wiederherstellen misslungen am letzten Tag ohne Satz ueber danach`() {
        assertEquals(
            "Nur bis Do 15.10. freigegeben – ab Fr 16.10. hat es nicht geklappt. 3 Wecker vom Fr 16.10. stehen jetzt nicht mehr – bitte in der Liste im Bereich „Wecker“ prüfen.",
            hinweis(freigegebenBis = fr.minusDays(1), fehlerTag = fr, wiederherstellung = Wiederherstellung.Misslungen(3))
        )
    }

    @Test
    fun `Teilerfolg - Markierung steht noch`() {
        assertEquals(
            "Nur bis Di 13.10. freigegeben – ab Mi 14.10. hat es nicht geklappt. Bitte in der Liste im Bereich „Wecker“ prüfen, ob die Wecker ab Mi 14.10. noch stehen.",
            hinweis(freigegebenBis = di, fehlerTag = mi, wiederherstellung = Wiederherstellung.MarkierungStehtNoch)
        )
    }

    @Test
    fun `Teilerfolg - laut Kalender krank verspricht fuer diesen Tag kein Klingeln`() {
        // Review S6: der Backstop weist das Wiederstellen ab - am gescheiterten Tag klingelt nichts.
        assertEquals(
            "Nur bis Di 13.10. freigegeben – ab Mi 14.10. hat es nicht geklappt. Am Mi 14.10. klingelt trotzdem kein Wecker aus dem Dienstplan, der Tag ist laut Kalender krankgemeldet. Ab dem Tag danach klingeln die Wecker. Bitte noch einmal versuchen.",
            hinweis(freigegebenBis = di, fehlerTag = mi, wiederherstellung = Wiederherstellung.KalenderKrank)
        )
    }

    @Test
    fun `Teilerfolg - laut Kalender krank am letzten Tag`() {
        assertEquals(
            "Nur bis Do 15.10. freigegeben – ab Fr 16.10. hat es nicht geklappt. Am Fr 16.10. klingelt trotzdem kein Wecker aus dem Dienstplan, der Tag ist laut Kalender krankgemeldet.",
            hinweis(freigegebenBis = fr.minusDays(1), fehlerTag = fr, wiederherstellung = Wiederherstellung.KalenderKrank)
        )
    }

    // --- #142: wo ein Hinweis steht ---

    @Test
    fun `Eine Erfolgsmeldung steht nie auf der Uebersicht`() {
        assertNull(sichtbarerFreigabeHinweis("Dienst fällt aus bis Fr 16.10.", fr, mo, aufUebersicht = true))
    }

    @Test
    fun `Eine Erfolgsmeldung verschwindet im Wecker-Tab, wenn ihr Zeitraum vorbei ist`() {
        val text = "Dienst fällt aus bis Fr 16.10."
        assertEquals(text, sichtbarerFreigabeHinweis(text, fr, fr, aufUebersicht = false))
        assertNull(sichtbarerFreigabeHinweis(text, fr, fr.plusDays(4), aufUebersicht = false))
    }

    @Test
    fun `Ein Fehlertext steht ueberall, bis der naechste Vorgang ihn raeumt`() {
        val text = "„Zurücknehmen“ hat nicht geklappt"
        assertEquals(text, sichtbarerFreigabeHinweis(text, null, fr.plusDays(10), aufUebersicht = true))
        assertEquals(text, sichtbarerFreigabeHinweis(text, null, fr.plusDays(10), aufUebersicht = false))
        assertNull(sichtbarerFreigabeHinweis(null, null, mo, aufUebersicht = true))
    }
}
