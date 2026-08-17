"""Prueft am Emulator, ob die Wecker einen Neustart OHNE Entsperrung ueberleben.

WARUM ES DAS GIBT
-----------------
Das ist der einzige Pfad dieser App, den kein Test abdeckt. CLAUDE.md sagt an zwei Stellen
woertlich "Kein Unit-Test faengt das - nur ein echter `adb reboot`", und am 11.08.2026 ist
genau das real passiert: der Hilt-Graph der Application loeste eager WorkManager auf, der
Prozess starb VOR der ersten Entsperrung mit "Unable to create application", und damit lief
der Direct-Boot-Restore der Alarme NIE. Die Wecker kamen erst zurueck, nachdem der Nutzer
entsperrt hatte. Startet das Geraet nachts neu und niemand entsperrt es, gibt es keinen Wecker.

Bisher war die Pruefung ein mehrstufiges Handritual, das vor keinem Release jemand durchspielt.
Hier ist sie ein Befehl.

DIE ENTSCHEIDENDE VORBEDINGUNG
------------------------------
OHNE Bildschirmsperre ist dieser Test WERTLOS und schlimmer als keiner: ohne Credential gilt
der Nutzer beim LOCKED_BOOT_COMPLETED bereits als entsperrend/entsperrt, CE-Storage ist lesbar,
und die Exception bleibt aus. Der erste Anlauf des damaligen Fixes sah deshalb faelschlich
"am Geraet verifiziert" aus. Das Skript bricht deshalb ab, wenn keine Sperre gesetzt ist.

WARUM NUR EMULATOR
------------------
Das Skript REBOOTET das Zielgeraet. Auf dem produktiven Handy waere das der echte Wecker des
Nutzers. Es verweigert deshalb den Dienst, wenn das Ziel keine emulator-*-Seriennummer hat.
Einen Umgehungsschalter gibt es bewusst NICHT.
"""
import re
import subprocess
import sys
import time

PAKET = "com.github.f1rlefanz.cf_alarmfortimeoffice"
ALARM_MUSTER = re.compile(r"ENHANCED_ALARM_-?\d+")

# TOEDLICH: der Prozess stirbt, bevor irgendetwas wiederhergestellt werden kann. Genau der
# Vorfall vom 11.08.2026. Ein Treffer hier ist immer ein Fehlschlag.
TOEDLICH = [
    ("Unable to create application",
     "der Application-Prozess ist beim Bauen des Hilt-Graphen gestorben - "
     "der Direct-Boot-Restore laeuft dann NIE"),
]

# HINWEISE: im gesperrten Zustand ERWARTBAR und teils ausdruecklich so gebaut. Sie werden
# gezeigt, damit man sie im Blick hat, aber sie sind fuer sich KEIN Fehlschlag - sonst
# schlaegt die Pruefung bei jedem Lauf falschen Alarm, und niemand sieht mehr hin.
#   - WorkManager: HueSmartScheduler loest ihn erst beim Gebrauch auf und ueberspringt sich
#     mit WARN, wenn er nicht verfuegbar ist. Das IST die vorgesehene Degradation.
#   - CE-Zugriffe: credential-encrypted Storage ist vor der Entsperrung nicht lesbar. Wo das
#     gegated ist, erscheint nichts; wo es auffaellt, lohnt der Blick, ob es gegated gehoert.
HINWEISE = [
    ("WorkManager is not initialized",
     "WorkManager vor der Entsperrung nicht verfuegbar - so vorgesehen, solange es bei WARN "
     "bleibt und der Aufrufer sich ueberspringt"),
    ("are not available until after user",
     "CE-SharedPreferences vor der Entsperrung gelesen - pruefen, ob die Stelle ein "
     "userUnlocked-Gate braucht"),
    ("Unable to create parent directories",
     "Datei-Zugriff auf CE-Storage vor der Entsperrung - im gesperrten Zustand erwartbar"),
]

WARTE_BOOT_S = 180          # Kaltstart eines Emulators dauert gut eine Minute
WARTE_RESTORE_S = 120       # der BootReceiver bekommt Zeit; er sitzt bewusst kurz ab
POLL_S = 5


def sh(serial, *args, timeout=60):
    r = subprocess.run(["adb", "-s", serial, *args], capture_output=True, text=True,
                       timeout=timeout, errors="replace")
    return r.stdout


def ziel_bestimmen():
    """Genau ein Emulator, sonst Abbruch. Das Skript rebootet - ein Irrtum ist teuer."""
    r = subprocess.run(["adb", "devices"], capture_output=True, text=True, errors="replace")
    geraete = [z.split("\t")[0] for z in r.stdout.splitlines()[1:]
               if "\tdevice" in z]
    emulatoren = [g for g in geraete if g.startswith("emulator-")]
    andere = [g for g in geraete if not g.startswith("emulator-")]
    if not emulatoren:
        print("ABBRUCH: kein Emulator angeschlossen.")
        if andere:
            print(f"         Gefunden: {', '.join(andere)} - das sind KEINE Emulatoren.")
            print("         Dieses Skript REBOOTET das Zielgeraet. Auf einem echten Handy waere")
            print("         das der Wecker des Nutzers. Es laeuft deshalb ausschliesslich gegen")
            print("         emulator-*. Einen Umgehungsschalter gibt es bewusst nicht.")
        return None
    if len(emulatoren) > 1:
        print(f"ABBRUCH: mehrere Emulatoren ({', '.join(emulatoren)}) - Ziel nicht eindeutig.")
        return None
    if andere:
        print(f"Hinweis: {', '.join(andere)} ist ebenfalls angeschlossen und wird NICHT angefasst.")
    return emulatoren[0]


def alarme(serial):
    return set(ALARM_MUSTER.findall(sh(serial, "shell", "dumpsys", "alarm")))


