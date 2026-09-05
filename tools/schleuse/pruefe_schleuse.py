"""Schleuse: prueft mechanisch, bevor Arbeit den Branch verlaesst (PreToolUse-Hook).

WARUM ES DAS GIBT
-----------------
Alle Pruefskripte dieses Projekts existierten am 21.08.2026 bereits - sie hingen
nur an keinem Tor. Versionsbump, Changelog, Tests, Lint und Doku-Budget wurden
jedes Mal einzeln von Hand erledigt, und Handarbeit rutscht durch: die erste
1.30.2-APK meldete sich als 1.30.1, weil der Bump vergessen wurde, und ein
uebersehener Befund fiel erst beim Changelog-Schreiben auf - nach dem Merge.

Waehrend der Arbeit blockiert hier nichts. Erst wenn Arbeit den Branch verlaesst
(`git merge` / `git push`), wird geprueft, was mechanisch pruefbar ist.
Urteilsfragen stehen in `.claude/skills/`, nicht hier.

GESTAFFELTER UMFANG - UND WARUM
--------------------------------
    git merge (ueberall)          -> Pruefungen 1-9
    git push auf einem Feature-Branch -> Pruefungen 1-9
    git push auf main             -> Pruefungen 1-12

Der Skill `cfalarm-release-und-changelog` schreibt vor: erst mergen, DANN auf
`main` bumpen und den Changelog schreiben, dann pushen. Die Git-Historie belegt
es (d445975 Merge -> c22ebf8 Bump). Ein Tor, das Bump und Changelog schon beim
`git merge` verlangt, blockiert also den eigenen dokumentierten Ablauf. Deshalb
greifen die Pruefungen 10-12 ausschliesslich beim Push von `main`.

EIN UMGEBUNGSFEHLER IST KEIN FREISPRUCH
----------------------------------------
Laeuft der Testlauf gar nicht erst an, meldet die Schleuse "nicht
durchfuehrbar" und blockiert - sie meldet NICHT "gruen". Gradle ist in diesem
Projekt unbestaendig erreichbar (Memory `env_gradle_loopback`), und genau die
stille Degradierung auf "passt schon" ist das, wogegen dieses Skript antritt.

Damit das keine Sackgasse wird, gibt es einen Notausgang:

    CFALARM_SCHLEUSE=aus git push

Er ist fuer den Fall gedacht, dass die Schleuse SELBST kaputt ist - nicht dafuer,
einen roten Testlauf zu umgehen.

Rueckgabewert: 0 = durchlassen, 2 = blockieren (stderr geht an Claude zurueck).
"""
from __future__ import annotations

import glob
import io
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

WURZEL = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

INTEGRATIONSBRANCH = "main"
FERNREFERENZ = "origin/main"

GRADLE_DATEI = os.path.join(WURZEL, "app", "build.gradle.kts")
CHANGELOG = os.path.join(WURZEL, "CHANGELOG.md")
TEST_XML = os.path.join(WURZEL, "app", "build", "test-results", "testDebugUnitTest", "TEST-*.xml")

# Zeitgrenzen. Der Hook-Timeout in .claude/settings.json muss ueber der Summe
# liegen. Warm messen Tests und Lint zusammen 60-90 s; die Reserve ist fuer den
# kalten Daemon, der beim ersten Lauf einer Sitzung deutlich laenger braucht.
TIMEOUT_TESTS_S = 300
TIMEOUT_LINT_S = 180
TIMEOUT_KURZ_S = 60

# Wie viele Zeilen Werkzeugausgabe eine Fehlermeldung mitbekommt.
AUSGABEZEILEN = 15

# Commit-Betreffs, die KEINE nutzersichtbare Aenderung ankuendigen. Alles andere
# gilt als nutzersichtbar - im Zweifel lieber einen Changelog-Eintrag zu viel
# verlangen als einen zu wenig.
INTERNE_BETREFFS = re.compile(
    r"^(chore|refactor|test|docs|ci|style|build|perf)(\(.+\))?!?:|^merge\b",
    re.IGNORECASE,
)

