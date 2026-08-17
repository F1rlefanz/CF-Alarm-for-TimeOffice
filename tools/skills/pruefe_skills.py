"""Prueft jede SKILL.md gegen Anthropics harte Vorgaben. Exit 1 bei jedem Verstoss.

Gedacht als Wiederhol-Pruefung: nach jeder Aenderung an einem Skill laufen lassen.
Der wichtigste Fall, den es faengt: ein unquotierter YAML-Skalar bricht am ersten
': ' - der Parser wirft, und die Oberflaeche zeigt dann die H1-Ueberschrift statt
der Beschreibung. Der Skill existiert dann, triggert aber praktisch nicht mehr.

Zwei Betriebsarten:

    python tools/skills/pruefe_skills.py .claude/skills
        Normale Pruefung. Exit 0 = sauber, Exit 1 = Verstoesse (auf stdout aufgelistet).
        So laeuft es in der CI.

    python tools/skills/pruefe_skills.py --hook
        Fuer den PostToolUse-Hook. Liest die Hook-JSON von stdin, prueft NUR wenn die
        angefasste Datei eine SKILL.md unter .claude/skills/ ist, und gibt bei einem
        Verstoss Hook-JSON zurueck (decision=block), damit die Meldung bei Claude
        landet statt still zu verpuffen. Immer Exit 0 - das Urteil steht in der JSON.
"""
import glob
import io
import os
import re
import sys

try:
    import yaml                       # bevorzugt: echter Parser, urteilt wie die Harness
    HAT_YAML = True
except ImportError:                   # PyYAML ist auf CI-Runnern nicht garantiert
    HAT_YAML = False


class MiniYamlFehler(Exception):
    pass


def lade_frontmatter(block):
    """Minimalparser fuer flache 'key: wert'-Bloecke, wenn PyYAML fehlt.

    Er MUSS denselben Fehler werfen wie ein echter Parser, wenn ein unquotierter
    Skalar ein weiteres ': ' enthaelt - genau dieser Fall ist der Grund fuer dieses
    Skript. Ein Fallback, der stillschweigend durchlaesst, waere schlimmer als keiner.
    """
    if HAT_YAML:
        try:
            return yaml.safe_load(block)
        except yaml.YAMLError as e:
            raise MiniYamlFehler(str(e).splitlines()[0])
    daten = {}
    for zeile in block.splitlines():
        if not zeile.strip() or zeile.lstrip().startswith("#"):
            continue
        if zeile.startswith((" ", "\t")):
            continue                   # verschachtelt: fuer unsere Pruefungen unerheblich
        if ": " not in zeile and not zeile.rstrip().endswith(":"):
            raise MiniYamlFehler(f"keine Zuordnung: {zeile[:40]!r}")
        schluessel, _, wert = zeile.partition(":")
        wert = wert.strip()
        if wert.startswith('"') and wert.endswith('"') and len(wert) > 1:
            wert = wert[1:-1].replace('\\"', '"').replace("\\\\", "\\")
        elif wert.startswith("'") and wert.endswith("'") and len(wert) > 1:
            wert = wert[1:-1].replace("''", "'")
        elif ": " in wert:
            raise MiniYamlFehler(
                f"mapping values are not allowed here (unquotiertes ': ' in {schluessel.strip()})")
        daten[schluessel.strip()] = wert
    return daten


MAX_NAME = 64
MAX_DESC = 1024
MAX_SKILL_ZEILEN = 500      # Anthropic: SKILL.md-Rumpf unter 500 Zeilen
MIN_DESC = 120              # eigene Untergrenze: darunter ist es kein Trigger, sondern ein Titel
TOC_AB_ZEILEN = 100         # Anthropic: Referenzdatei > 100 Zeilen braucht ein Inhaltsverzeichnis
RESERVIERT = ("claude", "anthropic")