def nutzer_zustand(serial):
    m = re.search(r"State:\s*(\S+)", sh(serial, "shell", "dumpsys", "user"))
    return m.group(1) if m else "?"


def main():
    serial = ziel_bestimmen()
    if not serial:
        return 2
    print(f"Ziel: {serial}\n")

    # ---- 1. Vorbedingungen -------------------------------------------------
    print("[1/6] Vorbedingungen")
    if PAKET not in sh(serial, "shell", "pm", "list", "packages"):
        print(f"      ABBRUCH: {PAKET} ist nicht installiert.")
        return 2
    version = re.search(r"versionName=(\S+)", sh(serial, "shell", "dumpsys", "package", PAKET))
    print(f"      App installiert ({version.group(1) if version else 'Version unbekannt'})")

    gesperrt = sh(serial, "shell", "locksettings", "get-disabled").strip()
    if gesperrt != "false":
        print("      ABBRUCH: keine Bildschirmsperre gesetzt.")
        print("      Ohne Credential gilt der Nutzer beim LOCKED_BOOT_COMPLETED bereits als")
        print("      entsperrt, CE-Storage ist lesbar, und der Fehler bleibt aus. Der Test")
        print("      wuerde 'bestanden' melden, ohne irgendetwas zu belegen.")
        print("      Abhilfe:  adb -s %s shell locksettings set-pin 1234" % serial)
        return 2
    print("      Bildschirmsperre gesetzt (ohne sie waere der Test wertlos)")

    vorher = alarme(serial)
    if not vorher:
        print("      ABBRUCH: kein einziger Wecker armiert - der Test koennte nichts zeigen.")
        print("      Erst in der App Alarme erzeugen lassen, dann erneut laufen.")
        return 2
    print(f"      {len(vorher)} Wecker armiert")

    # ---- 2. Logcat leeren, damit die Auswertung nur den Boot sieht ---------
    print("\n[2/6] Logcat-Puffer leeren")
    sh(serial, "logcat", "-c")
    print("      geleert")

    # ---- 3. Reboot ---------------------------------------------------------
    print("\n[3/6] Neustart - danach wird NICHT entsperrt")
    sh(serial, "reboot")
    subprocess.run(["adb", "-s", serial, "wait-for-device"], timeout=WARTE_BOOT_S)
    grenze = time.time() + WARTE_BOOT_S
    while time.time() < grenze:
        if sh(serial, "shell", "getprop", "sys.boot_completed").strip() == "1":
            break
        time.sleep(POLL_S)
    else:
        print("      ABBRUCH: Geraet ist nicht rechtzeitig hochgekommen.")
        return 2
    print("      hochgefahren")

    zustand = nutzer_zustand(serial)
    if zustand != "RUNNING_LOCKED":
        print(f"      ABBRUCH: Nutzerzustand ist {zustand}, erwartet RUNNING_LOCKED.")
        print("      Wurde entsperrt? Dann misst der Test nicht mehr den Direct-Boot-Fall.")
        return 2
    print(f"      Nutzerzustand {zustand} - genau der Fall, um den es geht")

    # ---- 4. Wiederherstellung abwarten und messen -------------------------
    print(f"\n[4/6] Warte auf die Wiederherstellung (bis {WARTE_RESTORE_S}s, ohne zu entsperren)")
    nachher = set()
    grenze = time.time() + WARTE_RESTORE_S
    while time.time() < grenze:
        nachher = alarme(serial)
        if len(nachher) >= len(vorher):
            break
        time.sleep(POLL_S)
    print(f"      {len(nachher)} von {len(vorher)} Weckern wieder armiert")

    # ---- 5. Logcat auswerten ----------------------------------------------
    print("\n[5/6] Boot-Log")
    log = sh(serial, "logcat", "-d", timeout=120)
    tot = [(m, w) for m, w in TOEDLICH if m in log]
    hinweise = [(m, w) for m, w in HINWEISE if m in log]
    for m, w in tot:
        print(f"      TOEDLICH: \"{m}\"\n                {w}")
    for m, w in hinweise:
        print(f"      Hinweis:  \"{m}\"\n                {w}")
    if not tot and not hinweise:
        print("      unauffaellig")

    # ---- 6. Urteil ---------------------------------------------------------
    # Massgeblich ist das ERGEBNIS: sind die Wecker wieder armiert, ohne Entsperrung?
    # Ein Hinweis allein ist kein Fehlschlag - sonst schlaegt die Pruefung immer Alarm.
    print("\n[6/6] Ergebnis")
    fehlend = vorher - nachher
    ok = not fehlend and not tot
    if ok:
        print(f"      TRAEGT: alle {len(vorher)} Wecker sind nach dem Neustart wieder armiert,")
        print(f"      ohne dass entsperrt wurde (Nutzerzustand {zustand}).")
        if hinweise:
            print(f"      {len(hinweise)} Hinweis(e) im Log - kein Fehlschlag, aber sehenswert.")
    else:
        if fehlend:
            print(f"      REISST: {len(fehlend)} von {len(vorher)} Weckern fehlen nach dem Neustart.")
            for a in sorted(fehlend):
                print(f"              {a}")
            print("      Das ist der Fall, der nachts still den Wecker kostet.")
        if tot:
            print("      REISST: der Prozess ist im Boot gestorben (siehe oben).")
        print("      Hergang und Ursachen im Skill cfalarm-wecker-und-boot.")
    print(f"\n      Das Geraet ist noch GESPERRT. Zum Weiterarbeiten entsperren:")
    print(f"      adb -s {serial} shell input keyevent 82 && "
          f"adb -s {serial} shell input text 1234 && adb -s {serial} shell input keyevent 66")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
