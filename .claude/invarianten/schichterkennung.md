# Schichterkennung & Musterabgleich

> Ausgelagert aus `CLAUDE.md` (17.08.2026). Dort steht die Kurzregel, hier der Hergang:
> warum die Regel existiert, welcher Bug sie erzwungen hat, welche Messung sie belegt.
> **Vor Änderungen in diesem Bereich lesen.**

---

### Schichterkennung & Musterabgleich

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
  Eigene Änderungen werden per Gleichheitsvergleich übersprungen, sonst laufen Erkennung und Sync bei
  jeder Nutzeränderung zweimal — und zwar nebenläufig auf derselben Engine-Instanz.
- **`ShiftUseCase.add/update/deleteShiftDefinition` sind ENTFERNT** (v1.23.1, samt
  `IShiftUseCase`-Deklarationen). Sie hatten keinen Aufrufer und waren eine Falle: der Name klang
  passend („eine Schicht hinzufügen"), aber der Pfad speicherte die Konfiguration und invalidierte
  die Caches, ohne die System-Alarme anzufassen — die neue Schicht hätte bis zur nächsten 6h-Wartung
  keinen Wecker bekommen, die gelöschte weitergeklingelt. Der einzige richtige Weg bleibt
  `ShiftViewModel.updateShiftConfig(config)`, weil nur dort `triggerAlarmCreationFromConfigUpdate()`
  → `AlarmUseCase.syncAlarms()` dranhängt.

