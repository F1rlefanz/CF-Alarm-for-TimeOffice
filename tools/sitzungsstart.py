"""Leitet den Projektstand aus den echten Quellen ab und gibt ihn beim Sessionstart aus.

WARUM ES DAS GIBT
-----------------
Der Projektstand stand bis zum 21.08.2026 von Hand in
`..Projektdateien/claudes mds/HANDOFF.md`. Diese Datei war auf 26.201 Zeichen
gewachsen, und an einem einzigen Tag wurde dreimal von Hand daran geschrieben -
nur damit sie den Stand widerspiegelt, den Git ohnehin kennt. Ein von Hand
gepflegter Stand veraltet zudem lautlos: niemand merkt, dass er falsch ist.

Was hier ausgegeben wird, stammt deshalb ausschliesslich aus abgeleiteten
Quellen - Git, `app/build.gradle.kts`, die Testergebnisse. Damit KANN der Stand
nicht veralten, ohne dass sich tatsaechlich etwas geaendert hat. Die Handarbeit
beschraenkt sich seither auf das, was sich nicht ableiten laesst: die offenen
Punkte, und die liegen im Memory `project_offene_punkte`.

DIE TESTZAHL IST EIN MESSWERT MIT VERFALLSDATUM
-----------------------------------------------
Sie wird IMMER mit ihrem Alter ausgegeben ("Stand: vor 2 Stunden"). Ohne diese
Angabe waere sie genau die Sorte Behauptung, gegen die dieses Skript antritt -
eine gruene Zahl aus einem Lauf, der drei Tage und zwanzig Commits alt ist.
Fehlen die Dateien, steht dort "noch kein Testlauf", nie eine alte Zahl.

WAS HIER NICHT HINEINGEHOERT
-----------------------------
Kein Netzzugriff. Insbesondere KEIN `git fetch` - der Hook laeuft bei jedem
Sessionstart, und ein haengender Netzaufruf haette den Start blockiert. Der
Abstand zu `origin/main` wird gegen den zuletzt geholten Stand gemessen; steht
dort eine Zahl, ist ohnehin ein `git fetch` von Hand faellig.

Ausgabe: Markdown auf stdout. Rueckgabewert immer 0 - faellt eine Quelle aus,
fehlt nur ihr Abschnitt.
"""
from __future__ import annotations

import glob
import io
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

# Die Wurzel kommt aus dem Dateipfad, nicht aus dem Arbeitsverzeichnis: der Hook
# laeuft nicht zwingend im Projektwurzelverzeichnis.
WURZEL = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

GRADLE_DATEI = os.path.join(WURZEL, "app", "build.gradle.kts")
TEST_XML = os.path.join(WURZEL, "app", "build", "test-results", "testDebugUnitTest", "TEST-*.xml")

# Die offenen Punkte liegen im Memory, nicht im Repo: das Repo ist oeffentlich
# (GitHub Pages liefert daraus die Datenschutz-URL), und unbelegte
# Fehlerhypothesen ueber eine Wecker-App gehoeren nicht dorthin. Bis zum
# 21.08.2026 stand die Liste in `..Projektdateien/claudes mds/HANDOFF.md`; die
# Datei ist geloescht, weil ihr uebriger Inhalt entweder abgeleitet werden kann
# oder anderswo vollstaendig steht.
MEMORY_DATEI = "project_offene_punkte.md"

INTEGRATIONSBRANCH = "main"

# Wie viele offene Punkte der Abschnitt hoechstens auflistet. Mehr waere keine
# Uebersicht mehr, sondern eine zweite Kopie der Datei.
MAX_OFFENE_PUNKTE = 15


