"""Testet die Auswahl des Blickwinkels fuer die naechste Aufraeumrunde.

WARUM ES DIESE AUSWAHL GIBT (03.09.2026): Die Regel lautete "Nimm den obersten offenen
Eintrag" - und `gh issue list` sortiert nach Erstelldatum ABSTEIGEND. Die Warteschlange war
damit ein Stapel: die neun Blickwinkel vom 25.08.2026 kamen nie an die Reihe, weil oben immer
etwas Juengeres lag. Und weil der Torwaechter ein Issue wieder oeffnet, wenn er dessen PR
schliesst, ohne dass sich das Erstelldatum aendert, sperrte ein gescheiterter Blickwinkel den
Kopf der Liste dauerhaft: Issue #39 hat so VIER Runden verbraucht (PR #40, #48, #56, #58).

Diese Tests halten beide Richtungen fest - dass die Reihenfolge wirklich beim aeltesten
beginnt, und dass ein Blickwinkel nach dem Deckel wirklich ruht. Und sie halten die
Zaehlweise fest: ein GEMERGTER PR ist kein gescheiterter Anlauf, sonst zaehlte jeder Erfolg
gegen den Blickwinkel, an dem er gelungen ist.

Aufruf:
    python -m unittest discover -s tools/aufraeumen -p "test_*.py"
"""
from __future__ import annotations

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from blickwinkel_waehlen import gescheiterte_anlaeufe, waehle  # noqa: E402


def querverweis(nummer, zustand="closed", gemergt=None, ist_pr=True):
    """Ein `cross-referenced`-Ereignis, wie GitHub es in der Zeitleiste liefert."""
    quelle = {"number": nummer, "state": zustand}
    quelle["pull_request"] = {"merged_at": gemergt} if ist_pr else None
    return {"event": "cross-referenced", "source": {"issue": quelle}}


def kandidat(nummer, erstellt, anlaeufe=(), titel=None):
    return {
        "nummer": nummer,
        "titel": titel if titel is not None else "Blickwinkel: Nummer %d" % nummer,
        "erstellt": erstellt,
        "anlaeufe": list(anlaeufe),
    }


class ZaehltAnlaeufe(unittest.TestCase):
    def test_geschlossener_ungemergter_pr_zaehlt(self):
        self.assertEqual([40], gescheiterte_anlaeufe([querverweis(40)]))

    def test_gemergter_pr_zaehlt_nicht(self):
        """Der Fall Issue #31: PR #32 gemergt, PR #37 geschlossen - genau ein Anlauf."""
        zeitleiste = [querverweis(32, gemergt="2026-08-25T10:57:45Z"), querverweis(37)]
        self.assertEqual([37], gescheiterte_anlaeufe(zeitleiste))

    def test_offener_pr_zaehlt_nicht(self):
        self.assertEqual([], gescheiterte_anlaeufe([querverweis(60, zustand="open")]))

    def test_verweis_von_einem_issue_zaehlt_nicht(self):
        self.assertEqual([], gescheiterte_anlaeufe([querverweis(57, ist_pr=False)]))

    def test_mehrfachnennung_zaehlt_einmal(self):
        """GitHub traegt jede Erwaehnung einzeln ein - PR #58 nannte #39 mehrfach."""
        self.assertEqual([58], gescheiterte_anlaeufe([querverweis(58)] * 5))

    def test_andere_ereignisse_stoeren_nicht(self):
        zeitleiste = [{"event": "reopened"}, {"event": "labeled"}, querverweis(40)]
        self.assertEqual([40], gescheiterte_anlaeufe(zeitleiste))

    def test_der_echte_fall_39(self):
        """Vier Anlaeufe, gemessen am 03.09.2026 an der echten Zeitleiste."""
        zeitleiste = [querverweis(n) for n in (40, 48, 56, 58)]
        self.assertEqual([40, 48, 56, 58], gescheiterte_anlaeufe(zeitleiste))


class WaehltDenBlickwinkel(unittest.TestCase):
    def test_aeltester_zuerst(self):
        """Die Regressionsprobe gegen den Stapel: das juengste Issue gewinnt NICHT."""
        urteil = waehle([kandidat(50, "2026-09-01T00:00:00Z"), kandidat(19, "2026-08-25T00:00:00Z")])
        self.assertEqual(19, urteil["gewaehlt"]["nummer"])

    def test_anlaeufe_aendern_die_reihenfolge_nicht(self):
        """Sonst verhungert ein Blickwinkel mit einem Anlauf hinter lauter frischen."""
        urteil = waehle(
            [
                kandidat(31, "2026-08-25T10:33:07Z", anlaeufe=[37]),
                kandidat(60, "2026-09-03T00:00:00Z"),
            ]
        )
        self.assertEqual(31, urteil["gewaehlt"]["nummer"])

    def test_deckel_stellt_zurueck(self):
        """Der Fall #39: vier Anlaeufe, also nicht noch eine Runde."""
        urteil = waehle(
            [
                kandidat(39, "2026-08-27T14:29:06Z", anlaeufe=[40, 48, 56, 58]),
                kandidat(38, "2026-08-27T14:28:49Z"),
            ]
        )
        self.assertEqual(38, urteil["gewaehlt"]["nummer"])
        zurueckgestellt = [e for e in urteil["warteschlange"] if e["lage"] == "zurueckgestellt"]
        self.assertEqual([39], [e["nummer"] for e in zurueckgestellt])

    def test_knapp_unter_dem_deckel_kommt_noch_dran(self):
        """Die Gegenrichtung - der Deckel darf nicht zu frueh greifen."""
        urteil = waehle([kandidat(39, "2026-08-27T00:00:00Z", anlaeufe=[40, 48])])
        self.assertEqual(39, urteil["gewaehlt"]["nummer"])

    def test_deckel_ist_einstellbar(self):
        eintraege = [kandidat(39, "2026-08-27T00:00:00Z", anlaeufe=[40, 48, 56, 58])]
        self.assertIsNotNone(waehle(eintraege, deckel=9)["gewaehlt"])

    def test_nachtrag_gehoert_dem_eigentuemer(self):
        """Eine Runde kann `.claude/**` nicht schreiben - sie darf sich daran nicht festbeissen."""
        urteil = waehle(
            [
                kandidat(57, "2026-08-20T00:00:00Z", titel="Skill-Nachzug aus Runde 15 (#39)"),
                kandidat(19, "2026-08-25T00:00:00Z"),
            ]
        )
        self.assertEqual(19, urteil["gewaehlt"]["nummer"])
        self.assertEqual("eigentuemer", urteil["warteschlange"][0]["lage"])

    def test_nichts_waehlbar_ist_kein_absturz(self):
        """Leer heisst: melden und aufhoeren, nicht einen Blickwinkel erfinden."""
        urteil = waehle([kandidat(39, "2026-08-27T00:00:00Z", anlaeufe=[40, 48, 56])])
        self.assertIsNone(urteil["gewaehlt"])

    def test_leere_warteschlange(self):
        self.assertIsNone(waehle([])["gewaehlt"])

    def test_die_eingabe_bleibt_unberuehrt(self):
        eingabe = [kandidat(19, "2026-08-25T00:00:00Z")]
        waehle(eingabe)
        self.assertNotIn("lage", eingabe[0])


if __name__ == "__main__":
    unittest.main()
