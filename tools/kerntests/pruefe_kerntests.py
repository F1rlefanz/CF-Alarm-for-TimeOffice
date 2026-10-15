"""Haelt die Kerntest-Liste und die `@Category(Kerntest::class)`-Markierungen deckungsgleich.

WARUM ES DAS GIBT
-----------------
Kerntests sind die Tests, deren Bruch Daten oder den Wecker kostet (Liste: kerntests.txt
daneben). Sie sind beim Aufraeumen tabu - aber ein Aufraeumdurchgang loescht, legt zusammen und
benennt um, und jede Einzelaenderung sieht dabei berechtigt aus. Ein verschwundener Test faellt
nicht auf: die Suite bleibt gruen, sie prueft nur weniger. Deshalb vergleicht dieses Skript
mechanisch, in beide Richtungen:

  1. Ein gelisteter Test ohne Markierung im Code     -> verschwunden, umbenannt oder entmarkiert.
  2. Eine Markierung ohne Eintrag in der Liste       -> Liste nachfuehren (oder Markierung weg).
  3. Ein markierter Test ohne `@Test` oder mit `@Ignore` (an der Methode oder der Klasse)
     -> laeuft nicht mehr, obwohl die Markierung noch dasteht. Genau das waere sonst der
     stille Weg, einen Kerntest loszuwerden.

Exit 1 bei einem Befund. Laeuft in der CI und in der Schleuse.
"""
import os
import re
import sys

WURZEL = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
LISTE = os.path.join(WURZEL, "tools", "kerntests", "kerntests.txt")
PAKETPFAD = os.path.join("java", "com", "github", "f1rlefanz", "cf_alarmfortimeoffice")
QUELLSETS = {
    "test": os.path.join(WURZEL, "app", "src", "test", PAKETPFAD),
    "androidTest": os.path.join(WURZEL, "app", "src", "androidTest", PAKETPFAD),
}

MARKE = re.compile(r"@Category\(\s*Kerntest::class\s*\)")
FUN = re.compile(r"\bfun\s+(?:`([^`]+)`|(\w+))")
KLASSE = re.compile(r"^\s*(?:(?:internal|private|abstract|open)\s+)*class\s+\w+")


def lies_liste(pfad):
    """Liefert die Eintraege als Menge von (quellset, pfad, testname)."""
    eintraege = set()
    with open(pfad, encoding="utf-8") as f:
        for nr, zeile in enumerate(f, 1):
            zeile = zeile.rstrip("\n")
            if not zeile.strip() or zeile.lstrip().startswith("#"):
                continue
            kopf, trenner, name = zeile.partition("::")
            quellset, _, datei = kopf.partition(":")
            if not trenner or not datei or not name or quellset not in QUELLSETS:
                raise ValueError("{}:{}: unlesbarer Eintrag: {}".format(pfad, nr, zeile))
            eintraege.add((quellset, datei.strip(), name.strip()))
    return eintraege


def markierte_tests(quellsets):
    """Liefert {(quellset, pfad, testname): Liste der Maengel} fuer jede Markierung im Code."""
    gefunden = {}
    for quellset, wurzel in quellsets.items():
        for ordner, _, dateien in os.walk(wurzel):
            for d in sorted(dateien):
                if d.endswith(".kt"):
                    pfad = os.path.join(ordner, d)
                    datei = os.path.relpath(pfad, wurzel).replace("\\", "/")
                    for name, maengel in _markierte_in(pfad):
                        gefunden[(quellset, datei, name)] = maengel
    return gefunden


def _markierte_in(pfad):
    with open(pfad, encoding="utf-8") as f:
        zeilen = f.read().splitlines()
    klasse_ignoriert = False
    annotationen = []
    for zeile in zeilen:
        s = zeile.strip()
        if s.startswith("@"):
            annotationen.append(s)
            continue
        if not s or s.startswith(("//", "*", "/*")):
            continue                                    # Kommentar zwischen Annotationen
        if KLASSE.match(zeile):
            if any(a.startswith("@Ignore") for a in annotationen):
                klasse_ignoriert = True
        treffer = FUN.search(s)
        if treffer and any(MARKE.search(a) for a in annotationen):
            name = treffer.group(1) or treffer.group(2)
            maengel = []
            if not any(re.match(r"@Test\b", a) for a in annotationen):
                maengel.append("traegt kein @Test mehr")
            if any(a.startswith("@Ignore") for a in annotationen) or klasse_ignoriert:
                maengel.append("ist mit @Ignore abgeschaltet")
            yield name, maengel
        annotationen = []


def pruefe(liste=LISTE, quellsets=None):
    """Liefert die Befunde als Liste von Texten; leer heisst deckungsgleich."""
    soll = lies_liste(liste)
    ist = markierte_tests(quellsets or QUELLSETS)
    befunde = []
    for e in sorted(soll - set(ist)):
        befunde.append("Kerntest fehlt oder ist nicht mehr markiert: {}:{}::{}".format(*e))
    for e in sorted(set(ist) - soll):
        befunde.append("Markiert, aber nicht in kerntests.txt - Liste nachfuehren: {}:{}::{}".format(*e))
    for e, maengel in sorted(ist.items()):
        for m in maengel:
            befunde.append("Kerntest {}: {}:{}::{}".format(m, *e))
    return befunde


def main():
    befunde = pruefe()
    if befunde:
        print("Kerntests nicht deckungsgleich ({}):".format(len(befunde)))
        for b in befunde:
            print("  - " + b)
        print("Kerntests sind beim Aufraeumen tabu. Bewusste Verhaltensaenderung: Test mit Grund "
              "aendern und tools/kerntests/kerntests.txt im selben Commit nachfuehren.")
        return 1
    print("Kerntests: {} gelistet, alle markiert und aktiv.".format(len(lies_liste(LISTE))))
    return 0


if __name__ == "__main__":
    sys.exit(main())
