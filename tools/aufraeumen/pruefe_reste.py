#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Sucht die Reste, die ein Aufraeumdurchgang typischerweise hinterlaesst.

WARUM ES DIESES WERKZEUG GIBT (25.08.2026): In einer einzigen Sitzung wurde funfmal
hintereinander "fertig" gemeldet und funfmal etwas gefunden - tote Importe, haengende
Kommentare, KDoc-Verweise ins Leere, verwaiste Doku-Aussagen in FREMDEN Skills. Gefunden hat sie
jedes Mal ein von Hand getipptes Wegwerf-Skript, das nach dem Sitzungsende verschwand. Damit war
dieselbe Wette abgeschlossen, die dieses Projekt schon einmal verloren hat: die Regel "Dokumente
lebendig halten" stand jahrelang als Prosa da und scheiterte, bis `tools/doku/pruefe_budget.py`
daraus eine Messung machte.

Deshalb steht das hier - nicht als guter Vorsatz, sondern als Gatter in der Schleuse.

SIEBEN PRUEFUNGEN, alle mit dem Anspruch NAHEZU KEINER FEHLALARME. Ein Gatter, das haeufig falsch
meldet, wird weggeklickt und schuetzt dann gar nichts mehr (siehe die Triage-Lehre in
GitHub-Issue #18: 97 von 344 Meldungen waren Fehlalarm, und genau die verdeckten den Einzelfall).

  1. TOTE IMPORTE - mechanisch, blockierend.
  2. VERWAISTE STRING-RESSOURCEN - mechanisch, blockierend. Ein Nutzertext ohne Anzeige ist die
     Ruckseite der Projektregel "eine Faehigkeit ohne Bedienoberflaeche gibt es nicht".
  3. DOKU-VERWEISE AUF FRISCH ENTFERNTE SYMBOLE - blockierend, aber eng gefasst (siehe unten).
  4. COMPOSE-KARTEN, DIE NUR IHRE EIGENE VORSCHAU KENNT - blockierend. Ein `@Preview` ist kein
     Verbraucher; er ist Werkzeug der IDE und haelt nichts am Leben.
  5. KDOC-BLOECKE, DIE NICHTS MEHR BESCHREIBEN - blockierend. Die Narbe, die ein Aufraeumlauf
     hinterlaesst, wenn er die Deklaration entfernt und ihre Doku vergisst.
  6. EIGENSCHAFTEN OHNE VERWENDER (val, var, const val) - blockierend, mit Begruendungszwang
     im eigenen KDoc. Nicht nur `const val`: ein `dp`-Wert kann gar keine Compile-Zeit-Konstante
     sein, und genau dort lag eine tote Konstante.
  7. KONFIGURATIONSDATEIEN, DIE EIN STRIKTER PARSER NICHT LIEST - blockierend. Statisch
     entscheidbar, deshalb ohne Ermessensspielraum: eine Datei parst oder sie parst nicht.

Aufruf:
    python tools/aufraeumen/pruefe_reste.py            # alles, Ausgabe fuer Menschen
    python tools/aufraeumen/pruefe_reste.py --ci       # nur Fehler, Exit 1 bei Befund
    python tools/aufraeumen/pruefe_reste.py --basis <commit>   # Doku-Check gegen diese Basis
"""

import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

WURZEL = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
QUELLEN = os.path.join(WURZEL, "app", "src")
STRINGS = os.path.join(QUELLEN, "main", "res", "values", "strings.xml")
DOKU_ORTE = [os.path.join(WURZEL, "CLAUDE.md"), os.path.join(WURZEL, ".claude", "skills")]

# ---------------------------------------------------------------------------------------------
# DIE FALLE, DIE DIESES WERKZEUG VOR ALLEM ABSICHERN MUSS
#
# Ein reiner Referenz-Grep meldet Operator- und Delegations-Importe als ungenutzt: `getValue` und
# `setValue` stehen nie im Rumpf, sie werden von `by` aufgerufen. Beim ersten Handlauf am
# 25.08.2026 waren SIEBEN von zehn Treffern genau das - haette man sie geloescht, haette nichts
# mehr kompiliert. Dieselbe Klasse: `provideDelegate`, die arithmetischen Operatoren und
# `component1/2` fuer Destructuring.
# ---------------------------------------------------------------------------------------------
OPERATOR_IMPORTE = {
    "getValue", "setValue", "provideDelegate",
    "invoke", "plus", "minus", "times", "div", "rem", "unaryMinus", "unaryPlus", "not",
    "compareTo", "contains", "iterator", "rangeTo", "equals", "hashCode",
    "inc", "dec", "plusAssign", "minusAssign", "timesAssign", "divAssign",
}
OPERATOR_IMPORTE.update("component%d" % i for i in range(1, 10))


def _lies(pfad):
    try:
        with open(pfad, encoding="utf-8", errors="replace") as f:
            return f.read()
    except OSError:
        return ""


def _kotlin_dateien():
    for wurzel, _, dateien in os.walk(QUELLEN):
        for name in dateien:
            if name.endswith(".kt"):
                yield os.path.join(wurzel, name)


def _relativ(pfad):
    return os.path.relpath(pfad, WURZEL).replace(os.sep, "/")


# ---------------------------------------------------------------------------------------------
# 1. Tote Importe
# ---------------------------------------------------------------------------------------------
def tote_importe_in(text):
    """Die eigentliche Entscheidung - REIN, damit sie ohne Repository pruefbar ist.

    Liefert die Import-Zeilen, deren Name im Rumpf nicht mehr vorkommt.
    """
    ergebnis = []
    treffer = re.search(r"((?:^import [^\n]*\n)+)", text, re.M)
    if not treffer:
        return ergebnis
    rumpf = text[treffer.end():]
    for zeile in treffer.group(1).splitlines():
        zeile = zeile.strip()
        if zeile.endswith("*"):              # Wildcard-Import: nicht entscheidbar
            continue
        alias = re.match(r"import .+ as (\w+)$", zeile)
        name = alias.group(1) if alias else None
        if name is None:
            einfach = re.match(r"import (?:.*\.)?(\w+)$", zeile)
            if not einfach:
                continue
            name = einfach.group(1)
        if name in OPERATOR_IMPORTE:
            continue
        # WORTGRENZEN SIND HIER TRAGEND: ohne sie haelt `rememberCoroutineScope` den Import
        # `remember` am Leben, und ein echter toter Import bleibt unentdeckt.
        if not re.search(r"\b" + re.escape(name) + r"\b", rumpf):
            ergebnis.append(zeile)
    return ergebnis


def pruefe_tote_importe(befunde):
    for pfad in _kotlin_dateien():
        for zeile in tote_importe_in(_lies(pfad)):
            befunde.append("Toter Import: {}: {}".format(_relativ(pfad), zeile))


# ---------------------------------------------------------------------------------------------
# 2. Verwaiste String-Ressourcen
# ---------------------------------------------------------------------------------------------
def pruefe_verwaiste_strings(befunde):
    roh = _lies(STRINGS)
    if not roh:
        return
    namen = re.findall(r'<string name="([^"]+)"', roh)

    verwender = []
    for wurzel, _, dateien in os.walk(QUELLEN):
        for datei in dateien:
            pfad = os.path.join(wurzel, datei)
            if os.path.abspath(pfad) == os.path.abspath(STRINGS):
                continue
            if datei.endswith((".kt", ".xml")):
                verwender.append(_lies(pfad))
    alles = "\n".join(verwender)

    for name in namen:
        benutzt = re.search(r"\bR\.string\." + re.escape(name) + r"\b", alles) or \
            ("@string/" + name) in alles
        if not benutzt:
            befunde.append(
                "Verwaiste String-Ressource: {} - kein R.string- und kein @string-Verwender".format(name)
            )


# ---------------------------------------------------------------------------------------------
# 3. Doku-Verweise auf frisch entfernte Symbole
#
# WARUM SO ENG GEFASST: Ein naiver Abgleich "Doku nennt `foo()`, Code hat kein `fun foo`" liefert
# in diesem Projekt 54 Treffer, davon 53 Fehlalarm - die Doku nennt voellig zu Recht
# Plattform-APIs wie `startForeground()` oder `setAlarmClock()`, die es in unserem Quelltext nie
# als eigene Funktion gab. Gemessen am 25.08.2026.
#
# Die brauchbare Frage ist eine andere und viel schaerfere: Hat DIESE Aenderung ein Symbol
# entfernt, das die Doku noch nennt? Damit faellt die gesamte Plattform-API weg (die wurde nie
# entfernt, weil sie nie hier stand), und uebrig bleibt genau die Fehlerklasse, die real
# aufgetreten ist: Nach dem Ein-Modell-Umbau standen `wellnessEnabled` und `nightDefaultEnabled`
# noch in DREI Skills - darunter zwei, die der Umbau nie geoeffnet hatte.
#
# HISTORISCHE ERWAEHNUNGEN SIND ABSICHT und werden ausgenommen: Eine Zeile, die erklaert, was
# entfernt WURDE, ist der Hergang - ohne ihn baut die naechste Sitzung dieselbe Falle nach. Sie
# ist an ihren Signalwoertern erkennbar (siehe HISTORISCH).
# ---------------------------------------------------------------------------------------------
HISTORISCH = re.compile(
    r"ENTFERNT|entfernt|entfallen|abgel(oe|ö)st|frueher|früher|bis v\d|"
    r"nicht mehr|KEIN |KEINE |damalig|ehemal|seit v\d",
    re.I,
)

SYMBOL_DEFINITION = re.compile(
    r"^[-+]\s*(?:.*\b)?(?:fun|val|var|class|object|interface|enum class)\s+([A-Za-z_]\w*)"
)


def _git(*args):
    try:
        fertig = subprocess.run(
            ["git"] + list(args), cwd=WURZEL, capture_output=True, text=True,
            encoding="utf-8", errors="replace", timeout=60,
        )
        return fertig.returncode, fertig.stdout
    except (OSError, subprocess.SubprocessError):
        return 1, ""


def _absaetze(zeilen):
    """Grenzen zusammenhaengender Bloecke: Leerzeile trennt, ein neuer Aufzaehlungspunkt auch.

    Ein Skill-Eintrag ist typischerweise EIN Aufzaehlungspunkt ueber mehrere Zeilen. Nur die
    Leerzeile als Trenner zu nehmen wuerde benachbarte Punkte zusammenziehen und damit die
    Ausnahme des einen auf den anderen uebertragen.
    """
    start = None
    for i, zeile in enumerate(zeilen):
        neuer_punkt = re.match(r"^\s*[-*]\s", zeile) or re.match(r"^#{1,6}\s", zeile)
        if not zeile.strip():
            if start is not None:
                yield start, i
                start = None
        elif neuer_punkt and start is not None:
            yield start, i
            start = i
        elif start is None:
            start = i
    if start is not None:
        yield start, len(zeilen)


def pruefe_doku_verweise(befunde, basis):
    if not basis:
        return
    code, diff = _git("diff", "-U0", basis + "..HEAD", "--", "app/src")
    if code != 0 or not diff:
        return

    entfernt = set()
    for zeile in diff.splitlines():
        if not zeile.startswith("-") or zeile.startswith("---"):
            continue
        treffer = SYMBOL_DEFINITION.match(zeile)
        if treffer:
            entfernt.add(treffer.group(1))
    if not entfernt:
        return

    # Nur, was es HEUTE nirgends mehr im Quelltext gibt (umbenannt/verschoben zaehlt nicht).
    quelltext = "\n".join(_lies(p) for p in _kotlin_dateien())
    verschwunden = {
        s for s in entfernt
        if not re.search(r"\b(?:fun|val|var|class|object|interface)\s+" + re.escape(s) + r"\b", quelltext)
        # PARAMETERNAMEN ZAEHLEN MIT, und das ist kein Detail: Am 25.08.2026 entfernte ein
        # Durchgang das Feld `ShiftInfo.eventTitle` - und dieses Gatter meldete daraufhin zwei
        # Skill-Zeilen, die `matchesKeywords(eventTitle)` nennen. Diese Funktion gibt es
        # weiterhin; die Doku meinte ihren PARAMETER, nicht das geloeschte Feld. Ohne diese
        # Zeile blockiert das Gatter eine voellig richtige Notiz und verlangt, sie
        # kaputtzumachen - der sichere Weg, ein Gatter unglaubwuerdig zu machen.
        and not re.search(r"[(,]\s*(?:@\w+\s+)?" + re.escape(s) + r"\s*:", quelltext)
    }
    if not verschwunden:
        return

    for ort in DOKU_ORTE:
        dateien = []
        if os.path.isfile(ort):
            dateien = [ort]
        else:
            for wurzel, _, namen in os.walk(ort):
                dateien += [os.path.join(wurzel, n) for n in namen if n.endswith(".md")]
        for pfad in dateien:
            zeilen = _lies(pfad).splitlines()
            # ABSATZWEISE, nicht zeilenweise. Der Hergang-Eintrag zu einer Entfernung geht ueber
            # mehrere Zeilen, und das Signalwort ("entfernt", "bis v1.33") steht selten in
            # DERSELBEN Zeile wie das Symbol. Beim ersten Wurf dieses Werkzeugs waren dadurch
            # SECHS von neun Treffern Fehlalarm - ausgerechnet die Absaetze, die die Entfernung
            # sauber dokumentieren. Ein Gatter mit dieser Quote wird weggeklickt.
            for start, ende in _absaetze(zeilen):
                if HISTORISCH.search("\n".join(zeilen[start:ende])):
                    continue
                for nr in range(start, ende):
                    for symbol in verschwunden:
                        if _als_symbol_genannt(zeilen[nr], symbol):
                            befunde.append(
                                "Doku nennt entferntes Symbol `{}`: {}:{}".format(
                                    symbol, _relativ(pfad), nr + 1
                                )
                            )


def _als_symbol_genannt(zeile, symbol):
    """Steht das Wort in einer Code-Spanne wirklich als SYMBOL - oder nur als Wort darin?

    WARUM DIESE UNTERSCHEIDUNG (25.08.2026): Nach dem Entfernen von `HueBridgeConfig.whitelist`
    meldete das Gatter eine Skill-Zeile mit `cmd deviceidle whitelist -<pkg>` - einem
    adb-BEFEHL. Voellig richtige Notiz, nichts mit unserem Feld zu tun. Backticks bedeuten in
    dieser Doku "wortwoertlich", nicht "Kotlin-Symbol": Shell-Kommandos, Dateipfade und
    Log-Ausschnitte stehen genauso darin.

    Die Unterscheidung, die traegt: Ein Symbol steht an einem Punkt, einer Klammer oder allein -
    ein Wort in einem Kommando steht zwischen Leerzeichen.
    """
    for spanne in re.findall(r"`([^`]*)`", zeile):
        for treffer in re.finditer(r"\b" + re.escape(symbol) + r"\b", spanne):
            davor = spanne[treffer.start() - 1] if treffer.start() else ""
            danach = spanne[treffer.end()] if treffer.end() < len(spanne) else ""
            if davor in ("", ".", "(", "[") and danach in ("", ".", "(", ")", ",", "["):
                return True
    return False


# ---------------------------------------------------------------------------------------------
# 4. Compose-Karten, die nur ihre eigene Vorschau kennt
#
# HERGANG (25.08.2026): `NoAlarmCard` - eine Karte mit eigener versiegelter Grund-Hierarchie,
# 274 Zeilen - hatte NIE einen Aufrufer. Nicht seit dem Umbau, sondern seit dem Initial-Commit,
# und sie hat zwei ausdrueckliche Aufraeum-Commits ueberlebt. Gefunden hat sie keine
# Namenszaehlung, sondern erst die Frage nach dem VERBRAUCHER.
#
# DIE ENTSCHEIDENDE FEINHEIT: Ein `@Preview` zaehlt NICHT als Verbraucher. Er ist Werkzeug der
# IDE und haelt nichts am Leben - genau daran lief die erste Messung blind vorbei (`NoAlarmCard`
# galt als benutzt, weil `NoAlarmCardPreview` sie aufruft). Ebenso muss der Trailing-Lambda-Aufruf
# (`CFAlarmForTimeOfficeTheme { ... }`, ohne Klammern) zaehlen, sonst meldet der Check ein
# offensichtlich benutztes Theme.
#
# Mit beiden Feinheiten: EIN Treffer im ganzen Baum, kein Fehlalarm. Ohne sie: einer von beiden
# falsch. Deshalb ist diese Klasse gatterfaehig - anders als "ungenutzte Funktion" ueber
# Namensreferenzen (222 Kandidaten, praktisch alle falsch; siehe Skill
# `cfalarm-arbeit-abschliessen`).
# ---------------------------------------------------------------------------------------------
COMPOSABLE_DEF = re.compile(
    r"@Composable\s*(?:@[\w.]+(?:\([^)]*\))?\s*)*\n\s*(?:private |internal |)fun\s+(\w+)\s*\("
)


def _preview_bereiche(text):
    """Zeilenbereiche aller mit `@Preview` annotierten Funktionen."""
    zeilen = text.split("\n")
    bereiche = []
    for i, zeile in enumerate(zeilen):
        if "@Preview" not in zeile:
            continue
        j = i
        while j < len(zeilen) and not re.search(r"\bfun\s+\w+\s*\(", zeilen[j]):
            j += 1
        if j >= len(zeilen):
            continue
        tiefe, gestartet = 0, False
        for k in range(j, len(zeilen)):
            tiefe += zeilen[k].count("{") - zeilen[k].count("}")
            if "{" in zeilen[k]:
                gestartet = True
            if gestartet and tiefe <= 0:
                bereiche.append((i, k))
                break
    return bereiche


def pruefe_composable_ohne_verbraucher(befunde):
    dateien = [(p, _lies(p)) for p in _kotlin_dateien()]
    vorschau = {p: _preview_bereiche(t) for p, t in dateien}

    for pfad, text in dateien:
        if os.sep + "main" + os.sep not in pfad:
            continue
        for treffer in COMPOSABLE_DEF.finditer(text):
            name = treffer.group(1)
            eigene_zeile = text[:treffer.start()].count("\n")
            if any(a <= eigene_zeile <= b for a, b in vorschau[pfad]):
                continue
            rufe = 0
            for pfad2, text2 in dateien:
                for aufruf in re.finditer(r"\b" + re.escape(name) + r"\s*[({]", text2):
                    zeile = text2[:aufruf.start()].count("\n")
                    if any(a <= zeile <= b for a, b in vorschau[pfad2]):
                        continue
                    if re.search(r"fun\s+$", text2[:aufruf.start()]):
                        continue
                    rufe += 1
            if rufe == 0:
                befunde.append(
                    "Compose-Karte ohne Verbraucher: {} in {} - nur die eigene Vorschau "
                    "ruft sie auf".format(name, _relativ(pfad))
                )


# ---------------------------------------------------------------------------------------------
# 5. KDoc-Bloecke, die nichts mehr beschreiben
#
# HERGANG (25.08.2026): Der Aufraeumlauf v1.34.3 entfernte 35 ungenutzte Konstanten - und liess
# ELF ihrer KDoc-Kommentare stehen. Zurueck blieben Zeilen wie `/** Halbe Breite fuer zweispaltige
# Layouts */`, direkt gefolgt von der schliessenden Klammer, plus zwei Objekte, die dadurch leer
# dastanden. Ein Aufraeumen, das seine eigenen Narben hinterlaesst.
#
# ENG GEFASST, WEIL DIE WEITE FASSUNG NICHT TRAEGT: "KDoc, dem keine Deklaration folgt" liefert
# 79 Treffer, ganz ueberwiegend falsch - Enum-Eintraege (`MASTER_PAUSE,`), Konstruktor-Parameter
# und der voellig normale Fall "Datei-KDoc, dann Klassen-KDoc". Eindeutig ist nur: Auf den Block
# folgt NICHTS MEHR ausser einer schliessenden Klammer. Damit blieben nach der Bereinigung null
# Treffer, vorher elf.
# ---------------------------------------------------------------------------------------------
def haengende_kdocs_in(text):
    """Zeilennummern (1-basiert) von KDoc-Bloecken, hinter denen nur noch eine Klammer steht."""
    zeilen = text.split("\n")
    treffer = []
    i = 0
    while i < len(zeilen):
        if not zeilen[i].strip().startswith("/**"):
            i += 1
            continue
        j = i
        while j < len(zeilen) and not zeilen[j].strip().endswith("*/"):
            j += 1
        k = j + 1
        # Leerzeilen und Zeilenkommentare stehen oft zwischen Doku und Klammer - sie sind selbst
        # keine Deklaration und duerfen den Befund nicht verdecken.
        while k < len(zeilen) and (not zeilen[k].strip() or zeilen[k].strip().startswith("//")):
            k += 1
        if k >= len(zeilen) or zeilen[k].strip() in ("}", ")"):
            treffer.append(i + 1)
        i = j + 1
    return treffer


def pruefe_haengende_kdocs(befunde):
    for pfad in _kotlin_dateien():
        for nr in haengende_kdocs_in(_lies(pfad)):
            befunde.append(
                "KDoc ohne Deklaration: {}:{} - beschreibt nichts mehr".format(_relativ(pfad), nr)
            )


# ---------------------------------------------------------------------------------------------
# 6. Konstanten ohne jeden Verwender
#
# HERGANG (25.08.2026): Sieben von 48 Log-Tags hatte nie jemand benutzt - weder ueber `LogTags.X`
# noch als Zeichenkette. Dazu sechs weitere Konstanten in den Gestaltungs-Dateien. Die
# "fertige API fuer spaeter"-Falle, gegen die dieses Projekt schon zweimal angetreten ist.
#
# EINE AUSNAHME BRAUCHT EINEN GRUND IM CODE: `WICHTIGKEIT_NIEDRIG` bleibt bewusst ohne Verwender -
# sie benennt den Wert, auf dem der Weckerkanal von v1.9.7 bis v1.29.0 unbemerkt stand. Solche
# Faelle traegt man nicht in eine Liste am Rand, sondern ins KDoc der Konstante selbst: wer den
# Text `OHNE VERWENDER` in ihrer Doku schreibt, hat die Entscheidung getroffen und begruendet.
# Alles andere blockiert.
# ---------------------------------------------------------------------------------------------
# NICHT NUR `const val`, UND DAS WAR EINE ECHTE LUECKE: In Runde 7 fiel `CARD_ELEVATION = 4.dp`
# auf - ein `val`, kein `const val` (ein `dp`-Wert KANN keine Compile-Zeit-Konstante sein). Der
# ganze `SpacingConstants`-Block lag damit ausserhalb dieser Pruefung. Deshalb jetzt jede
# Eigenschaft auf Klassenebene.
#
# GEMESSEN, BEVOR ES EIN GATTER WURDE: Diese weite Fassung liefert 26 Treffer, davon 23 Felder von
# Hue-JSON-Modellen. Die waren aber NICHT harmlos - `BridgeCapabilities`, `HueUser` und der ganze
# `LightCapabilities`-Teilbaum wurden nie erzeugt und nie gelesen. Nach dem Entfernen bleiben
# DREI, und zwei davon tragen ihre Begruendung im eigenen KDoc. Die Quote stimmt also erst,
# seit der Baum aufgeraeumt ist - eine weite Pruefung auf einem ungeraeumten Baum waere ein
# Fehlalarm-Generator gewesen.
KONSTANTE = re.compile(
    r"^[ \t]{4}(?:private |internal |)(?:@\w+\s+)?(?:const )?(?:val|var) (\w+)\s*[:=]", re.M
)
BEWUSST_OHNE_VERWENDER = "OHNE VERWENDER"


BEZEICHNER = re.compile(r"[A-Za-z_]\w*")
DEKLARATION = re.compile(r"\b(?:val|var)\s+([A-Za-z_]\w*)")


def pruefe_ungenutzte_konstanten(befunde):
    """EIN Index statt verschachtelter Schleifen.

    Die naive Fassung (jede Eigenschaft gegen jede Datei) lief nach der Erweiterung von
    `const val` auf alle Eigenschaften in die 60-s-Grenze der Schleuse: 1039 Eigenschaften mal
    ~350 Dateien mal einem eigenen regulaeren Ausdruck. Hier wird der Baum EINMAL in Bezeichner
    zerlegt; danach ist jede Frage ein Nachschlagen. Gleiche Aussage, Laufzeit in Sekunden.
    """
    dateien = [(p, _lies(p)) for p in _kotlin_dateien()]

    vorkommen = {}
    deklarationen = {}
    for _, text in dateien:
        for wort in BEZEICHNER.findall(text):
            vorkommen[wort] = vorkommen.get(wort, 0) + 1
        for name in DEKLARATION.findall(text):
            deklarationen[name] = deklarationen.get(name, 0) + 1

    for pfad, text in dateien:
        if os.sep + "main" + os.sep not in pfad:
            continue
        for treffer in KONSTANTE.finditer(text):
            name = treffer.group(1)
            # Der KDoc-Block unmittelbar davor - dort steht die Begruendung, wenn es eine gibt.
            davor = text[:treffer.start()]
            block = davor[davor.rfind("/**"):] if "/**" in davor[-1200:] else ""
            if BEWUSST_OHNE_VERWENDER in block:
                continue
            # Jede Nennung ausser den Deklarationen selbst ist ein Verwender.
            if vorkommen.get(name, 0) - deklarationen.get(name, 0) == 0:
                befunde.append(
                    "Eigenschaft ohne Verwender: {} in {}:{} - loeschen, oder die Entscheidung "
                    "mit '{}' im eigenen KDoc begruenden".format(
                        name, _relativ(pfad), text[:treffer.start()].count(chr(10)) + 2,
                        BEWUSST_OHNE_VERWENDER
                    )
                )


# ---------------------------------------------------------------------------------------------
# 7. Konfigurationsdateien, die ein strikter Parser nicht liest
#
# HERGANG (31.08.2026): `app/lint.xml` war kein wohlgeformtes XML. In Zeile 15 stand Gradles
# Offline-Schalter ausgeschrieben, und seine zwei Bindestriche duerfen innerhalb eines
# XML-Kommentars nicht vorkommen. Android Lint nahm die Datei trotzdem klaglos an, saemtliche
# Unterdrueckungen wirkten weiter - es gab also nie einen Anlass hinzusehen. Gemerkt hat es erst
# das erste Werkzeug, das sie PARSEN wollte, und das musste sich deswegen mit regulaeren
# Ausdruecken behelfen. "Es funktioniert ja" ist kein Beleg fuer Wohlgeformtheit; das naechste
# Werkzeug zahlt.
#
# GEMESSEN, BEVOR ES EIN GATTER WURDE: 36 Konfigurationsdateien unter `git ls-files` (27 XML,
# 2 JSON, 1 TOML, 6 YAML), 1 ROHBEFUND, 0 FEHLALARME. Das ist die Eigenschaft, die diese Klasse
# gatterfaehig macht: sie ist statisch entscheidbar. Verworfen wurden bisher fast nur
# Blickwinkel, die ABSICHT erraten mussten (siehe die Tabelle im Skill
# `cfalarm-altlasten-abtragen`) - hier gibt es nichts zu erraten, und der Lauf dauert Sekunden.
#
# DER ZEITRAUM IST GEMESSEN, NICHT GESCHAETZT: jede Fassung der Datei geparst - `34abec2`
# (22.08.2025) bis `54ce3d2` (12.07.2026) wohlgeformt, ab `c445dee` (06.08.2026) kaputt. Also
# 349 Tage gut, danach gut drei Wochen kaputt. Ein frueherer Anlauf schrieb "jahrelang" in genau
# die Datei, die er wahrheitsgemaess machen sollte, und ist daran gescheitert.
#
# YAML BLEIBT DRAUSSEN: Die Standardbibliothek hat keinen YAML-Parser, und PyYAML ist hier nicht
# installiert (am 31.08.2026 nachgesehen). Alle sechs YAML-Dateien wurden einmalig von Hand
# gegengelesen und sind in Ordnung; eine Abhaengigkeit fuer eine Pruefung ohne einen einzigen
# Befund waere ein schlechter Tausch.
#
# TOML NUR, WENN DER PARSER DA IST: `tomllib` gibt es erst ab Python 3.11, und nichts sichert
# diese Version zu - der Schleusen-Hook ruft bares `python`, und `.github/workflows/ci.yml` hat
# kein `setup-python`. Am Modulkopf importiert wuerde unter 3.10 das GANZE Skript sterben, samt
# der sechs bestehenden Pruefungen, und das gemeldet als "Der Aufraeumdurchgang hat Reste
# hinterlassen" - eine Meldung, die in die voellig falsche Richtung zeigt. Deshalb lokal im
# `try`; fehlt der Parser, wird der TOML-Zweig still uebersprungen. Es geht ohnehin nur um
# `gradle/libs.versions.toml`, und das prueft Gradle bei jedem Build selbst.
# ---------------------------------------------------------------------------------------------
KONFIG_ENDUNGEN = (".xml", ".json", ".toml")

# DER AUSNAHMEWEG IST DIESE LISTE, KEIN MARKER IN DER DATEI - und das ist erzwungen, nicht
# bequem: Pruefung 3 laesst sich mit `HISTORISCH` in der Doku selbst begruenden, Pruefung 6 mit
# `OHNE VERWENDER` im eigenen KDoc. Hier geht das strukturell nicht. JSON kennt keine Kommentare,
# und in einer Datei, die der Parser nicht lesen kann, erreicht er einen Marker darin nie.
# Wer eine Datei bewusst nicht-standardkonform haelt, traegt ihren Pfad mit Begruendung hier ein.
BEWUSST_UNPARSBAR = {
    # Derzeit leer. Jeder Eintrag braucht seine Begruendung als Kommentar in derselben Zeile.
}


def konfig_fehler(pfad, roh):
    """Die eigentliche Entscheidung - REIN, damit sie ohne Repository pruefbar ist.

    `pfad` waehlt ueber die Endung den Parser und entscheidet ueber die Ausnahme, `roh` ist der
    Dateiinhalt als bytes (nicht als Text: die XML-Deklaration bestimmt die Kodierung selbst).

    Liefert die Fehlermeldung des Parsers - oder None, wenn die Datei parst, ihre Endung nicht
    geprueft wird oder sie bewusst ausgenommen ist.
    """
    if pfad in BEWUSST_UNPARSBAR:
        return None
    endung = os.path.splitext(pfad)[1].lower()
    if endung not in KONFIG_ENDUNGEN:
        return None
    try:
        if endung == ".xml":
            ET.fromstring(roh)
        elif endung == ".json":
            json.loads(roh.decode("utf-8"))
        else:
            try:
                import tomllib
            except ImportError:
                return None
            tomllib.loads(roh.decode("utf-8"))
    # ET.ParseError erbt von SyntaxError; JSONDecodeError, TOMLDecodeError und UnicodeDecodeError
    # erben von ValueError. Zusammen deckt das jeden Fehlschlag dieser drei Parser ab, ohne dass
    # ein blankes `except Exception` echte Programmierfehler im Gatter selbst verschluckt.
    except (SyntaxError, ValueError) as fehler:
        return "{}: {}".format(type(fehler).__name__, fehler)
    return None


def pruefe_konfigdateien(befunde):
    code, ausgabe = _git("ls-files")
    if code != 0:
        return
    for name in ausgabe.splitlines():
        name = name.strip()
        if not name.lower().endswith(KONFIG_ENDUNGEN):
            continue
        try:
            with open(os.path.join(WURZEL, name), "rb") as f:
                roh = f.read()
        except OSError:
            continue
        fehler = konfig_fehler(name, roh)
        if fehler:
            befunde.append(
                "Konfigurationsdatei parst nicht: {} - {}".format(name, fehler)
            )


def main():
    ci = "--ci" in sys.argv
    basis = ""
    if "--basis" in sys.argv:
        i = sys.argv.index("--basis")
        if i + 1 < len(sys.argv):
            basis = sys.argv[i + 1]

    befunde = []
    pruefe_tote_importe(befunde)
    pruefe_verwaiste_strings(befunde)
    pruefe_composable_ohne_verbraucher(befunde)
    pruefe_haengende_kdocs(befunde)
    pruefe_ungenutzte_konstanten(befunde)
    pruefe_konfigdateien(befunde)
    pruefe_doku_verweise(befunde, basis)

    if not befunde:
        if not ci:
            sys.stdout.write("Keine Reste gefunden.\n")
        return 0

    sys.stderr.write("Reste eines Aufraeumdurchgangs ({}):\n".format(len(befunde)))
    for b in sorted(befunde):
        sys.stderr.write("  - {}\n".format(b))
    sys.stderr.write(
        "\nHistorische Erwaehnungen in der Doku (\"entfernt\", \"bis v1.33\", \"entfallen\") sind\n"
        "ausgenommen und Absicht - sie sind der Hergang. Ein Verweis, der so tut, als gaebe es das\n"
        "Symbol noch, ist dagegen eine Notiz, die in die Irre fuehrt.\n"
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())
