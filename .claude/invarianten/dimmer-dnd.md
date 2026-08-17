# Schicht-Dimmer & DND-Steuerung

> Ausgelagert aus `CLAUDE.md` (17.08.2026). Dort steht die Kurzregel, hier der Hergang:
> warum die Regel existiert, welcher Bug sie erzwungen hat, welche Messung sie belegt.
> **Vor Änderungen in diesem Bereich lesen.**

---

### Schicht-Dimmer (Regel-Auflösung)

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

### DND-Steuerung (Nicht stören)

- **Zwei Fenster-Trigger plus ein Klipp-Modifikator, kein Regel-Editor.** `dnd/DndScheduleUseCase`
  kennt zwei unabhängig schaltbare Fenster-Quellen (`DndPrefs.Toggles`): „Schlaf-Fenster folgt dem
  Dimmer" und „Während der Dienstzeit". Kein `DndRule`-Modell — ein früherer, adversarial geprüfter
  Entwurf mit vollem Regel-Editor wurde zugunsten dieser einfacheren, tatsächlich angefragten Lösung
  verworfen. Der On-Call-Cutoff (siehe eigener Punkt unten) ist bewusst KEINE dritte Fenster-Quelle,
  sondern ein Klipp-Schritt, der auf das Ergebnis der beiden Quellen angewendet wird.
