# Schicht-Dimmer (Regel-Aufloesung) — Hergang

> Hergang zu den Kurzregeln in `CLAUDE.md` und in der `SKILL.md` daneben: welcher Bug die
> Regel erzwungen hat, welche Messung sie belegt, welche Alternative verworfen wurde.
> Jede Zeile hier hat einmal echten Schaden verhindert — im Zweifel gilt sie, nicht die Intuition.

## Inhalt

- Ein Modell statt drei Quellen — und warum die eigentliche Lehre die KOPPLUNG ist (v1.34.0)
- Die Modellmigration und warum der App-Start als einziger Anlass zu wenig war (v1.34.0)
- Das Aufräumen der Dimm-VORSCHAU darf nicht am `viewModelScope` hängen
- Ein Kalendertag kann ZWEI Schichten haben (Prüfrunde 8)
- Der Regelkonflikt an einem solchen Tag wird entschieden, nicht aufgelöst (Prüfrunde 8)
- Dimmer-Regeln binden über den Namen der Schichtdefinition (Prüfrunde 8)
- Pro Kalendertag GENAU eine Regel
- `findRuleForShift` nimmt den ERSTEN Treffer
- Leere Fensterliste = Unterdrückung dieser Nacht
- CLOCK↔CLOCK = lückenlos jede Kalendernacht
- Zeitrechnung: echte Wanduhrzeit + Datums-Arithmetik, niemals „Mitternacht-Instant + Minuten" und
- Die Fenster-Schleifen beginnen einen Kalendertag VOR `today` (`LOOKBACK_DAYS`)
- Das Fenster-Ende ist HALB OFFEN (`first <= now < last`)
- Die Tick-Kette darf nicht abreißen
- Dimmer-Korrektur-Override (Feature C, seit v1.20.0) lebt im DataStore, nicht in-memory
- `DimNotificationService` klemmt den `strengthDelta` selbst, nicht nur den abgeleiteten
- Jeder Setter, der einen `DimOverlayPrefs`-Wert schreibt, MUSS direkt danach
- `DimAccessibilityService.isRunning()` (der einzige echte Bound-Status) wird seit v1.22.1 in
- Diese Log-Zeile allein genuegte NICHT — ein wirkungsloses Dimm-Fenster muss SICHTBAR sein
- „Kurz hell, dann wieder dunkel“ — das Verschwinden ist nicht loggbar, die Rueckkehr schon
- `DimCorrectionNotifier.show()` prüft `NotificationManagerCompat.areNotificationsEnabled()`

---

- **Ein Modell statt drei Quellen (v1.34.0) — und die eigentliche Lehre ist die KOPPLUNG, nicht die
  Zahl drei.** Bis v1.33.0 hatte der Dimmer drei gleichrangige Fenster-Quellen mit drei eigenen
  Schaltern: „Wellness/Wind-down" (dimmt eine Weile vor JEDER Weckzeit des Alarm-Bestands),
  „Schicht-Regeln" (`DimRule`) und den eingebauten „Nacht-Standard" (`buildDefaultNightSpans`,
  seit v1.17.0: ab fester Uhrzeit bis zum nächsten Wecker, mit eigener Verdunkelung/Wärme und einer
  eigenen Ausnahmen-Chipliste). Seit v1.34.0 gibt es **nur noch die Regeln** und **einen** Schalter
  (`dim_enabled`).

  **Warum es die drei überhaupt gab:** jede war für sich eine berechtigte Bequemlichkeit — „ich will
  nachts einfach Ruhe, ohne erst eine Regel zu bauen". Gebaut wurden sie NEBEN die Regel-Funktion,
  nicht AUF sie. **Und genau das ist die Lehre: die Regeln konnten „jede Nacht 22–7 außer
  Nachtdienst" schon FÜNF TAGE, BEVOR der Nacht-Standard gebaut wurde** — `cb9a94d` (21.07.2026,
  Regelsystem), `58890f0` (23.07.2026, CLOCK↔CLOCK lückenlos jede Nacht + Nachtdienst-Ausnahme über
  die leere Fensterliste), und erst `ff5b5e2` (28.07.2026) legte den Nacht-Standard daneben. Die
  Bequemlichkeitsschicht duplizierte eine vorhandene Fähigkeit als eigene Quelle mit eigenem
  Zustand, statt sie als Vorlage auf diese Fähigkeit zu setzen. Wer das nächste Mal „das geht mit
  den Regeln zwar, ist aber umständlich" denkt: die Antwort ist eine Vorlage, die eine gewöhnliche
  Regel anlegt (heute `SchnellstartVorlage`), nie eine zweite Quelle.

  **Der Konstruktionsfehler war die KOPPLUNG der drei Schalter, nicht ihre Zahl.** Der Nacht-Standard
  wirkte NUR an Tagen, die keine aktivierte Regel ohnehin abdeckte, **und nur solange die Quelle
  „Regeln" eingeschaltet war**. Damit änderte das Umlegen EINES Master-Schalters unsichtbar die
  BEDEUTUNG der anderen: wer „Regeln" ausschaltete, schaltete damit auch den Nacht-Standard scharf,
  wo er vorher wirkungslos war — und wer eine UNIVERSAL-Regel aktivierte, machte den Nacht-Standard
  samt seiner ausdrücklich gesetzten Ausnahmen vollständig wirkungslos, während dessen Schalter
  weiter „an" zeigte. Das ist die Fehlerklasse „angezeigt, wirkt nicht", nur eine Ebene höher: nicht
  eine Regel wirkt nicht, sondern ein ganzer Schalter bedeutet je nach Stand eines anderen Schalters
  etwas anderes. Aus einem Modell mit dieser Eigenschaft lässt sich der reale Zustand nicht ablesen —
  weder vom Nutzer noch beim Debuggen, und die Migration musste diese Ausschließlichkeit eigens
  nachbilden (`nachtStandardWirksam`), um überhaupt sagen zu können, was vorher galt.

  **Ausgelöst hat den Umbau der gemeldete Fehler vom 23.08.2026** — gedimmter Bildschirm und
  laufendes „Nicht stören" um 08:48 nach einem Spätdienst-Wecker 12:30; Hergang und Messung stehen
  beim Ende-Anker `ALARM_SONST_CLOCK` weiter unten und werden hier nicht wiederholt. Er schloss die
  Ausdrucklücke und machte damit zugleich den Nacht-Standard entbehrlich: ein Fenster
  `CLOCK 22:00 → ALARM_SONST_CLOCK 07:00` IST der komplette Nacht-Standard, für jede Kalendernacht,
  als EIN Fenster — ohne das Paar aus Rückwärts- und Vorwärts-Fenster und ohne die
  Folgetag-Bedingung `nextDayCoversTonight`, die auf ein ANDERES Datum schaut und deren erster Wurf
  real eine ganze Nacht hat durchfallen lassen (03./04.08.2026, siehe unten). Wellness ist ein
  Fenster `ALARM −X → ALARM +0`.

  **Was der alte Nacht-Standard konnte und warum sein Wegfall dokumentiert bleiben muss** (der Code
  ist weg, die Fälle bleiben gültige Prüfszenarien): er lief pro Tag über ZWEI unabhängige
  Fenster-Prüfungen, nicht eine exklusive (Fix v1.21.1) — ein Rückwärts-Fenster (nur falls der Tag
  selbst einen Wecker hat: die Nacht VOR diesem Wecker) UND ein Vorwärts-Fenster (immer, AUSSER der
  FOLGETAG hat selbst einen Wecker). Bis v1.21.0 waren beide exklusiv an „Tag hat keinen Wecker"
  gebunden, was die Nacht NACH einem späten Wecker komplett durchfallen ließ, sobald der Folgetag
  ebenfalls weckerlos war — real reproduziert am 03./04./05.08.2026 (S2-Wecker 14:30 → Tag ohne
  Kalendertermin → Frühschicht 05:30), und mit dem Dimmen fiel über DND-Modus 1 auch „Nicht stören"
  weg. **Dieser Fall ist NICHT erledigt, er ist nur anders begründet:** im Ein-Modell kann er aus
  einem ANDEREN Grund zurückkehren (Ende-Anker ohne obere Schranke), deshalb ist er in
  `DimWindowResolverTest` als Regel-Szenario erhalten geblieben statt gelöscht zu werden. Ein
  Ausschluss galt immer tages-granular und über ALLE Schichten des Tages (`istTagAusgeschlossen`);
  heute drückt eine spezifische Regel mit LEERER Fensterliste dasselbe aus. Die eigene
  Verdunkelung/Wärme (`nightDefaultStrength`/`nightDefaultWarmth`, v1.17.1) war ein ausdrücklicher
  Nutzerwunsch — die globalen Wellness-Werte mitzuverwenden war der erste Wurf und wurde
  zurückgewiesen; im Ein-Modell trägt ohnehin jede Regel ihre eigenen Werte.
  `DimmerAltmodellReferenz` (Testcode) hält `buildDefaultNightSpans` wortgetreu eingefroren fest —
  sie wird NICHT mitgepflegt, sie existiert nur als Vergleichsmaßstab der Migration.

  **Vier bewusst gezahlte Preise** (aus `DimmerModellMigrationTest`, dort mit Zahlen; nicht als neue
  Bugs melden): (1) ein SPÄTER Wecker verlängert die Nacht nicht mehr bis mittags — das war der
  Anlass, also die Absicht; (2) ein MANUELLER Wecker bekommt kein eigenes Wind-down-Fenster mehr
  (ALARM-verankerte Regelfenster lösen über die Schichtspannen auf, die alte Wellness-Quelle über
  den Alarm-Bestand); (3) die Nacht vor einem regelbelegten Tag endet an dessen Weckzeit statt an
  der festen Morgenuhrzeit — hellere Richtung; (4) an einem unterdrückten Tag entfällt das
  Wind-down: beides zugleich ist im Ein-Modell nicht ausdrückbar, und „bleibt hell" ist die
  harmlose Richtung. Ebenso zusammengeführt: zwei überlappende Quellen mit verschiedener
  Verdunkelung werden zu einer.

