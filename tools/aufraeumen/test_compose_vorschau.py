"""Testet die beiden Feinheiten, ohne die der Composable-Check falsch misst.

Beide sind am 25.08.2026 gemessen worden, und beide kosten je eine Richtung:

  1. OHNE "die Vorschau zaehlt nicht als Verbraucher" galt `NoAlarmCard` als benutzt, weil ihre
     eigene `@Preview` sie aufruft. Die Karte war seit dem INITIAL-COMMIT tot - 274 Zeilen samt
     versiegelter Grund-Hierarchie - und hat zwei ausdrueckliche Aufraeum-Commits ueberlebt.
     Das ist die stille Richtung: ein Fund, den niemand macht.
  2. OHNE den Trailing-Lambda-Aufruf (`Theme { ... }`, ohne Klammern) meldet der Check
     `CFAlarmForTimeOfficeTheme` - ein Composable, das die ganze App umschliesst. Das ist die
     laute Richtung: ein Fehlalarm, nach dem niemand mehr hinsieht.

Und eine dritte, die erst beim Bauen auffiel: Der Vorschau-BEREICH muss bei der `@Preview`-Zeile
beginnen, nicht bei der `fun`-Zeile. Dazwischen liegt das `@Composable`, und genau dort setzt die
Definitions-Erkennung an - eine Zeile spaeter meldete der Check JEDE Vorschau-Funktion als Karte
ohne Verbraucher.

Aufruf:
    python -m unittest discover -s tools/aufraeumen -p "test_*.py"
"""
from __future__ import annotations

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from pruefe_reste import COMPOSABLE_DEF, _preview_bereiche  # noqa: E402

UMBRUCH = chr(10)


def quelle(*zeilen):
    return UMBRUCH.join(zeilen)


class PreviewBereiche(unittest.TestCase):

    def test_der_bereich_beginnt_bei_der_annotation_nicht_bei_fun(self):
        text = quelle(
            "@Preview",
            "@Composable",
            "fun KartePreview() {",
            "    Karte()",
            "}",
        )

        self.assertEqual([(0, 4)], _preview_bereiche(text))

    def test_die_definitionserkennung_setzt_innerhalb_dieses_bereichs_an(self):
        """Der eigentliche Grund fuer die Zeile davor - hier zusammen gemessen."""
        text = quelle(
            "@Preview",
            "@Composable",
            "fun KartePreview() {",
            "    Karte()",
            "}",
        )

        treffer = list(COMPOSABLE_DEF.finditer(text))
        self.assertEqual(1, len(treffer))
        zeile_der_definition = text[:treffer[0].start()].count(UMBRUCH)
        (a, b), = _preview_bereiche(text)

        self.assertTrue(
            a <= zeile_der_definition <= b,
            "Die Vorschau-Definition muss im eigenen Bereich liegen, sonst meldet der Check sie",
        )

    def test_mehrere_vorschauen_werden_einzeln_erfasst(self):
        text = quelle(
            "@Preview",
            "@Composable",
            "fun EinsPreview() {",
            "    Eins()",
            "}",
            "",
            "@Preview",
            "@Composable",
            "fun ZweiPreview() {",
            "    Zwei()",
            "}",
        )

        self.assertEqual([(0, 4), (6, 10)], _preview_bereiche(text))

    def test_ohne_vorschau_gibt_es_keinen_bereich(self):
        text = quelle("@Composable", "fun Karte() {", "    Text()", "}")

        self.assertEqual([], _preview_bereiche(text))


class ComposableErkennung(unittest.TestCase):

    def test_sichtbarkeitsmodifizierer_stoeren_nicht(self):
        for modifizierer in ("", "private ", "internal "):
            text = quelle("@Composable", modifizierer + "fun Karte() {", "}")

            self.assertEqual(
                ["Karte"],
                [m.group(1) for m in COMPOSABLE_DEF.finditer(text)],
                "Modifizierer %r nicht erkannt" % modifizierer,
            )

    def test_weitere_annotationen_zwischen_composable_und_fun_stoeren_nicht(self):
        text = quelle("@Composable", "@OptIn(ExperimentalMaterial3Api::class)", "fun Karte() {", "}")

        self.assertEqual(["Karte"], [m.group(1) for m in COMPOSABLE_DEF.finditer(text)])


if __name__ == "__main__":
    unittest.main()
