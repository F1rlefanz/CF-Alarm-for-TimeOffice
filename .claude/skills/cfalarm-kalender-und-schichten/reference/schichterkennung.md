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
- **Jede Standard-Definition hat neben dem Stationskürzel ein generisches, mehrbuchstabiges
  Muster** (Frühdienst/Spätdienst/Nachtdienst/ZD). „IMCF/IMCS/IMCN/IMCZ" sind die Kürzel EINER
  Station; für einen Kollegen auf einer anderen war der Zwischendienst mit seinem einzigen Muster
  „IMCZ" strukturell tot — kein Treffer, kein Wecker, keine Meldung. `ShiftConfigSerializationTest`
  hält das fest („jede Standard-Definition hat ein Muster ohne Stationskuerzel") plus die Gegenprobe,
  dass die einbuchstabigen Muster keinen unscharfen Teiltreffer gewinnen.
- **Geraten wird nicht mehr — vorgeschlagen wird** (seit v1.23.0). `ShiftCodeSuggester` (rein, ohne
  Android) sammelt die Termintitel, die von KEINEM aktiven Muster getroffen werden, sortiert nach
  Häufigkeit (bei Gleichstand alphabetisch, damit die Reihenfolge nicht springt) und deckelt auf 8 —
  die Deckelung wird als `droppedCount` **benannt**, nicht verschwiegen. Zwei Ausschlüsse, beide
  technisch: zu lang (>24 Zeichen) ist Freitext, und ein Titel ohne einzigen Buchstaben/Ziffer („+"
  steht real im Dienstplan) könnte über Wortgrenzen NIE treffen — ihn vorzuschlagen wäre ein
  Versprechen, das die Erkennung nicht hält. Muster einer **deaktivierten** Definition unterdrücken
  keinen Vorschlag: sie erkennen nichts, für dieses Kürzel fehlt also gerade ein Wecker. Karte „Diese
  Kürzel stehen in deinem Kalender", oben im Schicht-Konfigurationsscreen. **Die App ordnet NICHTS
  selbst zu** — eine stille Automatik, die danebengreift, stellt einen Wecker auf die falsche Uhrzeit.
  `assignCodeToDefinition()` ergänzt das Kürzel als exaktes Keyword und geht über
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
  Default heißt `resetToDefaults()` und gehört dem Nutzer.
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
  nachgezogen und Hue nicht, und niemand erführe davon. Nach einer geänderten Dimm-Regel werden
  `DimScheduleUseCase.enable()` und `DndScheduleUseCase.enable()` neu armiert (best-effort, einzeln
  gefangen): die Fenster werden aus den Regeln berechnet, der nächste Tick stand also noch auf dem
  ALTEN Plan.
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
