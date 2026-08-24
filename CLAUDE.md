# CLAUDE.md

Leitfaden für Claude Code (claude.ai/code) in diesem Repository.

## Wegweiser: die Zusicherungen liegen in Skills

Diese Datei enthält nur die Regeln, deren Bruch **einen stummen oder verwaisten Wecker** erzeugt.
Alles Übrige — die vollständige Regelliste je Bereich **und** der Hergang dazu (welcher Bug sie
erzwungen hat, welche Messung sie belegt, welche Alternative verworfen wurde) — liegt in
Projekt-Skills unter `.claude/skills/`. Deren Beschreibungen sind immer im Kontext, ihr Inhalt lädt
erst bei Bedarf.

| Skill | Zuständig für |
|---|---|
| `cfalarm-wecker-und-boot` | Weckerauslösung, Vollbild, Snooze, Boot, Direct Boot, 6h-Wartung, Master-Pause |
| `cfalarm-kalender-und-schichten` | Kalender-Datenfluss, Eventlisten-Vollständigkeit, Schichterkennung, TimeOffice |
| `cfalarm-dimmer-und-dnd` | Schicht-Dimmer und „Nicht stören" |
| `cfalarm-hue` | Philips Hue: API-Semantik, Bridge, Regeln, Vorschau |
| `cfalarm-persistenz-und-auth` | DataStore, Token-Rotation, Fehlerbehandlung, Gerätewechsel/Export |
| `cfalarm-ui-und-navigation` | Compose-Layout, Nutzertexte, Zurück-Verhalten |
| `cfalarm-bauen-und-testen` | Gradle-Eigenheiten, Emulator, Gerätetests |
| `cfalarm-release-und-changelog` | Versionsbump, Changelog, Release-Ablauf |
| `cfalarm-arbeit-abschliessen` | Was Fertigsein heißt — und was die Schleuse NICHT prüfen kann |

**Vor einer Änderung in einem dieser Bereiche den zugehörigen Skill lesen.** Die Regel unten sagt
*was*, der Skill sagt *warum* — und ohne das Warum baut man dieselbe Falle in neuer Form nach.

**Pflege:** Neue Erkenntnisse mit Hergang gehören in den Skill, hierher nur die normative Zeile —
und auch die nur, wenn ihr Bruch den Wecker kostet.

**Diese Datei hat ein Budget, und es wird gemessen.** Sie wuchs vom 22.07. bis 14.08.2026 von
24.763 auf **149.571 Zeichen — 429 unter dem Limit der Harness**, ohne dass es jemand bemerkte;
am 11.08. allein kamen 53 k dazu. Das war kein Schlamperei-Problem: jede Zeile stammte aus einem
echten Bug, jede Ergänzung war für sich berechtigt. Gefehlt hat, dass **niemand die Summe angesehen
hat** — und die Regel dagegen war reine Prosa. Deshalb jetzt `tools/doku/pruefe_budget.py`:
Warnung ab 30 k, CI-Fehlschlag ab 40 k, plus ein `SessionStart`-Hook, der sich beim Überschreiten
von allein meldet. Gegen die echte Historie geprüft — hätte am 03.08. angeschlagen.
**Wenn die Meldung kommt, ist Verschieben in einen Skill die Antwort, nicht das Anheben der
Schwelle.** Dort kostet Wissen erst beim Lesen etwas; `reference/*.md` darf deshalb wachsen.

**Ein `: ` in einer `description` MUSS gequotet werden.** Ein unquotierter YAML-Skalar endet am
ersten Doppelpunkt-mit-Leerzeichen; das Frontmatter wird dann unlesbar, und die Oberfläche zeigt
statt der Beschreibung die H1-Überschrift — der Skill existiert, triggert aber praktisch nicht mehr.
Am 17.08.2026 traf das drei von acht Skills, und aufgefallen ist es nur zufällig beim Blick in die
geladene Liste. Dagegen stehen jetzt zwei Netze: `python tools/skills/pruefe_skills.py .claude/skills`
läuft in der CI, und ein `PostToolUse`-Hook (`.claude/settings.json`) prüft nach jedem Schreiben an
einer `SKILL.md`. Wer einen Skill ergänzt, braucht dafür nichts zu tun — außer die Meldung zu
beachten, wenn sie kommt.

