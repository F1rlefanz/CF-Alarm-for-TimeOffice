"""Testet, wann ein Wort in Backticks wirklich ein SYMBOL ist.

WARUM DIESE UNTERSCHEIDUNG EXISTIERT (25.08.2026): Nach dem Entfernen von
`HueBridgeConfig.whitelist` blockierte das Gatter den eigenen Merge und zeigte auf eine
Skill-Zeile mit `cmd deviceidle whitelist -<pkg>` — einem adb-BEFEHL. Die Notiz war völlig
richtig und hatte mit dem Feld nichts zu tun.

Der Denkfehler dahinter: Backticks bedeuten in dieser Doku „wortwörtlich", nicht
„Kotlin-Symbol". Shell-Kommandos, Dateipfade, Log-Ausschnitte und Berechtigungsnamen stehen
genauso darin. Wer das gleichsetzt, baut ein Gatter, das verlangt, richtige Notizen
kaputtzumachen — und genau dann wird es umgangen.

Die Unterscheidung, die trägt: **Ein Symbol steht an einem Punkt, einer Klammer oder allein.
Ein Wort in einem Kommando steht zwischen Leerzeichen.**

Dies ist der zweite Fehlalarm derselben Familie; der erste war `matchesKeywords(eventTitle)`
nach dem Entfernen von `ShiftInfo.eventTitle` (dort war der Parametername die Rettung, siehe
`pruefe_doku_verweise`). Beide Male hat das Gatter etwas gemeldet, beide Male war die Doku im
Recht. Deshalb stehen die Fälle hier fest.

Aufruf:
    python -m unittest discover -s tools/aufraeumen -p "test_*.py"
"""
from __future__ import annotations

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from pruefe_reste import _als_symbol_genannt  # noqa: E402


class WortInEinemKommando(unittest.TestCase):
    """Die Richtung, die das Gatter unglaubwürdig macht: melden, wo nichts ist."""

    def test_ein_adb_befehl_ist_kein_symbolverweis(self):
        zeile = "Akku-Ausnahme per `cmd deviceidle whitelist -<pkg>` entziehen"

        self.assertFalse(_als_symbol_genannt(zeile, "whitelist"))

    def test_ein_wort_mitten_in_einer_kommandozeile_zaehlt_nicht(self):
        zeile = "`./gradlew test --tests ShiftInfo`"

        self.assertFalse(_als_symbol_genannt(zeile, "test"))

    def test_ohne_backticks_gibt_es_keinen_treffer(self):
        """Fliesstext nennt Woerter wie 'port' oder 'name' staendig - in Prosa, nicht als Code."""
        zeile = "Der Port der Bridge steht in der Antwort, port ist aber egal."

        self.assertFalse(_als_symbol_genannt(zeile, "port"))


class EchterSymbolverweis(unittest.TestCase):
    """Die Richtung, die das Gatter nutzlos macht: nicht melden, wo etwas ist."""

    def test_ein_symbol_allein_in_der_spanne(self):
        zeile = "Der Schalter `wellnessEnabled` steht noch in drei Skills."

        self.assertTrue(_als_symbol_genannt(zeile, "wellnessEnabled"))

    def test_ein_member_zugriff(self):
        zeile = "siehe `ShiftInfo.eventTitle` weiter oben"

        self.assertTrue(_als_symbol_genannt(zeile, "eventTitle"))

    def test_ein_funktionsaufruf(self):
        zeile = "`buildRecommendations()` baute Empfehlungen, die niemand anzeigte."

        self.assertTrue(_als_symbol_genannt(zeile, "buildRecommendations"))

    def test_ein_argument_in_klammern(self):
        zeile = "`matchesKeywords(eventTitle)` erkennt Schichten in Kalendertiteln."

        self.assertTrue(_als_symbol_genannt(zeile, "eventTitle"))

    def test_der_qualifizierte_typ_zaehlt_auch(self):
        zeile = "`HueBridgeConfig.whitelist` faellt mit HueUser weg."

        self.assertTrue(_als_symbol_genannt(zeile, "whitelist"))


class Wortgrenzen(unittest.TestCase):

    def test_ein_laengerer_name_ist_kein_treffer(self):
        """`portForwarding` enthaelt `port` - das darf nicht als Verweis auf `port` gelten."""
        zeile = "`bridge.portForwarding` bleibt unberuehrt"

        self.assertFalse(_als_symbol_genannt(zeile, "port"))


if __name__ == "__main__":
    unittest.main()
