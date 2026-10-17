package com.github.f1rlefanz.cf_alarmfortimeoffice.model

import com.github.f1rlefanz.cf_alarmfortimeoffice.backup.ConfigBackup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Umbau (SPEC Abschnitt 4, Testvorlage): eine echte Konfiguration von vor dem Umbau muss
 * verlustfrei gelesen werden, und die abgeleiteten Kategorien muessen zu den Flags passen.
 *
 * Zwei Vorlagen:
 * - `umbau/testvorlage-nachgebaut.json` (im Repo, laeuft in der CI): dieselben Faelle wie die echte -
 *   7 Typen, mehrere Muster je Typ, ein stiller Rufbereitschafts-Typ, ein Krank-Typ - aber mit
 *   ERFUNDENEN Kuerzeln und Namen, denn das Repo ist oeffentlich.
 * - die echte Datei des Eigentuemers unter `..Projektdateien/umbau/testvorlagen/` (gitignored, nur
 *   lokal). Fehlt sie, wird der Test mit Meldung UEBERSPRUNGEN, nie still gruen.
 *
 * „Verlustfrei“ heisst hier: jedes Feld der Datei steht nach Lesen und erneutem Schreiben mit
 * demselben Wert da, und ein zweites Lesen ergibt dasselbe Objekt. Die neuen Felder kommen beim
 * Schreiben hinzu (`encodeDefaults`), alte fallen nie weg. Json-Flags wie `ConfigBackupUseCase`.
 */
class UmbauTestvorlageTest {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `nachgebaute Vorlage wird verlustfrei gelesen und die Kategorien passen zu den Flags`() {
        val text = checkNotNull(javaClass.classLoader?.getResource(NACHGEBAUT)) { "$NACHGEBAUT fehlt" }.readText()

        val config = pruefeVerlustfreiUndDeckungsgleich(text)

        // Die Faelle der echten Vorlage - faellt einer weg, deckt die CI ihn nicht mehr ab.
        assertEquals(7, config.definitions.size)
        assertEquals(listOf(2, 2, 2, 1, 1, 1, 1), config.definitions.map { it.keywords.size })
        val ruf = config.definitions.single { it.kategorie == ShiftKategorie.RUFBEREITSCHAFT }
        assertTrue("der Ruf-Typ ist still", ruf.isSilent)
        assertEquals(1, config.definitions.count { it.kategorie == ShiftKategorie.KRANK })
        assertEquals(5, config.definitions.count { it.kategorie == ShiftKategorie.DIENST })
        assertEquals("Vorlage von vor dem Umbau: kein Verweis", null, config.ohneKalendereintragWie)
    }

    @Test
    fun `echte Vorlage des Eigentuemers wird verlustfrei gelesen, falls lokal vorhanden`() {
        val kandidaten = kandidatenFuerEchteVorlage()
        val datei = kandidaten.firstOrNull { it.isFile }
        assumeTrue(
            "Echte Testvorlage nicht vorhanden (nur lokal beim Eigentuemer) - uebersprungen. Gesucht: " +
                kandidaten.joinToString { it.path },
            datei != null
        )

        val config = pruefeVerlustfreiUndDeckungsgleich(datei!!.readText(Charsets.UTF_8))

        assertTrue("die echte Vorlage hat Typen", config.definitions.isNotEmpty())
    }

    /** Liest [text] als Exportdatei und prueft die beiden Zusagen; gibt die gelesene Konfiguration zurueck. */
    private fun pruefeVerlustfreiUndDeckungsgleich(text: String): ShiftConfig {
        val gelesen = json.decodeFromString(ConfigBackup.serializer(), text)
        val config = checkNotNull(gelesen.shiftConfig) { "Vorlage ohne shiftConfig" }

        // 1. Kein Feld der Datei geht verloren oder aendert seinen Wert.
        val roh = json.parseToJsonElement(text).jsonObject.getValue("shiftConfig").jsonObject
        val neuGeschrieben = json.encodeToJsonElement(ShiftConfig.serializer(), config).jsonObject
        pruefeFelderErhalten("shiftConfig", roh, neuGeschrieben)
        val rohTypen = roh.getValue("definitions").jsonArray
        val neuTypen = neuGeschrieben.getValue("definitions").jsonArray
        assertEquals("keine zusaetzliche oder fehlende Schicht", rohTypen.size, neuTypen.size)
        rohTypen.zip(neuTypen).forEachIndexed { i, (alt, neu) ->
            pruefeFelderErhalten("Typ #$i", alt.jsonObject, neu.jsonObject)
        }
        assertEquals(
            "zweites Lesen ergibt dasselbe",
            config,
            json.decodeFromString(ShiftConfig.serializer(), json.encodeToString(ShiftConfig.serializer(), config))
        )

        // 2. Die abgeleitete Kategorie passt zu den Flags.
        config.definitions.forEachIndexed { i, typ ->
            assertEquals("Typ #$i: isSickDay <-> Krank", typ.isSickDay, typ.kategorie == ShiftKategorie.KRANK)
            assertEquals(
                "Typ #$i: isOnCall (ohne Krank) <-> Rufbereitschaft",
                typ.isOnCall && !typ.isSickDay,
                typ.kategorie == ShiftKategorie.RUFBEREITSCHAFT
            )
            assertTrue("Typ #$i: Wecker an ist Vorgabe", typ.weckerAn)
        }
        return config
    }

    private fun pruefeFelderErhalten(wo: String, roh: JsonObject, neu: JsonObject) {
        // Keine Feldwerte in der Meldung - die echte Vorlage enthaelt Kuerzel des Eigentuemers.
        for ((schluessel, wert) in roh) {
            assertTrue("$wo: Feld '$schluessel' fehlt nach dem Schreiben", schluessel in neu)
            if (schluessel != "definitions") {
                assertTrue("$wo: Feld '$schluessel' hat sich geaendert", wert == neu.getValue(schluessel))
            }
        }
    }

    /**
     * Wo die echte Vorlage liegen kann. Gradle-Unit-Tests laufen in `app/`; gesucht wird in jedem
     * uebergeordneten Ordner - und, weil der Umbau in Worktrees laeuft (`..\CFAlarm-umbau-wt\<nr>`),
     * zusaetzlich im Hauptbaum, auf den die `.git`-DATEI eines Worktrees zeigt
     * (`gitdir: <hauptbaum>/.git/worktrees/<name>`).
     */
    private fun kandidatenFuerEchteVorlage(): List<File> {
        val wurzeln = mutableListOf<File>()
        var ordner: File? = File(System.getProperty("user.dir")).absoluteFile
        while (ordner != null) {
            wurzeln += ordner
            val git = File(ordner, ".git")
            if (git.isFile) {
                val gitdir = git.readLines().firstOrNull { it.startsWith("gitdir:") }
                    ?.removePrefix("gitdir:")?.trim()?.replace('\\', '/')
                val hauptbaum = gitdir?.substringBefore("/.git/worktrees/", missingDelimiterValue = "")
                if (!hauptbaum.isNullOrEmpty()) wurzeln += File(hauptbaum)
            }
            ordner = ordner.parentFile
        }
        return wurzeln.distinct().map { File(it, ECHTE_VORLAGE) }
    }

    private companion object {
        const val NACHGEBAUT = "umbau/testvorlage-nachgebaut.json"
        const val ECHTE_VORLAGE = "..Projektdateien/umbau/testvorlagen/konfiguration-eigentuemer-2026-10-16.json"
    }
}