def pruefe(verzeichnis):
    """Gibt die Liste der Verstoesse zurueck (leer = alles in Ordnung)."""
    fehler = []

    def meld(pfad, text):
        fehler.append(f"{pfad}: {text}")

    for pfad in sorted(glob.glob(os.path.join(verzeichnis, "*", "SKILL.md"))):
        kurz = os.path.basename(os.path.dirname(pfad))
        text = io.open(pfad, encoding="utf-8").read()

        teile = text.split("---")
        if len(teile) < 3 or teile[0].strip():
            meld(kurz, "kein Frontmatter am Dateianfang")
            continue
        try:
            fm = lade_frontmatter(teile[1])
        except MiniYamlFehler as e:
            meld(kurz, f"Frontmatter ist kein gueltiges YAML ({e}) "
                       "- vermutlich ein unquotiertes ': ' in der description. "
                       'Abhilfe: den Wert in doppelte Anfuehrungszeichen setzen.')
            continue
        if not isinstance(fm, dict):
            meld(kurz, "Frontmatter ist keine Zuordnung")
            continue

        name = str(fm.get("name", ""))
        desc = str(fm.get("description", "") or "")

        if not name:
            meld(kurz, "name fehlt")
        else:
            if len(name) > MAX_NAME:
                meld(kurz, f"name zu lang ({len(name)} > {MAX_NAME})")
            if not re.fullmatch(r"[a-z0-9-]+", name):
                meld(kurz, f"name enthaelt unerlaubte Zeichen: {name!r}")
            for w in RESERVIERT:
                if w in name.lower():
                    meld(kurz, f"name enthaelt das reservierte Wort {w!r}")
            if name != kurz:
                meld(kurz, f"name {name!r} weicht vom Verzeichnisnamen ab")

        if not desc:
            meld(kurz, "description fehlt oder ist leer")
        else:
            if len(desc) > MAX_DESC:
                meld(kurz, f"description zu lang ({len(desc)} > {MAX_DESC})")
            if len(desc) < MIN_DESC:
                meld(kurz, f"description zu kurz ({len(desc)} < {MIN_DESC}) - liest sich als Titel, "
                           "nicht als Trigger; sie muss WAS und WANN nennen")
            for ich in (" ich ", "Ich ", " du ", "Du ", " dir ", " dich "):
                if ich in f" {desc} ":
                    meld(kurz, f"description nicht in dritter Person (enthaelt {ich.strip()!r})")
                    break

        zeilen = text.count("\n") + 1
        if zeilen >= MAX_SKILL_ZEILEN:
            meld(kurz, f"SKILL.md zu lang ({zeilen} >= {MAX_SKILL_ZEILEN} Zeilen) - in reference/ aufteilen")

        # Verweise genau eine Ebene tief, und jede genannte Referenzdatei muss existieren
        for ref in re.findall(r"`(reference/[^`]+\.md)`", text):
            if not os.path.exists(os.path.join(os.path.dirname(pfad), ref)):
                meld(kurz, f"verweist auf nicht existierende Datei {ref}")

        # Querverweise auf ANDERE Skills muessen einen existierenden Skill nennen. Beim Umbau
        # am 17.08.2026 zeigten mehrere Verweise auf Dateinamen der aufgeloesten Zwischenablage
        # (".claude/invarianten/alarme.md") bzw. per "siehe oben" ueber eine Dateigrenze hinweg -
        # alle von Hand gefunden. Das soll kein zweites Mal von Hand passieren muessen.
        vorhandene = {os.path.basename(os.path.dirname(p))
                      for p in glob.glob(os.path.join(os.path.dirname(os.path.dirname(pfad)),
                                                      "*", "SKILL.md"))}
        # SKILL.md UND alle reference/-Dateien: die Verweise, die beim Umbau tot waren,
        # standen ueberwiegend in den reference/-Dateien, nicht in der SKILL.md.
        zu_pruefen = [(kurz, text)] + [
            (f"{kurz}/reference/{os.path.basename(r)}", io.open(r, encoding="utf-8").read())
            for r in sorted(glob.glob(os.path.join(os.path.dirname(pfad), "reference", "*.md")))]
        for ort, inhalt in zu_pruefen:
            for genannt in re.findall(r"Skill\s+`([a-z0-9-]+)`", inhalt):
                if genannt not in vorhandene:
                    meld(ort, f"verweist auf Skill `{genannt}`, den es nicht gibt "
                              f"({len(vorhandene)} Skills liegen unter .claude/skills/)")

        for ref in sorted(glob.glob(os.path.join(os.path.dirname(pfad), "reference", "*.md"))):
            rtext = io.open(ref, encoding="utf-8").read()
            rzeilen = rtext.count("\n") + 1
            rkurz = f"{kurz}/reference/{os.path.basename(ref)}"
            if rzeilen > TOC_AB_ZEILEN and "## Inhalt" not in rtext:
                meld(rkurz, f"{rzeilen} Zeilen, aber kein '## Inhalt' - Anthropic verlangt ein "
                            f"Inhaltsverzeichnis ab {TOC_AB_ZEILEN} Zeilen")
            if "\\" in "".join(re.findall(r"`([^`]*\.md)`", rtext)):
                meld(rkurz, "Windows-Backslash in einem Pfad - Anthropic verlangt Vorwaerts-Slashes")

    return fehler


