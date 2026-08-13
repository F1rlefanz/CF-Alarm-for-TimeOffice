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

# Release build (R8/Minify AN; braucht NETZ, siehe unten)
./gradlew assembleRelease

# Run all unit tests
./gradlew test

# Run a single test class (NICHT `test --tests` - das Aggregat kennt die Option nicht)
./gradlew testDebugUnitTest --tests "com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.AlarmSchedulerTest"

# Lint check
./gradlew lint

# Install debug APK to connected device
./gradlew installDebug

# Instrumentation-Tests (ColdStartSmokeTest) - NICHT mit --offline, siehe unten
./gradlew connectedDebugAndroidTest
```

- **`assembleRelease` braucht Netz.** Die nur mit Minify laufende Task
  `produceReleaseComposeMapping` zieht eine Abhängigkeit, die nicht im Offline-Cache liegt;
  `--offline` scheitert mit dem irreführenden „Configuration cache state could not be cached".
  Signiert wird nur, wenn `keystore.properties` ODER `KEYSTORE_PASSWORD` da ist — sonst entsteht
  `app-release-unsigned.apk` (Absicht, siehe CI-Punkt unter „Umgebung / Arbeitsweise").
- **`connectedDebugAndroidTest` läuft NICHT mit `--offline`** (UTP braucht
  `android-test-plugin-host-additional-test-output`, nicht im Cache), das Gerät MUSS wach sein
  (bei dunklem Bildschirm bleibt die Activity bei CREATED — kein App-Bug), und die Task
  **deinstalliert die App danach**: ein von Hand eingerichteter Emulator-Zustand (Anmeldung,
  Kalenderauswahl) ist hinterher weg.

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
- **DataModule** – provides exactly **two** unverschlüsselte `DataStore<Preferences>` (`@MainDataStore` = `settings`, `@HueDataStore` = `hue_settings`, beide mit `ReplaceFileCorruptionHandler`) plus `ErrorHandler`. **Kein Token-Store und kein `TinkEncryptionHelper`** — Details unter „Authentication & Token Storage"
- **RepositoryModule** – binds repository interfaces to implementations
- **UseCaseModule** – binds use-case interfaces to implementations
- **HueModule** – OkHttp/Retrofit clients for Philips Hue API
- **ServiceModule** – `CredentialAuthManager`, `OAuth2TokenManager`, `AlarmManagerService`, `ShiftRecognitionEngine` (genau vier Provider). `BackgroundServiceManager` steht bewusst **nicht** hier: er hat seinen eigenen `@Singleton @Inject`-Konstruktor. `WakeLockManager`/`IWakeLockManager` standen hier bis v1.23.1 und sind ENTFERNT — siehe „Bekannt und so gewollt"
- **StateModule** – `CalendarStateHolder` (shared state between ViewModels)

All Android components (`Service`, `BroadcastReceiver`) that need injection are annotated `@AndroidEntryPoint`.

### Authentication & Token Storage

`auth/` contains the OAuth2 flow:
- **`CredentialAuthManager`** – Google Sign-In via `androidx.credentials`
- **`OAuth2TokenManager`** – fetches/refreshes tokens via `GoogleAuthUtil`, delegates storage to `TokenRepository`
- **`DataStoreTokenRepository`** – persists tokens encrypted with **Google Tink** (AES-256-GCM) via `TinkEncryptionHelper`
- Der Token liegt im DataStore **`token_data_v2_encrypted`**, den sich `DataStoreTokenRepository`
  per `EncryptedDataStoreFactory` **selbst** baut (nur `@ApplicationContext` injiziert).
  `TinkEncryptionHelper` muss ein Singleton bleiben, und **`getInstance()` in
  `EncryptedDataStoreFactory` ist der einzige Zugriffsweg**: der frühere Hilt-Provider in
  `DataModule` war ungenutzt und hätte den Keyset-Read aus dem CE-Storage in fremde DI-Graphen
  gezogen — inklusive der `directBootAware`-Komponenten, wo die Feldinjektion vor der ersten
  Entsperrung geworfen und den Wecker stumm gelassen hätte. Nicht zurückholen.
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

Tab-based navigation via `NavigationViewModel` and `MainTab` enum (`HOME, WECKER, STATUS, SETTINGS, HUE, DIMMER`). `MainScreen` is the Compose root; es verzweigt die `NavigationState`s (Unterscreens, Onboarding-Gates) und hält den `BackHandler`. Die Tab-Inhalte verteilt `MainContentScreen` auf `HomeTabContent`, `WeckerTabContent`, `StatusTabContent`, `SettingsTabContent`, `HueTabContent`, `DimmerTabContent`.

### Shared State

`di/state/CalendarStateHolder` – a Hilt singleton `StateFlow` holder: `CalendarViewModel` **schreibt**, `ShiftViewModel` **liest** (Einbahnstraße, siehe „Kalender-Datenfluss"). Vermeidet direkte ViewModel-zu-ViewModel-Abhängigkeiten.

## Key Constraints

- The `AD_ID` permission is explicitly blocked (`maxSdkVersion="0"`) — do not re-enable it
- `USE_EXACT_ALARM` and `USE_FULL_SCREEN_INTENT` are core permissions; the app cannot function without them
- Die `DataStore`-Namespaces bleiben getrennt (settings / hue / tokens) — nie zusammenlegen. Nur
  die ersten beiden kommen aus `DataModule`; der Token-Store ist der selbstgebaute
  verschluesselte `token_data_v2_encrypted` (siehe Authentication & Token Storage)
- `TinkEncryptionHelper.getInstance()` must stay a singleton — reinitializing it would break decryption of existing tokens
- **Jeder `preferencesDataStore` in diesem Projekt braucht einen `corruptionHandler`.** DataStore
  liest VOR JEDEM Write erneut — ohne Handler blockiert eine beschädigte `preferences_pb` nicht nur
  Lesen, sondern dauerhaft auch Schreiben, reboot-fest und ohne Selbstheilung außer „App-Daten
  löschen". Betroffen wären u. a. Alarme, Master-Pause, Dimmer-/DND-Konfiguration und der
  Anmeldezustand (`auth_prefs` war der letzte Store ohne Handler)
- **R8/Minify ist AN** (seit v1.23.0, `isMinifyEnabled = true` im Release-Buildtype; mit AGP 9.3.1 ohne den
  alten R8-NPE; APK 19,8 → 10,9 MB). Deshalb MÜSSEN `-dontshrink` und `-dontoptimize` in
  `proguard-rules.pro` aus bleiben — mit ihnen wäre Minify eine Attrappe, die Bauzeit kostet und
  nichts entfernt. Beide Zeilen stehen dort auskommentiert mit Begründung
- `minSdk = 26`, `compileSdk = 37`, `targetSdk = 37`; Java 17 source/target with core library desugaring enabled

---

## Grundregeln

- **Eine Funktion ohne Bedienoberfläche gibt es für den Nutzer nicht.** Wer eine Fähigkeit einbaut,
  baut die Stelle mit, an der man sie **sieht**, sie **auslöst** und ihren **Zustand abliest** —
  sonst ist sie totes Kapital, das bei jeder Prüfrunde Aufmerksamkeit kostet und niemandem nützt.
  Zwei reale Anlässe: das Auto-Backup wurde repariert, ohne dass der Nutzer je erfahren hätte, ob es
  greift (deshalb kam der sichtbare Export/Import dazu), und `UNIVERSAL_SHIFT_PATTERN` wurde vom
  Hue-UseCase seit v1.11.0 korrekt ausgewertet, war aber bis v1.24.0 über keine Oberfläche
  setzbar — ein funktionierender Codepfad, den niemand erreichen konnte.
- **Der Sweep über frisch geschriebenen Code beweist wenig.** Das Härtungsprogramm zu v1.23.0 hat
  seine Abbruchbedingung („zwei aufeinanderfolgende Runden ohne neue bestätigte Befunde") nie
  erfüllt — aber ein erheblicher Teil der Befunde lag jeweils in Code, den dieselbe Sitzung erst
  erzeugt hatte, und jeder Fix schafft neue Prüffläche. Aussagekräftig ist erst eine Runde über
  **unverändertem** Code. In v1.24.0 wurde das erstmals sauber getrennt: die drei Dimensionen über
  dem Sitzungs-Diff fanden NICHTS Bestätigtes (die Aufräumarbeit hat keine Regression eingebaut),
  die drei über unverändertem Code fanden 4 echte Befunde von 13 Rohbefunden. **Die
  Abbruchbedingung ist damit weiterhin NICHT erfüllt** — die nächste Runde muss wieder über
  unverändertem Code laufen.
- **Refutation-Voting ist kein Orakel — in beide Richtungen.** In derselben Runde wurden 9 von 13
  Befunden widerlegt, darunter drei sehr plausibel klingende (angeblich `deleteAlarm()` vor
  `cancelSystemAlarm()` im Delta-Sync; ein Alarm, der gespeichert, aber nie armiert wird; ein
  Boot-Zähler, der Erfolge behauptet) — alle drei zu Recht, mit Codebeleg. **Ein Fall wurde aber zu
  Unrecht widerlegt**: `DimmerRulesViewModel.previewRule()` ist zeichengleich derselbe Fehler wie
  der bestätigte in `DimmerViewModel.previewDim()`. Gefunden hat das nicht die Abstimmung, sondern
  der Fix-Agent des Zwillings. Ein „widerlegt" ist ein Hinweis, kein Freispruch — bei einem Befund,
  der ein bestätigtes Muster spiegelt, immer selbst am Code nachsehen.

## Bekannt und so gewollt

- **`res/mipmap-anydpi-v26` bleibt, obwohl Lint den `-v26`-Qualifier bei `minSdk 26` als
  überflüssig meldet (`ObsoleteSdkInt`).** Gemessen, nicht vermutet (v1.24.0): nach dem Umzug nach
  `mipmap-anydpi` meldet Lint **zwei `IconXmlAndPng`-WARNUNGEN** — im qualifierlosen Bucket verdeckt
  die Adaptive-Icon-XML die `ic_launcher*.webp` der Dichte-Ordner. Der Umzug tauscht also einen
  kosmetischen Hinweis gegen zwei Warnungen und weicht zusätzlich von der
  Android-Studio-Standardstruktur ab. Die verdeckten Bitmaps stattdessen zu löschen wäre ein
  sichtbares Risiko am App-Icon ohne Gegenwert. Der eine verbleibende Hinweis ist Absicht.
- **Zwei Scopes rufen bewusst NIE `.cancel()`** — beide sind seit v1.24.0 an Ort und Stelle
  begründet, weil jede Prüfrunde sie erneut als „vergessenes Aufräumen" gemeldet hat:
  `AlarmReceiver.receiverScope` (das System erzeugt pro Broadcast eine frische Receiver-Instanz,
  und die Arbeit MUSS `onReceive()` überleben — dafür steht `goAsync()` darüber; ein `cancel()`
  würde Ton-Start, Skip-Prüfung und Hue-Regeln mitten im Lauf abschneiden) und
  `CalendarSelectionRepository.repositoryScope` (`@Singleton` mit Prozess-Lebensdauer; ein
  `cancel()` wäre endgültig und legte den `retryWhen`-Collector still, der die einzige
  Verteidigung gegen den Direct-Boot-Fall ist — dieselbe Fehlerklasse wie
  `HueBridgeConnectionManager.cleanup()`). Gegenstück: `HueLightUseCase.followUpScope` ist
  ebenfalls Absicht, dort wäre ein `.cancel()` die Regression.

- **`Logger.business()` loggt auf INFO** → PII (E-Mail, Kalendertitel) landet in Debug-Builds im
  Datei-Log (`Logger.business`, `util/Logger.kt`). Bewusst: Release-Logs enthalten nur WARN+.
- **Regel speichern navigiert sofort weg** (`HueRuleConfigScreen`: `createRule()` ist
  fire-and-forget, `onSaveComplete()` folgt unmittelbar). Ein Fehler landet dadurch erst auf dem
  `HueSettingsScreen` statt im Formular. Seit v1.10.4 kann die Validierung tatsächlich ablehnen —
  bisher nur theoretisch, weil die UI-Validierung dieselben Bedingungen vorher abfängt. Wird das
  je unangenehm: auf das Result warten, bevor navigiert wird.
- **Eine defekte Schicht-Konfiguration erfährt der Nutzer nur über das Log.** Die Rohdaten liegen als
  `shift_config_broken` gesichert, der Sync wird ausgelassen, bestehende Alarme bleiben — aber ein
  sichtbarer Hinweis samt Angebot, die Sicherung zu verwerfen, fehlt noch. Bewusst offengelassen.
- **`WakeLockManager`/`IWakeLockManager` sind ENTFERNT** (v1.23.1, Klasse + Interface + der ungenutzte
  Konstruktor-Parameter von `AlarmManagerService` + zwei Provider in `ServiceModule`). Sie hatten
  keinen Aufrufer; die Wake-Locks des echten Weckvorgangs liegen direkt in `AlarmReceiver` (PARTIAL,
  um den Broadcast zu überleben) und `AlarmFullScreenActivity` (SCREEN_BRIGHT). Wer einem
  Wake-Lock-Verdacht nachgeht, muss DORT suchen — vorher stand hier eine Klasse, die wie der
  zuständige Ort aussah und am Laufzeitverhalten nichts änderte.
- **Eine Instanz besitzt den Wecker**: `AlarmSoundService` hält Ton, Vibration, Audio-Fokus und
  die einzige **Wecker**-Notification (ID 2002). Channel **stumm**, aber `IMPORTANCE_HIGH` (Pflicht
  für Full-Screen-Intent). Der `AlarmReceiver` darf **keine eigene Wecker-Notification** posten
  (die frühere ID 2001 brachte über ihren Channel einen zweiten Klingelton mit). Ausdrücklich
  ausgenommen: die stille Skip-Bestätigung `AlarmReceiver.showSkipNotification()` (eigener
  `SKIP_CHANNEL_ID`, `IMPORTANCE_LOW`, ID 9999, `setTimeoutAfter`) — kein Ton, kein Vollbild.
- **`AlarmSoundService`: `stopSelf(startId)` und `START_REDELIVER_INTENT`.** Blankes `stopSelf()`
  räumt bei zwei überlappenden Alarmen den gerade gestarteten mit ab; `START_STICKY` startet den
  Service mit `intent == null` neu, und daraus kann der `else`-Zweig nichts wiederherstellen — ein
  stummer Zombie-Service, während das Log wie ein funktionierender Wecker aussieht. Der Weckton
  probiert außerdem **alle** Ringtone-Kandidaten der Reihe nach und loggt einen Totalausfall laut
  (Direct Boot kann MediaStore-URIs unauflösbar machen).
- **Vollbild: Dismiss und Snooze teilen eine Einweg-Sperre (`OneShotAlarmHandoff.claim()`, am Anfang
  BEIDER Handler).** Belegt aus dem Gerätelog (05.08.2026): „dismissed" und „snoozed" 24 ms
  auseinander, beide Handler komplett durchgelaufen — Compose gibt jedem gleichzeitigen Zeiger seinen
  eigenen Klick, und die zwei bildschirmbreiten Knöpfe liegen 12 dp übereinander; „Alarm stoppen"
  plante also zusätzlich einen Schlummer-Wecker. Der Notausgang `stopAndClose()` fragt die Sperre
  bewusst NICHT (sonst blockiert sie den Fehlerpfad des Snooze). `AlarmFullScreenHandoffTest`.
- **`AlarmFullScreenActivity` braucht `onNewIntent()` mit `setIntent()`.** `launchMode="singleTask"`
  liefert eine zweite Zustellung desselben Full-Screen-Intents als `onNewIntent`, nicht als
  `onCreate` — ohne Überschreiben las `snoozeAlarm()` Schicht/ID/Snooze-Dauer aus dem VORHERIGEN Alarm.
- **`visibilitySnapshot()` ist Diagnostik, die im Release-Log landen MUSS.** Sie protokolliert
  `interactive`/`display`/`keyguardLocked`/`deviceSecure`/`wakeLockHeld` an `onCreate`/`onStart`/
  `onStop` und bei jedem Fensterfokus-Wechsel — stoppt die Activity, während der Wecker läuft, als
  **WARN** (Release-Logs enthalten nur WARN+). Das verschwindende Vollbild (05.08.2026, `STOPPED`
  276 ms nach `initialized`, Wecker klingelte 11 s weiter) ließ sich aus dem Log nicht von
  „Bildschirm aus" unterscheiden und ist am Emulator in drei Läufen nicht reproduzierbar — deshalb
  bewusst kein Fix ins Blaue. Auf DEBUG herunterstufen macht den nächsten Vorfall wieder unauswertbar.
- **Alle drei `setAlarmClock()`-Aufrufstellen behandeln eine entzogene Exact-Alarm-Berechtigung gleich
  (`AlarmManagerService.setExactOrInexact`): try/catch + inexakter Fallback.** `setAlarmClock()` ist
  NICHT davon ausgenommen (der alte KDoc behauptete das) und wirft auf API 31/32 ohne
  `SCHEDULE_EXACT_ALARM` eine `SecurityException` — ungefangen aus dem Notification-Snooze-Button riss
  das den ganzen Prozess mit. Ein verzögerter Wecker schlägt keinen Wecker; der Schicht-Wecker fiel
  vorher komplett aus, während UI und Repository „Alarme aktiv" zeigten.
  `requestExactAlarmPermission()` gehört NICHT in diesen Pfad — aus 6h-Wartung/Worker kann der
  Systemdialog wegen Background-Activity-Start gar nicht erscheinen.
- **Ein schwebender Snooze ist abbrechbar (`cancelSnooze`/`cancelAllSnoozes`) — aber nur auf
  ausdrücklichen Nutzer-Willen.** `cancelSystemAlarm()` baut ausschließlich `enhancedAlarmAction` und
  trifft den eigenen Snooze-Slot nie; ein Snooze lief dadurch durch Master-Pause, „Automatische Alarme
  aus" und `deleteAllAlarms` hindurch. Dazu ein Merker der Snooze-IDs im device-protected Storage
  (sonst sind sie nirgends persistiert). Bewusst NICHT in datengetriebenen Aufräumzweigen und nicht an
  `deleteAlarm(id)`: dort räumt der `BootReceiver` abgelaufene Alarme weg — und der Ursprungsalarm
  eines schwebenden Snooze IST abgelaufen.
- **Blockierte Benachrichtigungen sind ein WECKER OHNE OBERFLAECHE — und das muss sowohl sichtbar
  als auch im Log auswertbar sein.** Sind Benachrichtigungen fuer die App aus, laeuft
  `AlarmSoundService` weiter (Ton, Vibration), aber seine Notification wird unterdrueckt UND der
  Full-Screen-Intent abgelehnt: kein Weck-Bildschirm, keine Stopp-/Schlummer-Knoepfe, einziger
  Ausweg „App beenden" in den Systemeinstellungen. Am Emulator im echten Zustand gesehen
  (11.08.2026). Der Zustand entsteht ohne Zutun, wenn der Nutzer die EINMALIGE Abfrage ablehnt
  (`MainActivity.checkNotificationPermission()`, `LaunchedEffect` beim ersten Erreichen des
  Hauptbereichs) oder die Berechtigung spaeter entzieht — danach fragt die App nie wieder. Deshalb
  zwei Dinge: die Status-Karte `NotificationsEnabledCard` steht **VOR** `FullScreenIntentCard`
  (ohne Benachrichtigungen ist deren Aussage bedeutungslos) und fuehrt per
  `ACTION_APP_NOTIFICATION_SETTINGS` in die Einstellung — die Laufzeit-Abfrage zeigt Android nach
  einer Ablehnung gar nicht mehr; und `AlarmSoundService` loggt direkt nach `startForeground()` ein
  **WARN** (Release-Logs enthalten nur WARN+), sonst ist der Fall im Log von einem funktionierenden
  Wecker nicht zu unterscheiden.
- **Löschen heißt IMMER: erst `cancelSystemAlarm()`, dann `deleteAlarm()`.** Der Delta-Sync tat es
  umgekehrt — und damit gab es ein Fenster, in dem der Alarm im AlarmManager noch armiert war, aber
  weder Repository noch Direct-Boot-Spiegel ihn kannten. ALLE Cancel-Wege der App iterieren über den
  Repository-Bestand; es gibt keinen zweiten Anker. Bricht die Sequenz dort ab (Prozess-Tod,
  DataStore-Fehler), ist der Wecker unsichtbar UND unabbrechbar — er feuert bis zum nächsten
  Geräte-Neustart, und ein Handy läuft Wochen.
- **Der `isPersistenceBlocked()`-Wächter in `clearInternalAlarms()` unterscheidet, WARUM geräumt
  wird.** Im datengetriebenen Zweig wird nichts angefasst und laut gescheitert (Räumen ohne
  Cancellen ist die gefährliche Kombination). Bei einer AUSDRÜCKLICHEN Abschaltung (Master-Pause,
  „Automatische Alarme aus") laufen dagegen die zwei Schritte weiter, die den unlesbaren Bestand
  gar nicht brauchen: `cancelAllSnoozes()` (eigener Merker im Device-Protected-Storage) und
  `deleteAllAlarms()` (die dokumentierte `force`-Ausnahme — ohne sie re-armt der
  Direct-Boot-Restore genau die Alarme, die gerade abgeschaltet wurden). Der erste Wurf des
  Wächters stand vor allem und machte damit einen schwebenden Snooze wieder unkündbar: die App
  zeigte „pausiert", während der Schlummer-Alarm scharf blieb.
- **Ein manueller Alarm lässt sich nicht anlegen, während „Automatische Alarme" aus ist.** Der
  Schalter ist eine echte Pause, die ALLE Alarme räumt — auch manuelle (so entschieden, testlich
  festgeschrieben). Ohne die Ablehnung bekam der Nutzer eine Erfolgsmeldung für einen Wecker, den der
  nächste `syncAlarms()`-Lauf ohne Rückmeldung wieder löscht. Bei der Master-Pause war dieser
  Widerspruch längst geschlossen, beim Schwester-Schalter nicht.
- **Der Snooze-Merker ist serialisiert (`snoozeRegistryLock`) und schreibt mit `commit()`.** Drei
  unabhängige Read-Modify-Write-Pfade (Vormerken, Vergessen, Writeback des Boot-Restores) sind bei
  jedem Boot nebenläufig erreichbar; ein verlorener Eintrag heißt: der Snooze ist im AlarmManager
  scharf, aber die App kennt ihn nicht mehr — weder abbrechbar noch nach einem Reboot
  wiederherstellbar. `apply()` schreibt asynchron und verlöre denselben Eintrag bei einem
  Prozess-Tod unmittelbar danach. `armSnooze()` gibt seinen Erfolg zurück, und
  `restorePendingSnoozes()` zählt ECHTE Erfolge — vorher behauptete das Boot-Log eine
  Wiederherstellung, die nicht stattgefunden hatte.
- **Die datengetriebenen Räumzweige von `syncAlarms()` schonen MANUELLE Alarme**
  (`clearInternalAlarms(keepManualAlarms = true)` bei „keine Events" und „keine passende Schicht").
  Der Delta-Sync tat das immer (`eventId.isNotEmpty()`), die beiden Abkürzungs-Zweige davor
  umgingen die Zusicherung komplett und riefen ein pauschales `clearInternalAlarms()`. Ausgerechnet
  der manuelle Alarm ist der EINZIGE, der sich nicht aus dem Kalender rekonstruieren lässt: er kam
  nie wieder, und im Log stand „No matching shifts found - clearing all alarms", was wie
  Normalbetrieb klingt. Realer Ablauf: Urlaubswoche ohne Schicht-Treffer, Wecker für einen
  Arzttermin von Hand gestellt, App geöffnet — Wecker weg. **Ausdrückliche** Abschaltungen
  (Master-Pause, „Automatische Alarme aus", `deleteAllAlarms`) räumen weiter ALLES: dort will der
  Nutzer Stille, und der Direct-Boot-Spiegel muss wirklich leer werden. Drei Tests halten beide
  Seiten fest.
- **`clearInternalAlarms()` fragt ZUERST `alarmRepository.isPersistenceBlocked()` und scheitert
  laut.** Der Kommentar dort sicherte „lieber laut scheitern als leeren" zu, konnte das aber nicht
  halten: nach einem gescheiterten Init-Load steht der Cache auf einer leeren Liste, und
  `getAllAlarms()` gibt genau die als **Erfolg** heraus (die Sperre wurde nur intern vermerkt).
  `getOrThrow()` warf also nie, die Cancel-Schleife lief ins Leere — und `deleteAllAlarms()` leerte
  Store UND Spiegel trotzdem, weil es bewusst mit `force = true` schreibt. Genau die Kombination,
  die der Kommentar ausschließen sollte: verwaiste, armierte System-Alarme, die niemand mehr
  abbrechen kann (bei aktiver Master-Pause klingelt der Wecker dann trotz Pause).
- **Die Hue-Regelausführung im `AlarmReceiver` ist gedeckelt (`withTimeoutOrNull`, 20 s), weil
  `pendingResult.finish()` erst danach kommt.** Der Hue-Pfad hat keine Gesamtschranke:
  `executeRulesForAlarm()` läuft über ALLE passenden Regeln (je 30 s Batch-Timeout), danach folgt
  `scheduleBridgeAutoOff()` ganz ohne Timeout (GET + n DELETEs + ein POST pro Ziel, je 10 s
  OkHttp). Zwei Regeln und eine nicht antwortende Bridge (Handy nicht im Heim-WLAN — der Normalfall
  auf Reisen) reichen über das Broadcast-Fenster hinaus, und dann darf das System den Prozess
  abwürgen. Der Wecker selbst hängt nicht daran: Ton, Vibration und Vollbild laufen über den bereits
  gestarteten `AlarmSoundService`. Licht, das nicht angeht, ist hinnehmbar; ein abgewürgter Prozess
  nicht.
- **Kein `startActivity()` aus dem AlarmReceiver**: AlarmManager-Broadcasts stehen nicht auf der
  Exemption-Liste für Background-Activity-Starts. Einziger Weg: `setFullScreenIntent()`.
- **`_alarmActive = true` VOR `startForeground()`** — sonst schließt sich das Vollbild sofort.
- **Snooze braucht `snoozeAlarmAction(id)`**, nicht `enhancedAlarmAction(id)` — sonst bricht der
  Maintenance-Sync den Snooze ab.
- **Ein schwebender Snooze muss einen Reboot überleben** (`AlarmManagerService.
  restorePendingSnoozes()`, aufgerufen im `BootReceiver` direkt nach dem Direct-Boot-Restore der
  regulären Alarme, seit v1.23.0). AlarmManager verliert beim Neustart ALLE Alarme; der
  Ursprungsalarm ist zu dem Zeitpunkt bereits gefeuert und aus dem Repository geräumt, es gibt also
  keinen zweiten Anker. Bis v1.23.0 stand der Snooze in KEINEM der beiden Wiederherstellungs-Pfade:
  wer schlummerte und dessen Gerät in den Minuten danach neu startete, wurde nie wieder geweckt.
  Der Merker (`pending_snoozes`, DEVICE-PROTECTED, existierte schon für den Cancel-Weg) trägt
  seither auch Schichtname und Schichtbeginn — sonst zeigte das Vollbild nach einem Reboot
  „Deine Schicht beginnt um" ohne Zeit. **Der zweiteilige Altbestand (`id|triggerTime`) MUSS
  lesbar bleiben**: er ist die einzige Spur eines Snooze, der über die Aktualisierung hinweg
  läuft — gälte er als kaputt, wäre er weder wiederherstellbar noch abbrechbar. **Beide Anlässe
  armieren über dasselbe `armSnooze()`**: der PendingIntent muss bis aufs Zeichen identisch sein
  (requestCode = alarmId, `snoozeAlarmAction`), sonst trifft ein späterer Abbruch den
  wiederhergestellten Snooze nicht mehr — und ein nicht abbrechbarer Snooze klingelt mitten in
  einer gerade eingeschalteten Pause. Der Aufruf steht bewusst hinter demselben
  Master-Pause-Gate wie die Alarme (`directBootAlarmStore.isPausedNow()`, nicht der CE-Store).
- **NICHTS am Application-Graphen darf WorkManager (oder CE-Storage) beim BAUEN anfassen.** Der
  Hilt-Graph der Application wird in JEDEM Prozessstart aufgebaut — auch in dem, den das System VOR
  der ersten Entsperrung für den `directBootAware` `BootReceiver` startet. Dort ist WorkManager
  NICHT initialisiert: seine Initialisierung hängt am `androidx.startup.InitializationProvider`, und
  ContentProvider ohne `directBootAware` werden vor dem Entsperren gar nicht instanziiert —
  `WorkManager.getInstance()` wirft „WorkManager is not initialized properly". Am Emulator
  reproduziert (11.08.2026): `CFAlarmApplication` injizierte `MasterPauseUseCase` direkt, der zieht
  über seinen Konstruktor `HueSmartScheduler`, und dessen `initialize()` rief eager
  `WorkManager.getInstance()`. Der Wurf schlug aus der Feld-Injektion nach oben durch, der Prozess
  starb mit „Unable to create application" — und damit lief der Direct-Boot-Restore der Alarme UND
  der schwebenden Snoozes NIE. Die Wecker kamen erst zurück, nachdem der Nutzer das Gerät entsperrt
  hatte; startet das Gerät nachts neu und niemand entsperrt es, gibt es keinen Wecker. Zwei
  Maßnahmen, beide nötig: `MasterPauseUseCase` hängt als **`dagger.Lazy`** am Feld (Konstruktion
  erst nach erkanntem Gerätewechsel, was einen erfolgreichen CE-Read voraussetzt), und
  `HueSmartScheduler` löst WorkManager erst **beim Gebrauch** auf (Getter statt `lateinit`-Feld) und
  überspringt sich mit WARN, wenn er nicht verfügbar ist. **Kein Unit-Test kann das fangen** (auch
  `ColdStartSmokeTest` nicht: er läuft im entsperrten Prozess) — die Prüfung ist ein echter
  `adb reboot` mit gefülltem Direct-Boot-Spiegel, Ablauf im HANDOFF.
- **Ein Emulator OHNE Bildschirmsperre kann Direct Boot NICHT prüfen** — er hat den ersten Anlauf
  dieses Fixes fälschlich als „am Gerät verifiziert" aussehen lassen. Ohne Credential gilt der
  Nutzer beim `LOCKED_BOOT_COMPLETED` bereits als entsperrend/entsperrt
  (`ContextImpl.isUserUnlockingOrUnlocked()`), CE-Storage ist lesbar und die Exception bleibt aus;
  mit PIN ist der Nutzer `RUNNING_LOCKED` und sie kommt. **Vor jedem Direct-Boot-Test deshalb
  `adb shell locksettings set-pin 1234` setzen und nach dem Reboot NICHT entsperren.** Damit wurde
  die zweite Fundstelle (`BackgroundServiceManager`, CE-`SharedPreferences` im Property-Initializer
  des ERSTEN Application-Feldes) erst reproduzierbar: `SharedPreferences in credential encrypted
  storage are not available until after user (id 0) is unlocked` → 0 wiederhergestellte Alarme.
  Praktischer Nebeneffekt derselben Sperre: `run-as` kommt an das CE-Verzeichnis nur im entsperrten
  Zustand — Testdaten also VOR dem Reboot schreiben.
- **Kein `getSharedPreferences()` und kein CE-Zugriff in einem Property-Initializer einer Klasse am
  Application-Graphen** (`BackgroundServiceManager`, `HueBridgePinningStore`: beide `by lazy`). Der
  Zugriff selbst ist harmlos, der ZEITPUNKT ist es nicht. Wer daraus wieder einen sofortigen
  Initializer macht, baut einen Absturz, den weder ein Unit-Test noch ein Emulator ohne
  Bildschirmsperre zeigt.
- **`HueSmartScheduler.getInstance()` veröffentlicht `INSTANCE` erst NACH `initialize()`.** Vorher
  stand die Zuweisung davor: warf `initialize()` (siehe oben), blieb ein halb initialisiertes
  Singleton zurück, das `getInstance()` für den ganzen Prozess kommentarlos weiter herausgab —
  jeder WorkManager-Zugriff darauf scheiterte, heilbar nur durch Prozess-Neustart. Dieselbe
  Fehlerklasse wie `cleanup()` auf Prozess-Singletons.
- **Schlummer-Dauer (`AlarmPrefs`, seit v1.22.0) ist konfigurierbar, aber EINE Quelle für beide
  Ausloeser** (Vollbild-Button, Notification-Button) — nicht zwei getrennte Werte. Gelöst NICHT
  durch einen DataStore-Read in einem der beiden Ausloeser selbst: `AlarmSoundService.
  onStartCommand()`s `ACTION_SNOOZE_ALARM`-Zweig und `AlarmFullScreenActivity.snoozeAlarm()` sind
  beide bewusst synchron (Notausgang-Charakter, siehe Snooze-Bug-Historie oben). Stattdessen liest
  `AlarmReceiver` (bereits in einer Coroutine, `receiverScope.launch`) den Wert EINMAL pro
  Alarm-Feuern aus `AlarmPrefs` und reicht ihn als Intent-Extra (`AlarmSoundService.
  EXTRA_SNOOZE_MINUTES`) an beide Ausloeser durch — die lesen dort synchron aus dem Intent.
  **Dieser Read in `AlarmReceiver.startAlarmSoundService()` MUSS hinter `userUnlocked` gegated
  sein**, genau wie der Skip- und Silent-Check direkt daneben: `AlarmPrefs` liegt im
  `@MainDataStore` (CE-Storage), das vor der ersten Entsperrung nicht lesbar ist. Real am
  Fairphone reproduziert (05.08.2026): der erste Wurf dieses Features hatte den Read ungegatet —
  auf Direct Boot hätte das den Wecker komplett stumm gelassen (Exception im try/catch
  verschluckt, `startForegroundService()` nie erreicht), das exakte Gegenteil dessen, wofür
  `directBootAware="true"` existiert.
- **`AlarmMaintenanceService`: `stopSelf(startId)`, niemals blankes `stopSelf()`.** Zwei
  überlappende Starts teilen sich `serviceScope`; der Erste, der fertig wird, löst sonst
  `onDestroy()` → `scope.cancel()` aus und reißt den anderen mitten in der Arbeit ab.
- **Die 6h-Wartungskette hat GENAU einen Planer: `scheduleNext()`, auf genau einem Request-Code.**
  Es gab mal einen zweiten (`scheduleNextAlarm()`, Code 9999 statt 0). Verschiedene Request-Codes
  = verschiedene PendingIntents = zwei unabhängige Alarme; da der `finally`-Block von
  `onStartCommand` ohnehin immer `scheduleNext()` ruft, liefen dauerhaft zwei Wartungszyklen alle
  6h im Millisekunden-Abstand. Wer einen Lauf „sicherheitshalber" selbst nachplant, baut das
  wieder ein — der `finally`-Block deckt jeden Pfad ab.
- **Dieser `finally`-Block läuft in `withContext(NonCancellable)` und fängt den
  Master-Pause-Read.** Vorher stand der suspendierende `pausedNow()`-Read als erste Anweisung darin:
  in einer gecancelten Coroutine (`onDestroy()` → `serviceScope.cancel()`) warf er sofort, wodurch
  WEDER `scheduleNext()` NOCH `stopSelf(startId)` liefen — Request-Code 0 ist der einzige Slot, die
  rollierende Kette war damit bis zum nächsten Boot tot. Ebenfalls im `finally`: Dimmer-, DND- und
  Pre-Alarm-Refresh-Reschedule. Sie standen im tiefsten Erfolgszweig hinter fünf Returns, also
  gerade bei den häufigsten Läufen unerreichbar; bei Master-Pause wird abgeschaltet statt geplant.
- **Die 6h-Wartung MUSS Änderungen und Streichungen sehen können — die Lade-/Sync-Entscheidung
  liegt als reines `MaintenanceLoadDecision` daneben.** Zwei Gates standen vor dem Delta-Sync, der
  Update/Delete als einziger Ort beherrscht: Events wurden nur geladen, wenn der letzte Alarm < 7
  Tage entfernt lag (bei einem 14 Tage gepflegten Dienstplan praktisch nie), und danach brach die
  Wartung ab, sobald es keine Schicht OHNE bestehenden Alarm gab — ein verschobenes Event behält
  seine Event-ID, ein gestrichenes erzeugt gar keinen Match. Folge real am 30.07.2026 (~4 Tage
  unbemerkt): Wecker zur alten Zeit bzw. für eine Schicht, die es nicht mehr gab. Jetzt lädt es bei
  Puffer < 7 Tage ODER letzter echter Kalender-Abfrage ≥ 12 h ODER nächster Alarm ≤ 48 h, und
  synchronisiert **immer**, sobald Events vorliegen (`newShifts` ist nur noch Diagnose-Log). Eigener
  Frische-Stempel `last_event_load_time` — `last_maintenance_time` wird auch im Skip-Zweig gesetzt
  und ließe die Daten dauerhaft frisch aussehen. Die Leerlisten-Sperre bleibt.
- **`BootReceiver` liest die Kalenderauswahl über den DataStore und entscheidet nicht auf einem
  veralteten Snapshot.** Beim Boot ist der `StateFlow` noch nicht hydriert; und die
  Validierungs-/Löschschleife konnte einen vom parallel laufenden Wartungslauf gerade korrigierten
  Wecker wieder löschen, ohne ihn neu anzulegen (`BootAlarmValidation`). Außerdem setzt der Receiver
  **vor** der langen Recovery einen Wartungs-Anker (Foreground-Service, `forceSync=true`):
  `performCompleteSystemRecovery` läuft ohne `goAsync` in einem eigenen Scope und sitzt zuerst 5 s
  ab — in der Zeit ist der Prozess als „empty process" abschießbar, womit 6h-Kette, Sync und
  Dimmer-/DND-Planung lautlos ausfielen. Bei aktiver Master-Pause wird kein Anker gestartet
  (synchron über den Direct-Boot-Spiegel geprüft, weil `MasterPausePrefs` im CE-Storage nur
  suspendierend lesbar ist). Der `ACTION_PACKAGE_REPLACED`-Zweig ist entfernt: der Manifest-Filter
  kann ihn nicht zustellen (bräuchte `<data scheme="package"/>`, was die drei URI-losen Actions
  aussperren würde) — `MY_PACKAGE_REPLACED` deckt den echten Update-Fall ab.
- **`TimezoneChangeReceiver` startet die Wartung mit `forceSync=true`.** Ohne das Flag kehrte der
  angestoßene Lauf im Normalbetrieb zurück, ohne den Kalender anzufassen — der Receiver war
  wirkungslos. Ein bloßes Re-Arming wäre kein Ersatz: es rechnet die gespeicherten Millis mit
  derselben Zone hin und zurück.
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
  **Zusätzlich braucht das Flag ein Gate in `syncAlarms()` UND einen Backstop in
  `scheduleSystemAlarm()`** (dem einzigen Weg in den `AlarmManager`, u. a. Boot-Restore): der
  übersprungene Alarm ist aus dem Repository gelöscht, sein Event galt damit für den nächsten Sync als
  NEU — System-Alarm wieder scharf plus falsche „Neue Schicht erkannt"-Notification.
- **Der Delta-Sync hat pro Event ein eigenes `try/catch`, das `CancellationException` weiterwirft.**
  Ohne das brach ein einzelner abgelehnter Alarm (verstrichene Weckzeit) über `getOrThrow()` den
  GESAMTEN Sync ab und ließ den Rest der unsortierten Map ungesetzt; wird die Cancellation dagegen als
  Event-Fehler verbucht, meldet die Abschlusszeile „complete" mit unvollständiger Liste. Die
  Abschlusszeile sagt jetzt, wenn Events übersprungen wurden.
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
- **`ShiftRecognitionEngine`: EIN unveränderliches Cache-Objekt hinter einer Volatile-Referenz,
  Prüfung UND Veröffentlichung hinter `recognitionMutex`, PLUS eine Epochen-Kennung.** Der frühere
  Mehrfeld-Cache veröffentlichte seinen Schlüssel VOR dem Ergebnis (`lastRecognitionHash`/
  `lastCacheTime` vor, `cachedMatches` nach `performRecognition()`); dazwischen liegt eine echte
  Suspend-Phase, und ein nebenläufiger Aufrufer mit gleichem Event-Hash traf die Cache-Bedingung und
  bekam den alten Stand — im frischen Prozess eine **leere** Liste. `syncAlarms()` liest „leer" als
  „keine Schichten" und ruft `clearInternalAlarms()`: alle System-Alarme gecancelt, Repository und
  Direct-Boot-Spiegel geleert. Genau das Symptom „0 Alarme trotz korrekt erkannter Schichten"
  (v1.21.0 am Fairphone). **Der Mutex allein reicht nicht:** `clearRecognitionCache()` läuft aus
  synchronem Kontext und kann ihn nicht nehmen — es zählt die Epoche hoch und nullt DANACH den
  Stand; ein Lauf, dessen Grundlage inzwischen invalidiert wurde, veröffentlicht seinen Stand nicht
  mehr als frisch. Das ersetzt das alte `recognitionInProgress` mit 200-ms-Polling-Timeout, das nach
  Ablauf „sicherheitshalber" genau den halbfertigen Zustand las, den es verhindern sollte.
  `ShiftViewModel.processCalendarEvents` bleibt `suspend` (kein fire-and-forget `launch`) — das war
  der v1.21.0-Teilfix und ist weiterhin richtig, deckt aber nur einen der Aufrufer ab.
- **`ShiftDefinition.isEnabled` wird in `performRecognition()` respektiert.** Der Schalter
  „Schichtdefinition aktiviert" war eine Attrappe: gelesen hat ihn nur die Auswahl-UI, die Erkennung
  lief über ALLE Definitionen — eine deaktivierte Schicht verschwand aus den Listen, klingelte aber
  weiter. Gefiltert wird EINMAL, mit Log, wie viele übersprungen wurden. Bewusst **nicht** in
  `ShiftConfig.findDefinitionFor()`: dort wird ein BESTEHENDER Alarm einer Definition zugeordnet, ein
  Filter würde einem Alarm aus der Zeit vor dem Deaktivieren seine Hue-Regeln und das
  `isSilent`-Flag entziehen.
- **Ein gescheiterter Konfigurations-Read darf NIE zur leeren Definitionsliste werden.**
  `performRecognition()` las `getOrNull()?.definitions ?: emptyList()` — der Repository-Pfad für eine
  vorhandene, aber nicht dekodierbare Konfiguration liefert ein `Result.failure`, keine Exception.
  Aus „ich kann die Konfiguration nicht lesen" wurden lautlos 0 Definitionen → 0 erkannte Schichten
  → `syncAlarms()` löscht ALLE Alarme. Jetzt `getOrThrow()`: der Fehler kommt beim Aufrufer an, der
  Cache-Schlüssel bleibt unangetastet, der nächste Versuch läuft frisch.

- **`AlarmMaintenanceService.start()` fängt den abgelehnten Vordergrund-Start selbst — nicht die
  Aufrufer.** `startForegroundService()` wirft ab Android 12 eine
  `ForegroundServiceStartNotAllowedException`, wenn die App im Hintergrund ist und der Anlass nicht
  auf Androids Ausnahmeliste steht; **`ACTION_TIMEZONE_CHANGED` steht dort NICHT.** Von sechs
  Aufrufstellen fing genau eine nicht (`TimezoneChangeReceiver`), und eine Exception aus
  `onReceive()` reißt den Prozess mit — ausgefallen wäre damit genau die Neuberechnung, für die
  dieser Receiver als einzige Verteidigungslinie existiert. Der Fang steht deshalb in `start()`
  selbst (deckt jeden künftigen Aufrufer ab, gleiche Überlegung wie der Master-Pause-Backstop) plus
  ein EINMALIGER Nachhol-Alarm auf **eigenem** Request-Code (`MAINTENANCE_CATCHUP_REQUEST_CODE`,
  +10 s) — das Feuern eines Alarms IST ein erlaubter Anlass. Eigener Code, weil Code 0 der einzige
  Slot der rollierenden 6h-Kette ist; der Nachhol-Alarm plant sich nicht selbst nach, ist also kein
  zweiter Planer. Dazu reicht `AlarmMaintenanceBroadcastReceiver` das `forceSync`-Extra weiter —
  ohne das liefe der nachgeholte Lauf ohne Erzwingen zurück, also wirkungslos.

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
  `HueV1Envelope` (`internal object`, rein und testbar) — **auch die STEUER-Endpunkte werten den Body
  aus**, nicht nur `result.isSuccess`: nach einem entzogenen Whitelist-Eintrag meldete die Kette „5/5
  actions successful" und legte danach noch den Auto-Aus-Zeitplan an, für Licht, das nie anging.
  Dieselbe Zwei-Ebenen-Falle wie bei `Result<RuleValidationResult>`. Drei Regeln, alle aus echten
  Gerätelogs:
  - **`parseAll` ist streng** („kein Eintrag enthält `error`") und ausschließlich das Urteil der
    Fehlerhüllen-Wächter der GET-Endpunkte (`getLights`/`getGroups`/`getSchedules`). Eine Fehlerhülle
    (HTTP 200 + JSON-**Array** statt Map) wird VOR dem Parsen erkannt und geworfen, statt still „0
    Lampen" zu liefern — der Nutzer sah sonst „Keine Lampen gefunden", ohne Hinweis auf die nötige
    Neukopplung.
  - **`parseControl` ist bewusst milder** („mindestens ein `success`"): ein PUT auf `/state` liefert
    einen Eintrag **pro Attribut**, und die Bridge lehnt einzelne ab, während sie die anderen anwendet
    (`ct` an einer Lampe ohne Farbtemperatur = error 6, an ausgeschalteter = error 201). Mit `parseAll`
    wurde daraus ein Fehlschlag, `startSunrise` stieg nach Schritt 1 aus, die Lampe blieb am Wecktag
    auf `bri=1`. Abgelehnte Einzelattribute werden geloggt.
  - **Jedes vorhandene `success`-Feld ist ein Erfolg — auch als String.** Ein DELETE antwortet
    `[{"success":"/schedules/1 deleted"}]`; der frühere `as? Map<*, *>` machte aus einem erfolgreichen
    Löschen ein Failure — schädlich, weil das Aufräumen der Auto-Aus-Timer darauf angewiesen ist.
- **Ein Fehlschlag der Bridge darf nicht zur leeren Liste degradieren.**
  `HueLightUseCase.getAllLightTargets()` fing das Failure ab und lieferte `success(LightTargets(leer,
  leer))` — genau die stille leere Lampenliste, die die Hüllen-Wächter beseitigen sollten. Scheitern
  BEIDE Abfragen, wird der Fehler durchgereicht; der Teilerfolg-Zweig bleibt (eine Bridge ohne
  Gruppen ist normal). `HueLightTargetsFailureTest` trennt „Bridge lehnt ab" von „Bridge hat nichts".
- **`getBridgeConfig` prüft die Antwort (`bridgeid` oder `mac` müssen da sein), statt sie nur zu
  deserialisieren.** Beide Aufrufer benutzen sie als „hat sie geworfen?"-Orakel, sie ist damit de
  facto die Bridge-/Zugangsdaten-Prüfung der App — Gson erzwingt Kotlins Non-Null-Deklarationen aber
  NICHT, `fromJson("{}", …)` liefert ein Objekt voller nulls und wirft nicht. Wandert der DHCP-Lease,
  hätte ein beliebiges anderes Gerät an derselben IP als „unsere Bridge" gegolten.
- **Der gesamte Hue-Pfad ist IPv4-only** (Präfix-Klemme, URL-Bau ohne eckige Klammern,
  `isBridgeReachableNow`). mDNS wählt deshalb die erste **IPv4**-Adresse: in einem IPv6-Heimnetz
  meldete die Discovery sonst Erfolg, danach scheiterte jeder Zugriff, und der N-UPnP-Fallback greift
  nicht, weil mDNS ja „etwas" gefunden hatte. Nur-IPv6 liefert ehrlich `null`. Eine Nicht-IPv4-Adresse
  ist kein „SECURITY"-Vorfall — Adressfamilie, nicht Angriff; die Klemme bleibt genauso streng.
- **`healthCheckScope` braucht einen `CoroutineExceptionHandler` — der `SupervisorJob` reicht
  NICHT.** Ein SupervisorJob isoliert nur Geschwister; die Exception läuft trotzdem zum
  Thread-Default-Handler und beendet den PROZESS. In diesem Scope liegen fünf fire-and-forget
  `launch`-Blöcke, mehrere davon greifen ungeschützt auf den Hue-DataStore zu
  (`restoreConnectionFromStorage()` macht `dataStore.data.first()`); der
  `ReplaceFileCorruptionHandler` fängt nur Korruption, eine IOException reicht DataStore durch. Für
  eine Wecker-App ist das die falsche Reihenfolge der Wichtigkeit: ein misslungener
  Lichtsteuerungs-Read darf nie den Prozess beenden, der die Alarme hält. Zusätzlich liegt das
  `try/catch` des Netzwerk-Recovery-Collectors **INNERHALB** des `collect` — außen herum hätte ein
  einzelner fehlgeschlagener Versuch den Collector beendet, und der startet in diesem Prozess nie
  wieder (`initialize()` ist per Wächter idempotent): die autonome Wiederverbindung bei der
  Heimkehr ins Heim-WLAN wäre dauerhaft tot. Der Scope-Handler rettet den Prozess, nicht das
  Feature — beides ist nötig.
- **Eine im Direct-Boot übersprungene Hue-Planung wird NACHGEHOLT
  (`HueSmartScheduler.retrySkippedSchedulingIfNeeded()`).** Der Prozess, der vor der ersten
  Entsperrung startet, stirbt beim Entsperren nicht — er ist genau der Prozess, in dem der Nutzer
  die App danach bedient, und `HueBridgeConnectionManager.initialize()` ist per Wächter idempotent.
  Ohne das Nachholen fehlten für dessen gesamte Lebensdauer tägliche Planung, Pre-Alarm-Checks und
  der Alarm-Beobachter. Zwei Aufrufstellen: der **ignorierte** Zweig von `initialize()` und
  `onAppForeground()`. Ein früherer Kommentar behauptete, ein späterer Aufruf plane von selbst neu —
  das war falsch.
- **`cleanup()` auf Prozess-Singletons cancelt NUR Kinder.** `HueBridgeConnectionManager.cleanup()`
  rief `healthCheckScope.cancel()` — auf einem Singleton mit Prozess-Lebensdauer endgültig: jedes
  spätere `launch` (Restore, Health-Monitoring, Netzwerk-Recovery) wäre lautlos nie mehr gestartet,
  heilbar nur durch Prozess-Neustart, und beim Wecken blieb das Licht ohne Fehlermeldung aus. Jetzt
  `cancelChildren()` + Zurücksetzen von `initialized`, wie `HueSmartScheduler.cleanup()` und
  `WakeLockManager.releaseAllWakeLocks()`. Folge: `startNetworkRecoveryMonitoring()` läuft **nicht**
  „nur einmal pro Prozess" — ein späteres `initialize()` startet den Collector erneut.
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
- **`autoOffTargetsOf()` filtert bewusst NICHT nach Schichtnamen.** Die Auswahl gehört allein
  `findApplicableRules` — und die matcht seit v1.11.0 **exakter Definitionsname ODER
  `UNIVERSAL_SHIFT_PATTERN`**, kein Keyword, kein Teiltreffer (`HueRuleMatchingTest`). Ein zweiter
  Filter gegen den Schichtnamen würde genau die UNIVERSAL-Regeln wegwerfen, deren `shiftPattern` per
  Definition NICHT dem Schichtnamen gleicht: sie verlören ihr Auto-Aus, das Licht bliebe an. Diese
  Funktion besitzt nur den Rechenweg (welche Ziele, welche Verzögerung inkl.
  Sonnenaufgangs-Versatz). **Die frühere Begründung „findApplicableRules matcht auch über Keywords"
  war veraltet** — sie verleitete dazu, das Keyword-Matching „wiederherzustellen", also genau die
  S-auf-S2-Fehlerfamilie neu zu bauen (siehe „Schichterkennung & Musterabgleich").
- **`UNIVERSAL_SHIFT_PATTERN = "ALL"` ist seit v1.24.0 über den Regel-Editor erreichbar**
  (Eintrag „Alle Schichten" in `ShiftPatternCard`). Davor wertete der UseCase das Muster zwar aus,
  aber keine Oberfläche konnte es setzen — ein funktionierender Codepfad ohne Zugang. Drei Dinge
  hängen zusammen und dürfen nicht auseinanderlaufen: die UI referenziert die **Konstante**
  (`internal`, in der Companion von `HueRuleUseCase`) statt ein eigenes `"ALL"`-Literal zu führen;
  der Rücklesepfad erkennt sie mit **demselben Maßstab** wie `findApplicableRules`
  (`equals(..., ignoreCase = true)`) — sonst zeigt eine gespeicherte Universal-Regel beim
  Wiederöffnen „nichts ausgewählt" und der nächste Speichervorgang überschreibt sie unbemerkt mit
  einem Schichtnamen; und Regel-Liste wie Vorschau zeigen „Alle Schichten" statt des rohen
  Sentinels. Das **Matching selbst bleibt unverändert** — der Editor wurde erreichbar gemacht,
  nicht die Erkennung gelockert.
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
  der `else`-Zweig faengt jeden Unterscreen ab, die Sonderfaelle stehen davor. **VIER Gates sind
  nicht optional:** `BatteryExemption`, `UnusedAppRestrictions` und `TimeOfficeHealthCheck` muessen
  wie „Spaeter" wirken, also ihr jeweiliges Dismissed-Flag schreiben
  (`dismissBatteryPrompt()` bzw. `UnusedAppRestrictionsHelper.setDismissed`/
  `TimeOfficeHealthHelper.setPromptDismissed`) — sonst schickt `handleAuthenticationSuccess()` den
  Nutzer sofort zurueck und Zurueck sieht wirkungslos aus; `OEMWarning` muss wie „Verstanden" die
  Wartungskette anstossen (`finishOnboarding()`), sonst steht ein Nutzer ohne 6h-Wartung da.
  Dazu ein `MainContent`-Zweig: auf einem Nicht-Home-Tab fuehrt Zurueck auf HOME
  (Android-Konvention fuer Bottom-Navigation). Auf dem Home-Tab bleibt der Handler bewusst **aus**
  — dort ist Zurueck wirklich „App verlassen", und der Systemdefault kann das inkl.
  Predictive-Back besser.
- **`NavigationState.HueRuleConfig`/`DimmerRuleConfig` brauchen `cameFromSettingsList`, nicht nur
  `returnToTab`.** `HueRuleConfig` ist auf zwei Wegen erreichbar (direkt vom **HUE-Tab** „Neue
  Regel" ODER über `HueSettings` „Bearbeiten"), und der System-Back (`BackHandler`) UND der
  Screen-eigene Zurück-Pfeil/Speichern-Button MÜSSEN für denselben Einstiegspfad zum selben
  Ziel führen. Vor v1.22.0 taten sie das nicht: der Screen-eigene Weg ging immer zur Settings-Liste
  (falsch bei Direkteinstieg vom Tab), der System-Back-Weg ging immer direkt zum Tab (falsch bei
  Einstieg über die Settings-Liste) — zwei sich widersprechende, feste Annahmen statt einer
  gemeinsamen. Real am Fairphone verifiziert (05.08.2026, alle 4 Hue-Kombinationen).
  `DimmerRuleConfig` hat heute nur den Weg über `DimmerSettings` (Default `true`), trägt das Flag
  aber gleich mit, damit ein späterer Direktpfad automatisch korrekt zurückführt.

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

### Schichterkennung & Musterabgleich

**Zwei verschiedene Funktionen, zwei verschiedene Regeln — die Verwechslung hat schon zweimal
Wecker gekostet:**

- **`ShiftConfig.findDefinitionFor(shiftName)`** ordnet einem **bestehenden Alarm** eine Definition
  zu (für Hue-Regeln und `isSilent`). Streng nach Genauigkeit gestaffelt: exakter Name → exaktes
  Keyword → Teiltreffer per `contains` **ohne** Wortgrenzen, und dort nur mit Keywords ab
  `MIN_FUZZY_KEYWORD_LENGTH = 2`. Vorher stand im `AlarmReceiver` ein
  `find { name == x || keywords.any { shiftName.contains(it) } }`. `find` nimmt den **ersten**
  Treffer, und die Spätschicht trägt das Keyword **„S"** — das steckt in „S2", „Nacht**s**chicht"
  und „Zwi**s**chendienst". Folge: die S2-Regel feuerte **nie**, die Spätschicht-Regel bei fast
  **jeder** Schicht. Am Emulator gegen die echte Standardkonfiguration reproduziert (v1.11.0).
  `AlarmInfo.shiftName` ist immer der **Name** einer Definition → Stufe 1 trifft im Normalfall
  immer; die Keyword-Stufen sind nur für umbenannte Definitionen da. Wer `contains` nach vorn zieht
  oder `MIN_FUZZY_KEYWORD_LENGTH` senkt, baut den Fehler neu — `ShiftDefinitionMatchingTest` hält
  alle fünf Standard-Schichten fest, zusätzlich gegen eine nicht migrierte Bestandskonfiguration.
- **`ShiftDefinition.matchesKeywords(eventTitle)`** erkennt Schichten in **Kalendertiteln** und
  arbeitet mit **Wortgrenzen**. Dort trifft „F" nur ein alleinstehendes F, nicht „Frühschicht" und
  nicht „Fortbildung". Der `name` zählt ab `MIN_FUZZY_KEYWORD_LENGTH` als zusätzliches Muster (die
  Längengrenze ist Pflicht, sonst kehrt die einbuchstabige Falle über den Namen zurück). Muster
  werden beim Speichern **und** beim Matchen getrimmt (" IMCF" ergab sonst ein Regex, das „IMCF"
  nicht mehr traf, während die UI genau dieses Muster zeigte); leere Muster matchen nie.
- **Wortgrenzen über Unicode-Kategorien, NICHT `\b`.** `WORD_START`/`WORD_END` sind Lookarounds
  `(?<![\p{L}\p{N}_])` / `(?![\p{L}\p{N}_])`. Javas `\b` ist ASCII-basiert: Umlaute und `ß` gelten als
  Nicht-Wortzeichen, wodurch die Semantik für Muster mit führendem Umlaut oder abschließendem `ß`
  **invertiert** war — nachgemessen (10.08.2026): `\büd\b` traf „üd" und „station üd" NICHT, aber
  „xüd", also ausgerechnet mitten im Wort. Betrifft echte deutsche Bezeichnungen („Übergabedienst",
  „Ärztlicher Dienst", Kürzel „ÜD"), und seit der Name als Muster zählt, sagt der Editor dem Nutzer
  ausdrücklich zu, dass sein Schichtname erkannt wird. Bewusst Lookarounds statt `(?U)`: das
  Inline-Flag würde `\w`/`\d`/`\s` im ganzen Ausdruck umdefinieren. Die Konstanten liegen auf
  **Dateiebene**, nicht im Companion — `ShiftDefinition` ist `@Serializable`, ein
  `private companion object` macht `serializer()` mit privat.
- **Die einbuchstabigen Standard-Keywords „F"/"S"/"N" gehören in die Vorgaben.** Sie waren einmal
  entfernt (Begründung: ein privates „Kino mit F" erzeugt einen Wecker um 05:30) — das war die
  Verwechslung der beiden Funktionen oben. Am Emulator gegen den echten Dienstplan-Feed nachgewiesen
  (10.08.2026): ohne sie sank die Erkennung von 4 auf 1 Schicht, denn die realen Titel sind kurze
  Codes („F", „IMCF", „AD1", „FBE", „+"). Abwägung, vom Nutzer ausdrücklich so entschieden: eine nicht
  erkannte Schicht heißt KEIN WECKER — für einen überzähligen gibt es „Nächsten Alarm überspringen",
  für einen verschlafenen gibt es nichts. **Restrisiko akzeptiert und testlich festgeschrieben**
  (`ShiftConfigDefaultsTest` fordert ausdrücklich, dass „F"/"S"/"N" treffen), damit es niemand für ein
  Versehen hält: die Erkennung liest nur selbst ausgewählte Kalender, und das Muster ist entfernbar.
  Der Editor warnt sichtbar bei einem einzeichigen Muster, verbietet es nicht.
- **Jede Standard-Definition hat neben dem Stationskürzel ein generisches, mehrbuchstabiges
  Muster** (Frühdienst/Spätdienst/Nachtdienst/ZD). „IMCF/IMCS/IMCN/IMCZ" sind die Kürzel EINER
  Station; für einen Kollegen auf einer anderen war der Zwischendienst mit seinem einzigen Muster
  „IMCZ" strukturell tot — kein Treffer, kein Wecker, keine Meldung. `ShiftConfigSerializationTest`
  hält das fest („jede Standard-Definition hat ein Muster ohne Stationskuerzel") plus die Gegenprobe,
  dass die einbuchstabigen Muster keinen unscharfen Teiltreffer gewinnen.
- **Geraten wird nicht mehr — vorgeschlagen wird** (seit v1.23.0). `ShiftCodeSuggester` (rein, ohne
  Android) sammelt die Termintitel, die von KEINEM aktiven Muster getroffen werden, sortiert nach
  Häufigkeit (bei Gleichstand alphabetisch, damit die Reihenfolge nicht springt) und deckelt auf 8 —
  die Deckelung wird als `droppedCount` **benannt**, nicht verschwiegen. Zwei Ausschlüsse, beide
  technisch: zu lang (>24 Zeichen) ist Freitext, und ein Titel ohne einzigen Buchstaben/Ziffer („+"
  steht real im Dienstplan) könnte über Wortgrenzen NIE treffen — ihn vorzuschlagen wäre ein
  Versprechen, das die Erkennung nicht hält. Muster einer **deaktivierten** Definition unterdrücken
  keinen Vorschlag: sie erkennen nichts, für dieses Kürzel fehlt also gerade ein Wecker. Karte „Diese
  Kürzel stehen in deinem Kalender", oben im Schicht-Konfigurationsscreen. **Die App ordnet NICHTS
  selbst zu** — eine stille Automatik, die danebengreift, stellt einen Wecker auf die falsche Uhrzeit.
  `assignCodeToDefinition()` ergänzt das Kürzel als exaktes Keyword und geht über
  `updateShiftConfig()`, damit Speichern, Cache-Invalidierung, Erkennung und Alarm-Sync mitlaufen.
- **Kein stiller Default-Überschreiber der Schicht-Konfiguration — es gab DREI Schreibstellen.**
  Seit `ShiftConfigRepository` „noch nie konfiguriert" (liefert den Default als **Erfolg**) von
  „vorhanden, aber unlesbar" (`Result.failure`, Rohdaten gesichert unter `shift_config_broken`)
  unterscheidet, bedeutet ein Fehlschlag nur noch: defekt — und genau dort ist Überschreiben
  Datenverlust. Alle drei Fallbacks sind ersatzlos entfernt:
  `CalendarViewModel.createAlarmsFromLoadedEvents()` (lief bei JEDEM Kalender-Ladevorgang, also
  jedem App-Start), `ShiftViewModel.loadShiftConfig()` (im `init{}`, also bei JEDER
  ViewModel-Erzeugung) und `CFAlarmApplication.initializeApp()` (bei JEDEM Kaltstart, auch bei rein
  hintergrundgetriebenen Prozessstarts — unbemerkbar). Fail-safe stattdessen: Sync auslassen, Fehler
  loggen bzw. in den UI-State schreiben, bestehende Alarme bleiben gesetzt. Der bewusste Weg zum
  Default heißt `resetToDefaults()` und gehört dem Nutzer.
- **„Auf Standardwerte zurücksetzen" rührt `autoAlarmEnabled` nicht an**
  (`resetToDefaultsPreservingAutoAlarm()`). Vorher speicherte der Knopf die komplette
  `getDefaultConfig()`, und die enthält `autoAlarmEnabled = true`: wer die automatischen Alarme im
  Wecker-Tab bewusst ausgeschaltet hatte (eine ECHTE, sofortige Pause) und danach nur seine
  Schichtdefinitionen aufräumte, hob die Pause unwissentlich auf — `updateShiftConfig()` persistiert
  sofort und triggert den Resync. Der Automatik-Schalter gehört dem Wecker-Tab, dieselbe Trennung
  wie bei der Master-Pause; der Rückfrage-Dialog sagt das ausdrücklich. Zurücksetzen und Löschen
  einer Schicht fragen beide vorher nach — es gibt kein Undo.
- **`ShiftViewModel` beobachtet `IShiftUseCase.shiftConfig` und zieht Anzeige, Erkennung UND Alarme
  nach** (`observeExternalConfigChanges()`). Am Gerät gefunden (11.08.2026): der
  Konfigurations-Import schreibt direkt über das Repository, der Store war danach korrekt — aber die
  laufende App zeigte den alten Stand, und schlimmer: die **Alarme** wurden nicht neu gesetzt, eine
  importierte Konfiguration hätte bis zur nächsten 6h-Wartung die ALTEN Zeiten weitergeweckt.
  Bewusst ein Beobachter am gemeinsamen Datenfluss statt eines Aufrufs im Import — dieselbe Lehre wie
  beim Master-Pause-Backstop: ein zentraler Punkt deckt jeden heutigen und künftigen Schreiber ab.
  Eigene Änderungen werden per Gleichheitsvergleich übersprungen, sonst laufen Erkennung und Sync bei
  jeder Nutzeränderung zweimal — und zwar nebenläufig auf derselben Engine-Instanz.
- **`ShiftUseCase.add/update/deleteShiftDefinition` sind ENTFERNT** (v1.23.1, samt
  `IShiftUseCase`-Deklarationen). Sie hatten keinen Aufrufer und waren eine Falle: der Name klang
  passend („eine Schicht hinzufügen"), aber der Pfad speicherte die Konfiguration und invalidierte
  die Caches, ohne die System-Alarme anzufassen — die neue Schicht hätte bis zur nächsten 6h-Wartung
  keinen Wecker bekommen, die gelöschte weitergeklingelt. Der einzige richtige Weg bleibt
  `ShiftViewModel.updateShiftConfig(config)`, weil nur dort `triggerAlarmCreationFromConfigUpdate()`
  → `AlarmUseCase.syncAlarms()` dranhängt.

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
  **nicht** in `onCleared()` gecancelt — genau das wäre der Bug.

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
  nach einem **Lesefehler des Alarm-Bestands**. Die BEDEUTUNG einer leeren Fensterliste
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
- **Jeder Setter, der einen `DimOverlayPrefs`-Wert schreibt, MUSS direkt danach
  `DimScheduleUseCase.enable()` aufrufen** — nicht nur die `DimmerViewModel`-Setter, auch
  `NotificationSettingsViewModel.setDimCorrectionNotificationEnabled()` (Fix v1.22.1). Der Toggle
  für die Korrektur-Notification schrieb bis dahin nur den Preference-Wert; `DimCorrectionNotifier.
  show()/cancel()` wird aber ausschließlich aus `applyCurrentState()` entschieden, und das läuft nur
  beim rollenden Tick (Fenstergrenzen) oder eben einem `enable()`-Aufruf neu. Ein **mitten** im
  laufenden Fenster umgelegter Toggle blieb dadurch bis zum nächsten Tick wirkungslos — und dieser
  nächste Tick ist typischerweise das Fenster-ENDE, das die Notification sofort wieder wegräumt. Der
  Nutzer sah sie für das gerade laufende Fenster praktisch nie. Real gemeldet (05.08.2026). Wer einen
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
  Fensterliste an, sein eigenes `alarmReadFailed` blieb `false` (der eigene `getAllAlarms()`-Zweig
  wird bei nur-Modus-1 nie betreten) und `fallbackTick()` plante den 6-Stunden-Keep-alive statt des
  15-Minuten-Retry. Wer den Status wieder wegoptimiert, holt genau das zurück.
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
- **Das automatische Onboarding-Gate für den TimeOffice-Health-Prompt hängt an
  `NavigationViewModel.handleAuthenticationSuccess()`, NICHT nur an `proceedPastGates()`.**
  `proceedPastGates()` (MainScreen) verkettet die Gates nur, wenn der Nutzer die vorherigen Screens
  gerade durchläuft. `handleAuthenticationSuccess()` ist der EINZIGE Pfad, der bei jedem
  App-Vordergrund/Auth-Erfolg automatisch prüft, ob noch ein Gate offen ist — ohne einen eigenen
  Zweig dafür sehen Bestandsnutzer, die Kalender/Akku/Unused-App-Gates schon vor diesem Feature
  durchlaufen hatten, den TimeOffice-Prompt NIE automatisch (nur noch über die permanente
  Status-Tab-Karte). Genau der Fall, für den das Feature gebaut wurde. Fix seit v1.22.0: vierter
  `else if`-Zweig in `handleAuthenticationSuccess()`, gleiche Gate-Reihenfolge wie
  `proceedPastGates()`.

### Persistenz (DataStore)

- **Die REIHENFOLGE von `.catch` und `.map` in einem Preferences-Flow ist tragend** (v1.24.0).
  `ShiftConfigRepository.shiftConfig` hatte `.catch { emit(emptyPreferences()) }` **vor** dem `.map`.
  Damit konnte das `map` „Store nicht lesbar" nicht mehr von „noch nie konfiguriert" unterscheiden:
  `decodeShiftConfig(json, null)` liefert `NotConfigured`, und genau dieser Zweig schreibt
  `cachedConfig = getDefaultConfig()` samt frischem Zeitstempel — während der `Broken`-Zweig
  daneben bewusst NICHT cacht. Folge: `getCurrentShiftConfig()` prüft den Cache als allererste
  Anweisung und lieferte 30 s lang (`CACHE_VALIDITY_MS`) die **Standardkonfiguration als
  `Result.success`**, ohne den frischen Read zu erreichen, der ehrlich gescheitert wäre. Die vier
  Konsumenten, die sich ausdrücklich auf dieses Scheitern verlassen
  (`ShiftViewModel.observeExternalConfigChanges()`, `AlarmMaintenanceService`, `CFAlarmApplication`,
  `CalendarViewModel`), hätten mit Standard-Weckzeiten synchronisiert und die Alarme nicht mehr
  erkannter Schichten im Delta-Sync gelöscht. Auslöser ist eine **IOException** auf `shift_prefs` —
  der `ReplaceFileCorruptionHandler` fängt nur `CorruptionException`. Das `.catch` steht deshalb
  jetzt **hinter** dem `.map` (dann sieht das `map` den degradierten Zustand nie und kann nichts
  cachen) und **invalidiert zusätzlich den Cache**. Die Anzeige darf degradieren, die
  SCHREIBWAHRHEIT nicht. Wer das `.catch` wieder nach oben zieht, baut den Bug zurück.
- **Bei der Master-Pause ist die RICHTUNG der Degradation die eigentliche Entscheidung.**
  `MasterPausePrefs.paused` hatte kein `.catch` (v1.24.0 ergänzt, Vorbild `auth_prefs`) — betroffen
  waren der zentrale Backstop in `AlarmUseCase.syncAlarms()`, die Gates von
  `DimScheduleUseCase`/`DndScheduleUseCase` und der `BootReceiver`. Degradiert wird auf **`false` =
  NICHT pausiert**: ein fälschlich wiederhergestellter Wecker klingelt hörbar und ist abstellbar,
  ein fälschlich unterdrückter ist STILL und fällt erst beim Verschlafen auf. Dieselbe Abwägung wie
  beim `DeviceLocalFlagsGuard`. Der Fehler wird geloggt — sonst ist er im Log von normalem,
  nicht pausiertem Betrieb nicht zu unterscheiden.
- **`DimOverlayPrefs` schützt seine 13 Lese-Flows über EINEN gemeinsamen `safeData`-Quell-Flow**
  (v1.24.0), nicht über 13 einzelne `.catch`-Blöcke — damit ist ein später ergänzter Flow nicht
  wieder ungeschützt. Degradiert wird auf leere Preferences, also auf den Default jedes Flows; für
  `renderState` heißt das `overlayOn = false`. Diese Richtung ist Absicht: **im Zweifel NICHT
  verdunkeln.** Bei voller Verdunkelung kann der Nutzer sein Gerät nicht mehr bedienen und den
  Dimmer nicht mehr abschalten — ein unerwartet heller Bildschirm ist das kleinere Übel.
- **Stille Degradierung darf nie zur Schreibwahrheit werden.** DataStore liest vor jedem Write
  erneut; wer einen Lesefehler auf „leer"/„Default" degradiert, speist genau diese Notlage-Leere in
  den nächsten Read-Modify-Write und überschreibt echte Nutzerdaten. Konkret festgelegt:
  - **`AlarmRepository`**: ein nicht dekodierbarer `active_alarms`-Wert (oder ein Lesefehler) sperrt
    die Persistenz für diesen Prozess und sichert das Roh-JSON unter `active_alarms_broken`. Vorher
    setzte der Init-Load `emptyList()`, erfüllte das Bereit-Signal — und der Delta-Sync hielt jede
    Schicht für neu und schrieb über Rohdaten UND Direct-Boot-Spiegel. Verloren gingen genau die
    Alarme, die sich nicht aus dem Kalender rekonstruieren lassen (manuelle), plus der einzige Weg
    zurück vor der ersten Entsperrung nach einem Reboot. `deleteAllAlarms()` räumt bewusst trotzdem
    (force): Master-Pause muss den Spiegel wirklich leeren, sonst re-armt der Direct-Boot-Restore
    pausierte Alarme. Dazu ein Bereit-Signal (`CompletableDeferred`) plus gemeinsamer Mutex für alle
    Ganzlisten-Schreibpfade — vorher lieferte `getAllAlarms()` im Prozess-Startfenster fälschlich
    eine leere Liste, und der nachträglich zurückkehrende Init-Load überschrieb Cache, DataStore und
    Spiegel mit seinem alten Snapshot. Deshalb liest auch `clearInternalAlarms` über
    `getAllAlarms()`, nicht über `activeAlarms.first()`: sonst wurde KEIN System-Alarm gecancelt,
    während Repository und Spiegel geleert wurden — der verwaiste Alarm feuerte trotz Master-Pause.
  - **`EncryptedPreferencesSerializer.readFrom()`** wirft Fehler unverändert weiter, statt still
    `defaultValue` zu liefern (für DataStore der gültige Ist-Zustand, den der nächste Write über den
    intakten Ciphertext schreibt). Bewusst **nicht** als `CorruptionException` umgedeutet: ein
    IO-Fehler oder eine Cancellation dürfen den `corruptionHandler` nicht auslösen, der würde die
    intakte Datei ersetzen; ein defektes Protobuf meldet der `delegateSerializer` ohnehin selbst.
    `writeTo()` schreibt einen LEEREN Zustand als 0-Byte-Datei (`readFrom` liest das als „noch
    nichts gespeichert") — bei unbrauchbarem Keyset scheiterte sonst auch der Ersatz-Write an
    derselben `aead`-Instanz und der Store blieb lese- UND schreib-tot. Keyset-Neuaufbau bleibt offen.
  - **`DimRuleRepository`**: `coerceInputValues` gilt für die ANZEIGE, `editRules()` liest **strikt**.
    Sonst schrieb das nächste `upsert()`/`delete()` — auch an einer völlig anderen Regel — einen auf
    den Feld-Default gefallenen Anker dauerhaft fest. `upsert`/`delete` laufen als Read-Modify-Write
    INNERHALB einer einzigen `dataStore.edit{}`-Transaktion (Vorbild `HueConfigRepository`), damit ein
    Doppel-Tap keine Änderung verliert und ein defektes JSON das Speichern abbricht statt den ganzen
    Regelbestand zu leeren (inklusive der bedeutungstragenden leeren Fensterliste).
- **Ein CE-DataStore-Read VOR der ersten Entsperrung wirft NICHT — er liefert still leere
  Preferences.** Am Emulator mit PIN im Zustand `RUNNING_LOCKED` nachgemessen: die Datei ist nicht
  öffenbar, `exists()` ist false, DataStore fällt auf `serializer.defaultValue` zurück und meldet
  ERFOLG. Im Log stand „📭 No saved alarms found in DataStore", `persistenceBlocked` blieb false, und
  der Cache galt als Wahrheit. **Und dieser Prozess stirbt beim Entsperren nicht** — er ist derselbe,
  in dem der Nutzer die App danach bedient (pid im Test unverändert), während der Init-Load nur EINMAL
  lief. Folge: die App hielt dauerhaft „keine Alarme" für wahr, obwohl `active_alarms` sie noch
  enthielt; der nächste Sync hielt jede Schicht für neu und schrieb Bestand UND Direct-Boot-Spiegel
  neu — der manuelle Wecker war weg, und im Log sah das wie ein normaler Erstsync aus. Beide anderen
  Wachen liefen dabei ins Leere (`keepManualAlarms` kann nichts schonen, was nicht in der Liste steht;
  `isPersistenceBlocked()` meldet nichts ohne gesetzte Sperre). Deshalb: `AlarmRepository` fragt VOR
  dem Read den `UserManager`, akzeptiert bei gesperrtem Nutzer KEIN Ergebnis (Sperre an, damit kein
  Write die Notlage-Leere festschreibt) und **lädt beim ersten Zugriff nach dem Entsperren nach** —
  aufgehängt in `awaitInitialLoad()`, weil da jeder Lese- und Ganzlisten-Schreibpfad durchgeht, PLUS
  `onStart` am `activeAlarms`-Flow (ein Bildschirm, der nur beobachtet, ruft keine Methode; der Haken
  fehlte im ersten Wurf und fiel erst am Gerät auf). `CalendarSelectionRepository` hat für dieselbe
  Prozess-Lage `retryWhen`.
- **Der Direct-Boot-Spiegel wird bei JEDEM erfolgreichen Load abgeglichen.** `persistToDataStore()`
  schreibt zuerst den DataStore, dann den Spiegel; fällt der zweite Schritt aus, divergieren beide —
  und die Divergenz war PERMANENT, weil nachgespiegelt nur wurde, wenn der Load selbst abgelaufene
  Alarme entfernt hatte. Der häufigste Sync-Zweig („unverändert – nur re-armen") schreibt das
  Repository gar nicht, der Spiegel konnte also wochenlang falsch bleiben und nach einem Reboot die
  falschen (oder keine) Alarme wiederherstellen. `saveAll` ist idempotent.
- **`TinkEncryptionException` wird in `EncryptedDataStoreFactory` als `CorruptionException`
  übersetzt** (nur die fängt DataStores Selbstheilung), plus `ReplaceFileCorruptionHandler`. Abwägung:
  ein nicht entschlüsselbarer Token ist ohnehin wertlos — EINE Neuanmeldung ist das kleinere Übel
  gegenüber einer App, die nie wieder einen Token speichern kann (Endlos-Re-Auth, keine Alarme).

### Gerätewechsel & Konfigurations-Datei (seit v1.23.0)

- **`DeviceLocalFlagsGuard` (erster Schritt in `initializeApp()`, best-effort) setzt beim erkannten
  Gerätewechsel gerätelokale Flags zurück.** Der `settings`-Store liegt richtigerweise im
  Android-Backup, enthält aber auch vier „schon abgelehnt"-Markierungen
  (`battery_prompt_dismissed`, `unused_app_restrictions_dismissed`,
  `timeoffice_health_prompt_dismissed`, `oem_hint_shown_<OEM>`). Nach einem Restore fragte die App auf dem neuen
  Gerät nie wieder nach Akku-Ausnahme und „Pause bei Nichtnutzung" — genau die zwei Einstellungen,
  die in diesem Projekt nachweislich Wecker verschluckt haben. **Ein selektiver Ausschluss einzelner
  Schlüssel ist unmöglich: ein Preferences-Store ist EINE Datei** — deshalb ein Wächter über
  `Build.FINGERPRINT` statt einer Backup-Regel. Zurücksetzen ist harmlos, die Hinweise erscheinen nur,
  wenn die Einstellung real fehlt; ein unerwartet klingelnder Wecker ist deutlich harmloser als ein
  unerwartet stummer. **Bewusste Grenze:** fehlt der Marker (Erstinstallation oder Bestandsinstall
  von vor dieser Version), wird NICHT zurückgesetzt — sonst verliert ein laufender Install seine
  Abweisungen. Die beiden Backup-Regel-Dateien müssen inhaltlich identisch bleiben, sonst sichert
  dasselbe Gerät je nach Android-Version Unterschiedliches.
- **Eine mitgesicherte Master-Pause wird über `MasterPauseUseCase.resume()` aufgehoben, NICHT indem
  `DeviceLocalFlagsGuard` den Schlüssel löscht** — deshalb steht `master_pause_enabled` bewusst
  NICHT in `DEVICE_LOCAL_KEY_PATTERNS`, und `resetIfDeviceChanged()` gibt stattdessen `Boolean`
  zurück, damit `initializeApp()` `resume()` rufen kann. Eine Pause ist mehr als das
  DataStore-Flag: `pause()` schreibt zusätzlich den Device-Protected-Spiegel (den der
  `BootReceiver` VOR der ersten Entsperrung liest), löscht die Alarme und reißt 6h-Wartung,
  Dimmer-Tick, DND-Tick, Hue-Planung und Pre-Alarm-Refresh ab. Wer nur den Schlüssel entfernt,
  hinterlässt eine App, die „nicht pausiert" ANZEIGT, deren Boot-Wiederherstellung aber dauerhaft
  gesperrt bleibt und deren Hintergrundketten nie wieder anlaufen — die gefährlichere Variante des
  Bugs, den der Wächter beheben soll. (Der erste Wurf machte genau das; `master_pause_until`
  existiert im Code überhaupt nicht — ein erfundener Schlüssel, der in zwei Produktivdateien, zwei
  Tests und dieser Datei stand. `MasterPausePrefs` kennt nur `master_pause_enabled`.)
- **Der Konfigurations-Export (Settings-Tab → „Konfiguration" → „Exportieren"/„Importieren")
  entscheidet durch AUSSCHLUSS, nicht durch Aufzählen.** Die Stores werden generisch exportiert,
  `ConfigBackupFilter` nimmt heraus, was nicht mit darf — damit ist eine neue Einstellung automatisch
  dabei statt beim nächsten Feature stillschweigend zu fehlen. Drei Ausschlussgründe:
  **Laufzeitzustand** (`active_alarms`, Skip-Marker, Dimmer-Render- und -Korrekturzustand,
  Wartungs-Zeitstempel und vor allem `master_pause_enabled` — ein importierter Pausenzustand lässt
  den Wecker STUMM, und niemand sucht die Ursache in einer Importdatei), **Gerätebezug/Zugangsdaten**
  (Hue-Bridge-Username und -IP, die von diesem Gerät registrierte Zen-Regel-ID, Tokens, Anmeldung,
  die kontogebundene Kalenderauswahl, der Marker des `DeviceLocalFlagsGuard`, `shift_config` — das
  geht bewusst über das typisierte Repository) und **gerätelokale Onboarding-Markierungen** (dieselbe
  Liste wie der Wächter, eine Quelle statt zweier Kataloge). **Der Filter gilt in BEIDE Richtungen:**
  beim Import wird jeder Schlüssel erneut geprüft, eine handbearbeitete oder ältere Datei kann nichts
  einschleusen; abgelehnte Schlüssel werden dem Nutzer BENANNT. `exclusionReason()` ist der EINE Ort
  der Entscheidung, `isExportable()` leitet sich davon ab. **Die Ausschlussliste ist aus einer
  Inventur ALLER `*PreferencesKey("…")` im Baum abgeleitet, nicht aus den Schlüsseln einiger Pakete:**
  der erste Wurf war lückenhaft, der erste echte Export enthielt genau drei Schlüssel und ALLE DREI
  gehörten nicht hinein — darunter `active_alarms`. Wer eine neue Laufzeitgröße einführt, trägt sie
  hier ein; ein Test hält jede Kategorie fest.
- **Der Import lehnt eine LEERE Definitionsliste ab.** kotlinx.serialization füllt ein fehlendes
  `definitions`-Feld stillschweigend mit `emptyList()`; aus „Datei unvollständig oder von Hand
  verstümmelt" würde lautlos „keine Schichten" — und das ist der dokumentierte Weg zu NULL ALARMEN
  (Save → Cache-Invalidierung → `observeExternalConfigChanges()` → `syncAlarms()` erkennt nichts →
  kalenderbasierte Alarme weg), während der Import „Erfolg: 0 Schichtdefinitionen" meldet. Dieselbe
  Überlegung wie `structuralRejection` für die beiden JSON-Regelwerke, nur für den wertvollsten Teil.
- **Der erwartete TYP eines importierten Wertes kommt vom SCHLÜSSEL, nicht aus der Datei**
  (`ConfigBackupUseCase.typeMismatch`). Vorher prüfte `applyValue` nur, ob sich der Wert in den
  BEHAUPTETEN Typ parsen lässt — damit entschied eine fremde Datei über den DataStore-Typ. Ein
  falsch typisierter Wert ist schlimmer als ein fehlender: er liegt reboot-fest in der
  `preferences_pb`, und der nächste Lesezugriff scheitert mit einer ClassCastException, BEVOR ein
  `?:`-Default oder `coerceIn` greifen kann (`snooze_minutes` als String → `AlarmPrefs` wirft bei
  jedem Alarm-Feuern, der `AlarmReceiver` verschluckt es, der Wecker bleibt stumm). Erwartung aus
  dem lokalen Bestand, sonst aus einer kleinen Liste bekannter Zahlen-Schlüssel; ein lokal
  unbekannter Schlüssel behält den Typ der Datei (bei einem Schlüssel aus einer neueren Version ist
  das die einzige Information, und er kann keinen bestehenden Leser beschädigen).
- **Der Schlüssel-Filter sagt nichts über den WERT.** Eine Exportdatei ist Text: von Hand
  bearbeitbar, aus einer älteren Version, unterwegs beschädigt. Zwei Zahlen sind deshalb
  zusätzlich bereichsgeprüft (`ConfigBackupFilter.rangeRejection`, bewusst nur diese zwei):
  `snooze_minutes` ≤ 0 legt den Schlummer-Alarm in die VERGANGENHEIT — er feuert sofort wieder und
  der Wecker lässt sich nicht mehr wegdrücken; `dnd_oncall_cutoff_min` außerhalb `0..1439` lässt
  `DndOnCallCutoffResolver`s `LocalTime.ofSecondOfDay()` werfen und tötet den DND-Tick bei jedem
  Lauf. **Beide sind zusätzlich im LESEPFAD geklemmt** (`AlarmPrefs`, `DndPrefs`) — genau wie
  `DimOverlayPrefs` es überall tut: das Android-Backup ist ein zweiter Weg, auf dem so ein Wert
  ankommt, und den sieht der Import nie.
- **Unlesbare Regelwerke werden beim Import BENANNT abgelehnt**
  (`ConfigBackupUseCase.structuralRejection` für `dim_rules`/`hue_schedule_rules`) — obwohl beide
  Leser einen kaputten Wert bereits abfangen. Genau dieser Rückfall auf „leere Liste" ist das
  Problem: der Import meldete Erfolg, und der Nutzer sah eine leere Regelliste ohne Grund. Der
  Import ist der letzte Ort, an dem das noch sagbar ist.
- **`ShiftConfig.withCodeAssignedTo()` (Kürzel-Vorschlagskarte) macht DREI Dinge zusammen, weil
  jedes einzeln wirkungslos wäre:** Muster ergänzen, **Zieldefinition aktivieren** (die
  `ShiftRecognitionEngine` beachtet seit v1.23.0 nur aktivierte Definitionen — eine Zuordnung an
  eine ausgeschaltete Schicht wäre ein garantierter Nichts-passiert-Klick, und die Karte bietet
  genau solche Kürzel an, weil sie von keiner aktivierten Definition getroffen werden) und das
  Kürzel **bei allen anderen Definitionen entfernen** (zwei Besitzer hieße: `findDefinitionFor`
  nimmt den ersten Treffer, und die LISTENREIHENFOLGE entscheidet still über die Weckzeit). Reine
  Funktion im Modell, damit alle drei Fallen testbar festgehalten sind.

### Auth

- **Kein `getOrElse { emptyList() }` auf Auth-behafteten Ergebnissen.** Für eine Wecker-App ist
  „leer" die gefährlichste Lüge — nicht von „du hast frei" zu unterscheiden.
- **GMS-Token-Cache liegt außerhalb des App-Speichers** und überlebt die Deinstallation. Nur
  `GoogleAuthUtil.clearToken()` räumt ihn ab.
- **`auth_prefs` braucht `corruptionHandler` UND `.catch{}` am `authData`-Flow.** Dort liegt der
  Zustand, der die ganze App gated (`login_status`/`user_email`): eine beschädigte `preferences_pb`
  wäre dauerhaft lese- UND schreib-tot gewesen, und ein Upstream-Fehler hätte in den
  ViewModel-Collectorn die App beendet. Degradation auf „nicht angemeldet" löst einen Re-Login aus —
  das ist hier der richtige Ausgang.
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
- **`OAuth2TokenManager.refresh()`s Rotation-Chain-Check muss den NEUEN Token gegen die ID des
  ALTEN prüfen, nicht zwei „previous"-Zeiger gegeneinander.** `TokenData.validateRotation(id)` ist
  `this.previousRotationId == id` — der korrekte Aufruf ist also
  `storedToken.validateRotation(currentToken.rotationId)` („ist `storedToken` durch Rotation direkt
  aus `currentToken` entstanden?"), NICHT `currentToken.validateRotation(storedToken.
  previousRotationId)` (vergleicht zwei fremde Vorgänger-IDs miteinander — das ist nur bei
  `storedToken == currentToken` je wahr). Die falsche Variante schlägt bei JEDER legitimen
  gleichzeitigen Rotation fehl: `OAuth2TokenManager` ist ein Hilt-`@Singleton` ohne Mutex um
  `getValidToken()`/`refresh()`, und `AlarmMaintenanceService`, `CalendarPreAlarmRefreshWorker`,
  `CalendarUseCase` und `AuthUseCase.hasCalendarAuthorization()` rufen ihn alle unabhängig auf —
  zwei nahezu gleichzeitige Refreshs sind der Normalfall, kein Diebstahl. Die falsche Variante
  löste bei jedem Treffer `tokenRepository.clear()` + Zwangs-Re-Login aus, obwohl der erste Refresh
  längst erfolgreich war. `TokenDataTest` hält die Rotationsketten-Semantik jetzt fest.

### Fehlerbehandlung

- **`SafeExecutor.safeExecute()` wirft `CancellationException` WEITER, statt sie in einen `AppError`
  zu verpacken.** Eine Cancellation ist kein Fehler des Aufrufs, sondern die Ansage, dass die
  umgebende Coroutine beendet wird — sie muss die Aufrufkette hochlaufen, und `Result.failure` ist
  keine Aufrufkette. Verpackt verlor sie ihre Identität, und dadurch lief der ausdrückliche
  `catch (e: CancellationException) { throw e }` der Delta-Sync-Schleife ins Leere:
  `AlarmUseCase.scheduleSystemAlarm()` ist über `safeExecute` gewrappt, die Cancellation kam dort als
  gewöhnliches Failure an und wurde als Fehler EINES Events verbucht — die Schleife lief stur über
  alle restlichen Events weiter, ohne einen einzigen zu re-armieren, während die Abschlusszeile
  „complete" meldete. Dasselbe gilt für `AlarmRepository.getAllAlarms()`/`isPersistenceBlocked()`:
  eine Cancellation aus `awaitInitialLoad()` sagt nichts über den Bestand und darf nicht als
  „Persistenz gesperrt" gedeutet werden.

### Kalender-Datenfluss

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
- **Kein Fehler darf als leeres Erfolgsergebnis durchrutschen** — für eine Wecker-App ist „leer" die
  gefährlichste Lüge, und `syncAlarms()` deutet eine leere Eventliste als „keine Schichten" und
  löscht ALLE Alarme (System, Repository, Direct-Boot-Spiegel). Vier Stellen sind deshalb festgelegt:
  `CalendarUseCase.getCalendarEventsWithCache()` wirft bei **Totalausfall** aller angefragten
  Kalender den ersten Fehler (Teilerfolg bleibt bewusst Erfolg — gleiche Abgrenzung wie
  `CalendarViewModel.resolveCalendarAuthorizationOutcome()`); `CalendarPreAlarmRefreshWorker` und
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

### UI-Texte

- **Der Akku-Onboarding-Screen darf keine Einstellungen versprechen.** `MainScreen` feuert
  `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` mit `package:`-Data — das ist Androids
  **Systemdialog** („Zulassen, dass die App immer im Hintergrund läuft?"), ein Tipp, keine Liste.
  Der Screen beschrieb eine vierstufige Anleitung, die niemand je zu sehen bekam. Wer
  den Ablauf ändert, muss den Text mitändern — und umgekehrt.
- **Kernpunkt einmal, konkret, mit dem echten Einsatz.** Für eine Wecker-App heißt das nicht
  „Background-Jobs werden gestoppt", sondern „der Wecker bleibt still". Tiefere Erklärung gehört
  hinter „Warum ist das nötig?", nicht ein zweites Mal auf den Screen.
- **Ein Hinweistext nennt Karten- und Knopfbeschriftung wortgleich mit der UI, NIE eine Position.**
  „die Karte darunter" im AUTHORIZATION_LOST-Text zeigte auf die Alarm-Status-Karte; der Knopf
  „Kalender-Zugriff erneuern" sitzt eine Karte weiter. Positionen verschieben sich beim nächsten
  Layout-Umbau lautlos, Beschriftungen fallen beim Umbenennen auf.
- **Beispiele in Hinweistexten aus deklarierten Listen zusammenführen, die ein Test gegen die echte
  Standardkonfiguration prüft** (`ShiftConfigScreenTextTest`) — der Konfigurations-Hinweis nannte
  Muster („IMCF, IMCS, IMCN, IMCZ") und behauptete „erkannt wird über die Muster, nicht über den
  Schichtnamen allein"; beides hatte derselbe Arbeitsdurchgang unwahr gemacht, der den Text einführte.
  Zwei Bildschirme widersprachen sich (der `ShiftEditDialog` sagte es korrekt). Drift muss auffallen,
  nicht stumm bleiben.
- **Kein Text darf eine Anzeige behaupten, die es nicht gibt, und kein Zustand darf sich als
  anderer ausgeben.** Vier Fälle in einer Runde gefunden und behoben: „Zeige 5 von N Events" auf
  einer Karte, die überhaupt keine Events listet; „Schichttypen werden noch geladen" für einen
  Ladevorgang, der DAUERHAFT gescheitert ist; „⚠️ Aktiver Fehler" auf der Dimmer-Karte, obwohl der
  Nutzer den Dimmer nie eingeschaltet hat (das entwertet genau die roten Karten daneben, an denen
  der Wecker wirklich hängt); und „Verstanden" als Beschriftung des ABBRECHEN-Knopfs, während
  daneben der Knopf steht, der wirklich weiterführt. Dazu: eine nicht lesbare Schicht-Konfiguration
  rendert im Konfigurations-Screen keine stumme leere Liste mehr, sondern sagt, dass sie nicht
  lesbar ist und NICHT überschrieben wird.
- **Eine deaktivierte Schichtdefinition darf keine Weckzeit anzeigen.** Die Erkennung überspringt
  sie vollständig, es entsteht kein Alarm — die Liste zeigte trotzdem „Alarm: 05:30" in der
  Akzentfarbe. Eine angezeigte Weckzeit, die nie gestellt wird, ist die gefährlichste Anzeige, die
  eine Wecker-App haben kann; das Gegenstück `isSilent` hat aus demselben Grund ein eigenes Icon.
- **Der Kürzel-Zuordnungsdialog ist scrollbar.** Bei fünf Standard-Definitionen plus Erklärtext war
  der letzte Knopf auf schmalen Geräten abgeschnitten — und er ist der EINZIGE angebotene Weg für
  dieses Kürzel: kein Muster, keine erkannte Schicht, kein Wecker. Dieselbe Fehlerklasse wie der
  unerreichbare „Auf Standardwerte zurücksetzen"-Knopf desselben Screens.
- **Deutsche Nutzer-Texte in `UITextConstants` ohne Aufrufer löschen, nicht liegen lassen.** Sie sehen
  wie aktive UI-Texte aus und werden sonst als Vorlage weitergeschleppt (die Countdown-Texte hatten
  nach dem Entfernen von `CountdownTimer.kt` nur noch ihre Deklaration).

### Compose-Layout

- **`Row(SpaceBetween) { Column { … }; Switch }` braucht `weight(1f)` am Column.** Ohne das nimmt
  der Beschreibungstext die volle Breite und der Schalter landet außerhalb der Karte. Eine feste
  `.width(…dp)` als Pflaster bricht bei schmalem Display oder großer Schrift.
- **`ButtonDefaults.ContentPadding` = 24dp pro Seite.** In schmalen, geteilten Buttons bleibt zu
  wenig für die Schrift, und Compose bricht mitten im Wort. Dafür gibt es **`CompactButton`** und
  **`CompactOutlinedButton`** (in `ui/components/CompactActionButton.kt`) — **nur** für schmale,
  geteilte Buttons, nicht für ganzbreite, wo ein Zweizeiler gewollt ist.
- **Eine `LazyColumn` in einer `Column` braucht `weight(1f)`.** Ohne sie misst sie sich auf ihre
  Inhaltshöhe und frisst die gesamte Resthöhe — im `ShiftConfigScreen` war der Knopf „Auf
  Standardwerte zurücksetzen" darunter dadurch **unerreichbar** (bei fünf Schichten plus der
  Kürzel-Karte, am Gerät nachgeprüft). Ein zweiter Spacer mit `weight` daneben hilft nicht, er
  konkurriert nur.
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
  `--offline` nutzen — der Cache ist durch lokale Builds des Nutzers warm; **Ausnahmen:
  `assembleRelease` und `connectedDebugAndroidTest` brauchen Netz** (siehe „Build & Development
  Commands"). Selbst bauen, installieren, messen, A/B-testen statt nur durch Inspektion zu
  verifizieren. `emulator`-Binary
  ist nicht auf PATH:
  `C:\Users\Christoph\AppData\Local\Android\Sdk\emulator\emulator.exe`. Bibliotheks-Quelltext bei
  Bedarf trotzdem direkt lesbar: `~/.gradle/caches/modules-2/files-2.1/<group>/…-sources.jar`.
  Details siehe Memory `env-local-build-and-emulator`.
- **„Warnungen plötzlich weg" ist kein Fortschritt.** `org.gradle.configuration-cache=true`
  (in `gradle.properties`): Die Deprecation-Warnungen entstehen in der Konfigurationsphase. Wird
  der Konfigurations-Cache wiederverwendet, erscheinen sie schlicht nicht neu. Nach jeder Änderung
  an `build.gradle.kts`/`gradle.properties` sind sie wieder da.
- **Die Warnung lügt:** Ihr Vorschlag, `android.builtInKotlin`/`android.newDsl` zu entfernen und
  auf built-in Kotlin zu migrieren, zerlegt das Dreieck aus KSP 2.x, KGP 2.x und AGP 9.x. Beide
  Flags bleiben auf `false`.
- **Debug-Build** schreibt VERBOSE ins Datei-Log (`CFAlarmApplication.onCreate()`, Variable
  `fileLogMinPriority`). Release-Logs enthalten **nur WARN+** → erfolgreiche Operationen sind dort
  unsichtbar. Für Diagnose immer einen Debug-Build verlangen — **Ausnahme**: die
  Vollbild-Sichtbarkeits-Diagnostik loggt bewusst WARN und ist auch im Release da.
- **Die CI baut auch den Release-Pfad** (`.github/workflows/ci.yml`: `testDebugUnitTest`, `lintDebug`,
  `assembleDebug`, dann `lintVitalRelease` + `assembleRelease`). Vorher wäre ein kaputter
  Release-Build erst beim Ausliefern aufgefallen — und genau dort sitzt mit R8 das Risiko. Ohne
  Keystore-Secret entsteht `app-release-unsigned.apk`; das ist Absicht (geprüft werden
  Shrinking/Optimierung und Release-Lint, nicht das Signieren) und kann nicht unbemerkt ausgeliefert
  werden, weil sich eine unsignierte APK weder installieren noch hochladen lässt. Signiert wird lokal.
- **Grüne Unit-Tests sind kein Startbeweis.** Der Crash vom 05.08.2026 (Property nach `init{}`) fiel
  durch 329 grüne Tests und einen grünen Build; gefunden hat ihn erst die Installation. Dafür gibt es
  jetzt `ColdStartSmokeTest` (`app/src/androidTest/`, drei Fälle: Application kommt hoch, MainActivity
  erreicht RESUMED, und übersteht einen ZWEITEN Start in derselben Sitzung — deckt nicht-idempotente
  `initialize()` und dauerhaft gecancelte Singleton-Scopes ab) gegen den **echten, unveränderten
  Hilt-Graphen**, bewusst ohne `hilt-android-testing` und ohne eigenen Runner: die braucht man nur, um
  Bindings zu ERSETZEN. Ersetzt trotzdem keinen Gerätetest — mehrere Fehler dieser Runde
  (Import-Aktualisierung, unerreichbarer Knopf) hat erst das Gerät gezeigt.
- Debug-SHA-1 ist in der Google Cloud Console eingetragen (verifiziert 14.07.).
- Getestet wird auf einem echten Gerät **und einem Emulator als Zweitgerät**; Logcat-Auszüge
  kommen vom Nutzer.
- **Agenten committen nicht selbst in einen gemeinsamen Baum.** In der Härtungs-Runde zu v1.23.0
  liefen sechs Fix-Pakete parallel im selben Arbeitsverzeichnis; ein Agent hat beim Committen die noch
  unkommittierten Dateien eines fremden Pakets mitgenommen (der Commit
  „fix(persistenz): stille Degradierung…" enthält deshalb auch das Paket „Erkennungs-Engine und
  Musterabgleich"). Künftig: entweder committet der Orchestrator EINMAL nach Abschluss aller Agenten,
  oder jeder Agent arbeitet in einem eigenen `git worktree`.