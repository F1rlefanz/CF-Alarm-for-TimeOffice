"""Tripwires fuer Code-Invarianten, deren Bruch den Wecker kostet. Exit 1 bei Verstoss.

WARUM ES DAS GIBT
-----------------
Die Zusicherungen dieses Projekts stehen in CLAUDE.md und in .claude/skills/ - als Prosa.
Prosa hat in diesem Projekt schon zweimal nicht gehalten (17.08.2026: ein unquotiertes ': '
machte drei Skills untriggerbar; CLAUDE.md lief bis 429 Zeichen vor das Kontext-Limit).
Beide Male war jede Einzelaenderung berechtigt, und niemand hat die Wirkung im Ganzen gesehen.

Hier stehen deshalb die Zusicherungen, die sich BILLIG maschinell pruefen lassen UND deren
Bruch einen stummen oder verwaisten Wecker erzeugt. Alle halten beim Anlegen (17.08.2026) -
das ist Absicht: ein Rauchmelder wird auch nicht erst montiert, wenn es brennt.

WAS HIER NICHT HINEINGEHOERT
----------------------------
Zusicherungen, die ein Unit-Test besser prueft (davon gibt es 659), und solche, die sich nur
mit einer gepflegten Ausnahmeliste pruefen liessen - der Pflegeaufwand uebersteigt dort den
Nutzen. Direct Boot laesst sich hier prinzipiell nicht pruefen; dafuer gibt es
tools/geraet/pruefe_direct_boot.py.
"""
import glob
import io
import os
import re
import sys

QUELLEN = os.path.join("app", "src", "main")
DOKU = ["CLAUDE.md"] + sorted(glob.glob(os.path.join(".claude", "skills", "*", "SKILL.md"))) \
       + sorted(glob.glob(os.path.join(".claude", "skills", "*", "reference", "*.md")))


def kt_dateien():
    for wurzel, _, dateien in os.walk(QUELLEN):
        for d in dateien:
            if d.endswith(".kt"):
                yield os.path.join(wurzel, d)


def ist_kommentar(zeile):
    s = zeile.strip()
    return s.startswith(("//", "*", "/*"))


def ohne_kommentar(zeile):
    """Schneidet einen nachgestellten //-Kommentar ab, ohne an URLs zu scheitern."""
    if ist_kommentar(zeile):
        return ""
    pos = zeile.find("//")
    while pos != -1:
        if pos == 0 or zeile[pos - 1] != ":":       # 'https://' nicht als Kommentar lesen
            return zeile[:pos]
        pos = zeile.find("//", pos + 2)
    return zeile


def kurz(pfad):
    return pfad.replace("\\", "/").split("cf_alarmfortimeoffice/")[-1]


# ─────────────────────────── 1. corruptionHandler ───────────────────────────
def pruefe_corruption_handler():
    """Ohne corruptionHandler blockiert eine beschaedigte preferences_pb dauerhaft auch das
    SCHREIBEN - reboot-fest und ohne Selbstheilung ausser 'App-Daten loeschen'. Betroffen
    waeren u.a. Alarme, Master-Pause, Dimmer/DND und der Anmeldezustand."""
    fehler = []
    gefunden = 0
    for pfad in kt_dateien():
        zeilen = io.open(pfad, encoding="utf-8").read().split("\n")
        for i, zeile in enumerate(zeilen):
            if "preferencesDataStore(" not in ohne_kommentar(zeile):
                continue
            gefunden += 1
            umfeld = "\n".join(zeilen[i:i + 10])     # der Aufruf ist mehrzeilig
            if "corruptionHandler" not in umfeld:
                fehler.append(f"{kurz(pfad)}:{i + 1}: preferencesDataStore ohne corruptionHandler. "
                              "Eine beschaedigte preferences_pb blockiert sonst dauerhaft auch das "
                              "SCHREIBEN - reboot-fest, ohne Selbstheilung.")
    if gefunden == 0:
        fehler.append("kein einziger preferencesDataStore gefunden - Pruefung greift ins Leere?")
    return fehler, f"{gefunden} preferencesDataStore, alle mit corruptionHandler"


