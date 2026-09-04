"""Testet die Erkennung von Enum-Eintraegen ohne Verwender (Pruefung 7).

WARUM ES DIESE PRUEFUNG GIBT (Runde 16, Issue #19): 35 Enums, 146 Eintraege, SIEBEN ohne jeden
Verwender und KEIN Fehlalarm - sechs `ActionType`-Werte auf Vorrat und `DiscoveryMethod.N_UPNP`.
Pruefung 6 sah sie nicht: ein Enum-Eintrag ist weder `val` noch `var`.

WAS DIESE TESTS VOR ALLEM FESTHALTEN, ist die Blindstelle des Parsers. Die erste Messfassung
schnitt die Eintragsliste am ersten `;` auf Klammertiefe 0 ab - und `RueckbauErgebnis` hat einen
Strichpunkt IM KDoc seines ersten Eintrags. Das Enum galt als leer, acht Eintraege blieben
ungeprueft, und das Gatter haette "sauber" gemeldet. Deshalb ist
`test_ein_strichpunkt_im_kdoc_schneidet_die_liste_nicht_ab` kein Randfall, sondern der Grund,
warum vor jeder Struktursuche ausgeblendet wird.

Aufruf:
    python -m unittest discover -s tools/aufraeumen -p "test_*.py"
"""
from __future__ import annotations

import os
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from pruefe_reste import enum_eintraege_in  # noqa: E402

UMBRUCH = chr(10)
WERKZEUG = os.path.abspath(__file__).replace("test_enum_eintraege.py", "pruefe_reste.py")


def quelle(*zeilen):
    return UMBRUCH.join(zeilen)


def namen(text):
    return [eintrag for _, eintrag, _, _ in enum_eintraege_in(text)]


class EnumZerlegung(unittest.TestCase):
    """Die Zerlegung - sie entscheidet, WELCHE Eintraege ueberhaupt geprueft werden."""

    def test_einfache_eintraege_werden_gefunden(self):
        text = quelle("enum class A {", "    ROT,", "    GRUEN", "}")

        self.assertEqual(["ROT", "GRUEN"], namen(text))

    def test_ein_strichpunkt_im_kdoc_schneidet_die_liste_nicht_ab(self):
        """DER GRUND FUER DAS AUSBLENDEN - der reale Fall `RueckbauErgebnis`.

        Ohne Ausblenden endet die Eintragsliste am Strichpunkt im Kommentar; das Enum gilt als
        leer und KEINER seiner Eintraege wird je geprueft. Ein Gatter mit dieser Luecke meldet
        "keine Reste gefunden" und hat schlicht nicht hingesehen.
        """
        text = quelle(
            "enum class RueckbauErgebnis {",
            "    /** Der Alarm ist wieder weg; es steht nichts Scharfes mehr. */",
            "    ABGERAEUMT,",
            "",
            "    /** Der Rueckbau hat nicht funktioniert. */",
            "    MISSLUNGEN",
            "}",
        )

        self.assertEqual(["ABGERAEUMT", "MISSLUNGEN"], namen(text))

    def test_ein_strichpunkt_in_einem_string_schneidet_die_liste_nicht_ab(self):
        text = quelle(
            "enum class A(val text: String) {",
            '    ROT("rot; warm"),',
            '    BLAU("blau")',
            "}",
        )

        self.assertEqual(["ROT", "BLAU"], namen(text))

    def test_methoden_hinter_dem_strichpunkt_sind_keine_eintraege(self):
        """Der echte Strichpunkt trennt Eintraege von Methoden - dahinter darf nichts zaehlen."""
        text = quelle(
            "enum class A {",
            "    ROT,",
            "    BLAU;",
            "",
            "    fun istRot() = this == ROT",
            "}",
        )

        self.assertEqual(["ROT", "BLAU"], namen(text))

    def test_eintraege_mit_argumenten_und_rumpf(self):
        text = quelle(
            "enum class A(val n: Int) {",
            "    EINS(1) { override fun x() = 1 },",
            "    ZWEI(2)",
            "}",
        )

        self.assertEqual(["EINS", "ZWEI"], namen(text))

    def test_die_zeilennummer_zeigt_auf_den_eintrag(self):
        text = quelle("enum class A {", "    ROT,", "    BLAU", "}")

        self.assertEqual([2, 3], [zeile for _, _, zeile, _ in enum_eintraege_in(text)])


