"""Testet Pruefung 7: Konfigurationsdateien, die ein Standard-Parser nicht liest.

WARUM ES DIESE PRUEFUNG GIBT (03.09.2026): `app/lint.xml` war vom 06.08.2026 bis zum 03.09.2026
kein wohlgeformtes XML - Gradles Offline-Schalter stand im Kopfkommentar ausgeschrieben, und
dessen zwei Bindestriche beenden in XML einen Kommentar. Android Lint nahm die Datei klaglos an,
die Unterdrueckungen wirkten, niemand hatte einen Anlass hinzusehen. Gemerkt hat es erst das erste
Werkzeug, das sie PARSEN wollte.

WARUM DIESE TESTS SO AUSSEHEN, WIE SIE AUSSEHEN: Der Blickwinkel ist gut (30 Dateien, 1 Rohbefund,
0 Fehlalarme), aber DREI Anlaeufe sind an der Verdrahtung gescheitert, nicht am Befund. Jeder
dieser Fehler hat hier seinen Test:

  1. PR #48 fing `(SyntaxError, ValueError)`. `<?xml ... encoding="cp1252-de"?>` wirft
     `LookupError` - der entkam und riss ALLE Pruefungen mit, gemeldet als "Der Aufraeumdurchgang
     hat Reste hinterlassen". Eine Fehldiagnose an einem blockierenden Gatter.
  2. PR #48 verschluckte Pfade mit Nicht-ASCII-Bytes still.
  3. PR #56 sperrte das Repo im MERGE-KONFLIKT ein: ein Konfliktmarker ist nie wohlgeformtes
     XML, und die Schleuse nimmt nur `--dry-run` aus - `git merge --abort` und `--continue`,
     die beiden Rettungsbefehle, waeren abgewiesen worden.
  4. Bei PR #48 blieben alle Tests gruen, wenn man den Aufruf aus `main()` entfernte. Deshalb
     prueft `Verdrahtung` das ECHTE Skript ueber einem Wegwerf-Repo und nicht die Funktion
     allein: `WURZEL` leitet sich aus `__file__` ab, eine Kopie unter `<tmp>/tools/aufraeumen/`
     prueft also `<tmp>`.

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

from pruefe_reste import BEWUSST_UNPARSBAR, konfig_fehler  # noqa: E402

HIER = os.path.dirname(os.path.abspath(__file__))
SKRIPT = os.path.join(HIER, "pruefe_reste.py")


class ReineEntscheidung(unittest.TestCase):
    """`konfig_fehler` ohne Repository - beide Richtungen je Format."""

    def test_wohlgeformtes_xml_schweigt(self):
        self.assertEqual("", konfig_fehler("a.xml", b"<lint><issue id='X'/></lint>"))

    def test_der_reale_befund_wird_gemeldet(self):
        """Der Offline-Schalter ausgeschrieben in einem XML-Kommentar - `app/lint.xml`."""
        roh = "<lint>\n<!-- mit `./gradlew --offline lintDebug` gegenpruefen -->\n</lint>\n"

        fehler = konfig_fehler("app/lint.xml", roh.encode("utf-8"))

        self.assertIn("ParseError", fehler)

    def test_gueltiges_json_schweigt(self):
        self.assertEqual("", konfig_fehler("a.json", b'{"a": [1, 2]}'))

    def test_kaputtes_json_wird_gemeldet(self):
        self.assertIn("JSONDecodeError", konfig_fehler("a.json", b'{"a": [1, 2}'))

    def test_gueltiges_toml_schweigt(self):
        self.assertEqual("", konfig_fehler("a.toml", b'[versions]\nagp = "8.0"\n'))

    def test_kaputtes_toml_wird_gemeldet(self):
        fehler = konfig_fehler("a.toml", b'[versions\nagp = "8.0"\n')

        self.assertNotEqual("", fehler)

    def test_andere_endungen_gehen_die_pruefung_nichts_an(self):
        """Sonst meldet jede `.kt`- oder `.md`-Datei einen Parser-Fehler."""
        self.assertEqual("", konfig_fehler("a.kt", b"nicht mal ansatzweise XML"))
        self.assertEqual("", konfig_fehler("README.md", b"# Ueberschrift"))

    def test_grossgeschriebene_endung_wird_mitgeprueft(self):
        self.assertIn("ParseError", konfig_fehler("A.XML", b"<a><b></a>"))

    def test_unbekannte_kodierung_wirft_nicht_durch(self):
        """PR #48 starb hier: `LookupError` steckt in keinem Tupel aus Syntax-Fehlertypen.

        Der Aufruf DARF nicht werfen - er muss einen Befund liefern. Ein Wurf von hier reisst
        alle sieben Pruefungen mit und meldet "Reste hinterlassen", also das Gegenteil.
        """
        roh = b'<?xml version="1.0" encoding="cp1252-de"?><lint/>'

        fehler = konfig_fehler("a.xml", roh)

        self.assertIn("LookupError", fehler)


def _git(pfad, *args):
    return subprocess.run(
        ["git", "-c", "user.email=t@t", "-c", "user.name=T",
         "-c", "commit.gpgsign=false"] + list(args),
        cwd=pfad, capture_output=True, text=True, errors="replace",
    )


class Verdrahtung(unittest.TestCase):
    """Das ECHTE Skript ueber einem Wegwerf-Repo - nicht die Funktion allein.

    Bei PR #48 blieben alle Tests gruen, wenn man den Aufruf aus `main()` entfernte. Diese
    Klasse wird rot, sobald die Verdrahtung ausfaellt.
    """

    def setUp(self):
        self.repo = tempfile.mkdtemp(prefix="konfigtest-")
        self.addCleanup(shutil.rmtree, self.repo, True)
        ziel = os.path.join(self.repo, "tools", "aufraeumen")
        os.makedirs(ziel)
        shutil.copy(SKRIPT, ziel)
        self.skript = os.path.join(ziel, "pruefe_reste.py")

    def _init(self):
        _git(self.repo, "init", "-q", "-b", "main")

    def _schreibe(self, name, inhalt):
        """Dateiname als BYTES zusammengesetzt - anders ist ein Latin-1-Name nicht anlegbar."""
        pfad = os.path.join(os.fsencode(self.repo), os.fsencode(name))
        with open(pfad, "wb") as datei:
            datei.write(inhalt)

    def _lauf(self):
        fertig = subprocess.run(
            [sys.executable, self.skript, "--ci"],
            capture_output=True, text=True, errors="replace",
        )
        return fertig.returncode, fertig.stdout + fertig.stderr

    def test_eine_kaputte_xml_wird_gemeldet(self):
        self._init()
        self._schreibe("kaputt.xml", b"<a><b></a>")
        _git(self.repo, "add", "-A")

        code, ausgabe = self._lauf()

        self.assertEqual(1, code)
        self.assertIn("Konfigurationsdatei parst nicht: kaputt.xml", ausgabe)

    def test_eine_heile_xml_schweigt(self):
        self._init()
        self._schreibe("heil.xml", b"<a><b/></a>")
        _git(self.repo, "add", "-A")

        code, ausgabe = self._lauf()

        self.assertEqual(0, code, ausgabe)

    def test_ein_pfad_mit_nicht_utf8_bytes_faellt_nicht_still_heraus(self):
        """PR #48 verschluckte ihn: ohne `-z` quotet git, mit `errors="replace"` zerfaellt er.

        Der Dateiname traegt hier ein Latin-1-`ae` - ein Byte, das kein gueltiges UTF-8 ist.
        Genau so eine Datei muss den Weg bis `open()` ueberstehen, sonst prueft das Gatter sie
        nie und schweigt ueber eine kaputte Datei.
        """
        self._init()
        self._schreibe(b"kaputt-\xe4.xml", b"<a><b></a>")
        _git(self.repo, "add", "-A")

        code, ausgabe = self._lauf()

        self.assertEqual(1, code)
        self.assertIn("Konfigurationsdatei parst nicht: kaputt-", ausgabe)

    def test_im_merge_konflikt_schweigt_die_pruefung(self):
        """DIE LEHRE AUS PR #56: sonst blockiert das Gatter `git merge --abort`.

        Ein Konfliktmarker ist nie wohlgeformtes XML. Meldet Pruefung 7 hier, laeuft der
        Rettungsbefehl in den Schleusen-Hook und wird mit "Der Aufraeumdurchgang hat Reste
        hinterlassen" abgewiesen - eine Fehldiagnose plus ein Rat, den niemand befolgen kann.
        """
        self._init()
        self._schreibe("streit.xml", b"<a>eins</a>\n")
        _git(self.repo, "add", "-A")
        _git(self.repo, "commit", "-qm", "start")
        _git(self.repo, "checkout", "-q", "-b", "zweig")
        self._schreibe("streit.xml", b"<a>zwei</a>\n")
        _git(self.repo, "commit", "-qam", "zweig")
        _git(self.repo, "checkout", "-q", "main")
        self._schreibe("streit.xml", b"<a>drei</a>\n")
        _git(self.repo, "commit", "-qam", "main")
        _git(self.repo, "merge", "zweig")

        self.assertTrue(_git(self.repo, "ls-files", "-u").stdout.strip(), "kein Konflikt erzeugt")
        code, ausgabe = self._lauf()

        self.assertEqual(0, code, ausgabe)
        self.assertNotIn("Konfigurationsdatei", ausgabe)

    def test_ohne_repository_meldet_die_pruefung_statt_gruen_zu_sagen(self):
        """FAIL LOUD STATT FAIL OPEN: nichts geprueft ist nicht dasselbe wie nichts gefunden."""
        self._schreibe("kaputt.xml", b"<a><b></a>")   # kein `git init`

        code, ausgabe = self._lauf()

        self.assertEqual(1, code)
        self.assertIn("nicht durchfuehrbar", ausgabe)

    def test_eine_lokal_geloeschte_datei_ist_kein_befund(self):
        """Im Index, nicht im Arbeitsbaum - ein normaler Zwischenstand, kein Fehlalarm wert."""
        self._init()
        self._schreibe("weg.xml", b"<a/>")
        _git(self.repo, "add", "-A")
        _git(self.repo, "commit", "-qm", "start")
        os.remove(os.path.join(self.repo, "weg.xml"))

        code, ausgabe = self._lauf()

        self.assertEqual(0, code, ausgabe)


class Ausnahmeweg(unittest.TestCase):
    """Was der GRENZE-Absatz verspricht, muss es auch geben - PR #40 versprach es ohne Funktion."""

    def test_die_liste_ist_leer_und_das_ist_der_normalfall(self):
        self.assertEqual(frozenset(), BEWUSST_UNPARSBAR)

    def test_ein_eingetragener_pfad_wird_uebergangen(self):
        import pruefe_reste

        repo = tempfile.mkdtemp(prefix="konfigtest-")
        self.addCleanup(shutil.rmtree, repo, True)
        ziel = os.path.join(repo, "tools", "aufraeumen")
        os.makedirs(ziel)
        shutil.copy(SKRIPT, ziel)
        with open(os.path.join(repo, "kaputt.xml"), "wb") as datei:
            datei.write(b"<a><b></a>")
        _git(repo, "init", "-q", "-b", "main")
        _git(repo, "add", "-A")

        skript = os.path.join(ziel, "pruefe_reste.py")
        vorher = subprocess.run(
            [sys.executable, skript, "--ci"], capture_output=True, text=True, errors="replace",
        )
        self.assertEqual(1, vorher.returncode)

        with open(skript, encoding="utf-8") as datei:
            quelle = datei.read().replace(
                "BEWUSST_UNPARSBAR = frozenset()",
                'BEWUSST_UNPARSBAR = frozenset(["kaputt.xml"])',
            )
        with open(skript, "w", encoding="utf-8") as datei:
            datei.write(quelle)
        nachher = subprocess.run(
            [sys.executable, skript, "--ci"], capture_output=True, text=True, errors="replace",
        )

        self.assertEqual(0, nachher.returncode, nachher.stdout + nachher.stderr)
        self.assertEqual(frozenset(), pruefe_reste.BEWUSST_UNPARSBAR)   # das Original bleibt leer


if __name__ == "__main__":
    unittest.main()
