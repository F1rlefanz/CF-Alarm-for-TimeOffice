---
name: cfalarm-dimmer-und-dnd
description: Zusicherungen fuer den Schicht-Dimmer (systemweite Overlay-Verdunkelung ueber einen Accessibility-Dienst) und die DND-Steuerung ueber AutomaticZenRule in der CFAlarm-Wecker-App. Deckt Fensteraufloesung und Regel-Vorrang, den Nacht-Standard, den Korrektur-Override, die rollende Tick-Kette, die Dienstzeit-Fenster aus dem ShiftSpanStore und den Rufbereitschaft-Cutoff ab. Zu verwenden bei Arbeit an DimWindowResolver, DimScheduleUseCase, DimOverlayPrefs, DimAccessibilityService, DimNotificationService, DimmerViewModel, DimmerRulesViewModel, DndScheduleUseCase, DndPrefs oder DndOnCallCutoffResolver — und immer dann, wenn der Bildschirm zur falschen Zeit gedimmt bleibt, 'Nicht stoeren' waehrend der Dienstzeit abschaltet oder eine Dimm-Vorschau haengen bleibt.
---

# Schicht-Dimmer und DND-Steuerung

Unten stehen die **Kurzregeln** dieses Bereichs — was gilt, und was bei Bruch passiert.
Die wecker-kritische Teilmenge davon steht zusätzlich in `CLAUDE.md` (dort immer geladen, als
Sicherheitsnetz für den Fall, dass dieser Skill nicht anspringt); **alles Übrige steht
ausschließlich hier.** **Reicht die Kurzregel nicht, oder willst du eine davon ändern oder
umgehen: lies vorher die Hergang-Datei.** Dort steht, welcher Bug die Regel erzwungen hat — ohne
das baut man dieselbe Falle in neuer Form nach.

## Hergang und Belege

- `reference/dimmer.md` — Fensteraufloesung, Nacht-Standard, Vorschau-Scopes, Korrektur-Override
- `reference/dnd.md` — AutomaticZenRule, Policy, Dienstzeit-Fenster, Rufbereitschaft-Cutoff

---

## Kurzregeln

- **Ein Kalendertag kann ZWEI Schichten haben.** `buildRuleSpans` und `buildDefaultNightSpans`
  fragen JEDE Schicht des Tages (`slotsByDate`), nie nur die früheste. Wirksam wird trotzdem
  **pro Kalendertag GENAU eine Regel**; eine spezifische Regel **überschreibt** UNIVERSAL komplett,
  nicht additiv. UNIVERSAL heißt „alle **Tage**", nicht „alle Schichten".
- **Konflikt zweier VERSCHIEDENER spezifischer Regeln an einem Tag: es gewinnt die Regel der
  FRÜHESTEN Schicht** — plus WARN im Release-Log UND ein Hinweis an der verdrängten Regel in der
  Regelliste. Den Tag stattdessen auszulassen ist verboten (schaltet das Dimmen dort komplett ab,
  samt Nacht-Standard), die Fenster zu vereinigen ebenfalls (additiv).
- **Wirkung und Anzeige des Konflikts teilen EINE Funktion** (`regelFuerTag`, benutzt von
  `buildRuleSpans` und `findRuleConflicts`) — zwei Implementierungen würden auseinanderdriften.
- **Ein Ausschluss IRGENDEINER Schicht des Tages nimmt den GANZEN Tag aus dem Nacht-Standard**
  (`istTagAusgeschlossen`), und `nextDayCoversTonight` gilt nur für einen nicht ausgeschlossenen
  Folgetag mit Schicht.
- **`findRuleForShift` nimmt den ERSTEN Treffer** — zwei Regeln auf demselben Muster: die zweite ist tot.
- **Dimmer-Regeln binden über den NAMEN der Schichtdefinition** (`shiftPattern`), der frei änderbar
  ist — jedes Umbenennen zieht sie über `DimRuleUseCase.renameShiftPattern()` mit (Hergang im Skill
  `cfalarm-kalender-und-schichten`).
