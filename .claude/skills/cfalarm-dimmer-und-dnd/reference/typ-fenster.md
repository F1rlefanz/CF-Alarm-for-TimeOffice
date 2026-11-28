# Dimmen und „Nicht stören" am Schichttyp (Umbau Etappe 3, 1.59.0) — Hergang

> Hergang zu den Kurzregeln „Umbau Etappe 3" der `SKILL.md` daneben: welcher Befund die Regel
> erzwungen hat, welcher Test sie hält, welche Alternative verworfen wurde. Seit Etappe 3 liegen
> Dimm-Fenster und DND-Schalter AM TYP und an der festen Zeile „Ohne Kalendereintrag"; Regelliste,
> Regel-Editor, Dimmer-Bereich und die globalen Schalter gibt es nicht mehr. Was `dimmer.md` und
> `dnd.md` über Regeln, `dim_enabled` und `DndPrefs.Toggles` erzählen, ist der Weg dorthin.
> Die Speicherform am Typ und die Migration aus Sicht der Persistenz: Skill
> `cfalarm-persistenz-und-auth`, `reference/persistenz.md`. Die Bildschirme: Skill
> `cfalarm-ui-und-navigation`, `reference/typ-seite.md`.

## Inhalt

- Die eine Fenster-Quelle: die Fenster am Typ (#217)
- Die Typ-Modellmigration (#216, #234)
- „Nicht stören" liest die Schalter am Typ (#218)
- Die Tagesleiste rechnet über dieselben Klassen (#219)
- Die Statuszeile und die Kurzform der Typ-Liste (#219, #220)
- „Nacht davor": ein Feld, kein neuer Anker, und zwei Kodierungen an EINER Stelle (#227, #215)
- Die feste Zeile „Ohne Kalendereintrag" (#223, #238)
- „Weitere Optionen": Anker-Editor und Tageslicht-Beispiel (#224)
- Die Geräte-Einstellungen: Vorschau, Farbwärme, „Wer trotzdem durchkommt" (#225)
- Was mit Etappe 3 entfallen ist (#217, #219, #224, #226)

## Die eine Fenster-Quelle: die Fenster am Typ (#217)

- **`DimScheduleUseCase` liest weder `dim_enabled` noch die Dimm-Regeln.** Quelle ist `TypDimmQuelle`:
  ein Adapter, der je Typ eine gewöhnliche `DimRule` baut und an `buildRuleSpans`/`regelFuerTag`
  reicht — bewusst kein neuer Resolver. Die Semantik der alten Regeln bleibt damit erhalten:
  - ein Typ ohne wirksames Fenster (`an = false` oder keine Fenster) = LEERE Regel = Unterdrückung
    des Tages, auch neben einem zweiten Eintrag desselben Tages;
  - `null` (= keine Regel, Tag fällt durch) nur für einen Namen, den kein Typ trägt;
  - freie und freigegebene Tage bekommen die feste Zeile (`fuerFreienTag()`), „wie <Typ>" aufgelöst
    wie auf der Typ-Seite (`OhneEintragWahl.gewaehlterTyp`).
  Die Gleichwertigkeit mit der migrierten Regel prüft `TypDimmQuelleTest`.
- **Die Konfiguration wird in `computeWindows()` ZUERST und STRIKT gelesen**
  (`IShiftConfigRepository.getCurrentShiftConfig()`), vor jedem anderen CE-Speicher. Ein Fehler ist
  `alarmReadFailed` (15-min-Retry), nie „kein Typ hat Fenster": über „kein Fenster" hätte sich die
  Tick-Kette an einem vorübergehenden Lesefehler (etwa vor der ersten Entsperrung) abbestellt.
  Tests: `DimSchedulePreviewStatusTest`, `DimScheduleUseCaseTest`.
- **Die Stärke je Fenster reist als `DimRule.fensterStaerken` (`@Transient`)** und wird über
  `staerkeFuerFenster(i)` gelesen. Am Typ hat jedes Fenster sein eigenes „Mit %";
  `DimRule.strength` gilt nur noch für gespeicherte (Alt-)Regeln. Das Feld gehört nie in den
  Regel-Speicher oder einen Export.
- **Speichern eines Typs armiert Dimmer und DND** über den bestehenden `NacharmierBedarf`
  (`mitTypEinstellungen`, Anlass `SCHICHTTYP`). Ob es nötig ist, entscheidet
  `TypDimmQuelle.dimmenOderNichtStoerenGeaendert` (Vergleich je Typ-id: `dimmen`, `nichtStoeren`,
  feste Zeile, Verweis „wie <Typ>"). Eine reine Umbenennung zählt bewusst nicht — sie hat ihren
  eigenen Nachzug, und `Pruefrunde8SchichtUmbenennungNachzugTest` hält die Flags dort fest.
- **Diagnose:** Der frühere Abschaltgrund `KEIN_FENSTER_TROTZ_REGELN` heißt seit #217
  `DimDiagnostik.AbschaltGrund.KEIN_FENSTER_TROTZ_TYP_FENSTERN` („Fenster am Typ da, trotzdem kein
  Fenster") und ist weiter der einzige WARN-Abschaltgrund; `DIMMER_AUS` heißt jetzt „kein Typ und
  nicht die feste Zeile hat ein wirksames Fenster".
- **JVM-Test der Planung:** `scheduleNextTransition()` und `enable()` sind über `mitDimmTick` in
  `DimScheduleUseCaseTest.kt` prüfbar (Skill `cfalarm-bauen-und-testen`).

## Die Typ-Modellmigration (#216, #234)

`TypModellMigration` überträgt Regeln, Dimm-Schalter und DND-Schalter einmalig an die Typen und die
feste Zeile. Sie läuft aus denselben drei Anlässen wie `DimmerModellMigration`: `MainActivity`,
die Wartung (`AlarmMaintenanceService`) und der Konfigurations-Import. Prüfreihenfolge
(`fuehreAus()`):

1. entsperrt? (sonst still verschoben — CE-Speicher läse leer)
2. `ShiftConfig.modellStufe` schon gesetzt? → erledigt, und zwar **nie mit Fehler**, auch wenn der
   Altbestand inzwischen unlesbar wäre
3. `isPersistenceBlocked()`
4. `dim_modell_migration` gesetzt? — der Dimmer-Marker ist zugleich Reihenfolge-Tor und
   Kanarienvogel für einen still leeren `settings`-Store
5. Rufbereitschaft-Altliste weg (`RufbereitschaftMigration` gelaufen)?
6. Altbestand STRIKT lesen (`regelnOderFehler()`, `dimmenAnOderFehler()`, `altbestandOderFehler()`)
7. Konfiguration ERNEUT lesen (gleichzeitige Nutzeränderung), Standard-Konfiguration → verschoben
8. ein Schreibvorgang über `IShiftUseCase`, danach `ZeitkettenArmierer.armiere()`

Ein Fehlschlag steht nur im Speicher (`TypModellMigration.stand`, Karte im System-Status) — ein
neuer Preference-Schlüssel bräuchte Backup-Filter und Schlüssel-Inventur. **#234:** übersetzt wird
nur, was noch dem Standard entspricht, und zwar je Einstellungs-Objekt, nicht je Feld (Hergang im
Persistenz-Skill).

## „Nicht stören" liest die Schalter am Typ (#218)

- **Einen globalen Schalter gibt es nicht mehr.** Die beiden Fenster-Trigger und der Klipp-Schritt
  gelten weiter, nur JE TYP:
  - „Solange gedimmt wird" =
    `previewTimelineWithStatus(mitWeckzeitNachlauf = false, nurTage = DndTypSchalter::solangeGedimmt)`,
  - „Während des Kalendereintrags" = `buildShiftSpans(slots, DndTypSchalter::waehrendEintrag)`,
  - der Ruf-Cutoff = `TypNichtStoeren.endetSpaetestensMinuten` des jeweiligen Rufbereitschafts-Typs
    (`cutoffInstants(slots, cutoffMinutenFuer, zone)`).
  `dnd_follow_dimmer_enabled`, `dnd_during_shift_enabled`, `dnd_shift_excluded_shifts` und
  `dnd_oncall_cutoff_min` liest `DndScheduleUseCase` nicht mehr; sie bleiben liegen (SPEC 4, eine
  Version Rückweg). `anySourceEnabled` heißt: irgendein Typ oder die feste Zeile hat einen der beiden
  Schalter an; der Cutoff allein löst nichts aus. Test: `DndTypSchalterTest`, je Fall mit den alten
  globalen Schaltern auf AN (sonst bewiese der Test nicht, dass sie nicht mehr gelesen werden).
- **Der Tagesfilter sitzt VOR dem Vereinen und braucht die Herkunft der Spanne:**
  `DimWindowResolver.buildRuleSpansMitHerkunft` liefert `HerkunftSpan(span, herkunft)` mit
  `SpanHerkunft.Schicht(name)` bzw. `SpanHerkunft.OhneSchicht` (feste Zeile); `buildRuleSpans`
  bleibt `.map { it.span }`. Die Herkunft ist bewusst ein Umschlag und kein Feld von `DimSpan` —
  deren `equals` tragen Vorschau, Override-Identität und `TypDimmQuelleTest`. Nach `mergeToTimeline`
  ist die Herkunft weg: ein Filter danach könnte zwei Tage, deren Fenster sich berühren, nicht mehr
  trennen.
- **DND bildet einen Schichtnamen genau so auf seinen Typ ab wie der Dimmer**
  (`TypDimmQuelle.fuerSchicht`: erster Typ, Groß/Klein egal) und die feste Zeile so wie die
  Typ-Seite (`OhneEintragWahl.gewaehlterTyp`: „wie <Typ>" → dessen Schalter, sonst der eigene
  `nichtStoerenSolangeGedimmt`). Sonst folgte „Solange gedimmt wird" an einem Tag einem anderen Typ
  als dem, dessen Fenster der Dimmer dort rechnet (Test „Die feste Zeile folgt ihrem eigenen
  Schalter, bei wie Typ dem Schalter des Typs").
- **DND liest die Schicht-Konfiguration ZUERST und STRIKT.** Ein Lesefehler ist `alarmReadFailed`
  (Ruhezeit aus, 15-min-Retry), nie „kein Schalter an" — und vorher wird weder der Dimmer noch der
  Spannen-Speicher gefragt. Die Zustandszeile hat dafür den Aus-Grund `LESEFEHLER` („Grundlage nicht
  lesbar, neuer Versuch in 15 min"), geprüft VOR `KEINE_QUELLE`, weil bei unlesbarer Konfiguration
  niemand weiß, ob ein Schalter an ist. Früher fiel ein Konfigurationsfehler nur auf „kein Cutoff"
  zurück; seit die Schalter dort liegen, ginge über „aus" die Kette am ersten Lesefehler verloren.
- **Die Formen mit globaler Liste sind Altlast.**
  `DndShiftSpanResolver.buildShiftSpans(alarms, excludedShifts)` hat seit #219 nur noch Testaufrufer (`DndShiftSpanResolverTest`); die Form
  `DndOnCallCutoffResolver.cutoffInstants(alarms, onCallShifts, cutoffMinutes, zone)` rechnet weiter
  die Ruf-Kappung in `Tagesleiste.kt` (eine Uhrzeit für genau einen Typ). Der Scheduler nimmt die
  Formen mit Prädikat bzw. Minuten je Schicht. Zwei Rechenwege für dieselbe Frage sollen nicht
  dauerhaft nebeneinander stehen — die Weiterleitungen fallen, sobald kein Aufrufer mehr sie braucht
  (vorgemerkt in #228, offen). Stand 28.11.2026, am Code nachgesehen; der Nachtrag von #218 hatte
  den Wegfall schon für #219 angekündigt.

## Die Tagesleiste rechnet über dieselben Klassen (#219)

- **Die Tagesleiste liest Dimmen und „Nicht stören" aus der Konfiguration**
  (`TagesleisteQuellen.typen: Quelle<ShiftConfig>`) und bekommt die Einstellungen EINES Typs als
  Parameter:
  `DimDndEinstellungen(dimmen: TypDimmen, nichtStoeren: TypNichtStoeren, freierTag: OhneEintragEinstellungen)`;
  die Typ-Seite übergibt dort den Entwurf. Gerechnet wird über dieselben
  Klassen wie zur Laufzeit — `TypDimmQuelle`, `buildRuleSpansMitHerkunft`, `DndTypSchalter` —,
  indem der Adapter eine Ein-Typ-`ShiftConfig` baut (`mustertypKonfiguration`). Die Regel „rechnet
  `buildRuleSpans` auf einem erfundenen Mustertag, keine zweite Fensterlogik" gilt weiter.
- **Für Krank und Ignorieren rechnet die Leiste mit der festen Zeile**, nicht mit den eigenen
  Fenstern des Typs (`freierTagAus(config)`, bei „wie <Typ>" mit dessen Einstellungen) — weil die
  Laufzeit einen freigegebenen Tag als freien Tag rechnet (`TypDimmQuelle.fuerFreienTag`). Ein
  Frei-/Urlaub-/Fortbildung-Typ ohne Fenster unterdrückt dagegen; die feste Zeile springt nicht ein.
  `TagesleisteDimmenDndTest` hält „Leiste = Laufzeit" gegen den echten `DimScheduleUseCase` und
  `DndScheduleUseCase.fensterJetzt()` (Systemzone im Test auf UTC gestellt).

## Die Statuszeile und die Kurzform der Typ-Liste (#219, #220)

- **Die Statuszeile je Typ (`ermittleSchichtFolgen`) liest `ShiftDefinition.dimmen`/`.nichtStoeren`**,
  Rufbereitschaft über die KATEGORIE. `alsText()` sagt „Dimmen: aus / keine / N Fenster" und
  „Nicht stören: Dienstzeit / nach Dimmer" — das zeigt seit #220 nur noch die Tagesliste
  (`Tagesliste.kt`). Die Typ-LISTE nimmt die Dimm-Kurzform: „dimmt 20:00–Wecker" / „dimmt nach
  Dienst" / „N Dimm-Fenster" / „Dimmen aus"; bei Krank, Ignorieren, ausgeschaltetem Typ und einem Typ
  ohne Fenster steht dort nichts (Aufbau der Zeile: Skill `cfalarm-ui-und-navigation`).
- Texte zu „eigene Regel", „(+N ohne Wirkung)" oder „Dienstzeit ausgenommen" gibt es nicht mehr.
  Die Statustypen `DimmArt.ALLGEMEINE`/`UNTERDRUECKT`, `DienstzeitDnd.AUSGENOMMEN` und
  `DimmStatus.Wirksam.ohneWirkung` werden nicht mehr erzeugt und bleiben nur, weil `WeckerTage.kt`
  und seine Tests sie nennen (zum Entfernen vorgemerkt in #228).

## „Nacht davor": ein Feld, kein neuer Anker, und zwei Kodierungen an EINER Stelle (#227, #215)

- **Hergang:** Der erste Wurf von #215 kodierte „Nacht davor" nur mit dem `ALARM_SONST_CLOCK`-Start
  (laut `DimAnchor` „kein Start-Anker", er läuft über den Schichtpfad). Der rollt den Start nur dann
  auf den Vortag, wenn die Weckzeit VOR „Von" liegt — bei einem Nachtdienst-Wecker 19:30 und Von
  16:00 dimmte er die ganze Dienstnacht statt der Nacht davor. Das erzwang #227 (Entscheidung des
  Betreuers in #215).
- **`DimWindow.startAmVorabend` (Default `false`), kein neuer `DimAnchor`:** bei Start CLOCK, Ende
  CLOCK oder `ALARM_SONST_CLOCK` und Von ≥ Bis beginnt das Fenster des Tages D um Von an D−1 und
  endet um Bis an D (bzw. an der frühesten Weckzeit dazwischen, samt Nachlauf und „Hell ab"); mit
  Von < Bis wirkt das Feld nicht. Einziger Schaltpunkt ist `loeseNachtFenster` (`date.minusDays(1)`,
  Kalendertage statt −24 h). Es gilt daher an Schicht-Tagen und Tagen ohne Eintrag gleich und für
  „Nicht stören" über denselben `buildRuleSpans`; `resolveShiftWindow` liest es nicht, weil
  `resolveWindowForDate` diese Anker nie dorthin leitet. Ein neuer Anker hätte das Fenster nach einem
  Revert undeutbar gemacht (#213); ein unbekanntes Feld überlesen beide Stores. Test:
  `DimWindowNachtDavorTest`. „CLOCK↔CLOCK = lückenlos jede Kalendernacht" gilt weiter — ohne das
  Feld ist ein CLOCK-Start-Fenster die Nacht NACH dem Kalendertag.
- **Die Abbildung „Wann" ↔ Fenster steht an EINER Stelle: `DimFensterAbbildung` in
  `ui/screens/typ/DimmFensterForm.kt`, gemessen gegen `buildRuleSpans`** (Test `DimmFensterFormTest`):
  - „Nacht davor", Von ≥ Bis (auch gleich) = Start CLOCK, Ende CLOCK bzw. `ALARM_SONST_CLOCK`,
    `startAmVorabend = true`, `startNurAmWecktag = false`;
  - „Nacht davor", Von < Bis (Vorschlaf-Form, E14) = Start `ALARM_SONST_CLOCK` mit
    `startNurAmWecktag = true`, Feld aus;
  - „Abend danach" = CLOCK → CLOCK/`ALARM_SONST_CLOCK` ohne Feld;
  - „Nach dem Dienst" = `SHIFT_END` (Versatz 0) → CLOCK.
  **JEDES „Wann" schreibt `startAmVorabend` ausdrücklich** — sonst nähme der Wechsel von „Nacht
  davor" auf „Abend danach" das Feld aus der Basis mit und dimmte in der Nacht davor.
- **Die Rückrichtung `deute` ist total:** jede Kombination, die `kodiere` nie schriebe, ist
  `KENNUNG` — auch CLOCK + Feld bei Von < Bis (der Resolver ignoriert das Feld dort, trotzdem NIE
  „Abend danach", denn die Seite räumte es beim Schreiben ab), ein Weckzeit-Start bei Von ≥ Bis und
  `SHIFT_END` mit Feld (der Versatz wird zuerst geprüft). `CLOCK → ALARM` (der Vorschlaf der früheren
  Nachtdienst-Vorlage) und `SHIFT_END` mit Versatz ≠ 0 sind „nicht darstellbar" (Text „Eigene
  Zeiten").
- **An „Ohne Kalendereintrag"** erzeugen „Nach dem Dienst" und „Nacht davor" mit Von < Bis kein
  Fenster (es gibt keine Schicht und keinen Wecker); „Nacht davor" mit Von ≥ Bis dagegen schon. Die
  Typ-Seite (#221) weist nur auf die ersten beiden hin.

## Die feste Zeile „Ohne Kalendereintrag" (#223, #238)

- **`DimmenAbschnitt` und `NichtStoerenAbschnitt` bekommen ihre Werte dort nur über
  `OhneEintragAbschnittLogik`** (Hilfs-Entwurf mit `weckerAn = false`, Kategorie ohne Blocktage;
  zurück fließen nur Dimmen und „Solange gedimmt wird"). Ein gespeichertes `ALARM_SONST_CLOCK` bleibt
  dabei stehen und wirkt weiter, ohne dass die Karte es zeigt.
- **„Weitere Optionen" gibt es dort auch (#238)**, nur bei „Eigene Einstellungen": dieselbe
  `WeitereOptionenBlatt`, aber jede Änderung geht sofort über
  `OhneEintragAbschnittLogik.blattSchreibweg` → `ShiftViewModel.aendereOhneEintrag` →
  `nachDimmAenderung`. Statt eines Knopfs „Speichern" bekommt das Blatt `speicherHinweis` (den ganzen
  Satz liefert der Aufrufer; der frühere Parameter `speicherKnopf` entfällt). Warum die ganze Seite
  sofort schreibt und gegen den Vorgänger-Auftrag armiert: `reference/typ-seite.md` im UI-Skill.

## „Weitere Optionen": Anker-Editor und Tageslicht-Beispiel (#224)

- **Umgezogen** nach `ui/screens/typ/DimmenWeitereOptionen.kt`: `AnkerEditor`, `START_ANKER`,
  `ENDE_ANKER`, `AnkerFeld`, `feldFuerStartAnker`, `OffsetField`, `nachlaufBeispielText`; top-level in
  `viewmodel/ShiftViewModel.kt`: `nachlaufBeispiel`, `NachlaufBeispiel`, `BeispielGrund`. Deutsche
  Namen wie im übrigen Typ-Bereich: `AnchorButton` entfällt (Ersatz `AnkerKnopf`), `ClockField`
  entfällt (Ersatz `UhrzeitFeld`). Der Regel-Editor, der sie bis #226 mitbenutzte, ist entfallen.
- **Welche Fenster im Blatt den Anker-Editor bekommen, steht beim Öffnen fest**
  (`WeitereOptionenLogik.ohneForm`), nicht live über `DimFensterAbbildung.deute`. Sonst verschwände
  der Editor unter dem Finger, sobald eine Wahl das Fenster darstellbar macht (aus 16:00 „Zur
  Weckzeit" wird mit „Weckzeit, spätestens" ein „Abend danach").
- **„Dimmen nach dem Wecker" steht nur bei Ende `ALARM_SONST_CLOCK`**; sonst sagt ein Satz, warum
  (`NachlaufLage`). Die Tageslicht-Begründung (`tageslicht_dimmer_grund_*`) zeigt hier eine
  Beispielzeile; die frühere Vorschau-Zeile `tageslichtGrundZeile` ging mit `DimmerPreviewScreen`.

## Die Geräte-Einstellungen: Vorschau, Farbwärme, „Wer trotzdem durchkommt" (#225)

- **Die Vorschau ist EINE Klasse, `DimmVorschau`** (`viewmodel/DimmerViewModel.kt`, mit eigenem
  `previewScope`, `finally` unter `NonCancellable`, `cancelAndJoin`). Seit #226 hat sie einen
  Verwender: `GeraeteDimmenViewModel.zeigeVorschau()` (Einstellungen, 10 s, Geräte-Werte). Bis #226
  delegierte auch `DimmerRulesViewModel.previewRule()` (5 s, Formularwerte, inzwischen entfallen)
  dorthin; die Instanzen kannten einander nicht. `DimmerRulesViewModelPreviewCleanupTest` ist
  entfallen, Ersatz `GeraeteDimmenTest`.
- **Die Farbwärme für Typen ohne eigene (`dim_warmth`) hat wieder einen Setter,
  `DimOverlayPrefs.setWarmth`.** Der einzige Aufrufer `GeraeteDimmenViewModel.setzeFarbwaerme` ruft
  direkt danach nur `DimScheduleUseCase.enable()` (Darstellungs-Setter, kein
  `ZeitkettenArmierer`), und der Regler schreibt erst beim Loslassen — kein Entprell-Job, der am
  `viewModelScope` sterben könnte (Test `GeraeteDimmenTest`, ohne `enable()` rot).
- **„Wer trotzdem durchkommt" ist gemischt gepolt** und kommt aus der reinen Funktion
  `GeraeteTexte.werDurchkommt`: `block*` an = Gruppe kommt NICHT durch, `allowRepeatCallers` an =
  kommt durch, zählt aber nur bei `blockCalls`. Ein vertauschter Schalter macht
  `GeraeteDimmenNichtStoerenTest` rot. Vor dem ersten Lesen (`DndUiState.gelesen = false`) steht
  keine Aufzählung da.

## Was mit Etappe 3 entfallen ist (#217, #219, #224, #226)

- **Bildschirme und ViewModels (#226) entfallen:** `DimmerRulesViewModel`, die Klasse
  `DimmerViewModel` (die Datei bleibt und trägt `GeraeteDimmenViewModel` und `DimmVorschau`), `DimmerSettingsScreen`,
  `DimmerRuleConfigScreen`, `DimmerPreviewScreen`, `DimmerTabContent`, `previewRule`,
  `verdraengteRegeln` und `tageslichtGrundZeile`. Es gibt damit weder Regelliste noch Konflikt-Hinweis
  an einer verdrängten Regel noch Zeitleisten-Vorschau. `DimRuleUseCase`, `DimRuleRepository` und
  `DimmerModellMigration` bleiben bis Etappe 9 für Migration und Namens-Nachzug.
- **Vorlagen (#226):** `SchnellstartVorlage`, `NachtdienstRhythmus`, `NachtdienstRhythmusDialog`,
  `baueVorlagenRegel` und `vorhandeneRegelFuerVorlage` entfallen ersatzlos — „Wann" an der Typ-Seite
  ersetzt sie. Ihre Fenster leben in migrierten Beständen weiter; das gilt auch für den Vorschlaf
  CLOCK 16:00 bis `ALARM` mit `startNurAmWecktag` (#151), abgesichert durch `DimWindowResolverTest`
  und `DimmFensterFormTest` (Wecker 13:30: nur der Schlaf nach dem Dienst bleibt).
- **`DndViewModel.shiftNames` und `NachlaufBisHellWahl`** entfallen (beide ohne Aufrufer);
  `tageslichtEreignisName` liegt jetzt in `ui/screens/dimmer/DimTimeControls.kt`.
  `waehlbareSchichtNamen()` bleibt, sein Verwender ist die Schicht-Auswahl der Hue-Regel.
- **Tagesleiste (#219):** `DimmerQuelle` und `NichtStoerenQuelle` entfallen, ebenso
  `DimDndEinstellungen.dimEnabled`, `.regeln`, `.ausgenommeneSchichten` und der Parameter
  `regelwahl` von `dimmenUndNichtStoeren`; Ersatz siehe oben.
- **Diagnose (#217):** `KEIN_FENSTER_TROTZ_REGELN` → `KEIN_FENSTER_TROTZ_TYP_FENSTERN`.
