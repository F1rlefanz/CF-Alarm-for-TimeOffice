"""Testet Pruefung 7 aus `pruefe_reste.py`: Konfigurationsdateien, die kein Parser liest.

WARUM ES DAS GIBT
-----------------
Dieses Gatter blockiert `git merge` und `git push`. Seine gefaehrliche Richtung ist nicht der
Fehlalarm - eine Datei parst oder sie parst nicht, da gibt es nichts zu erraten -, sondern dass
es STILL NICHTS MEHR PRUEFT. Drei Wege dorthin sind real aufgetreten oder nachgestellt worden:

  1. EINE ENTKOMMENE AUSNAHME. Ein frueherer Anlauf fing `(SyntaxError, ValueError)`. Eine
     XML-Deklaration mit unbekannter Kodierung wirft aber `LookupError` - der riss das ganze
     Skript samt der sechs anderen Pruefungen mit, gemeldet als "Reste hinterlassen".
  2. EIN GEQUOTETER PFAD. Ohne `-z` quotet git Umlaut-Pfade C-artig, `open()` scheitert, die
     Datei faellt lautlos aus der Pruefung.
  3. EINE GEKAPPTE VERDRAHTUNG. Solange nur die reine Funktion getestet wird, bleiben alle
     Tests gruen, wenn der Aufruf aus `main()` verschwindet. Deshalb unten ein Lauf des ECHTEN
     Skripts ueber einem eigens gebauten Mini-Repo.

Aufruf:
    python -m unittest discover -s tools/aufraeumen -p "test_*.py"
    python tools/aufraeumen/test_konfig_parser.py
"""
from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from pruefe_reste import BEWUSST_UNPARSBAR, konfig_fehler, pruefe_konfigdateien  # noqa: E402

SKRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "pruefe_reste.py")


class ParstNicht(unittest.TestCase):
    """Die eine Richtung: meldet es, wo wirklich etwas ist?"""

    def test_der_echte_befund_zwei_bindestriche_in_einem_xml_kommentar(self):
        """Der Fall aus `app/lint.xml`, nachgebaut.

        Gradles Offline-Schalter ausgeschrieben in einem XML-Kommentar - die zwei Bindestriche
        beenden ihn. Android Lint nimmt die Datei klaglos an; genau deshalb stand der Fehler
        vom 06.08. bis 02.09.2026 unbemerkt drin.
        """
        roh = b'<?xml version="1.0"?>\n<lint>\n<!-- mit --offline pruefen -->\n</lint>\n'

        self.assertIn("ParseError", konfig_fehler("app/lint.xml", roh))

    def test_kaputtes_json(self):
        self.assertIn("JSONDecodeError", konfig_fehler(".claude/settings.json", b'{"a": }'))

    def test_kaputtes_toml(self):
        self.assertTrue(konfig_fehler("gradle/libs.versions.toml", b"a = = 1\n"))

    def test_eine_unbekannte_kodierung_entkommt_nicht(self):
        """DIE LUECKE, AN DER DER ZWEITE ANLAUF GESCHEITERT IST.

        `LookupError` ist weder `SyntaxError` noch `ValueError`. Mit einem Tupel aus
        Fehlertypen entkam die Ausnahme aus der Pruefung und toetete das ganze Skript - eine
        Fehldiagnose an einem blockierenden Gatter. Hier muss sie als ganz normaler Befund
        zurueckkommen, nicht als Absturz.
        """
        roh = b'<?xml version="1.0" encoding="cp1252-de"?>\n<resources/>\n'

        self.assertIn("LookupError", konfig_fehler("app/src/main/res/values/x.xml", roh))


