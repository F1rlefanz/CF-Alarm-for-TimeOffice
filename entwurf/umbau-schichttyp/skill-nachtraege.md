# Nachträge für Skills

Headless ist `.claude/**` schreibgeschützt. Lehren aus Umbau-Paketen stehen hier, der Eigentümer
übernimmt sie interaktiv in den passenden Skill und streicht den Eintrag.
Format: Skill · Paket · die normative Zeile · Hergang in einem Satz.

## cfalarm-wecker-und-boot

- **#168** · Kurzregel (neben „Verstrichene Weckzeit ist KEINE entfernte Schicht"): **Ein Typ mit
  `weckerAn = false` ist erkannt, aber ohne Wecker: `syncAlarms()` führt seinen Treffer als
  `SyncKandidat` mit `neuerAlarm = null` (`weckerAus = true`) – wie einen mit verstrichener Weckzeit.
  Schritt 1 räumt einen gepaarten Wecker ab (Cancel vor Delete), OHNE „Schicht entfernt"; Spannen und
  Krank-Logik sehen den Treffer weiter. `weckerAn` ist nicht `isEnabled`** (das schaltet die Erkennung
  ab und mit ihr die Spanne). Den Treffer stattdessen aus den Kandidaten wegzulassen, wäre der Weg, den
  `isEnabled = false` heute nimmt: die Paarung fände den gestellten Wecker nicht, Schritt 1 meldete ihn
  als entfernte Schicht. Hergang: Festlegung des Betreuers in #168. Test: `WeckerAusAmTypSyncTest`
  (auch Paarungsrunde (b) bei gleichzeitig neuer Feed-Kennung). Bekannt und hingenommen: aus → an
  meldet über `notifyCreated` „Neue Schicht erkannt", wie heute ein wieder aktivierter Typ (es gibt
  keinen Merker, warum der Wecker fehlte).

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

