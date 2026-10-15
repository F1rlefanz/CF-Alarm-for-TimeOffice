"""Erzeugt die eingebauten PLZ-Tabellen fuer "Ort fuer Tageslicht" (#140).

    python tools/tageslicht/baue_plz_tabelle.py DE.zip AT.zip CH.zip --ziel app/src/main/assets/tageslicht

Eingabe sind die Laender-Zips von https://download.geonames.org/export/zip/ (das Skript laedt
NICHTS selbst herunter; die Zips liegen per .gitignore ausgeschlossen unter tools/tageslicht/).
Ausgabe: plz_de.tsv, plz_at.tsv, plz_ch.tsv - Kopfzeilen mit '#' (Quelle, Lizenz, Stand), danach
`praefix<TAB>breite<TAB>laenge<TAB>name`, nach Praefix sortiert, Koordinaten auf 2 Stellen.
Gelesen von `tageslicht/PlzTabelle.parse` in der App.

AGGREGATION je Praefix (2 Stellen; fuer DE zusaetzlich 3 Stellen):
 1. Nur Zeilen MIT Genauigkeitsangabe (Spalte 12). In DE sind die Zeilen ohne Angabe fast
    durchweg Grosskunden- und Postfach-PLZ ("10875 Mercedes-Benz ..." liegt in Stuttgart, nicht
    in Berlin) - sie wuerden Schwerpunkte quer durchs Land verziehen und Firmennamen als Ortsnamen
    liefern.
 2. Je DISTINKTER PLZ ein Punkt (Mittel ihrer Ortszeilen), damit eine PLZ mit vielen Ortsteilen
    nicht mehr Gewicht bekommt als eine mit einem.
 3. Ausreisser verwerfen: PLZ, die weiter als AUSREISSER_KM vom koordinatenweisen MEDIAN der
    Region liegen. Der Median ist robust, das Mittel danach genau.
 4. Schwerpunkt = Mittel der verbliebenen PLZ-Punkte.
 5. Name = Ort der PLZ, die dem Schwerpunkt am naechsten liegt (stabil und lesbar). Ortsnamen
    mit "Postfach" sind nachrangig: AT fuehrt Postfach-PLZ ("1201 Wien Postfach") MIT
    Genauigkeitsangabe, Punkt 1 laesst sie durch - als Regionsname ist "Wien Postfach" unsinnig.
    Sie zaehlen weiter fuer den Schwerpunkt (ihre Koordinaten sind die des Orts).

Die Ausgabe ist deterministisch (gleiche Zips -> byte-gleiche Dateien, LF-Zeilenenden). Der
"Stand" ist das Dateidatum im Zip, nicht das heutige Datum - ein erneuter Lauf mit denselben
Daten erzeugt keinen Diff.

Lizenz: GeoNames, CC BY 4.0 - licenses/GeoNames-CC-BY-4.0.txt.
"""
import argparse
import math
import os
import statistics
import sys
import zipfile
from collections import defaultdict, namedtuple

Region = namedtuple("Region", "praefix breite laenge name")

AUSREISSER_KM = 150.0
DREI_STELLEN_LAENDER = {"DE"}
GROESSE_WARNUNG_BYTES = 30 * 1024

# GeoNames-Spalten (0-basiert)
SP_LAND, SP_PLZ, SP_ORT, SP_BREITE, SP_LAENGE, SP_GENAUIGKEIT = 0, 1, 2, 9, 10, 11


def _km(b1, l1, b2, l2):
    """Abstand per Equirectangular-Naeherung - fuer 150 km Schwelle mehr als genau genug."""
    x = math.radians(l2 - l1) * math.cos(math.radians((b1 + b2) / 2))
    y = math.radians(b2 - b1)
    return 6371.0 * math.hypot(x, y)


def _lies_zeilen(zip_pfad, land):
    with zipfile.ZipFile(zip_pfad) as z:
        roh = z.read(land + ".txt").decode("utf-8")
    for zeile in roh.splitlines():
        teile = zeile.split("\t")
        if len(teile) < 12 or teile[SP_LAND] != land:
            continue
        if not teile[SP_GENAUIGKEIT].strip():
            continue  # Grosskunde / Postfach, siehe Modul-Kopf Punkt 1
        plz = teile[SP_PLZ].strip()
        if not plz.isdigit():
            continue
        try:
            breite = float(teile[SP_BREITE])
            laenge = float(teile[SP_LAENGE])
        except ValueError:
            continue
        yield plz, teile[SP_ORT].strip(), breite, laenge


