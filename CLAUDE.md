# CLAUDE.md

Leitfaden für Claude Code (claude.ai/code) in diesem Repository.

## Wegweiser: die Zusicherungen liegen ausgelagert

Diese Datei enthält die **Kurzregeln**. Jede stammt aus einem echten Bug — meist einem stummen
Wecker. Der **Hergang** (auslösender Fehler, Gerätebeleg, verworfene Alternativen) steht thematisch
in `.claude/invarianten/`.

**Arbeitest du in einem dieser Bereiche, lies VORHER die zugehörige Datei.** Die Kurzregel sagt
*was*, die Detaildatei sagt *warum* — und ohne das Warum baut man dieselbe Falle in neuer Form nach.

| Bereich | Datei |
|---|---|
| Alarme, Boot, Snooze, Wartungskette, Vollbild | `.claude/invarianten/alarme.md` |
| Master-Pause (Hintergrunddienste pausieren) | `.claude/invarianten/master-pause.md` |
| Philips Hue | `.claude/invarianten/hue.md` |
| Hue-Vorschau & Lampentest | `.claude/invarianten/hue-vorschau.md` |
| Navigation & Zurück-Verhalten | `.claude/invarianten/navigation.md` |
| Schichterkennung & Musterabgleich | `.claude/invarianten/schichterkennung.md` |
| Schicht-Dimmer & DND | `.claude/invarianten/dimmer-dnd.md` |
| TimeOffice-Abhängigkeit | `.claude/invarianten/timeoffice.md` |
| Persistenz (DataStore) | `.claude/invarianten/persistenz.md` |
| Gerätewechsel & Konfigurations-Datei | `.claude/invarianten/geraetewechsel.md` |
| Auth & Token | `.claude/invarianten/auth.md` |
| Fehlerbehandlung | `.claude/invarianten/fehlerbehandlung.md` |
| Kalender-Datenfluss & Schicht-Änderungen | `.claude/invarianten/kalender.md` |
| UI-Texte & Compose-Layout | `.claude/invarianten/ui.md` |
| Umgebung, Build-Eigenheiten, Testverfahren | `.claude/invarianten/umgebung.md` |

Pflege: Erkenntnisse mit Hergang gehören in die Detaildatei, nur die normative Zeile hierher. Diese
Datei soll nicht wieder wachsen — sie war einmal 165k Zeichen und verdrängte damit den Kontext, den
sie schützen sollte.

## Git & GitHub Workflow

Es gilt der globale Default aus `~/.claude/CLAUDE.md`. Projekt-spezifisch:

- Branch-Präfixe: `feature/<kebab-case>`, `fix/<kebab-case>`, `chore/<kebab-case>`.
- **Mehrere Claude-Sessions arbeiten parallel an diesem Repo** (lokal **und** Cloud-Sessions auf
  `claude/*`-Branches, die eigenständig nach `origin/main` mergen). Deshalb: **immer `git fetch` +
  Divergenz prüfen, bevor** auf `main` gebumpt/committet/gepusht wird; bei Divergenz mergen/rebasen
  statt force-push. `versionCode` muss höher als der **höchste je vergebene** sein.
- **Im geteilten Arbeitsbaum gezielt stagen**: `git status` unmittelbar vor dem Commit, nie
  `git add .` / `commit -a`, kein `git stash`.
- **Handoff-Notizen gehören AUSSCHLIESSLICH in `..Projektdateien/claudes mds/HANDOFF.md`** (lokal,
  gitignored). **Keine getrackte Handoff-Datei anlegen** — `docs/` ist GitHub Pages (öffentlich).

## Build & Development Commands

```bash
./gradlew assembleDebug
./gradlew assembleRelease          # R8/Minify AN; braucht NETZ
./gradlew test
./gradlew testDebugUnitTest --tests "…AlarmSchedulerTest"   # NICHT `test --tests`
./gradlew lint
./gradlew installDebug
./gradlew connectedDebugAndroidTest   # nicht --offline, nicht bei zwei Geräten
```

- **`assembleRelease` braucht Netz** (`produceReleaseComposeMapping`); `--offline` scheitert mit dem
  irreführenden „Configuration cache state could not be cached". Ohne `keystore.properties`/
  `KEYSTORE_PASSWORD` entsteht `app-release-unsigned.apk` (Absicht).
- **`connectedDebugAndroidTest`**: nicht `--offline`, Gerät MUSS wach sein, und die Task
  **deinstalliert die App danach** (ein von Hand eingerichteter Emulator-Zustand ist weg).
- **Bei zwei angesteckten Geräten** scheitern `installDebug`/`connectedDebugAndroidTest` bzw. laufen
  auf ALLEN Geräten — also auch auf dem produktiven Fairphone. Sicherer Weg über `assembleDebug` +
  `adb -s emulator-5554 …`, siehe `umgebung.md`.

## Prerequisites

`keystore.properties` im Projekt-Root:

```
googleWebClientId=<client-id>.apps.googleusercontent.com
storeFile=../cf-alarm-release.keystore
storePassword=<password>
keyAlias=cf-alarm-key
keyPassword=<password>
```

Fehlt `googleWebClientId`, wirft der Build eine `GradleException` (kein hartkodierter Fallback, Absicht).

## Architecture

Clean Architecture + MVVM mit Hilt DI.

```
ui/screens/ + ui/components/          ← Jetpack Compose (Material3)
viewmodel/                            ← @HiltViewModel, StateFlow-based UI state
usecase/ + usecase/interfaces/        ← Business logic (interface-segregated)
repository/ + repository/interfaces/  ← Data access contracts
data/ + auth/ + calendar/ + hue/ + shift/ ← Konkrete Implementierungen
service/ + alarm/                     ← Android-Hintergrundkomponenten
di/modules/                           ← Hilt-Module
```

**Hilt-Module** (`di/modules/`): `DataModule` (genau **zwei** unverschlüsselte
`DataStore<Preferences>`: `@MainDataStore` = `settings`, `@HueDataStore` = `hue_settings`, beide mit
`ReplaceFileCorruptionHandler`, plus `ErrorHandler` — **kein** Token-Store, **kein**
`TinkEncryptionHelper`), `RepositoryModule`, `UseCaseModule`, `HueModule`, `ServiceModule` (genau
vier Provider; `BackgroundServiceManager` hat bewusst seinen eigenen `@Singleton @Inject`-Konstruktor),
`StateModule` (`CalendarStateHolder`). Alle injizierten `Service`/`BroadcastReceiver` sind
`@AndroidEntryPoint`.

