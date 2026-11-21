# Schichterkennung und Musterabgleich — Hergang

> Hergang zu den Kurzregeln in `CLAUDE.md` und in der `SKILL.md` daneben: welcher Bug die
> Regel erzwungen hat, welche Messung sie belegt, welche Alternative verworfen wurde.
> Jede Zeile hier hat einmal echten Schaden verhindert — im Zweifel gilt sie, nicht die Intuition.

## Inhalt

- `ShiftConfig.findDefinitionFor(shiftName)`
- `ShiftDefinition.matchesKeywords(eventTitle)`
- Wortgrenzen über Unicode-Kategorien, NICHT `\b`
- Die einbuchstabigen Standard-Keywords „F"/"S"/"N" gehören in die Vorgaben
- Jede Standard-Definition hat neben dem Stationskürzel ein generisches, mehrbuchstabiges
- Geraten wird nicht mehr — vorgeschlagen wird
- Kein stiller Default-Überschreiber der Schicht-Konfiguration — es gab DREI Schreibstellen
- „Auf Standardwerte zurücksetzen" rührt `autoAlarmEnabled` nicht an
- `ShiftViewModel` beobachtet `IShiftUseCase.shiftConfig` und zieht Anzeige, Erkennung UND Alarme
- Umbenennen einer Definition legte Dimmer- und Hue-Regeln lautlos still
- Der manuelle Wecker liest die Schichtliste reaktiv
- `ShiftUseCase.add/update/deleteShiftDefinition` sind ENTFERNT
- `ShiftRecognitionEngine`: EIN unveränderliches Cache-Objekt hinter einer Volatile-Referenz,
- `ShiftDefinition.isEnabled` wird in `performRecognition()` respektiert
- Ein gescheiterter Konfigurations-Read darf NIE zur leeren Definitionsliste werden
- Eine defekte Schicht-Konfiguration erfährt der Nutzer nur über das Log
- Umbau Schichttyp (Etappe 1/2): Sonderzeichen-Muster, Kategorie, Ignorieren, Auswahllisten, Einordnen

---

**Zwei verschiedene Funktionen, zwei verschiedene Regeln — die Verwechslung hat schon zweimal
Wecker gekostet:**

- **`ShiftConfig.findDefinitionFor(shiftName)`** ordnet einem **bestehenden Alarm** eine Definition
  zu (für Hue-Regeln und `isSilent`). Streng nach Genauigkeit gestaffelt: exakter Name → exaktes
  Keyword → Teiltreffer per `contains` **ohne** Wortgrenzen, und dort nur mit Keywords ab
  `MIN_FUZZY_KEYWORD_LENGTH = 2`. Vorher stand im `AlarmReceiver` ein
  `find { name == x || keywords.any { shiftName.contains(it) } }`. `find` nimmt den **ersten**
  Treffer, und die Spätschicht trägt das Keyword **„S"** — das steckt in „S2", „Nacht**s**chicht"
  und „Zwi**s**chendienst". Folge: die S2-Regel feuerte **nie**, die Spätschicht-Regel bei fast
  **jeder** Schicht. Am Emulator gegen die echte Standardkonfiguration reproduziert (v1.11.0).
  `AlarmInfo.shiftName` ist immer der **Name** einer Definition → Stufe 1 trifft im Normalfall
  immer; die Keyword-Stufen sind nur für umbenannte Definitionen da. Wer `contains` nach vorn zieht
  oder `MIN_FUZZY_KEYWORD_LENGTH` senkt, baut den Fehler neu — `ShiftDefinitionMatchingTest` hält
  alle fünf Standard-Schichten fest, zusätzlich gegen eine nicht migrierte Bestandskonfiguration.
- **`ShiftDefinition.matchesKeywords(eventTitle)`** erkennt Schichten in **Kalendertiteln** und
  arbeitet mit **Wortgrenzen**. Dort trifft „F" nur ein alleinstehendes F, nicht „Frühschicht" und
  nicht „Fortbildung". Der `name` zählt ab `MIN_FUZZY_KEYWORD_LENGTH` als zusätzliches Muster (die
  Längengrenze ist Pflicht, sonst kehrt die einbuchstabige Falle über den Namen zurück). Muster
  werden beim Speichern **und** beim Matchen getrimmt (" IMCF" ergab sonst ein Regex, das „IMCF"
  nicht mehr traf, während die UI genau dieses Muster zeigte); leere Muster matchen nie.
