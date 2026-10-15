"""Tests fuer baue_plz_tabelle.py (#140) - mit einer kleinen, eingebetteten GeoNames-Nachbildung.

Aufruf aus dem Projekt-Root:  python -m unittest tools/tageslicht/test_baue_plz_tabelle.py
"""
import io
import os
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import baue_plz_tabelle as bpt  # noqa: E402


def zeile(land, plz, ort, breite, laenge, genauigkeit="4"):
    """Eine GeoNames-Zeile: 12 Spalten, Tab-getrennt."""
    return "\t".join([land, plz, ort, "Land", "01", "Bezirk", "011", "Kreis", "01101",
                      str(breite), str(laenge), genauigkeit])


DE_ZEILEN = [
    # Region 80 (Muenchen): drei Zustell-PLZ, eine davon mit zwei Orten
    zeile("DE", "80331", "München", 48.1374, 11.5755),
    zeile("DE", "80333", "München", 48.1466, 11.5683),
    zeile("DE", "80333", "München", 48.1500, 11.5700),
    zeile("DE", "80995", "München", 48.2100, 11.5100),
    # Grosskunden-PLZ ohne Genauigkeit, geografisch weit weg: darf den Schwerpunkt nicht verziehen
    zeile("DE", "80300", "Bayerischer Rundfunk", 52.5, 13.4, genauigkeit=""),
    # Region 10 (Berlin) plus ein Ausreisser MIT Genauigkeit in Stuttgart
    zeile("DE", "10115", "Berlin", 52.5323, 13.3846),
    zeile("DE", "10117", "Berlin", 52.5170, 13.3889),
    zeile("DE", "10119", "Berlin", 52.5305, 13.4053),
    zeile("DE", "10875", "Ausreisser AG", 48.7239, 9.1699),
    # Eine Region, die nur aus Grosskunden-PLZ besteht: entfaellt
    zeile("DE", "99999", "Nur Grosskunde", 50.0, 10.0, genauigkeit=""),
]

AT_ZEILEN = [
    zeile("AT", "1010", "Wien", 48.2077, 16.3705),
    zeile("AT", "1020", "Wien", 48.2167, 16.3833),
    zeile("AT", "1030", "Wien", 48.1981, 16.3948),
]


def baue_zip(verzeichnis, land, zeilen):
    pfad = os.path.join(verzeichnis, land + ".zip")
    with zipfile.ZipFile(pfad, "w") as z:
        z.writestr(land + ".txt", "\n".join(zeilen) + "\n")
        z.writestr("readme.txt", "readme")
    return pfad


class BauePlzTabelleTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.de = baue_zip(self.tmp.name, "DE", DE_ZEILEN)
        self.at = baue_zip(self.tmp.name, "AT", AT_ZEILEN)

    def tearDown(self):
        self.tmp.cleanup()

    def regionen(self, pfad, land, mit_drei):
        return {r.praefix: r for r in bpt.regionen_aus_zip(pfad, land, mit_drei_stellen=mit_drei)}

    def test_zwei_stellige_praefixe_mit_schwerpunkt(self):
        r = self.regionen(self.de, "DE", mit_drei=False)
        self.assertIn("80", r)
        # Mittel der DISTINKTEN Zustell-PLZ (80333 zaehlt einmal, mit seinem Mittelpunkt)
        erwartet_breite = (48.1374 + (48.1466 + 48.1500) / 2 + 48.2100) / 3
        self.assertAlmostEqual(r["80"].breite, erwartet_breite, places=6)
        self.assertEqual(r["80"].name, "München")

    def test_grosskunden_ohne_genauigkeit_zaehlen_nicht(self):
        r = self.regionen(self.de, "DE", mit_drei=False)
        self.assertLess(r["80"].breite, 48.5, "Grosskunde in Berlin hat den Muenchner Schwerpunkt verzogen")
        self.assertNotIn("99", r, "eine reine Grosskunden-Region darf nicht entstehen")

    def test_geografischer_ausreisser_wird_verworfen(self):
        r = self.regionen(self.de, "DE", mit_drei=False)
        self.assertGreater(r["10"].breite, 52.4)
        self.assertGreater(r["10"].laenge, 13.3)
        self.assertEqual(r["10"].name, "Berlin")

    def test_postfach_name_ist_nachrangig(self):
        # Nachbildung von AT "12": die Postfach-PLZ liegen dem Schwerpunkt am naechsten und
        # haben eine Genauigkeitsangabe - der Name muss trotzdem der echte Ortsname sein.
        zeilen = [
            zeile("AT", "1200", "Wien", 48.240, 16.380),
            zeile("AT", "1201", "Wien Postfach", 48.220, 16.390, genauigkeit="1"),
            zeile("AT", "1210", "Wien", 48.200, 16.400),
            zeile("AT", "1211", "Wien Postfach", 48.221, 16.391, genauigkeit="1"),
        ]
        pfad = baue_zip(self.tmp.name, "AT", zeilen)
        r = self.regionen(pfad, "AT", mit_drei=False)
        self.assertEqual(r["12"].name, "Wien")
        self.assertAlmostEqual(r["12"].breite, (48.240 + 48.220 + 48.200 + 48.221) / 4, places=6,
                               msg="Postfach-PLZ zaehlen weiter fuer den Schwerpunkt")

    def test_name_ist_der_ort_mit_den_meisten_plz(self):
        # Nachbildung von DE "93" (Fairphone, 15.10.2026): der Schwerpunkt liegt neben einem Dorf,
        # die Stadt hat aber die meisten PLZ - die Region heisst nach der Stadt.
        zeilen = [
            zeile("DE", "93047", "Regensburg", 49.020, 12.100),
            zeile("DE", "93049", "Regensburg", 49.010, 12.080),
            zeile("DE", "93051", "Regensburg", 49.000, 12.090),
            zeile("DE", "93177", "Altenthann", 49.090, 12.300),
            zeile("DE", "93413", "Cham", 49.220, 12.660),
        ]
        pfad = baue_zip(self.tmp.name, "DE", zeilen)
        r = self.regionen(pfad, "DE", mit_drei=False)
        self.assertEqual(r["93"].name, "Regensburg")

    def test_bei_gleicher_plz_zahl_entscheidet_die_naehe_zum_schwerpunkt(self):
        # Je Ort eine PLZ (typisch fuer AT/CH-Doerfer): dann wie bisher der Ort naechst dem Schwerpunkt.
        zeilen = [
            zeile("AT", "2000", "Fern", 48.60, 16.00),
            zeile("AT", "2020", "Mitte", 48.50, 16.20),
            zeile("AT", "2050", "Anders", 48.40, 16.40),
        ]
        pfad = baue_zip(self.tmp.name, "AT", zeilen)
        self.assertEqual(self.regionen(pfad, "AT", mit_drei=False)["20"].name, "Mitte")

    def test_nur_postfach_namen_bleiben_als_letzter_ausweg(self):
        pfad = baue_zip(self.tmp.name, "AT", [zeile("AT", "1201", "Wien Postfach", 48.22, 16.39, genauigkeit="1")])
        self.assertEqual(self.regionen(pfad, "AT", mit_drei=False)["12"].name, "Wien Postfach")

    def test_drei_stellige_praefixe_nur_auf_wunsch(self):
        ohne = self.regionen(self.de, "DE", mit_drei=False)
        mit = self.regionen(self.de, "DE", mit_drei=True)
        self.assertNotIn("803", ohne)
        self.assertIn("803", mit)
        self.assertIn("809", mit)
        self.assertIn("80", mit)

    def test_ausgabe_format_und_kopf(self):
        text = bpt.als_tsv(bpt.regionen_aus_zip(self.at, "AT", mit_drei_stellen=False), "AT", "2026-10-14")
        zeilen = text.splitlines()
        self.assertTrue(zeilen[0].startswith("#"))
        kopf = "\n".join(z for z in zeilen if z.startswith("#"))
        self.assertIn("GeoNames", kopf)
        self.assertIn("CC BY 4.0", kopf)
        self.assertIn("2026-10-14", kopf)
        daten = [z for z in zeilen if not z.startswith("#")]
        self.assertEqual(daten, ["10\t48.21\t16.38\tWien"])

    def test_ausgabe_ist_nach_praefix_sortiert_und_gerundet(self):
        text = bpt.als_tsv(bpt.regionen_aus_zip(self.de, "DE", mit_drei_stellen=True), "DE", "2026-10-14")
        daten = [z.split("\t") for z in text.splitlines() if not z.startswith("#")]
        praefixe = [d[0] for d in daten]
        self.assertEqual(praefixe, sorted(praefixe))
        for d in daten:
            self.assertEqual(len(d), 4)
            for zahl in d[1:3]:
                self.assertLessEqual(len(zahl.split(".")[1]), 2, zahl)

    def test_ausgabe_ist_deterministisch(self):
        a = bpt.als_tsv(bpt.regionen_aus_zip(self.de, "DE", mit_drei_stellen=True), "DE", "2026-10-14")
        b = bpt.als_tsv(bpt.regionen_aus_zip(self.de, "DE", mit_drei_stellen=True), "DE", "2026-10-14")
        self.assertEqual(a, b)

    def test_main_schreibt_drei_dateien_mit_lf(self):
        ch = baue_zip(self.tmp.name, "CH", [zeile("CH", "8001", "Zürich", 47.3717, 8.5423)])
        ziel = os.path.join(self.tmp.name, "assets")
        bpt.main([self.de, self.at, ch, "--ziel", ziel, "--stand", "2026-10-14"])
        for land in ("de", "at", "ch"):
            pfad = os.path.join(ziel, "plz_%s.tsv" % land)
            with open(pfad, "rb") as f:
                roh = f.read()
            self.assertNotIn(b"\r\n", roh, pfad)
            self.assertTrue(roh.decode("utf-8").startswith("#"))

    def test_main_lehnt_falsch_benannte_zip_ab(self):
        with self.assertRaises(SystemExit):
            bpt.main([self.at, self.de, self.de, "--ziel", self.tmp.name])


if __name__ == "__main__":
    unittest.main()
