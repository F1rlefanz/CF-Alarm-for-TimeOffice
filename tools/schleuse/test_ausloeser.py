"""Testet, WORAUF die Schleuse anspringt - und worauf ausdruecklich nicht.

WARUM ES DAS GIBT (05.09.2026)
------------------------------
Der Ausloeser war `\\bgit\\s+(merge|push)\\b`. Zwischen `merge` und dem
Bindestrich in `git merge-base` steht eine Wortgrenze, also traf er auch diesen
reinen LESEBEFEHL und startete einen Zehn-Minuten-Gradle-Lauf. Die Schleuse ruft
`git merge-base` selbst auf, um ihre Basis zu bestimmen - der Fehlalarm sass
also im eigenen Werkzeugkasten.

Dieselbe Regex sperrt `git merge --abort` und `--continue`. Das ist die
teuerste Variante: waehrend eines Konflikts stehen Konfliktmarker im Baum,
Pruefungen schlagen an, und die beiden Befehle, mit denen man da wieder
herauskommt, sind blockiert. Die Leitplanke dazu steht seit Runde 15 im Skill;
behoben war bis heute nur die Doppelmeldung, nicht die Blockade.

Beide Richtungen gehoeren getestet: dass die Ausnahmen greifen UND dass die
Schleuse weiterhin auf alles anspringt, wofuer sie gebaut wurde. Eine Ausnahme,
die zu weit greift, ist schlimmer als der Fehlalarm - sie laesst ungeprueft
durch.

Aufruf:
    python -m unittest discover -s tools -p "test_*.py"
    python tools/schleuse/test_ausloeser.py     # einzeln
"""
from __future__ import annotations

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import pruefe_schleuse  # noqa: E402
from pruefe_schleuse import HANDBETRIEB, befehl_aus_stdin, soll_pruefen  # noqa: E402


class StdinAttrappe:
    """Ein stdin, dessen `isatty()` wir bestimmen - und das beim Lesen auffliegt."""

    def __init__(self, terminal, inhalt=""):
        self._terminal = terminal
        self._inhalt = inhalt
        self.wurde_gelesen = False

    def isatty(self):
        return self._terminal

    def read(self):
        self.wurde_gelesen = True
        return self._inhalt


class Handbetrieb(unittest.TestCase):
    """Von Hand aufgerufen darf sie weder haengen noch stillschweigend freisprechen.

    Am 05.09.2026 tat sie beides: ohne Umleitung wartete `sys.stdin.read()` ewig
    (dreimal je 25 Minuten, sah aus wie ein haengender Build), und mit `< NUL`
    fiel sie in den fail-open-Zweig und meldete Exit 0, ohne EINE Pruefung zu
    fahren.
    """

    def setUp(self):
        self._echt = pruefe_schleuse.sys.stdin

    def tearDown(self):
        pruefe_schleuse.sys.stdin = self._echt

    def test_am_terminal_wird_stdin_gar_nicht_gelesen(self):
        """Das eigentliche Haengen: `read()` darf hier nicht einmal aufgerufen werden."""
        attrappe = StdinAttrappe(terminal=True)
        pruefe_schleuse.sys.stdin = attrappe
        self.assertIs(befehl_aus_stdin(), HANDBETRIEB)
        self.assertFalse(attrappe.wurde_gelesen)

    def test_handbetrieb_endet_mit_fehlercode(self):
        """Kein stiller Freispruch - der Aufrufer muss merken, dass nichts geprueft wurde."""
        pruefe_schleuse.sys.stdin = StdinAttrappe(terminal=True)
        self.assertNotEqual(pruefe_schleuse.main(), 0)

    def test_hinweis_zeigt_den_richtigen_aufruf(self):
        self.assertIn("tool_input", pruefe_schleuse.HANDBETRIEB_HINWEIS)

    def test_als_hook_wird_gelesen(self):
        """Die Gegenrichtung - hinter einer Pipe liest sie ganz normal."""
        attrappe = StdinAttrappe(terminal=False, inhalt='{"tool_input":{"command":"git status"}}')
        pruefe_schleuse.sys.stdin = attrappe
        self.assertEqual(befehl_aus_stdin(), "git status")
        self.assertTrue(attrappe.wurde_gelesen)

    def test_leere_pipe_bleibt_fail_open(self):
        """Ein Hook mit leerem Eingang darf NICHT blockieren - sonst sperrt ein
        Defekt hier jede Arbeit aus. Nur der Mensch bekommt den Fehlercode."""
        pruefe_schleuse.sys.stdin = StdinAttrappe(terminal=False, inhalt="")
        self.assertIsNone(befehl_aus_stdin())

    def test_notausgang_kommt_vor_dem_stdin_lesen(self):
        """Sonst ist der Notausgang keiner.

        Er stand bis zum 05.09.2026 HINTER dem stdin-Lesen. Genau dort kann sich
        die Schleuse aber aufhaengen (Terminal) oder blockieren (Fehlercode 2) -
        wer dann `CFALARM_SCHLEUSE=aus` setzt, kam trotzdem nicht durch.
        """
        attrappe = StdinAttrappe(terminal=True)
        pruefe_schleuse.sys.stdin = attrappe
        alt = os.environ.get(pruefe_schleuse.NOTAUSGANG)
        os.environ[pruefe_schleuse.NOTAUSGANG] = "aus"
        try:
            self.assertEqual(pruefe_schleuse.main(), 0)
            self.assertFalse(attrappe.wurde_gelesen)
        finally:
            if alt is None:
                del os.environ[pruefe_schleuse.NOTAUSGANG]
            else:
                os.environ[pruefe_schleuse.NOTAUSGANG] = alt


