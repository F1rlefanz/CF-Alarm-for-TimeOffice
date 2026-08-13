package com.github.f1rlefanz.cf_alarmfortimeoffice.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Haelt die Backup-/Geraetetransfer-Regeln fest.
 *
 * Warum als Test und nicht "nur" als Kommentar in den XML-Dateien: Die Regeln sind reine
 * Konfiguration, die zur Laufzeit NUR beim Geraetewechsel greift - ein Fehler faellt also
 * genau dann auf, wenn er nicht mehr zu reparieren ist. Vor August 2026 nannten beide
 * Dateien ausschliesslich Pfade, die diese App nie angelegt hat (shift_config.xml,
 * alarm_settings.xml, ui_preferences.xml, app_preferences.xml, app_database.db). Weil eine
 * reine <include>-Liste alles Nicht-Genannte ausschliesst, war die effektive Backup-Menge
 * LEER, obwohl allowBackup="true" gesetzt war und der Kommentar "Same safe configuration for
 * better user experience" versprach.
 *
 * Geprueft wird deshalb:
 *  1. Die drei Bloecke (full-backup-content, cloud-backup, device-transfer) sind INHALTLICH
 *     IDENTISCH - sonst sichert dasselbe Geraet je nach Android-Version Unterschiedliches.
 *  2. Die vier geraetegebundenen/sensiblen Artefakte sind ueberall explizit ausgeschlossen.
 *  3. Keine der fuenf Phantom-Pfade taucht wieder auf.
 *  4. Jedes <exclude> liegt innerhalb eines <include>-Pfades derselben Domain - ein
 *     <exclude> ausserhalb ist ein No-Op UND eine Lint-FullBackupContent-Meldung (genau das
 *     hatte die geloeschte lint-baseline.xml 27-fach unterdrueckt).
 */
class BackupRulesConsistencyTest {

    private data class Rule(val kind: String, val domain: String, val path: String)

    /** Pfade, die es in dieser App nie gegeben hat (Stand vor der Korrektur). */
    private val phantomPaths = listOf(
        "shift_config.xml",
        "alarm_settings.xml",
        "ui_preferences.xml",
        "app_preferences.xml",
        "app_database.db"
    )

    /**
     * Muss ueberall ausgeschlossen bleiben:
     * - token_data_v2_encrypted: Tink-Ciphertext, Masterkey liegt im Keystore des alten Geraets
     * - auth_prefs: Anmeldezustand (login_status ohne Token = unbekannter Zustand)
     * - token_encryption_prefs.xml: das GERAETEGEBUNDENE Tink-Keyset (Anmeldeschleife)
     * - hue_trust_pinning.xml: Bridge-ID-Pin einer bestimmten physischen Bridge
     */
    private val mustBeExcluded = listOf(
        "file" to "datastore/token_data_v2_encrypted.preferences_pb",
        "file" to "datastore/auth_prefs.preferences_pb",
        "sharedpref" to "token_encryption_prefs.xml",
        "sharedpref" to "hue_trust_pinning.xml"
    )

    @Test
    fun `alle drei Bloecke sind inhaltlich identisch`() {
        val legacy = rulesOf(readXml("backup_rules.xml"), "full-backup-content")
        val cloud = rulesOf(readXml("data_extraction_rules.xml"), "cloud-backup")
        val transfer = rulesOf(readXml("data_extraction_rules.xml"), "device-transfer")

        assertTrue("backup_rules.xml enthaelt keine Regeln", legacy.isNotEmpty())
        assertEquals(
            "cloud-backup weicht von backup_rules.xml ab - beide Dateien muessen konsistent bleiben",
            legacy.toSet(),
            cloud.toSet()
        )
        assertEquals(
            "device-transfer weicht von cloud-backup ab - beide Bloecke muessen gepflegt werden",
            cloud.toSet(),
            transfer.toSet()
        )
    }

