# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Git & GitHub Workflow

Folgt dem globalen Default aus `~/.claude/CLAUDE.md` (Branch pro Änderung, proaktiv committen/mergen/pushen, nur bei irreversiblen Operationen nachfragen). Projekt-spezifische Abweichungen davon:

- Branch-Präfixe: `feature/<kebab-case>`, `fix/<kebab-case>`, `chore/<kebab-case>`.
- **Mehrere Claude-Sessions arbeiten parallel an diesem Repo** (lokal am PC **und** Cloud-Sessions auf `claude/*`-Branches, die eigenständig nach `origin/main` mergen). Deshalb: **immer `git fetch` + Divergenz prüfen, bevor** auf `main` gebumpt/committet/gepusht wird; bei Divergenz mergen/rebasen statt force-push. `versionCode` muss höher als der **höchste je vergebene** sein (nicht nur höher als der eigene Basisstand).
- **Handoff-Notizen gehören AUSSCHLIESSLICH in `..Projektdateien/claudes mds/HANDOFF.md`** (lokal, gitignored) — dort liegen auch die Play-Deklarations-Texte. **Keine getrackte Handoff-Datei anlegen** (kein `docs/HANDOFF.md` o. Ä.): der `docs/`-Ordner ist GitHub Pages (öffentlich), und eine zweite Datei erzeugt Doppelungen. Cloud-/Remote-Sessions, die den gitignoreten Ordner nicht sehen, arbeiten aus dieser `CLAUDE.md` + der Git-Historie, statt eine Zweitdatei zu erzeugen.

## Build & Development Commands

```bash
# Debug build
./gradlew assembleDebug

# Release build (requires keystore.properties)
./gradlew assembleRelease

# Run all unit tests
./gradlew test

# Run a single test class (NICHT `test --tests` - das Aggregat kennt die Option nicht)
./gradlew testDebugUnitTest --tests "com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.AlarmSchedulerTest"

# Lint check
./gradlew lint

# Install debug APK to connected device
./gradlew installDebug
```

## Prerequisites

Before building, `keystore.properties` must exist in the project root with:
```
googleWebClientId=<your-client-id>.apps.googleusercontent.com
storeFile=../cf-alarm-release.keystore
storePassword=<password>
keyAlias=cf-alarm-key
keyPassword=<password>
```

The build will throw a `GradleException` if `googleWebClientId` is missing (no hardcoded fallback by design).

## Architecture

The app follows **Clean Architecture + MVVM** with Hilt DI (recently migrated from manual DI).

### Layer overview

```
ui/screens/ + ui/components/   ← Jetpack Compose (Material3)
viewmodel/                     ← @HiltViewModel, StateFlow-based UI state
usecase/ + usecase/interfaces/ ← Business logic (interface-segregated)
repository/ + repository/interfaces/ ← Data access contracts
data/ + auth/ + calendar/ + hue/ + shift/ ← Concrete implementations
service/ + alarm/              ← Android background components
di/modules/                    ← Hilt module definitions
```

### Dependency Injection (Hilt)

Modules in `di/modules/`:
- **DataModule** – provides three `DataStore<Preferences>` instances (qualifiers `@MainDataStore`, `@HueDataStore`, `@TokenDataStore`) and `TinkEncryptionHelper` singleton
- **RepositoryModule** – binds repository interfaces to implementations
- **UseCaseModule** – binds use-case interfaces to implementations
- **HueModule** – OkHttp/Retrofit clients for Philips Hue API
- **ServiceModule** – `AlarmManagerService`, `BackgroundServiceManager`
- **StateModule** – `CalendarStateHolder` (shared state between ViewModels)

All Android components (`Service`, `BroadcastReceiver`) that need injection are annotated `@AndroidEntryPoint`.

### Authentication & Token Storage

`auth/` contains the OAuth2 flow:
- **`CredentialAuthManager`** – Google Sign-In via `androidx.credentials`
- **`OAuth2TokenManager`** – fetches/refreshes tokens via `GoogleAuthUtil`, delegates storage to `TokenRepository`
- **`DataStoreTokenRepository`** – persists tokens encrypted with **Google Tink** (AES-256-GCM) via `TinkEncryptionHelper`
- Der Token liegt im DataStore **`token_data_v2_encrypted`**, den sich `DataStoreTokenRepository`
  per `EncryptedDataStoreFactory` **selbst** baut (nur `@ApplicationContext` injiziert).
  `TinkEncryptionHelper` muss ein Singleton bleiben.
- **Es gibt bewusst keinen `@TokenDataStore`-Qualifier.** `DataModule` stellte bis v1.11.2 einen
  bereit (`oauth_tokens`), den **niemand** injizierte — ein Leichenrest der Hilt-Migration. Am
  Geraet verifiziert: `oauth_tokens.preferences_pb` existierte gar nicht. Die Falle war die
  Namensgleichheit: `DataStoreTokenRepository` hat ein **privates** Feld `tokenDataStore` (den
  verschluesselten Store) — wer nur den Namen sah, hielt den Provider fuer benutzt. In v1.11.3
  entfernt. Wer einen Token-Store ins DI-Modul zurueckholt, baut die zweite Wahrheit neu: Ein
  Klartext-`preferencesDataStore` fuer Tokens waere ausserdem genau das, was Tink verhindern soll.

### Alarm System

`service/` and `alarm/`:
- **`AlarmMaintenanceService`** – short-lived foreground service (type `specialUse`) triggered every 6 hours by an exact alarm. Pipeline: token refresh → health check → Google Calendar events → alarm creation
- **`AlarmSoundService`** – foreground service (type `mediaPlayback`) that plays alarm sounds
- **`AlarmReceiver`** – fired by `AlarmManager` when an alarm triggers
- **`BootReceiver`** – restores alarms after reboot/update (`BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`)
- **`BackgroundServiceManager`** – schedules WorkManager workers and the 6-hour maintenance exact alarm