- **Wortgrenzen über Unicode-Kategorien, NICHT `\b`.** `WORD_START`/`WORD_END` sind Lookarounds
  `(?<![\p{L}\p{N}_])` / `(?![\p{L}\p{N}_])`. Javas `\b` ist ASCII-basiert: Umlaute und `ß` gelten als
  Nicht-Wortzeichen, wodurch die Semantik für Muster mit führendem Umlaut oder abschließendem `ß`
  **invertiert** war — nachgemessen (10.08.2026): `\büd\b` traf „üd" und „station üd" NICHT, aber
  „xüd", also ausgerechnet mitten im Wort. Betrifft echte deutsche Bezeichnungen („Übergabedienst",
  „Ärztlicher Dienst", Kürzel „ÜD"), und seit der Name als Muster zählt, sagt der Editor dem Nutzer
  ausdrücklich zu, dass sein Schichtname erkannt wird. Bewusst Lookarounds statt `(?U)`: das
  Inline-Flag würde `\w`/`\d`/`\s` im ganzen Ausdruck umdefinieren. Die Konstanten liegen auf
  **Dateiebene**, nicht im Companion — `ShiftDefinition` ist `@Serializable`, ein
  `private companion object` macht `serializer()` mit privat.
- **Die einbuchstabigen Standard-Keywords „F"/"S"/"N" gehören in die Vorgaben.** Sie waren einmal
  entfernt (Begründung: ein privates „Kino mit F" erzeugt einen Wecker um 05:30) — das war die
  Verwechslung der beiden Funktionen oben. Am Emulator gegen den echten Dienstplan-Feed nachgewiesen
  (10.08.2026): ohne sie sank die Erkennung von 4 auf 1 Schicht, denn die realen Titel sind kurze
  Codes („F", „IMCF", „AD1", „FBE", „+"). Abwägung, vom Nutzer ausdrücklich so entschieden: eine nicht
  erkannte Schicht heißt KEIN WECKER — für einen überzähligen gibt es „Nächsten Alarm überspringen",
  für einen verschlafenen gibt es nichts. **Restrisiko akzeptiert und testlich festgeschrieben**
  (`ShiftConfigDefaultsTest` fordert ausdrücklich, dass „F"/"S"/"N" treffen), damit es niemand für ein
  Versehen hält: die Erkennung liest nur selbst ausgewählte Kalender, und das Muster ist entfernbar.
  Der Editor warnt sichtbar bei einem einzeichigen Muster, verbietet es nicht.
- **Die Vorgaben tragen KEINE Stationskürzel** (seit 01.10.2026, #128): Früh/Spät/Nacht mit
  „F"/"S"/"N" plus ausgeschriebenem Namen („Frühdienst"/„Spätdienst"/„Nachtdienst"), Zwischendienst
  mit „ZD". Vorher standen dort „IMCF/IMCS/IMCN/IMCZ" und ein Schichttyp „S2" — die Kürzel EINER
  Station, damit der Entwickler nach Test-Neuinstallationen nicht neu einrichten musste (dafür gibt
  es inzwischen Export/Import). Für jeden anderen Nutzer waren das tote Muster, und „S2" ein aktiver
  Wecker für eine Schicht, die es auf seiner Station nicht gibt. Davor schon war der Zwischendienst
  mit seinem einzigen Muster „IMCZ" anderswo strukturell tot — kein Treffer, kein Wecker, keine
  Meldung. Fremde Kürzel bleiben jetzt unerkannt und erscheinen im Kürzel-Vorschlag (Gegenprobe in
  `ShiftCodeSuggesterTest`). **Bestandsnutzer sind nicht betroffen**: eine gespeicherte
  Konfiguration wird nicht migriert, die Vorgaben greifen nur bei Neuinstallation oder „Auf
  Standardwerte zurücksetzen". `ShiftConfigSerializationTest` hält fest, dass keine Vorgabe ein
  Stationskürzel trägt, plus die Gegenprobe, dass die einbuchstabigen Muster keinen unscharfen
  Teiltreffer gewinnen (auch „S2" fällt nicht auf das „S" der Spätschicht).
- **Geraten wird nicht mehr — vorgeschlagen wird** (seit v1.23.0). `ShiftCodeSuggester` (rein, ohne
  Android) sammelt die Termintitel, die von KEINEM Muster getroffen werden, sortiert nach
  Häufigkeit (bei Gleichstand alphabetisch, damit die Reihenfolge nicht springt) und deckelt auf 8 —
  die Deckelung wird als `droppedCount` **benannt**, nicht verschwiegen. **Einziger Ausschluss: zu
  lang** (> `MAX_CODE_LENGTH` = 24 Zeichen) ist Freitext. Ein Titel ohne einzigen Buchstaben/Ziffer
  („+") wird seit #150 vorgeschlagen — die frühere Begründung, er könne über Wortgrenzen NIE
  treffen, war falsch (Abschnitt „Umbau Schichttyp" unten). **Muster ALLER Definitionen zählen als
  Zuordnung, auch deaktivierter** (seit v1.29.2; v1.29.1 filterte auf `isEnabled`, Test „Muster
  einer deaktivierten Definition unterdruecken den Vorschlag"), ebenso die eines Ignorieren-Typs
  (#167). Angezeigt seit #176 als „Im Kalender gefunden, noch nicht eingeordnet" in der
  Schichttypen-Liste (vorher Karte „Diese Kürzel stehen in deinem Kalender"). **Die App ordnet
  NICHTS selbst zu** — eine stille Automatik, die danebengreift, stellt einen Wecker auf die falsche
  Uhrzeit. `assignCodeToDefinition()` ergänzt das Kürzel als exaktes Keyword und geht über
  `updateShiftConfig()`, damit Speichern, Cache-Invalidierung, Erkennung und Alarm-Sync mitlaufen.
- **Kein stiller Default-Überschreiber der Schicht-Konfiguration — es gab DREI Schreibstellen.**
  Seit `ShiftConfigRepository` „noch nie konfiguriert" (liefert den Default als **Erfolg**) von
  „vorhanden, aber unlesbar" (`Result.failure`, Rohdaten gesichert unter `shift_config_broken`)
  unterscheidet, bedeutet ein Fehlschlag nur noch: defekt — und genau dort ist Überschreiben
  Datenverlust. Alle drei Fallbacks sind ersatzlos entfernt:
  `CalendarViewModel.createAlarmsFromLoadedEvents()` (lief bei JEDEM Kalender-Ladevorgang, also
  jedem App-Start), `ShiftViewModel.loadShiftConfig()` (im `init{}`, also bei JEDER
  ViewModel-Erzeugung) und `CFAlarmApplication.initializeApp()` (bei JEDEM Kaltstart, auch bei rein
  hintergrundgetriebenen Prozessstarts — unbemerkbar). Fail-safe stattdessen: Sync auslassen, Fehler
  loggen bzw. in den UI-State schreiben, bestehende Alarme bleiben gesetzt. Der bewusste Weg zum
  Default ist der Knopf „Auf Standardwerte zurücksetzen" und gehört dem Nutzer (das frühere
  `resetToDefaults()` höbe die Alarm-Pause auf — seit 01.10.2026 entfernt, siehe den nächsten Punkt).
- **„Auf Standardwerte zurücksetzen" rührt `autoAlarmEnabled` nicht an**
  (`resetToDefaultsPreservingAutoAlarm()`). Vorher speicherte der Knopf die komplette
  `getDefaultConfig()`, und die enthält `autoAlarmEnabled = true`: wer die automatischen Alarme im
  Wecker-Tab bewusst ausgeschaltet hatte (eine ECHTE, sofortige Pause) und danach nur seine
  Schichtdefinitionen aufräumte, hob die Pause unwissentlich auf — `updateShiftConfig()` persistiert
  sofort und triggert den Resync. Der Automatik-Schalter gehört dem Wecker-Tab, dieselbe Trennung
  wie bei der Master-Pause; der Rückfrage-Dialog sagt das ausdrücklich. Zurücksetzen und Löschen
  einer Schicht fragen beide vorher nach — es gibt kein Undo.
- **`ShiftViewModel` beobachtet `IShiftUseCase.shiftConfig` und zieht Anzeige, Erkennung UND Alarme
  nach** (`observeExternalConfigChanges()`). Am Gerät gefunden (11.08.2026): der
  Konfigurations-Import schreibt direkt über das Repository, der Store war danach korrekt — aber die
  laufende App zeigte den alten Stand, und schlimmer: die **Alarme** wurden nicht neu gesetzt, eine
  importierte Konfiguration hätte bis zur nächsten 6h-Wartung die ALTEN Zeiten weitergeweckt.
  Bewusst ein Beobachter am gemeinsamen Datenfluss statt eines Aufrufs im Import — dieselbe Lehre wie
  beim Master-Pause-Backstop: ein zentraler Punkt deckt jeden heutigen und künftigen Schreiber ab.
  Eigene Änderungen werden übersprungen, sonst laufen Erkennung und Sync bei jeder Nutzeränderung
  zweimal — und zwar nebenläufig auf derselben Engine-Instanz. **Erkannt werden sie am Merker
  `selfWrittenConfig`, der VOR dem Write gesetzt wird; ein Vergleich nur gegen den UI-State
  `currentShiftConfig` reichte nicht** — DataStore veröffentlicht den neuen Wert typischerweise,
  bevor `edit{}` zurückkehrt, also bevor `onSuccess` den UI-State aktualisiert hat. Der Beobachter
  hielt die eigene Änderung deshalb für eine fremde und löste genau den doppelten, nebenläufigen
  Lauf aus, den er vermeiden sollte.
  **DIE VIERTE TÜR, die dieser Beobachter selbst geöffnet hatte:** Der Flow `shiftConfig` degradiert
  bei einer vorhandenen, aber unlesbaren Konfiguration bewusst auf die Standardwerte (damit die
  Dimmer-/DND-Screens nicht abstürzen). Ungefiltert las der Collector das als „externe Änderung",
  schrieb die Standardwerte in den UI-State und stieß einen Alarm-Sync mit ihnen an — also genau der
  stille Default-Überschreiber, der in `CalendarViewModel`, `ShiftViewModel` und
  `CFAlarmApplication` gerade abgeschafft worden war. Maßgeblich ist deshalb
  `getCurrentShiftConfig()`, das im Defektfall SCHEITERT; nur ein Erfolg gilt als echte Änderung.
  Dieser Filter darf nicht kippen — er ist auch die Vorbedingung des Regelnachzugs unten.
- **`ShiftUseCase.add/update/deleteShiftDefinition` sind ENTFERNT** (v1.23.1, samt
  `IShiftUseCase`-Deklarationen). Sie hatten keinen Aufrufer und waren eine Falle: der Name klang
  passend („eine Schicht hinzufügen"), aber der Pfad speicherte die Konfiguration und invalidierte
  die Caches, ohne die System-Alarme anzufassen — die neue Schicht hätte bis zur nächsten 6h-Wartung
  keinen Wecker bekommen, die gelöschte weitergeklingelt. Der einzige richtige Weg bleibt
  `ShiftViewModel.updateShiftConfig(config)`, weil nur dort `triggerAlarmCreationFromConfigUpdate()`
  → `AlarmUseCase.syncAlarms()` dranhängt.

- **`ShiftRecognitionEngine`: EIN unveränderliches Cache-Objekt hinter einer Volatile-Referenz,
  Prüfung UND Veröffentlichung hinter `recognitionMutex`, PLUS eine Epochen-Kennung.** Der frühere
  Mehrfeld-Cache veröffentlichte seinen Schlüssel VOR dem Ergebnis (`lastRecognitionHash`/
  `lastCacheTime` vor, `cachedMatches` nach `performRecognition()`); dazwischen liegt eine echte
  Suspend-Phase, und ein nebenläufiger Aufrufer mit gleichem Event-Hash traf die Cache-Bedingung und
  bekam den alten Stand — im frischen Prozess eine **leere** Liste. `syncAlarms()` liest „leer" als
  „keine Schichten" und ruft `clearInternalAlarms()`: alle System-Alarme gecancelt, Repository und
  Direct-Boot-Spiegel geleert. Genau das Symptom „0 Alarme trotz korrekt erkannter Schichten"
  (v1.21.0 am Fairphone). **Der Mutex allein reicht nicht:** `clearRecognitionCache()` läuft aus
  synchronem Kontext und kann ihn nicht nehmen — es zählt die Epoche hoch und nullt DANACH den
  Stand; ein Lauf, dessen Grundlage inzwischen invalidiert wurde, veröffentlicht seinen Stand nicht
  mehr als frisch. Das ersetzt das alte `recognitionInProgress` mit 200-ms-Polling-Timeout, das nach
  Ablauf „sicherheitshalber" genau den halbfertigen Zustand las, den es verhindern sollte.
  `ShiftViewModel.processCalendarEvents` bleibt `suspend` (kein fire-and-forget `launch`) — das war
  der v1.21.0-Teilfix und ist weiterhin richtig, deckt aber nur einen der Aufrufer ab.
- **`ShiftDefinition.isEnabled` wird in `performRecognition()` respektiert.** Der Schalter
  „Schichtdefinition aktiviert" war eine Attrappe: gelesen hat ihn nur die Auswahl-UI, die Erkennung
  lief über ALLE Definitionen — eine deaktivierte Schicht verschwand aus den Listen, klingelte aber
  weiter. Gefiltert wird EINMAL, mit Log, wie viele übersprungen wurden. Bewusst **nicht** in
  `ShiftConfig.findDefinitionFor()`: dort wird ein BESTEHENDER Alarm einer Definition zugeordnet, ein
  Filter würde einem Alarm aus der Zeit vor dem Deaktivieren seine Hue-Regeln und das
  `isSilent`-Flag entziehen.
- **Ein gescheiterter Konfigurations-Read darf NIE zur leeren Definitionsliste werden.**
  `performRecognition()` las `getOrNull()?.definitions ?: emptyList()` — der Repository-Pfad für eine
  vorhandene, aber nicht dekodierbare Konfiguration liefert ein `Result.failure`, keine Exception.
  Aus „ich kann die Konfiguration nicht lesen" wurden lautlos 0 Definitionen → 0 erkannte Schichten
  → `syncAlarms()` löscht ALLE Alarme. Jetzt `getOrThrow()`: der Fehler kommt beim Aufrufer an, der
  Cache-Schlüssel bleibt unangetastet, der nächste Versuch läuft frisch.

- **Eine defekte Schicht-Konfiguration erfährt der Nutzer nur über das Log.** Die Rohdaten liegen als
  `shift_config_broken` gesichert, der Sync wird ausgelassen, bestehende Alarme bleiben — aber ein
  sichtbarer Hinweis samt Angebot, die Sicherung zu verwerfen, fehlt noch. Bewusst offengelassen.


## Umbenennen einer Definition legte Dimmer- und Hue-Regeln lautlos still (Prüfrunde 8)

Dimmer- und Hue-Regeln binden über den **Namen** der Schichtdefinition (`shiftPattern`), der Name
ist aber bei gleichbleibender `id` frei änderbar. Eine reine Umbenennung („AD1" → „Frühdienst
AD1") legte damit beide Regelarten still: das Licht ging zur Weckzeit nicht mehr an, das
Dimm-Fenster verschwand, und im Modus „Schlaf-Fenster folgt dem Dimmer" fiel auch das DND-Fenster
weg. **Und die Oberfläche bestätigte den Gegenzustand:** die Regelliste zeigte die Regel durchgehend
als aktiv, der Hue-Editor gleichzeitig „kein Muster gewählt" und „Bei <Altname>-Schicht".

Nachgezogen wird über `zieheRegelmusterNach()` (`planeSchichtUmbenennungen()` bestimmt aus dem Stand
VOR und NACH der Änderung, welche `id` einen neuen Namen hat, dann
`Dim-/HueRuleUseCase.renameShiftPattern()`). Was daran tragend ist:

- **Der Aufruf hängt am ZENTRALEN Beobachter `observeExternalConfigChanges()`, nicht nur am
  Aufrufer-Gate `updateShiftConfig()`.** Eine Rücksicherung oder ein Gerätewechsel bringt dieselbe
  Definition (gleiche `id`) unter anderem Namen zurück, und genau dieser Fall kommt ausschließlich
  über den Beobachter herein — er hätte die Migration sonst umgangen. Dieselbe Lehre wie beim
  zentralen Master-Pause-Backstop.
- **Der Degradierungs-Filter davor („die vierte Tür") ist Vorbedingung.** Käme die
  Notlage-Standardkonfiguration durch, zöge die Migration jede Regel auf einen Standardnamen um —
  ein Datenverlust, den kein Nutzer verursacht hat.
- **Das Universalmuster wird nie mitgezogen.** Es ist ein Sentinel (`"ALL"`), kein Definitionsname;
  eingesetzt ergäbe es eine Regel, die plötzlich nur noch eine Schicht trifft.
- **`withContext(NonCancellable)`**, weil hier ein konsistenter Zustand HERgestellt wird — bricht
  der `viewModelScope` mittendrin ab (der Nutzer verlässt den Screen), wäre sonst der Dimmer
  nachgezogen und Hue nicht, und niemand erführe davon.
- **Nacharmiert wird ERST NACH dem Alarm-Sync**, nicht direkt nach dem Schreiben:
  `DimScheduleUseCase.enable()`/`DndScheduleUseCase.enable()` hängen im `finally` von
  `triggerAlarmCreationFromConfigUpdate()` (`ShiftViewModel.armiereZeitkettenNeu()`, best-effort,
  einzeln gefangen). Der `ShiftSpanStore` wird ausschließlich in `syncAlarms()` beschrieben und
  armiert selbst nichts; lief `enable()` davor, mischte es die frisch nachgezogenen Namenslisten
  (NEUER Name) mit Spannen, die noch den ALTEN trugen — Fenster ohne Cutoff und ohne Ausnahme, und
  weil der nächste Tick auf dieses zu späte Ende fällt, blieb der falsche Plan die ganze Nacht
  stehen. Im `finally`, weil der Sync regelmäßig ausfällt (Master-Pause, keine Termine,
  unvollständige Liste) — dann sind die Listen trotzdem schon neu.

### Nachtrag 21.08.2026: die drei übersehenen NAMENSLISTEN

`zieheRegelmusterNach()` zog anfangs nur die beiden REGELARTEN mit. Drei weitere Stellen binden
ebenfalls über den Namen und vergleichen dabei **exakt**: `dnd_oncall_shifts` (Rufbereitschaft,
`DndOnCallCutoffResolver`), `dnd_shift_excluded_shifts` (`DndShiftSpanResolver`) und
`dim_night_default_excluded_shifts` (`DimScheduleUseCase`). Am Gerät stand dort noch der Altname,
der On-Call-Cutoff griff nicht mehr, und „Nicht stören" blieb in der Nacht vor der Rufbereitschaft
über 05:00 hinaus an — sichtbar war nichts, weil die Chips aus den AKTUELLEN Namen gebaut werden.
Nachgezogen über `DndPrefs.renameShiftName()` / `DimOverlayPrefs.renameShiftName()`. Daraus:

- **Eine reine SCHREIBWEISEN-Änderung ist eine Umbenennung.** `planeSchichtUmbenennungen()` stieg
  bei `equals(ignoreCase = true)` aus — richtig gedacht für die Regeln (die vergleichen selbst
  `ignoreCase`), falsch für die Listen. Der Abbruch ist jetzt der EXAKTE Vergleich; für die Regeln
  ist der zusätzliche Schreibvorgang folgenlos.
- **Blockade heißt für die Listen nicht „nichts tun".** Gehört der gespeicherte Altname nach einem
  Namenstausch einer ANDEREN Definition, ist der Eintrag nicht tot, sondern scharf für die falsche
  Schicht. Nur in diesem Fall (`BlockierteUmbenennung.alterNameGehoertJetztAnderer`, exakt
  verglichen wie die Listen selbst) wird er über `removeShiftName()` geräumt — bei den übrigen
  Blockaden ist der Altname herrenlos, und Löschen wäre Datenverlust ohne Gegenwert.
- **Der Hinweistext ist zusammengesetzt, kein `when`.** Beide Planlisten können gleichzeitig gefüllt
  sein; der Text nennt die betroffene Schicht beim Namen, sagt, was geräumt wurde, und behauptet
  nicht mehr pauschal, es sei nichts mitgezogen worden.
- **Fehlschläge werden gemeldet, nicht geschluckt** (`ShiftUiState.regelNachzugHinweis`) — eine
  nicht nachgezogene Regel ist eine Funktion, die der Nutzer bewusst eingerichtet hat und die ab
  jetzt nichts mehr tut.

**Bewusst offen:** Zeigt ein gespeichertes `shiftPattern` aus einem Import auf einen Namen, den es
nicht mehr gibt, steht im Regeleditor kein Optionsfeld ausgewählt, während daneben „Bei
<Altname>-Schicht" steht. Die Migration verkleinert den Fall, beseitigt ihn nicht.

## Der manuelle Wecker liest die Schichtliste reaktiv (Prüfrunde 8)

`AlarmViewModel` fror die verfügbaren Schichtdefinitionen in einem Snapshot aus dem `init{}`-Block
ein. Eine soeben geänderte Weckzeit kam dort nie an — und die Karte **bestätigte die alte
ausdrücklich** („Weckzeit: 05:00"), der manuelle Wecker wurde dann auch mit ihr armiert. Der
Kalender-Sync repariert das nicht: er schont manuelle Alarme (`keepManualAlarms`). Jetzt zwei
Stufen: `observeAvailableShifts()` hält die Liste reaktiv (bei gleicher `id` wird das FRISCHE Objekt
übernommen, die angezeigte Weckzeit immer nachgerechnet), und unmittelbar vor dem Armieren wird die
Definition über `getCurrentShiftConfig()` frisch aufgelöst — die armierte Zeit stammt aus dieser
Lesung, nicht aus dem Anzeigezustand.

**Statuszeile je Schicht (#70):** bindet über `definition.name` (nie Keywords) an Dimm-, Hue- und
DND-Regeln, rein lesend — kein Eintrag im Umbenennungs-Nachzug nötig, weil nichts gespeichert wird.

## Umbau Schichttyp (Etappe 1/2, Oktober 2026): Sonderzeichen-Muster, Kategorie, Ignorieren, Auswahllisten, Einordnen

- **Sonderzeichen-Muster („+") treffen den GANZEN Titel (#150).** Ein Muster ohne Buchstabe und ohne
  Ziffer trifft in `matchesKeywords` nur den ganzen getrimmten Titel (`matchesAsWholeWord`),
  Zeichenklasse `\p{L}\p{N}` wie die Wortgrenzen; gemischte Muster („N+") bleiben beim
  Wortgrenzen-Abgleich. Hergang: der Vorschlag verwarf reine Zeichen mit der Begründung, die
  Wortgrenzen könnten sie NIE treffen — falsch, die Lookarounds trafen ein von Hand eingetragenes
  „+" längst, und zwar auch in „C++" und „x + y". Ein Dienstplan mit „+" konnte den Eintrag nie
  einordnen. Tests: `ShiftCodeSuggesterTest`, `ShiftDefinitionPatternMatchingTest` (Abschnitt 4).
- **Die Kategorie ist berechnet, nicht gespeichert neben den Flags (#166).** `ShiftDefinition.kategorie`
  liest `isSickDay` → Krank, sonst `isOnCall` → Rufbereitschaft, sonst den gespeicherten Wert, sonst
  Dienst; beide Flags true gilt als Krank, `isOnCall` bleibt dabei stehen. Geändert wird nur über
  `mitKategorie()`, das beide Flags setzt; die Flags bleiben die Sprache aller älteren Leser. Ein
  gespeichertes Kategorie-Feld NEBEN den Flags liefe auseinander, sobald ein Schreiber nur
  `copy(isSickDay = …)` macht; beide-true war schon vorher speicherbar und wirkungslos, weil
  `ShiftRecognitionEngine` Krank vor jeder Schichtspanne abzweigt. Test: `ShiftKategorieTest`.
- **Ignorieren wird erst NACH der Schicht-Suche gefragt (#167),** anders als Krank. Unter „erste
  passende Definition gewinnt" hätte ein vorn stehender Ignorieren-Typ einen Dienst geschluckt;
  deshalb stehen in `IgnorierenErkennungTest` die Ignorieren-Typen absichtlich VOR dem Dienst. Die
  Engine liefert solche Termine getrennt (`erkenne()` → `Erkennung.ignorierteTermine`, derselbe
  Cache, dieselbe Epoche). Der Code-Vorschlag brauchte keine Änderung (jedes Muster zählt als
  Zuordnung, Test „ein ignorierter Code erscheint nicht als Vorschlag"). Die Home-Karte „Keine
  Schicht erkannt" schließt Ignorieren aus (Zählung und Titel-Kostprobe, nächster Punkt).
- **Auswahllisten über EINE Funktion (#171).** `istWaehlbareSchicht()` (`SchichttypAuswahl.kt`) ist
  exakt die Menge, aus der `ShiftRecognitionEngine` Schichttreffer macht; die Engine ruft sie aber
  nicht selbst (Kerntest-Nähe). Angeschlossen: manueller Wecker (Liste UND frische Abfrage im
  Erstellungspfad), Dimmer-Regel-Ziele, DND-Ausnahmen, Hue-Regelmuster, Zählung
  `enabledShiftTypeCount` der Übersichtskarte. Zweite Frage derselben Datei:
  `istIgnorierterTermin(titel)` (Gegenstück zu `istKrankmeldung`) für die Titel-Kostprobe der Karte
  „Keine Schicht erkannt" (`kostprobeTitel`) — ohne sie stünde ein ignorierter Code dort als „im
  Kalender steht: TB" und legte nahe, ihn als Muster einzutragen. Sie prüft einfach (aktiv +
  Ignorieren + Muster), nicht in Engine-Reihenfolge: die Kostprobe steht nur, wenn nichts erkannt
  wurde. **Verhaltensänderung, bewusst:** Dimmer-Regeln und DND-Ausnahmen schlossen vorher nur Krank
  aus, jetzt auch ausgeschaltete Typen (wie Hue und manueller Wecker schon vorher) — eine bestehende
  Regel/Ausnahme auf einen ausgeschalteten Typ bleibt gespeichert, ist aber bis zum Wiedereinschalten
  nicht als Chip/Option sichtbar. Hergang: die Bedingung stand an sechs Stellen kopiert, die
  Ignorieren-Kategorie hätte sechs Änderungen verlangt, und eine vergessene hätte einen Typ
  angeboten, der nie greift. Tests: `SchichttypAuswahlTest`, `SchichttypAuswahlVerdrahtungTest` (mit
  den alten Bedingungen an vier Stellen alle vier rot, 17.10.2026); Hue-Karte und Übersichts-Zählung
  sind Einzeiler im Composable ohne eigenen Test.
- **Einordnen (#169, #176, #190).** `SchichttypEinordnen.neuerTypAusCode()` speichert NICHT — erst
  „Erstellen" auf der Typ-Seite (`TypSeiteSpeicherung.erstelle` → `ShiftViewModel.legeTypAusCodeAn`),
  sonst wird eine geratene Weckzeit scharf. Voreinstellung „Wecker an" nur bei Dienst und
  Fortbildung (`SchichttypEinordnen.voreinstellung`, Festlegung des Betreuers; der Prototyp nennt nur
  Dienst „dienstartig"). `ignorieren()` geht über `withCodeAssignedTo()` an den ersten AKTIVEN
  Ignorieren-Typ — ein ausgeschalteter würde dabei eingeschaltet und ignorierte still auch seine
  alten Codes. Ein neu angelegter Ignorieren-Typ startet OHNE Muster: trüge er den Code schon, sähe
  `withCodeAssignedTo()` im Normalfall nichts zu tun und lieferte `null`, der Klick verpuffte (die
  Mutationsprobe zeigte genau diese Lücke im ersten Testsatz). `legeTypAusCodeAn` hängt den Typ an
  und ruft `withCodeAssignedTo(code, neu.id)` nur, wenn der Typ den Code noch als Muster trägt (hat
  der Nutzer ihn im Editor entfernt, bleibt der Code im Fund) — sonst behielte ein ausgeschalteter
  Typ ein zweites Exemplar, und `findDefinitionFor` entschiede nach Listenreihenfolge. **Danach
  stellt er `isEnabled` auf den Wert des Editors zurück**, weil `withCodeAssignedTo()` das Ziel
  immer einschaltet: ein im Editor bewusst ausgeschalteter Dienst-Typ wurde sonst eingeschaltet
  gespeichert und hätte an jedem Tag des Codes geweckt (Prüfer ohne Umsetzungskontext, Test „ein im
  Editor ausgeschalteter neuer Typ wird ausgeschaltet gespeichert"). **Achtung:** der NAME zählt ab
  zwei Zeichen als Muster, ein Typ mit `name == code` trifft den Code also auch ohne Muster. Die
  Liste „Gehört zu" filtert nur `kategorie != IGNORIEREN` (`gehoertZuZiele`): Krank und
  ausgeschaltete Typen bleiben wählbar (ein ausgeschalteter wird dabei eingeschaltet, die Zeile sagt
  es). Tests: `SchichttypEinordnenTest`, `EinordnenAblaufTest`.