def git(*argumente, standard=None):
    """Fuehrt einen git-Befehl aus. Scheitert er, ist das Ergebnis `standard`."""
    try:
        ergebnis = subprocess.run(
            ("git",) + argumente,
            cwd=WURZEL,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
    except OSError:
        return standard
    if ergebnis.returncode != 0:
        return standard
    return ergebnis.stdout.strip()


def lies(pfad):
    try:
        return io.open(pfad, encoding="utf-8", errors="replace").read()
    except OSError:
        return None


# ---- Die einzelnen Abschnitte ----------------------------------------------


def version():
    """Liest versionName/versionCode aus app/build.gradle.kts.

    Der Anker auf versionName-mit-Anfuehrungszeichen ist tragend: weiter unten
    stehen `versionNameSuffix`-Zuweisungen fuer die Debug- und Staging-Variante,
    die ein laxeres Muster zuerst faengt.
    """
    inhalt = lies(GRADLE_DATEI)
    if inhalt is None:
        return None
    name = re.search(r'\bversionName\s*=\s*"([^"]+)"', inhalt)
    code = re.search(r"\bversionCode\s*=\s*(\d+)", inhalt)
    if not name or not code:
        return None
    return "Version: {} / versionCode {}".format(name.group(1), code.group(1))


def alter_in_worten(sekunden):
    if sekunden < 90:
        return "gerade eben"
    minuten = int(sekunden // 60)
    if minuten < 90:
        return "vor {} Minute{}".format(minuten, "" if minuten == 1 else "n")
    stunden = int(sekunden // 3600)
    if stunden < 36:
        return "vor {} Stunde{}".format(stunden, "" if stunden == 1 else "n")
    tage = int(sekunden // 86400)
    return "vor {} Tag{}".format(tage, "" if tage == 1 else "en")


def testergebnis():
    """Summiert die JUnit-XML des letzten Laufs - mit Altersangabe.

    Die Zahlen kommen aus den `testsuite`-Attributen, nicht aus einem
    Gradle-Rueckgabewert: ein gruener Exit-Code ist in diesem Projekt kein
    gruener Testlauf.
    """
    dateien = glob.glob(TEST_XML)
    if not dateien:
        return "Tests: noch kein Testlauf in diesem Arbeitsbaum"

    tests = fehlschlaege = fehler = uebersprungen = 0
    juengste = 0.0
    gelesen = 0
    for pfad in dateien:
        try:
            wurzel = ET.parse(pfad).getroot()
        except (ET.ParseError, OSError):
            continue  # eine kaputte Datei macht den ganzen Lauf nicht wertlos
        gelesen += 1
        tests += int(wurzel.get("tests", 0))
        fehlschlaege += int(wurzel.get("failures", 0))
        fehler += int(wurzel.get("errors", 0))
        uebersprungen += int(wurzel.get("skipped", 0))
        try:
            juengste = max(juengste, os.path.getmtime(pfad))
        except OSError:
            pass

    if gelesen == 0:
        return "Tests: Ergebnisdateien vorhanden, aber keine lesbar"

    schlecht = fehlschlaege + fehler
    stand = alter_in_worten(time.time() - juengste) if juengste else "Alter unbekannt"
    zeile = "Tests: {} gruen, {} Fehler".format(tests - schlecht - uebersprungen, schlecht)
    if uebersprungen:
        zeile += ", {} uebersprungen".format(uebersprungen)
    return "{} (Stand: {})".format(zeile, stand)


def abstand_zu_origin():
    """Abstand zum zuletzt GEHOLTEN Stand von origin/main - ohne eigenes fetch."""
    zaehlung = git("rev-list", "--left-right", "--count", "origin/main...HEAD")
    if not zaehlung:
        return None
    teile = zaehlung.split()
    if len(teile) != 2:
        return None
    hinterher, voraus = int(teile[0]), int(teile[1])
    if hinterher == 0 and voraus == 0:
        return "Gegen origin/main: gleichauf"
    stuecke = []
    if hinterher:
        stuecke.append("{} Commit(s) hinterher - vor dem Bumpen fetchen".format(hinterher))
    if voraus:
        stuecke.append("{} Commit(s) voraus".format(voraus))
    return "Gegen origin/main: " + ", ".join(stuecke)


def arbeitsstand():
    branch = git("rev-parse", "--abbrev-ref", "HEAD")
    if not branch:
        return None  # kein Git-Repo: der ganze Abschnitt entfaellt

    zeilen = []
    if branch == INTEGRATIONSBRANCH:
        zeilen.append(
            "Branch: **{}** (Integrationsbranch - fuer Aenderungen erst abzweigen)".format(branch)
        )
    else:
        zeilen.append("Branch: **{}**".format(branch))

    schmutzig = git("status", "--porcelain", standard="")
    if schmutzig:
        anzahl = len(schmutzig.splitlines())
        zeilen.append("Arbeitsbaum: {} geaenderte Datei(en), nicht committet".format(anzahl))
    else:
        zeilen.append("Arbeitsbaum: sauber")

    for eintrag in (version(), testergebnis(), abstand_zu_origin()):
        if eintrag:
            zeilen.append(eintrag)

    commits = git("log", "--oneline", "-3")
    if commits:
        zeilen.append("")
        zeilen.append("Letzte Commits:")
        zeilen.extend("- " + z for z in commits.splitlines())

    return "\n".join(zeilen)


def memory_pfad(dateiname):
    """Findet ~/.claude/projects/<bereinigte-wurzel>/memory/<dateiname>.

    Claude Code ersetzt im Verzeichnisnamen die Pfadtrenner und den
    Laufwerks-Doppelpunkt durch Bindestriche. Der Pfad wird ABGELEITET und
    nicht festgeschrieben: ein fest eingetragener Pfad broeche bei einem
    Umzug des Projekts oder einem anderen Rechner, und der Abschnitt
    verschwaende dann STILL - genau die Degradierung, gegen die dieses Skript
    antritt. Passt die Ableitung nicht, wird ueber den Projektordner-Namen
    gesucht. (Dieselbe Ableitung wie in tools/doku/pruefe_budget.py.)
    """
    basis = os.path.expanduser("~/.claude/projects")
    abgeleitet = re.sub(r"[:\\/]", "-", WURZEL)
    kandidat = os.path.join(basis, abgeleitet, "memory", dateiname)
    if os.path.exists(kandidat):
        return kandidat
    ordner = os.path.basename(WURZEL)
    treffer = [
        p
        for p in glob.glob(os.path.join(basis, "*", "memory", dateiname))
        if p.replace("\\", "/").split("/")[-3].endswith(ordner)
    ]
    return treffer[0] if len(treffer) == 1 else None


def offene_punkte():
    """Zieht die Ueberschriften der offenen Punkte aus dem Memory.

    Bewusst nur die Titel, nicht der Text: der Abschnitt ist ein Wegweiser in
    die Datei, keine zweite Kopie davon. Die Datei selbst liegt ohnehin im
    Memory und ist damit bei Bedarf greifbar.
    """
    pfad = memory_pfad(MEMORY_DATEI)
    inhalt = lies(pfad) if pfad else None
    if inhalt is None:
        return None

    titel = [
        "- " + re.sub(r"^##\s+", "", z).strip()
        for z in inhalt.splitlines()
        if re.match(r"^##\s", z)
    ]
    if not titel:
        return None

    gekuerzt = titel[:MAX_OFFENE_PUNKTE]
    text = "\n".join(gekuerzt)
    if len(titel) > len(gekuerzt):
        text += "\n- ... und {} weitere".format(len(titel) - len(gekuerzt))
    return text + "\n\nEinzelheiten: Memory `project_offene_punkte`"


def main():
    # Der eigene Text ist ASCII, die Titel der offenen Punkte sind es NICHT
    # (echte Umlaute, Gedankenstriche). Ohne diese Zeile zerlegt die
    # Windows-Konsole sie in Fragezeichen, und der Abschnitt wird unlesbar.
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, OSError, ValueError):
        pass

    bloecke = []
    for titel, inhalt in (
        ("Arbeitsstand", arbeitsstand()),
        ("Offen", offene_punkte()),
    ):
        if inhalt:
            bloecke.append("## {}\n{}".format(titel, inhalt))

    if bloecke:
        print("\n\n".join(bloecke))
    return 0


if __name__ == "__main__":
    sys.exit(main())