### Shift Recognition

`shift/ShiftRecognitionEngine` maps Google Calendar events to shift definitions (`model/ShiftDefinition`) using configurable keyword matching. Results are cached adaptively (2–30 seconds based on usage patterns). `ShiftConfig` stored in `@MainDataStore`.

### Philips Hue Integration

`hue/`:
- Discovery: mDNS (`HueMdnsDiscoveryService`), N-UPnP (`HueNUpnpDiscoveryService`), official endpoint (`OfficialHueDiscoveryService`)
- **`HueBridgeConnectionManager`** – singleton managing connection health and reconnect
- **`HueApiClient`** – Retrofit-based API client (OkHttp with custom TrustManager for local bridge TLS)
- **`HueSmartScheduler`** – orchestrates WorkManager workers for daily planning and pre-alarm health checks
- Hue config stored in `@HueDataStore`

### Navigation

Tab-based navigation via `NavigationViewModel` and `MainTab` enum (`HOME, WECKER, STATUS, SETTINGS, HUE, DIMMER`). `MainScreen` is the Compose root; it receives all ViewModels and delegates to tab content composables (`HomeTabContent`, `WeckerTabContent`, `HueTabContent`, `SettingsTabContent`, `StatusTabContent`, `DimmerTabContent`).

### Shared State

`di/state/CalendarStateHolder` – a Hilt singleton `StateFlow` holder shared between `CalendarViewModel` and `MainViewModel` to avoid direct ViewModel-to-ViewModel dependencies.

## Key Constraints

- The `AD_ID` permission is explicitly blocked (`maxSdkVersion="0"`) — do not re-enable it
- `USE_EXACT_ALARM` and `USE_FULL_SCREEN_INTENT` are core permissions; the app cannot function without them
- Die `DataStore`-Namespaces bleiben getrennt (settings / hue / tokens) — nie zusammenlegen. Nur
  die ersten beiden kommen aus `DataModule`; der Token-Store ist der selbstgebaute
  verschluesselte `token_data_v2_encrypted` (siehe Authentication & Token Storage)
- `TinkEncryptionHelper.getInstance()` must stay a singleton — reinitializing it would break decryption of existing tokens
- `minSdk = 26`, `compileSdk = 37`, `targetSdk = 37`; Java 17 source/target with core library desugaring enabled

---

## Bekannt und so gewollt

- **`Logger.business()` loggt auf INFO** → PII (E-Mail, Kalendertitel) landet in Debug-Builds im
  Datei-Log (`Logger.kt:116`). Bewusst: Release-Logs enthalten nur WARN+.
- **Regel speichern navigiert sofort weg** (`HueRuleConfigScreen`: `createRule()` ist
  fire-and-forget, `onSaveComplete()` folgt unmittelbar). Ein Fehler landet dadurch erst auf dem
  `HueSettingsScreen` statt im Formular. Seit v1.10.4 kann die Validierung tatsächlich ablehnen —
  bisher nur theoretisch, weil die UI-Validierung dieselben Bedingungen vorher abfängt. Wird das
  je unangenehm: auf das Result warten, bevor navigiert wird.

---

## Invarianten — nicht versehentlich zurückdrehen

Siehe auch Memory `project_alarm_ux_rebuild.md`.

### Wecker

- **Eine Instanz besitzt den Wecker**: `AlarmSoundService` hält Ton, Vibration, Audio-Fokus und
  die einzige Notification (ID 2002). Channel **stumm**, aber `IMPORTANCE_HIGH` (Pflicht für
  Full-Screen-Intent). Der `AlarmReceiver` darf **keine** eigene Notification posten.
- **Kein `startActivity()` aus dem AlarmReceiver**: AlarmManager-Broadcasts stehen nicht auf der
  Exemption-Liste für Background-Activity-Starts. Einziger Weg: `setFullScreenIntent()`.
- **`_alarmActive = true` VOR `startForeground()`** — sonst schließt sich das Vollbild sofort.
- **Snooze braucht `snoozeAlarmAction(id)`**, nicht `enhancedAlarmAction(id)` — sonst bricht der
  Maintenance-Sync den Snooze ab.
- **`AlarmMaintenanceService`: `stopSelf(startId)`, niemals blankes `stopSelf()`.** Zwei
  überlappende Starts teilen sich `serviceScope`; der Erste, der fertig wird, löst sonst
  `onDestroy()` → `scope.cancel()` aus und reißt den anderen mitten in der Arbeit ab.
- **Die 6h-Wartungskette hat GENAU einen Planer: `scheduleNext()`, auf genau einem Request-Code.**
  Es gab mal einen zweiten (`scheduleNextAlarm()`, Code 9999 statt 0). Verschiedene Request-Codes
  = verschiedene PendingIntents = zwei unabhängige Alarme; da der `finally`-Block von
  `onStartCommand` ohnehin immer `scheduleNext()` ruft, liefen dauerhaft zwei Wartungszyklen alle
  6h im Millisekunden-Abstand. Wer einen Lauf „sicherheitshalber" selbst nachplant, baut das
  wieder ein — der `finally`-Block deckt jeden Pfad ab.