- **Leere Fensterliste = Unterdrückung dieser Nacht**, NICHT „keine Regel". Nicht wegoptimieren.
- **CLOCK↔CLOCK = lückenlos jede Kalendernacht**; ALARM/SHIFT_END brauchen eine **Schichtspanne**.
- **Zeitrechnung: echte Wanduhrzeit + Datums-Arithmetik**, niemals „Mitternacht-Instant + Minuten"
  und niemals fixe 24h-Millis (DST-Tage haben 23/25 h).
- **Die Fenster-Schleifen beginnen einen Kalendertag VOR `today`** (`LOOKBACK_DAYS`) — Achtung bei
  Tests, die Spannen absolut zählen.
- **Das Fenster-Ende ist HALB OFFEN (`first <= now < last`)** — sonst bleibt der Randzustand hängen.
- **Die Tick-Kette darf nicht abreißen**: Keep-alive-Tick (6 h), solange eine Quelle AN ist, plus
  kurzer Retry-Tick (15 min) nach einem Lesefehler der Fenster-Grundlage.
- **Nacht-Standard ist eine DRITTE, eigenständige Fenster-Quelle** mit eigener Verdunkelung/Wärme.
  **Pro Tag laufen ZWEI unabhängige Fenster-Prüfungen** (rückwärts + vorwärts), nicht eine exklusive.
- **Das Aufräumen der Dimm-VORSCHAU darf nicht am `viewModelScope` hängen** — je ein eigener
  `previewScope` mit `CoroutineExceptionHandler`, Reset im `finally` unter `NonCancellable`, und ein
  zweiter Tipp räumt die laufende Vorschau per `cancelAndJoin()` ZUERST auf. Diese Scopes werden
  bewusst **nicht** in `onCleared()` gecancelt — genau das wäre der Bug. Betrifft `previewDim()`
  **und** `previewRule()`.
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
- **`DimAccessibilityService.isRunning()` wird in `applyCurrentState()` mitgeloggt** — ohne diese
  Zeile ist ein ECM-/Binding-Vorfall nachträglich nicht mehr rekonstruierbar.
- **`DimCorrectionNotifier.show()` prüft `areNotificationsEnabled()` vor `notify()`.**
- **DND: zwei Fenster-Trigger plus ein Klipp-Modifikator, kein Regel-Editor.**
- **Jeder Setter, der DIMM-FENSTERGRENZEN verschiebt, armiert BEIDE Ketten neu — Dimmer, dann
  DND** (`armiereFensterkettenNeu()` in `DimmerViewModel` und `DimmerRulesViewModel`, unter
  `NonCancellable`). Reine DARSTELLUNGS-Setter (Verdunkelung/Wärme) rufen bewusst nur den Dimmer.
- **Modus 1 dupliziert KEINE Fenster-Logik** — er ruft `previewTimelineWithStatus()` direkt auf.
  Einbahnstraße: `dnd/` liest von `dimmer/`, nie umgekehrt. **Das `…WithStatus` ist kein Luxus**: der
  Lesefehler muss über die Grenze kommen, sonst plant DND den 6h-Keep-alive statt des 15-min-Retry.
- **Modus 2 braucht `AlarmInfo.shiftStartTime`**, nicht `triggerTime`.
- **Ein freigegebener Tag („Tag freigeben") verliert seine Schichtspannen an EINER Stelle:
  `FreieTageStore.filtereSpannen`, angewendet direkt nach `spansNow()` in BEIDEN
  `computeWindows()` (Dimmer und DND).** Danach sieht der Tag fuer die Fensterlogik aus wie ein
  echter freier Tag — FREI-Regel und Nacht-Standard greifen, `nextDayCoversTonight` rechnet mit
  ihm als freiem Tag. **Das ist die ausdrueckliche Nutzer-Entscheidung, kein Versehen**: wer frei
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
- **Logcat-Fallstrick**: `W/System.err` mit `Thread.dumpStack()` um `setAutomaticZenRuleState()` ist
  Androids eigenes Tracing, kein Crash.
