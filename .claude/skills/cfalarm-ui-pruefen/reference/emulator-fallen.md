# UI-Prüfung am Emulator — Fallen und Belege aus Etappe 1 und 2

> Hergang zu den Regeln der `SKILL.md` daneben. Gesammelt aus den Abnahmen der Pakete #170–#197
> (Oktober/November 2026). Jede Falle hat mindestens eine Abnahme Zeit oder ein Scheinergebnis gekostet.

## Inhalt

- `pruefe_texte.py` richtig füttern
- Tippen und Wischen
- Windows-Python aus Git Bash
- Zustände ohne neue Typen prüfen
- Nachweise, die nicht im Dump stehen
- Der Bewerter
- Die Abnahme als Skript

## `pruefe_texte.py` richtig füttern

- **Es scrollt bei JEDEM Lauf nur NACH UNTEN und liest ab der aktuellen Position.** Vor jedem Lauf —
  auch dem zweiten — mit drei Wischern nach oben an den Anfang; sonst „fehlt" alles, was oberhalb
  liegt (#170: bei 320 dp „NICHT BESTANDEN", ab Anfang BESTANDEN; #190: der Banner).
- **Ein Seitentitel gehört nicht in die geordnete Erwartung:** die Kopfzeile („Neuer Typ") steht im
  Dump je nach Scrollstand VOR oder NACH dem unteren Knopf. Titel einzeln belegen (#190).
- **Zwei erwartete Texte im SELBEN Knoten** (z. B. „Dieser Eintrag wird übergangen." und „Ohne
  Kalendereintrag" im Ignorieren-Kasten) meldet es als „Reihenfolge"-Befund, weil es jeden Text in
  einem eigenen, späteren Knoten sucht — nur den ersten als erwartet eintragen, den zweiten per Dump
  belegen (#189).
- **Mit `-I` aufgerufen schreibt es seine Befundmeldung in cp1252** (Gedankenstrich = Byte 0x96): ein
  Aufrufer mit `text=True` und UTF-8 stirbt im Reader-Thread mit `UnicodeDecodeError` und bekommt
  `None` — das sieht wie ein Befund aus („EXIT 1"), ist aber nur Kodierung. Mit
  `encoding='cp1252', errors='replace'` lesen (#197).
- **Wer selbst auswertet, nimmt einen XML-Parser.** uiautomator quotet einen Text, der `"` enthält, mit
  einfachen Anführungszeichen (`text='… ("FD" …)'`); eine Regex `text="([^"]*)"` überspringt diese
  Knoten still — Warnung und Muster-Hinweis „fehlten", obwohl sie auf dem Bildschirm standen
  (#188). Emoji stehen im Dump als `&#128295;` (#156). `pruefe_texte.py` liest mit `ElementTree`.

## Tippen und Wischen

- **Vor jedem Tipp neu `uiautomator dump`en, nie Koordinaten der vorigen Ansicht nehmen.** Ein Dialog,
  dessen Inhalt beim Tipp wächst, verschiebt seine Zeilen: ein Tipp auf „Eigene Einstellungen" mit
  den Koordinaten VOR dem Wachsen traf „Wie Frühschicht" (#177). Ebenso „Verwerfen" rechts im
  Dialog (#188) und der Kategorie-Dialog bei 420 dpi, in dem „Ignorieren" unter dem Rand lag (#189).
- **Ein Dialog mit scrollbarer Liste braucht Wischen IM Dialog**, bevor die unteren Zeilen antippbar
  sind bzw. im Dump stehen (Krank, Ignorieren bei 320 dp; #176, #188).
- **Nie per Teiltreffer tippen.** `tap "Erstellen"` traf den Banner („… erst mit „Erstellen".")
  statt des Knopfs (#190); auf „Ohne Kalendereintrag" traf ein Teiltreffer auf „kein Wecker" eine
  Auswahlzeile, und diese Wahl speichert sofort (#156). Knöpfe mit gleichlautendem Text im Fließtext
  über die Position aus dem Dump.
- **Nach jedem Bildschirm prüfen, wo man steht** (#156, #176): auf der kurzen Ignorieren-Seite fing ein
  Textfeld den Fokus, `input swipe` über der offenen Tastatur wurde zu Wischtippen („GT", „ty" im
  Entwurf), `BACK` schloss nur die Tastatur oder öffnete die Rückfrage, und alle folgenden Prüfungen
  liefen auf der falschen Seite — das sah wie ein App-Fehler aus. Wischgesten von oben ziehen die
  Benachrichtigungsleiste herunter, sobald die App nicht im Vordergrund ist; Zurück ohne offenen
  Dialog führt Schicht für Schicht bis zur Übersicht. Am Anfang eines Skripts `am start` und
  `cmd statusbar collapse`.
- **Zurück bei offener Tastatur schließt nur die Tastatur** (auch nach `input text`); die Rückfrage
  kommt erst beim zweiten. Zurück ohne Änderung fragt nicht — auch nicht, wenn die Änderung
  zurückgetippt wurde (#190, #197).
- **Schalter:** im Dump ohne Text; getippt wird auf seine x-Position (≈ 930 bei 1080 px) in Höhe der
  Textzeile, nicht auf den Zeilentitel (der schaltet bei „Schichtdefinition aktiviert" nicht).
  `checked` bzw. `enabled="false"` im Dump belegen den Zustand (#189, #156). Eine gewählte
  Radio-Zeile (`selectable(selected = true)`) steht nicht als `clickable`, die ungewählten schon (#191).

## Windows-Python aus Git Bash

- **Text per Befehlszeile an Windows-Python verliert Umlaute und Anführungszeichen** („„Frühschicht"
  öffnen" kam als `�Fr�hschicht� �ffnen` an). Nur ASCII-Teilstrings suchen — und prüfen, auf
  WELCHER Seite man steht: `ffnen` traf auf der Typ-Liste den Hilfeknopf „Anleitung im Browser
  öffnen", weil die Suche am vorigen Fehler nicht abbrach (#191).
- **Pfade:** `--datei` als `$(cygpath -w …)` übergeben; `/tmp/…` löst Windows-Python nicht auf (#170).
  Weitere Fallen (`MSYS_NO_PATHCONV`, `python -I`): Skill `cfalarm-bauen-und-testen`.

## Zustände ohne neue Typen prüfen

- **Sonderfall-Kästen (Rufbereitschaft, Krank, Ignorieren) im ENTWURF prüfen:** auf der Typ-Seite eines
  Dienst-Typs die Kategorie wechseln, nichts speichern, danach Zurück → „Verwerfen" (#189). Der
  Entwurf übersteht `wm density 540` und `cmd uimode night yes`, der gespeicherte Typ bleibt
  unberührt (danach in der Liste noch „Frühschicht · Alarm: 05:30").
- **Einen Test-Typ entfernen:** Zeile suchen (Liste wischen), „Löschen", im Dialog „Löschen"; danach
  steht der Code wieder unter „Im Kalender gefunden" (#190).

## Nachweise, die nicht im Dump stehen

- **Die Tagesleiste ist ein Canvas.** Belegt wird ihr Rechnen über die Ereignisliste darunter: im
  Entwurf „Wecker an diesem Tag" ausschalten → „05:30 · Wecker – Frühschicht" verschwindet sofort,
  „06:00 Frühschicht beginnt" bleibt; danach Zurück → „Verwerfen" (#197).
- **„Wecker an einem Tag ohne Eintrag"** (#177): Termine des Zeitraums über
  `adb shell content query --uri content://com.android.calendar/instances/when/<von_ms>/<bis_ms>
  --projection title:begin:end:allDay` (nur der in der App gewählte Kalender zählt), die App-Wecker
  aus `adb shell dumpsys alarm` (Tag `…ENHANCED_ALARM_<id>`, Zeile `origWhen=`) vor der Wahl, mit der
  Wahl und nach dem Zurücknehmen vergleichen. Beleg #177: 2 Wecker → 5 (neu genau die Tage ohne
  Termin) → wieder 2. **`dumpsys alarm | grep -c ENHANCED_ALARM` zählt Zeilen, nicht Wecker**
  (6 → 9 → 8 für dieselben Wecker, #191): `origWhen=` je Tag vergleichen.

## Der Bewerter

- **Er bekommt die „Festlegungen des Betreuers" des Issues im Wortlaut** zu Screenshot, Prototyp-Stelle
  und SPEC-Abschnitt. Ohne sie wertete er Krank mit 11/16 „nicht bestanden", weil die Wecker-Karte
  fehlt — SPEC E2 sagt „jede Kategorie kann alles", das Issue legt fest, dass Krank und Ignorieren
  bis Etappe 6 keinen Wecker-Block haben. Ebenso je ein Abzug für jeden Textwahrheits-Wortlaut
  („Stellt … einen Wecker" statt „Klingelt"). Nach dem Nachreichen: 16/16 in allen vier Kategorien
  (#189).
- **Beide Werte gehören in den Abschlusskommentar** (erst 14/11/15/14, dann 16/16/16/16), sonst sieht
  die Neuwertung nach Schönrechnen aus. Er darf weiter Abzüge zeigen: der Aus-Zustand der
  Material-Schalter (1,34:1 auf Weiß, Theme `Outline`) läuft als #200 für die ganze App.
- **Er misst, statt zu schätzen:** den Umbruch der Leisten-Achse bei 320 dp fand er durch Pixelmessung
  im PNG (#197).

## Die Abnahme als Skript

**Ein Bash-Aufruf je Bildschirmzustand** (navigieren, `pruefe_texte.py`, Screenshots, `density`/`night`
im `finally` zurück), nicht Einzel-Taps. Lauf 1 von #197 verbrauchte ~25 seiner 81 Turns mit
Einzel-Taps und endete mitten in der Abnahme; sein Log hielt die Ergebnisse nur als Prosa fest („B3r
BESTANDEN"), und ein Folgelauf musste die Abnahme neu fahren, weil das Issue die AUSGABE verlangt.