- **Das "Nächsten Alarm überspringen"-Flag läuft zeitbasiert ab, nicht per ID-Match.**
  `AlarmSkipUseCase.skipNextAlarm()` löscht den System-Alarm SOFORT (SKIP-IMMEDIATE-UX) — damit
  feuert er nie wieder, und der eigentlich vorgesehene Rücksetz-Pfad
  (`checkAndProcessSkip()` via `AlarmReceiver.onReceive()`) ist für genau diesen Alarm für immer
  unerreichbar. Real beobachtet (26.07.–30.07.2026, ~4 Tage): das Flag blieb hängen, die Karte
  zeigte dauerhaft "Aufheben"/bräunliches Icon, bis der Nutzer manuell aufhob — obwohl der
  eigentliche Wecker in der Zwischenzeit korrekt (normal) geklingelt hatte. Auch ein
  ID-Mismatch in `checkAndProcessSkip()` (ein *anderer* Alarm feuert) räumt das Flag nicht auf
  (`ALARM_EXECUTED`-Zweig ruft bewusst kein `clearSkipStatus()`). Fix seit v1.18.2:
  `AlarmSkipState.skippedAlarmTriggerTime` speichert die ursprüngliche Weckzeit;
  `AlarmSkipUseCase.clearExpiredSkip()` setzt das Flag automatisch zurück, sobald diese Zeit
  verstrichen ist. Aufgehängt an `AlarmUseCase.syncAlarms()` — dem einzigen Einstiegspunkt der
  Event→Alarm-Pipeline (Vordergrund-Sync beim App-Öffnen UND 6h-Wartung) — bewusst kein neuer
  Scheduler. Wer den Ablauf wieder auf reines ID-Matching zurückbaut, holt sich den Bug zurück.
- **Stille Schicht (`ShiftDefinition.isSilent`/`AlarmInfo.isSilent`, seit v1.20.0) ist KEIN Ersatz
  für eine optionale `alarmTime`.** `alarmTime` bleibt bewusst ein nicht-nullables Pflichtfeld — sie
  ist der Zeit-Anker, den DND/Dimmer/Feature A (Rufbereitschaft-Cutoff) weiterhin brauchen. Eine
  echte Nullable-`alarmTime` hätte `ShiftRecognitionEngine`/`AlarmUseCase`/`AlarmManagerService`/
  `ShiftMatch` durchzogen UND Feature A die Datengrundlage entzogen (kein Alarm = kein Eintrag in
  `getAllAlarms()` = kein Cutoff-Anker). Das Flag gated stattdessen NUR die Wecker-AUSLÖSUNG:
  `AlarmReceiver.isSilentAlarm()` (reine, testbare Funktion) prüft `alarmInfo?.isSilent == true` und
  überspringt bei Treffer per frühem `return@launch` — noch VOR dem Wake-Lock — sowohl
  `AlarmSoundService`-Start (Ton/Vibration/Vollbild) als auch `executeHueRulesForAlarm()`. Der
  Broadcast selbst feuert normal weiter, die `AlarmInfo` bleibt normal in `getAllAlarms()` — DND/
  Dimmer/Feature A sind davon unberührt. **Fail-safe wie der Skip-Check daneben:** schlägt der
  `AlarmInfo`-Lookup fehl (z. B. Direct Boot vor Entsperrung), gilt der Alarm NICHT als still — im
  Zweifel wecken statt versehentlich stumm bleiben.
- **„Deine Schicht beginnt um" (Notification + Vollbild) muss `AlarmInfo.shiftStartTime` zeigen,
  NICHT `triggerTime`/die Weckzeit** (Fix v1.20.1, Extra dafür heißt seither
  `AlarmReceiver.EXTRA_SHIFT_START_TIME`, Schlüssel `"shift_start_time_formatted"`). Real
  beobachtet: bei S2 (Weckzeit 14:30, Kalender-Schichtbeginn z. B. 14:48) zeigte die Anzeige die
  Weckzeit. Die eigentliche Falle lag NICHT in der Erstplanung (`AlarmManagerService.
  createEnhancedAlarmIntent()`, liest korrekt `ShiftMatch.calendarEvent.startTime`), sondern im
  weit häufiger durchlaufenen Re-Arming-Pfad `AlarmUseCase.scheduleSystemAlarm(alarmInfo)` — jeder
  der drei `syncAlarms()`-Zweige (neu/geändert/unverändert-re-armen) läuft darüber, also praktisch
  jeder App-Start, jede 6h-Wartung, jeder Boot. Diese Funktion baute bis dahin eine SYNTHETISCHE
  `CalendarEvent.startTime` direkt aus `alarmInfo.triggerTime` (der Weckzeit) — obwohl `AlarmInfo.
  shiftStartTime` (Epoch-Millis des echten Schichtbeginns, seit dem Rufbereitschaft-Cutoff-Feature
  vorhanden) bereits verfügbar war. Nur ein Live-Test am Emulator (Alarm über `cmd alarm set-time`
  wirklich feuern lassen, nicht nur Code-Review) hat das aufgedeckt — der ansonsten korrekt
  aussehende Fix an `createEnhancedAlarmIntent()` allein hätte den Bug NICHT behoben, weil dieser
  Pfad in der Praxis kaum greift. Snooze und Direct-Boot-Restore reichen denselben Wert unverändert
  durch (Schichtbeginn ändert sich durchs Schlummern nicht). Fallback bei `shiftStartTime <= 0`
  (z. B. manueller Test-Alarm ohne echte Schicht) bleibt bewusst die Weckzeit — unveränderte
  UX für den Fall, der nicht Teil dieses Bugs war.