**Auth** (`auth/`): `CredentialAuthManager` (Google Sign-In via `androidx.credentials`),
`OAuth2TokenManager` (Token holen/refreshen via `GoogleAuthUtil`), `DataStoreTokenRepository`
(Tink/AES-256-GCM). Der Token liegt im DataStore `token_data_v2_encrypted`, den sich
`DataStoreTokenRepository` per `EncryptedDataStoreFactory` **selbst** baut.

**Alarm** (`service/`, `alarm/`): `AlarmMaintenanceService` (kurzlebiger Foreground-Service,
`specialUse`, alle 6 h per Exact-Alarm: Token-Refresh → Health-Check → Kalender-Events →
Alarm-Erzeugung), `AlarmSoundService` (`mediaPlayback`), `AlarmReceiver`, `BootReceiver`,
`BackgroundServiceManager`.

**Schichterkennung**: `shift/ShiftRecognitionEngine` bildet Kalender-Events auf `ShiftDefinition` ab
(konfigurierbarer Musterabgleich), Ergebnis adaptiv gecacht (2–30 s). `ShiftConfig` im `@MainDataStore`.

**Hue** (`hue/`): Discovery per mDNS/N-UPnP/offiziellem Endpunkt, `HueBridgeConnectionManager`
(Singleton, Verbindungsgesundheit + Reconnect), `HueApiClient` (Retrofit/OkHttp mit eigenem
TrustManager), `HueSmartScheduler` (WorkManager für Tagesplanung und Pre-Alarm-Checks).
Hue-Konfiguration im `@HueDataStore`.

**Navigation**: eigener `NavigationState` + `MainTab`-Enum (`HOME, WECKER, STATUS, SETTINGS, HUE,
DIMMER`), **kein** Navigation-Compose. `MainScreen` ist die Compose-Wurzel (Unterscreens,
Onboarding-Gates, `BackHandler`), `MainContentScreen` verteilt die Tab-Inhalte.

**Shared State**: `di/state/CalendarStateHolder` — Hilt-Singleton mit `StateFlow`.
`CalendarViewModel` **schreibt**, `ShiftViewModel` **liest** (Einbahnstraße).

## Key Constraints

- `AD_ID` ist bewusst blockiert (`maxSdkVersion="0"`) — nicht reaktivieren.
- `USE_EXACT_ALARM` und `USE_FULL_SCREEN_INTENT` sind Kernberechtigungen.
- Die DataStore-Namensräume bleiben getrennt (settings / hue / tokens) — nie zusammenlegen.
- `TinkEncryptionHelper.getInstance()` muss ein Singleton bleiben, und dieser Weg ist der **einzige**
  Zugriff. Kein Hilt-Provider dafür (zöge den Keyset-Read in `directBootAware`-Komponenten).
- **Es gibt bewusst keinen `@TokenDataStore`-Qualifier.** Wer einen Token-Store ins DI-Modul
  zurückholt, baut eine zweite Wahrheit — und ein Klartext-Store für Tokens ist genau das, was Tink
  verhindern soll.
- **Jeder `preferencesDataStore` braucht einen `corruptionHandler.`** Ohne ihn blockiert eine
  beschädigte `preferences_pb` dauerhaft auch das SCHREIBEN, reboot-fest.
- **R8/Minify ist AN** (seit v1.23.0; APK 19,8 → 10,9 MB). `-dontshrink`/`-dontoptimize` müssen in
  `proguard-rules.pro` auskommentiert bleiben.
- `minSdk = 26`, `compileSdk = 37`, `targetSdk = 37`; Java 17 mit core library desugaring.

---

## Grundregeln

- **Eine Funktion ohne Bedienoberfläche gibt es für den Nutzer nicht.** Wer eine Fähigkeit einbaut,
  baut die Stelle mit, an der man sie **sieht**, **auslöst** und ihren **Zustand abliest**.
- **Der Sweep über frisch geschriebenen Code beweist wenig** — ein Fix schafft neue Prüffläche.
  Aussagekräftig ist nur eine Runde über **unverändertem** Code. Abbruchbedingung des
  Härtungsprogramms: zwei aufeinanderfolgende solche Runden ohne neuen bestätigten Befund.
  **Sie ist bis heute NICHT erfüllt, der Zähler steht bei null.** Rohbefund- und
  Bestätigungszahlen jeder Runde gehören ins Protokoll, sonst zählt die Runde nicht mit.
- **Refutation-Voting ist kein Orakel — in beide Richtungen.** Ein „widerlegt" ist ein Hinweis, kein
  Freispruch. Spiegelt ein Befund ein bereits bestätigtes Muster (Zwillinge in Schwesterfunktionen),
  immer selbst am Code nachsehen — genau so wurde schon ein zu Unrecht verworfener Befund gefunden.
- **Am Ende eines Arbeitsdurchgangs aufräumen**: toter Code, nicht mehr aufgerufene Funktionen,
  überholte Kommentare, jetzt redundante Abstraktionen. Und: widerlegte Notizen **korrigieren**,
  nicht danebenschreiben.

---

## Zusicherungen (Kurzform)

Jede Zeile ist eine Regel, deren Bruch schon einmal Schaden angerichtet hat. Hergang und Belege in
der jeweils genannten Detaildatei.

### Alarme, Wecker & Boot → `.claude/invarianten/alarme.md`

- **Löschen heißt IMMER: erst `cancelSystemAlarm()`, dann `deleteAlarm()`.** Umgekehrt entsteht ein
  armierter Alarm, den weder Repository noch Direct-Boot-Spiegel kennen — unsichtbar UND
  unabbrechbar bis zum nächsten Neustart.
- **Eine Instanz besitzt den Wecker**: `AlarmSoundService` hält Ton, Vibration, Audio-Fokus und die
  einzige Wecker-Notification (ID 2002, Channel stumm, aber `IMPORTANCE_HIGH`). Der `AlarmReceiver`
  darf **keine eigene Wecker-Notification** posten. Ausgenommen: die stille Skip-Bestätigung (ID 9999).
- **`AlarmSoundService`: `stopSelf(startId)` und `START_REDELIVER_INTENT`** — nie blankes
  `stopSelf()`/`START_STICKY`. Der Weckton probiert alle Ringtone-Kandidaten und loggt Totalausfall laut.
