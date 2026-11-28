---
name: cfalarm-dimmer-und-dnd
description: Zusicherungen fuer den Schicht-Dimmer (systemweite Overlay-Verdunkelung ueber einen Accessibility-Dienst) und die DND-Steuerung ueber AutomaticZenRule in der CFAlarm-Wecker-App. Deckt das Ein-Quellen-Modell (Dimm-Fenster und DND-Schalter am Schichttyp, kein Hauptschalter), Fensteraufloesung und Tages-Vorrang, die Anker inklusive ALARM_SONST_CLOCK und 'Nacht davor', die Modellmigrationen, den Korrektur-Override, die rollende Tick-Kette, die Dienstzeit-Fenster aus dem ShiftSpanStore und den Rufbereitschaft-Cutoff ab. Zu verwenden bei Arbeit an DimWindowResolver, DimScheduleUseCase, TypDimmQuelle, TypModellMigration, DimFensterAbbildung, DimOverlayPrefs, DimmerModellMigration, DimAccessibilityService, DimNotificationService, GeraeteDimmenViewModel, DndScheduleUseCase, DndTypSchalter, DndPrefs oder DndOnCallCutoffResolver — und immer dann, wenn der Bildschirm zur falschen Zeit gedimmt bleibt, 'Nicht stoeren' waehrend der Dienstzeit abschaltet oder eine Dimm-Vorschau haengen bleibt.
---

# Schicht-Dimmer und DND-Steuerung

Unten stehen die **Kurzregeln** dieses Bereichs — was gilt, und was bei Bruch passiert.
Die wecker-kritische Teilmenge davon steht zusätzlich in `CLAUDE.md` (dort immer geladen, als
Sicherheitsnetz für den Fall, dass dieser Skill nicht anspringt); **alles Übrige steht
ausschließlich hier.** **Reicht die Kurzregel nicht, oder willst du eine davon ändern oder
umgehen: lies vorher die Hergang-Datei.** Dort steht, welcher Bug die Regel erzwungen hat — ohne
das baut man dieselbe Falle in neuer Form nach.

## Hergang und Belege

- `reference/dimmer.md` — Ein-Quellen-Modell und warum es drei Quellen gab, Fensteraufloesung,
  Anker, Modellmigration, Vorschau-Scopes, Korrektur-Override
- `reference/dnd.md` — AutomaticZenRule, Policy, Dienstzeit-Fenster, Rufbereitschaft-Cutoff
- `reference/typ-fenster.md` — Umbau Etappe 3: Dimm-Fenster und DND-Schalter am Typ, Typ-Migration,
  „Nacht davor", feste Zeile, Geräte-Einstellungen, was entfallen ist
- `reference/pflichtregeln-aus-claude-md.md` — die Pflichtregeln aus der CLAUDE.md im vollen Wortlaut, mit Begründung

---

## Kurzregeln

- **Es gibt GENAU EINE Fenster-Quelle: die Fenster am Schichttyp und an der festen Zeile „Ohne
  Kalendereintrag"** (`TypDimmQuelle`, seit #217 / 1.59.0). Einen Hauptschalter gibt es nicht mehr:
  `DimScheduleUseCase` liest weder `dim_enabled` noch die Dimm-Regeln. Der Adapter baut je Typ eine
  gewöhnliche `DimRule` für `buildRuleSpans`/`regelFuerTag` — kein zweiter Resolver. Typ ohne
  wirksames Fenster (`an = false` oder leer) = leere Regel = Unterdrückung des Tages, auch neben
  einem zweiten Eintrag; nur ein Name, den kein Typ trägt, ist `null`; freie und freigegebene Tage
  bekommen die feste Zeile, „wie <Typ>" aufgelöst wie auf der Typ-Seite
  (`OhneEintragWahl.gewaehlterTyp`). Gleichwertigkeit mit der migrierten Regel: `TypDimmQuelleTest`.
  **Wer eine zweite, „eingebaute" Quelle daneben stellt, baut die Kopplung wieder auf, an der das
  Drei-Quellen-Modell vor v1.34.0 gescheitert ist** (Hergang in `reference/dimmer.md`, der Umbau in
  `reference/typ-fenster.md`).