- **`ShiftConfig.autoAlarmEnabled = false` ist eine ECHTE, sofortige Pause** (seit v1.21.0,
  Wecker-Tab): `AlarmUseCase.syncAlarms()` ruft in diesem Zweig `clearInternalAlarms()` (cancelt
  System-Alarme + räumt Repository + Direct-Boot-Spiegel), nicht nur ein stilles `return
  emptyList()`. Wer diesen Aufruf entfernt, macht den Schalter wieder zur Attrappe, die nur
  *neue* Alarme verhindert, aber bestehende weiterlaufen lässt — das war der ursprüngliche,
  gemeldete Zustand. `ShiftViewModel.triggerAlarmCreationFromConfigUpdate()` ruft bei
  `!autoAlarmEnabled` zusätzlich direkt `alarmUseCase.deleteAllAlarms()`, unabhängig vom
  `CalendarStateHolder`-Cache-Zustand — sonst wirkt „Ausschalten" nicht, wenn gerade keine
  Kalender-Events geladen sind (realer Fall direkt nach App-Start).
- **`ShiftRecognitionEngine` ist ein gemeinsam genutzter Singleton mit nicht-atomarem
  Mehrfeld-Cache** (`lastRecognitionHash`/`cachedMatches`/`recognitionInProgress`/`lastCacheTime`,
  alle `@Volatile`, aber ohne gemeinsame Atomizität). `AlarmUseCase.syncAlarms()` und
  `ShiftUseCase.recognizeShiftsInEvents()` rufen dieselbe Instanz auf verschiedenen Dispatchern
  (IO vs. Main) auf. Real am Fairphone reproduziert: `ShiftViewModel.updateShiftConfig()` löste
  `processCalendarEvents(currentEvents)` (Engine-Aufruf 1) und danach
  `triggerAlarmCreationFromConfigUpdate()` (Engine-Aufruf 2, über `syncAlarms`) aus — solange
  `processCalendarEvents` fire-and-forget lief (eigener, unabgewarteter `viewModelScope.launch`),
  überlappten sich beide Aufrufe auf derselben Engine-Instanz, und „Automatische Alarme" wieder
  einschalten erzeugte trotz korrekt erkannter Schichten 0 Alarme. Fix: `processCalendarEvents` ist
  jetzt `suspend fun` (kein eigener `launch` mehr) — beide bestehenden Aufrufer liefen ohnehin
  schon in einer Coroutine und rufen es jetzt direkt/abgewartet auf. Wer hier wieder ein
  fire-and-forget `launch` einbaut, holt sich die Race zurück.

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
  selbst — als erste Zeile, VOR jeder anderen Logik — `masterPausePrefs.pausedNow()` und räumt bei
  `true` über `clearInternalAlarms()` auf. Das ist der garantierte Fangnetz-Punkt für JEDEN
  aktuellen UND künftigen Aufrufer; die einzelnen Gates an den Aufrufstellen bleiben zusätzlich
  bestehen (vermeiden unnötige Arbeit wie Kalender-Fetches), sind aber NICHT mehr die einzige
  Verteidigungslinie.
- **Fünf bekannte `syncAlarms()`-Aufrufer** (Stand v1.21.0): `BootReceiver`,
  `AlarmMaintenanceService`, `CalendarViewModel.createAlarmsFromLoadedEvents()`,
  `ShiftViewModel.triggerAlarmCreationFromConfigUpdate()`, `CalendarPreAlarmRefreshWorker`. Wer
  einen sechsten hinzufügt, muss sich um Master-Pause-Gating NICHT mehr einzeln kümmern (siehe
  Backstop oben) — aber genau diese Liste zeigt, wie leicht ein Aufrufer beim manuellen Gaten
  übersehen wird.
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

### Hue

- **Das Auto-Aus gehört der BRIDGE, nicht dem Handy** (seit v1.11.0). `executeRulesForAlarm()`
  legt es im selben Atemzug mit dem Einschalten als Bridge-Zeitplan ab. Der frühere
  `AutoOffWorker` ist **ersatzlos** weg — kein Fallback, und keiner wird gebraucht: ging das
  Licht an, war die Bridge erreichbar und der Zeitplan entsteht; war sie es nicht, ging kein
  Licht an und es gibt nichts auszuschalten. Wer hier einen zweiten Mechanismus „zur Sicherheit"
  einbaut, baut die Fehlerklasse „Handy nicht im Heim-WLAN" wieder ein.
- **Die V1-API antwortet auch bei ABLEHNUNG mit HTTP 200.** Das Urteil steht im Body
  (`[{"error":…}]`). `makeSecureHueRequest` kennt nur den Status — wer sich darauf verlässt,
  hält einen abgelehnten Zeitplan für angelegt und das Licht geht nie wieder aus. Dafür gibt es
  `parseV1Envelope`. Dieselbe Zwei-Ebenen-Falle wie bei `Result<RuleValidationResult>`.
- **Das 90-Zeichen-Limit für `command` aus der offiziellen Doku greift nicht.** Ein reales
  Command misst mit 40-Zeichen-Username ~111 Zeichen und wird akzeptiert (verifiziert 15.07.2026
  gegen BSB002, apiversion 1.78.0; die öffentlich archivierte Doku ist von 2013/API 1.0). Nicht
  „reparieren" — `BridgeScheduleSerializationTest` hält das absichtlich fest.
- **Timer (`PT00:30:00`), nicht absolute Zeit.** Der Zeitplan entsteht zur Weckzeit, gemeint ist
  „+30 Minuten". Der Timer zählt auf der Uhr der **Bridge** herunter → immun gegen falsch
  gestellte Bridge-Zeitzone, Sommerzeit und Drift zwischen Handy und Bridge.