- **`_alarmActive = true` VOR `startForeground()`** — sonst schließt sich das Vollbild sofort.
- **Kein `startActivity()` aus dem `AlarmReceiver`** — einziger Weg ist `setFullScreenIntent()`.
- **Vollbild-Dismiss und -Snooze teilen eine Einweg-Sperre** (`OneShotAlarmHandoff.claim()`, am
  Anfang BEIDER Handler). Der Notausgang `stopAndClose()` fragt sie bewusst NICHT.
- **`AlarmFullScreenActivity` braucht `onNewIntent()` mit `setIntent()`** (`launchMode="singleTask"`).
- **`visibilitySnapshot()` ist Diagnostik, die im Release-Log landen MUSS** (WARN). Herabstufen macht
  den nächsten Vorfall unauswertbar. Die Ursache des verschwindenden Vollbilds ist weiterhin unbelegt.
- **Alle `setAlarmClock()`-Aufrufstellen behandeln eine entzogene Exact-Alarm-Berechtigung gleich**
  (`AlarmManagerService.setExactOrInexact`: try/catch + inexakter Fallback).
  `requestExactAlarmPermission()` gehört NICHT in diesen Pfad.
- **Snooze braucht `snoozeAlarmAction(id)`**, nicht `enhancedAlarmAction(id)`.
- **Ein schwebender Snooze ist abbrechbar, aber nur auf ausdrücklichen Nutzer-Willen** — nicht in
  datengetriebenen Aufräumzweigen und nicht an `deleteAlarm(id)`.
- **Ein schwebender Snooze muss einen Reboot überleben** (`restorePendingSnoozes()` im `BootReceiver`).
  Der zweiteilige Altbestand (`id|triggerTime`) MUSS lesbar bleiben; beide Anlässe armieren über
  dasselbe `armSnooze()` (identischer PendingIntent), hinter demselben Master-Pause-Gate.
- **Der Snooze-Merker ist serialisiert (`snoozeRegistryLock`) und schreibt mit `commit()`**, nicht
  `apply()`. `armSnooze()` gibt Erfolg zurück, `restorePendingSnoozes()` zählt ECHTE Erfolge.
- **Schlummer-Dauer ist EINE Quelle für beide Auslöser**: `AlarmReceiver` liest sie einmal pro Feuern
  und reicht sie als Intent-Extra durch. **Dieser Read MUSS hinter `userUnlocked` gegated sein.**
- **`clearInternalAlarms()` fragt ZUERST `isPersistenceBlocked()` und scheitert laut.** Der Wächter
  unterscheidet, WARUM geräumt wird: datengetrieben → nichts anfassen; ausdrückliche Abschaltung →
  `cancelAllSnoozes()` + `deleteAllAlarms()` (dokumentierte `force`-Ausnahme) laufen weiter.
- **Die datengetriebenen Räumzweige von `syncAlarms()` schonen MANUELLE Alarme**
  (`keepManualAlarms = true`). Ausdrückliche Abschaltungen räumen weiter ALLES.
- **Ein manueller Alarm lässt sich nicht anlegen, während „Automatische Alarme" aus ist.**
- **`ShiftConfig.autoAlarmEnabled = false` ist eine ECHTE, sofortige Pause** — `syncAlarms()` ruft
  dort `clearInternalAlarms()`, und `ShiftViewModel` zusätzlich `deleteAllAlarms()`.
- **Das Skip-Flag läuft zeitbasiert ab, nicht per ID-Match** (`skippedAlarmTriggerTime` +
  `clearExpiredSkip()`, aufgehängt an `syncAlarms()`). Dazu ein Gate in `syncAlarms()` UND ein
  Backstop in `scheduleSystemAlarm()`.
- **Stille Schicht (`isSilent`) gated NUR die Wecker-AUSLÖSUNG**, nicht die `AlarmInfo` selbst;
  `alarmTime` bleibt ein nicht-nullables Pflichtfeld. Fail-safe: Lookup-Fehler = NICHT still.
- **„Deine Schicht beginnt um" zeigt `AlarmInfo.shiftStartTime`, nicht `triggerTime`.** Die Falle
  liegt im Re-Arming-Pfad `scheduleSystemAlarm()`, nicht in der Erstplanung.
- **Der Delta-Sync hat pro Event ein eigenes `try/catch`, das `CancellationException` weiterwirft.**
- **Verstrichene Weckzeit ist KEINE entfernte Schicht** (`expiredEventIds`) — sonst meldet die App
  jeden Schichtmorgen „Schicht entfernt" für den Dienst, den der Nutzer gerade antritt.
- **Die 6h-Wartungskette hat GENAU einen Planer: `scheduleNext()`, auf genau einem Request-Code.**
  Wer „sicherheitshalber" nachplant, erzeugt zwei parallele Zyklen.
- **Deren `finally`-Block läuft in `withContext(NonCancellable)`** und fängt den Master-Pause-Read;
  dort liegen auch Dimmer-, DND- und Pre-Alarm-Reschedule.
- **`AlarmMaintenanceService`: `stopSelf(startId)`**, niemals blankes `stopSelf()`.
- **`AlarmMaintenanceService.start()` fängt den abgelehnten Vordergrund-Start selbst** (nicht die
  Aufrufer) und setzt einen einmaligen Nachhol-Alarm auf **eigenem** Request-Code.
- **Die 6h-Wartung MUSS Änderungen und Streichungen sehen können** — Laden bei Puffer < 7 Tage ODER
  letzter Abfrage ≥ 12 h ODER nächstem Alarm ≤ 48 h, und danach **immer** synchronisieren. Eigener
  Frische-Stempel `last_event_load_time`. Die Leerlisten-Sperre bleibt.
- **`BootReceiver` liest die Kalenderauswahl über den DataStore**, nicht über den noch nicht
  hydrierten `StateFlow`, und setzt **vor** der langen Recovery einen Wartungs-Anker.
- **`TimezoneChangeReceiver` startet die Wartung mit `forceSync=true`** — ein bloßes Re-Arming wäre
  kein Ersatz (es rechnet dieselben Millis hin und zurück).
