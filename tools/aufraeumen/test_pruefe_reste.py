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

Aufruf:
    python -m unittest discover -s tools/aufraeumen -p "test_*.py"
    python tools/aufraeumen/test_pruefe_reste.py
"""
from __future__ import annotations

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from pruefe_reste import (  # noqa: E402
    HISTORISCH,
    _absaetze,
    tote_importe_in,
    wirkungslose_lint_eintraege,
)


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


class WirkungsloseLintEintraege(unittest.TestCase):
    """Runde 10: der real gefundene Fall war `<issue id="BatteryLife">` mit nur einem
    Kommentar darin - er sah nach Unterdrueckung aus, unterdrueckte aber nichts (gemessen:
    Entfernen aendert den Lint-Bericht in keiner Zeile). Die Gegenrichtung ist hier die
    gefaehrlichere: meldet das Gatter einen Eintrag, der WIRKT, wird eine echte
    Unterdrueckung geloescht - und ein Lint-Fehler bricht danach den Release-Build.
    """

    def test_ein_block_ohne_severity_und_ohne_ignore_wird_gemeldet(self):
        text = '<lint>\n    <issue id="BatteryLife">\n    </issue>\n</lint>\n'

        self.assertEqual(["BatteryLife"], wirkungslose_lint_eintraege(text))

    def test_ein_block_mit_nur_einem_kommentar_darin_wird_gemeldet(self):
        """Der Originalfall: der Kommentar sieht nach Inhalt aus, konfiguriert aber nichts."""
        text = '<lint>\n    <issue id="BatteryLife">\n        <!-- alte Datei entfernt -->\n' \
               '    </issue>\n</lint>\n'

        self.assertEqual(["BatteryLife"], wirkungslose_lint_eintraege(text))

    def test_severity_ignore_und_option_gelten_als_wirksam(self):
        text = (
            '<lint>\n'
            '    <issue id="ObsoleteSdkInt" severity="informational" />\n'
            '    <issue id="CustomX509TrustManager">\n'
            '        <ignore path="**/HueTrustManager.kt" />\n'
            '    </issue>\n'
            '    <issue id="UnusedIds">\n'
            '        <option name="x" value="y" />\n'
            '    </issue>\n'
            '</lint>\n'
        )

        self.assertEqual([], wirkungslose_lint_eintraege(text))

    def test_ein_auskommentierter_block_zaehlt_nicht(self):
        """Sonst meldet das Gatter einen Eintrag, den Lint nie gesehen hat."""
        text = '<lint>\n    <!--\n    <issue id="Alt">\n    </issue>\n    -->\n</lint>\n'

        self.assertEqual([], wirkungslose_lint_eintraege(text))

    def test_die_echte_datei_ist_sauber(self):
        """Die Ausgangslage nach Runde 10 - schlaegt an, wenn jemand so einen Block zurueckholt."""
        pfad = os.path.join(os.path.dirname(os.path.dirname(
            os.path.dirname(os.path.abspath(__file__)))), "app", "lint.xml")
        with open(pfad, encoding="utf-8") as f:
            self.assertEqual([], wirkungslose_lint_eintraege(f.read()))


if __name__ == "__main__":
    unittest.main()