- **Auf der Bridge liegen fremde Zeitpläne** (real: zwei „Hue dimmer switch 1"). Aufgeräumt wird
  ausschließlich, was `BridgeTimer.NAME_PREFIX` trägt. Und aufgeräumt werden **muss**: sonst legt
  jeder Snooze einen weiteren Timer an, und der älteste schaltet zu früh aus.
- **`autoOffTargetsOf()` filtert bewusst NICHT nach Schichtnamen.** Die Auswahl gehört dem
  Aufrufer (`findApplicableRules` matcht auch über **Keywords**). Ein zweiter Filter gegen den
  Schichtnamen würde genau die Regeln wegwerfen, die über ein Keyword getroffen haben — eine
  Regel mit `shiftPattern` „Früh" fällt gegen „Frühschicht" durch und verlöre ihr Auto-Aus.
- **`HueBridgeConnectionManager.initialize()` muss idempotent bleiben.** Zwei Aufrufer ohne
  feststehende Reihenfolge: `CFAlarmApplication.initializeApp()` (asynchron im applicationScope)
  und `HueBridgeRepository.init` (Hauptthread, sobald Hilt das HueViewModel baut). Ohne Wächter
  lief alles doppelt und der SmartScheduler verwarf seine gerade angelegten WorkManager-Jobs.
- **`Result<RuleValidationResult>` hat zwei Ebenen.** `isFailure` heißt „die Prüfung ist
  gescheitert", **nicht** „die Regel ist ungültig" — das steht innen in `isValid`. Beides
  verwechselt hieß: ungültige Regeln wurden gespeichert. Siehe `requireValidRule`.
- **`MIN_RULE_NAME_LENGTH = 1`, nicht mehr.** Die UI verlangt nur `isNotBlank()`; eine reale Regel
  heißt „FS". Eine höhere Schwelle macht bestehende Regeln unbearbeitbar (`updateRule` validiert
  ebenfalls).
- **„Bridge eingerichtet" und „Bridge verbunden" sind zwei Fragen.** Der `HueSmartScheduler` plant
  nichts, solange `hasStoredBridge()` false ist (ohne Bridge kann jeder Job nur scheitern — der
  Fallback-Health-Check tat das lautstark bei jedem Start). `hasStoredBridge()` liest den
  **persistierten** Wert. Nicht auf `getCurrentConnectionInfo()` umbauen: das liefert nur bei
  `CONNECTED` etwas, fällt nach einem misslungenen Health-Check auf `ERROR` — und dann würde die
  App genau die Pre-Alarm-Checks abschalten, die der Weg zurück sind.

### Navigation

- **Zurueck gehoert dem `BackHandler` in `MainScreen`.** Die App navigiert ueber einen eigenen
  `NavigationState`, nicht ueber Navigation-Compose — es gibt **keinen Backstack**, der Zurueck
  von allein behandelt. Ohne Handler landet jeder Druck beim Activity-Default und **beendet die
  App**: aus „Kalender-Events" sprang der Nutzer auf den Android-Homescreen (am Fairphone 6
  gemeldet, 15.07.2026). Wer einen neuen `NavigationState` ergaenzt, muss ihn dort mitbedenken —
  der `else`-Zweig faengt jeden Unterscreen ab, die Sonderfaelle stehen davor. Zwei davon sind
  nicht optional: `BatteryExemption` muss wie „Spaeter" wirken (`dismissBatteryPrompt()`), sonst
  schickt `handleAuthenticationSuccess()` den Nutzer sofort zurueck und Zurueck sieht wirkungslos
  aus; `OEMWarning` muss wie „Verstanden" die Wartungskette anstossen (`finishOnboarding()`),
  sonst steht ein Nutzer ohne 6h-Wartung da. Auf dem Home-Tab bleibt der Handler bewusst **aus**
  — dort ist Zurueck wirklich „App verlassen", und der Systemdefault kann das inkl.
  Predictive-Back besser.

### Hue-Vorschau & Test

- **Die Regel-Vorschau raeumt IMMER auf** — unabhaengig vom Auto-Aus der Regel. Das Aufraeumen
  haing frueher an `hasAutoOff`, und das steht bei einer **neuen** Regel auf `false`: Der
  Vorschau-Knopf schaltete das Licht an und liess es an, ohne Weg zurueck ausser der Hue-App.
  Der Unterschied zum echten Weckvorgang ist Absicht: der **laesst** ohne Auto-Aus an (so
  gewollt), nur die Vorschau raeumt auf — sie ist ein Ausprobieren, kein Lichtschalter. Der
  Hinweistext trennt beides. `RulePreviewCleanupTest` haelt das fest.
- **Beim Sonnenaufgang haengt das Aus HINTER der Rampe** (`SUNRISE_TEST_DURATION_MINUTES +
  AUTO_OFF_TEST_DURATION_SECONDS`), nicht bei flachen 20s. Sonst wuergt es die laufende native
  Bridge-Transition mitten im Aufblenden ab. Derselbe Gedanke wie `sunriseOffsetMinutes` in
  `autoOffTargetsOf()`.
- **`runLightTest()` blitzt LAMPEN, niemals Gruppen** — auch wenn eine Gruppe pro PUT mehrere
  Lampen erreicht und damit sparsamer waere. **Gruppen ueberschneiden sich beliebig, auch
  untereinander**: real (Bridge des Nutzers, verifiziert 15.07.2026) liegt Lampe 4 in
  „Wohnzimmer", „Deckenlampe" UND „Zuhause"; von 10 Gruppen decken sich mehrere. Jede Gruppe
  anzufunken heisst also mehrere Alerts auf derselben Lampe — und weil jedes Ziel ein eigener
  HTTP-PUT ist, kommen die zeitversetzt an: Aufleuchten, Pause, Blinken. Genau so wurde es
  gemeldet. Die Lampen-Ebene ist die **einzige**, auf der „jede Lampe genau einmal" strukturell
  gilt, egal wie die Gruppen geschnitten sind. Deshalb nimmt `flashLight(lightId)` bewusst
  **kein** `isGroup`-Flag mehr — der Fehler soll nicht wieder formulierbar sein.
  (Zwischenstand v1.11.1 „Gruppen + Lampen ohne Gruppe" war nur die halbe Miete: er entdoppelte
  Lampen gegen Gruppen, nicht Gruppen gegeneinander.)
