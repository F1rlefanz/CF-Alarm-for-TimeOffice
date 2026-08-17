# Kalender-Datenfluss und Schicht-Aenderungen — Hergang

> Hergang zu den Kurzregeln in `CLAUDE.md` und in der `SKILL.md` daneben: welcher Bug die
> Regel erzwungen hat, welche Messung sie belegt, welche Alternative verworfen wurde.
> Jede Zeile hier hat einmal echten Schaden verhindert — im Zweifel gilt sie, nicht die Intuition.

## Inhalt

- `CalendarStateHolder` ist eine Einbahnstraße
- Laden gehört ausschließlich dem `CalendarViewModel`
- Endlosschleifen-Bremse im Kalender-`LaunchedEffect` von `MainScreen`
- Der Collector der Kalenderauswahl nimmt sich wieder auf (`retryWhen`)
- Eine unvollständige Eventliste ist KEINE Löschgrundlage — und „unvollständig" hat zwei
- Kein Fehler darf als leeres Erfolgsergebnis durchrutschen
- Ganztägige Termine gehen durch `CalendarEventConverter`
- Nachgeladen wird immer ein PRÄFIX, nie eine Seite ab Offset
- `loadEventsForSelectedCalendars()` braucht einen Generation-Counter, kein einfaches
- Neue Properties in `CalendarViewModel` (und jedem anderen ViewModel mit `init{}`-Block)
- Kein echtes Push möglich, bewusst nicht versucht
- Die Notification-Entscheidung lebt INNERHALB von `AlarmUseCase.syncAlarms()`, nicht bei dessen
- Der allererste Sync (z. B. nach Neuinstallation) flutet nicht

---

- **`CalendarStateHolder` ist eine Einbahnstraße**: `CalendarViewModel` schreibt hinein, liest nie
  daraus; einziger Leser ist `ShiftViewModel`. Wer Events lädt und nur dorthin schreibt,
  aktualisiert die `CalendarUiState` nicht — und die rendert Home.
- **Laden gehört ausschließlich dem `CalendarViewModel`** (`refreshData(forceRefresh = true)`
  aktualisiert beides und trägt Fehler in den State). Keinen zweiten Ladepfad einbauen — genau der
  hat den stummen Retry erzeugt.
- **Endlosschleifen-Bremse im Kalender-`LaunchedEffect` von `MainScreen`** (Bedingung
  `availableCalendars.isEmpty() && error == null`): automatisches Nachladen nur ohne Fehler. Sonst:
  Laden scheitert → `isLoading` false → Effect erneut → Liste leer → laden … im Sekundentakt gegen
  die Google-API (real passiert bei 401). Nicht entfernen.
- **Der Collector der Kalenderauswahl nimmt sich wieder auf (`retryWhen`).** Das `collect` lag in
  einem `try/catch`: der ERSTE Upstream-Fehler beendete es für die gesamte Prozesslaufzeit, und es
  gibt keinen zweiten Aufrufer von `initializeFromDataStore()`. Danach stand
  `_selectedCalendarIds` dauerhaft auf `emptySet()`, obwohl Kalender ausgewählt sind — für eine
  Wecker-App genau die gefährliche Leere, die diese Klasse an anderer Stelle bekämpft. Im
  Direct-Boot-Prozess (der `BootReceiver` injiziert dieses Repository) ist der Fehler garantiert:
  CE-Store nicht lesbar. Nach dem Entsperren wäre er lesbar, deshalb 10 Versuche mit wachsendem
  Abstand statt endgültigem Aus.