**Ein neues Skill-Verzeichnis wird erst nach `/reload-skills` geladen.** Claude Code beobachtet nur
Verzeichnisse, die beim Sessionstart existierten. Ein neu angelegtes `.claude/skills/` erscheint
also nicht von selbst — Änderungen an bestehenden `SKILL.md` dagegen schon.

## Git & GitHub Workflow

Es gilt der globale Default aus `~/.claude/CLAUDE.md`. Projekt-spezifisch:

- Branch-Präfixe: `feature/<kebab-case>`, `fix/<kebab-case>`, `chore/<kebab-case>`.
- **Mehrere Claude-Sessions arbeiten parallel an diesem Repo** (lokal **und** Cloud-Sessions auf
  `claude/*`-Branches, die eigenständig nach `origin/main` mergen). Deshalb: **immer `git fetch` +
  Divergenz prüfen, bevor** auf `main` gebumpt/committet/gepusht wird; bei Divergenz mergen statt
  force-push. `versionCode` muss höher als der **höchste je vergebene** sein.
- **Im geteilten Arbeitsbaum gezielt stagen**: `git status` unmittelbar vor dem Commit, nie
  `git add .` / `commit -a`, kein `git stash`.
- **Es gibt keine Handoff-Datei mehr, und es soll keine neue geben.** Der Projekt-STAND wird
  abgeleitet (`tools/sitzungsstart.py`: Branch, Arbeitsbaum, Version, Testzahl **mit Alter**,
  Abstand zu `origin/main`) — von Hand gepflegt veraltete er lautlos und wuchs zweimal zu, zuletzt
  auf 26.201 Zeichen.