- **`flashLight` nutzt `lselect` und bricht es nach `FLASH_DURATION` (4s) selbst ab.** `select`
  waere nur ein einzelner Blitz — als Beweis zu leise. `lselect` blinkt aber von sich aus **15s**,
  und das ist als Rueckmeldung zu lang (vom Tester gemeldet). Gegen die echte Bridge verifiziert:
  `alert:"none"` bricht ein laufendes `lselect` ab und die Lampe faellt in ihren vorherigen
  An/Aus-Zustand zurueck.
- **Der Abbruch-Timer und das Vorschau-Auto-Aus haengen an `followUpScope`, nicht am Aufrufer.**
  Beide muessen auch feuern, wenn der Nutzer den ausloesenden Bildschirm laengst verlassen hat —
  ein `viewModelScope` waere gecancelt, und das Licht bliebe an bzw. die Lampe am Blinken.
- **Nichts in `runLightTest()` darf `refreshLightTargets()` benutzen.** Das ist
  **fire-and-forget** (startet nur eine Coroutine); der `uiState` direkt danach ist immer noch
  leer. Genau das war „der erste Klick tut nichts, der zweite blinkt": Direkt nach dem Koppeln
  ist die Liste leer, der erste Klick stiess den Refresh an, las die leere Liste und meldete
  „Keine Lampen gefunden". Wer Ziele braucht, ruft `getAllLightTargets()` und **wartet**.

### Schicht → Regel

- **`ShiftConfig.findDefinitionFor()` ist streng nach Genauigkeit gestaffelt: exakter Name →
  exaktes Keyword → Teiltreffer (nur Keywords ab 2 Zeichen).** Vorher stand im `AlarmReceiver`
  ein `find { name == x || keywords.any { shiftName.contains(it) } }`. `find` nimmt den **ersten**
  Treffer, und die Spätschicht trägt das Keyword **„S"** — das steckt in „S2", „Nacht**s**chicht"
  und „Zwi**s**chendienst". Folge: die S2-Regel feuerte **nie**, die Spätschicht-Regel bei fast
  **jeder** Schicht. Nur die Frühschicht stimmte, weil sie zufällig vorn in der Liste steht.
  Am Emulator gegen die echte Standardkonfiguration reproduziert (v1.11.0).
  `AlarmInfo.shiftName` ist immer der **Name** einer Definition → Stufe 1 trifft im Normalfall
  immer; die Keyword-Stufen sind nur für umbenannte Definitionen da. Wer `contains` wieder nach
  vorn zieht oder die Längengrenze senkt, baut den Fehler neu — `ShiftDefinitionMatchingTest`
  hält alle fünf Standard-Schichten fest.

### Schicht-Dimmer (Regel-Auflösung)

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
  schicht-relativ und brauchen einen Alarm an dem Datum. Wer CLOCK↔CLOCK wieder schicht-relativ
  macht, reißt „immer 22–7 außer ND" wieder auf. `DimWindowResolverTest` hält das Kern-Szenario fest.
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
  selbst). Das Read-Modify-Write auf die Override-Prefs läuft außerdem hinter einem `Mutex`
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
- **Modus 1 dupliziert KEINE Fenster-Logik.** Er ruft `DimScheduleUseCase.previewTimeline()`
  direkt auf (bereits öffentlich, seiteneffektfrei) statt eine eigene Kopie der
  Dimmer-Fensterberechnung zu pflegen. Einbahnstraße wie `CalendarStateHolder`: `dnd/` liest von
  `dimmer/`, nie umgekehrt — der Dimmer bleibt unverändert und unwissend von DND. Wer hier eine
  eigene, „ähnliche" Fensterberechnung für DND einbaut, öffnet genau das Drift-Risiko (zwei
  Quellen der Wahrheit für „ist gerade Nacht"), vor dem die adversariale Kritikrunde gewarnt hat.
- **Modus 2 braucht `AlarmInfo.shiftStartTime`**, nicht `triggerTime` (Weckzeit, meist vor
  Schichtbeginn wegen Anfahrt) und nicht nur `shiftEndTime`. Gesetzt in
  `AlarmUseCase.createAlarmFromShiftMatch` aus `shiftMatch.calendarEvent.startTime` — exakt
  daneben, wo `shiftEndTime` aus `calendarEvent.endTime` gesetzt wird.
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
  Tick aufgerufen. **Noch offen:** ein echter wiederholter Testanruf, der die Anrufer-Ausnahme
  (`allowRepeatCallers`) tatsächlich prüft, und der Modus „Während der Dienstzeit" mit real
  synchronisierten Alarmen (bestehende Alarme vor diesem Feature haben `shiftStartTime = 0`, siehe
  unten).
- **Logcat-Fallstrick beim Debuggen, kein Bug:** `W/System.err` mit `java.lang.Exception: Stack
  trace` + `Thread.dumpStack()` rund um `setAutomaticZenRuleState()` ist Androids eigenes internes
  Aufruf-Tracing für Zen-Änderungen — sieht wie ein Crash aus, ist keiner. Der direkt folgende
  `V/Settings: ZEN_MODE change value to X` sowie der eigene Erfolgs-Log bestätigen den echten
  Aufrufausgang.

