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
- Den letzten Kalender abwählen IST eine Löschgrundlage — ein leeres Ladeergebnis nicht

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

## Der Teilerfolg: richtig gesperrt, aber unsichtbar (v1.26.0)

Die Sperren oben sind richtig — und genau deshalb war dieser Fall so schwer zu sehen.

Sind mehrere Kalender ausgewählt (privat + TimeOffice-Dienstplan) und **einer** davon dauerhaft
unerreichbar (gelöscht, Freigabe entzogen, Feed-Quelle abgeschaltet), ist jede Eventliste
unvollständig. Alle vier `isComplete`-Sperren greifen und verhindern das Löschen. Sie verhindern
damit aber auch, dass **jemals wieder etwas angelegt** wird: `AlarmMaintenanceService`,
`CalendarPreAlarmRefreshWorker` und `BootReceiver` kehren zurück, bevor `syncAlarms()` läuft. Die
bestehenden Wecker klingeln der Reihe nach und laufen aus, nichts wächst nach — nach etwa zwei
Wochen ist der Bestand leer, ohne eine einzige Meldung.

Gefunden in der Prüfrunde vom 18.08.2026. Bis dahin stand `failedCalendars` ausschließlich im Log
und in den Sperren selbst.

**Was daraus folgt, und was ausdrücklich NICHT:**

- **Nur der Teilerfolg ist neu.** Fallen ALLE Kalender aus, ist das der Autorisierungsfall
  (`resolveCalendarAuthorizationOutcome()` → `calendarAuthorizationValid = false`) mit eigener,
  handlungsfähiger Meldung. `CalendarUiState.unavailableCalendarIds` bleibt dann bewusst leer —
  zwei Warnungen für dieselbe Lage sind schlechter als eine.
- **Die IDs, nicht die Anzahl.** „Irgendein Kalender ist nicht abrufbar" lässt sich nicht abwählen.
  `failedCalendars` ist deshalb nur noch eine abgeleitete Property von `failedCalendarIds`.
- **Der Name kann fehlen, und das ist in Ordnung.** `availableCalendars` lädt seitenweise (20 pro
  Seite); ein ausgewählter Kalender kann noch gar nicht darin stehen. Dann nennt der Text die
  Anzahl statt eines geratenen Namens. Sind zwei betroffen und nur einer auflösbar, wird KEINER
  genannt — sonst wählt der Nutzer einen ab und wundert sich, dass die Meldung bleibt.
- **Die App entfernt nie selbst.** Aus demselben Grund: „ID fehlt in `availableCalendars`" ist kein
  Beweis für „gelöscht". Eine selbsttätige Bereinigung wäre bei einer vorübergehenden Störung genau
  die „leer ist die gefährlichste Lüge"-Falle, nur auf der Auswahl statt auf den Events. Es gibt
  einen Knopf, und der gehört dem Nutzer.
- **Dieser Knopf hat seit v1.30.0 eine zweite Wirkung, und deshalb eine Rückfrage.** Trifft
  „Aus Auswahl entfernen" den LETZTEN ausgewählten Kalender, ist es keine Bereinigung mehr, sondern
  eine Abwahl — und die räumt alle kalenderbasierten Wecker der nächsten zwei Wochen samt der
  Dienstzeit-Fenster für Dimmer und DND (siehe den Abschnitt unten). Der Anlass ist dabei häufig
  vorübergehend (Server- oder Freigabestörung), also etwas, das von allein vergeht. Deshalb prüft
  `entfernenWuerdeAuswahlLeeren()` gegen die AKTUELL ausgewählten IDs (nicht gegen eine gemerkte
  Anzahl — die Liste der nicht abrufbaren stammt aus dem letzten Ladevorgang) und stellt vorher die
  Frage; der harmlose Ausweg ist der hervorgehobene Knopf, „Trotzdem entfernen" der unauffällige.
  Die Texte sind Konstanten, damit ein Test sie festhalten kann: der Text IST hier die Zusicherung —
  er muss die Folge benennen („alle Wecker der nächsten zwei Wochen", „selbst gestellte bleiben")
  und das Abwarten anbieten.
- **Vorübergehend ≠ dauerhaft.** Ein Funkloch während des Abrufs erzeugt dieselben
  `failedCalendarIds`. Die Karte zeigt das sofort (sie ist ohnehin nur sichtbar, wenn jemand
  hinsieht), die BENACHRICHTIGUNG erst, wenn dieselbe ID zwei aufeinanderfolgende Wartungsläufe
  scheitert. Eine Wecker-App, die grundlos warnt, wird stumm geschaltet — und dann fehlt auch die
  echte Warnung.
