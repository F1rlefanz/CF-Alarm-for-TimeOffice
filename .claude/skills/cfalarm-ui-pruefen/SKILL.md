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
  umgekehrt) und sieht nur Screenshot, Prototyp-Stelle und SPEC-Abschnitt, nicht den Weg.
- Jede Änderung am Gerätezustand (Dichte, Dunkelmodus) danach **zurücksetzen**.

## Ablauf

1. Bauen und installieren: `./gradlew assembleDebug`, dann
   `adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk`.
2. Bildschirm ansteuern (Menü, Knöpfe; Koordinaten aus `uiautomator dump` oder
   `get_clickable_elements`, nie aus dem Screenshot geschätzt).
3. **Texte:** Pflichttexte des Bildschirms aus `BILDSCHIRME.md` in eine Datei schreiben (eine Zeile
   je Text, verbotene mit `!`, z. B. `!Prototyp`), dann
   `python tools/geraet/pruefe_texte.py --datei <datei>` → muss `BESTANDEN` melden (Exit 0).
   Die Ausgabe gehört ins Log bzw. in den Abschlusskommentar des Issues.
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
- Mit Git Bash `MSYS_NO_PATHCONV=1` setzen, sonst werden `/data/local/tmp`-Pfade verbogen.
- Das Overlay des Dimmers erscheint nicht im Screenshot; Nachweis über `dumpsys SurfaceFlinger`.
- Hängt mobile-mcp am Gerät, ist die Bedienungshilfe still aus (Memory
  `env_mobile_mcp_legt_bedienungshilfen_lahm`).
