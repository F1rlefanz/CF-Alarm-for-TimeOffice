# Schicht-Dimmer (Regel-Aufloesung) — Hergang

> Hergang zu den Kurzregeln in `CLAUDE.md` und in der `SKILL.md` daneben: welcher Bug die
> Regel erzwungen hat, welche Messung sie belegt, welche Alternative verworfen wurde.
> Jede Zeile hier hat einmal echten Schaden verhindert — im Zweifel gilt sie, nicht die Intuition.

## Inhalt

- Das Aufräumen der Dimm-VORSCHAU darf nicht am `viewModelScope` hängen
- Pro Kalendertag GENAU eine Regel
- `findRuleForShift` nimmt den ERSTEN Treffer
- Leere Fensterliste = Unterdrückung dieser Nacht
- CLOCK↔CLOCK = lückenlos jede Kalendernacht
- Zeitrechnung: echte Wanduhrzeit + Datums-Arithmetik, niemals „Mitternacht-Instant + Minuten" und
- Die Fenster-Schleifen beginnen einen Kalendertag VOR `today` (`LOOKBACK_DAYS`)
- Das Fenster-Ende ist HALB OFFEN (`first <= now < last`)
- Die Tick-Kette darf nicht abreißen
- Nacht-Standard (`DimWindowResolver.buildDefaultNightSpans`, seit v1.17.0) ist eine DRITTE,
- Dimmer-Korrektur-Override (Feature C, seit v1.20.0) lebt im DataStore, nicht in-memory
- `DimNotificationService` klemmt den `strengthDelta` selbst, nicht nur den abgeleiteten
- Jeder Setter, der einen `DimOverlayPrefs`-Wert schreibt, MUSS direkt danach
- `DimAccessibilityService.isRunning()` (der einzige echte Bound-Status) wird seit v1.22.1 in
- `DimCorrectionNotifier.show()` prüft `NotificationManagerCompat.areNotificationsEnabled()`

---

- **Das Aufräumen der Dimm-VORSCHAU darf nicht am `viewModelScope` hängen** (v1.24.0, an ZWEI
  Stellen: `DimmerViewModel.previewDim()` und `DimmerRulesViewModel.previewRule()` — „Regel
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

- **Pro Kalendertag GENAU eine Regel** (`DimWindowResolver.buildRuleSpans`): Schicht-Tag →
  `findRuleForShift` (exakter Schichtname → sonst UNIVERSAL), freier Tag → `findRuleForFreeDay`
  (FREI → sonst UNIVERSAL). Eine spezifische Regel **überschreibt** UNIVERSAL für dieses Datum
  komplett — nicht additiv. Deshalb ist UNIVERSAL „alle **Tage**" (Schicht + frei + Urlaub), nicht
  „alle Schichten" — das UI-Label heißt entsprechend „Alle Tage (Universal)".
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
  Keep-alive-Tick (6 h), solange überhaupt eine Quelle AN ist, plus ein kurzer Retry-Tick (15 min)
  nach einem **Lesefehler der Fenster-Grundlage** (Alarm-Bestand für Wellness, Schichtspannen für
  Regeln/Nacht-Standard und für den gesamten DND-Pfad). Die BEDEUTUNG einer leeren Fensterliste
  (Nachtdienst-Unterdrückung) bleibt unverändert — es wird nur später noch einmal nachgesehen.
- **Nacht-Standard (`DimWindowResolver.buildDefaultNightSpans`, seit v1.17.0) ist eine DRITTE,
  eigenständige Fenster-Quelle** neben Regeln — dimmt ab fester Uhrzeit bis zum nächsten Wecker,
  ganz ohne dass dafür eine `DimRule` existieren muss. Wirkt NUR an Tagen, die `isExcluded` nicht
  ausschließt — dieses Prädikat bündelt zwei unabhängige Wege: eine explizit vom Nutzer markierte
  Schicht (`DimOverlayPrefs.nightDefaultExcludedShifts`, Toggle direkt an der Karte) ODER eine
  vorhandene `DimRule`, die den Tag ohnehin schon abdeckt (dieselbe Ausschließlichkeit wie oben).
  **Pro Tag laufen ZWEI unabhängige Fenster-Prüfungen, nicht eine exklusive** (Fix v1.21.1): ein
  Rückwärts-Fenster (nur falls der Tag selbst einen Wecker hat — die Nacht VOR diesem Wecker,
  endend am Wecker) UND ein Vorwärts-Fenster (immer, AUSSER der FOLGETAG hat selbst einen Wecker —
  dann deckt dessen eigenes Rückwärts-Fenster den heutigen Abend automatisch ab). Bis v1.21.0
  waren beide exklusiv an „Tag hat keinen Wecker" gebunden — das ließ die Nacht NACH einem
  Wecker, der nicht der frühe Morgen ist (z. B. eine Nachmittagsschicht mit Wecker 14:30),
  komplett durchfallen, sobald der Folgetag ebenfalls keinen Wecker hatte: der Skip für den
  Folgetag nahm fälschlich an, ein Wecker am ÜBERnächsten Tag decke die Nacht schon ab — dessen
  Rückwärts-Fenster reicht aber nur exakt einen Tag zurück. Real reproduziert am 03./04./05.08.2026
  (S2-Wecker 14:30 → Tag ganz ohne Kalendertermin → Frühschicht 05:30): die Nacht vom 3. auf den
  4.8. blieb dadurch komplett ungedimmt UND ohne DND (DND-Modus 1 nutzt dieselbe Fensterberechnung
  über `previewTimeline()`). `DimWindowResolverTest` hält sowohl das alte Kern-Szenario als auch
  diesen Regressionsfall fest. **Eigene Verdunkelung/Wärme** (`nightDefaultStrength`/
  `nightDefaultWarmth`, seit v1.17.1) — NICHT die globalen Wellness-Werte mitverwenden, das war
  der erste Wurf und wurde vom Nutzer explizit zurückgewiesen.
- **Dimmer-Korrektur-Override (Feature C, seit v1.20.0) lebt im DataStore, nicht in-memory** —
  `DimAccessibilityService`/`DimScheduleReceiver` haben keine garantierte Lebensdauer, ein
  In-Memory-State ginge bei Prozess-Neustart verloren. `DimOverlayPrefs.Override` speichert
  `strengthDelta`/`paused` PLUS `windowEnd` UND `windowStrength` (nicht nur `windowEnd`!) als
  Fenster-Identität. **Reine `windowEnd`-Identität reicht nicht:** `DimScheduleUseCase.windows()`
  liefert drei unabhängige, überlappende Quellen (Wellness/Regeln/Nacht-Standard), die sehr häufig
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
  („die Regler feuern pro Frame") — durch den UI-Umbau auf `CommitOnReleaseSlider`
  (`onValueChangeFinished`) kommt pro Bewegung genau EIN Setter-Aufruf an: Nutzen null, Risiko real.
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
- **`DimCorrectionNotifier.show()` prüft `NotificationManagerCompat.areNotificationsEnabled()`
  vor `notify()`** (Fix v1.22.1) — ohne POST_NOTIFICATIONS-Berechtigung verschluckt `notify()` sonst
  lautlos, ohne Log oder Exception, und sieht im Logcat identisch aus wie der obige Toggle-Bug.