### TimeOffice-Abhängigkeit

- **CFAlarms gesamte Funktion hängt an einer Kette außerhalb dieser App**: TimeOffice
  (`de.pradtke.timeoffice`) schreibt den Dienstplan (inkl. Krankschreibungen) lokal in einen
  eigenen Google-Kalender ("Timeoffice Dienstplanfeed", ein besessener Sekundärkalender im
  Google-Konto, keine URL-Subscription — verifiziert per Sharing-/Zugriffsberechtigungs-Optionen
  in dessen Kalender-Einstellungen). CFAlarm liest von dort. **Live am 30.07.2026 nachgewiesen**:
  TimeOffice selbst war von "App bei Nichtnutzung pausieren" (aktiv) UND Akku-Optimierung
  "Optimiert" betroffen — der Sync blieb ~4 Tage stehen, obwohl TimeOffice die Krankschreibung
  längst intern kannte (🤒-Symbol im eigenen Dienstplan sichtbar). CFAlarms eigene Alarme liefen
  in der Zwischenzeit einwandfrei — das Problem war unsichtbar, bis man gezielt danach sucht.
- **`TimeOfficeHealthHelper`/`TimeOfficeHealthCard`/`TimeOfficeHealthOnboardingScreen`** (seit
  v1.19.0) spiegeln dieses Muster aus `BatteryOptimizationHelper`/`UnusedAppRestrictionsHelper`
  auf die externe Abhängigkeit. **Wichtige Einschränkung, absichtlich so gelassen**: Es gibt
  **keine öffentliche API**, um den "Nicht verwendete Apps"/Hibernation-Status einer ANDEREN App
  abzufragen (`PackageManagerCompat.getUnusedAppRestrictionsStatus()` funktioniert nur für die
  aufrufende App selbst) — nur die Akku-Optimierungs-Ausnahme ist für fremde Pakete prüfbar
  (`PowerManager.isIgnoringBatteryOptimizations(anderesPackage)` akzeptiert jeden Package-Namen,
  siehe `BatteryOptimizationHelper.isExempted(context, packageName)`). Die Karte zeigt deshalb für
  Akku-Optimierung echtes Grün/Rot, für "Nicht verwendete Apps" bewusst **keinen** vorgetäuschten
  Status. Wer hier versucht, einen Hibernation-Status für TimeOffice "irgendwie" zu ermitteln,
  jagt einer API hinterher, die nicht existiert.
- **Kein `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`-Dialog für TimeOffice.** Anders als beim
  eigenen Akku-Onboarding (das einen Ein-Klick-System-Dialog auslöst) führt der Aktions-Button
  hier IMMER auf TimeOffices eigene App-Info-Seite (`ACTION_APPLICATION_DETAILS_SETTINGS`) — nicht
  dokumentiert/getestet, ob Android den Bestätigungsdialog für ein fremdes Package überhaupt
  zulässt. Der App-Info-Weg ist der einzige, der am 30.07.2026 live nachweislich funktioniert hat.
- **`<queries>` in AndroidManifest.xml ist Pflicht** für `TimeOfficeHealthHelper.isInstalled()`
  (Android 11+ Package-Visibility) — ohne die Deklaration liefert `getPackageInfo()` für
  `de.pradtke.timeoffice` immer `NameNotFoundException`, unabhängig davon ob installiert.
- **Kein Unit-Test für `TimeOfficeHealthHelper`** — bewusst, gleiche Konvention wie
  `BatteryOptimizationHelper`/`UnusedAppRestrictionsHelper` (siehe deren fehlende/minimale Tests):
  dünne Android-Wrapper ohne eigene Logik werden hier nicht getestet, nur reine Funktionen wie
  `UnusedAppRestrictionsHelper.needsPrompt()`.

### Auth

- **Kein `getOrElse { emptyList() }` auf Auth-behafteten Ergebnissen.** Für eine Wecker-App ist
  „leer" die gefährlichste Lüge — nicht von „du hast frei" zu unterscheiden.
- **GMS-Token-Cache liegt außerhalb des App-Speichers** und überlebt die Deinstallation. Nur
  `GoogleAuthUtil.clearToken()` räumt ihn ab.
- **`onResult` gehört `OAuth2TokenManager.authorize()`** — es feuert auf jedem Weg genau einmal
  (Sofort-Erfolg, Fehler, Dialog via `handlePermissionResult`). Niemand sonst ruft ihn. Ein
  zweiter Aufruf im `AuthUseCase` startete die Wartung doppelt.
- **`observeTokenLoss()` nimmt nur das NEGATIVE Signal.** `hasValidToken` heißt „`getValidToken()`
  klappt gerade" inkl. Refresh; „Token liegt im Store" ist schwächer und würde das Gate bei einem
  toten, noch nicht verworfenen Token fälschlich aufmachen. `drop(1)` ist Pflicht: die erste
  Emission ist der Ist-Zustand, kein Verlust.
- **`signOutInProgress` nicht wegoptimieren.** Beim Abmelden verwirft die App das Token selbst;
  ohne das Flag stieße `observeTokenLoss()` direkt danach einen Zustimmungsdialog an. `isSignedIn`
  allein reicht **nicht** — die DataStore-Emission trifft asynchron ein, `observeAuthState` ist
  zusätzlich 200ms entprellt.
- **Abmelden heißt: nichts bleibt zurück.** `AuthUseCase.signOut()` verwirft Auth-Daten UND Token
  (inkl. GMS-Cache). `CredentialAuthManager.signOutLocally()` ist nur eine Log-Zeile — sich darauf
  zu verlassen war der Fehler.

