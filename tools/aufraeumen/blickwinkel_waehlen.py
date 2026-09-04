#!/usr/bin/env python3
"""Waehlt den Blickwinkel, den die naechste Aufraeumrunde bearbeitet.

WARUM ES DIESES WERKZEUG GIBT
Bis zum 03.09.2026 stand im Skill nur: "Nimm den obersten offenen Eintrag" aus
`gh issue list --label aufraeumen --state open`. Das hat zwei Fehler, beide am
03.09.2026 an den echten Daten gemessen, nicht vermutet:

1. DIE LISTE IST EIN STAPEL, KEINE SCHLANGE. `gh issue list` sortiert nach
   Erstelldatum absteigend - der NEUESTE Eintrag steht oben. Die neun
   Blickwinkel vom 25.08.2026 (#19-#25) sind deshalb seit ihrer Anlage nie an
   die Reihe gekommen; gearbeitet wurde immer am juengsten Eintrag.
2. EIN GESCHEITERTER BLICKWINKEL SPERRT DEN KOPF DER LISTE. Schliesst der
   Torwaechter den PR, oeffnet er das Issue wieder - das Erstelldatum aendert
   sich dabei nicht. Issue #39 stand damit ab dem 28.08.2026 dauerhaft oben und
   hat VIER Runden verbraucht (PR #40, #48, #56, #58, alle geschlossen), waehrend
   die neun aelteren Blickwinkel unberuehrt lagen. Jede Runde scheiterte an dem,
   was sie beim Reparieren NEU hineinschrieb - nicht am Befund.

Also: aeltester zuerst, und nach `--deckel` gescheiterten Anlaeufen ist ein
Blickwinkel zurueckgestellt. Zurueckgestellt heisst NICHT verworfen - das Issue
bleibt offen, aber es kostet keine weitere Runde, bis ein Mensch entschieden hat.

WIE "GESCHEITERTER ANLAUF" GEZAEHLT WIRD
Nicht aus dem Fliesstext der PR-Beschreibung (eine `#40` darin kann ein PR ODER
ein Issue meinen) und nicht aus den `reopened`-Ereignissen (die zaehlen zu
niedrig: nicht jede Runde schliesst ihr Issue, bevor der Torwaechter urteilt -
fuer #39 ergaben sie 2 statt 4). Gezaehlt wird, was GitHub selbst verknuepft:
`cross-referenced`-Ereignisse der Zeitleiste, die auf einen Pull Request zeigen,
der geschlossen und NICHT gemergt ist. Fuer #39 ergibt das exakt #40, #48, #56,
#58; der gemergte PR #32 an Issue #31 wird korrekt nicht mitgezaehlt.

BEKANNTE UNGENAUIGKEIT, gemessen statt verschwiegen: GitHub verknuepft JEDE
Erwaehnung, auch eine beilaeufige. Issue #38 zaehlt deshalb einen Anlauf (PR #37),
obwohl dieser PR an Issue #31 arbeitete und #38 nur als Folgefrage nannte. Die
Zahl kann also zu HOCH sein, nie zu niedrig. Deshalb zwei Dinge: der Deckel liegt
bei 3 (eine einzelne Fehlzaehlung stellt nichts zurueck), und die PR-Nummern
stehen in der Ausgabe - wer eine Zurueckstellung anzweifelt, sieht sofort woran.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys

DECKEL_VORGABE = 3

# Titel-Praefix der Nachtrags-Issues. Sie stehen mit demselben Label in der
# Warteschlange, sind aber fuer eine Runde nicht abarbeitbar: das Schreiben an
# `.claude/**` lehnt Claude Code unbeaufsichtigt ab (am 03.09.2026 headless
# nachgestellt - Edit, Write UND Bash, und weder eine Pfadregel in
# `--allowedTools` noch `--permission-mode acceptEdits` heben es auf). Sie
# gehoeren dem Eigentuemer; eine Runde traegt ihre Lehren nach
# `tools/aufraeumen/nachtraege.md`.
NACHTRAG_PRAEFIX = "Skill-Nachzug"


def gescheiterte_anlaeufe(zeitleiste):
    """Nummern der PRs, die diesen Blickwinkel bearbeitet haben und scheiterten.

    Geschlossen UND nicht gemergt. Doppelnennungen (GitHub traegt jede Erwaehnung
    einzeln ein) werden zusammengefasst, die Reihenfolge ist aufsteigend.
    """
    treffer = set()
    for ereignis in zeitleiste:
        if ereignis.get("event") != "cross-referenced":
            continue
        quelle = (ereignis.get("source") or {}).get("issue") or {}
        pr = quelle.get("pull_request")
        if not isinstance(pr, dict):
            continue  # ein Issue, kein PR
        if quelle.get("state") != "closed":
            continue  # laeuft noch
        if pr.get("merged_at"):
            continue  # gemergt = gelungen, kein Anlauf
        nummer = quelle.get("number")
        if isinstance(nummer, int):
            treffer.add(nummer)
    return sorted(treffer)


def _lage(kandidat, deckel):
    if kandidat["titel"].startswith(NACHTRAG_PRAEFIX):
        return "eigentuemer"
    if len(kandidat["anlaeufe"]) >= deckel:
        return "zurueckgestellt"
    return "waehlbar"


def waehle(kandidaten, deckel=DECKEL_VORGABE):
    """Ordnet die Warteschlange und benennt den Blickwinkel dieser Runde.

    `kandidaten`: je {nummer, titel, erstellt, anlaeufe}. Sortiert wird allein
    nach Erstelldatum aufsteigend - AELTESTER ZUERST, ohne Ansehen der Anlaufzahl.
    Nach der Anlaufzahl zu sortieren waere verlockend (unberuehrter Boden traegt
    mehr), fuehrt aber in dieselbe Falle zurueck: jede Runde legt laut Skill neue
    Issues an, und lauter frische Null-Anlauf-Eintraege liessen einen Blickwinkel
    mit einem einzigen Anlauf nie wieder an die Reihe kommen. Das Blockieren
    besorgt der Deckel, nicht die Reihenfolge.

    Zurueckgestellte und dem Eigentuemer vorbehaltene Eintraege bleiben in der
    Tabelle sichtbar, kommen aber nicht zum Zug.
    """
    warteschlange = []
    for kandidat in kandidaten:
        eintrag = dict(kandidat)
        eintrag["lage"] = _lage(kandidat, deckel)
        warteschlange.append(eintrag)
    warteschlange.sort(key=lambda e: (e["erstellt"], e["nummer"]))

    waehlbare = [e for e in warteschlange if e["lage"] == "waehlbar"]
    return {
        "deckel": deckel,
        "gewaehlt": waehlbare[0] if waehlbare else None,
        "warteschlange": warteschlange,
    }


def _gh(argumente):
    ergebnis = subprocess.run(
        ["gh"] + argumente, capture_output=True, text=True, encoding="utf-8"
    )
    if ergebnis.returncode != 0:
        raise RuntimeError(
            "`gh " + " ".join(argumente) + "` scheiterte: " + (ergebnis.stderr or "").strip()
        )
    return ergebnis.stdout


def hole_kandidaten(repo):
    roh = json.loads(
        _gh(
            [
                "issue", "list", "--label", "aufraeumen", "--state", "open",
                "--limit", "100", "--json", "number,title,createdAt",
            ]
        )
        or "[]"
    )
    kandidaten = []
    for issue in roh:
        seiten = json.loads(
            _gh(
                [
                    "api", "repos/%s/issues/%d/timeline" % (repo, issue["number"]),
                    "--paginate", "--slurp",
                ]
            )
            or "[]"
        )
        zeitleiste = [e for seite in seiten for e in seite]
        kandidaten.append(
            {
                "nummer": issue["number"],
                "titel": issue["title"],
                "erstellt": issue["createdAt"],
                "anlaeufe": gescheiterte_anlaeufe(zeitleiste),
            }
        )
    return kandidaten


def als_text(urteil):
    marken = {"waehlbar": "  ", "zurueckgestellt": "!!", "eigentuemer": "->"}
    zeilen = [
        "Warteschlange (aeltester zuerst; Deckel: %d gescheiterte Anlaeufe)" % urteil["deckel"],
        "",
    ]
    for eintrag in urteil["warteschlange"]:
        anlaeufe = ", ".join("#%d" % pr for pr in eintrag["anlaeufe"]) or "-"
        zeilen.append(
            "%s #%-4d %-15s Anlaeufe: %-2d (%s)  %s"
            % (
                marken[eintrag["lage"]],
                eintrag["nummer"],
                eintrag["lage"],
                len(eintrag["anlaeufe"]),
                anlaeufe,
                eintrag["titel"][:56],
            )
        )
    zeilen.append("")
    gewaehlt = urteil["gewaehlt"]
    if gewaehlt:
        zeilen.append("Diese Runde bearbeitet: #%d - %s" % (gewaehlt["nummer"], gewaehlt["titel"]))
    else:
        zeilen.append("NICHTS WAEHLBAR. Alles Offene ist zurueckgestellt (Deckel erreicht)")
        zeilen.append("oder gehoert dem Eigentuemer. Nichts erfinden - melden und aufhoeren.")
    zeilen.append("")
    zeilen.append("!! = zurueckgestellt: der Blickwinkel taugt, die bisherigen Reparaturen")
    zeilen.append("     nicht. Braucht eine Entscheidung, keine weitere Runde.")
    zeilen.append("-> = Nachtrag an `.claude/`: kann eine Runde nicht schreiben (gemessen),")
    zeilen.append("     gehoert dem Eigentuemer. Lehren nach tools/aufraeumen/nachtraege.md.")
    return "\n".join(zeilen)


def main(argv=None):
    zerleger = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    zerleger.add_argument(
        "--deckel", type=int, default=DECKEL_VORGABE,
        help="ab wie vielen gescheiterten Anlaeufen ein Blickwinkel ruht",
    )
    zerleger.add_argument("--json", action="store_true", help="Urteil als JSON")
    zerleger.add_argument("--repo", default=None, help="owner/name (sonst aus `gh repo view`)")
    argumente = zerleger.parse_args(argv)

    try:
        repo = argumente.repo or _gh(
            ["repo", "view", "--json", "nameWithOwner", "-q", ".nameWithOwner"]
        ).strip()
        kandidaten = hole_kandidaten(repo)
    except (RuntimeError, OSError, ValueError) as fehler:
        print("Warteschlange nicht lesbar: %s" % fehler, file=sys.stderr)
        print(
            "Ohne sie NICHT nach Gefuehl waehlen - Runde abbrechen und melden.",
            file=sys.stderr,
        )
        return 2

    urteil = waehle(kandidaten, argumente.deckel)
    if argumente.json:
        print(json.dumps(urteil, indent=1, ensure_ascii=False))
    else:
        print(als_text(urteil))
    return 0 if urteil["gewaehlt"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
