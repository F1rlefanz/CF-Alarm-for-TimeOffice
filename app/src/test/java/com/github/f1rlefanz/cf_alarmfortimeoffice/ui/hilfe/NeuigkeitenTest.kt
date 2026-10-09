package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.hilfe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "Was ist neu" liest den Changelog-Ausschnitt, den der Build beilegt. Der wichtigste Fall ist der
 * letzte Test: er laeuft gegen die ECHTE `CHANGELOG.md` - weicht ein Eintrag von den
 * Schreibkonventionen so ab, dass die App ihn nicht mehr findet, faellt das hier auf und nicht erst
 * beim Tester nach dem Update.
 */
class NeuigkeitenTest {

    private val beispiel = """
        ## 🆕 Version 1.2.3 (Aktuell – interne Alpha)

        **Stand:** Oktober 2026

        _Ein Satz mit *Betonung*._

        ### ✨ Neu

        - **Hilfe in der App:** Ein `?` führt zur [Anleitung](https://x.y/).
        - Ohne Kurzfassung
          und über zwei Zeilen.

        ### 🐛 Behoben

        - **Wecker**: klingelt wieder.

        ## Version 1.2.2

        ### 🔧 Unter der Haube

        - **Aufgeräumt:** Nichts Sichtbares.
    """.trimIndent()

    @Test
    fun `liest Version, Stand, Zusammenfassung, Rubriken und Eintraege`() {
        val versionen = Neuigkeiten.parse(beispiel)
        assertEquals(listOf("1.2.3", "1.2.2"), versionen.map { it.versionName })

        val neu = versionen.first()
        assertEquals("Version 1.2.3", neu.titel)
        assertEquals("Oktober 2026", neu.stand)
        assertEquals("Ein Satz mit Betonung.", neu.zusammenfassung)
        assertEquals(listOf("✨ Neu", "🐛 Behoben"), neu.rubriken.map { it.titel })
        assertEquals(
            listOf(
                NeuigkeitenPunkt("Hilfe in der App", "Ein ? führt zur Anleitung."),
                NeuigkeitenPunkt(null, "Ohne Kurzfassung und über zwei Zeilen.")
            ),
            neu.rubriken[0].punkte
        )
        assertEquals(NeuigkeitenPunkt("Wecker", "klingelt wieder."), neu.rubriken[1].punkte.single())
    }

    @Test
    fun `findet die laufende Version auch im Debug-Build`() {
        val versionen = Neuigkeiten.parse(beispiel)
        assertEquals("1.2.2", Neuigkeiten.zurVersion(versionen, "1.2.2-DEBUG")?.versionName)
        assertNull(Neuigkeiten.zurVersion(versionen, "9.9.9"))
    }

    @Test
    fun `Unterstriche in Woertern bleiben stehen`() {
        assertEquals("dim_enabled bleibt", Neuigkeiten.ohneMarkup("dim_enabled bleibt"))
    }

    @Test
    fun `die echte CHANGELOG-Datei liefert die aktuelle Version mit Inhalt`() {
        val datei = listOf(File("../CHANGELOG.md"), File("CHANGELOG.md")).first { it.exists() }
        val versionen = Neuigkeiten.parse(datei.readText().substringFrom("\n## "))
        val oberste = versionen.first()
        assertTrue("oberste Version ohne Nummer", oberste.versionName != null)
        assertTrue(
            "Version ${oberste.versionName} ohne Eintraege",
            oberste.rubriken.sumOf { it.punkte.size } > 0
        )
        assertTrue(oberste.rubriken.flatMap { it.punkte }.none { "**" in it.text || "**" in (it.kurz ?: "") })
    }

    private fun String.substringFrom(marke: String) = substring(indexOf(marke) + 1)
}