class LoestAus(unittest.TestCase):
    """Die Schleuse muss anspringen - sonst verlaesst Ungeprueftes den Branch."""

    def test_merge(self):
        self.assertTrue(soll_pruefen("git merge --no-ff chore/etwas"))

    def test_push(self):
        self.assertTrue(soll_pruefen("git push origin main"))

    def test_push_ohne_argumente(self):
        self.assertTrue(soll_pruefen("git push"))

    def test_merge_am_zeilenende(self):
        self.assertTrue(soll_pruefen("git merge"))

    def test_in_einer_kette(self):
        self.assertTrue(soll_pruefen("git fetch -q origin && git push origin main"))

    def test_merge_mit_nachricht(self):
        self.assertTrue(soll_pruefen('git merge --no-ff x -m "Merge branch \'x\'"'))

    def test_push_mit_upstream(self):
        self.assertTrue(soll_pruefen("git push -u origin feature/etwas"))


class LoestNichtAus(unittest.TestCase):
    """Die Ausnahmen - jede einzeln erkauft."""

    def test_merge_base_ist_ein_lesebefehl(self):
        """Der Fehlalarm vom 05.09.2026."""
        self.assertFalse(soll_pruefen("git merge-base HEAD origin/main"))

    def test_merge_base_in_einer_kette(self):
        self.assertFalse(soll_pruefen("git merge-base --is-ancestor a b && echo ja"))

    def test_rettungsbefehl_abort(self):
        self.assertFalse(soll_pruefen("git merge --abort"))

    def test_rettungsbefehl_continue(self):
        self.assertFalse(soll_pruefen("git merge --continue"))

    def test_rettungsbefehl_quit(self):
        self.assertFalse(soll_pruefen("git merge --quit"))

    def test_dry_run(self):
        self.assertFalse(soll_pruefen("git push --dry-run origin main"))

    def test_anderer_git_befehl(self):
        self.assertFalse(soll_pruefen("git status"))

    def test_kein_git(self):
        self.assertFalse(soll_pruefen("./gradlew testDebugUnitTest"))

    def test_wortanfang_zaehlt(self):
        """`pushd` ist kein `push`."""
        self.assertFalse(soll_pruefen("pushd C:/tmp"))

    def test_angehaengtes_wortzeichen(self):
        self.assertFalse(soll_pruefen("git pushx"))


