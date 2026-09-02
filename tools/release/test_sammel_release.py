"""Tests fuer das Sammel-Release.

Geprueft wird die Entscheidung, nicht die Git-Anbindung: ob ein Commit-Betreff als Wartung gilt,
wie die naechste Version aussieht, und dass der Changelog-Block der bisher obersten Version ihr
`🆕` wirklich abnimmt. Die Betreffs unten sind ECHTE Zeilen aus der Historie dieses Repos - der
Detektor muss an genau denen haengen, nicht an ausgedachten.
"""
import unittest

from sammel_release import (
    changelog_block,
    ist_wartungscommit,
    lies_version,
    naechste_version,
    schreibe_version,
    setze_neuen_block,
)


class WartungsErkennung(unittest.TestCase):
    def test_dependabot_squash(self):
        # 580f549, e6b3760 - Dependabot squasht, der Praefix bleibt im Betreff stehen.
        self.assertTrue(ist_wartungscommit("chore(deps): Bump com.google.android.gms:play-services-auth (#47)"))
        self.assertTrue(ist_wartungscommit("chore(deps): Bump the libraries group with 2 updates (#43)"))

    def test_aufraeumrunde(self):
        # 7f4760d
        self.assertTrue(ist_wartungscommit(
            "chore(aufraeumen): bewusstes commit() in der Weckerkette gegen Lint verankern (#53)"))

    def test_ci_bumps(self):
        # 67766a5 - Dependabot fuer Actions, aendert nur .github/workflows.
        self.assertTrue(ist_wartungscommit("chore(ci): Bump actions/setup-java from 5 to 6 (#46)"))

    def test_merge_commit_des_torwaechters(self):
        # 725fb90 - der Torwaechter merged mit `--merge`, es entsteht ein Merge-Commit.
        self.assertTrue(ist_wartungscommit(
            "Merge pull request #35 from F1rlefanz/chore/aufraeumen-ueberholte-suppress-notizen"))
        self.assertTrue(ist_wartungscommit(
            "Merge branch 'chore/aufraeumen-tote-suppress'"))

    def test_dependabot_merge_commit(self):
        self.assertTrue(ist_wartungscommit(
            "Merge pull request #47 from F1rlefanz/dependabot/gradle/play-services-auth-21.4.0"))

    def test_inhaltliches_blockiert(self):
        """Der wichtigste Test: alles Inhaltliche muss die Automatik anhalten."""
        for betreff in [
            "feat(ui): ein Abgleich statt drei Knoepfen, ehrliche Zeitstempel (v1.39.0)",
            "fix(ui): der Speichern-Knopf der Kalenderauswahl luegt nicht mehr (#50)",
            "docs: die Play-Auslieferung war nirgends dokumentiert",
            "refactor(hue): eine Karte je Betriebsart im Regel-Editor",
            "chore(release): v1.39.1 (versionCode 127)",
            "Merge pull request #12 from F1rlefanz/feature/hue-szenen",
            "Merge branch 'fix/torwaechter-stummer-lauf'",
        ]:
            with self.subTest(betreff=betreff):
                self.assertFalse(ist_wartungscommit(betreff))

    def test_chore_release_gilt_nicht_als_wartung(self):
        """Sonst wuerde ein Sammel-Release den naechsten ausloesen - eine Endlosschleife."""
        self.assertFalse(ist_wartungscommit("chore(release): v1.39.2 (versionCode 128)"))


class Versionsfortschreibung(unittest.TestCase):
    def test_patch_bump(self):
        self.assertEqual(naechste_version("1.39.1", 127), ("1.39.2", 128))
        self.assertEqual(naechste_version("1.39.9", 200), ("1.39.10", 201))

    def test_kein_minor_oder_major(self):
        name, _ = naechste_version("1.39.1", 127)
        self.assertTrue(name.startswith("1.39."))

    def test_unsinnige_version_wirft(self):
        with self.assertRaises(ValueError):
            naechste_version("1.39", 127)

    def test_lesen_und_schreiben(self):
        gradle = 'defaultConfig {\n  versionCode = 127\n  versionName = "1.39.1"\n}\n'
        self.assertEqual(lies_version(gradle), ("1.39.1", 127))
        neu = schreibe_version(gradle, "1.39.2", 128)
        self.assertEqual(lies_version(neu), ("1.39.2", 128))
        # Nur die beiden Zeilen duerfen sich geaendert haben.
        self.assertEqual(len(neu.splitlines()), len(gradle.splitlines()))

    def test_versionname_suffixe_bleiben_unberuehrt(self):
        """`versionNameSuffix` steht in derselben Datei und darf nicht mitwandern."""
        gradle = (
            'versionCode = 127\nversionName = "1.39.1"\n'
            'buildTypes {\n  debug { versionNameSuffix = "-DEBUG" }\n}\n'
        )
        neu = schreibe_version(gradle, "1.39.2", 128)
        self.assertIn('versionNameSuffix = "-DEBUG"', neu)
        self.assertIn('versionName = "1.39.2"', neu)


class ChangelogBlock(unittest.TestCase):
    KOPF = "# Changelog\n\nVorspann.\n\n"

    def test_nimmt_der_bisherigen_version_das_abzeichen(self):
        alt = self.KOPF + "## 🆕 Version 1.39.1 (Aktuell – interne Alpha)\n\ninhalt\n"
        neu = setze_neuen_block(alt, changelog_block("1.39.2", 3, 2026, 9))
        self.assertIn("## 🆕 Version 1.39.2 (Aktuell – interne Alpha)", neu)
        self.assertIn("## Version 1.39.1", neu)
        self.assertEqual(neu.count("🆕"), 1)

    def test_neuer_block_steht_oben(self):
        alt = self.KOPF + "## 🆕 Version 1.39.1 (Aktuell – interne Alpha)\n\ninhalt\n"
        neu = setze_neuen_block(alt, changelog_block("1.39.2", 1, 2026, 9))
        self.assertLess(neu.index("Version 1.39.2"), neu.index("Version 1.39.1"))
        self.assertTrue(neu.startswith(self.KOPF))

    def test_vorspann_bleibt_erhalten(self):
        alt = self.KOPF + "## Version 1.39.1\n\ninhalt\n"
        neu = setze_neuen_block(alt, changelog_block("1.39.2", 2, 2026, 9))
        self.assertTrue(neu.startswith(self.KOPF))
        self.assertIn("## Version 1.39.1", neu)

    def test_singular_und_plural(self):
        self.assertIn("Eine Änderung ohne sichtbare Wirkung", changelog_block("1.0.1", 1, 2026, 9))
        self.assertIn("4 Änderungen ohne sichtbare Wirkung", changelog_block("1.0.1", 4, 2026, 9))

    def test_form_die_der_generator_braucht(self):
        """`tools/changelog/build_changelog.py` verlaesst sich auf genau diese drei Zeilen."""
        block = changelog_block("1.39.2", 2, 2026, 9)
        self.assertIn("## 🆕 Version 1.39.2 (Aktuell – interne Alpha)\n", block)
        self.assertIn("**Stand:** September 2026\n", block)
        self.assertIn("_Wartungsversion", block)
        self.assertIn("### 🔧 Unter der Haube\n", block)

    def test_ohne_versionsueberschrift_wirft(self):
        with self.assertRaises(ValueError):
            setze_neuen_block("# Changelog\n\nnur Vorspann\n", changelog_block("1.0.1", 1, 2026, 9))


if __name__ == "__main__":
    unittest.main()