NOTAUSGANG = "CFALARM_SCHLEUSE"


# ---- Werkzeug ---------------------------------------------------------------


def lauf(argumente, timeout=TIMEOUT_KURZ_S):
    """Fuehrt einen Befehl aus. Ein Fehlschlag ist Datenlage, keine Ausnahme.

    Gibt (rueckgabewert, ausgabe) zurueck; stdout und stderr sind
    zusammengefuehrt, damit die letzten Zeilen zitierbar sind. Ein Rueckgabewert
    von None heisst: der Befehl liess sich gar nicht ausfuehren.
    """
    try:
        ergebnis = subprocess.run(
            argumente,
            cwd=WURZEL,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
        )
    except subprocess.TimeoutExpired:
        return None, "Zeitgrenze von {} s ueberschritten.".format(timeout)
    except OSError as fehler:
        return None, str(fehler)
    return ergebnis.returncode, (ergebnis.stdout or "") + (ergebnis.stderr or "")


def git(*argumente):
    return lauf(("git",) + argumente)


def letzte_zeilen(text, anzahl=AUSGABEZEILEN):
    zeilen = [z for z in (text or "").strip().splitlines() if z.strip()]
    return "\n".join(zeilen[-anzahl:])


def python_skript(*argumente):
    return lauf((sys.executable,) + argumente, timeout=TIMEOUT_KURZ_S)


def gradle(*aufgaben, timeout):
    """Ruft den Gradle-Wrapper - unter Windows die .bat, sonst das Shellskript.

    `--offline` NUR lokal. Es steht dort, weil der Rechner des Eigentuemers einen
    gefuellten Gradle-Cache hat (gemessen 25.08.2026: 11 GB) und weil Gradles
    Netzzugriff hier unbestaendig ist (Memory `env_gradle_loopback`) - offline ist
    schneller UND verlaesslicher.

    Auf einem CI-Runner ist beides umgekehrt: der Container startet ohne Cache.
    `--offline` scheitert dort an der ersten fehlenden Abhaengigkeit, die Schleuse
    meldete "Testlauf NICHT DURCHFUEHRBAR" und blockierte jeden autonomen Lauf -
    fail-closed, also richtig gemeldet, aber aus dem falschen Grund. Deshalb faellt
    das Flag weg, sobald `CI` gesetzt ist (GitHub Actions setzt es auf "true").

    Wichtig: NICHT den Rueckgabewert von Gradle als Testurteil verwenden - das
    Urteil kommt weiterhin aus den JUnit-XML, siehe pruefe_tests().
    """
    wrapper = os.path.join(WURZEL, "gradlew.bat" if os.name == "nt" else "gradlew")
    if not os.path.exists(wrapper):
        return None, "Gradle-Wrapper nicht gefunden: {}".format(wrapper)
    flags = () if os.environ.get("CI") else ("--offline",)
    return lauf((wrapper,) + flags + aufgaben, timeout=timeout)


def lies(pfad):
    try:
        return io.open(pfad, encoding="utf-8", errors="replace").read()
    except OSError:
        return None


def versionen_aus(inhalt):
    """(versionName, versionCode) aus einem build.gradle.kts-Text.

    Der Anker auf versionName-mit-Anfuehrungszeichen ist tragend: weiter unten
    stehen `versionNameSuffix`-Zuweisungen, die ein laxeres Muster zuerst faengt.
    """
    if not inhalt:
        return None, None
    name = re.search(r'\bversionName\s*=\s*"([^"]+)"', inhalt)
    code = re.search(r"\bversionCode\s*=\s*(\d+)", inhalt)
    return (name.group(1) if name else None, int(code.group(1)) if code else None)


