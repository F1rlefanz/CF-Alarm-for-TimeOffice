"""Testet die beiden Entscheidungen in `pruefe_reste.py`, die still falsch sein koennen.

WARUM ES DAS GIBT
-----------------
Dieses Gatter blockiert `git merge` und `git push`. Ein Gatter, das haeufig FALSCH meldet,
wird weggeklickt und schuetzt danach gar nichts mehr - das ist in diesem Projekt schon
gemessen worden (GitHub-Issue #18: 97 von 344 Meldungen waren Fehlalarm, und genau die
verdeckten den einen echten Einzelfall). Beide Fehlalarm-Quellen sind hier real aufgetreten
und beide sind unauffaellig, wenn sie zurueckkommen:

  1. OPERATOR-IMPORTE. `getValue`/`setValue` stehen nie im Rumpf - sie werden von `by`
     aufgerufen. Beim ersten Handlauf am 25.08.2026 waren SIEBEN von zehn Treffern genau
     das; haette man sie geloescht, haette nichts mehr kompiliert.
  2. HISTORISCHE DOKU-EINTRAEGE. Ein Absatz, der erklaert, was entfernt WURDE, ist der
     Hergang und muss stehenbleiben. Zeilenweise geprueft waren SECHS von neun Treffern
     genau solche Absaetze - das Signalwort steht selten in derselben Zeile wie das Symbol.

Dazu der Konfliktzustand (#60): waehrend eines offenen Merge darf das Gatter NICHT melden, sonst
sperrt es `git merge --continue`/`--abort`. Getestet an einem echten offenen Merge in einem
Wegwerf-Repo, und zwar die Verdrahtung in `main()`, nicht nur die Hilfsfunktion.

Aufruf:
    python -m unittest discover -s tools/aufraeumen -p "test_*.py"
    python tools/aufraeumen/test_pruefe_reste.py
"""
from __future__ import annotations

import os
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import pruefe_reste  # noqa: E402
from pruefe_reste import HISTORISCH, _absaetze, tote_importe_in  # noqa: E402


class ToteImporte(unittest.TestCase):

    def test_ein_wirklich_unbenutzter_import_wird_gemeldet(self):
        text = "package a\n\nimport java.io.File\n\nfun f() = 1\n"

        self.assertEqual(["import java.io.File"], tote_importe_in(text))

    def test_ein_benutzter_import_wird_nicht_gemeldet(self):
        text = "package a\n\nimport java.io.File\n\nfun f() = File(\"x\")\n"

        self.assertEqual([], tote_importe_in(text))

    def test_operator_importe_gelten_nie_als_tot(self):
        """`by` ruft sie auf, im Rumpf stehen sie nie. Loeschen bricht den Build."""
        text = (
            "package a\n\n"
            "import androidx.compose.runtime.getValue\n"
            "import androidx.compose.runtime.setValue\n\n"
            "var x by mutableStateOf(0)\n"
        )

        self.assertEqual([], tote_importe_in(text))

    def test_wildcard_importe_werden_uebergangen(self):
        """Bei `import a.b.*` ist gar nicht entscheidbar, welcher Name gemeint war."""
        text = "package a\n\nimport java.util.*\n\nfun f() = 1\n"

        self.assertEqual([], tote_importe_in(text))

    def test_ein_alias_import_wird_ueber_seinen_alias_geprueft(self):
        tot = "package a\n\nimport java.io.File as Datei\n\nfun f() = 1\n"
        benutzt = "package a\n\nimport java.io.File as Datei\n\nfun f() = Datei(\"x\")\n"

        self.assertEqual(["import java.io.File as Datei"], tote_importe_in(tot))
        self.assertEqual([], tote_importe_in(benutzt))

    def test_ein_laengerer_name_haelt_den_kurzen_import_nicht_am_leben(self):
        """DIE FALLE OHNE WORTGRENZEN: `rememberCoroutineScope` enthaelt `remember`.

        Ohne die Wortgrenzen im Suchmuster gaelte der Import als benutzt und ein echter
        toter Import bliebe unentdeckt - ein stiller Falsch-Negativ, also genau die
        Richtung, gegen die dieses Werkzeug gebaut ist.
        """
        text = (
            "package a\n\n"
            "import androidx.compose.runtime.remember\n\n"
            "fun f() { val s = rememberCoroutineScope() }\n"
        )

        self.assertEqual(["import androidx.compose.runtime.remember"], tote_importe_in(text))