- **Eine unvollständige Eventliste ist KEINE Löschgrundlage — und „unvollständig" hat zwei
  Quellen.** `syncAlarms()` entfernt im Delta-Sync jeden bestehenden Alarm, dessen `eventId` in der
  übergebenen Liste fehlt; „Termin gelöscht" und „Termin fehlt in diesem Ausschnitt" sind auf der
  reinen Liste **nicht unterscheidbar**. Die zwei Quellen:
  1. **Teilerfolg**: `getCalendarEventsWithCache()` liefert bei einem Ausfall EINZELNER Kalender
     bewusst `Result.success` mit den Events der überlebenden. Für die Anzeige richtig — als
     Löschgrundlage tödlich: fällt der Dienstplan-Feed aus, während der private Kalender antwortet,
     findet KEIN Schicht-Alarm mehr seinen Treffer.
  2. **Lazy-Präfix**: der Vordergrund-Ladevorgang holt pro Kalender nur die ersten **10** Events
     (`observeCalendarSelection` → `initialPageSize = 10`), während `totalEvents` den vollen
     14-Tage-Bestand zählt. Bei mehr als zehn Schichten in 14 Tagen — für einen Schichtplan der
     Normalfall — löschte jedes App-Öffnen die spätesten Wecker samt „Schicht entfällt"-Meldung.

  Deshalb: **jeder löschende Konsument geht über `getCalendarEventsWithStatus()` und prüft
  `CalendarFetchOutcome.isComplete`** (Vorbild `previewTimelineWithStatus()` — die Unvollständigkeit
  muss über die Grenze kommen, sonst ist sie beim Leser eine ununterscheidbar kurze Liste).
  Betroffen und umgestellt: `BootReceiver` (Validierung nur bei vollständigem Abruf),
  `AlarmMaintenanceService`, `CalendarPreAlarmRefreshWorker` (der gefährlichste — er läuft **3 h vor
  der Weckzeit**) und `CalendarViewModel` (fordert bei gekürzter Anzeige die vollständige Liste
  nach, `isEventListCompleteForAlarmSync` als reine Funktion daneben).
  **Auch der `CalendarStateHolder` trägt die Vollständigkeit mit** (`eventsComplete`): sein Leser
  `ShiftViewModel.triggerAlarmCreationFromConfigUpdate()` gibt die Liste an `syncAlarms()` weiter,
  bekam aber genau das Lazy-Präfix. Das Flag steht bewusst an der GRENZE statt als Prüfung im
  einzelnen Leser — ein künftiger dritter Leser erbt die Falle sonst erneut (gleiche Überlegung wie
  beim zentralen Master-Pause-Backstop). `clearEvents()` setzt es auf `false`: leer UND „vollständig"
  wäre die gefährlichste Kombination. **Wer einen neuen `syncAlarms()`-Aufrufer ergänzt, muss diese
  Frage beantworten** — die Zwillinge sind hier zweimal übersehen worden, beide Male erst beim
  eigenen Nachlesen gefunden, nicht durch eine Prüfrunde.
  **Drei Aufrufer bleiben absichtlich auf `getCalendarEventsWithCache()`, weil sie NICHTS löschen**
  (nachgesehen, nicht vermutet — bitte nicht erneut als Befund melden): der `loadAll`-Zweig von
  `CalendarViewModel.loadEventsForSelectedCalendars()` und `startBackgroundSync()` (beide reine
  Anzeige bzw. Cache-Wärmung) sowie der Verbindungstest in `BootReceiver.performHealthDiagnostics()`,
  der ausschließlich `isSuccess` auswertet.