# ─────────────────────────── 2. syncAlarms-Aufrufer ─────────────────────────
# Die Doku sagt ausdruecklich: fuer die VOLLSTAENDIGKEIT der Eventliste gibt es KEINEN
# Backstop in syncAlarms() - die Funktion kann einer Liste nicht ansehen, ob sie ein
# Ausschnitt ist. Genau das wurde schon zweimal uebersehen. Deshalb dieser Zaehler.
ERWARTETE_AUFRUFER = {
    "alarm/CalendarPreAlarmRefreshWorker.kt",
    "alarm/receiver/BootReceiver.kt",
    "service/AlarmMaintenanceService.kt",
    "viewmodel/CalendarViewModel.kt",
    "viewmodel/ShiftViewModel.kt",
}


def pruefe_syncalarms_aufrufer():
    ist = set()
    for pfad in kt_dateien():
        if pfad.endswith("IAlarmUseCase.kt") or pfad.endswith("AlarmUseCase.kt"):
            continue                                  # Deklaration bzw. Implementierung selbst
        for zeile in io.open(pfad, encoding="utf-8"):
            if ".syncAlarms(" in ohne_kommentar(zeile):
                ist.add(kurz(pfad))
                break
    neu = sorted(ist - ERWARTETE_AUFRUFER)
    weg = sorted(ERWARTETE_AUFRUFER - ist)
    fehler = []
    if neu:
        fehler.append(
            "NEUE syncAlarms()-Aufrufstelle(n): " + ", ".join(neu) + ".\n"
            "      Ein neuer Aufrufer muss die VOLLSTAENDIGKEIT seiner Eventliste selbst\n"
            "      sicherstellen - dafuer gibt es in syncAlarms() KEINEN Backstop, denn die\n"
            "      Funktion kann einer Liste nicht ansehen, ob sie ein Ausschnitt ist. Eine\n"
            "      unvollstaendige Liste laesst den Delta-Sync Wecker LOESCHEN.\n"
            "      Pruefen: geht der Aufrufer ueber getCalendarEventsWithStatus() und wertet\n"
            "      er isComplete aus? Siehe Skill cfalarm-kalender-und-schichten.\n"
            "      Wenn ja: Datei in ERWARTETE_AUFRUFER in diesem Skript eintragen.")
    if weg:
        fehler.append("Erwartete syncAlarms()-Aufrufstelle(n) verschwunden: " + ", ".join(weg) +
                      " - Liste in diesem Skript nachziehen.")
    return fehler, f"{len(ist)} syncAlarms()-Aufrufstellen (erwartet {len(ERWARTETE_AUFRUFER)})"


# ────────────────── 3. CE-SharedPreferences im Property-Initializer ─────────
def pruefe_eager_sharedprefs():
    """Der ZUGRIFF ist harmlos, der ZEITPUNKT nicht: der Hilt-Graph der Application wird
    auch in dem Prozess gebaut, den das System VOR der ersten Entsperrung startet. Ein
    eager Property-Initializer auf CREDENTIAL-ENCRYPTED SharedPreferences wirft dort und
    toetet den Prozess - die Boot-Wiederherstellung der Alarme laeuft dann NIE.

    Device-protected Storage ist ausdruecklich in Ordnung: der ist im Direct Boot lesbar,
    genau dafuer gibt es ihn."""
    fehler = []
    geprueft = 0
    for pfad in kt_dateien():
        zeilen = io.open(pfad, encoding="utf-8").read().split("\n")
        for i, zeile in enumerate(zeilen):
            klar = ohne_kommentar(zeile)
            # NUR echte Properties: oberste Ebene (Spalte 0) oder Klassenmember (4 Leerzeichen).
            # Ohne diese Klemme schlaegt die Pruefung auf LOKALEN vals in Funktionen an - real
            # passiert an TinkEncryptionHelper.getDiagnostics(), wo der Zugriff harmlos ist,
            # weil er erst beim Aufruf stattfindet und nicht bei der Objekt-Konstruktion.
            if not re.match(r"^ {0,4}(?:private |internal |protected |public )*val\s+\w+\s*(?::[^=]+)?=",
                            klar) or "by lazy" in klar:
                continue
            umfeld = "\n".join(zeilen[i:i + 4])
            if "getSharedPreferences(" not in umfeld:
                continue
            geprueft += 1
            if "createDeviceProtectedStorageContext()" in umfeld:
                continue                              # im Direct Boot lesbar - Absicht
            fehler.append(f"{kurz(pfad)}:{i + 1}: getSharedPreferences im EAGER "
                          "Property-Initializer auf nicht-device-protected Storage. "
                          "'by lazy' verwenden - sonst stirbt der Prozess im Direct Boot, "
                          "und die Wiederherstellung der Alarme laeuft nie.")
    return fehler, f"{geprueft} eager SharedPreferences-Initializer, alle device-protected"