def _plz_punkte(zip_pfad, land):
    """PLZ -> (breite, laenge, [(ort, breite, laenge), ...]), je PLZ gemittelt."""
    zeilen = defaultdict(list)
    for plz, ort, breite, laenge in _lies_zeilen(zip_pfad, land):
        zeilen[plz].append((ort, breite, laenge))
    punkte = {}
    for plz in sorted(zeilen):
        orte = zeilen[plz]
        punkte[plz] = (
            sum(o[1] for o in orte) / len(orte),
            sum(o[2] for o in orte) / len(orte),
            orte,
        )
    return punkte


def _ist_postfach(ort):
    return "postfach" in ort.lower()


def _region(praefix, plz_liste, punkte):
    med_b = statistics.median(punkte[p][0] for p in plz_liste)
    med_l = statistics.median(punkte[p][1] for p in plz_liste)
    behalten = [p for p in plz_liste if _km(med_b, med_l, punkte[p][0], punkte[p][1]) <= AUSREISSER_KM]
    if not behalten:
        return None
    breite = sum(punkte[p][0] for p in behalten) / len(behalten)
    laenge = sum(punkte[p][1] for p in behalten) / len(behalten)
    # Postfach-Namen nachrangig (Modul-Kopf Punkt 5): erst eine PLZ mit echtem Ortsnamen
    naechste = min(behalten, key=lambda p: (
        all(_ist_postfach(o[0]) for o in punkte[p][2]),
        _km(breite, laenge, punkte[p][0], punkte[p][1]), p))
    orte = punkte[naechste][2]
    name = min(orte, key=lambda o: (_ist_postfach(o[0]), _km(breite, laenge, o[1], o[2]), o[0]))[0]
    return Region(praefix, breite, laenge, name)


def regionen_aus_zip(zip_pfad, land, mit_drei_stellen):
    """Alle Regionen eines Landes, nach Praefix sortiert."""
    punkte = _plz_punkte(zip_pfad, land)
    laengen = (2, 3) if mit_drei_stellen else (2,)
    gruppen = defaultdict(list)
    for plz in punkte:
        for n in laengen:
            if len(plz) > n:
                gruppen[plz[:n]].append(plz)
    regionen = []
    for praefix in sorted(gruppen):
        r = _region(praefix, gruppen[praefix], punkte)
        if r is not None:
            regionen.append(r)
    return regionen


def als_tsv(regionen, land, stand):
    kopf = [
        "# Quelle: GeoNames (www.geonames.org), Postleitzahlen %s - Lizenz CC BY 4.0 "
        "(https://creativecommons.org/licenses/by/4.0/)" % land,
        "# Aggregiert zu Regions-Schwerpunkten von tools/tageslicht/baue_plz_tabelle.py - nicht von Hand bearbeiten",
        "# Stand %s" % stand,
        "# praefix\tbreite\tlaenge\tname",
    ]
    daten = ["%s\t%.2f\t%.2f\t%s" % (r.praefix, r.breite, r.laenge, r.name)
             for r in sorted(regionen, key=lambda r: r.praefix)]
    return "\n".join(kopf + daten) + "\n"


def _stand_aus_zip(zip_pfad, land):
    with zipfile.ZipFile(zip_pfad) as z:
        jahr, monat, tag = z.getinfo(land + ".txt").date_time[:3]
    return "%04d-%02d-%02d" % (jahr, monat, tag)


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("de_zip")
    p.add_argument("at_zip")
    p.add_argument("ch_zip")
    p.add_argument("--ziel", required=True, help="Zielordner, z.B. app/src/main/assets/tageslicht")
    p.add_argument("--stand", help="Datum im Kopf (Standard: Dateidatum im Zip)")
    a = p.parse_args(argv)

    eingaben = [("DE", a.de_zip), ("AT", a.at_zip), ("CH", a.ch_zip)]
    for land, pfad in eingaben:
        if os.path.basename(pfad).upper() != land + ".ZIP":
            p.error("%s erwartet als %s.zip, bekommen: %s" % (land, land, pfad))

    os.makedirs(a.ziel, exist_ok=True)
    for land, pfad in eingaben:
        regionen = regionen_aus_zip(pfad, land, mit_drei_stellen=land in DREI_STELLEN_LAENDER)
        text = als_tsv(regionen, land, a.stand or _stand_aus_zip(pfad, land))
        ziel = os.path.join(a.ziel, "plz_%s.tsv" % land.lower())
        with open(ziel, "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
        groesse = len(text.encode("utf-8"))
        print("%s: %d Regionen, %d Bytes -> %s" % (land, len(regionen), groesse, ziel))
        if groesse > GROESSE_WARNUNG_BYTES:
            print("WARNUNG: %s ist groesser als %d Bytes" % (ziel, GROESSE_WARNUNG_BYTES), file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
