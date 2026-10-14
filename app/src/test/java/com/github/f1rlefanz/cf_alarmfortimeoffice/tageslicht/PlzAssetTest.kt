package com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Prueft die ECHTEN, eingebauten PLZ-Tabellen (#140) - erzeugt von
 * `tools/tageslicht/baue_plz_tabelle.py` aus den GeoNames-Zips.
 *
 * Gelesen wird die Datei aus `src/main/assets`, wie `BackupRulesConsistencyTest` die XML-Regeln
 * liest: der Test sieht genau das, was in die APK wandert, und faengt einen kaputten oder
 * vertauschten Generatorlauf (falsches Land, Spalten vertauscht, Lizenzkopf vergessen).
 */
class PlzAssetTest {

    @Test
    fun `jede Tabelle nennt Quelle und Lizenz im Kopf`() {
        PlzLand.entries.forEach { land ->
            val kopf = text(land).lines().filter { it.startsWith("#") }.joinToString("\n")
            assertTrue("$land: GeoNames fehlt im Kopf", kopf.contains("GeoNames"))
            assertTrue("$land: CC BY 4.0 fehlt im Kopf", kopf.contains("CC BY 4.0"))
            assertTrue("$land: Stand fehlt im Kopf", kopf.contains("Stand "))
        }
    }

    @Test
    fun `Muenchen 80 liegt in Muenchen`() {
        val r = region(PlzLand.DE, "80331")
        assertNahe(48.1, 11.6, r)
    }

    @Test
    fun `Wien 10 liegt in Wien`() {
        val r = region(PlzLand.AT, "1010")
        assertNahe(48.2, 16.4, r)
    }

    @Test
    fun `Zuerich 80 liegt in Zuerich - nicht in Muenchen`() {
        val r = region(PlzLand.CH, "8001")
        assertNahe(47.4, 8.5, r)
    }

    @Test
    fun `Berlin 10 liegt in Berlin - Grosskunden-PLZ verziehen den Schwerpunkt nicht`() {
        // 10875 ist in GeoNames eine Stuttgarter Grosskunden-PLZ; der Generator laesst sie aus
        val r = region(PlzLand.DE, "10115")
        assertNahe(52.5, 13.4, r)
    }

    @Test
    fun `jede Koordinate liegt innerhalb von DE, AT und CH`() {
        PlzLand.entries.forEach { land ->
            val zeilen = datenZeilen(land)
            assertTrue("$land: Tabelle ist leer", zeilen.isNotEmpty())
            zeilen.forEach { z ->
                val (praefix, breite, laenge) = z
                assertTrue("$land $praefix: Breite $breite ausserhalb", breite in 45.7..55.1)
                assertTrue("$land $praefix: Laenge $laenge ausserhalb", laenge in 5.8..17.2)
            }
        }
    }

    @Test
    fun `jede Datenzeile wird vom App-Parser gelesen`() {
        PlzLand.entries.forEach { land ->
            val tabelle = PlzTabelle.parse(land, text(land))
            datenZeilen(land).forEach { (praefix, _, _) ->
                val r = tabelle.suche(land, praefix)
                assertNotNull("$land: Zeile $praefix wird nicht gelesen", r)
                assertEquals(praefix, r!!.praefix)
            }
        }
    }

    @Test
    fun `alle zweistelligen Leitregionen sind abgedeckt`() {
        // DE: 01..99 ohne die nicht vergebenen 00, 05, 11, 43, 62 (Stand der Leitregionen)
        val de = datenZeilen(PlzLand.DE).map { it.first }.filter { it.length == 2 }.toSet()
        assertTrue("DE hat nur ${de.size} Leitregionen", de.size >= 90)
        val at = datenZeilen(PlzLand.AT).map { it.first }.filter { it.length == 2 }.toSet()
        assertTrue("AT hat nur ${at.size} Regionen", at.size >= 70)
        val ch = datenZeilen(PlzLand.CH).map { it.first }.filter { it.length == 2 }.toSet()
        assertTrue("CH hat nur ${ch.size} Regionen", ch.size >= 70)
    }

    @Test
    fun `Tabellen bleiben klein`() {
        PlzLand.entries.forEach { land ->
            val bytes = datei(land).length()
            assertTrue("$land: $bytes Bytes - der Plan sieht deutlich unter 30 KB vor", bytes < 30 * 1024)
        }
    }

    // ------------------------------------------------------------------

    private fun region(land: PlzLand, plz: String): PlzRegion {
        val r = PlzTabelle.parse(land, text(land)).suche(land, plz)
        assertNotNull("$land $plz nicht gefunden", r)
        return r!!
    }

    private fun assertNahe(breite: Double, laenge: Double, r: PlzRegion) {
        assertEquals("Breite von ${r.land} ${r.praefix} (${r.name})", breite, r.breite, 0.15)
        assertEquals("Laenge von ${r.land} ${r.praefix} (${r.name})", laenge, r.laenge, 0.15)
    }

    private fun datenZeilen(land: PlzLand): List<Triple<String, Double, Double>> =
        text(land).lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map {
            val t = it.split('\t')
            assertEquals("$land: Zeile '$it' hat nicht vier Spalten", 4, t.size)
            Triple(t[0], t[1].toDouble(), t[2].toDouble())
        }

    private fun text(land: PlzLand): String = datei(land).readText(Charsets.UTF_8)

    private fun datei(land: PlzLand): File {
        val relativ = "src/main/assets/tageslicht/plz_${land.name.lowercase()}.tsv"
        return listOf(File(relativ), File("app/$relativ")).firstOrNull { it.isFile }
            ?: run { fail("$relativ nicht gefunden (Arbeitsverzeichnis ${File(".").absolutePath})"); error("unreachable") }
    }
}