# ─────────────────────────── 4. rohes setAlarmClock ─────────────────────────
ZENTRALE_ALARM_DATEI = "service/AlarmManagerService.kt"


def pruefe_setalarmclock():
    """setAlarmClock() wirft auf API 31/32 ohne SCHEDULE_EXACT_ALARM eine SecurityException.
    Ungefangen aus dem Notification-Snooze-Button riss das den ganzen Prozess mit. Deshalb
    genau EIN Aufrufweg, mit try/catch und inexaktem Fallback."""
    stellen = []
    for pfad in kt_dateien():
        for i, zeile in enumerate(io.open(pfad, encoding="utf-8")):
            if re.search(r"\.setAlarmClock\s*\(", ohne_kommentar(zeile)):
                stellen.append(f"{kurz(pfad)}:{i + 1}")
    fehler = []
    fremd = [s for s in stellen if not s.startswith(ZENTRALE_ALARM_DATEI)]
    if fremd:
        fehler.append("setAlarmClock() ausserhalb des zentralen Wrappers: " + ", ".join(fremd) +
                      ". Nur ueber AlarmManagerService.setExactOrInexact aufrufen (try/catch + "
                      "inexakter Fallback) - roh wirft es auf API 31/32 eine SecurityException "
                      "und reisst den Prozess mit.")
    if not stellen:
        fehler.append("keine setAlarmClock()-Aufrufstelle gefunden - Pruefung greift ins Leere?")
    return fehler, f"{len(stellen)} setAlarmClock()-Aufrufstelle(n), alle im zentralen Wrapper"


# ─────────────────────── 5. dokumentierte Konstanten == Code ────────────────
def pruefe_konstanten():
    """Am 17.08.2026 stand in CLAUDE.md '20 s', wo der Code auf 45_000L stand. Solche Drifts
    fallen nur auf, wenn jemand die Zahl gegen den Code misst."""
    # Jede Fundstelle EINZELN merken (Datei + Zeile). Wuerde man die Werte nur in einer Menge
    # sammeln und "irgendeiner passt" gelten lassen, maskierte eine korrekte Fundstelle einen
    # Widerspruch in einer anderen Datei - genau der Fall, den diese Pruefung fangen soll.
    behauptet = {}
    for d in DOKU:
        if not os.path.exists(d):
            continue
        for nr, zeile in enumerate(io.open(d, encoding="utf-8"), 1):
            for name, wert in re.findall(r"`?([A-Z][A-Z0-9_]{3,})`?\s*=\s*`?([0-9][0-9_]*)", zeile):
                behauptet.setdefault(name, []).append(
                    (int(wert.replace("_", "")), f"{d.replace(os.sep, '/')}:{nr}"))

    tatsaechlich = {}
    for pfad in kt_dateien():
        for zeile in io.open(pfad, encoding="utf-8"):
            for name, wert in re.findall(r"val\s+([A-Z][A-Z0-9_]{3,})\s*=\s*([0-9][0-9_]*)",
                                         ohne_kommentar(zeile)):
                tatsaechlich[name] = int(wert.replace("_", ""))

    fehler = []
    geprueft = 0
    for name, fundstellen in sorted(behauptet.items()):
        if name not in tatsaechlich:
            continue                                  # nicht jede genannte Zahl ist eine Konstante
        echt = tatsaechlich[name]
        for wert, ort in fundstellen:
            geprueft += 1
            # Die Doku darf denselben Wert verkuerzt nennen (45_000 ms als "45 s").
            if wert == echt or wert * 1000 == echt:
                continue
            fehler.append(f"{ort}: {name} steht dort als {wert}, im Code ist es {echt}. "
                          "Die Doku nachziehen - oder pruefen, ob die Aenderung am Code "
                          "gewollt war.")
    return fehler, f"{geprueft} Konstanten-Fundstellen in der Doku stimmen mit dem Code ueberein"