def changelog_eintraege(text):
    """Sammelt die Eintraege eines Changelogs als Multimenge.

    Bullet-Zeilen ab der ersten `##`-Ueberschrift; eingerueckte Fortsetzungs-
    zeilen werden an den offenen Eintrag angehaengt, Leerraum auf einfache
    Leerzeichen normalisiert. Als Liste (nicht als Menge) zurueckgegeben, damit
    ein woertlich wiederholter Eintrag weiter als ein neuer zaehlt.
    """
    if not text:
        return []
    eintraege = []
    begonnen = False
    for zeile in text.splitlines():
        if zeile.startswith("##"):
            begonnen = True
            continue
        if not begonnen:
            continue
        if re.match(r"^[-*]\s", zeile):
            eintraege.append(re.sub(r"\s+", " ", zeile.strip()))
        elif eintraege and re.match(r"^\s+\S", zeile):
            eintraege[-1] = re.sub(r"\s+", " ", eintraege[-1] + " " + zeile.strip())
    return eintraege


def neue_eintraege(vorher, nachher):
    """Multimengen-Differenz: was steht jetzt drin, das vorher nicht drinstand."""
    alt = list(changelog_eintraege(vorher))
    neu = []
    for eintrag in changelog_eintraege(nachher):
        if eintrag in alt:
            alt.remove(eintrag)
        else:
            neu.append(eintrag)
    return neu


# ---- Die Pruefungen ---------------------------------------------------------


def pruefe_geheimnisse(probleme):
    code, ausgabe = git(
        "ls-files", "--", "keystore.properties", "*.keystore", "*.jks", "..Projektdateien*"
    )
    if code == 0 and ausgabe.strip():
        dateien = ", ".join(ausgabe.split())
        probleme.append(
            "Diese Dateien sind von Git getrackt: {}.\n"
            "Sie enthalten Signaturschluessel oder interne Notizen und duerfen nicht ins "
            "oeffentliche Repository. Entfernen mit `git rm --cached <datei>`.".format(dateien)
        )


def pruefe_hilfsskript(probleme, pfad, titel, argumente=(), hinweis=""):
    """Ruft eines der bestehenden Pruefskripte. Die Schleuse ruft nur, sie prueft nicht selbst."""
    code, ausgabe = python_skript(os.path.join(WURZEL, pfad), *argumente)
    if code == 0:
        return
    if code is None:
        probleme.append("{} liess sich nicht ausfuehren:\n{}".format(pfad, letzte_zeilen(ausgabe)))
        return
    text = "{}:\n{}".format(titel, letzte_zeilen(ausgabe))
    if hinweis:
        text += "\n" + hinweis
    probleme.append(text)


def pruefe_changelog_seite(probleme):
    """Exit 1 = Seite veraltet, Exit 2 = Aufruffehler. Zwei Lagen, zwei Meldungen."""
    code, ausgabe = python_skript(
        os.path.join(WURZEL, "tools", "changelog", "build_changelog.py"), "--pruefen"
    )
    if code == 0:
        return
    if code == 1:
        probleme.append(
            "docs/changelog.html passt nicht mehr zu CHANGELOG.md.\n"
            "Generator laufen lassen: `python tools/changelog/build_changelog.py` - und beide "
            "Dateien zusammen committen."
        )
    else:
        probleme.append(
            "Der Changelog-Generator bricht ab (kein blosses Veralten der Seite):\n{}".format(
                letzte_zeilen(ausgabe)
            )
        )


