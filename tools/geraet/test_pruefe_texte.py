import unittest

import pruefe_texte as p

DUMP = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation="0">
<node text="" bounds="[0,0][1080,2400]">
  <node text="Gestellte Wecker" bounds="[40,900][600,960]" />
  <node text="Manueller Wecker" bounds="[40,200][600,260]" />
  <node text="" content-desc="Probehören" bounds="[900,500][1000,560]" />
  <node text="Wie der Wecker klingt" bounds="[40,500][600,560]" />
</node></hierarchy>"""


class TexteAusDump(unittest.TestCase):
    def test_sortiert_nach_lage_nicht_nach_baum(self):
        self.assertEqual(p.texte_aus_dump(DUMP),
                         ["Manueller Wecker", "Wie der Wecker klingt", "Probehören", "Gestellte Wecker"])


class Zusammenfuehren(unittest.TestCase):
    def test_ueberlappung_zaehlt_einmal(self):
        self.assertEqual(p.zusammenfuehren(["a", "b", "c"], ["b", "c", "d"]), ["a", "b", "c", "d"])

    def test_ohne_ueberlappung_wird_angehaengt(self):
        self.assertEqual(p.zusammenfuehren(["a"], ["x"]), ["a", "x"])


class Pruefe(unittest.TestCase):
    GEFUNDEN = ["Manueller Wecker", "Dienst fällt aus", "Wie der Wecker klingt", "Gestellte Wecker"]

    def test_bestanden(self):
        self.assertEqual(p.pruefe(self.GEFUNDEN, ["Manueller", "Dienst fällt aus", "Gestellte"], []), [])

    def test_vertauschte_reihenfolge_ist_befund(self):
        befunde = p.pruefe(self.GEFUNDEN, ["Dienst fällt aus", "Manueller Wecker"], [])
        self.assertEqual(len(befunde), 1)
        self.assertTrue(befunde[0].startswith("Reihenfolge"))

    def test_fehlender_text_ist_befund(self):
        self.assertEqual(p.pruefe(self.GEFUNDEN, ["Schicht-Dimmer"], []), ['fehlt: "Schicht-Dimmer"'])

    def test_verbotener_text_ist_befund(self):
        befunde = p.pruefe(self.GEFUNDEN + ["Prototyp – Werte sind Beispiele"], [], ["Prototyp"])
        self.assertEqual(len(befunde), 1)

    def test_erwartung_liest_verbote_und_kommentare(self):
        self.assertEqual(p.lies_erwartung(["# B7", "Übersicht", "", "!Schicht-Dimmer"]),
                         (["Übersicht"], ["Schicht-Dimmer"]))


class NurEmulator(unittest.TestCase):
    def test_fremdes_geraet_wird_abgelehnt(self):
        import sys
        alt = sys.argv
        sys.argv = ["x", "--datei", "egal.txt", "--serial", "FP6ABC"]
        try:
            self.assertEqual(p.main(), 2)
        finally:
            sys.argv = alt


if __name__ == "__main__":
    unittest.main()