- **NICHTS am Application-Graphen darf WorkManager oder CE-Storage beim BAUEN anfassen.** Der Graph
  wird auch im Direct-Boot-Prozess aufgebaut. Deshalb `MasterPauseUseCase` als `dagger.Lazy` und
  WorkManager-Auflösung erst beim Gebrauch. **Kein Unit-Test fängt das** — nur ein echter `adb reboot`.
- **Kein `getSharedPreferences()`/CE-Zugriff in einem Property-Initializer** einer Klasse am
  Application-Graphen (`BackgroundServiceManager`, `HueBridgePinningStore`: beide `by lazy`).
- **Ein Emulator OHNE Bildschirmsperre kann Direct Boot NICHT prüfen.** Vor jedem Direct-Boot-Test
  `adb shell locksettings set-pin 1234` und nach dem Reboot NICHT entsperren.
- **Blockierte Benachrichtigungen sind ein Wecker ohne Oberfläche**: `NotificationsEnabledCard` steht
  VOR `FullScreenIntentCard`, und `AlarmSoundService` loggt direkt nach `startForeground()` ein WARN.
- **Die Hue-Regelausführung im `AlarmReceiver` ist gedeckelt** (`withTimeoutOrNull`,
  `HUE_EXECUTION_BUDGET_MS = 45 s`), weil `pendingResult.finish()` erst danach kommt. Nicht kleiner
  machen: allein der Batch-Timeout einer einzigen Regel ist 30 s, und ein zu knapper Deckel lässt das
  Licht an, ohne dass der Auto-Aus-Zeitplan je entsteht.
- **Zwei Scopes rufen bewusst NIE `.cancel()`**: `AlarmReceiver.receiverScope` und
  `CalendarSelectionRepository.repositoryScope`. Gegenstück: `HueLightUseCase.followUpScope`.
- **`WakeLockManager`/`IWakeLockManager` sind ENTFERNT** (v1.23.1). Die Wake-Locks des echten
  Weckvorgangs liegen in `AlarmReceiver` (PARTIAL) und `AlarmFullScreenActivity` (SCREEN_BRIGHT) —
  dort suchen, nicht nach einer zuständig klingenden Klasse.
- **`Logger.business()` loggt auf INFO** → PII landet in Debug-Builds im Datei-Log (Absicht;
  Release-Logs enthalten nur WARN+).
- **Eine defekte Schicht-Konfiguration erfährt der Nutzer nur über das Log** — sichtbarer Hinweis
  fehlt noch, bewusst offengelassen.
- **`res/mipmap-anydpi-v26` bleibt**, obwohl Lint den Qualifier als überflüssig meldet: der Umzug
  tauscht einen kosmetischen Hinweis gegen zwei `IconXmlAndPng`-Warnungen (gemessen).

### Master-Pause → `.claude/invarianten/master-pause.md`

- **Eigenständig neben `autoAlarmEnabled`** — `pause()`/`resume()` rühren den Flag NICHT an, sondern
  rufen direkt `alarmUseCase.deleteAllAlarms()`.
- **`syncAlarms()` hat einen zentralen Master-Pause-Backstop**, nicht nur Gates an den Aufrufstellen —
  als erste inhaltliche Prüfung innerhalb von `SafeExecutor.safeExecute`. Die Einzel-Gates bleiben
  zusätzlich (sparen unnötige Fetches), sind aber nicht mehr die einzige Verteidigungslinie.
- **Denselben Backstop haben `DimScheduleUseCase.enable()` und `DndScheduleUseCase.enable()`.**
  `disable()` bleibt bewusst ungegatet, sonst kommt `pause()` nicht mehr durch.
- **`pause()`/`resume()` laufen in `withContext(NonCancellable)`** — beide stellen einen Zustand HER,
  und der Schalter wird als erstes geschrieben.
- **Der Pausen-Spiegel wird beim App-Start mit der CE-Wahrheit abgeglichen**
  (`reconcileDirectBootMirror()`) — `savePaused()` schluckt seinen Fehler.
- **`HueSmartScheduler.initializeSmartScheduling()` läuft bei JEDEM Kaltstart**; der Master-Pause-Check
  steht als allererste Prüfung INNERHALB der Coroutine.
- **`BackgroundServiceManager.initializeMaintenanceService()` ist `suspend`** und prüft die Pause zuerst.
- **`DimScheduleUseCase.disable()`/`DndScheduleUseCase.disable()` rühren KEINE persistierten Toggles an.**
- **Die Master-Pause überlebt weder Gerätewechsel noch Konfigurations-Import** — beides Absicht.

### Kalender-Datenfluss → `.claude/invarianten/kalender.md`

- **`CalendarStateHolder` ist eine Einbahnstraße**: `CalendarViewModel` schreibt, `ShiftViewModel` liest.
- **Laden gehört ausschließlich dem `CalendarViewModel`** — keinen zweiten Ladepfad einbauen.
- **Eine unvollständige Eventliste ist KEINE Löschgrundlage.** Zwei Quellen der Unvollständigkeit:
  Teilerfolg einzelner Kalender und das Lazy-Präfix (10 Events pro Kalender). **Jeder löschende
  Konsument geht über `getCalendarEventsWithStatus()` und prüft `CalendarFetchOutcome.isComplete`.**
  Der `CalendarStateHolder` trägt `eventsComplete` mit; `clearEvents()` setzt es auf `false`.
  Drei Aufrufer bleiben absichtlich auf `getCalendarEventsWithCache()`, weil sie nichts löschen —
  bitte nicht erneut als Befund melden.
- **Kein Fehler darf als leeres Erfolgsergebnis durchrutschen** — „leer" ist für eine Wecker-App die
  gefährlichste Lüge. Totalausfall wirft; Teilerfolg bleibt Erfolg; Worker und Wartung haben je ein
  eigenes Leerlisten-Gate; `getCurrentSelectedCalendarIds()` liest den DataStore, nicht den `StateFlow`.
- **Endlosschleifen-Bremse im Kalender-`LaunchedEffect`** (`availableCalendars.isEmpty() && error == null`)
  — nicht entfernen.
- **Der Collector der Kalenderauswahl nimmt sich wieder auf (`retryWhen`)**, statt beim ersten
  Upstream-Fehler dauerhaft zu enden.
- **Ganztägige Termine gehen durch `CalendarEventConverter`** und setzen `isAllDay`;
  `calculateAlarmTime()` überspringt dann die Nachtschicht-Vortags-Heuristik.