def pruefe_tests(probleme):
    """Urteilt aus den JUnit-XML, nicht aus dem Rueckgabewert von Gradle.

    Ein gruener Exit-Code ist in diesem Projekt kein gruener Testlauf. Umgekehrt
    gilt: laeuft Gradle durch, sind die vorliegenden XML das gueltige Ergebnis
    fuer den aktuellen Quellstand - auch wenn die Task als UP-TO-DATE nichts
    neu geschrieben hat. Nur wenn Gradle scheitert, muss die Frische zaehlen,
    sonst verkauft ein abgestuerzter Lauf die Zahlen des Vorlaufs als Urteil.
    """
    begonnen = time.time()
    code, ausgabe = gradle("testDebugUnitTest", timeout=TIMEOUT_TESTS_S)

    dateien = glob.glob(TEST_XML)
    tests = schlecht = 0
    juengste = 0.0
    gelesen = 0
    for pfad in dateien:
        try:
            wurzel = ET.parse(pfad).getroot()
        except (ET.ParseError, OSError):
            continue
        gelesen += 1
        tests += int(wurzel.get("tests", 0))
        schlecht += int(wurzel.get("failures", 0)) + int(wurzel.get("errors", 0))
        try:
            juengste = max(juengste, os.path.getmtime(pfad))
        except OSError:
            pass

    if code == 0:
        if gelesen == 0:
            probleme.append(
                "Gradle meldet Erfolg, aber es liegt kein auswertbares Testergebnis vor "
                "(app/build/test-results/testDebugUnitTest/).\n"
                "Ein Rueckgabewert ohne Ergebnisdateien ist kein Testlauf."
            )
        elif schlecht:
            probleme.append(
                "{} von {} Tests schlagen fehl (aus den Ergebnisdateien gelesen, nicht aus dem "
                "Rueckgabewert).".format(schlecht, tests)
            )
        return

    # Gradle ist gescheitert. Frische XML mit roten Tests = echter Testfehler.
    frisch = gelesen > 0 and juengste >= begonnen - 1
    if frisch and schlecht:
        probleme.append(
            "{} von {} Tests schlagen fehl (aus den Ergebnisdateien gelesen, nicht aus dem "
            "Rueckgabewert).\n{}".format(schlecht, tests, letzte_zeilen(ausgabe))
        )
    else:
        probleme.append(
            "Testlauf NICHT DURCHFUEHRBAR - das ist kein gruener Lauf, sondern ein unbekannter.\n"
            "{}\n"
            "Erst die Ursache beheben. Ist die Schleuse selbst kaputt, steht der Notausgang im "
            "Kopf von tools/schleuse/pruefe_schleuse.py.".format(letzte_zeilen(ausgabe))
        )


def pruefe_androidtest_kompiliert(probleme):
    """`gradlew test` kompiliert den androidTest-Quelltext NIE - das ist ein blinder Fleck.

    Verifiziert am 25.08.2026: Die Unit-Test-Task uebersetzt ausschliesslich `src/test`. Ein
    Instrumentierungstest kann also monatelang nicht mehr uebersetzbar sein, ohne dass irgendetwas
    meldet - er faellt erst auf, wenn jemand ein Geraet anschliesst und
    `connectedDebugAndroidTest` startet. Genau dann braucht man ihn aber und hat keine Zeit dafuer.

    Geprueft wird nur das KOMPILIEREN, nicht das Ausfuehren: Ausfuehren braucht ein Geraet, und in
    diesem Projekt deinstalliert die Task hinterher die App (ein eingerichteter Emulator-Zustand
    waere weg). Das Uebersetzen braucht nichts davon.
    """
    code, ausgabe = gradle("compileDebugAndroidTestKotlin", timeout=TIMEOUT_TESTS_S)
    if code == 0:
        return
    probleme.append(
        "Der androidTest-Quelltext kompiliert nicht:\n{}\n"
        "`gradlew test` faellt darueber NICHT - es uebersetzt nur src/test.".format(
            letzte_zeilen(ausgabe)
        )
    )


def pruefe_lint(probleme):
    code, ausgabe = gradle("lintDebug", timeout=TIMEOUT_LINT_S)
    if code == 0:
        return
    if code is None:
        probleme.append(
            "Lint NICHT DURCHFUEHRBAR - das ist kein sauberer Lauf, sondern ein unbekannter.\n"
            "{}".format(letzte_zeilen(ausgabe))
        )
        return
    probleme.append("`gradlew lintDebug` schlaegt fehl:\n{}".format(letzte_zeilen(ausgabe)))