- **Dimm-Fenster werden in SCHLAFSPRACHE eingestellt** („Wann" an der Typ-Seite: „Nacht davor",
  „Abend danach", „Nach dem Dienst"; Abbildung in `DimFensterAbbildung`). Der Anker-Editor unter
  „Weitere Optionen" bleibt die Detailansicht — wer dort neue Nutzertexte anbringt, ersetzt damit
  NICHT die Schlafsprache; der Eigentümer ist an der Anker-Sprache gescheitert (18.09.2026), nicht
  an fehlenden Erklärungen. (Die erste Schlafsprache, die Nachtdienst-Vorlage von 1.43.0, ist mit
  #226 entfallen.)
- **Eine Bequemlichkeit gehört AUF die Fähigkeit, nicht NEBEN sie.** Was der Nutzer schnell
  einrichten können soll, ist eine Voreinstellung der vorhandenen Fähigkeit (heute „Wann" am Typ),
  danach sichtbar und änderbar — nie eine eigene Quelle mit eigenem Schalter. (Die früheren
  Regel-Vorlagen sind mit #226 entfallen, Liste in `reference/typ-fenster.md`.)
- **Ein Modellwechsel braucht eine Migration, und die braucht MEHR als den App-Start.**
  `DimmerModellMigration` läuft einmalig, versioniert (`dim_modell_migration`) und idempotent, aus
  ZWEI Anlässen: `MainActivity.onCreate` **und** `AlarmMaintenanceService.rescheduleSideChannels`
  (die einzige Kette, die den erreicht, der die App nach einem Auto-Update tagelang nicht öffnet).
  Sie **gated sich selbst gegen die Entsperrung** (CE-Storage liefert davor still leere Preferences
  — eine Migration darüber schriebe „alles aus" und setzte den Marker), sie ist **fail-safe**
  (scheitert etwas: Dimmer aus, Marker ungesetzt, WARN, Retry), und der **Konfigurations-Import
  nimmt den Marker zurück** — aber nur, wenn die Datei die ALTEN Schlüssel mitbringt UND kein
  `dim_enabled`. Die alten Preference-Schlüssel werden bewusst NICHT gelöscht (eine Version Rückweg).
  **Dasselbe Muster hat `TypModellMigration` (#216)**: dieselben beiden Anlässe, dazu läuft sie wie
  die Dimmer-Migration nach einem Konfigurations-Import, und sie prüft in fester Reihenfolge: entsperrt → `modellStufe` schon gesetzt (dann NIE ein
  Fehler) → `isPersistenceBlocked()` → `dim_modell_migration` gesetzt → Ruf-Altliste weg →
  Altbestand STRIKT → Konfiguration ERNEUT lesen → ein Schreibvorgang über `IShiftUseCase` →
  `ZeitkettenArmierer.armiere()`. Ein Fehlschlag steht nur im Speicher (`TypModellMigration.stand`,
  Karte im System-Status).
- **Ein Fenster traegt seine BLOCKPOSITION** (`DimWindow.blockPositionen`, seit 1.42.0): an
  welchen Tagen einer Folge gleicher Schichten es gilt — erster, mittlerer, letzter, einzelner.
  Die vier Werte sind DISJUNKT, ein alleinstehender Tag ist EINZELNER und nicht „erster und
  letzter zugleich". Default = alle (jedes Altfenster wirkt unveraendert). Der Resolver liest die
  Position an den NACHBARTAGEN ab (`blockpositionFuerTag`, Schichtname ohne Gross/Klein) — und
  **genau dafuer haelt der `ShiftSpanStore` beendete Spannen DREI Tage vor und MISCHT sie beim
  Schreiben unter den frischen Kalenderstand** (`mische`): der Kalender-Abruf beginnt bei
  „jetzt" und kennt beendete Dienste nicht mehr; ein Vollersatz machte am Morgen nach der
  dritten Nacht den letzten Tag zum einzelnen. Wer die Rueckschau wieder auf 24 h kuerzt oder
  den Vollersatz zurueckholt, bricht das lautlos. FREI-Regeln ignorieren das Feld. Der Editor
  zeigt die Chips nur bei einer benannten Schicht und laesst das letzte Haekchen stehen.
- **Beim Verbinden des Dimm-Dienstes wird der Soll-Zustand NEU BEWERTET** (Tick-Broadcast aus
  `onServiceConnected`). Sonst behauptet die Korrektur-Benachrichtigung nach einer
  Prozess-Rueckkehr bis zur naechsten Fenstergrenze „Dimmt nicht — Dienst ist aus", waehrend das
  Overlay laengst rendert (am Fairphone gesehen, 18.09.2026).
- **Ein Kalendertag kann ZWEI Schichten haben.** `buildRuleSpans`
  fragt JEDE Schicht des Tages (`slotsByDate`), nie nur die früheste. Wirksam wird trotzdem
  **pro Kalendertag GENAU eine Regel**; eine spezifische Regel **überschreibt** UNIVERSAL komplett,
  nicht additiv. UNIVERSAL heißt „alle **Tage**", nicht „alle Schichten". (Seit #217 kommt UNIVERSAL
  zur Laufzeit nicht mehr vor: `TypDimmQuelle` liefert je Typ eine Regel und die feste Zeile als
  FREI-Regel; eine Regel OHNE Fenster unterdrückt den ganzen Tag, auch neben einer dimmenden.)
- **Zwei VERSCHIEDENE Typen an einem Tag: es gewinnt der Typ der FRÜHESTEN Schicht** — plus WARN
  im Release-Log. Den Tag stattdessen auszulassen ist verboten (schaltet das Dimmen dort komplett
  ab — es gibt keine zweite Quelle, die einspränge), die Fenster zu vereinigen ebenfalls (additiv).
  Der Hinweis an der verdrängten Regel ist mit der Regelliste entfallen (#226); `findRuleConflicts`
  hat seither nur noch Testaufrufer.
- **Wirkung und Anzeige des Konflikts teilen EINE Funktion** (`regelFuerTag`, benutzt von
  `buildRuleSpans` und `findRuleConflicts`) — zwei Implementierungen würden auseinanderdriften.
- **Einen Typ aus der Nachtruhe zu nehmen heißt: Dimmen am Typ aus oder ohne Fenster** (leere Regel,
  sie unterdrückt den Tag ganz). Eine tages-granulare Ausnahmenliste (früher `istTagAusgeschlossen`,
  `nextDayCoversTonight`) gibt es nicht mehr — und damit keine Schichtnamens-Liste im Dimmer.
- **`findRuleForShift` und `TypDimmQuelle.fuerSchicht` nehmen den ERSTEN Treffer** (Groß/Klein
  egal) — zwei Typen mit demselben Namen: der zweite dimmt nie.
- **Die Alt-Regeln (bis Etappe 9 nur noch Quelle der Migration) binden über den NAMEN der
  Schichtdefinition** (`shiftPattern`) — jedes Umbenennen zieht sie weiter über
  `DimRuleUseCase.renameShiftPattern()` mit (Hergang im Skill `cfalarm-kalender-und-schichten`).
  Die Fenster am Typ hängen am Typ selbst und brauchen keinen Namens-Nachzug.
- **Leere Fensterliste = Unterdrückung dieser Nacht**, NICHT „keine Regel". Nicht wegoptimieren.
- **CLOCK↔CLOCK = lückenlos jede Kalendernacht**; ALARM/SHIFT_END brauchen eine **Schichtspanne**.
- **`ALARM_SONST_CLOCK` ist ein reiner ENDE-Anker: „bis zur Weckzeit, spätestens um X".** Er sucht
  die früheste Weckzeit ECHT nach dem Fensterstart und ECHT vor der Uhrzeit-Schranke — in der
  gesamten Zeitleiste, nicht im Slot des Tages. Mit CLOCK-Start gilt er deshalb für JEDE
  Kalendernacht und braucht keinen Wecker an diesem Datum. Leere Zeitleiste ⇒ Ende an der Uhrzeit
  (fail-safe hell, nie endlos dunkel). Am START verhält er sich wie CLOCK.
- **Die Weckzeit-Zeitleiste ist eine ZWEITE, flachere Sicht neben den Slots — sie ersetzt sie nicht.**
  Slots beantworten „welche Regel gilt an diesem Tag" und brauchen den Schichtnamen; die Zeitleiste
  nur „wann klingelt als nächstes etwas". Deshalb enthält sie zusätzlich MANUELLE Wecker (die keine
  Schichtspanne haben und bewusst NICHT durch den Freie-Tage-Filter laufen) — sie in die Slots zu
  legen würde dagegen die Regel-Auswahl FREI↔Schicht still kippen.
- **Zeitrechnung: echte Wanduhrzeit + Datums-Arithmetik**, niemals „Mitternacht-Instant + Minuten"
  und niemals fixe 24h-Millis (DST-Tage haben 23/25 h).
- **Die Fenster-Schleifen beginnen einen Kalendertag VOR `today`** (`LOOKBACK_DAYS`) — Achtung bei
  Tests, die Spannen absolut zählen.
- **Das Fenster-Ende ist HALB OFFEN (`first <= now < last`)** — sonst bleibt der Randzustand hängen.
- **Die Tick-Kette darf nicht abreißen**: Keep-alive-Tick (6 h), solange der Dimmer AN ist, plus
  kurzer Retry-Tick (15 min) nach einem Lesefehler der Fenster-Grundlage.
- **Ein GEKLINGELTER Wecker bleibt drei Tage in der Weckzeit-Zeitleiste** (`GeklingelteWeckzeiten`,
  geschrieben vom `AlarmReceiver` NACH der Skip-Prüfung, Device-Protected). Ohne ihn fiel ein
  manueller Wecker nach dem Klingeln heraus, und jede Neuberechnung öffnete die Nacht bis zur
  Uhrzeit-Grenze erneut (FP6, 13.10.2026). Nicht beim ANLEGEN merken — dann beendete ein gelöschter
  Wecker das Fenster. Anlegen/Löschen eines manuellen Weckers armiert Dimmer und DND neu, und
  der Receiver armiert nach dem Merken neu (Rennen gegen den Tick an der Weckzeit).
- **„Bis Weckzeit + X, spätestens um die Uhrzeit"** (`DimWindow.weckzeitNachlaufMinuten`, eigenes
  Feld, NICHT `endOffsetMinutes`): die Uhrzeit bleibt harte Obergrenze, und DND-Modus 1 liest die
  Zeitleiste OHNE den Nachlauf (`mitWeckzeitNachlauf = false`) — nach dem Wecken erreichbar.
  Hergang in `reference/dimmer.md`, letzter Abschnitt.
- **Ein MANUELLER Wecker spannt kein Dimm-Fenster mehr selbst auf** — er kann eines nur noch über
  `ALARM_SONST_CLOCK` beenden. ALARM-verankerte Regelfenster lösen über die Schichtspannen auf; die
  alte Wellness-Quelle legte ihr Fenster dagegen um JEDE Weckzeit des Alarm-Bestands. Bewusst in
  Kauf genommen, Begründung und Ausweg stehen im Code bei `computeWindows`.
- **Das Aufräumen der Dimm-VORSCHAU darf nicht am `viewModelScope` hängen** — je ein eigener
  `previewScope` mit `CoroutineExceptionHandler`, Reset im `finally` unter `NonCancellable`, und ein
  zweiter Tipp räumt die laufende Vorschau per `cancelAndJoin()` ZUERST auf. Diese Scopes werden
  bewusst **nicht** in `onCleared()` gecancelt — genau das wäre der Bug. Seit #225 ist die Vorschau
  EINE Klasse, `DimmVorschau` (`viewmodel/DimmerViewModel.kt`), mit einem Verwender:
  `GeraeteDimmenViewModel.zeigeVorschau()` (Einstellungen, 10 s). Regressionstest: `GeraeteDimmenTest`.
- **Der Korrektur-Override lebt im DataStore**, mit `windowEnd` UND `windowStrength` als
  Fenster-Identität (reine `windowEnd`-Identität reicht nicht). Kein neuer Timer — der rollende Tick
  macht ihn stale.
- **`DimNotificationService` klemmt den `strengthDelta` selbst** (Bereich
  `-active.strength..(STRENGTH_MAX - active.strength)`), nicht nur den abgeleiteten `effectiveStrength`.
- **Der Override-Mutex lebt im `@Singleton DimOverlayPrefs`** (`withOverrideLock`), nicht lokal im
  Service — mindestens sechs unabhängige Aufrufer teilen diesen Zustand.
- **Jeder Setter, der einen `DimOverlayPrefs`-Wert schreibt, MUSS direkt danach
  `DimScheduleUseCase.enable()` rufen** — auch `setDimCorrectionNotificationEnabled()`. Und zwar
  **unentprellt**.
- **`isRunning()` wird in `onUnbind` zurueckgesetzt, nicht nur in `onDestroy`.** Ein entbundener,
  aber nicht zerstoerter Dienst meldete sonst weiter „laeuft" — und Diagnosezeile wie Status-Karte
  behaupteten einen Dimmer, den es gerade nicht gab.
- **Jeder Aus-Weg von `applyCurrentState()` protokolliert seinen GRUND**, und der Dienst
  protokolliert Verbinden/Entbinden/Zerstoeren auf **WARN** (Release-Log). Ohne das ist ein
  „warum war der Bildschirm kurz hell?" nicht rekonstruierbar. Nur der Verdachtsfall
  (Fenster am Typ da + trotzdem kein Fenster, `KEIN_FENSTER_TROTZ_TYP_FENSTERN`) ist WARN, der Rest DEBUG.
- **Ein Dimm-Fenster, das nichts bewirken KANN, muss das SAGEN — nicht nur loggen.** Läuft ein
  Fenster, während der Bedienungshilfen-Dienst nicht gebunden ist, meldet die
  Korrektur-Benachrichtigung „Dimmt nicht — Bedienungshilfen-Dienst ist aus" (ohne die drei
  Korrektur-Knöpfe) und `applyCurrentState()` schreibt ein entprelltes WARN ins Release-Log.
  Entscheidung in `DimDiagnostik.dimmenWirkungslos()`, bewusst nur bei aktivem, nicht pausiertem
  Fenster. Vorher stand der Dienst-Zustand NUR in einer DEBUG-Zeile, während die Benachrichtigung
  einen Verdunkelungswert behauptete — Hergang in `reference/dimmer.md`.
- **„Nicht gebunden" hat ZWEI Lagen, und Android zeigt nur den Schalter** (`DimDiagnostik.DienstLage`,
  seit 27.09.2026): Schalter AUS, oder Schalter AN und trotzdem vom System getrennt (Signatur:
  ordentliches `entbunden`+`zerstoert` ohne folgendes `verbunden`; belegt: eine `UiAutomation`,
  die ALLE Bedienungshilfen trennt). Karte und Benachrichtigung sagen für die zweite Lage
  „eingeschaltet, aber nicht verbunden" mit eigener Abhilfe (aus/ein, sonst Neustart) — wer das
  wieder zu „nicht aktiv — aktivieren" zusammenlegt, schickt den Nutzer zu einem Schalter, der
  schon an ist. Die `entbunden`-Zeile trägt, ob der Schalter danach noch AN steht.
  **Am Fairphone kein mobile-mcp**: sein Geräte-Server hält die UiAutomation dauerhaft und
  überlebt die Sitzung. Hergang in `reference/dimmer.md`.
- **Und dieser Hinweis muss AN DIE STELLE FÜHREN, an der man ihn auflöst.** Ein Tipp darauf öffnet
  den Status-Tab, rollt die Bedienungshilfen-Karte ins Bild und zeigt deren Offenlegung; ihr Knopf
  führt über `ACTION_ACCESSIBILITY_SETTINGS` in die Bedienungshilfen. **Die Offenlegung bleibt
  dazwischen** — der direkte Sprung aus der Benachrichtigung ginge an der Play-Pflicht vorbei, und
  genau deshalb hat der Weg zwei Stationen statt einer. Signalweg und seine vier Fallen
  (SINGLE_TOP, `setIntent()`, `savedInstanceState == null`, Zähler statt Boolean) in
  `reference/dimmer.md`.
- **Weiter als bis zur Bedienungshilfen-LISTE kommt diese App nicht — beides durchgemessen.**
  `ACTION_ACCESSIBILITY_DETAILS_SETTINGS` (die Seite dieses Dienstes) ist unerreichbar — nicht „auf
  manchen Geräten", sondern immer. Die Ziel-Activity ist seit Android 11 mit
  `OPEN_ACCESSIBILITY_DETAILS_SETTINGS` geschützt (`signature|installer`, `@hide`, AOSP: „Not for
  use by third-party applications"); an FP6 und Emulator gemessen, beide `Permission Denial`. Wer
  sie zurückholt, baut einen Zweig, der nur seinen eigenen Rückfall erreicht und dabei jedes Mal
  ein WARN ins Release-Log schreibt. **Und den eigenen Eintrag in der Liste hervorzuheben
  (`:settings:fragment_args_key`) wirkt aus der Shell, aus DIESER App aber nicht** — mit und ohne
  Argument-Bündel, mit und ohne `NEW_TASK`, bei identischem Intent laut `dumpsys`. Beide Zusätze
  sind draußen, je ein Test hält sie draußen. Hergang, die Messtabelle und der Prüfweg ohne
  Installation in `reference/dimmer.md`.
- **Das Verschwinden des Dienstes ist nicht protokollierbar — die RÜCKKEHR schon.** Bei einem
  `SIGKILL` (App-Update, Speicherdruck, Absturz) läuft weder `onUnbind` noch `onDestroy`. Deshalb
  setzt `onServiceConnected()` einen SharedPreferences-Merker (`commit()`, nicht `apply()`), den
  `onUnbind`/`onDestroy` wieder wegräumen; steht er beim nächsten Verbinden noch, gab es ein
  unerwartetes Ende → WARN. `DimDiagnostik.rueckkehrArt()` entscheidet. **Der Geräteneustart MUSS
  ausgenommen bleiben** (`elapsedRealtime()` fällt beim Booten auf null — die Wanduhr taugt dafür
  nicht), sonst steht nach jedem Boot ein falsches WARN im Release-Log.
- **`DimCorrectionNotifier.show()` prüft `areNotificationsEnabled()` vor `notify()`.**
- **DND: zwei Fenster-Trigger plus ein Klipp-Modifikator, kein Regel-Editor** — seit #218 JE TYP:
  die Schalter liegen am Typ (`DndTypSchalter`), einen globalen gibt es nicht mehr (Kurzregeln
  unten, Abschnitt Etappe 3).
- **Jeder Setter, der DIMM-FENSTERGRENZEN verschiebt, armiert BEIDE Ketten neu — Dimmer, dann
  DND** — seit v1.34.3 an EINER Stelle, `ZeitkettenArmierer.armiere()`, statt fuenfmal von Hand.
  Reihenfolge, `NonCancellable` und das getrennte Fangen liegen dort. Reine DARSTELLUNGS-Setter (Verdunkelung/Wärme) rufen bewusst nur den Dimmer.
- **Modus 1 dupliziert KEINE Fenster-Logik** — er ruft `previewTimelineWithStatus()` direkt auf.
  Einbahnstraße: `dnd/` liest von `dimmer/`, nie umgekehrt. **Das `…WithStatus` ist kein Luxus**: der
  Lesefehler muss über die Grenze kommen, sonst plant DND den 6h-Keep-alive statt des 15-min-Retry.
- **Modus 2 braucht `AlarmInfo.shiftStartTime`**, nicht `triggerTime`.
- **Ein freigegebener Tag („Tag freigeben") verliert seine Schichtspannen an EINER Stelle:
  `FreieTageStore.filtereSpannen`, angewendet direkt nach `spansNow()` in BEIDEN
  `computeWindows()` (Dimmer und DND).** Danach sieht der Tag fuer die Fensterlogik aus wie ein
  echter freier Tag — die feste Zeile greift (`TypDimmQuelle.fuerFreienTag`), und ein Fenster mit `ALARM_SONST_CLOCK`-Ende findet
  an ihm keine Weckzeit mehr, endet also an seiner Uhrzeit-Schranke. **Das ist die ausdrueckliche Nutzer-Entscheidung, kein Versehen**: wer frei
  hat, will den Abend eines freien Tages. Ein dritter Tageszustand „gar kein Dimmen" wurde bewusst
  verworfen — die Regelliste koennte ihn nicht anzeigen. Folge fuer die Nutzertexte: „Nicht
  stoeren bleibt aus" gilt NUR fuer die Dienstzeit; nachts kann Modus 1 weiterhin schalten, weil
  er dem Dimmer folgt. Hergang im Skill `cfalarm-wecker-und-boot` (dortige Hergang-Datei zu „Tag freigeben").
  Die Wecker-Tagesliste liest die Spannen ABSICHTLICH ungefiltert (reine Anzeige: sie nennt den
  ausfallenden Dienst) — das ist keine zweite Filterstelle, sondern kein Wirkungs-Konsument.
- **Die Dienstzeit-Fenster kommen aus `ShiftSpanStore`, NICHT aus dem Alarm-Bestand** — ein Alarm
  überlebt die Weckzeit nicht. Spannen werden in `syncAlarms()` **vor** dem Vergangenheits-Filter
  geschrieben, **auch in den beiden Leer-Zweigen**, und der Schreibvorgang ist nicht-fatal gekapselt.
  Eine Spanne kennt bewusst kein `isActive` und kein „übersprungen".
- **`ShiftSpan.alarmTriggerTime` ist NICHT redundant** — daraus leitet `DimWindowResolver` den
  Kalendertag ab. Ein Platzhalter datiert alle Dimm-Fenster auf 1970.
- **Rufbereitschaft-Cutoff klippt, statt eine eigene Fensterlogik/Policy zu duplizieren.** Zwei Fallen:
  der Cutoff-Tag muss auf den Folgetag rollen, und der Zeitpunkt wird als echte Wanduhrzeit aufgelöst.
  **Erst je Fenster kappen, DANN vereinen** (wie `DndScheduleUseCase.computeWindows`, so auch die
  Tagesleiste, #193): umgekehrt verschmilzt ein Bereich ab 06:00 mit dem an 05:00 gekappten davor
  und verschluckt den Cutoff.
- **`DimWindow.startNurAmWecktag` (Default `false`) lässt einen CLOCK-Start auf den Kalendertag der
  Weckzeit zeigen, nie auf den Vortag; liegt die Weckzeit davor, entfällt das Fenster** (#151).
  Gesetzt wird es von „Nacht davor" mit Von < Bis (`DimFensterAbbildung`) und steht in migrierten
  Vorschlaf-Fenstern der früheren Nachtdienst-Vorlage; der allgemeine Rücksprung in
  `clockAtOrBefore` bleibt („20:00 vor Wecker 05:30" ist der Vorabend).
- **Die Tagesleiste der Typ-Seite rechnet Dimmen und „Nicht stören" NICHT nach**, sondern lässt
  dieselben Klassen wie die Laufzeit (`TypDimmQuelle`, `buildRuleSpansMitHerkunft`,
  `DndTypSchalter`) auf einer Ein-Typ-Konfiguration (`mustertypKonfiguration`) und einem erfundenen
  Mustertag laufen (`tagesleiste/TagesleisteDimmenDnd.kt`, `dimmenUndNichtStoeren`, #194/#219).
  Gezeigt werden nur die Fenster des Kalendertags der Weckzeit; den EIGENEN Ruf-Cutoff kappt
  `Tagesleiste.rechne()`, die Cutoffs der NACHBARTAGE im Block der Adapter selbst
  (`kappenAmNachbarCutoff`). Wer eine Anzeige der Dimm-/DND-Fenster baut, ruft dieselben Resolver —
  eine zweite Fensterlogik driftet.
- **`AutomaticZenRule`, nicht rohes `setInterruptionFilter()`** (das überschreibt manuelles DND und
  fremde Automatisierung kommentarlos). Nur ab API 30.
- **Policy vollständig nutzer-konfigurierbar, NICHTS hart codiert.** Ein hart gesetztes
  `allowMedia(false)` hat live einen laufenden Podcast stummgeschaltet.
- **`ensureZenRule()` aktualisiert eine registrierte Regel bei JEDEM Tick** (`updateAutomaticZenRule()`)
  und prüft `Build.VERSION.SDK_INT` direkt (wegen Lint).
- **Eigener Request-Code `REQ_DND_TICK = 7712`**, getrennt von Dimmer-Tick (7710) und Wartung (0).
- **`DndScheduleUseCase.CONDITION_ID` ist `by lazy`** (sonst scheitert die Companion-Init im Test-JVM).
- **„Koppeln mit Schlafenszeit" ist final verworfen** (AOSP-verifiziert): fremde Zen-Regeln sind
  nicht lesbar, `MODIFY_DAY_NIGHT_MODE` ist `signature|privileged` und `@hide`. Nicht erneut aufrollen.
- **DND protokolliert seinen ZUSTAND, nicht nur den naechsten Wechsel** — eine Zeile pro Wechsel
  auf WARN (`DndDiagnostik.zustandszeile`, aufgerufen NACH dem erfolgreichen
  `setAutomaticZenRuleState`), mit Uhrzeit-Fenster und Quelle beim AN und einem von vier
  unterscheidbaren Gruenden beim AUS. Entprellt ueber die zuletzt protokollierte Zeile, sonst
  schriebe jeder Keep-alive-Tick dieselbe Zeile. Ohne das laesst sich am Tag danach nicht
  beantworten, ob „Nicht stoeren" nachts an war — und **Androids eigenes Zen-Protokoll taugt als
  Ersatz nicht** (siehe naechster Punkt).
- **Logcat-Fallstrick**: `W/System.err` mit `Thread.dumpStack()` um `setAutomaticZenRuleState()` ist
  Androids eigenes Tracing, kein Crash. Und im Zen Log von `dumpsys notification` ist der Abschnitt
  **State Changes** — der einzige, der „Regel an/aus" beantwortet — nach gut einer halben Stunde
  ueberschrieben: Googles Digital Wellbeing ruft dort im MINUTENTAKT `setAzrState` auf seiner
  eigenen, abgeschalteten Regel auf. Der Puffer fasst **100 Eintraege**, Wellbeing erzeugt drei pro
  Minute; die halbe Stunde ist also ausgerechnet, nicht geraten. Fuer eine Frage vom Vortag ist er
  leer — deshalb das eigene Protokoll. Das Geschwister-Unterlog **Interception Events** trifft es
  NICHT (eigene 100 Plaetze, ungeflutet, am 05.09.2026 knapp 15 Stunden zurueck): welche
  Benachrichtigung unterdrueckt wurde, steht dort weiterhin.
- **„Hell ab“ (#140, `DimWindow.nachlaufBisHell`) kuerzt NUR den Nachlauf** — Ende = fruehestes
  von Weckzeit + Nachlauf, Uhrzeit-Obergrenze und Hell-Zeitpunkt, aber **nie vor der Weckzeit**;
  Fensteranfang, Faelle ohne Weckzeit und „Nicht stören“ bleiben unberuehrt. Der Resolver bleibt
  rein: er bekommt nur eine `hellZeitpunkt`-Funktion (Default: nie hell), keinen Repository-Zugriff.
  Jede Luecke (kein Ort, Lesefehler, gesperrt, Polartag/-nacht) heisst VOLLER Nachlauf. Den Ort
  liefert ausschliesslich `TageslichtQuelle.stand()`. Hergang in `reference/dimmer.md`.

## Umbau Etappe 3: Dimmen und „Nicht stören" am Typ — Kurzregeln

Hergang, Tests und was entfallen ist: `reference/typ-fenster.md`.

- **Die Konfiguration wird ZUERST und STRIKT gelesen — im Dimmer (`computeWindows()`) wie in DND**
  (`getCurrentShiftConfig()`), vor jedem anderen CE-Speicher. Ein Lesefehler ist `alarmReadFailed`
  (15-min-Retry), nie „kein Typ hat Fenster" bzw. „kein Schalter an" — sonst bestellt sich die Kette
  am ersten vorübergehenden Lesefehler ab. DND meldet dafür `LESEFEHLER`, geprüft VOR `KEINE_QUELLE`.
- **„Nicht stören" liest die Schalter AM TYP:** „Solange gedimmt wird" =
  `previewTimelineWithStatus(mitWeckzeitNachlauf = false, nurTage = DndTypSchalter::solangeGedimmt)`,
  „Während des Kalendereintrags" = `buildShiftSpans(slots, DndTypSchalter::waehrendEintrag)`, der
  Ruf-Cutoff = `TypNichtStoeren.endetSpaetestensMinuten` des Ruf-Typs. Die alten `dnd_*`-Schalter
  liest `DndScheduleUseCase` nicht mehr (sie bleiben liegen). Der Cutoff allein löst nichts aus.
- **DND bildet Schichtnamen und feste Zeile GENAU SO auf einen Typ ab wie der Dimmer**
  (`TypDimmQuelle.fuerSchicht`, `OhneEintragWahl.gewaehlterTyp`) — sonst folgt „Solange gedimmt
  wird" an einem Tag einem anderen Typ als dem, dessen Fenster gerechnet wird.
- **Der Tagesfilter sitzt VOR dem Vereinen** und braucht die Herkunft der Spanne
  (`buildRuleSpansMitHerkunft` → `HerkunftSpan`, `SpanHerkunft.Schicht`/`OhneSchicht`). Die Herkunft
  ist ein Umschlag, kein Feld von `DimSpan` (deren `equals` tragen Vorschau und Override-Identität).
- **Die Stärke je Fenster reist als `DimRule.fensterStaerken` (`@Transient`)**, gelesen über
  `staerkeFuerFenster(i)` — nie in den Regel-Speicher oder einen Export.
- **Speichern eines Typs armiert Dimmer und DND** über `NacharmierBedarf.mitTypEinstellungen`
  (Anlass `SCHICHTTYP`), entschieden von `TypDimmQuelle.dimmenOderNichtStoerenGeaendert`; eine reine
  Umbenennung zählt nicht (eigener Nachzug).
- **„Nacht davor" ist `DimWindow.startAmVorabend`, kein neuer `DimAnchor`** (#227): einziger
  Schaltpunkt `loeseNachtFenster`, wirkt nur bei Von ≥ Bis. Ein neuer Anker wäre nach einem Revert
  undeutbar (#213), ein unbekanntes Feld wird überlesen.
- **Die Abbildung „Wann" ↔ Fenster steht an EINER Stelle (`DimFensterAbbildung`,
  `DimmFensterForm.kt`), gemessen gegen `buildRuleSpans`.** JEDES „Wann" schreibt
  `startAmVorabend` ausdrücklich, und `deute` ist total: was `kodiere` nie schriebe, ist `KENNUNG`.
- **Die Tagesleiste rechnet Krank und Ignorieren mit der FESTEN ZEILE** (`freierTagAus`), weil die
  Laufzeit einen freigegebenen Tag als freien Tag rechnet; ein Frei-/Urlaub-Typ ohne Fenster
  unterdrückt dagegen. „Leiste = Laufzeit" hält `TagesleisteDimmenDndTest` gegen die echten Scheduler.
- **An der festen Zeile bekommen Dimm- und DND-Karte ihre Werte nur über
  `OhneEintragAbschnittLogik`**, „Weitere Optionen" schreibt dort sofort (`blattSchreibweg` →
  `aendereOhneEintrag`); ein gespeichertes `ALARM_SONST_CLOCK` bleibt stehen, ohne dass die Karte es zeigt.
- **Welche Fenster im Blatt „Weitere Optionen" den Anker-Editor bekommen, steht beim Öffnen fest**
  (`WeitereOptionenLogik.ohneForm`), nicht live — sonst verschwindet der Editor unter dem Finger.
- **Die Farbwärme ohne eigene am Typ (`dim_warmth`) schreibt nur `DimOverlayPrefs.setWarmth`**, und
  ihr einziger Aufrufer (`GeraeteDimmenViewModel.setzeFarbwaerme`) ruft danach nur
  `DimScheduleUseCase.enable()`; der Regler schreibt beim Loslassen.
- **„Wer trotzdem durchkommt" ist gemischt gepolt und kommt aus EINER reinen Funktion**
  (`GeraeteTexte.werDurchkommt`): `block*` an = kommt NICHT durch, `allowRepeatCallers` an = kommt
  durch, zählt nur bei `blockCalls`.
- **Zwei Rechenwege für dieselbe Frage stehen nicht dauerhaft nebeneinander:** die
  Resolver-Formen mit globaler Liste (`buildShiftSpans(alarms, excludedShifts)`, `cutoffInstants`
  mit `onCallShifts`) sind Weiterleitungen und fallen, sobald kein Aufrufer sie mehr braucht.
