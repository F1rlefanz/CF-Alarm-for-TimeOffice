"""Misst den Kontext, der in JEDER Session geladen wird, und schlaegt frueh Alarm.

WARUM ES DAS GIBT
-----------------
`CLAUDE.md` wuchs zwischen dem 22.07. und 14.08.2026 von 24.763 auf 149.571 Zeichen -
429 Zeichen unter dem 150k-Limit von Claude Code. Am 11.08. allein kamen 53k dazu.

Das war KEIN Schlamperei-Problem: jede einzelne Zeile stammte aus einem echten Bug,
jede Ergaenzung war fuer sich berechtigt. Gefehlt hat etwas anderes - niemand hat je
die SUMME angesehen. Es gab kein Signal bis zur Wand, und die Regel dagegen
("Dokumente lebendig halten, nicht nur anhaeufen") war reine Prosa.

Genau diese Luecke schliesst dieses Skript: es misst das Aggregat, nicht die einzelne
Datei, und meldet sich lange vor dem Limit.

WAS ZAEHLT UND WAS NICHT
------------------------
Gezaehlt wird nur, was IMMER im Kontext liegt:
  - die Projekt-CLAUDE.md
  - die `description`-Zeilen aller Skills (Metadaten sind immer geladen)
  - die globale CLAUDE.md und MEMORY.md (nur lokal erreichbar, nicht in der CI)

NICHT gezaehlt werden `reference/*.md` und die Skill-Ruempfe: die kosten nichts, bis
sie gelesen werden. Das ist der ganze Sinn der Auslagerung - sie durften wachsen.

BETRIEBSARTEN
-------------
    python tools/doku/pruefe_budget.py
        Vollbericht fuer Menschen. Exit 1, wenn ein Repo-Budget gerissen ist.

    python tools/doku/pruefe_budget.py --ci
        Nur die Repo-Anteile (die CI sieht ~/.claude nicht). Exit 1 bei Ueberschreitung.

    python tools/doku/pruefe_budget.py --hook
        Fuer den SessionStart-Hook. Bleibt STILL, solange alles unter der
        Warnschwelle liegt - Stille heisst also "in Ordnung". Erst darueber gibt es
        Hook-JSON mit einer Meldung an Nutzer und Modell. Immer Exit 0.
"""
import glob
import io
import json
import os
import re
import sys

# ---------------------------------------------------------------- Budgets
# Herleitung: das harte Limit der Harness liegt bei 150.000 Zeichen. Die Schwellen
# hier lassen bewusst reichlich Luft, damit das Signal kommt, wenn Aufraeumen noch
# eine halbe Stunde kostet - nicht wenn es einen ganzen Tag kostet.
# Gegenprobe an der echten Historie: FAIL_CLAUDE_MD haette am 03.08.2026 (46.276)
# angeschlagen, also VOR dem Sprung auf 107k. Genau das war das Ziel.
WARN_CLAUDE_MD = 30_000
FAIL_CLAUDE_MD = 40_000

WARN_BESCHREIBUNGEN = 9_000      # Summe aller description-Zeilen
FAIL_BESCHREIBUNGEN = 14_000

WARN_GESAMT = 60_000             # alles Always-on zusammen, inkl. der lokalen Dateien
HARTES_LIMIT = 150_000           # nur zur Anzeige: hier bricht die Harness ab


def zeichen(pfad):
    try:
        return len(io.open(pfad, encoding="utf-8").read())
    except OSError:
        return None


def memory_index():
    """Findet ~/.claude/projects/<bereinigter-cwd>/memory/MEMORY.md.

    Claude Code ersetzt in dem Verzeichnisnamen die Pfadtrenner und den
    Laufwerks-Doppelpunkt durch Bindestriche. Falls die Ableitung nicht passt
    (andere Claude-Code-Version, anderes Betriebssystem), wird ueber den
    Projektordner-Namen gesucht, statt stillschweigend nichts zu messen.
    """
    basis = os.path.expanduser("~/.claude/projects")
    abgeleitet = re.sub(r"[:\\/]", "-", os.getcwd())
    kandidat = os.path.join(basis, abgeleitet, "memory", "MEMORY.md")
    if os.path.exists(kandidat):
        return kandidat
    ordner = os.path.basename(os.getcwd())
    treffer = [p for p in glob.glob(os.path.join(basis, "*", "memory", "MEMORY.md"))
               if p.replace("\\", "/").split("/")[-3].endswith(ordner)]
    return treffer[0] if len(treffer) == 1 else None


