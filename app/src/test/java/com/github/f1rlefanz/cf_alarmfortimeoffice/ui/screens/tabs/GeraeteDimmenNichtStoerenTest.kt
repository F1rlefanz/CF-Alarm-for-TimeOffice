package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs

import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimDiagnostik
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Die Texte der Geraete-Einstellungen (#225).
 *
 * Die Kurzzeile unter „Wer trotzdem durchkommt“ ist gemischt gepolt: `block*` an heisst stumm,
 * `allowRepeatCallers` an heisst durchlassen (und zaehlt nur bei blockierten Anrufen). Ein
 * vertauschter Schalter behauptete dem Nutzer Erreichbarkeit, die es nicht gibt.
 */
class GeraeteDimmenNichtStoerenTest {

    private val allesStumm = DndPrefs.Policy(
        blockCalls = true,
        allowRepeatCallers = false,
        blockMessages = true,
        blockConversations = true,
        blockReminders = true,
        blockEvents = true,
        blockSystem = true,
        blockMedia = true,
        blockAlarms = true
    )

    private fun kommtDurch(policy: DndPrefs.Policy): List<String> {
        val text = GeraeteTexte.werDurchkommt(policy)
        assertTrue(text, text.endsWith(" – gilt für alle Typen"))
        return text.removeSuffix(" – gilt für alle Typen").split(", ")
    }

    @Test
    fun `ist alles stumm, kommt nur der eigene Wecker durch`() {
        assertEquals(listOf("dein Wecker"), kommtDurch(allesStumm))
    }

    @Test
    fun `jeder ausgeschaltete Schalter laesst genau seine Gruppe durch`() {
        val faelle = listOf(
            allesStumm.copy(blockCalls = false) to listOf("Anrufe", "dein Wecker"),
            allesStumm.copy(blockMessages = false) to listOf("Nachrichten", "dein Wecker"),
            allesStumm.copy(blockConversations = false) to listOf("Gespräche", "dein Wecker"),
            allesStumm.copy(blockReminders = false) to listOf("Erinnerungen", "dein Wecker"),
            allesStumm.copy(blockEvents = false) to listOf("Termine", "dein Wecker"),
            allesStumm.copy(blockSystem = false) to listOf("System-Töne", "dein Wecker"),
            allesStumm.copy(blockAlarms = false) to listOf("Wecker"),
            allesStumm.copy(blockMedia = false) to listOf("dein Wecker", "Medien")
        )
        faelle.forEach { (policy, erwartet) -> assertEquals(policy.toString(), erwartet, kommtDurch(policy)) }
    }

    @Test
    fun `wiederholte Anrufer zaehlen nur, solange Anrufe stumm sind`() {
        assertEquals(
            listOf("Wiederholte Anrufer", "dein Wecker"),
            kommtDurch(allesStumm.copy(allowRepeatCallers = true))
        )
        // Anrufe sind frei: dann kommen ohnehin alle durch, egal wie der zweite Schalter steht.
        listOf(true, false).forEach { wiederholte ->
            assertEquals(
                listOf("Anrufe", "dein Wecker"),
                kommtDurch(allesStumm.copy(blockCalls = false, allowRepeatCallers = wiederholte))
            )
        }
    }

    @Test
    fun `die Werkseinstellung nennt wiederholte Anrufer, System-Toene, Wecker und Medien`() {
        assertEquals(
            "Wiederholte Anrufer, System-Töne, Wecker, Medien – gilt für alle Typen",
            GeraeteTexte.werDurchkommt(DndPrefs.Policy())
        )
    }

    @Test
    fun `der Hinweis ueber den Schaltern nimmt den Durchlassen-Schalter aus`() {
        // „Eingeschaltet = bleibt stumm“ gilt fuer „Wiederholte Anrufer durchlassen“ gerade nicht.
        val zeilen = listOf(File("app/src/main/res/values/strings.xml"), File("src/main/res/values/strings.xml"))
            .firstOrNull { it.isFile }?.readLines() ?: error("strings.xml nicht gefunden")
        fun text(name: String) = zeilen.single { it.contains("name=\"$name\"") }

        assertTrue(text("dnd_policy_repeat_callers").contains("durchlassen<"))
        val hinweis = text("dnd_policy_hint")
        assertTrue(hinweis, hinweis.contains("„durchlassen“"))
    }

    @Test
    fun `nur der verbundene Dienst heisst erlaubt, und nur dann verspricht die Vorschau etwas`() {
        val verbunden = DimDiagnostik.DienstLage.VERBUNDEN
        assertTrue(GeraeteTexte.bedienungshilfe(verbunden).startsWith("Erlaubt"))
        assertEquals("10 Sekunden testen", GeraeteTexte.vorschau(verbunden))

        DimDiagnostik.DienstLage.entries.filter { it != verbunden }.forEach { lage ->
            assertFalse(lage.name, GeraeteTexte.bedienungshilfe(lage).startsWith("Erlaubt"))
            assertTrue(lage.name, GeraeteTexte.vorschau(lage).contains("Bedienungshilfe"))
        }
        // Schalter an, nicht verbunden: nicht zum Einschalten schicken (Hergang bei DienstLage).
        assertTrue(
            GeraeteTexte.bedienungshilfe(DimDiagnostik.DienstLage.EINGESCHALTET_NICHT_VERBUNDEN)
                .contains("aus- und wieder einschalten")
        )
    }
}
