# Architektur im Detail

Ausgelagert aus `CLAUDE.md` (Wortlaut unverändert); dort stehen Schichtenbild und Paketbaum.

**Hilt-Module** (`di/modules/`): `DataModule` (genau **zwei** unverschlüsselte
`DataStore<Preferences>`: `@MainDataStore` = `settings`, `@HueDataStore` = `hue_settings`, beide mit
`ReplaceFileCorruptionHandler`, plus `ErrorHandler` — **kein** Token-Store, **kein**
`TinkEncryptionHelper`), `RepositoryModule`, `UseCaseModule`, `HueModule`, `ServiceModule` (genau
vier Provider; `BackgroundServiceManager` hat bewusst seinen eigenen `@Singleton @Inject`-Konstruktor).
Alle injizierten `Service`/`BroadcastReceiver` sind
`@AndroidEntryPoint`.

**Auth** (`auth/`): `CredentialAuthManager` (Google Sign-In via `androidx.credentials`),
`OAuth2TokenManager` (Token holen/refreshen via AuthorizationClient; offline ist `hasResolution` KEIN Anmeldefall), `DataStoreTokenRepository`
(Tink/AES-256-GCM). Der Token liegt im DataStore `token_data_v2_encrypted`, den sich
`DataStoreTokenRepository` per `EncryptedDataStoreFactory` **selbst** baut.

**Alarm** (`service/`, `alarm/`): `AlarmMaintenanceService` (kurzlebiger Foreground-Service,
`specialUse`, alle 6 h per Exact-Alarm: Token-Refresh → Kalender-Events (bei jedem Lauf) →
Alarm-Erzeugung), `AlarmSoundService` (`mediaPlayback`), `AlarmReceiver`, `BootReceiver`,
`BackgroundServiceManager`.

**Schichterkennung**: `shift/ShiftRecognitionEngine` bildet Kalender-Events auf `ShiftDefinition` ab,
Ergebnis adaptiv gecacht (2–30 s). `ShiftConfig` im `@MainDataStore`.

**Hue** (`hue/`): Discovery per mDNS/N-UPnP/offiziellem Endpunkt, `HueBridgeConnectionManager`
(Singleton), `HueApiClient` (**handgebautes OkHttp + Gson**, kein Retrofit — die V1-Semantik
„HTTP 200 auch bei Ablehnung" braucht die Body-Auswertung in `HueV1Envelope`), `HueSmartScheduler`
(WorkManager für Tagesplanung und Pre-Alarm-Checks). Eine Regel setzt das Licht auf genau eine von
drei Arten: Szene, manuell oder Sonnenaufgang. Hue-Konfiguration im `@HueDataStore`.

**Navigation**: eigener `NavigationState` + `MainTab`-Enum (`HOME, WECKER, STATUS, SETTINGS, HUE,
DIMMER`), **kein** Navigation-Compose. `MainScreen` ist die Compose-Wurzel (Unterscreens,
Onboarding-Gates, `BackHandler`), `MainContentScreen` verteilt die Tab-Inhalte.

**Shared State**: `di/state/CalendarStateHolder` — Hilt-Singleton mit `StateFlow`, gebunden über
`@Singleton @Inject constructor` (kein Modul).
`CalendarViewModel` **schreibt**, `ShiftViewModel` **liest** (Einbahnstraße).
