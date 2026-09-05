---
name: cfalarm-dimmer-und-dnd
description: Zusicherungen fuer den Schicht-Dimmer (systemweite Overlay-Verdunkelung ueber einen Accessibility-Dienst) und die DND-Steuerung ueber AutomaticZenRule in der CFAlarm-Wecker-App. Deckt das Ein-Quellen-Modell (Regeln als einzige Fenster-Quelle, ein Schalter), Fensteraufloesung und Regel-Vorrang, die Anker inklusive ALARM_SONST_CLOCK, die Modellmigration, den Korrektur-Override, die rollende Tick-Kette, die Dienstzeit-Fenster aus dem ShiftSpanStore und den Rufbereitschaft-Cutoff ab. Zu verwenden bei Arbeit an DimWindowResolver, DimScheduleUseCase, DimOverlayPrefs, DimmerModellMigration, DimAccessibilityService, DimNotificationService, DimmerViewModel, DimmerRulesViewModel, DndScheduleUseCase, DndPrefs oder DndOnCallCutoffResolver — und immer dann, wenn der Bildschirm zur falschen Zeit gedimmt bleibt, 'Nicht stoeren' waehrend der Dienstzeit abschaltet oder eine Dimm-Vorschau haengen bleibt.
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

---

## Kurzregeln

- **Es gibt GENAU EINE Fenster-Quelle: die Regeln** (seit v1.34.0). Ein Schalter (`dim_enabled`)
  sagt „Dimmen an/aus"; WANN, wie dunkel und wie warm gedimmt wird, steht ausschließlich in einer
  `DimRule`. Die früheren Quellen „Wellness/Wind-down" und „Nacht-Standard" sind ersatzlos
  ausgebaut — beide sind als gewöhnliche Regel ausdrückbar (Nachtruhe = ein Fenster
  `CLOCK 22:00 → ALARM_SONST_CLOCK 07:00`, Wellness = `ALARM −X → ALARM +0`). **Wer eine zweite,
  „eingebaute" Quelle daneben stellt, baut die Kopplung wieder auf, an der das alte Modell
  gescheitert ist** (Hergang in `reference/dimmer.md`).
- **Eine Bequemlichkeit gehört AUF die Fähigkeit, nicht NEBEN sie.** Was der Nutzer schnell
  einrichten können soll, wird als Vorlage ausgedrückt, die eine gewöhnliche, danach sichtbare und
  änderbare Regel anlegt (`DimmerRulesViewModel.SchnellstartVorlage`) — nicht als eigene Quelle mit
  eigenem Schalter. Eine Vorlage legt **keine zweite aktivierte Regel auf demselben `shiftPattern`**
  an (die wäre tot, siehe `findRuleForShift`), sondern benennt die vorhandene und bietet an, sie zu
  öffnen; ohne Schichtnamen schreibt sie gar nichts.
