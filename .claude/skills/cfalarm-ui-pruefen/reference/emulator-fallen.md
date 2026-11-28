# UI-Prüfung am Emulator — Fallen und Belege aus den Etappen 1 bis 3

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
- Etappe 3: Dimm-Karten, „Nicht stören", Dimm-Dienst

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

## Etappe 3: Dimm-Karten, „Nicht stören", Dimm-Dienst

- **Gerätezustand bleibt über Bash-Aufrufe hinweg stehen** (#221): Dichte, Dunkelmodus und Schrift
  gehören dem Gerät, nicht dem Aufruf. Jedes Zustands-Skript setzt ihn am Anfang selbst und gibt
  `wm density` aus, nicht nur am Ende zurück. Hergang: ein Aufruf endete ohne Zurücksetzen auf
  320 dp, die folgenden „Normalbreite"-Bilder samt Textprüfung liefen schmal; erst der Bewerter sah
  es an der Höhe der App-Leiste.
- **Ein Feld rechts neben einer zweizeiligen Beschriftung** (#221): `pruefe_texte.py` sortiert nach
  Oberkante und liest deshalb den WERT vor der Unterzeile. Die Erwartungsdatei folgt der Geometrie,
  die Pflichttexte bleiben, wie sie in `BILDSCHIRME.md` stehen. Auf der Dimm-Karte: „Bis" · „07:00" ·
  „am Tag selbst" · „40" · „Mit %". Migrierte Typen starten mit „Dimmen an diesem Tag" aus, „+
  Weiteres Fenster" ist dann gesperrt.
- **Den Zugriff auf „Nicht stören" umschalten** (#222):
  `adb shell cmd notification disallow_dnd <paket>` / `allow_dnd <paket>`, die Rückgabe im `trap`; die Seite liest den Zugriff beim nächsten
  `am start` neu. `settings get secure enabled_notification_policy_access_packages` liefert auf API
  37 `null`, obwohl der Zugriff erteilt ist (Emulator, 27.11.2026) — taugt nicht als Beleg.
- **Ob ein Knopf gesperrt ist, steht nicht am Text-Knoten darin** (#222): der trägt
  `enabled="true"` auch bei gesperrtem Knopf. Am Schalter (`checkable`) bzw. am Bild ablesen.
- **Ein Dimm-Fenster ohne „Wann" lässt sich in der Oberfläche nicht anlegen** (#224). Für die Abnahme
  `files/datastore/shift_prefs.preferences_pb` bei angehaltener App per `run-as` sichern, den
  JSON-Wert `shift_config` ersetzen und danach das Original zurückkopieren.
- **Den Dimm-Dienst erst NACH dem letzten `am force-stop` einschalten** (#225):
  `settings put secure enabled_accessibility_services <paket>/<paket>.dimmer.DimAccessibilityService`
  und `accessibility_enabled 1`. `force-stop` nimmt den Dienst wieder aus der Liste (die Zeile stand
  danach weiter auf „Nicht erlaubt"). Das Overlay ab API 34 über `dumpsys SurfaceFlinger --list`
  belegen: die hängende Ebene sind die Zeilen `CFAlarmDimLayer` mit `parentId=` (vorher 0, an 2,
  abgehängt 1). Ein Fenster `TYPE_ACCESSIBILITY_OVERLAY` gibt es dort nicht.