- **Die Modellmigration: der App-Start als einziger Anlass war zu wenig, und der Import brauchte
  einen eigenen Weg** (`DimmerModellMigration`, v1.34.0). Die alten Preference-Schlüssel bleiben
  bewusst im Store liegen (eine Version Rückweg), gelesen werden sie nur noch hier. Vier Dinge sind
  tragend, jedes davon war ein gefundener Ausfall:
  **(1) Zwei Anlässe.** `dim_enabled` ist ein NEUER Schlüssel mit Default `false`; bis die Migration
  lief, steigt `computeWindows()` sofort mit leerer Fensterliste aus. Angestoßen wurde sie zuerst nur
  aus `MainActivity.onCreate` — wer die App nur zum Wecken benutzt und sie nach einem
  Play-Auto-Update tagelang nicht öffnet, hätte ab der ersten Nacht nach dem Update gar kein Dimmen
  mehr gehabt, und über DND-Modus 1 wäre das Nachtfenster von „Nicht stören" mitgefallen — sichtbar
  nirgends. Zweiter Anlass ist deshalb `AlarmMaintenanceService.rescheduleSideChannels`, die einzige
  Kette, die diesen Nutzer alle 6 h erreicht. Der Marker macht jeden weiteren Aufruf zum No-op, die
  Reihenfolge ist gleichgültig.
  **(2) Entsperrungs-Gate.** Weil die Migration damit aus einer Hintergrundkette kommen kann, fragt
  sie selbst den `UserManager`: der `@MainDataStore` liegt im CE-Storage und liefert vor der ersten
  Entsperrung still LEERE Preferences, ohne zu werfen. Eine Migration darüber sähe eine leere
  Alt-Konfiguration, schriebe `dim_enabled = false`, setzte den Marker — und niemand sähe je wieder
  nach.
  **(3) Nur übernehmen, was auch GEWIRKT hat.** Siehe die Kopplung oben: war der Nacht-Standard durch
  eine aktive UNIVERSAL-Regel wirkungslos, entsteht weder sein Fenster noch eine seiner Ausnahmen —
  sonst würde aus einer inerten Ausnahme eine scharfe Unterdrückung, die der UNIVERSAL-Regel genau
  die Nächte nimmt, in denen sie heute dimmt. War die Regel-Quelle aus, wird ihr inerter Bestand
  deaktiviert statt vom einen neuen Schalter scharf gemacht. Und das Wind-down-Fenster wird NICHT an
  eine Regel mit leerer Fensterliste gehängt — die Leere ist die Nachtdienst-Ausnahme, ein Fenster
  darin nähme ihr genau die Bedeutung (gefunden, nachdem der erste Gleichheitsbeweis das nicht sehen
  konnte: seine Testdaten hatten keinen Kalendertag mit ZWEI Schichten).
  **(4) Der Import ist ein eigener Anlass.** Der Marker ist zu Recht aus dem Backup ausgeschlossen
  (ein importierter Marker überspränge auf einem unmigrierten Gerät die Migration). Damit fehlte aber
  jeder Weg, eine importierte ALT-Konfiguration noch zu übersetzen — Gerätewechsel: frische
  Installation setzt beim ersten Start den Marker, danach schreibt der Import die alten Schlüssel roh
  in den Store, `dim_enabled` bleibt `false`, es dimmt nichts, und der neue Hauptschalter machte
  stattdessen die auf dem Altgerät inerten Regeln scharf. `ConfigBackupUseCase.import` nimmt den
  Marker jetzt zurück — **aber nur, wenn die Datei die ALTEN Schlüssel mitbringt UND kein
  `dim_enabled`.** Die Gegenrichtung ist die gefährlichere: ein Übersetzungslauf über eine
  Ein-Modell-Datei läse die fehlenden alten Schlüssel als „alles aus" und schaltete den frisch
  importierten Dimmer sofort wieder ab.
  **Fail-safe:** scheitert etwas, bleibt der Dimmer aus (heller Bildschirm, Wecker unberührt), der
  Marker ungesetzt (Retry beim nächsten Anlass), WARN im Release-Log. Ein halb geschriebener
  Regelbestand ist ungefährlich, weil `dim_enabled` erst ganz am Ende und nur im Erfolgsfall auf
  `true` geht. Der Beweis der Verlustfreiheit liegt in `DimmerModellMigrationTest`: für sechs
  Alt-Konfigurationen wird die 14-Tage-Zeitleiste einmal mit `DimmerAltmodellReferenz` und einmal
  mit den migrierten Regeln berechnet und auf Gleichheit geprüft.

