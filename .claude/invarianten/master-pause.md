# Master-Pause (Hintergrunddienste pausieren)

> Ausgelagert aus `CLAUDE.md` (17.08.2026). Dort steht die Kurzregel, hier der Hergang:
> warum die Regel existiert, welcher Bug sie erzwungen hat, welche Messung sie belegt.
> **Vor Änderungen in diesem Bereich lesen.**

---

### Hintergrunddienste pausieren (Master-Pause, seit v1.21.0)

- **Eigenständig neben `autoAlarmEnabled`, keine Kombination der beiden.** `MasterPauseUseCase.
  pause()`/`resume()` (Settings-Tab, `masterpause/`-Package) rührt `ShiftConfig.autoAlarmEnabled`
  bewusst NICHT an — sie ruft stattdessen direkt `alarmUseCase.deleteAllAlarms()`. Würde `pause()`
  den Flag umlegen, müsste sich das System merken, ob der Nutzer ihn schon VOR der Pause manuell
  deaktiviert hatte, um das beim Fortsetzen nicht zurückzudrehen — unnötiger Zustand für denselben
  Effekt.
- **`AlarmUseCase.syncAlarms()` hat einen zentralen Master-Pause-Backstop, nicht nur einzelne
  Gates an den Aufrufstellen.** Beim ersten Bau wurden `BootReceiver`, `AlarmMaintenanceService`
  und `HueSmartScheduler` einzeln gegen `MasterPausePrefs.pausedNow()` abgesichert — aber
  `CalendarViewModel.createAlarmsFromLoadedEvents()` (ein fünfter, unabhängiger Aufrufer von
  `syncAlarms()`, ausgelöst bei JEDEM Kalender-Ladevorgang inkl. normalem App-Start) wurde dabei
  übersehen. Real am Fairphone reproduziert: nach einem Reboot waren 0 Alarme gesetzt (Master-Pause
  hielt), aber das bloße Öffnen der App legte sofort wieder 5 Alarme an. Deshalb prüft `syncAlarms()`
  selbst `masterPausePrefs.pausedNow()` — als **erste inhaltliche Prüfung innerhalb von
  `SafeExecutor.safeExecute`**, vor jeder Event-/Alarm-Verarbeitung; davor laufen nur die
  Serialisierung (`alarmSyncMutex`) und das folgenlose `clearExpiredSkip()`. Bei `true` wird über
  `clearInternalAlarms(alsoCancelPendingSnoozes = true)` geräumt. Das ist der garantierte
  Fangnetz-Punkt für JEDEN aktuellen UND künftigen Aufrufer; die einzelnen Gates an den Aufrufstellen
  bleiben zusätzlich bestehen (vermeiden unnötige Arbeit wie Kalender-Fetches), sind aber NICHT mehr
  die einzige Verteidigungslinie.
- **Denselben Backstop haben `DimScheduleUseCase.enable()` und `DndScheduleUseCase.enable()`**
  (Vorbild `syncAlarms()`): jeder ViewModel-Setter ruft `enable()` ungegatet, und die rollende
  Tick-Kette plant sich selbst nach — eine einzige Einstellungsänderung während der Pause weckte
  Dimmer bzw. Zen-Regel dauerhaft wieder auf, obwohl die UI „pausiert" anzeigt. `disable()` bleibt
  bewusst ungegatet, sonst kommt `MasterPauseUseCase.pause()` nicht mehr durch.
- **Der Pausen-Spiegel wird beim App-Start mit der CE-Wahrheit abgeglichen
  (`reconcileDirectBootMirror()`).** `KEY_PAUSED` hat zwei Schreiber und drei Leser, die alle im
  Boot-Pfad sitzen — der Spiegel ist das Einzige, was der `BootReceiver` vor der ersten Entsperrung
  über die Pause weiß. `savePaused()` schluckt seinen Fehler; fiel ein Schreibvorgang aus,
  divergierten beide dauerhaft, denn es gab keinen abgleichenden Pfad. Beide Richtungen sind
  schlecht: ein hängendes `true` sperrt die Boot-Wiederherstellung dauerhaft (kein Wecker nach dem
  nächsten Neustart), ein hängendes `false` re-armt Alarme, die der Nutzer pausiert hat.