- **Ein Modellwechsel braucht eine Migration, und die braucht MEHR als den App-Start.**
  `DimmerModellMigration` läuft einmalig, versioniert (`dim_modell_migration`) und idempotent, aus
  ZWEI Anlässen: `MainActivity.onCreate` **und** `AlarmMaintenanceService.rescheduleSideChannels`
  (die einzige Kette, die den erreicht, der die App nach einem Auto-Update tagelang nicht öffnet).
  Sie **gated sich selbst gegen die Entsperrung** (CE-Storage liefert davor still leere Preferences
  — eine Migration darüber schriebe „alles aus" und setzte den Marker), sie ist **fail-safe**
  (scheitert etwas: Dimmer aus, Marker ungesetzt, WARN, Retry), und der **Konfigurations-Import
  nimmt den Marker zurück** — aber nur, wenn die Datei die ALTEN Schlüssel mitbringt UND kein
  `dim_enabled`. Die alten Preference-Schlüssel werden bewusst NICHT gelöscht (eine Version Rückweg).
- **Ein Kalendertag kann ZWEI Schichten haben.** `buildRuleSpans`
  fragt JEDE Schicht des Tages (`slotsByDate`), nie nur die früheste. Wirksam wird trotzdem
  **pro Kalendertag GENAU eine Regel**; eine spezifische Regel **überschreibt** UNIVERSAL komplett,
  nicht additiv. UNIVERSAL heißt „alle **Tage**", nicht „alle Schichten".
- **Konflikt zweier VERSCHIEDENER spezifischer Regeln an einem Tag: es gewinnt die Regel der
  FRÜHESTEN Schicht** — plus WARN im Release-Log UND ein Hinweis an der verdrängten Regel in der
  Regelliste. Den Tag stattdessen auszulassen ist verboten (schaltet das Dimmen dort komplett ab —
  es gibt keine zweite Quelle mehr, die einspränge), die Fenster zu vereinigen ebenfalls (additiv).
- **Wirkung und Anzeige des Konflikts teilen EINE Funktion** (`regelFuerTag`, benutzt von
  `buildRuleSpans` und `findRuleConflicts`) — zwei Implementierungen würden auseinanderdriften.
- **Eine Schicht aus der Nachtruhe zu nehmen heißt heute: eine spezifische Regel mit LEERER
  Fensterliste** (sie überschreibt UNIVERSAL für diesen Tag und unterdrückt ihn damit ganz). Die
  frühere tages-granulare Ausnahmenliste des Nacht-Standards (`istTagAusgeschlossen`,
  `nextDayCoversTonight`) gibt es nicht mehr — und damit auch keine Schichtnamens-Liste im Dimmer,
  die beim Umbenennen nachgezogen werden müsste (die Regeln binden weiter über `shiftPattern`).
- **`findRuleForShift` nimmt den ERSTEN Treffer** — zwei Regeln auf demselben Muster: die zweite ist tot.
- **Dimmer-Regeln binden über den NAMEN der Schichtdefinition** (`shiftPattern`), der frei änderbar
  ist — jedes Umbenennen zieht sie über `DimRuleUseCase.renameShiftPattern()` mit (Hergang im Skill
  `cfalarm-kalender-und-schichten`).
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
- **Ein MANUELLER Wecker spannt kein Dimm-Fenster mehr selbst auf** — er kann eines nur noch über
  `ALARM_SONST_CLOCK` beenden. ALARM-verankerte Regelfenster lösen über die Schichtspannen auf; die
  alte Wellness-Quelle legte ihr Fenster dagegen um JEDE Weckzeit des Alarm-Bestands. Bewusst in
  Kauf genommen, Begründung und Ausweg stehen im Code bei `computeWindows`.
- **Das Aufräumen der Dimm-VORSCHAU darf nicht am `viewModelScope` hängen** — je ein eigener
  `previewScope` mit `CoroutineExceptionHandler`, Reset im `finally` unter `NonCancellable`, und ein
  zweiter Tipp räumt die laufende Vorschau per `cancelAndJoin()` ZUERST auf. Diese Scopes werden
  bewusst **nicht** in `onCleared()` gecancelt — genau das wäre der Bug. Betrifft heute nur noch
  `previewRule()`: die 5-Sekunden-Vorschau des Dimmer-Reiters ist mit dem
  Ein-Modell-Umbau entfallen, der zugehörige Regressionstest zog auf `previewRule()` um statt
  gelöscht zu werden.
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
  (Dimmer an + Regeln da + trotzdem kein Fenster) ist WARN, der Rest DEBUG.
- **Ein Dimm-Fenster, das nichts bewirken KANN, muss das SAGEN — nicht nur loggen.** Läuft ein
  Fenster, während der Bedienungshilfen-Dienst nicht gebunden ist, meldet die
  Korrektur-Benachrichtigung „Dimmt nicht — Bedienungshilfen-Dienst ist aus" (ohne die drei
  Korrektur-Knöpfe) und `applyCurrentState()` schreibt ein entprelltes WARN ins Release-Log.
  Entscheidung in `DimDiagnostik.dimmenWirkungslos()`, bewusst nur bei aktivem, nicht pausiertem
  Fenster. Vorher stand der Dienst-Zustand NUR in einer DEBUG-Zeile, während die Benachrichtigung
  einen Verdunkelungswert behauptete — Hergang in `reference/dimmer.md`.