class DieAusnahmenGreifenNichtZuWeit(unittest.TestCase):
    """Eine zu breite Ausnahme laesst Ungeprueftes durch - das waere schlimmer."""

    def test_abort_als_zweigname_rettet_nicht(self):
        """`--abort` muss direkt hinter `git merge` stehen, nicht irgendwo."""
        self.assertTrue(soll_pruefen("git merge --no-ff fix/abort-behandlung"))

    def test_continue_im_branchnamen_rettet_nicht(self):
        self.assertTrue(soll_pruefen("git merge chore/continue-marker"))

    def test_push_nach_einem_merge_base_wird_geprueft(self):
        self.assertTrue(soll_pruefen("git merge-base HEAD main; git push origin main"))

    def test_dry_run_deckt_nicht_den_ganzen_rest(self):
        """Bekannte Grenze, bewusst so: `--dry-run` gilt fuer die ganze Zeile."""
        self.assertFalse(soll_pruefen("git push --dry-run && git push origin main"))


class AndererArbeitsordner(unittest.TestCase):
    """#212 (05.11.2026): Aus einem anderen Worktree gemergt oder gepusht, lief keine Pruefung.

    Beim Abschluss von Etappe 2 ging `git -C <worktree> merge` ohne Schleuse durch, und
    `cd <worktree> && git push origin main` ebenso. Griff der Hook doch, pruefte er den
    Baum der Sitzung statt den des Ziels. Beides gehoert zum vollautonomen Auslieferungspfad.
    """

    def test_git_mit_C_loest_aus(self):
        self.assertTrue(soll_pruefen("git -C ../CFAlarm-umbau-wt/main push origin main"))
        self.assertTrue(soll_pruefen('git -C "C:/x y/wt" merge --no-ff feature/a'))

    def test_globale_optionen_vor_dem_befehl_loesen_aus(self):
        self.assertTrue(soll_pruefen("git -c core.hooksPath=/dev/null push origin main"))
        self.assertTrue(soll_pruefen("git --no-pager -C wt merge x"))

    def test_rettungsbefehle_mit_C_bleiben_frei(self):
        self.assertFalse(soll_pruefen("git -C ../wt merge --abort"))

    def test_lesebefehle_mit_C_bleiben_frei(self):
        self.assertFalse(soll_pruefen("git -C ../wt log --oneline -3"))
        self.assertFalse(soll_pruefen("git -C ../wt merge-base HEAD origin/main"))

    def test_ziel_aus_C(self):
        z = pruefe_schleuse.ziel_verzeichnis("git -C ../wt/main push origin main", "C:/repo")
        self.assertEqual(os.path.normpath(z), os.path.normpath("C:/wt/main"))

    def test_ziel_aus_cd_davor(self):
        z = pruefe_schleuse.ziel_verzeichnis('cd "C:/a b/wt" && git push origin main', "C:/repo")
        self.assertEqual(os.path.normpath(z), os.path.normpath("C:/a b/wt"))

    def test_letztes_cd_vor_dem_befehl_zaehlt(self):
        z = pruefe_schleuse.ziel_verzeichnis("cd /c/x; cd ../y && git merge z; cd /c/q", "C:/repo")
        self.assertEqual(os.path.normpath(z), os.path.normpath("C:/y"))

    def test_msys_pfad_wird_windows_pfad(self):
        z = pruefe_schleuse.ziel_verzeichnis("cd /c/Users/x/wt && git push", "C:/repo")
        self.assertEqual(os.path.normpath(z), os.path.normpath("C:/Users/x/wt"))

    def test_ohne_cd_und_C_kein_fremdes_ziel(self):
        self.assertIsNone(pruefe_schleuse.ziel_verzeichnis("git push origin main", "C:/repo"))

    def test_powershell_set_location(self):
        z = pruefe_schleuse.ziel_verzeichnis("Set-Location -Path 'D:\\wt'; git push", "C:/repo")
        self.assertEqual(os.path.normpath(z), os.path.normpath("D:/wt"))


if __name__ == "__main__":
    unittest.main()
