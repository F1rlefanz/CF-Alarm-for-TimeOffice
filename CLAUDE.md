# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Git & GitHub Workflow

Folgt dem globalen Default aus `~/.claude/CLAUDE.md` (Branch pro Änderung, proaktiv committen/mergen/pushen, nur bei irreversiblen Operationen nachfragen). Projekt-spezifische Abweichungen davon:

- Branch-Präfixe: `feature/<kebab-case>`, `fix/<kebab-case>`, `chore/<kebab-case>`.
- Commit-Trailer für dieses Projekt: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>` (weicht vom Session-Default ab, bewusst so beibehalten).
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

Tab-based navigation via `NavigationViewModel` and `MainTab` enum. `MainScreen` is the Compose root; it receives all ViewModels and delegates to tab content composables (`HomeTabContent`, `HueTabContent`, `SettingsTabContent`, `StatusTabContent`).

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