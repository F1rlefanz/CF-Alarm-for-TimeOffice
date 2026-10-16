# Umbau: der Schichttyp wird die Zentrale

Stand: 16.10.2026, mit dem Eigentümer beschlossen. Diese Datei ist die verbindliche Vorlage für
alle Arbeitspakete des Umbaus. Wie die Arbeit abläuft, steht in [`ABLAUF.md`](ABLAUF.md).

**Es ist ein Umbau, kein Neubau.** Was heute funktioniert, bleibt erhalten und zieht nur um:
manuelle Wecker, „Tag freigeben“ (Dienst fällt aus), Skip, Snooze, Master-Pause, Direct Boot,
6-h-Wartung, Hue-Bridge, Tageslicht. Wo möglich wird vorhandener Code verschoben statt neu
geschrieben. Alle Pflichtregeln aus `CLAUDE.md` und den Skills gelten unverändert.

## 1. Warum

- Urlaub, Fortbildung (FBE/FBI) und Krank hatten keinen Platz in den Schichttypen (#145 wurde als
  „Urlaub wie frei“ geschlossen – das ist hiermit revidiert).
- Was an einem Tag passiert (Wecker, Dimmen, „Nicht stören“, Licht), war auf vier Bildschirme
  verteilt: Schichttypen, Schicht-Dimmer mit Regeln, „Nicht stören“, Hue-Regeln.

## 2. Die Entscheidungen