class DokuAbsaetze(unittest.TestCase):

    def test_ein_aufzaehlungspunkt_ist_ein_absatz(self):
        zeilen = [
            "- Erster Punkt, der",
            "  ueber zwei Zeilen geht",
            "- Zweiter Punkt",
        ]

        self.assertEqual([(0, 2), (2, 3)], list(_absaetze(zeilen)))

    def test_eine_leerzeile_trennt(self):
        zeilen = ["Absatz eins", "", "Absatz zwei"]

        self.assertEqual([(0, 1), (2, 3)], list(_absaetze(zeilen)))

    def test_die_ausnahme_eines_punktes_faerbt_nicht_auf_den_naechsten_ab(self):
        """Der eigentliche Zweck der Absatz-Grenzen.

        Wuerde nur die Leerzeile trennen, laege das Signalwort des ersten Punktes im selben
        Block wie das Symbol des zweiten - und der zweite waere stillschweigend mit
        freigestellt. Genau diese Richtung ist die gefaehrliche.
        """
        zeilen = [
            "- `AltesDing` wurde in v1.30 entfernt.",
            "- `NeuesDing` macht das heute.",
        ]

        bloecke = list(_absaetze(zeilen))
        historisch = [bool(HISTORISCH.search("\n".join(zeilen[a:b]))) for a, b in bloecke]

        self.assertEqual([True, False], historisch)

    def test_ein_signalwort_deckt_den_ganzen_eigenen_absatz(self):
        """Und die Gegenrichtung: im Hergang steht das Symbol selten in derselben Zeile."""
        zeilen = [
            "- Die Entprellung wurde mit dem Ein-Modell-Umbau entfernt, weil die Regler",
            "  seither nur noch `LokalerState` schreiben.",
        ]

        (a, b), = list(_absaetze(zeilen))

        self.assertTrue(HISTORISCH.search("\n".join(zeilen[a:b])))


def _git_in(ordner, *args):
    subprocess.run(
        ["git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false"]
        + list(args),
        cwd=ordner, check=False, capture_output=True, text=True,
    )


class OffenerMerge(unittest.TestCase):
    """Echter offener Merge statt Attrappe - `git ls-files -u` ist nur so wirklich befuellt."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.repo = self._tmp.name
        _git_in(self.repo, "init", "-q", "-b", "main")
        datei = os.path.join(self.repo, "A.kt")

        def schreibe(wert):
            with open(datei, "w", encoding="utf-8") as f:
                f.write("package a\n\nval x = %d\n" % wert)

        schreibe(1)
        _git_in(self.repo, "add", "A.kt")
        _git_in(self.repo, "commit", "-q", "-m", "basis")
        _git_in(self.repo, "switch", "-q", "-c", "zweig")
        schreibe(2)
        _git_in(self.repo, "commit", "-q", "-am", "zweig")
        _git_in(self.repo, "switch", "-q", "main")
        schreibe(3)
        _git_in(self.repo, "commit", "-q", "-am", "main")

    def tearDown(self):
        self._tmp.cleanup()

    def test_sauberer_baum_ist_kein_offener_merge(self):
        with mock.patch.object(pruefe_reste, "WURZEL", self.repo):
            self.assertFalse(pruefe_reste.offener_merge())

    def test_konflikt_wird_erkannt(self):
        _git_in(self.repo, "merge", "zweig")
        with mock.patch.object(pruefe_reste, "WURZEL", self.repo):
            self.assertTrue(pruefe_reste.offener_merge())

    def test_main_ueberspringt_alle_pruefungen_bei_offenem_merge(self):
        _git_in(self.repo, "merge", "zweig")
        explodiert = mock.Mock(side_effect=AssertionError("Pruefung lief trotz offenem Merge"))
        with mock.patch.object(pruefe_reste, "WURZEL", self.repo),                 mock.patch.object(pruefe_reste, "pruefe_tote_importe", explodiert),                 mock.patch.object(sys, "argv", ["pruefe_reste.py", "--ci"]):
            self.assertEqual(0, pruefe_reste.main())
        explodiert.assert_not_called()

    def test_main_prueft_ohne_offenen_merge(self):
        """Gegenprobe: der Waechter darf das Gatter nicht dauerhaft abschalten."""
        gerufen = mock.Mock()
        with mock.patch.object(pruefe_reste, "WURZEL", self.repo),                 mock.patch.object(pruefe_reste, "pruefe_tote_importe", gerufen),                 mock.patch.object(pruefe_reste, "pruefe_verwaiste_strings"),                 mock.patch.object(pruefe_reste, "pruefe_composable_ohne_verbraucher"),                 mock.patch.object(pruefe_reste, "pruefe_haengende_kdocs"),                 mock.patch.object(pruefe_reste, "pruefe_ungenutzte_konstanten"),                 mock.patch.object(pruefe_reste, "pruefe_doku_verweise"),                 mock.patch.object(sys, "argv", ["pruefe_reste.py", "--ci"]):
            pruefe_reste.main()
        gerufen.assert_called_once()


if __name__ == "__main__":
    unittest.main()
