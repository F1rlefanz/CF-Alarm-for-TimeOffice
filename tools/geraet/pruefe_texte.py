"""Prueft am Emulator, ob ein Bildschirm die Pflichttexte in der richtigen Reihenfolge zeigt.

WARUM ES DAS GIBT
-----------------
Fuer den Umbau "Schichttyp als Zentrale" (entwurf/umbau-schichttyp/) setzen Agenten Bildschirme
unbeaufsichtigt um. Ein Screenshot-Urteil allein ist weich: ein Modell "sieht" eine Ueberschrift,
die in Wahrheit fehlt, oder uebersieht eine vertauschte Reihenfolge. Die Pflichttexte je
Bildschirm stehen in entwurf/umbau-schichttyp/BILDSCHIRME.md; dieses Skript liest die Texte
der laufenden App per uiautomator aus (scrollt dabei nach unten) und vergleicht hart.

WARUM NUR EMULATOR
------------------
Es wischt auf dem Zielgeraet. Auf dem produktiven Handy des Nutzers waere das ein Eingriff in
seinen echten Wecker. Ein Ziel ohne emulator-*-Seriennummer wird abgelehnt; einen
Umgehungsschalter gibt es bewusst nicht (wie pruefe_direct_boot.py).

AUFRUF
------
  python tools/geraet/pruefe_texte.py --datei erwartet.txt [--serial emulator-5554] [--seiten 6]

erwartet.txt: ein Text pro Zeile, von oben nach unten. Eine Zeile mit fuehrendem "!" ist ein
Text, der NICHT vorkommen darf. Leere Zeilen und Zeilen mit "#" werden ignoriert.
Exit 0 = alles da, in Reihenfolge, nichts Verbotenes. Exit 1 = Befund (steht auf stdout).
Exit 2 = Aufruf- oder Geraetefehler.
"""
import argparse
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

BOUNDS = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")


def texte_aus_dump(xml_text):
    """Alle sichtbaren Texte eines uiautomator-Dumps, von oben nach unten, links nach rechts."""
    knoten = []
    for el in ET.fromstring(xml_text).iter("node"):
        for attr in ("text", "content-desc"):
            wert = (el.get(attr) or "").strip()
            if not wert:
                continue
            m = BOUNDS.match(el.get("bounds") or "")
            oben, links = (int(m.group(2)), int(m.group(1))) if m else (0, 0)
            knoten.append((oben, links, wert))
    knoten.sort(key=lambda k: (k[0], k[1]))
    return [k[2] for k in knoten]


def zusammenfuehren(bisher, neu):
    """Haengt die Texte einer weiteren Bildschirmseite an, ohne die Ueberlappung doppelt zu zaehlen.

    Nach einem Wisch steht oben ein Teil der vorigen Seite. Gesucht wird das laengste Ende von
    `bisher`, das ein Anfang von `neu` ist; nur der Rest wird angehaengt."""
    for laenge in range(min(len(bisher), len(neu)), 0, -1):
        if bisher[-laenge:] == neu[:laenge]:
            return bisher + neu[laenge:]
    return bisher + neu


def lies_erwartung(zeilen):
    erwartet, verboten = [], []
    for zeile in zeilen:
        zeile = zeile.strip()
        if not zeile or zeile.startswith("#"):
            continue
        if zeile.startswith("!"):
            verboten.append(zeile[1:].strip())
        else:
            erwartet.append(zeile)
    return erwartet, verboten


def pruefe(gefunden, erwartet, verboten):
    """Liefert die Befunde als Liste von Saetzen; leer heisst bestanden.

    Jeder erwartete Text muss als Teilstring in einem gefundenen Text stehen, und zwar NACH dem
    Fundort des vorigen erwarteten Textes (Reihenfolge)."""
    befunde = []
    pos = 0
    for text in erwartet:
        treffer = next((i for i in range(pos, len(gefunden)) if text in gefunden[i]), None)
        if treffer is None:
            frueher = next((i for i in range(0, pos) if text in gefunden[i]), None)
            if frueher is not None:
                befunde.append(f"Reihenfolge: \"{text}\" steht vor \"{gefunden[pos - 1]}\", erwartet danach")
            else:
                befunde.append(f"fehlt: \"{text}\"")
            continue
        pos = treffer + 1
    for text in verboten:
        for g in gefunden:
            if text in g:
                befunde.append(f"darf nicht vorkommen: \"{text}\" (gefunden in \"{g}\")")
                break
    return befunde


def adb(serial, *args):
    return subprocess.run(["adb", "-s", serial, *args], capture_output=True, text=True,
                          encoding="utf-8", errors="replace", timeout=60)


def dump(serial):
    r = adb(serial, "shell", "uiautomator", "dump", "/data/local/tmp/ui.xml")
    if r.returncode != 0:
        raise RuntimeError("uiautomator dump: " + r.stderr.strip())
    return adb(serial, "shell", "cat", "/data/local/tmp/ui.xml").stdout


def sammle(serial, seiten):
    groesse = adb(serial, "shell", "wm", "size").stdout
    m = re.search(r"(\d+)x(\d+)", groesse)
    breite, hoehe = (int(m.group(1)), int(m.group(2))) if m else (1080, 2400)
    alles = texte_aus_dump(dump(serial))
    for _ in range(seiten - 1):
        adb(serial, "shell", "input", "swipe", str(breite // 2), str(int(hoehe * 0.75)),
            str(breite // 2), str(int(hoehe * 0.35)), "400")
        neu = texte_aus_dump(dump(serial))
        vorher = len(alles)
        alles = zusammenfuehren(alles, neu)
        if len(alles) == vorher:
            break
    return alles


def main():
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("--datei", required=True)
    p.add_argument("--serial", default="emulator-5554")
    p.add_argument("--seiten", type=int, default=6)
    a = p.parse_args()
    if not a.serial.startswith("emulator-"):
        print("Abgelehnt: nur Emulator-Ziele (emulator-*). Das produktive Handy wird nie angesprochen.")
        return 2
    try:
        with open(a.datei, encoding="utf-8") as f:
            erwartet, verboten = lies_erwartung(f.readlines())
        gefunden = sammle(a.serial, a.seiten)
    except (OSError, RuntimeError, subprocess.SubprocessError, ET.ParseError) as e:
        print("Fehler:", e)
        return 2
    befunde = pruefe(gefunden, erwartet, verboten)
    print(f"{len(gefunden)} Texte gelesen, {len(erwartet)} erwartet, {len(verboten)} verboten.")
    for b in befunde:
        print("BEFUND:", b)
    if befunde:
        print("Gelesen, in Reihenfolge:")
        for g in gefunden:
            print("  |", g)
    print("BESTANDEN" if not befunde else f"NICHT BESTANDEN ({len(befunde)} Befunde)")
    return 0 if not befunde else 1


if __name__ == "__main__":
    sys.exit(main())