- **Offene Punkte liegen an zwei Orten; das Kriterium ist, ob es öffentlich stehen darf.** Das Repo
  ist öffentlich und bleibt es (GitHub Pages liefert aus `main` `/docs` die Datenschutz-URL für die
  OAuth-Verifizierung). Deshalb: **belegte, harmlose Aufräumarbeit als GitHub Issue** (so seit
  24.08.2026, derzeit #15–#18), **unbelegte Fehlerhypothesen über eine Wecker-App im Play Store
  ins Memory `project_offene_punkte`** — die gehören nicht in ein öffentliches Issue. Die frühere
  Pauschalregel „keine Issues" war zu grob. In eine Datei im Repo gehören sie weiterhin nicht.
  **Vor dem Anlegen BEIDE Orte prüfen** (`gh issue list`): der Sessionstart-Hook zeigt nur die
  Memory-Überschriften und kennt die Issues nicht — wer nur ihm glaubt, legt Dubletten an.
- **Vor `git merge`/`git push` läuft die Schleuse** (`tools/schleuse/pruefe_schleuse.py`,
  `PreToolUse`-Hook) und prüft mechanisch: Geheimnisse, Skills, Doku-Budget, Changelog-Seite,
  Code-Invarianten, Aufraeum-Reste, Tests (inkl. androidTest-Uebersetzung), Lint — beim Push
  von `main` zusätzlich Changelog-Eintrag, Bump und `versionCode` gegen `origin/main`.
  **Ein grüner Lauf ist die untere Schranke, nicht das Ziel**;
  was sie nicht prüfen kann, steht im Skill `cfalarm-arbeit-abschliessen`. Notausgang für den Fall,
  dass die Schleuse selbst kaputt ist: `CFALARM_SCHLEUSE=aus`.

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

- **`assembleRelease` braucht Netz**: die nur mit Minify laufende Task
  `produceReleaseComposeMapping` zieht eine Abhängigkeit, die nicht im Offline-Cache liegt;
  `--offline` scheitert mit dem irreführenden „Configuration cache state could not be cached".
  Signiert wird nur, wenn `keystore.properties` ODER `KEYSTORE_PASSWORD` da ist — sonst entsteht
  `app-release-unsigned.apk` (Absicht, siehe CI im Skill `cfalarm-bauen-und-testen`).
- **`connectedDebugAndroidTest`**: nicht `--offline`, Gerät MUSS wach sein, und die Task
  **deinstalliert die App danach** (ein eingerichteter Emulator-Zustand ist hinterher weg).
- **Bei zwei angesteckten Geräten** läuft `installDebug`/`connectedDebugAndroidTest` auch auf dem
  produktiven Fairphone. Sicherer Weg über `assembleDebug` + `adb -s emulator-5554 …` — Details im
  Skill `cfalarm-bauen-und-testen`.

## Prerequisites

`keystore.properties` im Projekt-Root:

```
googleWebClientId=<client-id>.apps.googleusercontent.com
storeFile=../cf-alarm-release.keystore
storePassword=<password>
keyAlias=cf-alarm-key
keyPassword=<password>
```

Fehlt `googleWebClientId`, wirft der Build eine `GradleException` (kein Fallback, Absicht).

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

**Schichterkennung**: `shift/ShiftRecognitionEngine` bildet Kalender-Events auf `ShiftDefinition` ab,
Ergebnis adaptiv gecacht (2–30 s). `ShiftConfig` im `@MainDataStore`.

**Hue** (`hue/`): Discovery per mDNS/N-UPnP/offiziellem Endpunkt, `HueBridgeConnectionManager`
(Singleton), `HueApiClient` (Retrofit/OkHttp mit eigenem TrustManager), `HueSmartScheduler`
(WorkManager für Tagesplanung und Pre-Alarm-Checks). Hue-Konfiguration im `@HueDataStore`.

**Navigation**: eigener `NavigationState` + `MainTab`-Enum (`HOME, WECKER, STATUS, SETTINGS, HUE,
DIMMER`), **kein** Navigation-Compose. `MainScreen` ist die Compose-Wurzel (Unterscreens,
Onboarding-Gates, `BackHandler`), `MainContentScreen` verteilt die Tab-Inhalte.

**Shared State**: `di/state/CalendarStateHolder` — Hilt-Singleton mit `StateFlow`.
`CalendarViewModel` **schreibt**, `ShiftViewModel` **liest** (Einbahnstraße).

## Key Constraints

- `AD_ID` ist bewusst blockiert (`maxSdkVersion="0"`) — nicht reaktivieren.
- `USE_EXACT_ALARM` und `USE_FULL_SCREEN_INTENT` sind Kernberechtigungen.
- Die DataStore-Namensräume bleiben getrennt (settings / hue / tokens) — nie zusammenlegen.
- `TinkEncryptionHelper.getInstance()` muss ein Singleton bleiben und ist der **einzige** Zugriff.
  Kein Hilt-Provider dafür (zöge den Keyset-Read in `directBootAware`-Komponenten).
- **Es gibt bewusst keinen `@TokenDataStore`-Qualifier.** `DataModule` stellte bis v1.11.2 einen
  bereit (`oauth_tokens`), den **niemand** injizierte — am Gerät verifiziert: die Datei existierte
  gar nicht. **Die Falle ist die Namensgleichheit:** `DataStoreTokenRepository` hat ein privates
  Feld `tokenDataStore` (den verschlüsselten Store) — wer nur den Namen sieht, hält den Provider
  für benutzt und holt ihn zurück. Dann gibt es eine zweite Wahrheit, und ein Klartext-Store für
  Tokens ist genau das, was Tink verhindern soll.
- **Jeder `preferencesDataStore` braucht einen `corruptionHandler`.** Ohne ihn blockiert eine
  beschädigte `preferences_pb` dauerhaft auch das SCHREIBEN, reboot-fest.
- **R8/Minify ist AN** (seit v1.23.0; APK 19,8 → 10,9 MB). `-dontshrink`/`-dontoptimize` müssen in
  `proguard-rules.pro` auskommentiert bleiben.
- `minSdk = 26`, `compileSdk = 37`, `targetSdk = 37`; Java 17 mit core library desugaring.

---

## Grundregeln

- **Eine Funktion ohne Bedienoberfläche gibt es für den Nutzer nicht.** Wer eine Fähigkeit einbaut,
  baut die Stelle mit, an der man sie **sieht**, **auslöst** und ihren **Zustand abliest**.
- **Der Sweep über frisch geschriebenem Code beweist wenig** — jeder Fix schafft neue Prüffläche.
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

## Die Regeln, deren Bruch den Wecker kostet

Vollständige Regellisten und Belege in den Skills oben. Was hier steht, gilt immer.

### Alarme anlegen, ändern, löschen

- **Löschen heißt IMMER: erst `cancelSystemAlarm()`, dann `deleteAlarm()`.** Umgekehrt entsteht ein
  armierter Alarm, den weder Repository noch Direct-Boot-Spiegel kennen — unsichtbar UND
  unabbrechbar bis zum nächsten Neustart.
- **`clearInternalAlarms()` fragt ZUERST `isPersistenceBlocked()` und scheitert laut.** Räumen ohne
  Cancellen ist die gefährliche Kombination.
- **Die datengetriebenen Räumzweige von `syncAlarms()` schonen MANUELLE Alarme**
  (`keepManualAlarms = true`) — nur der manuelle Alarm lässt sich nicht aus dem Kalender
  rekonstruieren. Ausdrückliche Abschaltungen räumen weiter ALLES.
- **Der Delta-Sync hat pro Event ein eigenes `try/catch`, das `CancellationException` weiterwirft** —
  sonst bricht ein einzelner abgelehnter Alarm den gesamten Sync ab.
- **Die Wecker-Identitaet haengt NICHT allein an der Kalender-Kennung.** Ein abonnierter
  Dienstplan-Feed bekommt von Google alle paar Tage neue Event-IDs fuer dieselben Termine (am
  Geraet gemessen: 11 geloescht, 11 angelegt, Schnittmenge der IDs null, Schichten und Weckzeiten
  unveraendert). `syncAlarms()` paart deshalb ERST (Kennung, sonst Weckzeit + Schicht) und
  entscheidet DANN; bei reinem Kennungswechsel wird die neue Kennung still uebernommen, die
  `AlarmInfo.id` bleibt. Dieselbe Frage stellt die Boot-Wiederherstellung - sonst loescht ein
  Neustart im Rotationsfenster den GESAMTEN Bestand.
- **Die Skip-Erkennung nimmt den WECKZEITPUNKT als Anker, nicht nur die id** (in beiden Gates,
  auch im Backstop). `skipNextAlarm()` loescht den Eintrag; nach einer Kennungsrotation gaebe es
  sonst nichts mehr zu paaren, und der uebersprungene Wecker klingelte am freien Morgen.
- **Verstrichene Weckzeit ist KEINE entfernte Schicht** (`expiredEventIds`).
- **Alle `setAlarmClock()`-Aufrufstellen fangen die entzogene Exact-Alarm-Berechtigung**
  (`setExactOrInexact`: try/catch + inexakter Fallback). Ein verzögerter Wecker schlägt keinen Wecker.
- **Das Skip-Flag läuft zeitbasiert ab, nicht per ID-Match** — plus Gate in `syncAlarms()` UND
  Backstop in `scheduleSystemAlarm()`.
- **`ShiftConfig.autoAlarmEnabled = false` ist eine ECHTE, sofortige Pause**, kein stilles `return`.
- **Ein freigegebener Tag („Tag freigeben") braucht Gate UND Backstop — und nimmt MANUELLE Wecker
  aus.** Der Anker ist überall die Weckzeit (vier Stellen). Ohne den Backstop holt der
  `BootReceiver` den Wecker nach einem Neustart zurück; ohne die Ausnahme für manuelle Wecker
  löscht die Freigabe einen Wecker, den kein Kalender rekonstruieren kann — und der Backstop
  verhindert zusätzlich, dass an dem Tag überhaupt einer gestellt werden kann.

### Wecken, Vollbild, Snooze

- **`_alarmActive = true` VOR `startForeground()`** — sonst schließt sich das Vollbild sofort.
- **Kein `startActivity()` aus dem `AlarmReceiver`** — einziger Weg ist `setFullScreenIntent()`.
- **Eine Instanz besitzt den Wecker**: `AlarmSoundService` hält Ton, Vibration und die einzige
  Wecker-Notification (ID 2002). Der `AlarmReceiver` darf **keine eigene** posten.
- **`AlarmSoundService`: `stopSelf(startId)` und `START_REDELIVER_INTENT`** — nie blankes
  `stopSelf()`/`START_STICKY` (stummer Zombie-Service bei sonst normal aussehendem Log).
- **Vollbild-Dismiss und -Snooze teilen eine Einweg-Sperre** (`OneShotAlarmHandoff.claim()`).
- **In `onNewIntent()` wird ERARBEITETER Zustand nur bei einem ANDEREN Weckvorgang verworfen.**
  Aus dem Intent abgeleitete Werte werden immer frisch gelesen; ein Fehlerhinweis und die
  Einweg-Sperre gehoeren dagegen zum laufenden Vorgang. Die Wecker-Notification traegt denselben
  PendingIntent auch als `setContentIntent()` — ein Tipp darauf ist DERSELBE Wecker und darf einen
  Schlummer-Fehlerhinweis nicht wegwischen. Vergleich ueber die Alarm-Kennung.
- **`AlarmFullScreenActivity` braucht `onNewIntent()` mit `setIntent()`** (`launchMode="singleTask"`).
- **Snooze braucht `snoozeAlarmAction(id)`**, nicht `enhancedAlarmAction(id)`.
- **Ein AUFGEGEBENER Armierungsversuch hinterlaesst nichts Scharfes.** Scheitert das Vormerken
  NACH dem Planen, wird der bereits gestellte Alarm abgebrochen, bevor der Fehlschlag gemeldet
  wird — sonst steht er scharf im AlarmManager, waehrend die App „kein weiterer Weckruf" sagt, und
  ohne Merker kann ihn niemand mehr abbrechen. Der WIEDERHERSTELLUNGS-Lauf nach einem Neustart
  raeumt dabei nichts weg: dort ist der Merker die Vorlage, nicht das Ergebnis.
- **Ein schwebender Snooze muss einen Reboot überleben** (`restorePendingSnoozes()`), und beide
  Anlässe armieren über dasselbe `armSnooze()` — sonst trifft ein späterer Abbruch ihn nicht mehr.
- **Der Snooze-Merker ist serialisiert und schreibt mit `commit()`**, nicht `apply()`.
- **Der Schlummer-Read in `AlarmReceiver` MUSS hinter `userUnlocked` gegated sein** — `AlarmPrefs`
  liegt im CE-Storage, ungegatet bleibt der Wecker bei Direct Boot komplett stumm.
- **Stille Schicht (`isSilent`) gated NUR die Auslösung.** Fail-safe: Lookup-Fehler = NICHT still.
- **„Deine Schicht beginnt um" zeigt `AlarmInfo.shiftStartTime`, nicht `triggerTime`.**
- **Wer die Wichtigkeit eines Notification-Kanals anhebt, braucht eine NEUE Kanal-ID.** Android
  ändert die Importance eines bestehenden Kanals nur nach UNTEN und ignoriert alle übrigen Felder;
  ein unter derselben ID neu angelegter Kanal kommt mit seinen ALTEN Einstellungen zurück. Der
  Weckerkanal stand deshalb auf jeder Installation von vor v1.9.7 bis v1.29.0 unbemerkt auf
  `IMPORTANCE_LOW` — Wecker ohne Vollbild, ohne Knöpfe, ohne DND-Durchgriff. Ein frisch
  installiertes Gerät zeigt das NIE.
- **Die Schlummer-Beschriftung kommt aus derselben Variablen wie `scheduleSnooze()`**, nie aus
  einem festen Text — sonst verspricht der Knopf am Weckbildschirm eine andere Dauer, als er
  schlummert.
- **Blockierte Benachrichtigungen sind ein Wecker ohne Oberfläche** — deshalb die Status-Karte davor
  und ein WARN direkt nach `startForeground()`.
- **`visibilitySnapshot()` ist Diagnostik, die im Release-Log landen MUSS** (WARN).

### Hintergrundketten und Boot

- **Kein Hintergrund-Paket importiert `ui.` oder `viewmodel.`** (nach unten greifen ist frei, nur
  die Gegenrichtung ist gesperrt). `AlarmReceiver`, `AlarmSoundService`, `BootReceiver` und die
  6h-Wartung laufen bei gesperrtem Gerät und im Direct-Boot-Prozess, ohne sichtbare Activity — eine
  Referenz von dort in die Oberfläche ist der kurze Weg zu einem Leak oder Klassenauflösungsfehler,
  den kein Unit-Test sieht. Beim Anlegen der Regel (22.08.2026) hielten alle 17 Pakete sie bereits.
- **NICHTS am Application-Graphen darf WorkManager oder CE-Storage beim BAUEN anfassen.** Der Graph
  wird auch im Direct-Boot-Prozess aufgebaut; ein Wurf dort tötet den Prozess, und die
  Wiederherstellung der Alarme läuft NIE. **Kein Unit-Test fängt das** — die Prüfung ist ein echter Reboot ohne Entsperrung:
  `python tools/geraet/pruefe_direct_boot.py` (nur Emulator, verweigert jedes andere Ziel).
- **Kein `getSharedPreferences()`/CE-Zugriff in einem Property-Initializer** einer Klasse am
  Application-Graphen. Der Zugriff ist harmlos, der ZEITPUNKT nicht.
- **Die 6h-Wartungskette hat GENAU einen Planer** (`scheduleNext()`, ein Request-Code). Wer
  „sicherheitshalber" nachplant, erzeugt zwei parallele Zyklen.
- **Deren `finally`-Block läuft in `withContext(NonCancellable)`** — sonst stirbt die rollierende
  Kette bis zum nächsten Boot.
- **`AlarmMaintenanceService`: `stopSelf(startId)`**, niemals blankes `stopSelf()`.
- **`AlarmMaintenanceService.start()` fängt den abgelehnten Vordergrund-Start selbst**, nicht die
  Aufrufer — eine Exception aus `onReceive()` reißt den Prozess mit.
- **Die 6h-Wartung MUSS Änderungen und Streichungen sehen können** und synchronisiert **immer**,
  sobald Events vorliegen. Die Leerlisten-Sperre bleibt.
- **`TimezoneChangeReceiver` startet die Wartung mit `forceSync=true`** — ein Re-Arming wäre kein
  Ersatz, die Weckzeit ist eine Wanduhrzeit auf dem Kalendertag.
- **Die Hue-Regelausführung im `AlarmReceiver` ist gedeckelt** (`HUE_EXECUTION_BUDGET_MS = 45 s`).
  Nicht kleiner machen: der Batch-Timeout EINER Regel ist schon 30 s, und ein zu knapper Deckel
  lässt das Licht an, ohne dass der Auto-Aus-Zeitplan je entsteht.

### Master-Pause

- **`syncAlarms()` hat einen zentralen Master-Pause-Backstop**, nicht nur Gates an den Aufrufstellen —
  als erste inhaltliche Prüfung innerhalb von `SafeExecutor.safeExecute`.
- **Denselben Backstop haben `DimScheduleUseCase.enable()` und `DndScheduleUseCase.enable()`.**
  `disable()` bleibt ungegatet, sonst kommt `pause()` nicht mehr durch.
- **`pause()`/`resume()` laufen in `withContext(NonCancellable)`** — sie stellen einen Zustand HER.
- **Der Pausen-Spiegel wird beim App-Start mit der CE-Wahrheit abgeglichen.**

### Kalender und Schichterkennung

- **Eine unvollständige Eventliste ist KEINE Löschgrundlage.** Zwei Quellen: Teilerfolg einzelner
  Kalender und das Lazy-Präfix (10 Events pro Kalender). **Jeder löschende Konsument geht über
  `getCalendarEventsWithStatus()` und prüft `isComplete`** — einzige Ausnahme ist die
  ausdrückliche Kalender-ABWAHL (leere Auswahl aus dem Speicher rückgelesen, nicht leeres
  Ladeergebnis); Einzelheiten im Kalender-Skill; der `CalendarStateHolder` trägt
  `eventsComplete` mit. Wer einen neuen `syncAlarms()`-Aufrufer ergänzt, muss das beantworten.
- **Ein Zustand, der den Alarm-Sync DAUERHAFT anhält, muss sichtbar sein.** Die
  `isComplete`-Sperren verhindern nicht nur das Löschen, sondern auch jedes Anlegen; bleibt ein
  Kalender dauerhaft unerreichbar, versiegen die Wecker lautlos. Deshalb trägt
  `CalendarFetchOutcome` die `failedCalendarIds` (nicht nur ihre Zahl), die Status-Karte zeigt sie
  mit Folge und Ausweg, und ab dem ZWEITEN Wartungslauf in Folge warnt eine Benachrichtigung.
- **Kein Fehler darf als leeres Erfolgsergebnis durchrutschen** — „leer" ist für eine Wecker-App die
  gefährlichste Lüge und löscht ALLE Alarme.
- **Ein gescheiterter Konfigurations-Read darf NIE zur leeren Definitionsliste werden**
  (`getOrThrow()`), und es gibt **keinen stillen Default-Überschreiber**.
- **`ShiftRecognitionEngine`: ein unveränderliches Cache-Objekt, Prüfung UND Veröffentlichung hinter
  dem Mutex, PLUS Epochen-Kennung.** Sonst liest ein nebenläufiger Aufrufer eine leere Liste — und
  „leer" heißt „keine Schichten" heißt: alle Alarme weg.
- **`findDefinitionFor` und `matchesKeywords` nicht verwechseln** — die Verwechslung hat zweimal
  Wecker gekostet. Ersteres ordnet einem bestehenden Alarm zu (gestaffelt), Letzteres erkennt in
  Kalendertiteln (mit Wortgrenzen über Unicode-Kategorien, NICHT `\b`).
- **Wer einen Schichtnamen PERSISTENT speichert, traegt ihn in den Umbenennungs-Nachzug ein.**
  Vier Stellen binden ueber den Namen: Dimmer- und Hue-Regeln (`shiftPattern`), die
  Rufbereitschaft-Auswahl und die Dienstzeit-Ausnahmen. Die beiden Letzteren
  vergleichen EXAKT - eine reine Schreibweisen-Aenderung zaehlt deshalb als Umbenennung. Beim
  Namenstausch wird der falsch gewordene Eintrag geraeumt, ausser beide Namen stehen in derselben
  Liste (dann stimmt ihr Inhalt weiter). Die vollstaendige Inventur steht im Kalender-Skill.
- **`CalendarStateHolder` ist eine Einbahnstraße**, und Laden gehört ausschließlich dem
  `CalendarViewModel`.
- **`loadEventsForSelectedCalendars()` braucht einen Generation-Counter** — die Prüfung VOR JEDEM
  Schreiben, auch vor dem ersten `isLoading = true`.
- **Neue Properties in ViewModels mit `init{}` gehören VOR den `init{}`-Block** — sonst NPE beim
  ersten App-Start, die 329 grüne Tests nicht fangen.

### Persistenz

- **`isPersistenceBlocked()` heisst „der Bestand ist unlesbar", NICHT „der letzte Schreibvorgang
  ging schief".** Die beiden Lagen zu einem Signal zu verodern klingt sparsam und ist toedlich:
  `clearInternalAlarms()` ueberspringt bei „unlesbar" bewusst die ganze `cancelSystemAlarm()`-
  Schleife — nach einem einzigen fehlgeschlagenen Write raeumte die Master-Pause dann den Bestand
  und liesse jeden Systemalarm scharf zurueck. Ein Schreibfehler bekommt einen eigenen Weg zu
  seinem einzigen Konsumenten.
- **Stille Degradierung darf nie zur Schreibwahrheit werden.** DataStore liest vor jedem Write
  erneut; wer einen Lesefehler auf „leer"/„Default" degradiert, speist die Notlage-Leere in den
  nächsten Read-Modify-Write und überschreibt echte Nutzerdaten.
- **Ein CE-DataStore-Read VOR der ersten Entsperrung wirft NICHT — er liefert still leere
  Preferences** und meldet Erfolg. Deshalb fragt `AlarmRepository` vorher den `UserManager` und lädt
  nach dem Entsperren nach.
- **Der Direct-Boot-Spiegel wird bei JEDEM erfolgreichen Load abgeglichen.**
- **Die REIHENFOLGE von `.catch` und `.map` in einem Preferences-Flow ist tragend** — `.catch`
  gehört **hinter** das `.map` und muss den Cache invalidieren.
- **Die RICHTUNG der Degradation ist jeweils bewusst gewählt**: Master-Pause → `false` (nicht
  pausiert), Dimmer → aus, Onboarding-Hinweise → werden gezeigt. Im Zweifel klingeln und hell.

### Auth

- **Kein `getOrElse { emptyList() }` auf Auth-behafteten Ergebnissen.**
- **`refresh()` prüft den NEUEN Token gegen die ID des ALTEN**
  (`storedToken.validateRotation(currentToken.rotationId)`) — vertauscht erzwingt jede legitime
  gleichzeitige Rotation einen Re-Login.
- **Eine frische Neu-Autorisierung ist KEIN Kettenbruch.**
- **`DataStoreTokenRepository.observe()` nutzt `retryWhen`, und der Fehlerfall emittiert NICHTS** —
  kein Signal statt falschem Signal.

### Dimmer, DND, Hue

- **Es gibt GENAU EINE Dimm-Fenster-Quelle: die Regeln** (seit v1.34.0, ein Schalter `dim_enabled`).
  Wer eine zweite, „eingebaute" Quelle daneben stellt, koppelt Schalter aneinander — genau der
  Konstruktionsfehler, der Wellness und Nacht-Standard gekostet hat (Hergang im Dimmer-Skill).
- **Leere Fensterliste = Unterdrückung dieser Nacht**, NICHT „keine Regel". Nicht wegoptimieren.
- **Die Tick-Kette darf nicht abreißen** — Keep-alive (6 h) plus Retry (15 min) nach Lesefehler.
- **Das Aufräumen der Dimm-VORSCHAU darf nicht am `viewModelScope` hängen** — sonst bleibt der
  Bildschirm systemweit verdunkelt, wenn der Nutzer die App verlässt.
- **Jeder `DimOverlayPrefs`-Setter MUSS direkt danach `DimScheduleUseCase.enable()` rufen**, unentprellt.
- **Die Dienstzeit-Fenster kommen aus `ShiftSpanStore`, nicht aus dem Alarm-Bestand** — ein Alarm
  überlebt die Weckzeit nicht.
- **Die V1-Hue-API antwortet auch bei ABLEHNUNG mit HTTP 200** — das Urteil steht im Body.
- **Ein Fehlschlag der Bridge darf nicht zur leeren Liste degradieren**, und die Subnetz-Prüfung ist
  ein HINWEIS auf das Timeout, **niemals ein Veto**.
- **`cleanup()` auf Prozess-Singletons cancelt NUR Kinder** (`cancelChildren()`), nie den Scope.
- **`healthCheckScope` braucht einen `CoroutineExceptionHandler`** — ein `SupervisorJob` allein
  lässt die Exception den PROZESS beenden.

### Navigation

- **Zurück gehört dem `BackHandler` in `MainScreen`** — es gibt keinen Backstack, ohne Handler
  beendet jeder Druck die App. Wer einen neuen `NavigationState` ergänzt, muss ihn dort mitbedenken.
- **Die vier Onboarding-Gates sind nicht optional**, und „Später" beim Akku-Gate heißt ERLEDIGT,
  nicht abgebrochen — sonst wird dem Nutzer der nächste Schritt NIE angeboten.