- **Und dieser Hinweis muss AN DIE STELLE FÜHREN, an der man ihn auflöst.** Ein Tipp darauf öffnet
  den Status-Tab, rollt die Bedienungshilfen-Karte ins Bild und zeigt deren Offenlegung; ihr Knopf
  führt in die Bedienungshilfen — auf den **hervorgehobenen** Eintrag dieses Dienstes, über
  `ACTION_ACCESSIBILITY_SETTINGS` plus `:settings:fragment_args_key`. **Die Offenlegung bleibt
  dazwischen** — der direkte Sprung aus der Benachrichtigung ginge an der Play-Pflicht vorbei, und
  genau deshalb hat der Weg zwei Stationen statt einer. Signalweg und seine vier Fallen
  (SINGLE_TOP, `setIntent()`, `savedInstanceState == null`, Zähler statt Boolean) in
  `reference/dimmer.md`.
- **`ACTION_ACCESSIBILITY_DETAILS_SETTINGS` ist für diese App unerreichbar — nicht „auf manchen
  Geräten", sondern immer.** Die Ziel-Activity ist seit Android 11 mit
  `OPEN_ACCESSIBILITY_DETAILS_SETTINGS` geschützt (`signature|installer`, `@hide`, AOSP: „Not for
  use by third-party applications"); an FP6 und Emulator gemessen, beide `Permission Denial`. Wer
  sie zurückholt, baut einen Zweig, der nur seinen eigenen Rückfall erreicht und dabei jedes Mal
  ein WARN ins Release-Log schreibt. Ein Regressionstest hält sie draußen; Hergang und der
  Prüfweg ohne Installation in `reference/dimmer.md`.
- **Das Verschwinden des Dienstes ist nicht protokollierbar — die RÜCKKEHR schon.** Bei einem
  `SIGKILL` (App-Update, Speicherdruck, Absturz) läuft weder `onUnbind` noch `onDestroy`. Deshalb
  setzt `onServiceConnected()` einen SharedPreferences-Merker (`commit()`, nicht `apply()`), den
  `onUnbind`/`onDestroy` wieder wegräumen; steht er beim nächsten Verbinden noch, gab es ein
  unerwartetes Ende → WARN. `DimDiagnostik.rueckkehrArt()` entscheidet. **Der Geräteneustart MUSS
  ausgenommen bleiben** (`elapsedRealtime()` fällt beim Booten auf null — die Wanduhr taugt dafür
  nicht), sonst steht nach jedem Boot ein falsches WARN im Release-Log.
- **`DimCorrectionNotifier.show()` prüft `areNotificationsEnabled()` vor `notify()`.**
- **DND: zwei Fenster-Trigger plus ein Klipp-Modifikator, kein Regel-Editor.**
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
  echter freier Tag — die FREI-Regel greift, und ein Fenster mit `ALARM_SONST_CLOCK`-Ende findet
  an ihm keine Weckzeit mehr, endet also an seiner Uhrzeit-Schranke. **Das ist die ausdrueckliche Nutzer-Entscheidung, kein Versehen**: wer frei
  hat, will den Abend eines freien Tages. Ein dritter Tageszustand „gar kein Dimmen" wurde bewusst
  verworfen — die Regelliste koennte ihn nicht anzeigen. Folge fuer die Nutzertexte: „Nicht
  stoeren bleibt aus" gilt NUR fuer die Dienstzeit; nachts kann Modus 1 weiterhin schalten, weil
  er dem Dimmer folgt. Hergang im Skill `cfalarm-wecker-und-boot` (dortige Hergang-Datei zu „Tag freigeben").
- **Die Dienstzeit-Fenster kommen aus `ShiftSpanStore`, NICHT aus dem Alarm-Bestand** — ein Alarm
  überlebt die Weckzeit nicht. Spannen werden in `syncAlarms()` **vor** dem Vergangenheits-Filter
  geschrieben, **auch in den beiden Leer-Zweigen**, und der Schreibvorgang ist nicht-fatal gekapselt.
  Eine Spanne kennt bewusst kein `isActive` und kein „übersprungen".
- **`ShiftSpan.alarmTriggerTime` ist NICHT redundant** — daraus leitet `DimWindowResolver` den
  Kalendertag ab. Ein Platzhalter datiert alle Dimm-Fenster auf 1970.
- **Rufbereitschaft-Cutoff klippt, statt eine eigene Fensterlogik/Policy zu duplizieren.** Zwei Fallen:
  der Cutoff-Tag muss auf den Folgetag rollen, und der Zeitpunkt wird als echte Wanduhrzeit aufgelöst.
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