# ─────────────────── 6. keine Hintergrundschicht importiert die Oberflaeche ──
# Die Pakete unterhalb von viewmodel/. ui/ und viewmodel/ selbst stehen NICHT
# in der Liste - nach unten zu greifen ist erlaubt und geschieht auch (die
# Oberflaeche liest Konstanten wie HueRuleUseCase.UNIVERSAL_SHIFT_PATTERN
# bewusst an der Quelle, statt sie zu verdoppeln).
UNTERE_SCHICHTEN = ("alarm", "auth", "backup", "calendar", "data", "di", "dimmer", "dnd",
                    "error", "hue", "masterpause", "model", "repository", "service",
                    "shift", "usecase", "util")

OBERE_SCHICHTEN = ("ui", "viewmodel")

BASISPAKET = "com.github.f1rlefanz.cf_alarmfortimeoffice"


def pruefe_schichtrichtung():
    """Kein Hintergrund-Paket darf `ui.` oder `viewmodel.` importieren.

    Die Richtung nach unten ist frei; geprueft wird nur die Gegenrichtung. Wer sie bricht,
    haengt eine Activity-, Composable- oder ViewModel-Klasse an einen Pfad, der ohne
    Oberflaeche laufen MUSS: `AlarmReceiver`, `AlarmSoundService`, `BootReceiver` und die
    6h-Wartung laufen bei gesperrtem Geraet, im Direct-Boot-Prozess und ohne sichtbare
    Activity. Eine statische Referenz von dort in die Oberflaeche ist der kurze Weg zu einem
    Leak oder zu einem Klassenauflösungsfehler, den kein Unit-Test sieht - er faellt beim
    Klingeln auf.

    Ausnahmefrei: beim Anlegen (22.08.2026) importiert KEINES der 17 unteren Pakete etwas aus
    ui/ oder viewmodel/. Genau deshalb steht die Pruefung hier - ein Rauchmelder wird nicht
    erst montiert, wenn es brennt.
    """
    muster = re.compile(r"^\s*import\s+" + re.escape(BASISPAKET) + r"\.(" +
                        "|".join(OBERE_SCHICHTEN) + r")\.")
    fehler = []
    geprueft = 0
    for pfad in kt_dateien():
        rel = kurz(pfad).replace("\\", "/")
        paket = rel.split("/java/" + BASISPAKET.replace(".", "/") + "/")[-1].split("/")[0]
        if paket not in UNTERE_SCHICHTEN:
            continue
        geprueft += 1
        for i, zeile in enumerate(io.open(pfad, encoding="utf-8")):
            if muster.match(zeile):
                fehler.append(f"{rel}:{i + 1} importiert die Oberflaeche: {zeile.strip()}. "
                              "Hintergrundpfade laufen ohne Activity (Direct Boot, gesperrtes "
                              "Geraet) - was sie brauchen, gehoert nach unten gereicht, nicht "
                              "von oben geholt.")
    if geprueft == 0:
        fehler.append("kein einziges Hintergrund-Paket gefunden - Pruefung greift ins Leere?")
    return fehler, f"{geprueft} Dateien in {len(UNTERE_SCHICHTEN)} Hintergrund-Paketen, keine greift nach oben"


PRUEFUNGEN = [
    ("corruptionHandler an jedem DataStore", pruefe_corruption_handler),
    ("syncAlarms()-Aufrufer vollstaendig bekannt", pruefe_syncalarms_aufrufer),
    ("kein CE-Zugriff im eager Property-Initializer", pruefe_eager_sharedprefs),
    ("setAlarmClock nur im zentralen Wrapper", pruefe_setalarmclock),
    ("dokumentierte Konstanten == Code", pruefe_konstanten),
    ("keine Hintergrundschicht importiert die Oberflaeche", pruefe_schichtrichtung),
]


def main():
    if not os.path.isdir(QUELLEN):
        print(f"FEHLER: {QUELLEN} nicht gefunden - falsches Arbeitsverzeichnis?")
        return 1
    alle = []
    for titel, fn in PRUEFUNGEN:
        fehler, zusammenfassung = fn()
        if fehler:
            print(f"  REISST  {titel}")
            for f in fehler:
                print(f"          {f}")
            alle.extend(fehler)
        else:
            print(f"  OK      {titel}  ({zusammenfassung})")
    if alle:
        print(f"\n{len(alle)} Verstoss/Verstoesse. Jede dieser Regeln steht in CLAUDE.md bzw. in "
              "einem Skill unter .claude/skills/ - dort steht auch, welcher Bug sie erzwungen hat.")
        return 1
    print(f"\n{len(PRUEFUNGEN)} Invarianten geprueft - alle halten.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
