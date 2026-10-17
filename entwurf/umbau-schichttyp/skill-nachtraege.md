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
- **#172** · Kurzregel (neben der #168-Zeile): **Steht am selben Tag wie eine Rufbereitschaft ein
  Treffer der Kategorie Dienst (der nachgetragene Einsatz), führt `syncAlarms()` den Ruf-Treffer als
  `SyncKandidat` ohne Alarm (`OhneWecker.RUF_UEBERNOMMEN`) – derselbe Weg wie „Wecker aus": ein
  gestellter Ruf-Wecker geht über die Paarung (Cancel vor Delete) OHNE „Schicht entfernt". Die
  Ruf-SPANNE bleibt in `persistShiftSpans`, denn aus ihr lesen `RufbereitschaftAbfrage` und
  `DndOnCallCutoffResolver`. Die Frage stellt EINE reine Funktion, `shift/RufUebernahme.kt`
  (`uebernommeneRufTermine`); der Tag ist das Datum des Termin-BEGINNS, nicht der Weckzeit** (beim
  Nachtdienst fallen sie auseinander; ganztägig = sein Datum). Nur Kategorie Dienst übernimmt, und
  zwar unabhängig von `weckerAn` und verstrichener Weckzeit des Dienstes: der Tag folgt dem Dienst-Typ.
  Hergang: Festlegung des Betreuers in #172 (SPEC E3, Codes beim Eigentümer AD/AD1). Den Treffer
  stattdessen aus `shiftMatches` zu entfernen, nähme die Spanne mit – die stündliche Abfrage und das
  DND-Ende 05:00 fielen an genau dem Tag weg, an dem der Chef angerufen hat. Tests:
  `RufUebernahmeTest`, `RufUebernahmeSyncTest` (auch Paarungsrunde (b) bei neuer Feed-Kennung).
  Bekannt und hingenommen wie bei #168: wird der Einsatz gestrichen, meldet der Sync den F-Wecker zu
  Recht als entfernt und den zurückkehrenden Ruf-Wecker über `notifyCreated` als „Neue Schicht erkannt".