def pruefe_release_pflichten(probleme, basis):
    """Pruefungen 10-12: Changelog-Eintrag, Versionsbump, versionCode gegen origin/main.

    Nur beim Push von `main` - siehe GESTAFFELTER UMFANG im Kopf.
    """
    code, ausgabe = git("log", "{}..HEAD".format(basis), "--format=%s")
    betreffs = [z for z in (ausgabe or "").splitlines() if z.strip()] if code == 0 else []
    nutzersichtbar = bool(betreffs) and not all(INTERNE_BETREFFS.match(b) for b in betreffs)

    jetzt_name, jetzt_code = versionen_aus(lies(GRADLE_DATEI))

    if nutzersichtbar:
        # 8. Changelog-Eintrag. Gefragt ist nicht "steht etwas ganz oben", sondern
        # "kam gegenueber der Basisfassung ein Eintrag hinzu" - sonst blockiert
        # das blosse Umbenennen der obersten Version den eigenen Push.
        _, basis_changelog = git("show", "{}:CHANGELOG.md".format(basis))
        if not neue_eintraege(basis_changelog, lies(CHANGELOG)):
            probleme.append(
                "Der Stand enthaelt nutzersichtbare Aenderungen (feat/fix), aber in CHANGELOG.md "
                "steht dazu kein neuer Eintrag.\n"
                "Eintrag in Nutzersprache ergaenzen (Vorlage im Skill "
                "`cfalarm-release-und-changelog`), danach den Generator laufen lassen. Oder, wenn "
                "wirklich nichts sichtbar ist, die Commits als chore/refactor kennzeichnen."
            )

        # 9. Versionsbump gegen die Basisfassung.
        code_basis, basis_gradle = git("show", "{}:app/build.gradle.kts".format(basis))
        if code_basis == 0:
            basis_name, basis_code = versionen_aus(basis_gradle)
            if basis_name == jetzt_name and basis_code == jetzt_code:
                probleme.append(
                    "Version steht unveraendert auf {} / versionCode {}, obwohl der Stand "
                    "nutzersichtbare Aenderungen enthaelt.\n"
                    "Patch fuer Fixes, Minor fuer Features - in app/build.gradle.kts.".format(
                        jetzt_name, jetzt_code
                    )
                )

    # 10. versionCode nicht NIEDRIGER als der auf origin/main. Mehrere Sessions
    # (lokal und Cloud) vergeben unabhaengig Nummern; hat eine andere bereits
    # hoeher gezaehlt, waere der eigene Stand ein Rueckschritt.
    #
    # Bewusst nur "<", nicht "<=": Gleichstand ist der Normalfall eines reinen
    # chore/docs-Pushes, bei dem gar kein Bump vorgesehen ist. Und Gleichstand
    # TROTZ nutzersichtbarer Aenderung faengt bereits Pruefung 11 - deren Basis
    # ist auf `main` genau dieses origin/main.
    code_fern, fern_gradle = git("show", "{}:app/build.gradle.kts".format(FERNREFERENZ))
    if code_fern == 0 and jetzt_code is not None:
        _, fern_code = versionen_aus(fern_gradle)
        if fern_code is not None and jetzt_code < fern_code:
            probleme.append(
                "versionCode {} ist NIEDRIGER als der auf {} ({}) - eine andere Session hat "
                "bereits weiter gezaehlt.\n"
                "Zuerst `git fetch`, dann auf mindestens {} bumpen.".format(
                    jetzt_code, FERNREFERENZ, fern_code, fern_code + 1
                )
            )


# ---- Ablauf -----------------------------------------------------------------


HANDBETRIEB = object()