| # | Entscheidung |
|---|---|
| E1 | **Kategorien:** Dienst, Rufbereitschaft, Frei, Urlaub, Fortbildung, Krank, Ignorieren – dazu die feste Zeile **„Ohne Kalendereintrag“**. |
| E2 | **Jede Kategorie kann alles** (Wecker an/aus, stummer Wecker, Dimmen, „Nicht stören“, Licht). Die Kategorie sortiert nur und setzt Voreinstellungen. Ein Wechsel der Kategorie behält die Einstellungen. Keine kategorieabhängigen Sperren, keine Sammelkategorie „Abwesend“. |
| E3 | **Sonderfälle nur bei zwei Einträgen am selben Tag:** Krank hebt einen Dienst auf. Ein nachgetragener Einsatz-Dienst übernimmt einen Rufbereitschaftstag (Codes beim Eigentümer: AD/AD1); die stündliche Prüfung und das DND-Ende 05:00 der Rufbereitschaft bleiben aktiv. |
| E4 | **Mehrere Muster pro Typ** (Beispiel: F und IMCF = derselbe Wecker), auch in der Ersteinrichtung („gehört zu F“). |
| E5 | **„Ohne Kalendereintrag“** kann sich „wie <Typ>“ verhalten (beim Eigentümer: nichts eingetragen = frei). Kein fest verdrahtetes „+“. Reine Zeichen-Codes wie „+“ müssen als Muster erkennbar und vorschlagbar sein (#150). |
| E6 | **Dimmen pro Typ, mehrere Fenster:** je Fenster *Wann* (Nacht davor / Abend danach / Nach dem Dienst), *Von*, *Bis*, *Mit %*, *Schon beim Wecker enden*, *Blocktage* (erster / mittlere / letzter / einzelner). Seltene Optionen (Nachlauf bis es hell ist, Dimmen nach dem Wecker) unter „Weitere Optionen“. |
| E7 | **„Nicht stören“ pro Typ = zwei Schalter:** „Solange gedimmt wird“ und „Während des Kalendereintrags“. Bei Rufbereitschaft zusätzlich „Endet spätestens um“ (05:00). **Kein globaler Hauptschalter** mehr, weder für Dimmen noch für DND. |
| E8 | **Licht (Hue) am Typ:** Lichtregeln hängen am Typ und starten mit dem Wecker. Ohne Weckzeit gibt es kein Licht – der Hinweis empfiehlt einen stummen Wecker (Lichtwecker). |
| E9 | Die Bereiche **Schicht-Dimmer** und **Nicht stören** schrumpfen auf Geräte-Einstellungen unter „Einstellungen“ (Bedienungshilfe, Farbwärme, Vorschau; DND-Zugriff, „Wer trotzdem durchkommt“). „Alle Typen im Vergleich“ entfällt. In der Typ-Liste zeigt jede Zeile eine Kurzform („dimmt 20:00–Wecker“). |
| E10 | **Bereich „Wecker“** ersetzt die Tagesliste. Reihenfolge: Manueller Wecker → Dienst fällt aus → Wie der Wecker klingt → Automatische Alarme → Gestellte Wecker (Tage als Bänder, aufklappbar mit Zeitleiste). |
| E11 | **Übersicht** bekommt einen fortlaufenden, wischbaren **Zeitstrahl** (Fokuslinie, „Gerade“ / „Als Nächstes“, Symbole in den Bändern, Legende; 19 px pro Stunde). In der echten App folgt er minütlich der Uhr und springt beim Wiederöffnen oder nach etwa 1 Minute Ruhe zurück auf „jetzt“. |
| E12 | **Manueller Wecker** im neuen Modell (#153): einmalig **und** wiederkehrend mit Wochentagswahl; ein Wecker ohne Schicht kann eigene Einstellungen für Dimmen, DND und Licht haben. |
| E13 | **Rufdienst-Vortag** (#152): ab 18:00 am Vortag stündlich abgleichen und an TimeOffice erinnern. Die Erinnerung verschwindet, sobald TimeOffice geöffnet wurde. Die Master-Pause gilt. Ist der Tag freigegeben, entfallen Erinnerung und Vortags-Abfrage. |
| E14 | Die **Nachtdienst-Vorlage** darf bei einem Wecker vor 16:00 kein Fenster ab 16:00 am Vortag erzeugen (#151, per Test belegt). |

## 3. Bildschirme (Soll)

Die Soll-Bilder liegen unter `soll/` (je Bildschirm ein PNG). Die Quellen des klickbaren
Prototyps liegen unter `prototyp/`. Sie laufen nur im Design-Werkzeug, nicht allein im Browser.
Maßgeblich ist bei Widerspruch **dieser Text**, nicht das Bild.

### 3.1 Einstellungen → Schichttypen (Liste)
- Oben, nur wenn vorhanden: **„Im Kalender gefunden, noch nicht eingeordnet“** – je Code „Einordnen …“
  und „Ignorieren“.
- Darunter Gruppen je Kategorie (E1); Rufbereitschaft und Krank mit Marke „Sonderfall“. Je Zeile Code,
  Name und Kurzform der Einstellungen. Leere Gruppe: „Noch kein Typ in dieser Kategorie.“
- „Ohne Kalendereintrag“ ist eine feste Zeile.

### 3.2 Einordnen eines Codes (Kategorie-Wahl)
- Zuerst „Gehört zu einem bestehenden Typ“ (der Code wird dort als weiteres Muster eingetragen), dann
  „Oder als neuer Typ in der Kategorie“ mit Kurzbeschreibung je Kategorie.
- Hinweis: „Die Kategorie sortiert nur. Wecker, Dimmen, ‚Nicht stören‘ und Licht stellst du danach am
  Typ selbst ein.“

### 3.3 Typ-Seite (die Zentrale)
Von oben nach unten:
1. Banner nach Neuanlage („Neu angelegt aus dem Kalender-Code … Alles darunter ist eine Voreinstellung“).
2. **Typ:** Name; Muster-Liste (Chips mit ×, „+“); Kategorie. Bei „Ohne Kalendereintrag“ stattdessen
   die Erklärung und die Wahl „Eigene Einstellungen“ oder „Wie <Typ> (<Code>)“ – mit Link zum Typ.
3. **„So läuft ein Tag <Name>“:** Tagesleiste von 18 Uhr am Vortag bis 18 Uhr am Folgetag mit den
   Spuren Eintrag, Dimmen, Nicht stören, Licht, Ruf-Abfrage und Wecker. Sie rechnet bei jeder Änderung
   unten sofort mit. Darunter die Ereignisse als Liste; Link „Im Bereich ‚Wecker‘ über die ganze Woche
   ansehen“.
4. Sonderfall-Kasten (nur Rufbereitschaft, Krank, Ignorieren) mit dem Text aus E3 bzw. „Dieser Eintrag
   wird übergangen …“.
5. **Wecker:** an/aus, Weckzeit, **Stummer Wecker** („Kein Ton, kein Vollbild. Licht, Dimmer und
   ‚Nicht stören‘ richten sich trotzdem nach der Weckzeit – ein Lichtwecker.“).
6. **Bildschirm dimmen:** Fenster nach E6; „+ Weiteres Fenster“; „Weitere Optionen“.
7. **Nicht stören:** nach E7.
8. **Licht (Hue):** nach E8; „+ Lichtregel hinzufügen“.

### 3.4 Bereich „Wecker“
Nach E10. Der manuelle Wecker öffnet ein Blatt mit dem roten Knopf: Datum, „Schicht wählen ▾“ (ein
einziges Aufklappmenü), Weckzeit, „Alarm erstellen“. „Dienst fällt aus“ öffnet einen Vollbild-
Bereichskalender wie der heutige DateRangePicker, mit den Kalender-Codes je Tag. „Wie der Wecker
klingt“: Schlummer-Dauer, sanft lauter werden, Anlaufzeit, Startlautstärke; der Lautsprecher-Knopf
oben rechts spielt zur Probe. Je Tag in „Gestellte Wecker“: Code, Datum, Name, Bänder mit Konflikt-
Markierung, Hinweise; aufgeklappt die Ereignisse, „Zurücknehmen – Dienst findet doch statt“ (bei
freigegebenen Tagen) und „Typ <Name> einstellen“.

### 3.5 Übersicht
Nächster Wecker, Nächste Schicht, **Dein Zeitstrahl** nach E11 mit „Gerade: …“ / „Als Nächstes: …“,
aufklappbarer Legende und dem Link „Alle Tage im Bereich ‚Wecker‘“; Knopf „Mit Google Kalender
abgleichen“.

### 3.6 Einstellungen
Kalender (Auswahl, Schichttypen einrichten), Bildschirm dimmen (Bedienungshilfe, Farbwärme, Vorschau),
Nicht stören (Zugriff, „Wer trotzdem durchkommt – gilt für alle Typen“), Philips Hue (Bridge),
Tageslicht (Standort), Sonstiges (Master-Pause, Wecker-Ton, Schlummern, Export, Logs – wie heute).

### 3.7 Menü
Übersicht · Wecker („Deine Tage mit Zeitstrahl“) · Philips Hue · System-Status · Einstellungen.
**Schicht-Dimmer entfällt** als eigener Menüpunkt.

### 3.8 Ersteinrichtung
1. Willkommen, Google-Anmeldung.
2. Kalender wählen.
3. Kalender durchsuchen: je gefundenem Code die Kategorie oder „gehört zu <Typ>“ (E4); dazu die Zeile
   „Tage ganz ohne Eintrag“ (E5).
4. Weckzeiten für die Typen mit Wecker; die anderen sind „Ohne Wecker voreingestellt“. Dimmen, DND und
   stummer Lichtwecker kommen später pro Typ.
5. Die vier Pflicht-Gates (sie bleiben Pflicht: fehlt eines, verschluckt Android den Wecker still).
6. „Eingerichtet – Nächster Wecker: …“.

## 4. Datenmodell und Migration (Leitplanken, nicht der Entwurf)

- Kategorie, Musterliste, Wecker-, Dimm-, DND- und Licht-Einstellungen hängen am Schichttyp.
  „Ohne Kalendereintrag“ ist ein fester Typ mit optionalem Verweis „wie <Typ>“.
- **Bestehende Daten werden migriert, nicht verworfen:** heutige Dimm-Regeln (auch die Ziele FREI und
  UNIVERSAL), DND-Einstellungen und Hue-Regeln werden den Typen zugeordnet. Die Migration ist einmalig,
  idempotent und läuft nur über einen lesbaren Bestand (`isPersistenceBlocked()` beachten). Scheitert
  sie, bleibt der alte Bestand unangetastet und der Fehler ist sichtbar.
- Die DataStore-Namensräume bleiben getrennt; jeder neue `preferencesDataStore` bekommt einen
  `corruptionHandler`.
- Die Regel „genau eine Dimm-Fenster-Quelle“ gilt weiter – die Quelle sind dann die Fenster am Typ.

## 5. Was fertig heißt (für den ganzen Umbau)

1. Alle Bildschirme aus Abschnitt 3 sind gebaut, am Emulator per Soll-Ist-Vergleich abgenommen und
   ohne Abschneiden bei 320 dp Breite.
2. Die alten Bildschirme (Schicht-Dimmer, Regel-Editor, DND-Seite, Hue-Regel-Seite, soweit ersetzt)
   und ihr Code sind entfernt – keine Funktion ohne Bedienoberfläche, kein toter Code.
3. Eine echte Konfiguration (Export-Datei als Testvorlage) migriert verlustfrei; der Test liegt bei.
4. Doku nachgezogen: `ARCHITECTURE.md`, die betroffenen Skills (über die Nachtrags-Datei, siehe
   ABLAUF), Website (`docs/*.html`), `CHANGELOG.md` in Nutzersprache.
5. Eine Prüfrunde über **unverändertem** Code ohne neuen bestätigten Befund.