### Kalender-Datenfluss

- **`CalendarStateHolder` ist eine Einbahnstraße**: `CalendarViewModel` schreibt hinein, liest nie
  daraus; einziger Leser ist `ShiftViewModel`. Wer Events lädt und nur dorthin schreibt,
  aktualisiert die `CalendarUiState` nicht — und die rendert Home.
- **Laden gehört ausschließlich dem `CalendarViewModel`** (`refreshData(forceRefresh = true)`
  aktualisiert beides und trägt Fehler in den State). Keinen zweiten Ladepfad einbauen — genau der
  hat den stummen Retry erzeugt.
- **Endlosschleifen-Bremse in `MainScreen`** (~Zeile 101): automatisches Nachladen nur bei
  `error == null`. Sonst: Laden scheitert → `isLoading` false → Effect erneut → Liste leer →
  laden … im Sekundentakt gegen die Google-API (real passiert bei 401). Nicht entfernen.

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

### UI-Texte

- **Der Akku-Onboarding-Screen darf keine Einstellungen versprechen.** `MainScreen` feuert
  `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` mit `package:`-Data — das ist Androids
  **Systemdialog** („Zulassen, dass die App immer im Hintergrund läuft?"), ein Tipp, keine Liste.
  Der Screen beschrieb eine vierstufige Anleitung, die niemand je zu sehen bekam. Wer
  den Ablauf ändert, muss den Text mitändern — und umgekehrt.
- **Kernpunkt einmal, konkret, mit dem echten Einsatz.** Für eine Wecker-App heißt das nicht
  „Background-Jobs werden gestoppt", sondern „der Wecker bleibt still". Tiefere Erklärung gehört
  hinter „Warum ist das nötig?", nicht ein zweites Mal auf den Screen.

### Compose-Layout

- **`Row(SpaceBetween) { Column { … }; Switch }` braucht `weight(1f)` am Column.** Ohne das nimmt
  der Beschreibungstext die volle Breite und der Schalter landet außerhalb der Karte. Eine feste
  `.width(…dp)` als Pflaster bricht bei schmalem Display oder großer Schrift.
- **`ButtonDefaults.ContentPadding` = 24dp pro Seite.** In schmalen, geteilten Buttons bleibt zu
  wenig für die Schrift, und Compose bricht mitten im Wort. Dafür gibt es
  `ui/components/CompactActionButton.kt` — **nur** für schmale, geteilte Buttons, nicht für
  ganzbreite, wo ein Zweizeiler gewollt ist.
- **`RadioButton`/`Checkbox` mit `onClick = null` brauchen `heightIn(min = 48.dp)` am Row.**
  Das Muster „ganze Zeile klickbar" (`Modifier.selectable`/`toggleable` am Row, `onClick = null`
  am Knopf) ist richtig — aber der Knopf ist damit **nicht mehr klickbar** und bringt seine
  eingebaute `minimumInteractiveComponentSize()` nicht mehr mit. Ohne die Klemme schrumpft die
  Reihe auf ~32dp: breiter als vorher, aber flacher als Materials Minimum. Am Emulator
  nachgemessen (Density 420: 48dp = 126px). Konstante: `MIN_TOUCH_TARGET` in
  `HueRuleConfigScreen`.
- **Chip-Reihen als `FlowRow`**, nicht `Row` mit `chunked(n)`. `FlowRow` ist in Compose 1.11.4
  stabil (nur die deprecated Überladung mit `overflow` ist `@ExperimentalLayoutApi`) → kein
  `@OptIn` nötig.

---

## Umgebung / Arbeitsweise

- **Gradle UND der Emulator sind in dieser Umgebung erreichbar** (verifiziert 15.07.2026 über
  `./gradlew --offline installDebug` → echter Build + Install auf `emulator-5554`, exit 0, ~40s).
  `--offline` nutzen — der Cache ist durch lokale Builds des Nutzers warm. Selbst bauen,
  installieren, messen, A/B-testen statt nur durch Inspektion zu verifizieren. `emulator`-Binary
  ist nicht auf PATH:
  `C:\Users\Christoph\AppData\Local\Android\Sdk\emulator\emulator.exe`. Bibliotheks-Quelltext bei
  Bedarf trotzdem direkt lesbar: `~/.gradle/caches/modules-2/files-2.1/<group>/…-sources.jar`.
  Details siehe Memory `env-local-build-and-emulator`.
- **„Warnungen plötzlich weg" ist kein Fortschritt.** `org.gradle.configuration-cache=true`
  (`gradle.properties:23`): Die Deprecation-Warnungen entstehen in der Konfigurationsphase. Wird
  der Konfigurations-Cache wiederverwendet, erscheinen sie schlicht nicht neu. Nach jeder Änderung
  an `build.gradle.kts`/`gradle.properties` sind sie wieder da.
- **Die Warnung lügt:** Ihr Vorschlag, `android.builtInKotlin`/`android.newDsl` zu entfernen und
  auf built-in Kotlin zu migrieren, zerlegt das Dreieck aus KSP 2.x, KGP 2.x und AGP 9.x. Beide
  Flags bleiben auf `false`.
- **Debug-Build** schreibt VERBOSE ins Datei-Log (`CFAlarmApplication.kt:72`). Release-Logs
  enthalten **nur WARN+** → erfolgreiche Operationen sind dort unsichtbar. Für Diagnose immer
  einen Debug-Build verlangen.
- Debug-SHA-1 ist in der Google Cloud Console eingetragen (verifiziert 14.07.).
- Getestet wird auf einem echten Gerät **und einem Emulator als Zweitgerät**; Logcat-Auszüge
  kommen vom Nutzer.