- **Der Notifier wird VOR der `isComplete`-Sperre und AUCH mit leerer Menge aufgerufen.** Stünde er
  dahinter, sähe er ausschließlich Störungen und verstummte nach der ersten nie wieder; ohne die
  leeren Läufe erführe er nie, dass sich ein Kalender erholt hat.

**Am Emulator durchgemessen (18.08.2026)**, mit einer nicht existierenden Kalender-ID in der echten
Auswahl — die API antwortet darauf mit 404, also genau wie bei einem gelöschten Kalender:
Teilerfolg erzeugt (`1/2 Kalender nicht abrufbar`), die 8 bestehenden Wecker blieben unangetastet,
Karte zeigte Text und Knopf, Lauf 1 schwieg, **Lauf 2 meldete**, Lauf 3 wiederholte nicht, und nach
dem Tippen auf „Aus Auswahl entfernen" lief der Sync sofort wieder an (8 → 9 Alarme).

**Bekannte Kleinigkeit:** Die Entprellungs-Merker werden erst vom nächsten WARTUNGSLAUF geräumt,
nicht schon beim Entfernen des Kalenders. Wer denselben Kalender innerhalb dieses Fensters (max.
6 h) wieder hinzufügt, während er noch kaputt ist, bekommt keine erneute Benachrichtigung — die
Karte zeigt ihn trotzdem. Bewusst nicht behoben: der Aufwand stünde in keinem Verhältnis.


## Den letzten Kalender abwählen IST eine Löschgrundlage — ein leeres Ladeergebnis nicht (v1.30.0)

Der `else`-Zweig von `observeCalendarSelection()` leerte beim Abwählen des LETZTEN Kalenders nur
Eventliste und `CalendarStateHolder` — **ohne jeden Alarm-Sync**. Das Abwählen EINES von mehreren
Kalendern räumte dessen Wecker korrekt ab (der Delta-Sync des nächsten Ladevorgangs entfernt jeden
Alarm, dessen `eventId` fehlt); beim letzten gab es diesen nächsten Ladevorgang nicht mehr. Und
danach war **kein Pfad mehr zuständig**: 6h-Wartung, `CalendarPreAlarmRefreshWorker` und der
`ShiftViewModel`-Pfad steigen bei leerer Auswahl bzw. leerer Eventliste alle VOR `syncAlarms()` aus,
und der `BootReceiver` armiert die gespeicherten Alarme sogar aktiv neu. Folge: Die Oberfläche zeigte
„kein Kalender ausgewählt" und null Termine, während das Gerät **bis zu 14 Tage weiter nach dem
entfernten Dienstplan weckte** und zu dessen Dienstzeiten dimmte. Abstellen ließ sich das nur durch
Einzellöschung jedes Weckers oder die Master-Pause.

**Warum das hier erlaubt ist, obwohl „leer" sonst die gefährlichste Lüge ist:** Diese Leere stammt
nicht aus einem Abruf, sondern aus einer ausdrücklichen Nutzeraktion. Ein gescheiterter Abruf kann
den Pfad gar nicht erreichen — er ist doppelt abgesichert:
1. `hasSeenNonEmptySelection` — es muss ein Übergang „war ausgewählt → ist es nicht mehr" sein, nicht
   der leere Startwert des noch nicht hydrierten `StateFlow`.