    @Test
    fun `sensible und geraetegebundene Pfade sind in jedem Block ausgeschlossen`() {
        forEachBlock { label, rules ->
            val excluded = rules.filter { it.kind == "exclude" }.map { it.domain to it.path }.toSet()
            mustBeExcluded.forEach { expected ->
                assertTrue(
                    "$label: ${expected.second} (domain=${expected.first}) ist NICHT ausgeschlossen",
                    expected in excluded
                )
            }
        }
    }

    @Test
    fun `keine Phantom-Pfade mehr in den Backup-Regeln`() {
        forEachBlock { label, rules ->
            rules.forEach { rule ->
                phantomPaths.forEach { phantom ->
                    if (rule.path.contains(phantom)) {
                        fail("$label: verweist wieder auf den nie existierenden Pfad '$phantom'")
                    }
                }
            }
        }
    }

    @Test
    fun `jedes exclude liegt innerhalb eines include derselben Domain`() {
        forEachBlock { label, rules ->
            val includesByDomain = rules.filter { it.kind == "include" }
                .groupBy({ it.domain }, { it.path })

            rules.filter { it.kind == "exclude" }.forEach { exclude ->
                val includes = includesByDomain[exclude.domain].orEmpty()
                val covered = includes.any { include ->
                    include == "." || exclude.path == include || exclude.path.startsWith("$include/")
                }
                assertTrue(
                    "$label: <exclude domain=\"${exclude.domain}\" path=\"${exclude.path}\"/> liegt " +
                        "ausserhalb jedes <include> derselben Domain - das ist ein No-Op und wird " +
                        "von Lint als FullBackupContent gemeldet",
                    covered
                )
            }
        }
    }

    @Test
    fun `die vom Nutzer konfigurierten DataStore-Dateien werden gesichert`() {
        forEachBlock { label, rules ->
            val fileIncludes = rules.filter { it.kind == "include" && it.domain == "file" }.map { it.path }
            assertTrue(
                "$label: das DataStore-Verzeichnis (Schichten, Dimmer-/DND-/Hue-Regeln, " +
                    "Kalenderauswahl) ist nicht im Backup",
                fileIncludes.any { it == "datastore" || it == "datastore/" }
            )
        }
    }

    // ------------------------------------------------------------------
    // Helfer
    // ------------------------------------------------------------------

    private fun forEachBlock(action: (String, List<Rule>) -> Unit) {
        action("backup_rules.xml/full-backup-content", rulesOf(readXml("backup_rules.xml"), "full-backup-content"))
        action("data_extraction_rules.xml/cloud-backup", rulesOf(readXml("data_extraction_rules.xml"), "cloud-backup"))
        action("data_extraction_rules.xml/device-transfer", rulesOf(readXml("data_extraction_rules.xml"), "device-transfer"))
    }

    private fun readXml(fileName: String): org.w3c.dom.Document {
        val relative = "src/main/res/xml/$fileName"
        // Das Arbeitsverzeichnis der Unit-Tests ist das Modul-Verzeichnis (app/); der
        // zweite Kandidat deckt einen Lauf aus dem Repo-Root ab.
        val file = listOf(File(relative), File("app/$relative")).firstOrNull { it.isFile }
            ?: fail("$relative nicht gefunden (Arbeitsverzeichnis: ${File(".").absolutePath})").let {
                error("unreachable")
            }
        return DocumentBuilderFactory.newInstance()
            .also { it.isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(file)
    }

    /** Liest alle <include>/<exclude>-Kinder des benannten Blocks. */
    private fun rulesOf(document: org.w3c.dom.Document, blockName: String): List<Rule> {
        val block = if (document.documentElement.tagName == blockName) {
            document.documentElement
        } else {
            document.getElementsByTagName(blockName).item(0) as? Element
                ?: fail("Block <$blockName> fehlt").let { error("unreachable") }
        }

        val rules = mutableListOf<Rule>()
        val children = block.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType != Node.ELEMENT_NODE) continue
            val element = child as Element
            if (element.tagName != "include" && element.tagName != "exclude") continue
            rules += Rule(
                kind = element.tagName,
                domain = element.getAttribute("domain"),
                path = element.getAttribute("path")
            )
        }
        return rules
    }
}
