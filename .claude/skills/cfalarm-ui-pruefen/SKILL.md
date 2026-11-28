---
name: cfalarm-ui-pruefen
description: "UI-Abnahme eines Bildschirms der CFAlarm-App am Emulator gegen den Prototyp des Umbaus Schichttyp als Zentrale: Pflichttexte und Reihenfolge hart per uiautomator (tools/geraet/pruefe_texte.py), dazu 320 dp, Dunkelmodus und eine Bewertung des Screenshots mit 16 Punkten durch ein anderes Modell als den Umsetzer. Nur emulator-5554, nie das Fairphone. Zu verwenden, bevor ein UI-Paket des Umbaus als fertig gilt, beim Etappen-Abschluss und wenn ein Bildschirm gegen entwurf/umbau-schichttyp/BILDSCHIRME.md geprueft werden soll."
---

# UI-Prüfung am Emulator (Umbau Schichttyp)

Gilt für jedes Paket des Umbaus, das einen Bildschirm ändert. Vorlage ist der Prototyp-Code
(`entwurf/umbau-schichttyp/prototyp/`), die Pflichttexte stehen in
`entwurf/umbau-schichttyp/BILDSCHIRME.md`, maßgeblich bei Widerspruch ist `SPEC.md`.

## Harte Regeln

- **Nur `emulator-5554`.** Vorher `adb devices`: steht dort ein zweites Gerät, **nicht**
  `installDebug` benutzen (trifft beide), sondern `assembleDebug` + `adb -s emulator-5554 install -r`.
  Das Fairphone wird nie angesprochen.
- **Texte werden hart geprüft, nicht per Auge.** Ein Screenshot-Urteil ersetzt `pruefe_texte.py` nicht.
- **Der Bewerter ist ein anderes Modell als der Umsetzer** (Sonnet setzt um → Opus bewertet und
  umgekehrt) und sieht Screenshot, Prototyp-Stelle, SPEC-Abschnitt **und die „Festlegungen des
  Betreuers" des Issues im Wortlaut**, nicht den Weg. Ohne die Festlegungen zieht er für jede
  bewusste Abweichung vom Prototyp ab (#189: Krank 11/16 statt 16/16). Wird nachgereicht, gehören
  BEIDE Wertungen in den Abschlusskommentar.
- Jede Änderung am Gerätezustand (Dichte, Dunkelmodus) danach **zurücksetzen**.
- **Die Abnahme läuft als Skript, ein Bash-Aufruf je Bildschirmzustand** (navigieren,
  `pruefe_texte.py`, Screenshots, Zurücksetzen im `finally`) — Einzel-Taps verbrauchen das
  Turn-Budget, und ein Log mit Prosa statt AUSGABE zählt nicht als Beleg.

Fallen und Belege im Einzelnen: `reference/emulator-fallen.md`.

## Ablauf

1. Bauen und installieren: `./gradlew assembleDebug`, dann
   `adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk`.
2. Bildschirm ansteuern (Menü, Knöpfe; Koordinaten aus `uiautomator dump` oder
   `get_clickable_elements`, nie aus dem Screenshot geschätzt).
3. **Texte:** Pflichttexte des Bildschirms aus `BILDSCHIRME.md` in eine Datei schreiben (eine Zeile
   je Text, verbotene mit `!`, z. B. `!Prototyp`), dann
   `python tools/geraet/pruefe_texte.py --datei <datei>` → muss `BESTANDEN` melden (Exit 0).
   Die Ausgabe gehört ins Log bzw. in den Abschlusskommentar des Issues. **Vor JEDEM Lauf an den
   Seitenanfang wischen** (es scrollt nur nach unten); Seitentitel nicht in die geordnete
   Erwartung, und von zwei Texten im selben Knoten nur den ersten.
4. **320 dp:** `adb -s emulator-5554 shell wm density <Breite_px / 2>` (1080 px → 540), Bildschirm
   erneut öffnen, Screenshot, nichts abgeschnitten; danach `wm density reset`.
5. **Dunkelmodus:** `adb -s emulator-5554 shell cmd uimode night yes`, Screenshot, lesbar; danach
   `night no`.
6. **Bewertung** (anderes Modell, Subagent), 8 Kriterien je 0–2 Punkte, bestanden ab 14 und ohne 0:
   Inhalt vollständig · Reihenfolge wie Prototyp · Wortlaut · Gruppierung und Abstände · Farben und
   Kategorie-Marken · Bedienelemente (Schalter, Chips, Knöpfe) wie Prototyp · 320 dp · Dunkelmodus.
   Jeder Punktabzug braucht einen Satz Begründung mit Fundstelle im Prototyp.

## Fallen

- `adb shell input text` kann keinen Doppelpunkt: Zeitfelder nicht neu tippen, sondern einen
  bestehenden Eintrag mit gültiger Zeit bearbeiten.
- Mit Git Bash `MSYS_NO_PATHCONV=1` setzen, sonst werden `/data/local/tmp`-Pfade verbogen — dann
  aber Pfade an Windows-Python mit `cygpath -w` übergeben, und Text mit Umlauten kommt dort
  verstümmelt an (nur ASCII-Teilstrings suchen).
- **Vor jedem Tipp neu `uiautomator dump`en, nie per Teiltreffer tippen, nach jedem Bildschirm
  prüfen, wo man steht.** Dialoge wachsen und verschieben Zeilen, ein Teiltreffer traf eine sofort
  speichernde Auswahlzeile, und Wischen über einer offenen Tastatur tippt.
- Zustände wie Krank/Ignorieren im ENTWURF einer bestehenden Typ-Seite prüfen (Kategorie wechseln,
  danach Zurück → „Verwerfen"), statt Typen anzulegen. Die Tagesleiste ist ein Canvas: belegt wird
  sie über die Ereignisliste darunter.
- Das Overlay des Dimmers erscheint nicht im Screenshot; Nachweis über
  `dumpsys SurfaceFlinger --list` (Zeilen `CFAlarmDimLayer` mit `parentId=`). Den Dimm-Dienst erst NACH dem letzten
  `am force-stop` einschalten — `force-stop` nimmt ihn wieder aus der Liste.
- **Gerätezustand (Dichte, Dunkelmodus, Schrift) bleibt über Bash-Aufrufe hinweg stehen:** jedes
  Zustands-Skript setzt ihn am Anfang selbst und gibt `wm density` aus, nicht nur am Ende zurück.
- **Neben einer zweizeiligen Beschriftung liest `pruefe_texte.py` den WERT vor der Unterzeile**
  (Sortierung nach Oberkante): die Erwartungsdatei folgt der Geometrie, die Pflichttexte bleiben.
- **„Nicht stören"-Zugriff umschalten:** `adb shell cmd notification disallow_dnd <paket>` /
  `allow_dnd <paket>` (Rückgabe im `trap`). Ob ein Knopf gesperrt ist, steht nicht am Text-Knoten
  (`enabled="true"` auch gesperrt) — am Schalter bzw. am Bild ablesen.
- **Zustände, die die Oberfläche nicht anlegen kann** (z. B. ein Dimm-Fenster ohne „Wann"): den
  Store bei angehaltener App per `run-as` sichern, ändern, danach das Original zurückkopieren.
- Hängt mobile-mcp am Gerät, ist die Bedienungshilfe still aus (Memory
  `env_mobile_mcp_legt_bedienungshilfen_lahm`).
