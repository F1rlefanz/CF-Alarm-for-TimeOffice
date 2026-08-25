"""Testet die Erkennung von KDoc-Bloecken, die nichts mehr beschreiben.

WARUM ES DIESE PRUEFUNG GIBT (25.08.2026): Der Aufraeumlauf v1.34.3 entfernte 35 ungenutzte
Konstanten und liess ELF ihrer KDoc-Kommentare stehen. Zurueck blieben Zeilen wie
`/** Halbe Breite fuer zweispaltige Layouts */` direkt vor der schliessenden Klammer, dazu zwei
Objekte, die dadurch leer dastanden. Ein Aufraeumen, das seine eigenen Narben hinterlaesst - und
niemandem faellt es auf, weil so eine Zeile voellig normal aussieht.

WARUM SO ENG GEFASST: Die weite Fassung ("KDoc, dem keine Deklaration folgt") liefert in diesem
Projekt 79 Treffer, ganz ueberwiegend falsch - Enum-Eintraege, Konstruktor-Parameter und der
voellig normale Fall "Datei-KDoc, dann Klassen-KDoc". Diese Tests halten die enge Fassung fest,
damit sie niemand spaeter "verbessert" und sich damit 78 Fehlalarme einhandelt.

Aufruf:
    python -m unittest discover -s tools/aufraeumen -p "test_*.py"
"""
from __future__ import annotations

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from pruefe_reste import haengende_kdocs_in  # noqa: E402

UMBRUCH = chr(10)


def quelle(*zeilen):
    return UMBRUCH.join(zeilen)


class HaengendeKdocs(unittest.TestCase):

    def test_ein_kdoc_direkt_vor_der_klammer_wird_gemeldet(self):
        """Der reale Fall aus UIConstants.kt - die Konstante ist weg, ihre Doku steht noch."""
        text = quelle(
            "object LayoutFractions {",
            "    /** Halbe Breite fuer zweispaltige Layouts */",
            "}",
        )

        self.assertEqual([2], haengende_kdocs_in(text))

    def test_leerzeilen_dazwischen_verdecken_den_befund_nicht(self):
        text = quelle(
            "object A {",
            "    /** Beschreibt nichts mehr */",
            "",
            "",
            "}",
        )

        self.assertEqual([2], haengende_kdocs_in(text))

    def test_ein_zeilenkommentar_dazwischen_verdeckt_den_befund_nicht(self):
        """Genau die Lage in UIConstants.kt: unter den Waisen stand die ENTFERNT-Notiz.

        Wuerde ein `//`-Kommentar als Deklaration durchgehen, blieben die vier Waisen dort
        unentdeckt - und zwar dauerhaft, weil die Notiz ja bleiben soll.
        """
        text = quelle(
            "object A {",
            "    /** Beschreibt nichts mehr */",
            "",
            "    // ENTFERNT (v1.34.3): 30 Konstanten ohne Verwender.",
            "}",
        )

        self.assertEqual([2], haengende_kdocs_in(text))

    def test_ein_kdoc_ueber_einer_deklaration_ist_in_ordnung(self):
        text = quelle(
            "object A {",
            "    /** Breite fuer Dialoge */",
            "    const val DIALOG_WIDTH = 0.9f",
            "}",
        )

        self.assertEqual([], haengende_kdocs_in(text))

    def test_mehrzeilige_kdocs_werden_als_ein_block_gelesen(self):
        text = quelle(
            "object A {",
            "    /**",
            "     * Mehrzeilig,",
            "     * mit Fliesstext.",
            "     */",
            "    const val B = 1",
            "}",
        )

        self.assertEqual([], haengende_kdocs_in(text))

    def test_ein_kdoc_vor_einem_enum_eintrag_ist_KEIN_befund(self):
        """DIE WICHTIGSTE GEGENPROBE. Enum-Eintraege sind keine Deklarationen im Sinne des
        Musters, und die weite Fassung meldete sie alle - allein in `DimDiagnostik` fuenf Stueck.
        Die enge Fassung sieht sie gar nicht erst an, weil hinter ihnen kein `}` steht.
        """
        text = quelle(
            "enum class Grund {",
            "    /** Die Master-Pause ist aktiv. */",
            "    MASTER_PAUSE,",
            "",
            "    /** Der Dimmer ist aus. */",
            "    DIMMER_AUS",
            "}",
        )

        self.assertEqual([], haengende_kdocs_in(text))

    def test_ein_dateikopf_vor_einem_klassen_kdoc_ist_KEIN_befund(self):
        """Zweite Gegenprobe: zwei KDoc-Bloecke hintereinander sind voellig normal."""
        text = quelle(
            "/**",
            " * Dateikopf.",
            " */",
            "",
            "/**",
            " * Klassendoku.",
            " */",
            "class A",
        )

        self.assertEqual([], haengende_kdocs_in(text))


if __name__ == "__main__":
    unittest.main()