class ParstDoch(unittest.TestCase):
    """Die Gegenrichtung: schweigt es, wo nichts ist?"""

    def test_gueltiges_xml_json_und_toml(self):
        self.assertEqual("", konfig_fehler("a.xml", b'<?xml version="1.0"?>\n<lint/>\n'))
        self.assertEqual("", konfig_fehler("a.json", b'{"a": [1, 2]}'))
        self.assertEqual("", konfig_fehler("a.toml", b'[versions]\nagp = "8.0"\n'))

    def test_ein_xml_kommentar_mit_bindestrich_ist_erlaubt(self):
        """Nur ZWEI aufeinanderfolgende Bindestriche sind verboten, einer nicht.

        Ohne diesen Test waere eine Verschaerfung auf "Bindestrich im Kommentar" nicht zu
        bemerken - und die traefe fast jeden Kommentar dieses Repos.
        """
        roh = b'<?xml version="1.0"?>\n<lint>\n<!-- Lint-Ergebnis - siehe oben -->\n</lint>\n'

        self.assertEqual("", konfig_fehler("app/lint.xml", roh))

    def test_endungen_ohne_parser_werden_uebergangen(self):
        """`.yml` und `.kt` haben hier bewusst keinen Parser - sie duerfen nicht melden."""
        self.assertEqual("", konfig_fehler(".github/workflows/ci.yml", b": : nicht: yaml"))
        self.assertEqual("", konfig_fehler("A.kt", b"das ist kein XML"))

    def test_die_ausnahmeliste_ist_leer_und_das_ist_der_normalfall(self):
        """Sie existiert, weil der GRENZE-Absatz im Werkzeug einen Ausweg verspricht.

        Ein Marker IN der Datei ist hier unmoeglich (JSON kennt keine Kommentare, und in einer
        unparsbaren Datei erreicht der Parser ihn nie) - also eine Liste im Skript. Waere sie
        gefuellt, ohne dass jemand es merkt, verschwaende sie stillschweigend Befunde.
        """
        self.assertEqual(frozenset(), BEWUSST_UNPARSBAR)


class DieVerdrahtung(unittest.TestCase):
    """Faehrt das ECHTE Skript ueber einem Mini-Repo - inklusive `main()`.

    Ohne diesen Test bleiben alle anderen gruen, wenn `pruefe_konfigdateien` nicht mehr aus
    `main()` gerufen wird oder das `befunde.append` verschwindet. `WURZEL` leitet sich aus
    `__file__` ab; eine Kopie unter `<tmp>/tools/aufraeumen/` prueft deshalb `<tmp>`.
    """

    def _mini_repo(self, dateien):
        wurzel = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, wurzel, True)
        ziel = os.path.join(wurzel, "tools", "aufraeumen")
        os.makedirs(ziel)
        shutil.copy(SKRIPT, ziel)
        for name, inhalt in dateien.items():
            pfad = os.path.join(wurzel, name)
            os.makedirs(os.path.dirname(pfad), exist_ok=True)
            with open(pfad, "wb") as datei:
                datei.write(inhalt)
        subprocess.run(["git", "init", "-q"], cwd=wurzel, check=True)
        subprocess.run(["git", "add", "-A"], cwd=wurzel, check=True)
        return wurzel, subprocess.run(
            [sys.executable, os.path.join(ziel, "pruefe_reste.py"), "--ci"],
            cwd=wurzel, capture_output=True, text=True, timeout=120,
        )

    def test_eine_kaputte_datei_im_baum_laesst_das_skript_scheitern(self):
        _, lauf = self._mini_repo({"app/lint.xml": b"<lint>\n<!-- --x -->\n</lint>\n"})

        self.assertEqual(1, lauf.returncode)
        self.assertIn("Konfigurationsdatei parst nicht: app/lint.xml", lauf.stderr)

    def test_ohne_kaputte_datei_schweigt_das_skript(self):
        _, lauf = self._mini_repo({"app/lint.xml": b"<lint/>\n", "a.json": b"{}"})

        self.assertEqual(0, lauf.returncode)
        self.assertEqual("", lauf.stderr)

    def test_ein_umlaut_pfad_faellt_nicht_still_heraus(self):
        """OHNE `-z` quotet git ihn C-artig, `open()` scheitert, der Befund verschwindet.

        Zwei byte-gleich kaputte Dateien, von denen nur eine gemeldet wird - das faellt an
        einem Gatter niemandem auf.
        """
        kaputt = b'{"a": }'
        _, lauf = self._mini_repo({"docs/pruefung.json": kaputt, "docs/prüfung.json": kaputt})

        self.assertEqual(1, lauf.returncode)
        self.assertIn("docs/pruefung.json", lauf.stderr)
        self.assertIn("docs/prüfung.json", lauf.stderr)


class DerEchteBaum(unittest.TestCase):

    def test_dieses_repository_ist_frei_von_befunden(self):
        """Das Gatter muss auf dem eigenen Baum schweigen, sonst blockiert es jeden Push."""
        befunde = []
        pruefe_konfigdateien(befunde)

        self.assertEqual([], befunde)


if __name__ == "__main__":
    unittest.main()