class GatterUeberEinemWegwerfRepo(unittest.TestCase):
    """DIE VERDRAHTUNG, nicht nur die Funktion.

    Lehre aus Runde 15 (PR #48): dort blieben alle Tests gruen, wenn man den Aufruf aus `main()`
    entfernte - die Pruefung war getestet und trotzdem tot. Diese Tests rufen deshalb das ECHTE
    Skript als Unterprozess ueber einem Wegwerf-Baum auf und lesen seinen Rueckgabewert.
    """

    def baue_repo(self, verzeichnis, inhalt):
        quellen = os.path.join(verzeichnis, "app", "src", "main", "java")
        os.makedirs(quellen)
        with open(os.path.join(quellen, "A.kt"), "w", encoding="utf-8") as f:
            f.write(inhalt)
        werkzeuge = os.path.join(verzeichnis, "tools", "aufraeumen")
        os.makedirs(werkzeuge)
        with open(WERKZEUG, encoding="utf-8") as f:
            skript = f.read()
        ziel = os.path.join(werkzeuge, "pruefe_reste.py")
        with open(ziel, "w", encoding="utf-8") as f:
            f.write(skript)
        return ziel

    def lauf(self, inhalt):
        with tempfile.TemporaryDirectory() as verzeichnis:
            ziel = self.baue_repo(verzeichnis, inhalt)
            fertig = subprocess.run(
                [sys.executable, ziel, "--ci"], capture_output=True, text=True, timeout=120,
            )
            return fertig.returncode, fertig.stdout + fertig.stderr

    def test_ein_toter_eintrag_laesst_das_gatter_rot_werden(self):
        kode, ausgabe = self.lauf(quelle(
            "enum class Aktion {",
            "    AN,",
            "    TOT",
            "}",
            "val benutzt = Aktion.AN",
        ))

        self.assertEqual(1, kode)
        self.assertIn("Aktion.TOT", ausgabe)
        self.assertNotIn("Aktion.AN", ausgabe)

    def test_ein_baum_ohne_tote_eintraege_bleibt_still(self):
        """Die Gegenprobe - ein Gatter, das immer meldet, schuetzt nichts."""
        kode, ausgabe = self.lauf(quelle(
            "enum class Aktion {",
            "    AN,",
            "    AUS",
            "}",
            "val a = Aktion.AN",
            "val b = Aktion.AUS",
        ))

        self.assertEqual(0, kode, ausgabe)

    def test_ein_iteriertes_enum_wird_ausgenommen(self):
        """`entries` erreicht jeden Eintrag, ohne ihn zu nennen - hier waere Melden falsch."""
        kode, ausgabe = self.lauf(quelle(
            "enum class Aktion {",
            "    AN,",
            "    AUS",
            "}",
            "val alle = Aktion.entries.toList()",
        ))

        self.assertEqual(0, kode, ausgabe)

    def test_die_begruendung_im_kommentar_nimmt_einen_eintrag_aus(self):
        """Der Ausweg fuer ein GESPEICHERTES FORMAT - dieselbe Ausnahme wie bei Pruefung 6."""
        kode, ausgabe = self.lauf(quelle(
            "enum class Aktion {",
            "    AN,",
            "    /** Steht OHNE VERWENDER im Bestands-JSON auf echten Geraeten. */",
            "    ALT",
            "}",
            "val a = Aktion.AN",
        ))

        self.assertEqual(0, kode, ausgabe)

    def test_eine_nennung_in_einem_string_zaehlt_als_verwender(self):
        """Ein Eintragsname in einer Test-JSON ist Teil eines gespeicherten Formats.

        Deshalb wird auf dem ROHTEXT gezaehlt: wer hier Strings ausblendet, erklaert ein
        persistiertes Format zu totem Code.
        """
        kode, ausgabe = self.lauf(quelle(
            "enum class Aktion {",
            "    AN,",
            "    GESPEICHERT",
            "}",
            "val a = Aktion.AN",
            'val json = "{\\"aktion\\":\\"GESPEICHERT\\"}"',
        ))

        self.assertEqual(0, kode, ausgabe)


class OffenerMerge(unittest.TestCase):
    """DER KONFLIKTZUSTAND - die teuerste Lehre aus Runde 15 (PR #56, geschlossen).

    Eine blockierende Pruefung, die den Baum liest, trifft waehrend eines offenen Merge auf
    Konfliktmarker. Meldet sie dann, blockiert sie ausgerechnet `git merge --continue` und
    `git merge --abort` - die beiden Rettungsbefehle -, und zwar mit einer Fehldiagnose. Der
    Ausweg ist billig; hier steht er als Test, nicht als Behauptung im Kommentar.
    """

    def test_bei_unaufgeloesten_pfaden_schweigt_die_pruefung(self):
        with tempfile.TemporaryDirectory() as verzeichnis:
            hilfe = GatterUeberEinemWegwerfRepo()
            # Ein Baum, der OHNE Konflikt melden wuerde - sonst beweist der Test nichts.
            ziel = hilfe.baue_repo(verzeichnis, quelle(
                "enum class Aktion {", "    AN,", "    TOT", "}", "val a = Aktion.AN",
            ))
            quelldatei = os.path.join(verzeichnis, "app", "src", "main", "java", "A.kt")

            def git(*args):
                subprocess.run(["git"] + list(args), cwd=verzeichnis, check=True,
                               capture_output=True, text=True)

            git("init", "-q")
            git("config", "user.email", "test@example.invalid")
            git("config", "user.name", "Test")
            git("add", "-A")
            git("commit", "-qm", "Basis")
            git("checkout", "-qb", "zweig")
            with open(quelldatei, "w", encoding="utf-8") as f:
                f.write(quelle("enum class Aktion {", "    AN,", "    TOT,", "    ZWEIG", "}"))
            git("add", "-A")
            git("commit", "-qm", "Zweig")
            git("checkout", "-q", "master" if _hat_zweig(verzeichnis, "master") else "main")
            with open(quelldatei, "w", encoding="utf-8") as f:
                f.write(quelle("enum class Aktion {", "    AN,", "    TOT,", "    HAUPT", "}"))
            git("add", "-A")
            git("commit", "-qm", "Haupt")
            merge = subprocess.run(["git", "merge", "zweig"], cwd=verzeichnis,
                                   capture_output=True, text=True)
            self.assertNotEqual(0, merge.returncode, "Der Testaufbau erzeugt keinen Konflikt")

            fertig = subprocess.run([sys.executable, ziel, "--ci"], capture_output=True,
                                    text=True, timeout=120)

            self.assertEqual(0, fertig.returncode, fertig.stdout + fertig.stderr)


def _hat_zweig(verzeichnis, name):
    fertig = subprocess.run(["git", "rev-parse", "--verify", name], cwd=verzeichnis,
                            capture_output=True, text=True)
    return fertig.returncode == 0


if __name__ == "__main__":
    unittest.main()
