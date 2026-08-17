# -*- coding: utf-8 -*-
"""Beweist, dass die Umstellung auf CHANGELOG.md verlustfrei war.

Vergleicht den reinen TEXT der handgeschriebenen Original-Seite mit dem der
neu erzeugten Seite: alle Tags raus, Entities aufgeloest, Leerraum
normalisiert. Das Original ist die Wahrheit - weicht etwas ab, ist die
KONVERTIERUNG zu korrigieren, niemals diese Pruefung.

Aufruf:
    python tools/changelog/pruefe_treue.py <original.html> [<neu.html>]

Ohne zweites Argument wird docs/changelog.html geprueft.
Rueckgabewert: 0 = identisch, 1 = Abweichung, 2 = Aufruffehler.
"""
from __future__ import annotations

import html as html_modul
import re
import sys
from pathlib import Path

PROJEKT_WURZEL = Path(__file__).resolve().parents[2]
STANDARD_ZIEL = PROJEKT_WURZEL / "docs" / "changelog.html"

# Verglichen wird nur der Versionsteil der Seite: vom ersten <div class=
# "content-card"> bis zum Ende von <main>. Kopf, Navigation und Footer sind
# nicht Gegenstand der Umstellung.
BEREICH_ANFANG = '<div class="content-card">'
BEREICH_ENDE = "</main>"

# Wie viele Zeichen Umfeld eine Abweichung im Bericht mitbekommt.
KONTEXT_ZEICHEN = 120


def versionsbereich(seite: str) -> str:
    try:
        anfang = seite.index(BEREICH_ANFANG)
        ende = seite.rindex(BEREICH_ENDE)
    except ValueError as fehler:
        raise SystemExit(f"Versionsbereich nicht gefunden: {fehler}")
    return seite[anfang:ende]


def nur_text(abschnitt: str) -> str:
    ohne_kommentare = re.sub(r"<!--.*?-->", " ", abschnitt, flags=re.S)
    ohne_tags = re.sub(r"<[^>]+>", " ", ohne_kommentare)
    entschluesselt = html_modul.unescape(ohne_tags)
    return " ".join(entschluesselt.split())


def versionsueberschriften(seite: str) -> list[str]:
    roh = re.findall(r"<h3>(.*?)</h3>", versionsbereich(seite), re.S)
    return [" ".join(html_modul.unescape(re.sub(r"<[^>]+>", " ", t)).split()) for t in roh]


def erste_abweichung(links: str, rechts: str) -> int:
    for i, (a, b) in enumerate(zip(links, rechts)):
        if a != b:
            return i
    return min(len(links), len(rechts))


def main(argv: list[str]) -> int:
    if len(argv) not in (2, 3):
        print(__doc__, file=sys.stderr)
        return 2
    original_pfad = Path(argv[1])
    neu_pfad = Path(argv[2]) if len(argv) == 3 else STANDARD_ZIEL
    for pfad in (original_pfad, neu_pfad):
        if not pfad.is_file():
            print(f"FEHLER: Datei nicht gefunden: {pfad}", file=sys.stderr)
            return 2

    original = original_pfad.read_text(encoding="utf-8")
    neu = neu_pfad.read_text(encoding="utf-8")

    kopf_original = versionsueberschriften(original)
    kopf_neu = versionsueberschriften(neu)
    print(f"Versionskarten Original: {len(kopf_original)}")
    print(f"Versionskarten neu     : {len(kopf_neu)}")

    fehlerhaft = False
    if kopf_original != kopf_neu:
        fehlerhaft = True
        fehlend = [k for k in kopf_original if k not in kopf_neu]
        zusaetzlich = [k for k in kopf_neu if k not in kopf_original]
        print(f"ABWEICHUNG bei den Ueberschriften. Fehlt: {fehlend} / Zuviel: {zusaetzlich}")

    text_original = nur_text(versionsbereich(original))
    text_neu = nur_text(versionsbereich(neu))

    if text_original != text_neu:
        fehlerhaft = True
        stelle = erste_abweichung(text_original, text_neu)
        von = max(0, stelle - KONTEXT_ZEICHEN)
        print("ABWEICHUNG im Text ab Zeichen", stelle)
        print("ORIGINAL: ..." + text_original[von:stelle + KONTEXT_ZEICHEN])
        print("NEU     : ..." + text_neu[von:stelle + KONTEXT_ZEICHEN])
        print(f"Laenge Original: {len(text_original)}, neu: {len(text_neu)}")

    if fehlerhaft:
        print("ERGEBNIS: NICHT treu - die Konvertierung ist zu korrigieren.")
        return 1

    print(f"Verglichene Textzeichen: {len(text_original)}")
    print("ERGEBNIS: treu - Original und erzeugte Seite sind textgleich.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