- **Schnellstart-Vorlagen legen ECHTE Regeln an — das ist ihr ganzer Zweck** (`SchnellstartVorlage`,
  v1.34.0). Drei Vorlagen oben in der Regelliste: „Nacht-Dimmen" (UNIVERSAL,
  `CLOCK 22:00 → ALARM_SONST_CLOCK 07:00`), „Nachtdienst-Rhythmus" (Regel auf eine gewählte Schicht
  mit ZWEI Fenstern an einem Kalendertag: `SHIFT_END +0 → CLOCK 14:00` und `CLOCK 15:00 → ALARM +0`)
  und „Schicht ausnehmen" (Regel auf eine gewählte Schicht mit LEERER Fensterliste). Jede geht über
  `saveRule()` und damit über den `ZeitkettenArmierer`. Der Unterschied zur abgelösten
  eingebauten Quelle ist der Punkt: was entsteht, steht danach **sichtbar** in der Regelliste und
  lässt sich ändern und löschen. Damit der Nutzer das auch sieht, öffnet der Bildschirm den Editor
  der neuen Regel (`neueRegelId` als Ereignis); `ruleById` fällt dabei auf die zuletzt angelegte
  Regel zurück, weil der DataStore-Fluss sie erst Millisekunden später emittiert und der Editor
  sonst leer aufginge. **Zwei Fallen, beide gefunden:** eine Vorlage darf keine ZWEITE aktivierte
  Regel auf demselben `shiftPattern` anlegen (`findRuleForShift` nimmt den ERSTEN Treffer, die zweite
  wäre tot — und der Konflikt-Hinweis der Regelliste fängt das nicht ab, er entsteht nur aus
  VERSCHIEDENEN Regeln an einem Tag); stattdessen benennt ein Dialog die vorhandene und bietet an,
  sie zu öffnen — ein stiller Abbruch wäre dieselbe Fehlerklasse gewesen. Ausgeschaltete Regeln
  blockieren bewusst nicht. Und ohne Schichtnamen wird gar nichts geschrieben: eine Regel auf leerem
  Muster träfe keine Schicht und stünde trotzdem als aktiv in der Liste.

- **Der Fenster-Editor muss JEDEN Anker anbieten, den Erklärtext, Vorlage oder Migration erzeugen
  können.** Der Editor bot am START nur „Feste Uhrzeit" und „Schichtende" an — `DimAnchor.ALARM` war
  über die Oberfläche nicht erreichbar, obwohl der Erklärtext im Dimmer-Tab und die Migration genau
  dieses Fenster als Ersatz für die ausgebaute Wellness-Quelle benennen. Zwei Ausfälle in einem:
  wer der Anleitung folgte, fand den Knopf nicht und griff zur nächstbesten Wahl (feste Uhrzeit —
  vor einem Spätdienst mit Weckzeit 12:30 also stundenlang zu früh); und ein migriertes Fenster
  `ALARM −120 → ALARM +0` fiel im Editor in den `else`-Zweig: ein Uhrzeit-Feld mit dem Feld-Default
  20:00, kein markierter Anker, und der `onPicked`-Callback schrieb `startAnchor = CLOCK`. **Ein
  einziger Tipp auf das Feld** machte aus „zwei Stunden vor dem Aufstehen" dauerhaft „jede Nacht ab
  20:00" — vor einem Spätdienst ein Fenster von 16,5 Stunden. Die Anker-Listen und die
  Feld-Zuordnung liegen deshalb als prüfbare Konstanten neben dem Composable, und
  `DimmerFensterEditorAnkerTest` stellt sie gegen die Fenster, die die Migration wirklich erzeugt.