HANDBETRIEB_HINWEIS = """Die Schleuse ist ein PreToolUse-Hook, kein Kommandozeilenwerkzeug.
Sie liest den zu beurteilenden Befehl als Hook-JSON von stdin - haengt stdin an
einem Terminal, wartet sie ewig auf ein Dateiende. NICHTS wurde geprueft.

Von Hand pruefen, so wie der Hook es tut:

    echo '{"tool_input":{"command":"git push"}}' | python tools/schleuse/pruefe_schleuse.py

PowerShell:

    '{"tool_input":{"command":"git push"}}' | python tools/schleuse/pruefe_schleuse.py
"""


def befehl_aus_stdin():
    """Liest das Hook-JSON. Alles Unerwartete laesst durch - fail-open.

    Anders als in der Node-Vorlage liefert Python bei leerem stdin einen leeren
    String statt zu werfen; der Fall wird deshalb ausdruecklich abgefangen.

    HAENGT STDIN AN EINEM TERMINAL, ist der Aufrufer ein Mensch und kein Hook -
    dann NICHT lesen. Am 05.09.2026 hat das dreimal je 25 Minuten gekostet:
    `sys.stdin.read()` wartet auf ein Dateiende, das per Hand nie kommt, und der
    Prozess sieht dabei aus wie ein haengender Build (null CPU, kein Gradle, kein
    Kindprozess). Schlimmer als das Haengen ist aber der Ausweg, der sich anbietet:
    wer mit `< NUL` nachhilft, faellt in den fail-open-Zweig unten und bekommt
    stillschweigend Exit 0 - einen Freispruch, ohne dass EINE Pruefung lief.
    Fail-open ist richtig, solange ein Hook ruft (sonst sperrt ein Defekt hier
    jede Arbeit aus); gegenueber einem Menschen ist es eine Luege.
    """
    if sys.stdin is None or sys.stdin.isatty():
        return HANDBETRIEB
    try:
        eingabe = sys.stdin.read()
    except (OSError, ValueError):
        return None
    if not eingabe.strip():
        return None
    try:
        return (json.loads(eingabe).get("tool_input") or {}).get("command") or ""
    except (ValueError, AttributeError):
        return None


def soll_pruefen(befehl):
    """Loest dieser Befehl die Schleuse aus?

    Zwei Ausnahmen, beide teuer erkauft:

    `git merge-base` ist ein reiner LESEBEFEHL und darf nichts ausloesen. Der
    alte Ausdruck `\\bgit\\s+(merge|push)\\b` traf ihn trotzdem - zwischen `merge`
    und dem Bindestrich steht eine Wortgrenze. Am 05.09.2026 hat das mitten in
    einer Fehlersuche einen Zehn-Minuten-Gradle-Lauf ausgeloest, und die Schleuse
    ruft `git merge-base` sogar selbst auf. `(?![\\w-])` verlangt jetzt, dass nach
    `merge`/`push` weder ein Wortzeichen noch ein Bindestrich folgt.

    Die RETTUNGSBEFEHLE eines offenen Merge (`--abort`, `--continue`, `--quit`)
    duerfen NIE blockiert werden. Waehrend eines Konflikts stehen Konfliktmarker
    im Baum; jede Pruefung, die den Baum liest, schlaegt dann an - und
    ausgerechnet die Befehle, mit denen man da wieder herauskommt, waeren gesperrt.
    Das Repo sperrt sich selbst ein. Die Leitplanke dazu steht seit Runde 15 im
    Skill `cfalarm-altlasten-abtragen`; behoben war dort nur die Doppelmeldung,
    nicht die Blockade. Was nach `--continue` entsteht, prueft ohnehin der
    anschliessende Push.
    """
    if not re.search(r"\bgit\s+(merge|push)(?![\w-])", befehl):
        return False
    if "--dry-run" in befehl:
        return False
    if re.search(r"\bgit\s+merge\s+(--abort|--continue|--quit)\b", befehl):
        return False
    return True