- **#175** · Kurzregel (neben der #168/#172-Zeile): **Ein Wecker an einem Tag ohne Kalendereintrag
  („wie <Typ>“) ist ein `SyncKandidat` mit der Kennung `ohne-eintrag:<Datum>`
  (`AlarmUseCase.OHNE_EINTRAG_PRAEFIX`, Test `istOhneEintragWecker()`), `shiftStartTime = 0`, Name,
  `shiftId` und `isSilent` vom Verweistyp, alles deterministisch. Er läuft durch den normalen
  Delta-Pfad (Cancel vor Delete, Freigabe-Gate, Skip-Anker, Leerlisten-Räumung, denn die Kennung ist
  NICHT leer), meldet aber NIE `notifyCreated`/`notifyUpdated`/`notifyDeleted` und zählt nicht in
  der Krankmeldung. Er nimmt an Paarungsrunde (b) auf KEINER Seite teil. Keine Schichtspanne. Der
  Zweig `shiftMatches.isEmpty()` greift nur, wenn auch keine leeren Tage anstehen.** Hergang: Mit
  Runde (b) übernähme ein echter Termin desselben Typs auf einem bisher leeren Tag (gleiche Weckzeit,
  gleiche Schicht) den synthetischen Wecker still, und die neue Schicht meldete sich nie. **Heute**
  fällt weg, wenn eine schon BEENDETE Spanne aus dem `ShiftSpanStore` (Stand VOR dem Schreiben dieses
  Laufs) heute berührt (`heuteDurchBeendeteSpanneBelegt`), oder wenn die Spannen unlesbar sind.
  Hier ist „im Zweifel wecken“ die falsche Richtung: der Abruf beginnt bei jetzt, nach dem
  Nachtdienst bis 07:00 sähe heute leer aus, und der Verweistyp klingelte nach dem Dienst. Bekannte
  Restlücke: ein heute beendeter, noch nicht eingeordneter Code hat keine Spanne. Ausgelagert:
  Boot-Validierung löscht diese Wecker (#182), und der Weckbildschirm nennt bei `shiftStartTime = 0`
  die Weckzeit als Schichtbeginn, heute schon beim manuellen Wecker (#183). Test:
  `OhneEintragSyncTest`.

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
  Zuordnung, Test „ein ignorierter Code erscheint nicht als Vorschlag"). Die Home-Karte „Keine Schicht
  erkannt" schließt Ignorieren seit #171 aus (Zählung und Titel-Kostprobe, siehe #171 unten).

- **#171** · Kurzregel (Abschnitt Schichterkennung, neben `findDefinitionFor`/`matchesKeywords`): **Welche
  Typen eine AUSWAHLLISTE anbieten darf, entscheidet EINE Funktion: `ShiftDefinition.istWaehlbareSchicht()`
  (`SchichttypAuswahl.kt`, aktiv UND Kategorie weder Krank noch Ignorieren; Dienst, Rufbereitschaft, Frei,
  Urlaub, Fortbildung bleiben wählbar – SPEC E2). Wer eine Liste baut, ruft `waehlbareSchichttypen()` bzw.
  `waehlbareSchichtNamen()`, nie eine eigene `isEnabled && !isSickDay`-Bedingung.** Das ist exakt die Menge,
  aus der `ShiftRecognitionEngine` Schichttreffer macht (`schichtDefinitionen`), die Engine ruft die Funktion
  aber nicht selbst (Kerntest-Nähe, nicht Teil von #171). Angeschlossen: manueller Wecker (Liste UND frische
  Abfrage im Erstellungspfad), Dimmer-Regel-Ziele, DND-Ausnahmen, Hue-Regelmuster, Zählung
  `enabledShiftTypeCount` der Übersichtskarte. **Bewusst NICHT angeschlossen:** `ShiftViewModel.schichtFolgen`
  (Statuszeile der Typ-Liste) – sie braucht den Ignorieren-Typ, um „wird übergangen" zu sagen
  (`SchichtFolgen.kt`). Zweite Frage derselben Datei: `istIgnorierterTermin(titel)` (Gegenstück zu
  `istKrankmeldung`) für die Titel-Kostprobe der Karte „Keine Schicht erkannt" (`kostprobeTitel`) – ohne sie
  stünde ein ignorierter Code dort als „im Kalender steht: TB" und legte nahe, ihn als Muster einzutragen.
  Sie prüft einfach (aktiv + Ignorieren + Muster) und nicht in der Engine-Reihenfolge „erst Schicht, dann
  Ignorieren": die Kostprobe steht nur, wenn nichts erkannt wurde. **Verhaltensänderung, bewusst:** die
  Listen von Dimmer-Regeln und DND-Ausnahmen schlossen vorher nur Krank aus, jetzt auch ausgeschaltete Typen
  (wie Hue und manueller Wecker schon vorher) – eine bestehende Ausnahme/Regel auf einen ausgeschalteten
  Typ bleibt gespeichert, ist aber bis zum Wiedereinschalten nicht als Chip/Option sichtbar. Hergang: die
  Bedingung stand an sechs Stellen kopiert; die Ignorieren-Kategorie (#166/#167) hätte sechs Änderungen
  verlangt, und eine vergessene hätte einen Typ angeboten, der nie greift. Tests: `SchichttypAuswahlTest`
  (reine Funktion), `SchichttypAuswahlVerdrahtungTest` (Dimmer, DND, manueller Wecker inkl. frischer
  Abfrage – mit den alten Bedingungen an diesen vier Stellen sind alle vier rot, am 17.10.2026 gemessen).
  Hue-Karte und Übersichts-Zählung sind Einzeiler im Composable und ohne eigenen Test.

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

- **#173** · Kurzregel (Abschnitt Kalender-Datenfluss, neben „Eine unvollständige Eventliste ist KEINE
  Löschgrundlage“): **Für „Ohne Kalendereintrag“ wirkt die Unvollständigkeit UMGEKEHRT – eine lückenhafte
  Liste lässt Tage leer aussehen und stellt Wecker. Die Frage beantwortet EINE reine Funktion,
  `shift/TageOhneEintrag.kt` (`tageOhneEintrag`): leere Liste = kein Tag (Kalender-Abwahl), nur Tage von heute
  bis VOR den Tag, der das Abruf-Ende enthält, nur Ignoriertes zählt nicht als Eintrag, ein Ende genau 00:00
  deckt seinen Endtag nicht ab, `freieTage` fallen weg, Verweistyp muss `istWaehlbareSchicht()` UND `weckerAn`
  sein (also auch Krank = kein Tag).** Hergang: Festlegung des Betreuers in #173; Krank steht nicht in dessen
  Liste, folgt aber aus „wie <Typ>“ (ein Krank-Eintrag stellt nie einen Wecker) und aus #171 (die
  Verweis-Auswahl bietet nur wählbare Typen an). **Offene Falle für den Einbau (#175):** der Abruf beginnt bei
  JETZT (`timeMin = now`) – ein heute schon beendeter Termin (Nachtdienst bis 07:00, Sync um 08:00) fehlt, heute
  sieht leer aus, und eine spätere Weckzeit des Verweistyps klingelte nach dem Nachtdienst. Die reine Funktion
  hat kein Gedächtnis; Vorbilder sind `AlarmUseCase.zeitgebundeneEnden` und `ShiftSpanStore.mische`. Test:
  `TageOhneEintragTest` (je Festlegung ein Block; Mutationsprobe am 17.10.2026: zwölf Mutationen, jede von
  ihrem Test rot).

## cfalarm-persistenz-und-auth

- **#166** · Kurzregel (Abschnitt Persistenz): **Ein neues Feld an `ShiftDefinition`/`ShiftConfig` hat einen
  Default und wird gegen eine testlokale Kopie der Vorgänger-Klassen geprüft (`AltDecoderKompatibilitaetTest`);
  eine Aufzählung wird als Text gespeichert, nie als kotlinx-Enum.** `ShiftConfigRepository.json` hat kein
  `coerceInputValues`: ein unbekannter Enum-Wert (spätere Version, dann Revert) machte die GANZE
  Konfiguration `Broken`. `formatVersion` der Sicherung bleibt 1, sonst lehnt die Vorversion jede neue Datei
  ab. Hergang: SPEC 4 „Kopieren, nicht verschieben“ – ein zurückgenommener Stand muss lesen, was der neue
  schrieb. Zusätzlich: **`ShiftEditDialog` baut beim Speichern ein NEUES `ShiftDefinition(...)`** – jedes
  neue Feld fällt dort still auf den Default — **seit #174 behoben:** `ShiftEditFormular.speichern()` geht vom
  bestehenden Typ aus (`copy`, dann `mitKategorie`), ein künftiges Feld am Typ (Etappe 3: Dimm-Fenster, DND)
  bleibt dort ohne Zutun erhalten, solange niemand wieder einen neuen Konstruktoraufruf baut.
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

- **#174** · Kurzregel (Abschnitt Nutzertexte, neben #170): **Der Schicht-Editor wählt die Kategorie (sieben
  Radio-Zeilen, `ShiftDefinitionTexte.kategorieBeschreibung`) statt der Schalter „Krankmeldung“ und „Rufbereitschaft“,
  und speichert über `ShiftEditFormular.speichern()` – vom bestehenden Typ aus (`copy` + `mitKategorie`), nie als
  neuer `ShiftDefinition(...)`.** Bei Krank UND Ignorieren zeigt er statt Wecker/Weckzeit/Stummer Wecker nur den
  Hinweis (`zeigtWeckerBlock`): beide ergeben nie einen Wecker (#142, #167), ein Feld dort zeigte einen Ablauf, den es
  nicht gibt. Die Werte bleiben im Typ stehen (E2), ein Wechsel zurück zu Dienst bringt sie wieder. Bei „Wecker an diesem
  Tag“ aus sind Weckzeit und Stummer Wecker ausgegraut, und ein kaputtes Zeitfeld sperrt das Speichern nicht
  (`zeitZumSpeichern`: bisherige Zeit, sonst 06:00 – nie 00:00). **Festlegung des Betreuers erweitert:** „einzige
  Ausnahme Krank“ gilt hier auch für Ignorieren – der Prototyp blendet den Tag-Block bei `kat === 'ign'` aus
  (`zeigeTag`), derselbe Grund wie bei Krank. **Der Editor nimmt einen vorbelegten, nicht gespeicherten Typ über den
  Parameter `vorlage`** (`shift` bleibt der bestehende): `isNewShift` = `shift == null`, also hängt
  `ShiftConfigScreen` ihn an (sein `map` auf die `id` träfe sonst ins Leere), und der Umbenennungs-Hinweis erscheint
  nicht. Verdrahtet wird `vorlage` im Paket „Im Kalender gefunden“ – bis dahin ruft niemand sie auf.
  **Textwahrheit statt Prototyp-Wortlaut:** (1) „Stummer Wecker“ verspricht im Prototyp und in SPEC 3.3 „Licht … ein
  Lichtwecker“; heute überspringen `AlarmReceiver` und `HueSmartScheduler` bei stiller Schicht jede Hue-Regel, der Text
  sagt deshalb „Eine Hue-Lichtregel startet dann nicht“ (Test `Der Stummer-Wecker-Hinweis verspricht kein Licht …`).
  **Etappe 4 (E8) tauscht den Satz**, wenn Hue stillen Weckern folgt. (2) Die Urlaub-Beschreibung lässt „und Dimmer“ weg:
  der Dimmer hängt bis Etappe 3 an Regeln, „Wecker aus“ nimmt ihn nicht weg (eine ALL-Regel gilt auch für Urlaub).
  (3) „Wecker an diesem Tag“ sagt „Stellt … einen Wecker“, nicht „Klingelt“ – ob er klingelt, regelt der Stumm-Schalter.
  (4) Der Hinweis unter „Schichtdefinition aktiviert“ nennt für „kein Wecker, Zeitfenster trotzdem“ jetzt den Schalter
  „Wecker an diesem Tag“ (seit #168 erkannt, Spanne bleibt), nicht mehr „Stille Schicht“. Tests: `ShiftEditFormularTest`
  (Mutationsprobe am 17.10.2026: alter Konstruktor-Bau → 5 von 9 rot; Kategoriewechsel setzt Zeit/Muster zurück → der
  Kategoriewechsel-Test rot), `ShiftDefinitionTexteTest`. **Website zieht nicht mit:** `docs/advanced-setup.html:90-93`,
  `index.html:186-189`, `troubleshooting.html:96-97` nennen noch die Schalter „Krankmeldung“, „Stille Schicht“ und
  „Rufbereitschaft“ – Etappe 9 (SPEC 5.4), Issue mit `umbau` + `spaeter` angelegt.
- **#174** (Fortsetzung, Prüfer-Befunde) · **Die Ein-Buchstaben-Warnung im Editor richtet sich nach Kategorie UND
  Wecker-Schalter** (`ShiftDefinitionTexte.einBuchstabeWarnung(muster, kategorie, weckerAn)`): nur ein Typ, der wirklich
  einen Wecker stellt, bekommt „weckt dich dann an freien Tagen“, Krank behält seinen Text, Ignorieren und „Wecker aus“
  bekommen `EIN_BUCHSTABE_OHNE_WECKER`. **Der Aktiviert-Hinweis hat bei Ignorieren einen eigenen Text**
  (`IGNORIEREN_AKTIVIERT_HINWEIS`): der allgemeine verwies auf „Wecker an diesem Tag“, den es dort nicht gibt. Hergang: der
  unabhängige Prüfer (Opus, nur Diff/Issue/Spec/Prototyp) fand beides; erst #174 macht diese Zustände im Editor
  einstellbar. **Weitergereicht:** #181 (Fenster-Ende folgt bei Wecker aus weiter `alarmTriggerTime`, Etappe 3), #180
  (Website). Wer künftig einen Text unter einem Editor-Feld baut, prüft ihn gegen ALLE Kategorien, in denen das Feld
  sichtbar ist - nicht nur gegen Dienst.
