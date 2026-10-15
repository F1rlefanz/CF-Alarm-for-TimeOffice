"""Testet, dass `pruefe_kerntests.py` jeden stillen Weg meldet, einen Kerntest loszuwerden.

Ohne diesen Test waere die Pruefung eine Behauptung: ein Parser, der eine Markierung nicht mehr
erkennt, meldet "alles deckungsgleich" genauso gruen wie ein intakter.
"""
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pruefe_kerntests  # noqa: E402

TESTDATEI = '''package com.github.f1rlefanz.cf_alarmfortimeoffice.usecase

import org.junit.Test
import org.junit.experimental.categories.Category

class BeispielTest {

    @Category(Kerntest::class)
    @Test
    fun `der Wecker klingelt`() = runTest {
    }

    @Test
    fun `nur Komfort`() {
    }
}
'''


class PruefeKerntestsTest(unittest.TestCase):
    def setUp(self):
        self.ordner = tempfile.TemporaryDirectory()
        self.wurzel = os.path.join(self.ordner.name, "test")
        os.makedirs(os.path.join(self.wurzel, "usecase"))
        self.datei = os.path.join(self.wurzel, "usecase", "BeispielTest.kt")
        self.liste = os.path.join(self.ordner.name, "kerntests.txt")
        self._schreibe(self.datei, TESTDATEI)
        self._schreibe(self.liste, "# Kopf\ntest:usecase/BeispielTest.kt::der Wecker klingelt\n")

    def tearDown(self):
        self.ordner.cleanup()

    @staticmethod
    def _schreibe(pfad, text):
        with open(pfad, "w", encoding="utf-8") as f:
            f.write(text)

    def _befunde(self):
        return pruefe_kerntests.pruefe(self.liste, {"test": self.wurzel})

    def _ersetze(self, alt, neu):
        self._schreibe(self.datei, TESTDATEI.replace(alt, neu))

    def test_deckungsgleich_meldet_nichts(self):
        self.assertEqual([], self._befunde())

    def test_geloeschter_kerntest_wird_gemeldet(self):
        self._ersetze("    @Category(Kerntest::class)\n    @Test\n    fun `der Wecker klingelt`() = runTest {\n    }\n", "")
        self.assertIn("fehlt", " ".join(self._befunde()))

    def test_umbenannter_kerntest_wird_gemeldet(self):
        self._ersetze("der Wecker klingelt", "der Wecker klingelt vielleicht")
        befunde = " ".join(self._befunde())
        self.assertIn("fehlt", befunde)
        self.assertIn("nachfuehren", befunde)

    def test_entfernte_markierung_wird_gemeldet(self):
        self._ersetze("    @Category(Kerntest::class)\n", "")
        self.assertIn("fehlt", " ".join(self._befunde()))

    def test_markierung_ohne_listeneintrag_wird_gemeldet(self):
        self._ersetze("    @Test\n    fun `nur Komfort`", "    @Category(Kerntest::class)\n    @Test\n    fun `nur Komfort`")
        self.assertIn("nachfuehren", " ".join(self._befunde()))

    def test_verlorenes_test_wird_gemeldet(self):
        self._ersetze("    @Category(Kerntest::class)\n    @Test\n", "    @Category(Kerntest::class)\n")
        self.assertIn("kein @Test", " ".join(self._befunde()))

    def test_ignore_an_methode_wird_gemeldet(self):
        self._ersetze("    @Category(Kerntest::class)\n", "    @Ignore(\"spaeter\")\n    @Category(Kerntest::class)\n")
        self.assertIn("@Ignore", " ".join(self._befunde()))

    def test_ignore_an_klasse_wird_gemeldet(self):
        self._ersetze("class BeispielTest {", "@Ignore\nclass BeispielTest {")
        self.assertIn("@Ignore", " ".join(self._befunde()))

    def test_echte_liste_ist_deckungsgleich(self):
        self.assertEqual([], pruefe_kerntests.pruefe())


if __name__ == "__main__":
    unittest.main()