- **Nachgeladen wird immer ein PRÄFIX, nie eine Seite ab Offset** (`offset = 0`, `maxEvents =
  angezeigt + limit`); `mergeMoreEvents()` dedupliziert und sortiert defensiv.
- **`loadEventsForSelectedCalendars()` braucht einen Generation-Counter**, kein In-Flight-Flag — und
  die Prüfung muss VOR JEDEM Schreiben stehen, auch vor dem ersten `isLoading = true`.
- **Neue Properties in ViewModels mit `init{}` gehören VOR den `init{}`-Block.** Kotlin initialisiert
  in Textreihenfolge, und ein `StateFlow`-Collector feuert synchron während der Konstruktion —
  reale `NullPointerException`, die 329 grüne Tests nicht gefangen haben.
- **Schicht-Änderungs-Notification lebt INNERHALB von `syncAlarms()`**, nicht bei dessen Aufrufern;
  alle drei Notifier-Aufrufe in eigenem `try/catch`. Der allererste Sync flutet nicht
  (`isFirstSync`), `notifyUpdated()` hat eine eigene Schwelle (≥10 min oder Name geändert).
- **Pre-Alarm-Refresh**: pro Alarm ein WorkManager-Job 3 h vorher (max. 14 Tage, max. 10 Jobs).
  Schließt die Lücke NICHT — echtes Push ist auf einem Android-Gerät nicht möglich.

### Schichterkennung → `.claude/invarianten/schichterkennung.md`

- **Zwei verschiedene Funktionen, zwei verschiedene Regeln** — die Verwechslung hat zweimal Wecker gekostet:
  - **`findDefinitionFor(shiftName)`** ordnet einem BESTEHENDEN Alarm eine Definition zu: exakter Name
    → exaktes Keyword → `contains` ohne Wortgrenzen, dort nur Keywords ab `MIN_FUZZY_KEYWORD_LENGTH = 2`.
  - **`matchesKeywords(eventTitle)`** erkennt Schichten in KALENDERTITELN, mit **Wortgrenzen**.
- **Wortgrenzen über Unicode-Kategorien, NICHT `\b`** — Javas `\b` ist ASCII-basiert und invertiert
  die Semantik bei Umlauten/`ß`. Konstanten auf **Dateiebene** (`ShiftDefinition` ist `@Serializable`).
- **Die einbuchstabigen Standard-Keywords „F"/"S"/"N" gehören in die Vorgaben** — ohne sie sank die
  Erkennung am echten Feed von 4 auf 1 Schicht. Restrisiko bewusst akzeptiert und testlich festgeschrieben.
- **Jede Standard-Definition hat neben dem Stationskürzel ein generisches, mehrbuchstabiges Muster.**
- **`ShiftDefinition.isEnabled` wird in `performRecognition()` respektiert** — bewusst NICHT in
  `findDefinitionFor()`.
- **Ein gescheiterter Konfigurations-Read darf NIE zur leeren Definitionsliste werden** (`getOrThrow()`).
- **Kein stiller Default-Überschreiber der Schicht-Konfiguration** — alle drei Fallbacks sind entfernt.
  Der bewusste Weg zum Default heißt `resetToDefaults()` und gehört dem Nutzer.
- **„Auf Standardwerte zurücksetzen" rührt `autoAlarmEnabled` nicht an.**
- **`ShiftRecognitionEngine`: EIN unveränderliches Cache-Objekt hinter Volatile-Referenz, Prüfung UND
  Veröffentlichung hinter `recognitionMutex`, PLUS eine Epochen-Kennung** (der Mutex allein reicht
  nicht — `clearRecognitionCache()` läuft synchron).
- **`ShiftViewModel` beobachtet `IShiftUseCase.shiftConfig`** und zieht Anzeige, Erkennung UND Alarme
  nach; eigene Änderungen per Gleichheitsvergleich übersprungen.
- **Geraten wird nicht mehr — vorgeschlagen wird** (`ShiftCodeSuggester`). Die App ordnet NICHTS selbst zu.
- **`ShiftUseCase.add/update/deleteShiftDefinition` sind ENTFERNT** — sie speicherten, ohne die
  System-Alarme anzufassen. Einziger richtiger Weg: `ShiftViewModel.updateShiftConfig(config)`.
- **`withCodeAssignedTo()` macht DREI Dinge zusammen**: Muster ergänzen, Zieldefinition aktivieren,
  Kürzel bei allen anderen entfernen. Jedes einzeln wäre wirkungslos oder gefährlich.
- **Fünf bekannte `syncAlarms()`-Aufrufer**; ein sechster erbt das Master-Pause-Gating automatisch,
  **muss sich aber selbst um die Vollständigkeit seiner Eventliste kümmern**.

### Schicht-Dimmer & DND → `.claude/invarianten/dimmer-dnd.md`

- **Pro Kalendertag GENAU eine Regel**; eine spezifische Regel **überschreibt** UNIVERSAL komplett,
  nicht additiv. UNIVERSAL heißt „alle **Tage**", nicht „alle Schichten".
- **`findRuleForShift` nimmt den ERSTEN Treffer** — zwei Regeln auf demselben Muster: die zweite ist tot.
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
- **Modus 1 dupliziert KEINE Fenster-Logik** — er ruft `previewTimelineWithStatus()` direkt auf.
  Einbahnstraße: `dnd/` liest von `dimmer/`, nie umgekehrt. **Das `…WithStatus` ist kein Luxus**: der
  Lesefehler muss über die Grenze kommen, sonst plant DND den 6h-Keep-alive statt des 15-min-Retry.
- **Modus 2 braucht `AlarmInfo.shiftStartTime`**, nicht `triggerTime`.
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

### Hue → `.claude/invarianten/hue.md`, `.claude/invarianten/hue-vorschau.md`

- **Das Auto-Aus gehört der BRIDGE, nicht dem Handy** — als Bridge-Zeitplan im selben Atemzug mit dem
  Einschalten. Der frühere `AutoOffWorker` ist ersatzlos weg; kein Fallback nachbauen.
