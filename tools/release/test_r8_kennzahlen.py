"""Tests fuer die R8-Kennzahlen (Issue #54).

Die Eingabe ist ein ECHTER Messwert: der Inhalt von `r8-metadata.dat` aus dem Release-Build vom
01.10.2026 (R8 9.4.14), gekuerzt auf die Felder, die das Werkzeug liest. Die Feldnamen muessen an
genau dieser Form haengen, nicht an einer ausgedachten.
"""
from __future__ import annotations

import json
import pathlib
import tempfile
import unittest
import zipfile

from r8_kennzahlen import (
    MAPPING_PFAD,
    R8_JSON_PFAD,
    KennzahlenFehler,
    lies_kennzahlen,
    pruefe,
)

WURZEL = pathlib.Path(__file__).resolve().parents[2]

# Gemessen am 01.10.2026, noch mit -dontobfuscate.
ECHT_01_10 = (
    '{"options":{"isObfuscationEnabled":false,"isOptimizationsEnabled":true,'
    '"isShrinkingEnabled":true,"minApiLevel":"26"},"baselineProfileRewriting":{},'
    '"dexFiles":[{"checksum":"988f69","sizeInBytes":10096720,"startup":false}],'
    '"stats":{"noObfuscationPercentage":99.99,"noOptimizationPercentage":22.09,'
    '"noShrinkingPercentage":21.61},"featureSplits":{"featureSplits":[{"dexFiles":[]}],'
    '"isolatedSplits":false},"version":"9.4.14"}'
)


def _mit(**stats):
    daten = json.loads(ECHT_01_10)
    daten["stats"].update(stats)
    return json.dumps(daten)


def _bundle(verz: pathlib.Path, r8_json: str | None, mapping: bool) -> pathlib.Path:
    pfad = verz / "app-release.aab"
    with zipfile.ZipFile(pfad, "w") as z:
        z.writestr("base/dex/classes.dex", b"dex")
        if r8_json is not None:
            z.writestr(R8_JSON_PFAD, r8_json)
        if mapping:
            z.writestr(MAPPING_PFAD, "# compiler: R8\n")
    return pfad


class Auswertung(unittest.TestCase):
    def test_echter_messwert_wird_umgerechnet(self):
        k = lies_kennzahlen(ECHT_01_10)
        self.assertEqual(k.dex_bytes, 10_096_720)
        # R8 meldet "nicht bearbeitet"; Play zeigt den Gegenwert.
        self.assertAlmostEqual(k.obfuskation, 0.01)
        self.assertAlmostEqual(k.optimierung, 77.91)
        self.assertAlmostEqual(k.shrinking, 78.39)
        self.assertIs(k.obfuskation_an, False)
        self.assertEqual(k.r8_version, "9.4.14")

    def test_der_stand_vom_01_10_haette_play_verfehlt(self):
        """Genau der Fall, fuer den es das Werkzeug gibt: knapp ueber 10 MB, Obfuskation 0 %."""
        k = lies_kennzahlen(ECHT_01_10)
        self.assertTrue(k.ueber_dex_schwelle())
        self.assertEqual(k.zu_niedrig(), ["Obfuskation"])
        self.assertTrue(k.verfehlt_play_vorgabe())

    def test_mit_obfuskation_ist_alles_gruen(self):
        k = lies_kennzahlen(_mit(noObfuscationPercentage=12.0))
        self.assertEqual(k.zu_niedrig(), [])
        self.assertFalse(k.verfehlt_play_vorgabe())

    def test_unter_10_mb_dex_gilt_die_vorgabe_nicht(self):
        daten = json.loads(ECHT_01_10)
        daten["dexFiles"][0]["sizeInBytes"] = 9_999_999
        k = lies_kennzahlen(json.dumps(daten))
        self.assertEqual(k.zu_niedrig(), ["Obfuskation"])
        self.assertFalse(k.verfehlt_play_vorgabe())

    def test_10_mb_heisst_dezimal(self):
        """9,63 MiB sind 10,1 MB - die strengere Lesart warnt frueher, das ist Absicht."""
        self.assertTrue(lies_kennzahlen(ECHT_01_10).ueber_dex_schwelle())

    def test_feature_splits_zaehlen_mit(self):
        daten = json.loads(ECHT_01_10)
        daten["dexFiles"][0]["sizeInBytes"] = 6_000_000
        daten["featureSplits"]["featureSplits"] = [{"dexFiles": [{"sizeInBytes": 5_000_000}]}]
        self.assertEqual(lies_kennzahlen(json.dumps(daten)).dex_bytes, 11_000_000)

    def test_fehlende_stats_sind_ein_fehler_keine_erfundene_null(self):
        daten = json.loads(ECHT_01_10)
        del daten["stats"]["noShrinkingPercentage"]
        with self.assertRaises(KennzahlenFehler):
            lies_kennzahlen(json.dumps(daten))
        with self.assertRaises(KennzahlenFehler):
            lies_kennzahlen("kein json")