def projektwurzel():
    """Die Wurzel aus dem SKRIPTPFAD ableiten, nicht aus dem Arbeitsverzeichnis.

    Der PostToolUse-Hook laeuft im Verzeichnis der bearbeiteten Datei, nicht in der
    Projektwurzel - ein relativer Pfad scheitert dort. Am 17.08.2026 genau so passiert,
    beim ersten Edit einer .kt-Datei tief im Baum.
    """
    return os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


SKILL_VERZEICHNIS = os.path.join(projektwurzel(), ".claude", "skills")


def als_hook():
    """PostToolUse-Modus: liest die Hook-JSON von stdin, urteilt per JSON auf stdout."""
    import json

    try:
        eingabe = json.loads(sys.stdin.read() or "{}")
    except (json.JSONDecodeError, ValueError):
        return 0                       # kein verwertbares stdin: nichts zu tun, still aussteigen

    ti = eingabe.get("tool_input") or {}
    tr = eingabe.get("tool_response") or {}
    pfad = str(ti.get("file_path") or tr.get("filePath") or "")
    # Nur reagieren, wenn wirklich eine SKILL.md unter .claude/skills/ angefasst wurde.
    # Beide Trennzeichen, weil der Pfad unter Windows mit Backslashes ankommt.
    normalisiert = pfad.replace("\\", "/")
    if not re.search(r"\.claude/skills/[^/]+/SKILL\.md$", normalisiert):
        return 0

    fehler = pruefe(SKILL_VERZEICHNIS)
    if not fehler:
        return 0

    text = ("Die Skill-Pruefung schlaegt fehl - so wuerde der Skill nicht (richtig) geladen:\n"
            + "\n".join(f"  - {f}" for f in fehler)
            + "\n\nBitte beheben, bevor weitergearbeitet wird. Haeufigste Ursache: ein "
              "unquotiertes ': ' in der description - dann bricht das YAML, und die "
              "Oberflaeche zeigt die H1-Ueberschrift statt der Beschreibung.")
    print(json.dumps({
        "decision": "block",
        "reason": text,
        "systemMessage": f"Skill-Pruefung: {len(fehler)} Verstoss/Verstoesse in {SKILL_VERZEICHNIS}",
    }, ensure_ascii=False))
    return 0


def main():
    if "--hook" in sys.argv:
        return als_hook()
    verzeichnis = next((a for a in sys.argv[1:] if not a.startswith("-")), ".")
    fehler = pruefe(verzeichnis)
    anzahl = len(glob.glob(os.path.join(verzeichnis, "*", "SKILL.md")))
    if anzahl == 0:
        print(f"Kein Skill unter {verzeichnis!r} gefunden - Pfad falsch?")
        return 1
    if fehler:
        print(f"{len(fehler)} Verstoss/Verstoesse in {anzahl} Skills:\n")
        for f in fehler:
            print(f"  - {f}")
        return 1
    print(f"{anzahl} Skills geprueft - alle Vorgaben erfuellt.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