- **Kein Fehler darf als leeres Erfolgsergebnis durchrutschen** — für eine Wecker-App ist „leer" die
  gefährlichste Lüge, und `syncAlarms()` deutet eine leere Eventliste als „keine Schichten" und
  löscht ALLE Alarme (System, Repository, Direct-Boot-Spiegel). Vier Stellen sind deshalb festgelegt:
  `CalendarUseCase.getCalendarEventsWithCache()` wirft bei **Totalausfall** aller angefragten
  Kalender den ersten Fehler (Teilerfolg bleibt Erfolg — gleiche Abgrenzung wie
  `CalendarViewModel.resolveCalendarAuthorizationOutcome()`; für löschende Konsumenten reicht das
  aber NICHT, siehe den Punkt darüber); `CalendarPreAlarmRefreshWorker` und
  `AlarmMaintenanceService` haben zusätzlich je ein eigenes Leerlisten-Gate (zweite
  Verteidigungslinie, weil jeder künftige Aufrufer dieselbe Falle erbt);
  `CalendarSelectionRepository.getCurrentSelectedCalendarIds()` liest den **DataStore**, nicht den im
  prozess-kalten Start noch nicht hydrierten `StateFlow` (Startwert `emptySet` hieß „keine Kalender
  ausgewählt" und verbrauchte den Worker-Job endgültig). Der `StateFlow` bleibt Quelle für reaktive
  Beobachter.
- **Ganztägige Termine gehen durch `CalendarEventConverter`** (rein, testbar) und setzen
  `CalendarEvent.isAllDay`. Der `value` eines `date`-Feldes ist UTC-Mitternacht, in Europe/Berlin also
  01:00/02:00 lokal — vorher stand „Deine Schicht beginnt um 02:00" in Notification/Vollbild, das
  DND-Dienstzeit-Fenster begann um 02:00, und wegen des end-exklusiven `end.date` war das Event ~24 h
  zu lang. Der Konverter leitet den Kalendertag zonenunabhängig aus dem UTC-Wert ab und macht daraus
  lokale Tagesgrenzen (00:00 bis 23:59 des LETZTEN Tages). **`calculateAlarmTime()` überspringt die
  Nachtschicht-Vortags-Heuristik bei `isAllDay`**: ein ganztägiger Eintrag hat keinen Schichtbeginn,
  gegen den „danach" prüfbar wäre. Ohne diesen Zusatz weckte die Standard-Spätschicht (12:30) einen
  ganzen Tag zu früh — und am Schichttag gar nicht.
- **Nachgeladen wird immer ein PRÄFIX, nie eine Seite ab Offset.** Der Erst-Ladevorgang holt PRO
  Kalender die ersten 10 Events; `getCalendarEventsLazy(alle Kalender)` schneidet dagegen aus der
  sortierten **Vereinigung**. Bei mehr als einem Kalender ist „je Kalender die ersten 10" kein Präfix
  dieser Vereinigung: die Nachlade-Seite lieferte bereits angezeigte Events erneut, während ein Block
  dazwischen fehlte. Real: dieselbe Event-Id zweimal in einer `LazyColumn` mit `key = { event.id }` →
  `IllegalArgumentException "Key … was already used"` → Absturz beim Scrollen; ohne Absturz doppelte
  Schichten auf Home. Deshalb `offset = 0, maxEvents = bereits angezeigt + limit` — das kostet nichts,
  weil `getCalendarEventsLazy()` intern ohnehin alles holt und erst danach schneidet.
  `mergeMoreEvents()` dedupliziert und sortiert zusätzlich defensiv (bei gleicher Id gewinnt die
  frische Fassung, sonst bliebe eine verschobene Schicht auf der alten Uhrzeit stehen).
  `loadMoreEvents()` **liest** die `eventLoadGeneration` nur, zieht keine eigene Nummer (Nachladen ist
  ein Anhänger, kein neuer Ladevorgang — sonst würgt es ein laufendes
  `loadEventsForSelectedCalendars()` als „überholt" ab) und setzt `isLoadingMoreEvents` auch beim
  Verwerfen zurück.
- **`loadEventsForSelectedCalendars()` braucht einen Generation-Counter, kein einfaches
  In-Flight-Flag** (anders als `loadAvailableCalendars()` direkt daneben). Zwei legitime Trigger
  (`observeCalendarSelection()`s Collector UND `refreshData(forceRefresh=true)`, z. B. der
  „Aktualisieren"-Button) dürfen beide feuern — ein Boolean-Gate würde den zweiten schlucken statt
  seine (eigentlich aktuelleren) Ergebnisse durchzulassen. `eventLoadGeneration` (`AtomicLong`)
  zieht jeder Aufruf beim Start eine Nummer; VOR JEDEM Schreiben in `_localUiState`/
  `CalendarStateHolder` — inklusive des allerersten `isLoading = true`-Writes und des
  Lazy-Loading-Resets, nicht nur der späteren Zwischen-/Endergebnisse — prüft er, ob er noch
  aktuell ist. Ein erster Fix-Versuch prüfte nur die späteren Writes und ließ einen bereits
  überholten Aufruf trotzdem `isLoading = true` setzen und Events leeren, bevor er sich selbst
  erst am Ende als überholt erkannte — das ließ die UI mit hängendem Spinner und leerer Liste
  zurück, schlimmer als der ursprüngliche Bug.
- **Neue Properties in `CalendarViewModel` (und jedem anderen ViewModel mit `init{}`-Block)
  gehören VOR den `init{}`-Block, nicht danach.** Kotlin initialisiert Property-Initializer und
  `init{}`-Blöcke strikt in Textreihenfolge. `viewModelScope.launch{}` läuft auf
  `Dispatchers.Main.immediate` — bereits auf dem Hauptthread synchron bis zum ersten echten
  Suspend-Punkt. Da `observeCalendarSelection()`s Quelle ein `StateFlow` mit sofort verfügbarem
  Wert ist, feuert `.collect{}` beim allerersten Sammeln SOFORT, noch während der eigenen
  Objekt-Konstruktion. Stand `eventLoadGeneration` textuell nach `init{}`, griff der Code beim
  allerersten App-Start auf `null` zu — `NullPointerException`, real am Fairphone reproduziert
  (05.08.2026), von keinem der 329 Unit-Tests gefangen (sie bilden dieses exakte
  Hilt-Konstruktions-Timing nicht nach). Alle 5 anderen ViewModels mit `init{}`
  (`ShiftViewModel`/`AuthViewModel`/`AlarmViewModel`/`HueViewModel`/`MainViewModel`) wurden
  geprüft und deklarieren korrekt alles vor `init{}`. In `CalendarViewModel` standen danach noch
  `isCalendarLoadingInProgress`/`lastCalendarLoadTime` hinter `init{}` — harmlos nur zufällig, weil
  ihre Initializer (`false`/`0L`) genau den JVM-Feld-Defaults entsprechen; bei einem Nicht-Default
  oder Objekt-Typ hätte der Initializer nach `init{}` überschrieben, was der synchron gestartete
  Collector schon gesetzt hat. Jetzt vor `init{}`, neben `eventLoadGeneration`.

### Schicht-Änderungs-Notification & Pre-Alarm-Refresh (seit v1.20.0)

- **Kein echtes Push möglich, bewusst nicht versucht.** Google Calendar Push (`events.watch`)
  braucht eine öffentlich erreichbare, domain-verifizierte HTTPS-Callback-URL — ein Android-Gerät im
  Hintergrund hat keine. Stattdessen: `CalendarPreAlarmRefreshScheduler` plant pro anstehendem Alarm
  (max. 14 Tage Lookahead, max. 10 Jobs — Vorbild `HueSmartScheduler`) einen WorkManager-
  `OneTimeWorkRequest` 3h vor der jeweiligen Weckzeit (`CalendarPreAlarmRefreshWorker`, `NetworkType.
  CONNECTED`), der `syncAlarms()` mit frischen Events anstößt. WorkManager statt Exact-Alarm, weil
  ein paar Minuten Verzug hier tolerierbar sind (Vorbild:
  `hue/scheduling/workers/PreAlarmHealthCheckWorker`, gleiches Muster). Reduziert die Lücke, schließt
  sie aber NICHT — eine Änderung, die erst innerhalb der letzten 3h vor dem Alarm eintrifft (wie der
  Rufbereitschafts-Abruf vom 03.08.2026, der den Sync nur zufällig rechtzeitig erreichte), bleibt
  weiterhin auf die nächste 6h-Wartung angewiesen. `reschedule()` läuft an denselben zwei Stellen wie
  der bestehende Dimmer-Reschedule (`AlarmMaintenanceService`, `BootReceiver`), jeweils best-effort
  im eigenen try/catch.
- **Die Notification-Entscheidung lebt INNERHALB von `AlarmUseCase.syncAlarms()`, nicht bei dessen
  vier Aufrufern.** `ShiftChangeNotifier` wird auf der Implementierung injiziert, NICHT auf
  `IAlarmUseCase` — das Interface bleibt unverändert. Wer einen fünften Aufrufer von `syncAlarms()`
  hinzufügt, bekommt die Notification automatisch, ohne selbst etwas zu tun. Alle drei
  Notifier-Aufrufe (Create/Update/Delete-Zweig) stehen in einem eigenen `try/catch` — eine
  fehlgeschlagene Notification darf die eigentlich kritische Alarm-Synchronisation niemals
  beeinträchtigen oder rückgängig machen.
- **Der allererste Sync (z. B. nach Neuinstallation) flutet nicht.** `isFirstSync =
  existingAlarms.isEmpty()` unterdrückt `notifyCreated()` gezielt nur für diesen einen Fall — jede
  danach neu erkannte Schicht (z. B. Rufbereitschaft → aktivierte Schicht) benachrichtigt normal.
  `notifyUpdated()` hat eine eigene Schwelle (Zeit-Delta ≥10min ODER Schichtname geändert), damit
  Rundungsrauschen nicht flutet.


- **Die Schicht-Änderungs-Notification feuert auch nach einer reinen DEFINITIONS-Änderung**, nicht
  nur bei einer echten Dienstplan-Änderung in TimeOffice — live ausgelöst am 14.08.2026 („AD1:
  06:30 → 05:00, Wecker angepasst"). Das ist folgerichtig (der Sync sieht eine geänderte Weckzeit
  und kann die Ursache nicht unterscheiden), aber beim Deuten von Nutzer-Meldungen wissenswert:
  eine solche Meldung heißt nicht zwingend, dass sich der Dienstplan geändert hat.