class Bundle(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.verz = pathlib.Path(self._tmp.name)

    def tearDown(self):
        self._tmp.cleanup()

    def test_vollstaendiges_bundle_mit_verfehlter_vorgabe_warnt_bricht_aber_nicht_ab(self):
        code, ausgabe, summary = pruefe(_bundle(self.verz, ECHT_01_10, mapping=True))
        self.assertEqual(code, 0)
        self.assertTrue(any(z.startswith("::warning::") and "Obfuskation" in z for z in ausgabe))
        self.assertIn("| Obfuskation | 0.01 %", summary)
        self.assertIn("eingebettet: ja", summary)

    def test_fehlende_mapping_datei_ist_ein_fehler(self):
        code, ausgabe, _ = pruefe(_bundle(self.verz, _mit(noObfuscationPercentage=10.0), mapping=False))
        self.assertEqual(code, 1)
        self.assertTrue(any(z.startswith("::error::") and MAPPING_PFAD in z for z in ausgabe))

    def test_fehlende_r8_json_warnt_nur(self):
        code, ausgabe, summary = pruefe(_bundle(self.verz, None, mapping=True))
        self.assertEqual(code, 0)
        self.assertIsNone(summary)
        self.assertTrue(any(z.startswith("::warning::") for z in ausgabe))

    def test_unlesbare_r8_json_haelt_den_mapping_fehler_nicht_auf(self):
        code, ausgabe, _ = pruefe(_bundle(self.verz, "kaputt", mapping=False))
        self.assertEqual(code, 1)
        self.assertTrue(any(z.startswith("::warning::") for z in ausgabe))

    def test_einzelne_json_datei_ohne_mapping_pruefung(self):
        datei = self.verz / "r8-metadata.dat"
        datei.write_text(_mit(noObfuscationPercentage=10.0), encoding="utf-8")
        code, ausgabe, summary = pruefe(datei)
        self.assertEqual(code, 0)
        self.assertNotIn("eingebettet", summary)
        self.assertFalse(any(z.startswith("::") for z in ausgabe))


class Workflow(unittest.TestCase):
    """Die Auslieferung haengt an zwei Zeilen im Workflow - die gehoeren mitgeprueft."""

    def setUp(self):
        self.yml = (WURZEL / ".github" / "workflows" / "veroeffentlichen.yml").read_text(encoding="utf-8")

    def test_kennzahlen_laufen_vor_dem_upload(self):
        messung = self.yml.find("tools/release/r8_kennzahlen.py")
        upload = self.yml.find("r0adkll/upload-google-play")
        self.assertGreater(messung, 0, "veroeffentlichen.yml ruft r8_kennzahlen.py nicht auf")
        self.assertLess(messung, upload, "die Mapping-Pruefung muss VOR dem Upload laufen")

    def test_kein_zweiter_mapping_upload(self):
        # Das Bundle bettet proguard.map selbst ein; Play lehnt dann eine zusaetzliche
        # Mapping-Datei ab (Entscheidung 02.10.2026, Issue #54).
        aktive = [z for z in self.yml.splitlines() if not z.strip().startswith("#")]
        self.assertFalse(any("mappingFile:" in z for z in aktive))


if __name__ == "__main__":
    unittest.main()
