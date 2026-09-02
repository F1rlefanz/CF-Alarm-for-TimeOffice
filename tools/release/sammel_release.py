#!/usr/bin/env python3
"""Sammel-Release: liefert Wartungsarbeit aus, die sonst unausgeliefert in `main` liegen bliebe.

## Warum es das gibt

Seit dem 01.09.2026 laedt `.github/workflows/veroeffentlichen.yml` jede Version in den internen
Play-Track - ausgeloest von einem erhoehten `versionCode` in `app/build.gradle.kts`. Dependabot
und die naechtliche Aufraeumrunde fassen diese Datei aber NIE an (am 02.09.2026 ueber die ganze
Historie geprueft: kein einziger `chore(deps)`- oder `chore(aufraeumen)`-Commit hat sie beruehrt).
Ihre Aenderungen landen also in `main` und erreichen die Tester erst, wenn ein Mensch das naechste
Mal eine Version baut - unter Umstaenden Wochen spaeter.

Dieses Werkzeug schliesst die Luecke: es erkennt Wartungs-Commits, die noch niemand ausgeliefert
hat, und schreibt dafuer einen Patch-Bump samt Changelog-Eintrag fort.

## Die Grenze, und sie ist der wichtigste Teil

**Automatisch ausgeliefert wird NUR, wenn ALLE unausgelieferten Commits Wartung sind.** Sobald ein
einziger inhaltlicher Commit dabei ist, tut dieses Werkzeug nichts. Der Grund ist nicht Vorsicht um
ihrer selbst willen: eine inhaltliche Aenderung gehoert in einen Changelog-Eintrag, den ein Mensch
in Nutzersprache schreibt, und sie gehoert vor dem Ausliefern angesehen. Ein Bot, der sie
mitveroeffentlicht, wuerde beides ueberspringen - und in diesem Projekt hat ein gruener Build schon
einmal einen Absturz beim Start durchgelassen (05.08.2026, Property nach `init{}`).

Wartung heisst hier ausschliesslich: Abhaengigkeits-Bumps von Dependabot und Aufraeum-PRs, die der
Torwaechter bereits selbst gebaut, getestet und von drei Widerlegern hat pruefen lassen.
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

WURZEL = Path(__file__).resolve().parents[2]
VERSIONSDATEI = WURZEL / "app" / "build.gradle.kts"
CHANGELOG = WURZEL / "CHANGELOG.md"

# Betreffzeilen, die als Wartung gelten. Bewusst eng: was hier nicht steht, blockiert die
# automatische Auslieferung, statt stillschweigend mitzufahren.
WARTUNG_PRAEFIXE = ("chore(deps)", "chore(aufraeumen)", "chore(ci)")

# Merge-Commits von Wartungszweigen. `unausgelieferte_commits()` filtert Merges inzwischen ohnehin
# heraus - dieses Muster ist der zweite Riegel, nicht der erste: es greift, wenn jemand die Liste
# ohne `--no-merges` auswertet (etwa beim Nachrechnen von Hand). Der Torwaechter merged mit
# `--merge`, Dependabot squasht; in der Historie kommen beide Formen vor (725fb90 gegen 580f549).
MERGE_MUSTER = re.compile(
    r"^Merge (?:pull request #\d+ from |branch .)"
    r"(?:[\w.-]+/)?(?:chore/aufraeumen-|dependabot/)"
)

MONATE = {
    1: "Januar", 2: "Februar", 3: "März", 4: "April", 5: "Mai", 6: "Juni",
    7: "Juli", 8: "August", 9: "September", 10: "Oktober", 11: "November", 12: "Dezember",
}


def ist_wartungscommit(betreff: str) -> bool:
    """Ist dieser Commit-Betreff reine Wartung - also ohne eigenen Changelog-Bedarf?"""
    betreff = betreff.strip()
    if betreff.startswith(WARTUNG_PRAEFIXE):
        return True
    return bool(MERGE_MUSTER.match(betreff))


def naechste_version(version_name: str, version_code: int) -> tuple[str, int]:
    """Patch-Bump. Ein Sammel-Release ist nie mehr als ein Patch - es aendert nichts Sichtbares."""
    teile = version_name.split(".")
    if len(teile) != 3 or not all(t.isdigit() for t in teile):
        raise ValueError(f"versionName '{version_name}' ist nicht MAJOR.MINOR.PATCH")
    haupt, neben, patch = (int(t) for t in teile)
    return f"{haupt}.{neben}.{patch + 1}", version_code + 1


def lies_version(text: str) -> tuple[str, int]:
    name = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    code = re.search(r"versionCode\s*=\s*(\d+)", text)
    if not name or not code:
        raise ValueError("versionName/versionCode nicht gefunden")
    return name.group(1), int(code.group(1))


def schreibe_version(text: str, name: str, code: int) -> str:
    text = re.sub(r'(versionName\s*=\s*")[^"]+(")', r"\g<1>" + name + r"\g<2>", text, count=1)
    return re.sub(r"(versionCode\s*=\s*)\d+", r"\g<1>" + str(code), text, count=1)


def changelog_block(version: str, anzahl: int, jahr: int, monat: int) -> str:
    """Der Eintrag fuer ein Sammel-Release - in Nutzersprache, ohne Commit-Prosa.

    Commit-Betreffs kaemen hier NICHT hinein: sie sind Entwickler-Prosa ("bewusstes commit() in der
    Weckerkette gegen Lint verankern"), und der Changelog ist fuer Schichtarbeiter geschrieben. Was
    der Nutzer wissen will, ist genau zweierlei - dass sich fuer ihn nichts aendert, und warum es
    die Version trotzdem gibt.
    """
    was = "Eine Änderung" if anzahl == 1 else f"{anzahl} Änderungen"
    return (
        "## 🆕 Version " + version + " (Aktuell – interne Alpha)\n"
        "\n"
        "**Stand:** " + MONATE[monat] + " " + str(jahr) + "\n"
        "\n"
        "_Wartungsversion — für die Bedienung ändert sich nichts._\n"
        "\n"
        "### 🔧 Unter der Haube\n"
        "\n"
        "- **" + was + " ohne sichtbare Wirkung:** aktualisierte Fremdbibliotheken und "
        "entfernter, ungenutzter Code. Solche Versionen gibt es, damit Wartungsarbeit auch "
        "wirklich auf dem Gerät ankommt, statt monatelang unausgeliefert liegen zu bleiben — "
        "jede einzelne davon ist vorher gebaut, getestet und gegengeprüft worden.\n"
        "\n"
    )


def setze_neuen_block(changelog: str, block: str) -> str:
    """Haengt den Block oben an und nimmt der bisher obersten Version ihr `🆕` samt Zusatz."""
    treffer = re.search(r"^## 🆕 Version (\S+) \(Aktuell – interne Alpha\)$", changelog, re.M)
    if treffer:
        changelog = changelog.replace(treffer.group(0), "## Version " + treffer.group(1), 1)
        stelle = changelog.index("## Version " + treffer.group(1))
    else:
        # Kein 🆕 vorhanden: vor die erste Versionsueberschrift setzen.
        erste = re.search(r"^## Version ", changelog, re.M)
        if not erste:
            raise ValueError("CHANGELOG.md enthaelt keine Versionsueberschrift")
        stelle = erste.start()
    return changelog[:stelle] + block + changelog[stelle:]


def _git(*args: str) -> str:
    # `encoding` ausdruecklich: unter Windows dekodiert Python sonst mit cp1252, und der erste
    # Commit-Betreff mit einem Umlaut oder Emoji laesst das Werkzeug abstuerzen.
    return subprocess.run(
        ["git", *args], cwd=WURZEL, capture_output=True, text=True, check=True,
        encoding="utf-8", errors="replace",
    ).stdout.strip()


def _version_code_bei(commit: str) -> int | None:
    try:
        text = _git("show", commit + ":app/build.gradle.kts")
    except subprocess.CalledProcessError:
        return None
    treffer = re.search(r"versionCode\s*=\s*(\d+)", text)
    return int(treffer.group(1)) if treffer else None


def letzter_release_commit() -> str | None:
    """Der juengste Commit, der den `versionCode` WIRKLICH erhoeht hat.

    Nicht einfach "der letzte Commit, der app/build.gradle.kts angefasst hat": diese Datei aendert
    sich auch fuer compileSdk, Abhaengigkeiten oder die Signier-Konfiguration, ohne dass etwas
    ausgeliefert wurde. Verglichen wird deshalb je Kandidat gegen seinen ersten Elternteil.
    """
    for commit in _git("log", "--format=%H", "--", "app/build.gradle.kts").splitlines():
        jetzt = _version_code_bei(commit)
        if jetzt is None:
            continue
        eltern = _version_code_bei(commit + "^")
        if eltern is None or jetzt > eltern:
            return commit
    return None


def unausgelieferte_commits() -> list[tuple[str, str]]:
    """Die ARBEITS-Commits seit dem letzten Release - Merge-Commits zaehlen nicht mit.

    `--no-merges` ist hier nicht Bequemlichkeit, sondern notwendig. Dieses Repo merged mit
    `--no-ff`; nach jedem Release steht also ein "Merge branch 'chore/release-...'" hinter dem
    Bump-Commit. Ohne `--no-merges` gilt diese reine Buchhaltungszeile als unausgelieferter
    Nicht-Wartungs-Commit und blockiert die Automatik DAUERHAFT - am 02.09.2026 unmittelbar nach
    dem ersten Scharfschalten aufgefallen, mit genau einem solchen Eintrag.

    Die Arbeit selbst geht dabei nicht verloren: die Commits eines gemergten Zweigs sind ueber den
    zweiten Elternteil erreichbar und stehen weiter in der Liste. Beurteilt wird also, was
    tatsaechlich geaendert wurde, statt wie es zusammengefuehrt wurde.

    Der Preis, offen gesagt: ein "evil merge" - eine Aenderung, die es nur im Merge-Commit selbst
    gibt und in keinem Elternteil - waere hier unsichtbar. In diesem Repo entstehen Merges durch
    Werkzeuge und werden nicht von Hand aufgeloest; das ist vertretbar.
    """
    basis = letzter_release_commit()
    bereich = (basis + "..HEAD") if basis else "HEAD"
    zeilen = _git("log", "--no-merges", "--format=%H%x1f%s", bereich).splitlines()
    return [(z.split("\x1f", 1)[0], z.split("\x1f", 1)[1]) for z in zeilen if "\x1f" in z]


def ermitteln() -> tuple[bool, list[tuple[str, str]], str]:
    """(ausliefern, unausgelieferte Commits, Begruendung in einem Satz)"""
    commits = unausgelieferte_commits()
    if not commits:
        return False, [], "Nichts Unausgeliefertes - main steht auf dem letzten Release."
    fremd = [(h, b) for h, b in commits if not ist_wartungscommit(b)]
    if fremd:
        namen = ", ".join(h[:7] + " " + b for h, b in fremd[:3])
        return False, commits, (
            str(len(fremd)) + " von " + str(len(commits)) + " unausgelieferten Commits sind KEINE "
            "Wartung (" + namen + "). Das veroeffentlicht ein Mensch, mit eigenem "
            "Changelog-Eintrag."
        )
    return True, commits, str(len(commits)) + " unausgelieferte Wartungs-Commits."


def bumpen(anzahl: int, jahr: int, monat: int) -> tuple[str, int]:
    gradle = VERSIONSDATEI.read_text(encoding="utf-8")
    name, code = lies_version(gradle)
    neuer_name, neuer_code = naechste_version(name, code)
    VERSIONSDATEI.write_text(schreibe_version(gradle, neuer_name, neuer_code), encoding="utf-8")
    CHANGELOG.write_text(
        setze_neuen_block(
            CHANGELOG.read_text(encoding="utf-8"),
            changelog_block(neuer_name, anzahl, jahr, monat),
        ),
        encoding="utf-8",
    )
    return neuer_name, neuer_code


def main() -> int:
    p = argparse.ArgumentParser(description="Sammel-Release ermitteln und vorbereiten")
    p.add_argument("--bumpen", action="store_true", help="Version und Changelog fortschreiben")
    p.add_argument("--ausgabe", help="GITHUB_OUTPUT-Datei fuer den Workflow")
    a = p.parse_args()

    ausliefern, commits, begruendung = ermitteln()
    print(begruendung)
    for h, b in commits:
        print("  " + ("+" if ist_wartungscommit(b) else "!") + " " + h[:7] + " " + b)

    zeilen = ["ausliefern=" + ("ja" if ausliefern else "nein")]
    if a.bumpen and ausliefern:
        from datetime import datetime, timezone
        jetzt = datetime.now(timezone.utc)
        name, code = bumpen(len(commits), jetzt.year, jetzt.month)
        print("Gebumpt auf " + name + " (versionCode " + str(code) + ").")
        zeilen += ["version=" + name, "code=" + str(code), "anzahl=" + str(len(commits))]

    if a.ausgabe:
        with open(a.ausgabe, "a", encoding="utf-8") as f:
            f.write("\n".join(zeilen) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
