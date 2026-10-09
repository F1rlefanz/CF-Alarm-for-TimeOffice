"""Belegt, dass pruefe_hilfe.py einen gerissenen Anker und einen verschwundenen Begriff meldet.

Gegen das echte Repo ist das Skript gruen - ohne diese Tests waere nicht zu unterscheiden, ob es
prueft oder nur nichts findet.
"""
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pruefe_hilfe  # noqa: E402

THEMA = '''
enum class HilfeSeite(val datei: String) {
    ANLEITUNG("anleitung.html"),
}
enum class HilfeThema(val seite: HilfeSeite, val anker: String?) {
    ANLEITUNG(HilfeSeite.ANLEITUNG, null),
    ANLEITUNG_WECKER(HilfeSeite.ANLEITUNG, "wecker");
    companion object { const val BASIS_URL = "https://beispiel.org/" }
}
'''


class PruefeHilfeTest(unittest.TestCase):
    def setUp(self):
        self.ordner = tempfile.TemporaryDirectory()
        d = self.ordner.name
        self.thema = os.path.join(d, "HilfeThema.kt")
        with open(self.thema, "w", encoding="utf-8") as f:
            f.write(THEMA)
        with open(os.path.join(d, "CNAME"), "w", encoding="utf-8") as f:
            f.write("beispiel.org\n")
        self.seite = os.path.join(d, "anleitung.html")
        self._alt = (pruefe_hilfe.HILFE_THEMA, pruefe_hilfe.DOCS)
        pruefe_hilfe.HILFE_THEMA, pruefe_hilfe.DOCS = self.thema, d

    def tearDown(self):
        pruefe_hilfe.HILFE_THEMA, pruefe_hilfe.DOCS = self._alt
        self.ordner.cleanup()

    def anker_befunde(self, html):
        with open(self.seite, "w", encoding="utf-8") as f:
            f.write(html)
        befunde = []
        pruefe_hilfe.pruefe_anker(befunde)
        return befunde

    def test_vorhandener_anker_ist_gruen(self):
        self.assertEqual([], self.anker_befunde('<section id="wecker">'))

    def test_fehlender_anker_wird_gemeldet(self):
        befunde = self.anker_befunde('<section id="wecker-neu">')
        self.assertEqual(1, len(befunde))
        self.assertIn('id="wecker"', befunde[0])

    def test_echtes_repo_findet_themen(self):
        pruefe_hilfe.HILFE_THEMA, pruefe_hilfe.DOCS = self._alt
        befunde = []
        pruefe_hilfe.pruefe_anker(befunde)
        self.assertEqual([], befunde)

    def test_begriffe_in_anfuehrungszeichen_werden_erkannt(self):
        self.assertEqual(["Neue Regel"], pruefe_hilfe.BEGRIFF.findall("Tippe auf „Neue Regel“."))


if __name__ == "__main__":
    unittest.main()
