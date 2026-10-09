"""Prueft, dass die Hilfe in der App und die Anleitung auf der Website zusammenpassen.

WARUM ES DAS GIBT
-----------------
Seit 1.47.0 verweist die App an vielen Stellen auf die Website (`docs/`): das "?" in den
Kopfzeilen auf die Anleitung, "Mehr dazu" unter den Warnungen der Status-Karten auf die
Problemhilfe. Der Text existiert damit genau einmal - aber die Verbindung kann still reissen,
und zwar in zwei Richtungen:

1. ANKER: Wird eine Ueberschrift umbenannt und ihre `id` dabei geaendert, oeffnet der Browser
   kommentarlos den Seitenanfang. Niemand merkt es. Geprueft wird deshalb jeder Anker aus
   `ui/hilfe/HilfeThema.kt` gegen die `id`s der genannten Datei, dazu die Domain gegen
   `docs/CNAME`.

2. BEGRIFFE: Die Anleitung setzt Bedienelemente in „Anfuehrungszeichen". Benennt die App einen
   Knopf um, beschreibt die Website einen, den es nicht mehr gibt - genau der Drift, den 1.46.2
   in der App selbst aufraeumen musste. Geprueft wird, dass jeder solche Begriff irgendwo in
   `app/src/main` vorkommt. Was nicht aus der App stammt (Android-Systemeinstellungen,
   Beispielnamen), steht in `hilfe_begriffe_ausserhalb.txt`.

Fuer Begriffe ist das eine untere Schranke: ein Treffer belegt, dass es das Wort gibt, nicht,
dass der Satz drumherum noch stimmt.

    python tools/doku/pruefe_hilfe.py      Exit 1 bei einem Befund.
"""
import glob
import io
import os
import re
import sys

WURZEL = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
HILFE_THEMA = os.path.join(
    WURZEL, "app", "src", "main", "java", "com", "github", "f1rlefanz",
    "cf_alarmfortimeoffice", "ui", "hilfe", "HilfeThema.kt",
)
DOCS = os.path.join(WURZEL, "docs")
GEPRUEFTE_SEITEN = ("advanced-setup.html", "troubleshooting.html")
AUSNAHMEN = os.path.join(os.path.dirname(os.path.abspath(__file__)), "hilfe_begriffe_ausserhalb.txt")
BEGRIFF = re.compile(r"„([^“<]{3,40})“")


def lies(pfad):
    with io.open(pfad, encoding="utf-8") as f:
        return f.read()


def pruefe_anker(befunde):
    quelle = lies(HILFE_THEMA)
    seiten = dict(re.findall(r'^\s*([A-Z_]+)\("([^"]+\.html)"\)', quelle, re.M))
    themen = re.findall(r'^\s*([A-Z_]+)\(HilfeSeite\.([A-Z_]+),\s*(?:"([^"]*)"|null)\)', quelle, re.M)
    if not seiten or not themen:
        befunde.append("HilfeThema.kt: keine Seiten oder Themen erkannt - Format geaendert?")
        return
    for thema, seite, anker in themen:
        datei = seiten.get(seite)
        pfad = os.path.join(DOCS, datei or "")
        if not datei or not os.path.isfile(pfad):
            befunde.append(f"{thema}: Seite {seite} ({datei}) gibt es unter docs/ nicht")
        elif anker and f'id="{anker}"' not in lies(pfad):
            befunde.append(f'{thema}: docs/{datei} hat kein id="{anker}"')
    basis = re.search(r'BASIS_URL = "https://([^/"]+)/"', quelle)
    cname = lies(os.path.join(DOCS, "CNAME")).strip()
    if not basis or basis.group(1) != cname:
        befunde.append(f"HilfeThema.BASIS_URL passt nicht zu docs/CNAME ({cname})")


def app_texte():
    teile = []
    for muster in ("**/*.kt", "**/*.xml"):
        for pfad in glob.glob(os.path.join(WURZEL, "app", "src", "main", muster), recursive=True):
            teile.append(lies(pfad))
    return "\n".join(teile).replace("\'", "'")


def pruefe_begriffe(befunde):
    ausnahmen = {
        z.strip() for z in lies(AUSNAHMEN).splitlines() if z.strip() and not z.startswith("#")
    }
    texte = app_texte()
    for seite in GEPRUEFTE_SEITEN:
        for begriff in sorted(set(BEGRIFF.findall(lies(os.path.join(DOCS, seite))))):
            if begriff not in ausnahmen and begriff not in texte:
                befunde.append(f"docs/{seite}: „{begriff}“ kommt in der App nicht vor")


def main():
    befunde = []
    pruefe_anker(befunde)
    pruefe_begriffe(befunde)
    if befunde:
        print("Hilfe und App passen nicht zusammen:")
        for b in befunde:
            print("  - " + b)
        print("Anker: id auf der Seite behalten oder HilfeThema.kt nachziehen. Begriffe: die "
              "Website an die App anpassen - oder, wenn er nicht aus der App stammt, in "
              "tools/doku/hilfe_begriffe_ausserhalb.txt eintragen.")
        return 1
    return 0


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main())