- **`pause()`/`resume()` laufen in `withContext(NonCancellable)`.** Beide stellen einen Zustand HER,
  statt nur einen Schalter umzulegen — und der Schalter wird als ERSTES geschrieben. Der einzige
  Aufrufer startet sie im `viewModelScope`; wird der abgebrochen (Activity beendet, Task
  weggewischt), stehen Flag und Wirklichkeit auseinander. Beide Richtungen sind gefährlich: bei
  `pause()` zeigt die App „pausiert", während 6h-Wartung, Dimmer-Tick, DND-Tick und Hue-Planung
  weiterlaufen; bei `resume()` zeigt sie „aktiv", während keine dieser Ketten wieder angelaufen ist
  — der Wecker bliebe STILL, und beim nächsten Boot liest der `BootReceiver` einen Spiegel, der
  nicht mehr zum Flag passt.
- **Die Master-Pause überlebt weder einen Gerätewechsel noch einen Konfigurations-Import** — beides
  Absicht: sie ist maßgeblicher Zustand, der (anders als die übrigen Laufzeitwerte im
  `settings`-Store) nicht neu abgeleitet wird, und mitgebracht bliebe der Wecker auf dem neuen Gerät
  STILL. Details in „Gerätewechsel & Konfigurations-Datei".
- **Fünf bekannte `syncAlarms()`-Aufrufer** (Stand v1.21.0): `BootReceiver`,
  `AlarmMaintenanceService`, `CalendarViewModel.createAlarmsFromLoadedEvents()`,
  `ShiftViewModel.triggerAlarmCreationFromConfigUpdate()`, `CalendarPreAlarmRefreshWorker`. Wer
  einen sechsten hinzufügt, muss sich um Master-Pause-Gating NICHT mehr einzeln kümmern (siehe
  Backstop oben) — aber genau diese Liste zeigt, wie leicht ein Aufrufer beim manuellen Gaten
  übersehen wird. **Um die VOLLSTÄNDIGKEIT seiner Eventliste muss er sich dagegen selbst kümmern**
  (siehe „Kalender-Datenfluss"): dafür gibt es keinen Backstop in `syncAlarms()`, weil die Funktion
  einer Liste nicht ansehen kann, ob sie ein Ausschnitt ist.
- **`DimScheduleUseCase.disable()`/`DndScheduleUseCase.disable()` rühren KEINE persistierten
  Toggles an** (`wellnessEnabled`/`rulesEnabled`/`nightDefaultEnabled` bzw. die DND-Trigger) — nur
  den Laufzeitzustand (aktives Overlay/Zen-Regel-Zustand) und den rollenden Tick-Alarm. Ein
  späteres `enable()` muss exakt die vorherige Konfiguration wiederherstellen, nicht eine durch
  die Pause veränderte.
- **`HueSmartScheduler.initializeSmartScheduling()` läuft bei JEDEM App-Kaltstart**, nicht nur nach
  einem Reboot (`CFAlarmApplication.initializeApp()` → `HueBridgeConnectionManager.initialize()`).
  Der Master-Pause-Check steht deshalb als allererste Prüfung INNERHALB der `schedulerScope.launch`-
  Coroutine, bevor `scheduleDailyPlanning()`/`calculateAndScheduleNextHealthChecks()` überhaupt
  erreicht werden — sonst würde ein einfacher App-Neustart die Pause für die Hue-Planung lautlos
  aufheben.
- **`BackgroundServiceManager.initializeMaintenanceService()` ist `suspend`** und prüft die
  Master-Pause als ersten Schritt — aufgerufen von `AuthViewModel` nach jeder erfolgreichen
  (Re-)Autorisierung. Ohne dieses Gate würde eine Re-Authentifizierung während der Pause die
  6h-Wartungskette lautlos wieder anstoßen.