2. Eine Rückfrage direkt beim DataStore über `getCurrentSelectedCalendarIds()`. Sie unterscheidet
   „wirklich leer" von „nicht lesbar" (`Result.failure`) — bei Zweifel wird NICHT geräumt, und der
   Widerspruch (Oberfläche sagt „kein Kalender", Wecker stehen weiter) wird gemeldet.

Geräumt wird über `syncAlarms(emptyList(), config)` statt über eigenes Löschen: dessen Leerlisten-
Zweig ist genau der schonende — er schreibt `persistShiftSpans(emptyList())` (sonst dimmen Dimmer und
DND weiter nach dem alten Dienstplan; `syncAlarms()` ist der EINZIGE Schreiber des `ShiftSpanStore`),
räumt mit `keepManualAlarms = true` (ein manueller Wecker stammt nicht aus dem Kalender) und hält die
Löschreihenfolge ein (erst `cancelSystemAlarm()`, dann `deleteAlarm()`). Das Abwählen zieht außerdem
eine neue `eventLoadGeneration` — sonst überholt ein noch laufender Ladevorgang das Leeren und legt
Wecker aus genau den Terminen an, die der Nutzer soeben entfernt hat.

### Der dauerhafte Räumauftrag — und warum er eine Gegenfrage braucht

`NonCancellable` schützt gegen den Abbruch der Coroutine, **nicht gegen den Prozesstod** — und beim
nächsten App-Start ist die leere Auswahl der Ausgangszustand, der Übergang also nicht mehr erkennbar
(`hasSeenNonEmptySelection` ist per Konstruktion falsch). Die naheliegendste Geste, Abwählen und die
App sofort wegwischen, trifft genau dieses Fenster. Deshalb der `PendingDeselectionCleanupStore`:
gesetzt **VOR** dem Räumen (danach bliebe genau die Lücke offen, die er schließen soll), gelöscht
erst nach nachweislichem Erfolg, abgearbeitet von der 6h-Wartung (`AbwahlRaeumauftrag`) — ganz ohne
die App. Scheitert das Festhalten selbst, wird trotzdem geräumt: dann ist der Auftrag nur nicht
prozessfest, also so gut wie vorher, aber nicht schlechter.

**Die Gegenfrage ist der Kern:** Die Wartung liest die Kalenderauswahl **selbst erneut** und verwirft
einen hinfällig gewordenen Auftrag. Ohne diese Gegenfrage wird ein dauerhafter Auftrag zur veralteten
Absicht, die später Wecker löscht — genau daran ist der baugleiche Merker für das Abmelden
gescheitert: er wusste nur „ein Abmelden ist unfertig", nie „der Nutzer ist noch abgemeldet",
sperrte deshalb nach einer Neuanmeldung bei JEDEM Neustart die Wiederherstellung und ließ die
Wartung alle Wecker des neuen Kontos löschen. Er wurde deshalb ersatzlos zurückgebaut.

**Die Gegenrichtung, ebenso bewusst:** Der Auftrag wird **nicht** schon aufgelöst, sobald wieder ein
Kalender ausgewählt ist. Bis v1.29.2 geschah genau das, begründet damit, dass der Sync des folgenden
Ladevorgangs jeden verwaisten Alarm entferne. Dieser Sync läuft aber nicht in jedem Fall: er sitzt
hinter der Prüfung „Eventliste nicht leer" und steigt zusätzlich fail-safe aus, wenn die Liste nicht
nachweislich vollständig ist. Liefert der neu gewählte Kalender null Termine, passiert gar nichts —
und mit dem gelöschten Auftrag fängt es auch die Wartung nicht mehr auf. Aufgelöst wird deshalb erst,
wo es BELEGT ist: nach einem gelungenen Sync über einer nachweislich vollständigen Eventliste.

**Ausdrücklich offen gelassen:** Ist die Master-Pause aktiv, steht ohnehin kein Wecker — der Auftrag
ist dann gegenstandslos und wird gelöscht, ohne zu syncen (ein Sync während der Pause würde über den
zentralen Backstop zusätzlich einen schwebenden Snooze abbrechen, was eine Kalender-Abwahl nicht tun
soll).

## Eine feste Notification-ID für n Meldungen eines Laufs (v1.30.0)

`ShiftChangeNotifier` postete alle drei Meldungsarten unter der konstanten ID **2202**. Die Aufrufer
in `syncAlarms()` sind aber Schleifen über den GESAMTEN Bestand (`notifyDeleted` je entferntem,
`notifyUpdated`/`notifyCreated` je neuem Alarm): `notify()` mit gleicher ID ERSETZT die stehende
Meldung, von n Meldungen eines Sync-Laufs überlebte also nur die letzte — auch quer über die Arten
hinweg. Ausgerechnet „Schicht entfernt", die Meldung, die dem Nutzer den WEGFALL eines Weckers sagt,
verschwand damit am ehesten, weil ein später verarbeitetes „Neue Schicht erkannt" sie überschrieb.
Ein Dienstplanwechsel, der mehrere Tage auf einmal ändert, ist der Normalfall, nicht der Ausnahmefall.

Gelöst als **sammelnde Meldung** (Anzahl plus Einzelposten) statt pro-Alarm abgeleiteter IDs: Letztere
hätten bei einem großen Wechsel die Leiste überschwemmt und mussten gegen die übrigen vergebenen IDs
abgegrenzt werden (2002 ist der Wecker). Die Sammlung wird pro Lauf zurückgesetzt und darf über
Prozessgrenzen hinweg nicht lügen; der Notifier darf den Sync weder ausbremsen noch werfen — er läuft
mitten in der Alarm-Erzeugung.
