package com.github.f1rlefanz.cf_alarmfortimeoffice.hue

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Jede Klasse, die Gson aus einer Bridge-Antwort befuellt, muss in einem Paket liegen, das
 * `proguard-rules.pro` ganz haelt. Gson erzeugt und befuellt sie reflexiv - R8 sieht keinen
 * Konstruktoraufruf und entfernt sie aus dem Release-Build. Der Debug-Build und alle Unit-Tests
 * zeigen davon nichts, erst die ausgelieferte App scheitert beim Parsen.
 *
 * Gemessen am 29.09.2026: `HueSceneDto` lag in `hue.api`, fehlte im Release-Dex, und der
 * Regel-Editor meldete bei "Szene" dauerhaft "Die Szenen konnten nicht von der Bridge geladen
 * werden" - im Debug-Build derselben Bridge lud die Liste.
 */
class GsonZielklassenR8Test {

    private fun datei(vararg kandidaten: String): File =
        kandidaten.map { File(it) }.firstOrNull { it.exists() }
            ?: error("nicht gefunden: ${kandidaten.toList()} (Arbeitsverzeichnis ${File(".").absolutePath})")

    private val quellen: List<File> by lazy {
        datei("src/main/java", "app/src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
    }

    /** Pakete, die eine aktive Regel der Form `-keep class <paket>.** { *; }` ganz haelt. */
    private val gehaltenePakete: List<String> by lazy {
        val muster = Regex("""^-keep class ([\w.]+)\.\*\* \{ \*; \}$""")
        datei("proguard-rules.pro", "app/proguard-rules.pro").readLines()
            .mapNotNull { muster.find(it.trim())?.groupValues?.get(1) }
    }

    /** Einfacher Klassenname -> Paket, aus den Deklarationen im Produktivcode. */
    private val paketVon: Map<String, String> by lazy {
        val paketZeile = Regex("""^package ([\w.]+)""", RegexOption.MULTILINE)
        val deklaration = Regex("""\b(?:class|object)\s+([A-Z]\w*)""")
        buildMap {
            quellen.forEach { f ->
                val text = f.readText()
                val paket = paketZeile.find(text)?.groupValues?.get(1) ?: return@forEach
                deklaration.findAll(text).forEach { putIfAbsent(it.groupValues[1], paket) }
            }
        }
    }

    /** Alle eigenen Klassen, die als Ziel von `TypeToken<...>` oder `fromJson(x, Y::class.java)` auftreten. */
    private fun gsonZiele(): Set<String> {
        val typeToken = Regex("""TypeToken<(.+)>\(\)""")
        val klasse = Regex("""fromJson\([^,]+,\s*([A-Z]\w*)::class\.java\)""")
        val bezeichner = Regex("""\b[A-Z]\w*\b""")
        return quellen.flatMap { f ->
            val text = f.readText()
            typeToken.findAll(text).flatMap { t -> bezeichner.findAll(t.groupValues[1]).map { it.value } }.toList() +
                klasse.findAll(text).map { it.groupValues[1] }.toList()
        }.filter { paketVon[it]?.startsWith("com.github.f1rlefanz") == true }.toSet()
    }

    @Test
    fun `Gson-Zielklassen werden gefunden - sonst misst der Test nichts`() {
        val ziele = gsonZiele()
        listOf("HueLight", "HueGroup", "BridgeSchedule", "HueBridgeConfig").forEach {
            assertTrue("$it fehlt unter den gefundenen Gson-Zielen $ziele", it in ziele)
        }
        assertTrue("keine gehaltenen Pakete gelesen", gehaltenePakete.isNotEmpty())
    }

    @Test
    fun `jede Gson-Zielklasse liegt in einem Paket, das R8 ganz haelt`() {
        val ungeschuetzt = gsonZiele().filterNot { name ->
            val paket = paketVon.getValue(name)
            gehaltenePakete.any { paket == it || paket.startsWith("$it.") }
        }
        assertTrue(
            "Diese Gson-Zielklassen entfernt R8 im Release-Build: " +
                ungeschuetzt.map { "$it (${paketVon[it]})" } + " - in ein gehaltenes Paket legen (hue/data)",
            ungeschuetzt.isEmpty()
        )
    }
}