def main():
    # DER NOTAUSGANG ZUERST, vor allem anderen. Er stand bis zum 05.09.2026 hinter
    # dem stdin-Lesen - und damit hinter genau der Stelle, an der sich die Schleuse
    # aufhaengen oder (neu) mit Fehlercode 2 blockieren kann. Ein Notausgang, den
    # man nur erreicht, wenn ohnehin alles funktioniert, ist keiner.
    if os.environ.get(NOTAUSGANG, "").strip().lower() == "aus":
        return 0

    befehl = befehl_aus_stdin()
    if befehl is HANDBETRIEB:
        sys.stderr.write(HANDBETRIEB_HINWEIS)
        return 2
    if befehl is None:
        return 0

    if not soll_pruefen(befehl):
        return 0

    ist_push = bool(re.search(r"\bgit\s+push\b", befehl))
    code, branch = git("rev-parse", "--abbrev-ref", "HEAD")
    branch = (branch or "").strip() if code == 0 else ""

    probleme = []

    pruefe_geheimnisse(probleme)
    pruefe_hilfsskript(
        probleme,
        os.path.join("tools", "skills", "pruefe_skills.py"),
        "Skill-Frontmatter verletzt die Vorgaben",
        argumente=(os.path.join(WURZEL, ".claude", "skills"),),
        hinweis="Ein unlesbares Frontmatter laesst den Skill praktisch nicht mehr triggern.",
    )
    pruefe_hilfsskript(
        probleme,
        os.path.join("tools", "doku", "pruefe_budget.py"),
        "Das Doku-Budget ist gerissen",
        argumente=("--ci",),
        hinweis="Inhalte in einen Skill verschieben - nicht die Schwelle anheben.",
    )
    pruefe_changelog_seite(probleme)
    pruefe_hilfsskript(
        probleme,
        os.path.join("tools", "invarianten", "pruefe_code.py"),
        "Eine Code-Invariante ist gerissen (Wecker-Killer)",
    )
    # Der Doku-Teil dieser Pruefung braucht eine Basis: entfernt wurde ein Symbol IN DIESER
    # Aenderung, nicht irgendwann. Ohne Basis laufen nur die beiden mechanischen Teile.
    code_mb, mb = git("merge-base", "HEAD", FERNREFERENZ)
    reste_argumente = ["--ci"]
    if code_mb == 0 and mb.strip():
        reste_argumente += ["--basis", mb.strip()]
    pruefe_hilfsskript(
        probleme,
        os.path.join("tools", "aufraeumen", "pruefe_reste.py"),
        "Der Aufraeumdurchgang hat Reste hinterlassen",
        argumente=tuple(reste_argumente),
        hinweis="Tote Importe und verwaiste Texte loeschen; eine Doku-Notiz, die auf ein "
                "entferntes Symbol zeigt, korrigieren statt danebenschreiben.",
    )
    pruefe_tests(probleme)
    pruefe_androidtest_kompiliert(probleme)
    pruefe_lint(probleme)

    # Pruefungen 10-12 nur beim Push von main: Bump und Changelog gehoeren laut
    # Release-Skill auf main NACH dem Merge.
    if ist_push and branch == INTEGRATIONSBRANCH:
        code_basis, basis = git("merge-base", "HEAD", FERNREFERENZ)
        if code_basis == 0 and basis.strip():
            pruefe_release_pflichten(probleme, basis.strip())

    if not probleme:
        return 0

    sys.stderr.write(
        "Schleuse: {} Punkt(e) offen, bevor diese Arbeit den Branch verlaesst.\n\n".format(
            len(probleme)
        )
        + "\n\n".join("{}. {}".format(i + 1, p) for i, p in enumerate(probleme))
        + "\n\nDas ist eine mechanische Pruefung, keine Meinung. "
        "Punkte beheben und den Befehl erneut ausfuehren.\n"
    )
    return 2


if __name__ == "__main__":
    sys.exit(main())
