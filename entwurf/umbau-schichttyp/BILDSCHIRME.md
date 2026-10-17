# Bildschirme: Prototyp-Stelle und Pflichttexte

Grundlage der UI-Prüfung (Skill `cfalarm-ui-pruefen`). Je Bildschirm: wo er im Prototyp-Code steht
(`prototyp/Main.dc.html` bzw. `Onboarding.dc.html`, Zustand im `sc-if`) und welche Texte die App
**in dieser Reihenfolge** zeigen muss (per uiautomator ausgelesen, von oben nach unten). Texte in
`<…>` sind Daten und werden nur auf Vorhandensein geprüft. Abweichungen im Wortlaut sind ein
Befund, außer die Spec sagt etwas anderes.

| ID | Bildschirm (SPEC) | Prototyp | Pflichttexte in Reihenfolge |
|---|---|---|---|
| B1 | Schichttypen-Liste (3.1) | Main `zeigeListe` | „Im Kalender gefunden, noch nicht eingeordnet“ (nur mit Fund) · „Einordnen …“ · „Ignorieren“ · Gruppen-Titel Dienst, Rufbereitschaft, Frei, Urlaub, Fortbildung, Krank, Ignorieren, Ohne Kalendereintrag · „Sonderfall“ bei Rufbereitschaft und Krank · leere Gruppe: „Noch kein Typ in dieser Kategorie.“ |
| B2 | Einordnen (3.2) | Main `zeigeKatWahl` | „Gehört zu einem bestehenden Typ“ · „Oder als neuer Typ in der Kategorie“ · Kategorien mit Beschreibung (Texte aus `KAT` im Skriptteil) · „Die Kategorie sortiert nur.“ (Satzanfang; bis Etappe 3/4 nennt der Rest nur den Wecker, danach wieder „Wecker, Dimmen, „Nicht stören“ und Licht …“ wie im Prototyp) |
| B3 | Typ-Seite (3.3) | Main `zeigeTyp` | „Typ“ · „Name“ · „Muster im Kalender – alle Codes, die zu diesem Typ gehören“ · „Kategorie“ · „So läuft ein Tag „<Name>““ · Spuren „Eintrag“, „Dimmen“, „Nicht stören“, „Licht“, „Ruf-Abfrage“, „Wecker“ · „Wecker“ · „Wecker an diesem Tag“ · „Weckzeit“ · „Stummer Wecker“ · „Bildschirm dimmen“ · „Wann“ · „Von“ · „Bis“ · „Mit %“ · „Schon beim Wecker enden“ · „+ Weiteres Fenster“ · „Weitere Optionen“ · „Nicht stören“ · „Solange gedimmt wird“ · „Während des Kalendereintrags“ · „Licht (Hue)“ · „+ Lichtregel hinzufügen“ |
| B3r | Typ-Seite Rufbereitschaft | Main `zeigeTyp` + `typ.istRuf` | zusätzlich „Sonderfall Rufbereitschaft“ · „Endet spätestens um“ |
| B3k | Typ-Seite Krank | `typ.istKrank` | zusätzlich „Sonderfall Krank“ |
| B3i | Typ-Seite Ignorieren | `typ.istIgnoriert` | „Dieser Eintrag wird übergangen.“ (Satzanfang) |
| B3o | Typ „Ohne Kalendereintrag“ | `typ.fest` | „Feste Zeile: gilt für jeden Tag, an dem im Kalender gar nichts steht“ (Satzanfang) · „Tage ohne Eintrag verhalten sich“ · „Eigene Einstellungen“ · „Wie <Name> (<Code>)“ |
| B4 | Bereich Wecker (3.4) | Main `zeigeWoche` | „Manueller Wecker“ · „Dienst fällt aus“ · „Wie der Wecker klingt“ · „Schlummer-Dauer“ · „Sanft lauter werden“ · „Anlaufzeit“ · „Startlautstärke“ · „Automatische Alarme“ · „Gestellte Wecker“ |
| B4m | Manueller Wecker (Blatt) | Main `manuell.offen` | „Manueller Alarm“ · „Datum:“ · „Schicht:“ · „Weckzeit:“ · „Alarm erstellen“ |
| B4f | Dienst fällt aus (Kalender) | Main `freigabe.offen` | „Dienst fällt aus“ · Monat · Wochentage · „Abbrechen“ · „Freigeben“ |
| B4t | Tag aufgeklappt | Main `d.offen` | Ereignisse oder „An diesem Tag passiert nichts.“ · „Typ „<Name>“ einstellen“ · bei freigegebenem Tag „Zurücknehmen – Dienst findet doch statt“ |
| B5 | Übersicht (3.5) | Main `zeigeUebersicht` | „Nächster Wecker“ · „Nächste Schicht“ · „Dein Zeitstrahl“ · „Gerade:“ · „Als Nächstes:“ · „Legende“ · „Alle Tage im Bereich „Wecker““ · „Mit Google Kalender abgleichen“ |
| B6 | Einstellungen (3.6) | Main `zeigeEinstellungen` | „Kalender“ · „Kalender auswählen“ · „Schichttypen einrichten“ · „Bildschirm dimmen“ · „Bedienungshilfe“ · „Farbwärme“ · „Vorschau“ · „Nicht stören“ · „Zugriff auf „Nicht stören““ · „Wer trotzdem durchkommt“ · „Philips Hue“ · „Tageslicht“ · „Standort“ |
| B7 | Menü (3.7) | Main `drawer` | „Übersicht“ · „Wecker“ · „Philips Hue“ · „System-Status“ · „Einstellungen“; **nicht** vorhanden: „Schicht-Dimmer“ |
| B8 | Ersteinrichtung (3.8) | Onboarding `s0`…`s5` | s0 „Dein Wecker nach Dienstplan“ · „Mit Google anmelden“ · s1 „Kalender durchsuchen“ · s2 „Tage ganz ohne Eintrag“ · „Weiter“ · s3 „Ohne Wecker voreingestellt“ · s4 „Diese vier Schritte sind Pflicht“ (Satzanfang) · „Fertig“ · s5 „Eingerichtet“ · „Nächster Wecker:“ |

Prototyp-Hinweistexte („Prototyp – Werte sind Beispiele …“, „Im Prototyp nur angedeutet“,
„wie heute“, „entfällt – steht jetzt am Typ“) gehören **nicht** in die App.

Für jeden Bildschirm zusätzlich: bei 320 dp Breite nichts abgeschnitten, im Dunkelmodus lesbar.