- **#167** · Kurzregel (Abschnitt Schichterkennung, neben der Krank-Regel): **Ein Typ der Kategorie Ignorieren
  ergibt keine Schicht; die Engine liefert solche Termine getrennt (`erkenne()` → `Erkennung.ignorierteTermine`,
  derselbe Cache, dieselbe Epoche) – `getAllMatchingShifts` enthält sie NICHT.** Anders als Krank wird
  Ignorieren erst NACH der Schicht-Suche gefragt: ein Ignorieren-Typ verdrängt nie einen echten Treffer
  („F TB" mit Dienst F und Ignorieren TB ist Schicht F, egal in welcher Reihenfolge die Typen stehen – im
  Zweifel wecken). Wer einen neuen Konsumenten der Erkennung baut, bekommt Ignoriert – wie Krank – nur,
  wenn er danach fragt. Hergang: unter „erste passende Definition gewinnt" hätte ein vorn stehender
  Ignorieren-Typ einen Dienst geschluckt; deshalb stehen in `IgnorierenErkennungTest` die Ignorieren-Typen
  absichtlich VOR dem Dienst. Der Code-Vorschlag brauchte keine Änderung (jedes Muster zählt als
  Zuordnung, Test „ein ignorierter Code erscheint nicht als Vorschlag"). **Noch offen, nicht in #167:** die
  Home-Karte „Keine Schicht erkannt" (`HomeTabContent`) filtert nur Krank heraus, nicht Ignorieren –
  `enabledShiftTypeCount` (:698) nimmt #171 mit, die Titel-Kostprobe (:639) steht dort als Hinweis;
  erreichbar erst, wenn ein Ignorieren-Typ angelegt werden kann (#169/#176).

- **#169** · Kurzregel (Abschnitt Schichterkennung, neben `withCodeAssignedTo`): **Einordnen läuft über
  `SchichttypEinordnen`: `neuerTypAusCode()` speichert NICHT (erst der Editor nach Bestätigung, sonst wird
  eine geratene Weckzeit scharf), `ignorieren()` geht über `withCodeAssignedTo()` an den ersten AKTIVEN
  Ignorieren-Typ – ein ausgeschalteter wird nie Ziel, sonst schaltete `withCodeAssignedTo()` ihn ein und
  ignorierte still auch seine alten Codes.** Hergang: (1) Ein neu angelegter Ignorieren-Typ startet OHNE
  Muster – trüge er den Code schon, sähe `withCodeAssignedTo()` beim Normalfall (frisch gefundener Code an
  keinem Typ) nichts zu tun und lieferte `null`, der Klick verpuffte; die Mutationsprobe hat genau diese
  Lücke im ersten Testsatz gezeigt (Test „ignorieren eines Codes, den noch kein Typ traegt“). (2) Wer den
  neuen Typ aus `neuerTypAusCode()` später speichert, hängt ihn an UND ruft `withCodeAssignedTo(code,
  neu.id)` – sonst behält ein ausgeschalteter Typ, der den Code noch trägt, ein zweites Exemplar, und
  `findDefinitionFor` entscheidet nach Listenreihenfolge. Voreinstellung: Wecker an nur bei Dienst und
  Fortbildung (Festlegung des Betreuers, weicht vom Prototyp ab, der nur Dienst „dienstartig“ nennt).

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

## cfalarm-ui-und-navigation

- **#170** · Kurzregel (Abschnitt UI-Texte/Compose-Layout): **Die Schichttypen-Liste ist NUR in der Anzeige nach
  Kategorien gruppiert (`schichttypBloecke`); die gespeicherte Reihenfolge der Typen wird nie umsortiert und nie
  zurückgeschrieben.** Hergang: die Erkennung nimmt den ersten Treffer in Listenreihenfolge, eine alphabetisch
  oder nach Kategorie sortierte Speicherung hätte stumm geändert, welcher Wecker gilt. Bearbeiten und Löschen
  arbeiten auf den Objekten der gespeicherten Liste (per `id`), nie auf Indizes einer Anzeigeliste. Der Code einer
  Zeile ist das erste nicht leere Muster (`listenCode()`), ohne Muster steht „–“. Test: `SchichttypGruppenTest`.
- **#170** · Kurzregel (Abschnitt Layout-Fallen, bei „Text links, Bedienelement rechts“): **Eine Zeile mit Marke
  links UND Papierkorb als eigener Spalte rechts nimmt der Textspalte beide ab — bei 320 dp blieben 146 statt der
  208 dp der alten Karte, und die mit geschützten Leerzeichen gebauten Abschnitte der Statuszeile
  (`SchichtFolgen.alsText`, „Dimmen: allgemeine Regel“) brachen mitten im Wort.** Der Papierkorb steht deshalb in
  der Namenszeile (gemessen am uiautomator-Dump, gleiche Daten, Dichte 540: 145,8 → 199,7 dp). Aufgefallen ist es
  dem unabhängigen Prüfer durch Rechnen; belegt hat es erst der Dump. Wer eine Zeile aus Marke · Text · Knopf baut,
  rechnet die Textbreite bei 320 dp nach.
- **#170** · Kurzregel (Abschnitt Nutzertexte): **Die Kurzform eines Typs mit `weckerAn = false` nennt kein Licht
  (`alsTextOhneLicht`)** — Hue-Regeln laufen nur zu einem Alarm (`AlarmReceiver` → `executeRulesForAlarm`,
  `HueSmartScheduler` liest nur Alarme), SPEC E8. Dimmen und „Nicht stören“ bleiben (das Dimm-Fenster fällt ohne
  Weckzeit auf die Uhrzeit zurück). **Die feste Zeile „Ohne Kalendereintrag“ zeigt fest „kein Wecker“, auch wenn
  `ShiftConfig.ohneKalendereintragWie` gesetzt ist:** das Feld wirkt noch nirgends, „verhält sich wie F (…)“ wäre
  eine Aussage über einen Ablauf, den es nicht gibt. Wer die Wahl „Wie <Typ>“ baut, stellt Erkennung UND Kurzform
  im selben Paket um — nie nur die Anzeige.
- **#170** · Hinweis zu `cfalarm-ui-pruefen`: `tools/geraet/pruefe_texte.py` scrollt nur NACH UNTEN und liest ab der
  aktuellen Position — vorher die Liste an den Anfang wischen, sonst meldet es „fehlt“ für Titel, die oberhalb
  liegen (bei 320 dp „NICHT BESTANDEN“, ab Anfang BESTANDEN). Mit Git Bash und Windows-Python: `--datei` als
  `$(cygpath -w …)` übergeben, `/tmp/…` löst Python nicht auf.