def erhebe(nur_repo):
    """Liefert (posten, befunde). posten = Liste (Bezeichnung, Zeichen, erreichbar)."""
    posten = []
    befunde = []

    n = zeichen("CLAUDE.md")
    if n is None:
        befunde.append("FEHLER: CLAUDE.md nicht lesbar - falsches Arbeitsverzeichnis?")
        return posten, befunde
    posten.append(("Projekt-CLAUDE.md", n, True))
    if n > FAIL_CLAUDE_MD:
        befunde.append(f"REISST: CLAUDE.md {n:,} Zeichen > {FAIL_CLAUDE_MD:,}. "
                       "Kurzregeln in den passenden Skill verschieben - dort kosten sie "
                       "erst beim Lesen etwas.")
    elif n > WARN_CLAUDE_MD:
        befunde.append(f"WARNUNG: CLAUDE.md {n:,} Zeichen > {WARN_CLAUDE_MD:,}. "
                       "Jetzt aufraeumen ist billiger als spaeter.")

    beschreibungen = 0
    for f in sorted(glob.glob(os.path.join(".claude", "skills", "*", "SKILL.md"))):
        for zeile in io.open(f, encoding="utf-8"):
            if zeile.startswith("description:"):
                beschreibungen += len(zeile)
                break
    if beschreibungen:
        anzahl = len(glob.glob(os.path.join(".claude", "skills", "*", "SKILL.md")))
        posten.append((f"{anzahl} Skill-Beschreibungen", beschreibungen, True))
        if beschreibungen > FAIL_BESCHREIBUNGEN:
            befunde.append(f"REISST: Skill-Beschreibungen {beschreibungen:,} Zeichen > "
                           f"{FAIL_BESCHREIBUNGEN:,}. Entweder kuerzen oder Skills zusammenlegen; "
                           "Metadaten sind IMMER geladen.")
        elif beschreibungen > WARN_BESCHREIBUNGEN:
            befunde.append(f"WARNUNG: Skill-Beschreibungen {beschreibungen:,} Zeichen > "
                           f"{WARN_BESCHREIBUNGEN:,}.")

    if not nur_repo:
        for bezeichnung, pfad in (("globale CLAUDE.md", os.path.expanduser("~/.claude/CLAUDE.md")),
                                  ("MEMORY.md (Index)", memory_index())):
            if not pfad:
                posten.append((bezeichnung, 0, False))
                continue
            n = zeichen(pfad)
            posten.append((bezeichnung, n or 0, n is not None))

    return posten, befunde


def main():
    nur_repo = "--ci" in sys.argv
    als_hook = "--hook" in sys.argv
    posten, befunde = erhebe(nur_repo)
    if not posten:
        print("\n".join(befunde) or "Nichts gemessen.")
        return 1

    summe = sum(n for _, n, ok in posten if ok)
    unvollstaendig = [b for b, _, ok in posten if not ok]
    if not nur_repo and summe > WARN_GESAMT:
        befunde.append(f"WARNUNG: Always-on gesamt {summe:,} Zeichen > {WARN_GESAMT:,} "
                       f"(hartes Limit {HARTES_LIMIT:,}).")

    reisst = [b for b in befunde if b.startswith(("REISST", "FEHLER"))]

    if als_hook:
        # Stille = in Ordnung. Nur melden, wenn wirklich etwas zu tun ist.
        if not befunde:
            return 0
        text = ("Doku-Budget: der Kontext, der in JEDER Session geladen wird, waechst "
                f"ueber die Schwelle ({summe:,} Zeichen).\n"
                + "\n".join(f"  - {b}" for b in befunde)
                + "\n\nDas ist der Fall, der am 14.08.2026 unbemerkt bis 429 Zeichen vor das "
                  "150k-Limit gelaufen ist. Kurzregeln gehoeren in den passenden Skill unter "
                  ".claude/skills/ - dort kosten sie erst beim Lesen etwas.")
        print(json.dumps({
            "systemMessage": f"Doku-Budget: {summe:,} Zeichen always-on "
                             f"({len(befunde)} Hinweis(e)) - siehe Kontext",
            "hookSpecificOutput": {"hookEventName": "SessionStart", "additionalContext": text},
        }, ensure_ascii=False))
        return 0

    print("Immer geladener Kontext:" if not nur_repo else "Immer geladen (nur Repo-Anteile):")
    for bezeichnung, n, ok in posten:
        print(f"  {n:>8,}  {bezeichnung}" if ok else f"  {'?':>8}  {bezeichnung} (nicht gefunden)")
    print(f"  {'-'*8}")
    print(f"  {summe:>8,}  Summe" + (f"   (hartes Limit {HARTES_LIMIT:,})" if not nur_repo else ""))
    if unvollstaendig:
        print(f"\n  Nicht mitgezaehlt: {', '.join(unvollstaendig)}")
    print()
    if befunde:
        for b in befunde:
            print(f"  {b}")
    else:
        print("  Alle Budgets eingehalten.")
    return 1 if reisst else 0


if __name__ == "__main__":
    sys.exit(main())