- **Timer (`PT00:30:00`), nicht absolute Zeit** — läuft auf der Uhr der Bridge, immun gegen Drift/DST.
- **Die V1-API antwortet auch bei ABLEHNUNG mit HTTP 200** — das Urteil steht im Body (`HueV1Envelope`).
  `parseAll` ist streng (GET-Endpunkte, Fehlerhülle wird VOR dem Parsen erkannt), `parseControl`
  bewusst milder („mindestens ein `success`"), und **jedes vorhandene `success`-Feld zählt, auch als
  String** (DELETE antwortet mit einem String). **Auch die Steuer-Endpunkte werten den Body aus.**
- **Ein Fehlschlag der Bridge darf nicht zur leeren Liste degradieren** (`getAllLightTargets()`).
- **`getBridgeConfig` prüft die Antwort** (`bridgeid` oder `mac`), statt sie nur zu deserialisieren —
  Gson erzwingt Kotlins Non-Null-Deklarationen NICHT.
- **Der gesamte Hue-Pfad ist IPv4-only**; mDNS wählt die erste IPv4-Adresse, Nur-IPv6 liefert `null`.
- **Die Subnetz-Prüfung ist ein HINWEIS auf das Timeout, NIEMALS ein Veto** — sie liefert
  Falsch-Negative bei Gast-WLAN, VLAN, Mesh, Doppel-NAT und am Emulator. **Beide** Aufrufstellen
  (Einrichtung und Cache-Pfad) sind so gebaut; der Verbindungszustand wird dabei nicht auf `ERROR`
  herabgestuft.
- **`healthCheckScope` braucht einen `CoroutineExceptionHandler`** — ein `SupervisorJob` isoliert nur
  Geschwister, die Exception beendet trotzdem den PROZESS. Das `try/catch` des
  Netzwerk-Recovery-Collectors liegt **INNERHALB** des `collect`.
- **`cleanup()` auf Prozess-Singletons cancelt NUR Kinder** (`cancelChildren()`), nie den Scope.
- **`HueBridgeConnectionManager.initialize()` muss idempotent bleiben** (zwei Aufrufer ohne feste
  Reihenfolge).
- **Eine im Direct-Boot übersprungene Hue-Planung wird NACHGEHOLT**
  (`retrySkippedSchedulingIfNeeded()`, zwei Aufrufstellen).
- **`HueSmartScheduler.getInstance()` veröffentlicht `INSTANCE` erst NACH `initialize()`.**
- **„Bridge eingerichtet" und „Bridge verbunden" sind zwei Fragen** — der Scheduler prüft
  `hasStoredBridge()` (persistierter Wert), nicht `getCurrentConnectionInfo()`.
- **`findApplicableRules` matcht exakten Definitionsnamen ODER `UNIVERSAL_SHIFT_PATTERN`** — kein
  Keyword, kein Teiltreffer. **`autoOffTargetsOf()` filtert bewusst NICHT nach Schichtnamen**
  (sonst verlören UNIVERSAL-Regeln ihr Auto-Aus).
- **`UNIVERSAL_SHIFT_PATTERN = "ALL"` ist über den Regel-Editor erreichbar**; UI referenziert die
  Konstante, der Rücklesepfad nutzt denselben Maßstab (`ignoreCase`), Anzeige sagt „Alle Schichten".
  Das Matching selbst bleibt unverändert.
- **`HueLightAction.targetId` ist BRIDGE-LOKAL — der Anker über Geräte hinweg ist `targetName`.**
  Bei Mehrdeutigkeit lieber nicht zuordnen als falsch; Lampen- und Gruppen-Namensraum bleiben
  getrennt; eine gescheiterte Abfrage ändert und meldet NICHTS (`lightsFailed`/`groupsFailed`).
  Zurückschreiben INNERHALB einer `dataStore.edit{}`-Transaktion, nicht bei offenem Editor, und
  NICHT im Weckpfad.
- **`Result<RuleValidationResult>` hat zwei Ebenen** — `isFailure` heißt „Prüfung gescheitert", nicht
  „Regel ungültig".
- **`MIN_RULE_NAME_LENGTH = 1`**, nicht mehr (eine reale Regel heißt „FS").
- **Das 90-Zeichen-Limit für `command` aus der offiziellen Doku greift nicht** (gemessen gegen BSB002).
- **Auf der Bridge liegen fremde Zeitpläne** — aufgeräumt wird ausschließlich, was
  `BridgeTimer.NAME_PREFIX` trägt. Und aufgeräumt werden **muss**.
- **Die Regel-Vorschau räumt IMMER auf**, unabhängig vom Auto-Aus der Regel — anders als der echte
  Weckvorgang. Beim Sonnenaufgang hängt das Aus HINTER der Rampe.
- **`runLightTest()` blitzt LAMPEN, niemals Gruppen** (Gruppen überschneiden sich beliebig, auch
  untereinander). `flashLight(lightId)` nimmt deshalb bewusst kein `isGroup`-Flag mehr.
- **`flashLight` nutzt `lselect` und bricht es nach 4 s selbst ab** (`alert:"none"`).
- **Abbruch-Timer und Vorschau-Auto-Aus hängen an `followUpScope`, nicht am Aufrufer.**
- **Nichts in `runLightTest()` darf `refreshLightTargets()` benutzen** — das ist fire-and-forget.
  Wer Ziele braucht, ruft `getAllLightTargets()` und **wartet**.
- **Regel speichern navigiert sofort weg** — ein Fehler landet dadurch auf dem `HueSettingsScreen`
  statt im Formular (bekannt, bisher unkritisch).

### Persistenz → `.claude/invarianten/persistenz.md`

- **Die REIHENFOLGE von `.catch` und `.map` in einem Preferences-Flow ist tragend.** `.catch` gehört
  **hinter** das `.map` und muss zusätzlich den Cache invalidieren — sonst wird „Store nicht lesbar"
  von „noch nie konfiguriert" ununterscheidbar und die Standardkonfiguration gilt als Erfolg.
- **Stille Degradierung darf nie zur Schreibwahrheit werden.** DataStore liest vor jedem Write erneut.
  - **`AlarmRepository`**: unlesbar/undekodierbar → Persistenz für diesen Prozess sperren, Roh-JSON
    unter `active_alarms_broken` sichern. Bereit-Signal (`CompletableDeferred`) + gemeinsamer Mutex
    für alle Ganzlisten-Schreibpfade. `clearInternalAlarms` liest über `getAllAlarms()`.
    `deleteAllAlarms()` räumt bewusst trotzdem (force).
  - **`EncryptedPreferencesSerializer.readFrom()`** wirft weiter, statt `defaultValue` zu liefern —
    und deutet den Fehler **nicht** als `CorruptionException` um. `writeTo()` schreibt einen leeren
    Zustand als 0-Byte-Datei.
  - **`DimRuleRepository`**: `coerceInputValues` gilt für die ANZEIGE, `editRules()` liest **strikt**;
    `upsert`/`delete` als Read-Modify-Write INNERHALB einer `dataStore.edit{}`-Transaktion.
- **Ein CE-DataStore-Read VOR der ersten Entsperrung wirft NICHT — er liefert still leere
  Preferences.** Deshalb fragt `AlarmRepository` VOR dem Read den `UserManager`, akzeptiert bei
  gesperrtem Nutzer KEIN Ergebnis und lädt beim ersten Zugriff nach dem Entsperren nach —
  aufgehängt an `awaitInitialLoad()` **und** `onStart` am `activeAlarms`-Flow.
- **Der Direct-Boot-Spiegel wird bei JEDEM erfolgreichen Load abgeglichen** (`saveAll` ist idempotent).
- **Die Reads der Onboarding-/Gate-Kette gehen über `readOrEmpty()`** und degradieren auf „NICHT
  abgelehnt" — im Zweifel wird der Hinweis GEZEIGT.
- **Bei der Master-Pause ist die RICHTUNG der Degradation die Entscheidung**: auf `false` = NICHT
  pausiert. Ein fälschlich klingelnder Wecker ist abstellbar, ein stummer fällt beim Verschlafen auf.
  Der Fehler wird geloggt.
- **`DimOverlayPrefs` schützt alle Lese-Flows über EINEN gemeinsamen `safeData`-Quell-Flow**;
  Degradation heißt `overlayOn = false` — **im Zweifel NICHT verdunkeln**.
- **`TinkEncryptionException` wird als `CorruptionException` übersetzt** (nur die fängt DataStores
  Selbstheilung) — eine Neuanmeldung ist besser als ein dauerhaft schreib-toter Token-Store.

### Gerätewechsel & Konfigurations-Datei → `.claude/invarianten/geraetewechsel.md`

- **`DeviceLocalFlagsGuard` setzt beim erkannten Gerätewechsel gerätelokale Flags zurück**
  (`Build.FINGERPRINT`). Ein selektiver Backup-Ausschluss ist unmöglich — ein Preferences-Store ist
  EINE Datei. Fehlt der Marker, wird NICHT zurückgesetzt. Die beiden Backup-Regel-Dateien müssen
  inhaltlich identisch bleiben.
- **Eine mitgesicherte Master-Pause wird über `resume()` aufgehoben, NICHT durch Löschen des
  Schlüssels** — `master_pause_enabled` steht deshalb bewusst NICHT in `DEVICE_LOCAL_KEY_PATTERNS`.
- **Der Konfigurations-Export entscheidet durch AUSSCHLUSS, nicht durch Aufzählen**
  (`ConfigBackupFilter`, `exclusionReason()` ist der EINE Ort). Drei Ausschlussgründe:
  Laufzeitzustand, Gerätebezug/Zugangsdaten, gerätelokale Onboarding-Markierungen. **Der Filter gilt
  in BEIDE Richtungen**, abgelehnte Schlüssel werden BENANNT. Die Liste stammt aus einer Inventur
  ALLER `*PreferencesKey("…")` im Baum.
- **Der Import lehnt eine LEERE Definitionsliste ab** (kotlinx.serialization füllt still `emptyList()`).
- **Der erwartete TYP kommt vom SCHLÜSSEL, nicht aus der Datei** — ein falsch typisierter Wert liegt
  reboot-fest und wirft bei jedem Lesen, bevor ein Default greifen kann.
- **Der Schlüssel-Filter sagt nichts über den WERT**: `snooze_minutes` und `dnd_oncall_cutoff_min`
  sind zusätzlich bereichsgeprüft — **und zusätzlich im LESEPFAD geklemmt** (Android-Backup ist ein
  zweiter Weg, den der Import nie sieht).
- **Unlesbare Regelwerke werden beim Import BENANNT abgelehnt**, statt still auf „leere Liste" zu fallen.

### Auth → `.claude/invarianten/auth.md`

- **Kein `getOrElse { emptyList() }` auf Auth-behafteten Ergebnissen.**
- **GMS-Token-Cache liegt außerhalb des App-Speichers** — nur `GoogleAuthUtil.clearToken()` räumt ihn ab.
- **`auth_prefs` braucht `corruptionHandler` UND `.catch{}`**; Degradation auf „nicht angemeldet".
- **`onResult` gehört `OAuth2TokenManager.authorize()`** — es feuert auf jedem Weg genau einmal.
- **`observeTokenLoss()` nimmt nur das NEGATIVE Signal**; `drop(1)` ist Pflicht.
- **`signOutInProgress` nicht wegoptimieren** — `isSignedIn` allein reicht nicht.
- **Abmelden heißt: nichts bleibt zurück** (Auth-Daten UND Token inkl. GMS-Cache).
- **Eine frische Neu-Autorisierung ist KEIN Kettenbruch** (`isLegitimateSuccessorOf`: identisch,
  direkt rotiert, oder per `authorize()` geholt). Der Diebstahls-Zweig bleibt für ältere Tokens.
- **`refresh()` prüft den NEUEN Token gegen die ID des ALTEN**
  (`storedToken.validateRotation(currentToken.rotationId)`) — die vertauschte Variante schlägt bei
  jeder legitimen gleichzeitigen Rotation fehl und erzwingt einen Re-Login.
- **`DataStoreTokenRepository.observe()` nutzt `retryWhen`, und der Fehlerfall emittiert NICHTS** —
  kein Signal statt falschem Signal.

### Fehlerbehandlung → `.claude/invarianten/fehlerbehandlung.md`

- **`SafeExecutor.safeExecute()` wirft `CancellationException` WEITER**, statt sie in einen `AppError`
  zu verpacken — sonst laufen nachgelagerte `catch (e: CancellationException)`-Zweige ins Leere.
- **„Kein Token vorhanden" landet als `UnknownError` im Log — und das umzubiegen wäre ein Fehler.**
  Eine Abbildung auf `AuthenticationError` würde `invalidateTokenIfRejectedByGoogle()` (nur für 401
  gedacht) ohne Anlass den GMS-Cache leeren lassen. Das Verhalten selbst ist korrekt.

### Navigation → `.claude/invarianten/navigation.md`

- **Zurück gehört dem `BackHandler` in `MainScreen`** — es gibt keinen Backstack, ohne Handler
  beendet jeder Druck die App. Wer einen neuen `NavigationState` ergänzt, muss ihn dort mitbedenken.
- **VIER Gates sind nicht optional**: `BatteryExemption`, `UnusedAppRestrictions` und
  `TimeOfficeHealthCheck` müssen ihr Dismissed-Flag schreiben, `OEMWarning` muss `finishOnboarding()`
  anstoßen. Auf dem Home-Tab bleibt der Handler bewusst aus.
- **„Später" beim Akku-Gate heißt ERLEDIGT, nicht abgebrochen** (`batteryGateResolved`) — sonst fällt
  der Nutzer aus jedem Zweig heraus und bekommt den nächsten Schritt NIE angeboten.
- **`HueRuleConfig`/`DimmerRuleConfig` brauchen `cameFromSettingsList`**, nicht nur `returnToTab`:
  System-Back und screen-eigener Zurück-Pfeil MÜSSEN für denselben Einstiegspfad zum selben Ziel führen.

### TimeOffice → `.claude/invarianten/timeoffice.md`

- **CFAlarms gesamte Funktion hängt an einer Kette außerhalb dieser App** — TimeOffice
  (`de.pradtke.timeoffice`) schreibt den Dienstplan in einen eigenen Google-Kalender.
- **Es gibt KEINE öffentliche API für den Hibernation-Status einer ANDEREN App.** Nur die
  Akku-Ausnahme ist für fremde Pakete prüfbar. Die Karte täuscht deshalb keinen Status vor.
- **Kein `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`-Dialog für TimeOffice** — immer der Weg über
  dessen App-Info-Seite.
- **`<queries>` im Manifest ist Pflicht** für `isInstalled()` (Package-Visibility ab Android 11).
- **Kein Unit-Test für `TimeOfficeHealthHelper`** — bewusst, gleiche Konvention wie die
  Schwester-Helper (dünne Wrapper ohne eigene Logik).
- **Das Onboarding-Gate hängt an `handleAuthenticationSuccess()`, nicht nur an `proceedPastGates()`** —
  sonst sehen Bestandsnutzer den Prompt nie automatisch.

### UI → `.claude/invarianten/ui.md`

- **Der Akku-Onboarding-Screen darf keine Einstellungen versprechen** — es erscheint Androids
  Systemdialog, keine Liste. Ablauf und Text ändern sich gemeinsam.
- **Ein Hinweistext nennt Karten- und Knopfbeschriftung wortgleich, NIE eine Position.**
- **Beispiele in Hinweistexten aus deklarierten Listen zusammenführen**, die ein Test gegen die echte
  Standardkonfiguration prüft — Drift muss auffallen.
- **Kein Text darf eine Anzeige behaupten, die es nicht gibt, und kein Zustand darf sich als anderer
  ausgeben.** Insbesondere: **eine deaktivierte Schichtdefinition darf keine Weckzeit anzeigen.**
- **Deutsche Nutzer-Texte in `UITextConstants` ohne Aufrufer löschen, nicht liegen lassen.**
- **`Row(SpaceBetween) { Column { … }; Switch }` braucht `weight(1f)` am Column.**
- **`ButtonDefaults.ContentPadding` = 24dp pro Seite** — für schmale, geteilte Buttons gibt es
  `CompactButton`/`CompactOutlinedButton`.
- **Eine `LazyColumn` in einer `Column` braucht `weight(1f)`** — sonst wird der Knopf darunter
  unerreichbar (real passiert). Gleiches gilt für lange Dialoge: scrollbar machen.
- **`RadioButton`/`Checkbox` mit `onClick = null` brauchen `heightIn(min = 48.dp)` am Row.**
- **Chip-Reihen als `FlowRow`**, nicht `Row` mit `chunked(n)`.

---

## Umgebung / Arbeitsweise → `.claude/invarianten/umgebung.md`

- **Gradle UND der Emulator sind erreichbar.** `--offline` nutzen (Cache ist warm), außer bei
  `assembleRelease`/`connectedDebugAndroidTest`. Selbst bauen, installieren, messen, A/B-testen statt
  nur durch Inspektion zu verifizieren. `emulator`-Binary:
  `C:\Users\Christoph\AppData\Local\Android\Sdk\emulator\emulator.exe`.
- **„Warnungen plötzlich weg" ist kein Fortschritt** — der Konfigurations-Cache
  (`org.gradle.configuration-cache=true`) verschluckt sie. Nach Änderungen an
  `build.gradle.kts`/`gradle.properties` sind sie wieder da.
- **Built-in Kotlin ist migriert** (v1.24.x): `kotlin.android`-Plugin raus, `builtInKotlin=true`,
  `newDsl=true`; ein enger Bypass bleibt (`android.disallowKotlinSourceSets=false`, wegen KSP).
  Wer die Flags anfasst, misst nach, statt dieser Zeile zu glauben.
- **Debug-Build schreibt VERBOSE ins Datei-Log; Release-Logs enthalten nur WARN+** → für Diagnose
  immer einen Debug-Build verlangen. Ausnahme: die Vollbild-Sichtbarkeits-Diagnostik loggt WARN.
- **Die CI baut auch den Release-Pfad** (`lintVitalRelease` + `assembleRelease`) — dort sitzt mit R8
  das Risiko. Ohne Keystore-Secret entsteht eine unsignierte APK (Absicht, nicht auslieferbar).
- **Grüne Unit-Tests sind kein Startbeweis.** Dafür gibt es `ColdStartSmokeTest` (drei Fälle, echter
  Hilt-Graph) und `AlarmFullScreenSmokeTest` (startet über den SERVICE-Zustand, nicht als nackte
  Activity — sonst misst er nur, wie schnell sich die Activity beendet; spielt echten Weckton).
  Ersetzt trotzdem keinen Gerätetest.
- **Agenten committen nicht selbst in einen gemeinsamen Baum** — entweder committet der Orchestrator
  einmal am Ende, oder jeder Agent bekommt einen eigenen `git worktree`.
- Debug-SHA-1 ist in der Google Cloud Console eingetragen. Getestet wird auf einem echten Gerät
  **und** einem Emulator; Logcat-Auszüge kommen vom Nutzer.