- **Das Aufräumen der Dimm-VORSCHAU darf nicht am `viewModelScope` hängen** (v1.24.0, an ZWEI
  Stelle: `DimmerRulesViewModel.previewRule()` — „Regel
  testen"). Beide schrieben mit `setActiveOverlay(true, …)` einen **persistenten** Zustand und
  stellten den regulären erst nach `delay(5s)` wieder her. `DimAccessibilityService` beobachtet nur
  `DimOverlayPrefs.renderState` und hat eine vom ViewModel völlig unabhängige Lebensdauer: verlässt
  der Nutzer die App innerhalb dieser 5 s (zweimal Zurück beendet die Activity, oder Wegwischen aus
  den Recents), lief `applyCurrentState()` nie, der Schreibvorgang aber schon — der Bildschirm
  bleibt bis zu 85 % verdunkelt, **systemweit**. Geheilt hätte das erst der nächste Dimm-Tick, und
  wer die Vorschau zum Ausprobieren nutzt, hat typischerweise noch gar keine Fenster-Quelle aktiv,
  es kommt also womöglich keiner. Deshalb je ein eigener `previewScope` (mit
  `CoroutineExceptionHandler` — der `SupervisorJob` allein deckt das NICHT ab), Zurücksetzen im
  `finally` unter `NonCancellable`, und ein zweiter Tipp lässt die laufende Vorschau per
  `cancelAndJoin()` ZUERST aufräumen (sonst schaltet deren `finally` die gerade neu eingeschaltete
  sofort wieder aus). Vorbild ist `HueLightUseCase.followUpScope`. Diese Scopes werden bewusst
  **nicht** in `onCleared()` gecancelt — genau das wäre der Bug. **Am Gerät belegt (14.08.2026):**
  `dim_overlay_on` vorher `false` → während der Vorschau `true` → App nach ~1,5 s verlassen → 8 s
  später wieder `false`. Nachweis NICHT über die Layer-Anwesenheit führen: `render(false)` fährt nur
  das Alpha auf 0 und lässt `CFAlarmDimLayer` stehen.

- **Ein Kalendertag kann ZWEI Schichten haben** (Prüfrunde 8, v1.30.0). `buildRuleSpans` und das
  damalige `buildDefaultNightSpans` bauten sich beide eine `HashMap<LocalDate, AlarmSlot>` mit
  „first wins";
  weil die Slots nach Weckzeit sortiert ankamen, gewann immer die früheste Schicht, und **jede
  weitere Schicht desselben Tages existierte für Regelauswahl und Nacht-Ausnahme nicht**. Getroffen
  hat das genau den Alltagsfall, für den die App gebaut ist: Frühdienst plus anschließende
  Rufbereitschaft — derselbe Fall, für den `DndPrefs.onCallShifts` und der
  `DndOnCallCutoffResolver` eigens existieren. Folge: der an der Nacht-Standard-Karte
  ausdrücklich gesetzte Ausschluss der Rufbereitschaft wirkte nicht, der Bildschirm dimmte
  mitten im Dienst, und über DND-Modus 1 („Schlaf-Fenster folgt dem Dimmer") schaltete „Nicht
  stören" in genau der Nacht ein, in der Erreichbarkeit der Zweck des Dienstes ist. Beide
  Die verbliebene Funktion geht über `slotsByDate()` (`groupBy` + explizite Sortierung nach
  `triggerTime`, bei Gleichstand `shiftName`). **Die Sortierung ist Teil der Zusicherung, nicht
  Kosmetik:** „früheste Schicht des Tages" darf nicht stillschweigend an der Lieferreihenfolge des
  `ShiftSpanStore` hängen — sonst kippt ein laufendes Fenster zwischen zwei Ticks auf einen anderen
  Anker, und die Fenster-Identität (`range.last` + `strength`, siehe `isOverrideStale`) wird
  instabil. Für den damaligen Nacht-Standard galt zusätzlich `istTagAusgeschlossen()`: **ein
  Ausschluss IRGENDEINER Schicht des Tages nahm den ganzen Tag heraus** (der Ausschluss war immer
  tages-granular). Beides ist mit dem Ein-Modell (v1.34.0) entfallen; im heutigen Modell drückt
  eine spezifische Regel mit LEERER Fensterliste denselben Ausschluss aus, und
  `Pruefrunde8MehrereSchichtenProTagTest` hält den Fall genau so fest — die Zusicherung „früheste
  Weckzeit des Tages entscheidet" ist unverändert.

- **Der Regelkonflikt an einem solchen Tag wird ENTSCHIEDEN, nicht aufgelöst** — und das Entscheiden
  muss sichtbar sein. Der erste Wurf des Fixes ließ einen Tag, an dem zwei Schichten zwei
  VERSCHIEDENE spezifische Regeln treffen, kommentarlos ganz aus. Das war schlimmer als der Bug:
  weil spezifische Regeln UNIVERSAL verdrängen **und** ein regelbelegter Tag damals auch noch aus
  dem Nacht-Standard fiel, blieb der Tag **komplett ohne Dimm-Quelle** — während die Regelliste
  beide Regeln unverändert als aktiv zeigte. Seit dem Ein-Modell (v1.34.0) gilt das erst recht: es
  gibt keine zweite Quelle mehr, die einspringen könnte. Also dieselbe
  Fehlerklasse „aktiv angezeigt, wirkt nicht", gegen die der Fix gebaut wurde, plus ein Bruch von
  „ein Zustand, der eine Funktion dauerhaft anhält, muss sichtbar sein". Heute gilt: **die Regel der
  frühesten Schicht gewinnt**, der Fall geht als **WARN** ins Release-Log (eine Zeile je Berechnung,
  nicht je Tag — die Schleife läuft bei jedem Tick; bewusst nur Datum und Regel-IDs, Regel- und
  Schichtnamen sind Nutzertexte), und die Regelliste trägt an der verdrängten Regel einen Hinweis
  mit Wirkung, Grund in Alltagssprache und Ausweg (`DimmerRulesViewModel.verdraengteRegeln` →
  `DimmerSettingsScreen`). Ein Log allein wäre wieder „angezeigt, wirkt nicht" — niemand liest
  Logcat. **Wirkung und Anzeige teilen eine einzige Funktion** (`regelFuerTag`, aufgerufen von
  `buildRuleSpans` und von `findRuleConflicts`) — zwei Implementierungen würden auseinanderdriften,
  und eine Anzeige, die von der Wirkung abweicht, ist schlechter als gar keine. **Verworfene
  Alternative: die Fenster beider Regeln vereinigen.** Das wäre additiv (Bruch von „pro Kalendertag
  GENAU eine Regel"), dimmte mehr als jede der beiden Regeln für sich, und bei widersprechenden
  Parametern für dieselbe Minute entstünde eine dritte, von niemandem konfigurierte Einstellung.
  **Bewusst offen (NICHT als neuen Bug melden):** der Konflikt wird entschieden, nicht aufgelöst —
  die unterlegene Regel wirkt an einem solchen Tag nicht, sie sagt es nur. Zwei Randfälle sind
  bewusst KEIN Konflikt: eine getroffene Regel mit leerer Fensterliste unterdrückt den ganzen Tag
  (ausdrückliche Nutzerentscheidung, Ergebnis „bleibt hell" ist das Bestellte), und
  `findRuleConflicts` schweigt, wenn die Regel-Quelle ganz aus ist oder die Schichtspannen nicht
  lesbar sind — lieber kein Hinweis als ein falscher. `DimWindowResolver.KONFLIKT_HORIZONT_TAGE`
  muss dem privaten `HORIZON_DAYS` von `DimScheduleUseCase` entsprechen, sonst behauptet die
  Oberfläche einen Zeitraum, den der Scheduler gar nicht plant.

- **Dimmer-Regeln binden über den NAMEN der Schichtdefinition** (`DimRule.shiftPattern`), und der
  Name ist bei gleichbleibender `id` frei änderbar. Eine reine Umbenennung legte die Regel deshalb
  lautlos still — das Dimm-Fenster verschwand, in DND-Modus 1 fiel das DND-Fenster gleich mit weg,
  und die Regelliste zeigte die Regel durchgehend als aktiv (Prüfrunde 8). Jedes Umbenennen zieht
  die Regeln jetzt über `DimRuleUseCase.renameShiftPattern(alt, neu)` mit; der Nachzug hängt am
  zentralen Beobachter, nicht am Aufrufer-Gate. Hergang und die verbleibende Lücke (ein verwaistes
  `shiftPattern` aus Import/Backup) stehen im Skill `cfalarm-kalender-und-schichten` — die
  Hue-Regeln haben dieselbe Bindung und dieselbe Migration.

- **Pro Kalendertag GENAU eine Regel** (`DimWindowResolver.buildRuleSpans`): Schicht-Tag →
  `findRuleForShift` je Schicht (exakter Schichtname → sonst UNIVERSAL), freier Tag →
  `findRuleForFreeDay` (FREI → sonst UNIVERSAL). Eine spezifische Regel **überschreibt** UNIVERSAL
  für dieses Datum komplett — nicht additiv. Deshalb ist UNIVERSAL „alle **Tage**" (Schicht + frei +
  Urlaub), nicht „alle Schichten" — das UI-Label heißt entsprechend „Alle Tage (Universal)".
  Welche Regel bei mehreren Schichten des Tages gewinnt, steht in den beiden Punkten oben.
- **`findRuleForShift` nimmt den ERSTEN Treffer.** Zwei aktivierte Regeln auf denselben
  `shiftPattern` → die zweite ist tot. „ND-Nacht unterdrücken UND Tagschlaf dimmen" ist darum EINE
  Nachtschicht-Regel mit nur dem Tag-Fenster (unterdrückt die Nacht, weil sie UNIVERSAL überschreibt
  und selbst kein Nacht-Fenster hat), nicht zwei. Wer hier ein „find-all" draus macht, ändert die
  Semantik still.
- **Leere Fensterliste = Unterdrückung dieser Nacht** (die Nachtdienst-Ausnahme), NICHT „keine
  Regel". `windows.isEmpty()` ist bewusst bedeutungstragend — nicht wegoptimieren.
- **CLOCK↔CLOCK = lückenlos jede Kalendernacht** (unabhängig von Schicht/frei); ALARM/SHIFT_END sind
  schicht-relativ und brauchen eine **Schichtspanne** an dem Datum (bis v1.25.1 stand hier „einen
  Alarm" — seither speist `DimScheduleUseCase` diese Slots aus `ShiftSpanStore`, weil ein Alarm die
  Weckzeit nicht überlebt). Wer CLOCK↔CLOCK wieder schicht-relativ macht, reißt „immer 22–7 außer
  ND" wieder auf. `DimWindowResolverTest` hält das Kern-Szenario fest.
- **Zeitrechnung: echte Wanduhrzeit + Datums-Arithmetik, niemals „Mitternacht-Instant + Minuten" und
  niemals fixe 24h-Millis.** An den DST-Umstellungstagen ist ein Kalendertag 23 h bzw. 25 h lang — aus
  22:00 wurde 23:00 bzw. 21:00, und Dimmen UND DND-Modus 1 (rechnet über dieselben Fenster)
  verschoben sich um eine Stunde. Dieselbe Falle wie beim DND-Rufbereitschaft-Cutoff.
- **Die Fenster-Schleifen beginnen einen Kalendertag VOR `today` (`LOOKBACK_DAYS`).** Sonst erzeugte
  ein am Vorabend gestartetes Fenster nach dem Datumswechsel keine Iteration mehr: jede Neuberechnung
  nach 00:00 (App-Update, 6h-Wartung, Master-Pause-Resume, ViewModel-Setter, Tap auf die
  Korrektur-Notification) hielt die laufende Nacht für „kein aktives Fenster" und schaltete Dimmen +
  DND ab. Vergangene Spannen sind harmlos, weil `activeSpan` per „now in range" und der Scheduler per
  „> now" filtert. **Achtung bei Tests, die Spannen absolut zählen** — ab `today` zählen.
- **Das Fenster-Ende ist HALB OFFEN (`first <= now < last`)** — in `DimWindowResolver` und in
  `DndScheduleUseCase.applyCurrentState()` (dort als benannte `isActiveAt()`). Ein Tick exakt auf
  `range.last` galt sonst noch als „im Fenster", während der nächste Wechsel strikt auf „> now"
  geplant wird: der Zustand „aus" wurde für diesen Rand nie berechnet, Dimmen/DND blieben bis zum
  nächsten Fensterstart hängen. Ein Rückbau auf „now in range" holt das zurück.
- **Die Tick-Kette darf nicht abreißen.** Lag keine Fenstergrenze mehr in der Zukunft, cancelte
  `scheduleNextTransition()` den Alarm — danach konnte sich die Kette nicht selbst wiederbeleben, und
  die übrigen `syncAlarms()`-Aufrufer armieren Dimmer/DND nicht nach: nach einer Urlaubswoche ohne
  Schichten blieb „Während der Dienstzeit" bis zum nächsten Reboot wirkungslos. Deshalb ein
  Keep-alive-Tick (6 h), solange der Dimmer überhaupt AN ist, plus ein kurzer Retry-Tick (15 min)
  nach einem **Lesefehler der Fenster-Grundlage** (Schichtspannen für die Regeln und für den
  gesamten DND-Pfad, Alarm-Bestand für die Weckzeit-Zeitleiste). Die BEDEUTUNG einer leeren Fensterliste
  (Nachtdienst-Unterdrückung) bleibt unverändert — es wird nur später noch einmal nachgesehen.
- **Aufraeumen nach dem Ein-Modell-Umbau (v1.34.2): was ohne Bedienelement uebrig blieb, ist weg.**
  Der Umbau hat den Dimmer-Reiter von drei Karten auf einen Schalter reduziert — die Oberflaeche
  liest seither aus `DimmerViewModel` nur noch `uiState.dimEnabled` und ruft `setDimEnabled()`.
  Alles Uebrige war damit ohne Aufrufer und ist entfernt: die globalen Verdunkelungs-/Waerme-Setter
  (im ViewModel UND in `DimOverlayPrefs`), `shiftNames` (dessen Konsumenten haengen samt und
  sonders an `DimmerRulesViewModel`/`DndViewModel`, nicht hier), die komplette
  `previewDim()`-Maschinerie samt eigenem Scope, zwei `uiState`-Felder und neun tote Importe. Die
  Datei schrumpfte von 207 auf 89 Zeilen.
  **Referenzbasiert geprueft, nicht annotationsbasiert** — und das war noetig: die Kommentare in
  `DndViewModel` und `DimmerViewModel` verwiesen weiter auf `setStrength`/`setWarmth`, als gaebe es
  sie noch.
  **Die SCHLUESSEL `dim_strength`/`dim_warmth` bleiben** und werden weiter gelesen
  (`strengthNow()`/`warmthNow()`): sie sind der Fallback des Renderzustands und der Wert, mit dem
  `setActiveOverlay(false, …)` abschaltet. Entfernt wurden nur die SETTER — ein Setter ohne
  Bedienelement ist die Altlast, der Getter mit Konsument nicht.
  **Kein Test wurde weggeworfen, nur weil sein Traeger entfiel:** Die beiden Regressionen zum
  Vorschau-Aufraeumen (App waehrend der Vorschau verlassen; zweiter Tipp loest den ersten in der
  richtigen Reihenfolge ab) sind auf `DimmerRulesViewModel.previewRule()` umgezogen — den Zwilling
  mit derselben Konstruktion und derselben Falle. Ersatzlos gestrichen wurde nur, was gar keinen
  Gegenstand mehr hat (`DimmerViewModelRenderSettersTest`, die `previewDim`-Haelfte des
  Stempel-Tests).
- **Der Ende-Anker `ALARM_SONST_CLOCK` (seit v1.33.0) bringt die Semantik „min(Weckzeit, Uhrzeit)"
  ins Modell — sie fehlte, und ihr Fehlen war ein gemeldeter Fehler.** Am 23.08.2026 wachte der
  Eigentuemer um 08:48 auf und fand den Bildschirm gedimmt. Kein Defekt: der Nacht-Standard endet
  an Schicht-Tagen am ALARM-Anker — *egal wie spaet der ist*. Vor einem Spaetdienst mit Weckzeit
  12:30 hiess „Nacht-Dimmung" damit faktisch „bis mittags", waehrend die eingestellten 07:00 nur an
  weckerfreien Tagen galten. Ausdruecken liess sich die erwartete Semantik mit keinem der drei
  Anker: `CLOCK` endet stur an der Uhrzeit und ueberdimmt jeden frueheren Wecker, `ALARM` endet
  stur am Wecker. **Der neue Anker ist das Minimum aus beidem** und war damit die Voraussetzung dafuer,
  den eingebauten Nacht-Standard als gewoehnliche Regel auszudruecken statt als eigene Quelle —
  **mit v1.34.0 umgesetzt, siehe den Eintrag „Ein Modell statt drei Quellen" oben.**
  **Er raeumte zugleich eine Sonderlogik ab:** der Nacht-Standard brauchte pro Tag ZWEI Fenster
  (rueckwaerts/vorwaerts) plus `nextDayCoversTonight` — eine Bedingung, die auf ein ANDERES Datum
  schaute als das gerade berechnete und deren erster Wurf real eine ganze Nacht durchfallen liess
  (03./04.08.2026). Weil der Anker seine Weckzeit in der GESAMTEN Zeitleiste sucht statt „im Wecker
  dieses Tages", entfaellt das Fensterpaar: jede Kalendernacht bekommt genau ein Fenster und findet
  ihr Ende selbst. `DimAnkerWeckzeitSonstUhrzeitTest` haelt den ausloesenden Fall (12:30 beendet die
  Nacht NICHT), den Gegenfall (05:30 beendet sie), die Auswahl der fruehesten Weckzeit, beide
  Raender (Weckzeit exakt auf Start bzw. exakt auf der Schranke) und beide DST-Tage fest.
  **Drei Entscheidungen, die nicht offensichtlich sind:** (1) Die Weckzeit muss ECHT nach dem Start
  liegen — sonst schrumpfte ein Wecker exakt auf 22:00 das Fenster auf Laenge null, also eine
  stille Dimm-Luecke statt eines erkennbaren Fehlers. (2) Leere Zeitleiste degradiert auf die
  Uhrzeit, nicht auf „kein Ende": die Richtung ist „im Zweifel hell", wie ueberall hier.
  (3) Am START verhaelt er sich wie `CLOCK` statt `null` zu liefern — die Oberflaeche bietet ihn
  dort nicht an, aber ein aus Daten eingeschleuster Wert soll das Fenster nicht verschwinden lassen.
  **Die Zeitleiste ist bewusst eine zweite Sicht neben den Slots** (`DimScheduleUseCase`): Slots
  entscheiden ueber die Regel-Auswahl und brauchen den Schichtnamen, die Zeitleiste nur „wann
  klingelt etwas". Nur deshalb duerfen MANUELLE Wecker hinein — sie haben keine Schichtspanne, und
  in die Slots gelegt wuerden sie aus einem freien Tag still einen Schicht-Tag machen und die
  FREI-Regel aushebeln. Sie laufen zudem bewusst NICHT durch den Freie-Tage-Filter: eine
  Tagesfreigabe streicht den DIENST, nicht einen selbst gestellten Wecker.
  **Downgrade-Verhalten am Geraet nachgemessen (24.08.2026):** Mit einer gespeicherten
  `ALARM_SONST_CLOCK`-Regel auf einer aelteren APK liest die Anzeige tolerant (Anker faellt auf den
  Feld-Default), und `editRules()` verweigert JEDE Aenderung — auch das Loeschen einer anderen
  Regel. Das ist die dokumentierte Absicht des `strictJson`-Schreibpfads und kein Defekt; wer beim
  Testen zwischen Versionen springt, muss es kennen, sonst sieht es wie ein kaputtes Loeschen aus.
- **Das Verschwinden des Overlays muss eine Spur hinterlassen (seit v1.34.1) — und der Vorfall,
  der das erzwang, war KEIN App-Fehler.** Am 24.08.2026 meldete der Eigentuemer, der Bildschirm sei
  waehrend einer per adb ferngesteuerten Sitzung „mail heller und mal dunkler" geworden. Ursache
  gemessen: `uiautomator` verbindet sich als `UiAutomation` und **unterdrueckt dabei alle anderen
  Bedienungshilfen-Dienste**, also auch `DimAccessibilityService`. Beleg: die SurfaceFlinger-Layer-ID
  des `CFAlarmDimLayer` wechselte bei JEDEM Automations-Aufruf (64604 → 64609 → 64614), im Leerlauf
  ueber Sekunden nie. Einzelheiten im Memory `env_android_emulator_mcp`.
  **Der eigentliche Befund war die Unauswertbarkeit:** `DimAccessibilityService` hatte KEINE einzige
  Log-Zeile beim Verbinden, Trennen oder Abraeumen, und der haeufigste Aus-Weg in
  `applyCurrentState()` („kein aktives Fenster") kehrte kommentarlos zurueck — man sah hinterher
  nur, wann gedimmt WURDE, nie wann und warum es aufhoerte. Seither: `onServiceConnected`,
  `onUnbind`, `onDestroy` und `removeAllOverlays()` loggen auf **WARN** (Release-Logs fuehren nur
  WARN+), jeweils mit einem einzeiligen, PII-freien Schnappschuss (`DimDiagnostik.overlaySnapshot`,
  Bauart wie `visibilitySnapshot()` am Weckbildschirm, inkl. `Locale.ROOT` — sonst haengt das
  Zahlenformat an der Geraetesprache). Am Emulator verifiziert: ein einziger `get_all_text`-Aufruf
  erzeugt jetzt `entbunden` → `zerstoert` → `verbunden`, 1,1 s auseinander.
  **Zwei echte Fehler fielen dabei mit ab:** (1) `running` wurde ausschliesslich in `onDestroy`
  zurueckgesetzt — ein entbundener, nicht zerstoerter Dienst meldete weiter `isRunning() == true`,
  und beide Konsumenten glaubten das: die Diagnosezeile schrieb `accessibilityServiceBound=true`,
  obwohl nichts zeichnete, und die Status-Karte zeigte dem Nutzer einen gruenen Dienst. Jetzt setzt
  `onUnbind` zurueck. (2) `onServiceConnected` startete jedes Mal einen NEUEN
  `renderState`-Collector, ohne den alten abzubrechen — bei einem Rebind ohne `onDestroy` haetten
  zwei Collector `render()` doppelt gerufen, jeder mit eigener Alpha-Rampe. Jetzt
  cancel-and-replace.
- **`enable()` rechnet die Fenster EINMAL, nicht zweimal (seit v1.34.1).** `applyCurrentState()`
  und `scheduleNextTransition()` berechneten frueher unabhaengig voneinander dieselbe Zeitleiste,
  Millisekunden auseinander. Am Emulator gemessen: zwei Laeufe je Kette, warm ~2 ms
  (`computeWindows #1 … #2`), nach dem Fix genau einer. **Das Leistungsargument allein traegt das
  nicht** — der eigentliche Grund ist Konsistenz: faellt eine Fenstergrenze zwischen die beiden
  Berechnungen, sieht `applyCurrentState()` das Fenster noch als aktiv und schaltet ein, waehrend
  `scheduleNextTransition()` dieselbe Grenze schon verwirft und erst die NAECHSTE plant — das
  Overlay bliebe bis dahin an. Der Schnappschuss wird **nur innerhalb von `enable()`**
  durchgereicht; beide Funktionen bleiben einzeln aufrufbar und rechnen dann selbst („beide immer
  zusammen" gilt nur fuer `enable()`). `BootReceiver`, `AlarmMaintenanceService` und
  `TagFreigabeUseCase` riefen die beiden Funktionen ausgeschrieben statt `enable()` — deshalb kam
  die Halbierung dort zunaechst NICHT an; sie rufen jetzt `enable()`.
  **NICHT gebaut wurde ein Entprellen ueber Aufrufer-Grenzen.** Dagegen stehen die
  „unentprellt"-Regel (eine fruehere 300-ms-Entprellung hing am `viewModelScope` und starb beim
  Verlassen der App), der Retry-Pfad (ein verworfener Aufruf verwirft auch den 15-min-Retry) und
  der Migrations-`enable()`, der keine zweite Chance hat. Waere es je noetig: daempfen, was
  gerechnet wird — nie, was angewendet wird (Vorbild `HueBridgeRediscoveryTest`).
  **Ein Unit-Test dafuer gibt es bewusst nicht:** `enable()` braucht `AlarmManager` und
  `PendingIntent`, die im reinen JVM-Test nicht existieren — kein Test im Projekt ruft es. Der
  Beleg ist die Geraetemessung oben, und der Messzaehler in `computeWindows()` (Debug-only) macht
  sie jederzeit wiederholbar.
- **Dimmer-Korrektur-Override (Feature C, seit v1.20.0) lebt im DataStore, nicht in-memory** —
  `DimAccessibilityService`/`DimScheduleReceiver` haben keine garantierte Lebensdauer, ein
  In-Memory-State ginge bei Prozess-Neustart verloren. `DimOverlayPrefs.Override` speichert
  `strengthDelta`/`paused` PLUS `windowEnd` UND `windowStrength` (nicht nur `windowEnd`!) als
  Fenster-Identität. **Reine `windowEnd`-Identität reicht nicht:** `DimScheduleUseCase.windows()`
  liefert mehrere überlappende Fenster (bis v1.33.0 aus drei Quellen, seit dem
  Ein-Modell aus Regeln mit mehreren Fenstern), die sehr häufig
  denselben Anker teilen (typischerweise ALARM-Offset 0 = die Weckzeit) — wechselt „darkest wins"
  (`activeSpan`) wegen einer neu überlappenden, stärkeren Quelle die aktive Spanne, bleibt
  `range.last` dabei oft identisch, nur die Stärke ändert sich. Ohne den Stärke-Vergleich in
  `DimWindowResolver.isOverrideStale()` bliebe ein Override fälschlich für die falsche Quelle aktiv
  (adversarial beim ersten Bau gefunden, jetzt in `DimWindowResolverTest` festgehalten). **Kein
  neuer Timer/Alarm** für den Reset — der ohnehin rollende Tick (`REQ_TICK`/`DimScheduleReceiver`)
  macht den Override beim nächsten `applyCurrentState()`-Aufruf automatisch stale.
- **`DimNotificationService` klemmt den `strengthDelta` selbst, nicht nur den abgeleiteten
  `effectiveStrength`.** Sonst wächst der gespeicherte Delta bei wiederholtem Dunkler ungebremst
  über den sichtbaren Bereich hinaus, und ein einzelnes Heller danach zeigt keine Wirkung, weil
  `effectiveStrength` trotzdem noch gedeckelt bleibt (adversarial gefunden/gefixt: Klemmbereich ist
  `-active.strength..(STRENGTH_MAX - active.strength)`, nicht `[0, STRENGTH_MAX]` auf den Delta
  selbst). **Am Gerät nachgemessen (14.08.2026):** Basis 55 %, sechsmal „Dunkler" wäre +60 — der
  Delta klemmt bei **30**, Render bei 85 %, und ein einziges „Heller" wirkt sofort (85 → 75). Ohne
  die Klemme stünde der Delta bei 60 und dieser Tipp bliebe sichtbar wirkungslos. Das Read-Modify-Write auf die Override-Prefs läuft außerdem hinter einem `Mutex`
  (`DimOverlayPrefs.withOverrideLock`, ein Singleton-Feld — **nicht** ein lokales Feld in
  `DimNotificationService`) — ohne ihn verlieren zwei rasch aufeinanderfolgende Button-Taps
  (Doppel-Tap) eine der beiden Änderungen beim Zurückschreiben. Ein rein lokaler Mutex in
  `DimNotificationService` allein reicht nicht: `DimScheduleUseCase.applyCurrentState()` liest/
  räumt denselben Override-Zustand unsynchronisiert von mindestens vier weiteren, unabhängigen
  Aufrufern (`DimScheduleReceiver`-Tick, 6h-Wartung, `BootReceiver`, jeder `DimmerViewModel`-Setter)
  — real als Race gefunden: ein Korrektur-Tap genau zur Tick-Fenstergrenze konnte stillschweigend
  wirkungslos bleiben. Deshalb lebt der Mutex im `@Singleton DimOverlayPrefs` selbst, der einzige
  Ort, den wirklich alle Aufrufer teilen — `DimNotificationService` UND `DimScheduleUseCase.
  applyCurrentState()` gehen beide über `withOverrideLock`.
- **Jeder Setter, der einen `DimOverlayPrefs`-Wert schreibt, MUSS direkt danach
  `DimScheduleUseCase.enable()` aufrufen** — nicht nur die `DimmerViewModel`-Setter, auch
  `NotificationSettingsViewModel.setDimCorrectionNotificationEnabled()` (Fix v1.22.1). Der Toggle
  für die Korrektur-Notification schrieb bis dahin nur den Preference-Wert; `DimCorrectionNotifier.
  show()/cancel()` wird aber ausschließlich aus `applyCurrentState()` entschieden, und das läuft nur
  beim rollenden Tick (Fenstergrenzen) oder eben einem `enable()`-Aufruf neu. Ein **mitten** im
  laufenden Fenster umgelegter Toggle blieb dadurch bis zum nächsten Tick wirkungslos — und dieser
  nächste Tick ist typischerweise das Fenster-ENDE, das die Notification sofort wieder wegräumt. Der
  Nutzer sah sie für das gerade laufende Fenster praktisch nie. Real gemeldet (05.08.2026), Fix **am
  Gerät belegt (14.08.2026)**: Toggle mitten im laufenden Fenster umgelegt → `id=2101,
  channel=dim_correction` binnen Sekunden gepostet. Wer einen
  neuen Dimmer-Prefs-Setter ergänzt, ohne `enable()` hinterherzurufen, baut dieselbe Falle neu.
  **Und zwar unentprellt.** Die vier Darstellungs-Regler hatten kurzzeitig eine 300-ms-Entprellung
  („die Regler feuern pro Frame“). Seit dem Ein-Modell-Umbau (v1.34.0) gibt es diese Regler in dieser
  Form nicht mehr: Stärke und Wärme stehen in `DimmerRuleConfigScreen` und schreiben in **lokalen
  Compose-State** — persistiert wird erst beim Speichern der Regel. Pro Reglerbewegung fällt also
  überhaupt kein `DimOverlayPrefs`-Setter mehr an: Nutzen der Entprellung null, Risiko real.
  Der Job hing am `viewModelScope` und starb beim Verlassen der App vor seinem `delay()`; der
  Prefs-Wert war geschrieben, das laufende Overlay behielt aber bis zur nächsten Fenstergrenze
  (typischerweise das Fenster-ENDE am Morgen) die alte Verdunkelung — exakt die Lücke, gegen die diese
  Invariante existiert. Hintergrund, warum `enable()` überhaupt nötig ist: der Dienst beobachtet nur
  `DimOverlayPrefs.renderState`, und das liest die globalen Regler nur als FALLBACK; die Render-Keys
  schreibt einzig `setActiveOverlay()`.
- **`DimAccessibilityService.isRunning()` (der einzige echte Bound-Status) wird seit v1.22.1 in
  `DimScheduleUseCase.applyCurrentState()` mitgeloggt**, zusammen mit Fenster-Zustand/Stärke/Pause.
  Vorher loggte der Tick nur generisch "Naechster Dimm-Wechsel geplant" — ob ein aktives Fenster auf
  einen tatsächlich NICHT gebundenen Dienst traf (z. B. ECM-Restricted-Settings nach Sideload, siehe
  Memory `project_a11y_restricted_settings_ecm.md`), war rückwirkend aus dem Log nicht rekonstruierbar.
  Wer diese Log-Zeile entfernt, verliert die einzige Möglichkeit, einen solchen Vorfall im Nachhinein
  zu diagnostizieren.
- **Diese Log-Zeile allein genügte NICHT — der Vorfall vom 29.08.2026 hat es bewiesen.**
  Der Eigentümer meldete: um 22:00 schaltete „Nicht stören" wie geplant, die Korrektur-Benachrichtigung
  meldete „Verdunkelung: 67 %" — und der Bildschirm blieb hell. Am Fairphone gemessen war die App
  fehlerfrei: Tick gefeuert (`22:00:00.038 Start proc … DimScheduleReceiver`), Fenster aktiv, ZenRule
  `STATE_TRUE` seit `2026-08-29T20:00:01Z`. Nur stand `DimAccessibilityService` nach einer
  Neuinstallation (`lastUpdateTime` 14:54, `installerPackageName=null`) nicht mehr in
  `Enabled services`, und in SurfaceFlinger existierte kein `CFAlarmDimLayer`.

  **Zwei Lehren, und die zweite ist die wichtigere.** Erstens: die Zeile war `Logger.d` — im
  Release-Log (nur WARN+) also gar nicht vorhanden. Ein Diagnose-Signal, das genau im Ernstfall fehlt,
  ist keines; deshalb geht der Fall jetzt als WARN durch (entprellt über einen Merker, weil
  `applyCurrentState()` auch bei jedem Setter läuft, nicht nur beim Tick). Zweitens, und das ist der
  eigentliche Fehler: **die einzige nachts sichtbare Fläche behauptete das Gegenteil.** Sie nannte
  einen Verdunkelungswert für ein Overlay, das technisch garantiert nicht existieren konnte, und
  schickte die Fehlersuche damit aktiv in die Fensterlogik statt in die Gerätekonfiguration. Ein Log
  ist Diagnostik für hinterher — hier fehlte die Wahrheit im Moment selbst.

  Die Entscheidung liegt als reine Funktion in `DimDiagnostik.dimmenWirkungslos()`, bewusst nur bei
  aktivem, **nicht pausiertem** Fenster: ohne Fenster soll ohnehin nicht gedimmt werden, dort wäre
  ein fehlender Dienst eine Dauerwarnung bei jedem, der den Dimmer nie einschaltet. Der Notifier
  zeigt dann „Dimmt nicht — Bedienungshilfen-Dienst ist aus", **ohne** die drei Korrektur-Knöpfe
  (sie würden ein Overlay verstellen, das es nicht gibt) und mit Tipp-Ziel in die App — nicht direkt
  in die Bedienungshilfen-Einstellungen, das ginge an der Play-Pflicht-Offenlegung vorbei, die die
  Status-Karte vor dem Aktivieren zeigt. Verworfen: eine zweite, eigene Notification daneben — zwei
  Dimmer-Meldungen nebeneinander wären genau die Doppelaussage, die hier korrigiert wird. Am Emulator
  beidseitig belegt (Fenster 22:00–07:00, Zeit 23:30): Dienst nicht gebunden ⇒ `actions=0` und genau
  ein WARN; Dienst gebunden ⇒ „Verdunkelung: 55 %, Wärme: 40 %", `actions=3`, kein WARN.
- **Die Meldung sagte, WAS zu tun ist — nicht, WO (04.09.2026).** Der Eigentümer fragte, ob die
  Benachrichtigung „nicht direkt zum Bedienungshilfen-aktivieren-Ort verlinken kann, oder
  wenigstens auf die Karte in der App". Der Hinweis endete mit „Zum Aktivieren tippen", und der
  Tipp öffnete die App — auf dem zuletzt benutzten Tab. Die Karte, die den Dienst aktivieren
  lässt, steht im Status-Tab unterhalb von sechs anderen; am Ende derselben Kette öffnete ihr
  Knopf die Liste **aller** Bedienungshilfen, in der der Eintrag der App zwischen Systemdiensten
  steht. Beide Enden waren also formal richtig und praktisch eine Schnitzeljagd — dieselbe Klasse
  Fehler wie oben, nur eine Ebene weiter: die Meldung stimmte, ihr Ausweg war unbenutzbar.

  **Direkt in die Android-Einstellungen zu springen bleibt verboten** — davor gehört die
  Play-Pflicht-Offenlegung, und die zeigt nur die Karte. Deshalb hat der Weg bewusst zwei
  Stationen: Das Extra `MainActivity.EXTRA_EINSTIEG` sagt der Activity, WESHALB geöffnet wurde;
  sie stellt den Wunsch (`DimBedienungshilfenWunsch`) und wechselt auf den Status-Tab, der rollt
  die Karte ins Bild und lässt sie die Offenlegung zeigen. **Erst deren Knopf** springt — seit
  jetzt über `ACTION_ACCESSIBILITY_DETAILS_SETTINGS` (ab Android 11) direkt auf die Seite DIESES
  Dienstes, mit Rückfall auf die Liste, weil das Detail-Ziel nicht auf jedem Gerät existiert.

  Vier Fallen stecken in den Details, alle nicht offensichtlich: `FLAG_ACTIVITY_CLEAR_TOP` **ohne**
  `FLAG_ACTIVITY_SINGLE_TOP` legt die laufende MainActivity (Start-Modus `standard`) neu an statt
  ihr `onNewIntent` zu geben — der Nutzer verlöre seinen Stand; `onNewIntent` ohne `setIntent()`
  lässt `getIntent()` weiter den Start-Intent liefern (dieselbe Falle wie am Weckbildschirm); die
  Auswertung in `onCreate` gehört hinter `savedInstanceState == null`, sonst schickt **jede
  Drehung** den Nutzer erneut auf den Status-Tab; und das Signal an die Karte ist ein ZÄHLER, kein
  Boolean — ein Rückkanal „verbraucht" wäre als `LaunchedEffect`-Schlüssel gerade der Wert, der
  den noch laufenden Bildlauf mittendrin abbräche. Das Bildlaufziel wird aus
  `positionInRoot()` von Karte und Spalte plus dem aktuellen Stand gerechnet: der reine Abstand im
  Fenster verschiebt sich beim Rollen mit.

  **Am Gerät noch nicht belegt** (die Sitzung, die es gebaut hat, hatte weder Emulator noch SDK).
  Zu prüfen ist genau zweierlei: dass die Detailseite auf dem Fairphone wirklich mit dem Eintrag
  der App öffnet (sonst greift der Rückfall, und der ist die Liste von vorher), und dass der
  Bildlauf die Karte trifft, wenn die App aus der Benachrichtigung KALT startet — dort läuft der
  Effekt vor dem ersten Layout-Durchgang und wartet auf die Vermessung.
- **„Kurz hell, dann von allein wieder dunkel" — warum das Verschwinden NIE im App-Log stehen kann
  (29.08.2026).** Unmittelbar nach dem obigen Fix meldete der Eigentümer beim Einspielen der neuen
  Version genau das. Im Systemlog stand der Grund lückenlos: `23:06:07.619 Killing 18584 … due to
  installPackageLI`, `23:06:08.245` neuer Prozess, `23:06:14.713` wieder `bound=true` — rund sieben
  Sekunden hell. Im App-Log stand **nichts**, und daran lässt sich auch nichts ändern: bei einem
  `SIGKILL` läuft kein `onUnbind` und kein `onDestroy` mehr. Wer hier „dann loggen wir das eben"
  denkt, baut etwas, das im Ernstfall garantiert nicht ausgeführt wird.

  **Die Lösung verschiebt den Zeitpunkt: nicht das Ende protokollieren, sondern die Rückkehr
  klassifizieren.** `onServiceConnected()` liest einen Merker, bevor es ihn neu setzt; steht er
  noch auf „läuft", endete der vorige Lauf ohne Abmeldung. Der Merker liegt bewusst in
  SharedPreferences mit `commit()` und nicht im DataStore: geschrieben wird er in `onUnbind` und
  `onDestroy`, also in Rückgabepfaden, die sofort fertig sein müssen — ein suspendierender Schreib‑
  vorgang hätte dort keinen Scope mehr, den das System noch am Leben lässt (gleiche Überlegung wie
  beim Snooze-Merker).

  **Die eigentliche Konstruktionsfrage war der Geräteneustart.** Er tötet den Dienst genauso ohne
  `onDestroy` und liesse den Merker ebenso stehen — ohne Unterscheidung stünde nach JEDEM Boot ein
  WARN im Release-Log und hätte die eine Zeile entwertet, auf die es ankommt. Erkannt wird er an
  `SystemClock.elapsedRealtime()`, das beim Booten auf null zurückfällt: gespeicherte Laufzeit
  größer als die aktuelle ⇒ Neustart. **Die Wanduhr taugt dafür nicht** — sie kann sich auch ohne
  Neustart verstellen (Zeitzone, NTP), und genau daran scheiterte die erste Überlegung.

  Alle vier Zweige am Emulator belegt: Erstinstallation ⇒ `ERSTMALIG` (DEBUG), zweites Update
  ⇒ `Killing … killDueToPackageUpdate` und beim Wiederverbinden das WARN, Dienst von Hand aus/ein
  ⇒ `SAUBER` (DEBUG, `onUnbind`+`onDestroy` liefen davor durch), `adb reboot` ⇒ `NACH_NEUSTART`
  (DEBUG, kein falsches WARN).
- **`DimCorrectionNotifier.show()` prüft `NotificationManagerCompat.areNotificationsEnabled()`
  vor `notify()`** (Fix v1.22.1) — ohne POST_NOTIFICATIONS-Berechtigung verschluckt `notify()` sonst
  lautlos, ohne Log oder Exception, und sieht im Logcat identisch aus wie der obige Toggle-Bug.
