"""Testet die Changelog-Diff-Logik der Schleuse.

WARUM ES DAS GIBT
-----------------
`neue_eintraege()` entscheidet, ob ein Push blockiert wird, weil zu
nutzersichtbaren Aenderungen kein Changelog-Eintrag kam. Ein Fehler DARIN ist
still: falsch-negativ heisst, ein fehlender Eintrag rutscht durch, und genau
dagegen wurde die Pruefung gebaut. Die Funktion ist reine Textverarbeitung ohne
Seiteneffekte - der billigste Test des ganzen Projekts, und der einzige Teil der
Schleuse, der eigene Logik statt eines Aufrufs enthaelt.

Das Parallelprojekt Wunschkalender hat denselben Algorithmus und daneben seit
jeher einen Test (`tools/changelog-pruefung.test.mjs`). Hier fehlte er bis zum
21.08.2026.

Aufruf:
    python -m unittest discover -s tools -p "test_*.py"
    python tools/schleuse/test_changelog_diff.py     # einzeln
"""
from __future__ import annotations

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from pruefe_schleuse import changelog_eintraege, neue_eintraege  # noqa: E402

VORSPANN = "# Changelog\n\nEin Vorspann mit einer Aufzaehlung, die NICHT zaehlt:\n- Vorspannpunkt\n"


class ChangelogEintraege(unittest.TestCase):
    def test_vorspann_zaehlt_nicht(self):
        """Bullets vor der ersten `##`-Ueberschrift sind Fliesstext, kein Eintrag.

        Der echte CHANGELOG.md-Vorspann erklaert die Schreibkonventionen und
        enthaelt dabei selbst Aufzaehlungen - wuerden die mitzaehlen, waere ab
        dem ersten Lauf immer "ein Eintrag da".
        """
        self.assertEqual(changelog_eintraege(VORSPANN), [])

    def test_eintraege_nach_ueberschrift(self):
        text = VORSPANN + "\n## Version 1.0.0\n\n- **A:** erster\n- **B:** zweiter\n"
        self.assertEqual(
            changelog_eintraege(text), ["- **A:** erster", "- **B:** zweiter"]
        )

    def test_fortsetzungszeile_gehoert_zum_eintrag(self):
        """Ein umbrochener Eintrag ist EIN Eintrag, nicht zwei."""
        text = "## V\n- **A:** erste Zeile\n  zweite Zeile\n"
        self.assertEqual(changelog_eintraege(text), ["- **A:** erste Zeile zweite Zeile"])

    def test_leerraum_wird_normalisiert(self):
        """Nur Umbruch/Einrueckung geaendert = derselbe Eintrag, kein neuer."""
        alt = "## V\n- **A:** ein sehr langer Eintrag ueber zwei Zeilen\n"
        neu = "## V\n- **A:** ein sehr langer\n  Eintrag ueber zwei Zeilen\n"
        self.assertEqual(changelog_eintraege(alt), changelog_eintraege(neu))

    def test_sternchen_zaehlt_wie_strich(self):
        self.assertEqual(len(changelog_eintraege("## V\n* **A:** x\n")), 1)


class NeueEintraege(unittest.TestCase):
    def test_neuer_eintrag_wird_erkannt(self):
        alt = "## V1\n- **A:** alt\n"
        neu = "## V2\n- **B:** neu\n\n## V1\n- **A:** alt\n"
        self.assertEqual(neue_eintraege(alt, neu), ["- **B:** neu"])

    def test_unveraendert_ergibt_nichts(self):
        text = "## V1\n- **A:** alt\n"
        self.assertEqual(neue_eintraege(text, text), [])

    def test_basis_ohne_changelog(self):
        """`git show <basis>:CHANGELOG.md` scheitert -> None. Alles ist dann neu."""
        self.assertEqual(neue_eintraege(None, "## V\n- **A:** x\n"), ["- **A:** x"])

    def test_woertliche_wiederholung_zaehlt_als_neu(self):
        """Multimenge, nicht Menge.

        Steht derselbe Satz schon einmal drin und wird ein zweites Mal
        hinzugefuegt, ist das ein neuer Eintrag. Eine Menge wuerde ihn
        schlucken und den Push faelschlich blockieren.
        """
        alt = "## V1\n- **A:** derselbe Satz\n"
        neu = "## V2\n- **A:** derselbe Satz\n\n## V1\n- **A:** derselbe Satz\n"
        self.assertEqual(neue_eintraege(alt, neu), ["- **A:** derselbe Satz"])

    def test_umbenennen_der_obersten_version_ist_kein_eintrag(self):
        """Der Fall, an dem die naive Pruefung scheitert.

        Beim Release verliert die bisher oberste Version ihr Emoji und den
        Zusatz "(Aktuell - interne Alpha)". Wer nur fragt "steht oben etwas",
        haelt das faelschlich fuer einen neuen Eintrag; gefragt ist, ob ein
        EINTRAG hinzukam.
        """
        alt = "## \U0001f195 Version 1.0.0 (Aktuell)\n- **A:** x\n"
        neu = "## Version 1.0.0\n- **A:** x\n"
        self.assertEqual(neue_eintraege(alt, neu), [])

    def test_geloeschter_eintrag_ist_kein_neuer(self):
        alt = "## V\n- **A:** x\n- **B:** y\n"
        neu = "## V\n- **A:** x\n"
        self.assertEqual(neue_eintraege(alt, neu), [])


class EchterChangelog(unittest.TestCase):
    """Gegenprobe am echten CHANGELOG.md - ein Muster, das ins Leere greift, faellt sonst nie auf."""

    def test_echte_datei_liefert_viele_eintraege(self):
        import io

        pfad = os.path.join(
            os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
            "CHANGELOG.md",
        )
        if not os.path.exists(pfad):
            self.skipTest("CHANGELOG.md nicht gefunden")
        with io.open(pfad, encoding="utf-8") as datei:
            eintraege = changelog_eintraege(datei.read())
        self.assertGreater(len(eintraege), 100, "Muster greift ins Leere?")
        self.assertTrue(all(e.startswith(("- ", "* ")) for e in eintraege))


if __name__ == "__main__":
    unittest.main(verbosity=2)
