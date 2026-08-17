# -*- coding: utf-8 -*-
"""Erzeugt den Versionsteil von docs/changelog.html aus CHANGELOG.md.

Die Markdown-Datei ist die QUELLE. Dieses Skript ersetzt in der HTML-Seite
ausschliesslich den Bereich zwischen den beiden Markern

    <!-- CHANGELOG:BEGIN -->  ...  <!-- CHANGELOG:END -->

Alles ausserhalb (Kopf, Navigation, Feedback-Karte, Footer) bleibt Zeichen
fuer Zeichen unangetastet.

Aufruf (vom Projekt-Wurzelverzeichnis):
    python tools/changelog/build_changelog.py            # schreibt die Seite
    python tools/changelog/build_changelog.py --pruefen  # prueft nur, schreibt nicht

Rueckgabewert: 0 = ok. Bei --pruefen bedeutet 1, dass die Seite nicht mehr zur
Markdown-Quelle passt (der Generator muss laufen).

Erwartete Markdown-Struktur (siehe CHANGELOG.md):
    ## <Ueberschrift> [(Zusatz)]   -> <h3>...<small>(Zusatz)</small></h3>
    **Label:** Wert                -> <p><strong>Label:</strong> Wert</p>
    _Kursiver Absatz_              -> <p><em>Kursiver Absatz</em></p>
    ### <Rubrik>                   -> <h4>Rubrik</h4>
    - Eintrag                      -> <ul><li>Eintrag</li></ul>
Innerhalb einer Zeile: **fett** -> <strong>, *kursiv* -> <em>.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

# --- Konstanten (keine Magic Numbers im Code weiter unten) -------------------

MARKER_ANFANG = "<!-- CHANGELOG:BEGIN -->"
MARKER_ENDE = "<!-- CHANGELOG:END -->"

# Einrueckung der bestehenden Seite: die Karte liegt in <main class="container">.
EINZUG_KARTE = " " * 8
EINZUG_BLOCK = " " * 12
EINZUG_LISTENEINTRAG = " " * 16

# Das Zeilenende wird aus der BESTEHENDEN Seite abgeleitet, nicht festgeschrieben.
#
# Hier stand bis zum 17.08.2026 ZEILENENDE = "\r\n" mit der Begruendung "die Seite ist
# durchgaengig mit CRLF gespeichert". Das galt fuer die Windows-ARBEITSKOPIE - in Git liegt
# die Datei mit LF, und der Linux-CI-Runner checkt sie so aus. Der Generator erzeugte dort
# CRLF-Karten in einer LF-Seite, `--pruefen` schlug fehl, und die CI war drei Commits lang
# rot, waehrend die Pruefung lokal gruen lief. Ein Wert, der plattformabhaengig ist, gehoert
# nicht in eine Konstante.
ZEILENENDE_VORGABE = "\r\n"


def zeilenende_von(seite: str) -> str:
    """Nimmt das Zeilenende, das die Seite tatsaechlich verwendet."""
    return "\r\n" if "\r\n" in seite else "\n"

PROJEKT_WURZEL = Path(__file__).resolve().parents[2]
QUELLE_MD = PROJEKT_WURZEL / "CHANGELOG.md"
ZIEL_HTML = PROJEKT_WURZEL / "docs" / "changelog.html"


class ChangelogFehler(RuntimeError):
    """Klar benannter Abbruch - lieber laut scheitern als still Falsches schreiben."""


# --- Markdown -> HTML --------------------------------------------------------

def html_maskieren(text: str) -> str:
    """Nur '&' ist im Bestand relevant; '<'/'>' kaemen aus Handarbeit und
    werden ebenfalls maskiert, damit nie rohes Markup durchrutscht."""
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def inline_nach_html(text: str) -> str:
    """**fett** -> <strong>, *kursiv* -> <em>. Reihenfolge ist tragend:
    erst die doppelten Sterne, sonst frisst die Kursiv-Regel die Haelfte."""
    text = html_maskieren(text)
    if "**" in text:
        teile = text.split("**")
        if len(teile) % 2 == 0:
            raise ChangelogFehler(f"Unpaarige '**' in: {text}")
        text = "".join(
            stueck if i % 2 == 0 else f"<strong>{stueck}</strong>"
            for i, stueck in enumerate(teile)
        )
    if "*" in text:
        teile = text.split("*")
        if len(teile) % 2 == 0:
            raise ChangelogFehler(f"Unpaarige '*' in: {text}")
        text = "".join(
            stueck if i % 2 == 0 else f"<em>{stueck}</em>"
            for i, stueck in enumerate(teile)
        )
    return text


def ueberschrift_nach_html(zeile: str) -> str:
    """'## Version 1.2.3 (Zusatz)' -> 'Version 1.2.3 <small>(Zusatz)</small>'."""
    treffer = re.match(r"^(?P<titel>.*?)\s*\((?P<zusatz>[^()]*)\)$", zeile)
    if treffer:
        titel = inline_nach_html(treffer.group("titel"))
        zusatz = inline_nach_html(treffer.group("zusatz"))
        return f"{titel} <small>({zusatz})</small>"
    return inline_nach_html(zeile)


def absatz_nach_html(zeile: str) -> str:
    """Ein komplett kursiver Absatz wird in CHANGELOG.md mit '_' geklammert -
    so bleibt ein *kursives* Wort INNERHALB davon eindeutig."""
    if zeile.startswith("_") and zeile.endswith("_") and len(zeile) > 2:
        return f"<p><em>{inline_nach_html(zeile[1:-1])}</em></p>"
    return f"<p>{inline_nach_html(zeile)}</p>"


def markdown_nach_karten(markdown: str, zeilenende: str = ZEILENENDE_VORGABE) -> str:
    """Erzeugt die Folge von <div class="content-card">-Bloecken."""
    zeilen = markdown.splitlines()
    karten: list[list[str]] = []
    aktuell: list[str] | None = None
    in_liste = False

    def liste_schliessen() -> None:
        nonlocal in_liste
        if in_liste:
            aktuell.append(EINZUG_BLOCK + "</ul>")
            in_liste = False

    for rohzeile in zeilen:
        zeile = rohzeile.rstrip()
        if zeile.startswith("## "):
            if aktuell is not None:
                liste_schliessen()
                karten.append(aktuell)
            aktuell = [
                EINZUG_KARTE + '<div class="content-card">',
                EINZUG_BLOCK + f"<h3>{ueberschrift_nach_html(zeile[3:].strip())}</h3>",
            ]
            continue
        if aktuell is None:
            continue  # Vorspann der Markdown-Datei gehoert nicht auf die Seite
        if not zeile:
            continue
        if zeile.startswith("### "):
            liste_schliessen()
            aktuell.append("")
            aktuell.append(EINZUG_BLOCK + f"<h4>{inline_nach_html(zeile[4:].strip())}</h4>")
            continue
        if zeile.startswith("- "):
            if not in_liste:
                aktuell.append(EINZUG_BLOCK + "<ul>")
                in_liste = True
            aktuell.append(EINZUG_LISTENEINTRAG + f"<li>{inline_nach_html(zeile[2:].strip())}</li>")
            continue
        if zeile.startswith("#"):
            raise ChangelogFehler(f"Unerwartete Ueberschriftebene: {zeile}")
        liste_schliessen()
        aktuell.append(EINZUG_BLOCK + absatz_nach_html(zeile))

    if aktuell is None:
        raise ChangelogFehler("CHANGELOG.md enthaelt keine einzige Version ('## ...').")
    liste_schliessen()
    karten.append(aktuell)

    for karte in karten:
        karte.append(EINZUG_KARTE + "</div>")

    bloecke = [zeilenende.join(karte) for karte in karten]
    return (zeilenende * 2).join(bloecke)


# --- Seite schreiben ---------------------------------------------------------

def neue_seite(seite: str, karten_html: str) -> str:
    anfang = seite.find(MARKER_ANFANG)
    ende = seite.find(MARKER_ENDE)
    if anfang == -1 or ende == -1:
        raise ChangelogFehler(
            f"Marker fehlen in {ZIEL_HTML}. Erwartet werden {MARKER_ANFANG} "
            f"und {MARKER_ENDE}."
        )
    if ende < anfang:
        raise ChangelogFehler("Marker stehen in falscher Reihenfolge.")
    kopf = seite[: anfang + len(MARKER_ANFANG)]
    fuss = seite[ende:]
    # Leerzeile zwischen letzter Karte und Endmarker, wie zwischen den Karten.
    ze = zeilenende_von(seite)
    return kopf + ze + karten_html + (ze * 2) + EINZUG_KARTE + fuss


def main(argv: list[str]) -> int:
    nur_pruefen = "--pruefen" in argv[1:]
    unbekannt = [a for a in argv[1:] if a != "--pruefen"]
    if unbekannt:
        raise ChangelogFehler(f"Unbekannte Argumente: {unbekannt}")

    if not QUELLE_MD.is_file():
        raise ChangelogFehler(f"Quelle nicht gefunden: {QUELLE_MD}")
    if not ZIEL_HTML.is_file():
        raise ChangelogFehler(f"Zielseite nicht gefunden: {ZIEL_HTML}")

    markdown = QUELLE_MD.read_text(encoding="utf-8")
    # newline="" haelt die CRLF der Seite fest (Path.read_text kennt den
    # Parameter erst ab Python 3.13, deshalb open()).
    with open(ZIEL_HTML, encoding="utf-8", newline="") as datei:
        seite = datei.read()
    ergebnis = neue_seite(seite, markdown_nach_karten(markdown, zeilenende_von(seite)))

    versionen = len(re.findall(r"^## ", markdown, re.M))

    if ergebnis == seite:
        print(f"docs/changelog.html ist aktuell ({versionen} Versionskarten).")
        return 0
    if nur_pruefen:
        print(
            "FEHLER: docs/changelog.html passt nicht zu CHANGELOG.md. "
            "Bitte 'python tools/changelog/build_changelog.py' ausfuehren.",
            file=sys.stderr,
        )
        return 1
    with open(ZIEL_HTML, "w", encoding="utf-8", newline="") as datei:
        datei.write(ergebnis)
    print(f"docs/changelog.html neu erzeugt: {versionen} Versionskarten.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv))
    except ChangelogFehler as fehler:
        print(f"FEHLER: {fehler}", file=sys.stderr)
        sys.exit(2)
