# Nachträge für Skills

Headless ist `.claude/**` schreibgeschützt. Lehren aus Umbau-Paketen stehen hier, der Eigentümer
übernimmt sie interaktiv in den passenden Skill und streicht den Eintrag.
Format: Skill · Paket · die normative Zeile · Hergang in einem Satz.

## cfalarm-dimmer-und-dnd

- **#151** · Kurzregel: **`DimWindow.startNurAmWecktag` (Default `false`) lässt einen CLOCK-Start auf
  den Kalendertag der Weckzeit zeigen, nie auf den Vortag; liegt die Weckzeit davor, entfällt das
  Fenster.** Nur das Vorschlaf-Fenster der Nachtdienst-Vorlage setzt es. Der allgemeine Rücksprung
  in `clockAtOrBefore` bleibt (Test „CLOCK-Start zielt auf die Uhrzeit VOR der Weckzeit"); ihn für
  alle Fenster zu ändern bräche „20:00 vor Wecker 05:30". Hergang: Wecker 13:30 → Vorschlaf
  „16:00 bis Wecker" wurde zu 16:00 des Vortags, 21,5 h gedimmt. Bestehende Regeln aus der Vorlage
  tragen das Feld nicht und wirken weiter wie bisher (bewusst, bis Etappe 3 migriert); es gibt noch
  keine Oberfläche dafür, weil der Umbau dort „Nacht davor / Abend danach / Nach dem Dienst" wählen
  lässt. Test: `NachtdienstVorlageVorschlafFensterTest`, `DimWindowResolverTest`.

## cfalarm-kalender-und-schichten

- **#150** · Kurzregel (Abschnitt Schichterkennung): **Ein Muster ohne Buchstabe und ohne Ziffer
  („+" für frei) trifft in `matchesKeywords` nur den GANZEN getrimmten Titel; `ShiftCodeSuggester`
  schlägt solche Titel vor (einziger Ausschluss: Länge > 24).** Zeichenklasse wie die Wortgrenzen
  (`\p{L}\p{N}`). Gemischte Muster („N+") bleiben beim Wortgrenzen-Abgleich. Hergang: Der
  Vorschlag verwarf reine Zeichen mit der Begründung, die Wortgrenzen könnten sie NIE treffen – falsch,
  die Lookarounds trafen ein von Hand eingetragenes „+" längst, und zwar auch in „C++" und „x + y".
  Ein Dienstplan mit „+" konnte den Eintrag nie einordnen. Tests: `ShiftCodeSuggesterTest`,
  `ShiftDefinitionPatternMatchingTest` (Abschnitt 4).
  **In `reference/schichterkennung.md` zu korrigieren** (Absatz „Geraten wird nicht mehr"): (1) „Zwei
  Ausschlüsse … ein Titel ohne einzigen Buchstaben/Ziffer … könnte über Wortgrenzen NIE treffen" ist
  widerlegt, siehe oben; (2) „Muster einer **deaktivierten** Definition unterdrücken keinen
  Vorschlag" widerspricht Code und Test seit v1.29.2 (`ShiftCodeSuggester.suggest` zählt alle
  Definitionen; Test „Muster einer deaktivierten Definition unterdruecken den Vorschlag").

- **#166** · Kurzregel (Abschnitt Schichterkennung): **Die Kategorie eines Typs ist `ShiftDefinition.kategorie`,
  BERECHNET aus den Flags (`isSickDay` → Krank, sonst `isOnCall` → Rufbereitschaft, sonst der gespeicherte
  Wert, sonst Dienst); beide Flags true gilt als Krank, `isOnCall` bleibt dabei stehen.** Ändern nur über
  `mitKategorie()`, das beide Flags setzt; die Flags bleiben die Sprache aller heutigen Leser. Hergang: ein
  gespeichertes Kategorie-Feld neben den Flags liefe auseinander, sobald der alte Editor nur `copy(isSickDay = …)`
  macht; beide-true ist heute speicherbar und schon wirkungslos, weil `ShiftRecognitionEngine` Krank vor jeder
  Schichtspanne abzweigt. Tests: `ShiftKategorieTest`.

## cfalarm-persistenz-und-auth

- **#166** · Kurzregel (Abschnitt Persistenz): **Ein neues Feld an `ShiftDefinition`/`ShiftConfig` hat einen
  Default und wird gegen eine testlokale Kopie der Vorgänger-Klassen geprüft (`AltDecoderKompatibilitaetTest`);
  eine Aufzählung wird als Text gespeichert, nie als kotlinx-Enum.** `ShiftConfigRepository.json` hat kein
  `coerceInputValues`: ein unbekannter Enum-Wert (spätere Version, dann Revert) machte die GANZE
  Konfiguration `Broken`. `formatVersion` der Sicherung bleibt 1, sonst lehnt die Vorversion jede neue Datei
  ab. Hergang: SPEC 4 „Kopieren, nicht verschieben“ – ein zurückgenommener Stand muss lesen, was der neue
  schrieb. Zusätzlich: **`ShiftEditDialog` baut beim Speichern ein NEUES `ShiftDefinition(...)`** – jedes
  neue Feld fällt dort still auf den Default (Hinweis in #174).
- **#166** · Testvorlage: `UmbauTestvorlageTest` findet `..Projektdateien/umbau/testvorlagen/` auch aus einem
  Worktree (über `gitdir:` der `.git`-Datei zum Hauptbaum); fehlt sie, `Assume` mit Meldung.