- **Rufbereitschaft-Cutoff (`DndOnCallCutoffResolver`, seit v1.20.0) klippt statt eine eigene
  Fensterlogik/Policy zu duplizieren.** Der Nutzer markiert bestimmte Schichten (`DndPrefs.
  onCallShifts`, z. B. „AD1") als On-Call; an einem so erkannten Tag wird JEDES aus den beiden
  bestehenden Quellen berechnete Fenster auf eine feste, konfigurierbare Uhrzeit
  (`DndPrefs.onCallCutoffMinutes`, Default 05:00) gekappt — unabhängig davon, welche Quelle das
  Fenster erzeugt hat. **Keine separate Policy für On-Call-Nächte:** dieselbe `AutomaticZenRule`
  gilt bis zum Cutoff unverändert (z. B. bleiben Anrufe geblockt, falls das die normale Policy so
  vorsieht — bewusste Nutzer-Entscheidung, kein Versehen). Zwei beim ersten Bau selbst adversarial
  gefundene und gefixte Fallen: (1) Der Cutoff-Tag darf NICHT unconditional der Kalendertag von
  `shiftStartTime` sein — bei abends beginnenden On-Call-Schichten (z. B. 21:00) läge die
  Cutoff-Uhrzeit (z. B. 05:00) sonst VOR Schichtbeginn und klippt die falsche, unbeteiligte Vornacht
  statt der eigentlichen On-Call-Nacht; der Tag muss auf den Folgetag rollen, sobald die Schicht
  ab/nach der Cutoff-Uhrzeit desselben Tages beginnt. (2) Der Cutoff-Zeitpunkt muss über
  `LocalTime.atZone()` als echte Wanduhrzeit aufgelöst werden, NICHT als Mitternacht-Instant plus
  fixer Minuten-Millis-Offset — sonst landet er an einem DST-Vorspringen-Tag eine Stunde zu spät.
  `DndOnCallCutoffResolverTest` hält beide Fälle fest.
- **Modus 1 dupliziert KEINE Fenster-Logik.** Er ruft `DimScheduleUseCase.previewTimelineWithStatus()`
  direkt auf (seiteneffektfrei) statt eine eigene Kopie der Dimmer-Fensterberechnung zu pflegen.
  Einbahnstraße wie `CalendarStateHolder`: `dnd/` liest von `dimmer/`, nie umgekehrt — der Dimmer
  bleibt unverändert und unwissend von DND. Wer hier eine eigene, „ähnliche" Fensterberechnung für DND
  einbaut, öffnet genau das Drift-Risiko (zwei Quellen der Wahrheit für „ist gerade Nacht"), vor dem
  die adversariale Kritikrunde gewarnt hat.
- **Das `…WithStatus` ist kein Luxus: der Lesefehler muss über die Dimmer-DND-Grenze kommen.** Nach
  einem transienten Lesefehler des Alarm-Bestands blieb DND-Modus 1 bis zu 6 h ohne „Nicht stören",
  obwohl der Dimmer sich planmäßig nach 15 min erholte und die Nacht dimmte: der Fehler passiert
  INNERHALB von `DimScheduleUseCase.computeWindows()` und kam bei DND als ununterscheidbar leere
  Fensterliste an, sein eigenes `alarmReadFailed` blieb `false` (der eigene Spannen-Zweig — bis
  v1.25.1 `getAllAlarms()`, seither `shiftSpanStore.spansNow()` — wird bei nur-Modus-1 nie
  betreten) und `fallbackTick()` plante den 6-Stunden-Keep-alive statt des 15-Minuten-Retry. Wer
  den Status wieder wegoptimiert, holt genau das zurück.
- **Modus 2 braucht `AlarmInfo.shiftStartTime`**, nicht `triggerTime` (Weckzeit, meist vor
  Schichtbeginn wegen Anfahrt) und nicht nur `shiftEndTime`. Gesetzt in
  `AlarmUseCase.createAlarmFromShiftMatch` aus `shiftMatch.calendarEvent.startTime` — exakt
  daneben, wo `shiftEndTime` aus `calendarEvent.endTime` gesetzt wird.
- **Ein Alarm ist ein Weckzeitpunkt, eine `ShiftSpan` ist ein DIENST — die Dienstzeit-Fenster
  kommen seit v1.25.2 aus `ShiftSpanStore`, NICHT mehr aus dem Alarm-Bestand.** Der überlebt die
  Weckzeit nicht, und das ist richtig so: `AlarmRepository` verwirft abgelaufene Alarme in BEIDEN
  Ladepfaden und lehnt das Speichern eines vergangenen Alarms ab — ein abgelaufener Alarm wäre
  genau die verwaiste, armierte Leiche, gegen die die übrigen Zusicherungen geschrieben sind. Bis
  v1.25.1 hing `DndShiftSpanResolver` aber genau daran: der erste `syncAlarms()` nach dem
  Klingeln räumte den Alarm, und mit ihm das Fenster der Schicht, die GERADE LÄUFT. Am Emulator
  gemessen (14.08.2026): 20.08. 08:00, mitten in der Frühschicht (Termin 06:00–14:12, Alarm
  05:30 bereits gefeuert) → `zen_mode=0`, Regel `STATE_FALSE`; nach dem Fix `zen_mode=1`,
  `STATE_TRUE`. Drei Dinge gehören zusammen: die Spannen werden in `syncAlarms()` **vor** dem
  Vergangenheits-Filter geschrieben (genau die Schichten, die der Alarm-Bestand nicht mehr
  hergibt), **auch in den beiden Leer-Zweigen** („keine Events" / „keine passende Schicht" —
  ohne das hält eine alte Spanne DND dauerhaft an, während die App „kein Dienst" anzeigt), und
  der Schreibvorgang ist **nicht-fatal gekapselt** (ein Nebenschauplatz darf den Alarm-Sync nie
  abbrechen). Eine Spanne kennt bewusst **kein `isActive` und kein „übersprungen"**: ein
  deaktivierter oder übersprungener Wecker ändert nichts daran, dass der Dienst stattfindet.
- **`ShiftSpan.alarmTriggerTime` ist NICHT redundant.** `DimWindowResolver` leitet den
  **Kalendertag** eines Slots aus der Weckzeit ab (`buildRuleSpans`/`buildDefaultNightSpans`,
  `Instant.ofEpochMilli(a.triggerTime)`). Wer die Spanne ohne diesen Wert baut und einen
  Platzhalter einsetzt, datiert den Slot auf 1970 und zerstört die Tagesverankerung ALLER
  Dimm-Fenster — dieselbe Fehlerklasse, die schon einmal falsche Dimm-Nächte erzeugt hat. Der
  Dimmer zieht deshalb Regel- und Nacht-Standard-Slots aus den Spannen, die **Wellness**-Quelle
  aber weiterhin aus dem echten Alarm-Bestand: sie dimmt VOR der Weckzeit, ihr Fenster ist nach
  dem Klingeln ohnehin vorbei.
- **Verstrichene Weckzeit ist KEINE entfernte Schicht.** Der Löschzweig des Delta-Syncs meldete
  jeden Schichtmorgen „Schicht entfernt" für den Dienst, den der Nutzer gerade antrat — beide
  Fälle landen im selben Zweig (`!newAlarmsMap.containsKey(eventId)`), aber nur einer ist eine
  Änderung des Dienstplans. `expiredEventIds` trennt sie: der Alarm wird weiterhin gecancelt und
  gelöscht (in dieser Reihenfolge), nur `notifyDeleted()` unterbleibt und das Log sagt „Weckzeit
  verstrichen, Termin läuft weiter". `AlarmUseCaseDeltaSyncTest` hält BEIDE Richtungen fest —
  der Regressionswächter für die echte Löschmeldung ist der wichtigere Teil.
- **`AutomaticZenRule`, nicht rohes `NotificationManager.setInterruptionFilter()`.** Der
  rohe Filter überschreibt kommentarlos das manuelle DND des Nutzers und jede fremde
  Automatisierung (Bixby/Tasker/System-Zeitplan) — kein Owner-Konzept, letzter Schreiber gewinnt.
  Die selbst registrierte Zen-Regel (eigene, rule-scoped `ZenPolicy`) erscheint stattdessen
  sichtbar unter Einstellungen → Ton → Nicht stören → Zeitpläne und koexistiert sauber.
- **Nur ab API 30 (Android 11).** Der 7-arg-`AutomaticZenRule`-Konstruktor mit
  `configurationActivity`-Ownership (kein `ConditionProviderService` nötig) existiert erst ab
  API 30; darunter bietet `DndPermissionHelper.isFeatureSupported()`/`DndScheduleUseCase.isSupported()`
  das Feature bewusst gar nicht an, statt einen zweiten Ownership-Pfad zu pflegen.
- **Policy ist vollständig Nutzer-konfigurierbar (`DndPrefs.Policy`), NICHTS hart codiert.**
  `buildAutomaticZenRule()` liest `prefs.policyNow()` und baut die `ZenPolicy` daraus — keine
  Kategorie ist im Code fest verdrahtet. **Vorfall, der zu dieser Entscheidung führte (28.07.2026):**
  ein erster Entwurf setzte `allowMedia(false)` und `allowAlarms(false)` hart, "um konsequent zu
  sein" — das schaltete live einen laufenden Podcast stumm und ließ sich vom Nutzer nicht mal mehr
  manuell zurückregeln (`allowMedia` wirkt auf die Medien-Audiospur, nicht nur auf Töne). Seither:
  Defaults `blockCalls`/`blockMessages`/`blockConversations`/`blockReminders`/`blockEvents` = `true`
  (das ist der eigentliche Zweck von „Nicht stören"), `blockSystem`/`blockMedia`/`blockAlarms` =
  `false` (unberührt, bis der Nutzer es explizit anschaltet). `allowRepeatCallers` bleibt eigene
  Nutzer-Option (Default an) — wiederholte Anrufer (Notfall) kommen durch, wenn `blockCalls` aktiv
  ist. Der Wecker selbst ist von `blockAlarms` unabhängig: `AlarmSoundService.setBypassDnd(true)`
  umgeht JEDE DND-Konfiguration, auch die eigene — `blockAlarms` betrifft nur FREMDE Wecker-Apps.
- **`ensureZenRule()` aktualisiert eine bereits registrierte Regel bei JEDEM Tick mit
  `updateAutomaticZenRule()`**, nicht nur bei der Erstregistrierung. Sonst wirkt eine
  Policy-Änderung des Nutzers erst nach einer Neuinstallation, weil die einmal registrierte Regel
  ihre alte `ZenPolicy` sonst dauerhaft behält — exakt der Fehler, der beim ersten Bau übersehen
  wurde (siehe Vorfall oben: die Regel hätte den Fix sonst erst nach Deinstallation bekommen).
- **Mehrere gleichzeitig aktive Zen-Regeln kombinieren sich vermutlich „freizügigste gewinnt" pro
  Kategorie** — beobachtet am 28.07.2026: während unsere Regel UND ein bereits vorhandener,
  fremder System-/Hersteller-Modus ("Schlafenszeit", 22–6 Uhr) gleichzeitig aktiv waren, blieben
  Anrufe/Klingelton hörbar (der andere Modus erlaubte sie, das gewann), aber Medien wurden stumm
  (nur unsere Regel hatte dazu überhaupt eine Meinung). Nicht durch eigenen Code behebbar — unsere
  Regel kann eine Kategorie nicht zuverlässiger blockieren, als es die am wenigsten strenge
  gleichzeitig aktive fremde Regel erlaubt. **"Koppeln mit Schlafenszeit" bewusst NICHT gebaut
  (29.07.2026, AOSP-Quellcode-verifiziert, geräteunabhängig):** Apps können laut Android-API nur
  EIGENE `AutomaticZenRule`s lesen/steuern (`getAutomaticZenRules()` ist auf das aufrufende Package
  beschränkt) — ein fremder/System-Modus kann weder ausgelesen noch direkt geschaltet werden. Auch
  ein systemweiter Theme-Wechsel (das eigentlich interessante an Schlafenszeit, da Dimmen bereits
  über den Schicht-Dimmer gelöst ist) ist unerreichbar: `UiModeManager.setNightMode()` verlangt
  `android.permission.MODIFY_DAY_NIGHT_MODE` (`protectionLevel="signature|privileged|role"`, dazu
  `@hide` — nicht mal Teil des öffentlichen SDK, siehe `core/res/AndroidManifest.xml` im
  AOSP-Quellcode). `setApplicationNightMode()` (die einzige App-erreichbare Variante) wirkt
  nachweislich nur auf die eigene App. Nicht erneut aufrollen ohne neuen Anlass.
- **Eigener Request-Code `REQ_DND_TICK = 7712`**, eigene rollierende Exact-Alarm-Kette
  (`DndScheduleReceiver`) — bewusst NICHT mit dem Dimmer-Tick (`REQ_TICK = 7710`,
  `DimScheduleUseCase`) oder der 6h-Wartung (Code 0) zusammengelegt. Zwei fachlich unabhängige
  Features, unabhängig deaktivierbar; ein Bug in einem darf nicht das andere mitreißen.
- **`DndScheduleUseCase.CONDITION_ID` ist `by lazy`.** Eager ausgewertet scheiterte `Uri.parse()` im
  Unit-Test-JVM bereits bei der Companion-Initialisierung und riss über die dauerhaft gescheiterte
  Klassen-Initialisierung auch fremde Tests mit, die die Klasse nur mocken wollten. Produktionsverhalten
  unverändert.
- **`ensureZenRule()` prüft `Build.VERSION.SDK_INT` direkt**, nicht nur über `isSupported()` –
  Lint verfolgt die Absicherung für `@RequiresApi`-Aufrufe (`buildAutomaticZenRule()`) nur bei
  einem lokalen, direkten SDK_INT-Vergleich zuverlässig durch mehrere Funktionsebenen.
- **Am Fairphone 6 (Android 16) verifiziert (28.07.2026):** Die Zen-Regel registriert sich echt,
  erscheint unter Einstellungen → Ton → Nicht stören → Zeitpläne mit funktionierendem
  `configurationActivity`-Link, `setAutomaticZenRuleState()` wird korrekt aufgerufen und der
  Zustand korrekt berechnet (`ZEN_MODE change value` je nach aktivem Fenster). **Zweiter Lauf
  (29.07.2026) nach dem Policy-Fix:** `adb shell dumpsys notification --noredact` zeigt die
  tatsächlich registrierte Regel mit `alarms=allow, media=allow, calls=disallow, messages=disallow,
  repeatCallers=allow` — exakt die neuen Defaults, `updateAutomaticZenRule()` nachweislich beim
  Tick aufgerufen. **Dritter Lauf (14.08.2026, Emulator): beide zuvor offenen Punkte belegt.**
  „Während der Dienstzeit" mit echten Kalenderzeiten: am AD1-Tag um 04:00 `zen_mode=1`/
  `STATE_TRUE`, um 05:30 `zen_mode=0`/`STATE_FALSE` — der Rufbereitschaft-Cutoff kappt, obwohl
  der Termin bis 24:00 läuft und der Alarm noch steht. Und die Anrufer-Ausnahme
  (`allowRepeatCallers`) per simuliertem Anruf (`adb emu gsm call`): erster Anruf
  `SKIP_RINGING (Inaudible: isVolumeOverZero=true, shouldRingForContact=false)` im
  Telecom-Log — also von UNSERER Regel unterdrückt, nicht von der Lautstärke —, der
  Wiederholungsanruf derselben Nummer 34 s später `START_RINGER` + `START_VIBRATOR`.
  Einschränkung: simulierte Telefonie, aber die Entscheidung fällt in Androids
  `matchesCallFilter` gegen die registrierte Regel, also im identischen Codepfad.
- **Logcat-Fallstrick beim Debuggen, kein Bug:** `W/System.err` mit `java.lang.Exception: Stack
  trace` + `Thread.dumpStack()` rund um `setAutomaticZenRuleState()` ist Androids eigenes internes
  Aufruf-Tracing für Zen-Änderungen — sieht wie ein Crash aus, ist keiner. Der direkt folgende
  `V/Settings: ZEN_MODE change value to X` sowie der eigene Erfolgs-Log bestätigen den echten
  Aufrufausgang.

