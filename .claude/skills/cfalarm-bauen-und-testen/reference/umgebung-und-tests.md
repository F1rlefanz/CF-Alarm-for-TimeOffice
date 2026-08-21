# Umgebung, Build-Eigenheiten und Testverfahren — Hergang

> Hergang zu den Kurzregeln in `CLAUDE.md` und in der `SKILL.md` daneben: welcher Bug die
> Regel erzwungen hat, welche Messung sie belegt, welche Alternative verworfen wurde.
> Jede Zeile hier hat einmal echten Schaden verhindert — im Zweifel gilt sie, nicht die Intuition.

## Inhalt

- Gradle UND der Emulator sind in dieser Umgebung erreichbar
- „Warnungen plötzlich weg" ist kein Fortschritt
- Built-in Kotlin: migriert (v1.24.x)
- Debug-Build
- Die CI baut auch den Release-Pfad
- Grüne Unit-Tests sind kein Startbeweis
- `AlarmFullScreenSmokeTest` (`app/src/androidTest/`) startet den Weck-Bildschirm ÜBER DEN
- Gegen zwei angesteckte Geräte hilft `installDebug`/`connectedDebugAndroidTest` nicht
- Debug-SHA-1 ist in der Google Cloud Console eingetragen (verifiziert 14.07.)
- Getestet wird auf einem echten Gerät 
- Agenten committen nicht selbst in einen gemeinsamen Baum
- Ein Emulator OHNE Bildschirmsperre kann Direct Boot NICHT prüfen
- `res/mipmap-anydpi-v26` bleibt, obwohl Lint den `-v26`-Qualifier bei `minSdk 26` als
- `Logger.business()` loggt auf INFO
- Die tragfähigsten Funde kamen aus Gerätelogs, nicht aus Code-Inspektion
- CI-Fallstrick, nicht als roten Lauf missdeuten
- Der Emulator muss vor Instrumentationstests wach sein
- Dependabot: Vorgehen für Bumps
- `cmd app_hibernation set-state` KOSTET den OAuth-Token
- Eine Neuinstallation schaltet den Dimm-Dienst AB
- Volles `/data` sieht wie ein Build-Fehler aus
- Drei Verdrahtungen sind bewusst ohne JVM-Test
- Die Gate-Kette ist am Emulator nicht end-to-end prüfbar

---

- **Gradle UND der Emulator sind in dieser Umgebung erreichbar** (verifiziert 15.07.2026 über
  `./gradlew --offline installDebug` → echter Build + Install auf `emulator-5554`, exit 0, ~40s).
  `--offline` nutzen — der Cache ist durch lokale Builds des Nutzers warm; **Ausnahmen:
  `assembleRelease` und `connectedDebugAndroidTest` brauchen Netz** (siehe `CLAUDE.md`, „Build & Development
  Commands"). Selbst bauen, installieren, messen, A/B-testen statt nur durch Inspektion zu
  verifizieren. `emulator`-Binary
  ist nicht auf PATH:
  `C:\Users\Christoph\AppData\Local\Android\Sdk\emulator\emulator.exe`. Bibliotheks-Quelltext bei
  Bedarf trotzdem direkt lesbar: `~/.gradle/caches/modules-2/files-2.1/<group>/…-sources.jar`.
  Details im Memory `env_gradle_loopback` (dort steht auch der Emulator-Pfad).
- **„Warnungen plötzlich weg" ist kein Fortschritt.** `org.gradle.configuration-cache=true`
  (in `gradle.properties`): Die Deprecation-Warnungen entstehen in der Konfigurationsphase. Wird
  der Konfigurations-Cache wiederverwendet, erscheinen sie schlicht nicht neu. Nach jeder Änderung
  an `build.gradle.kts`/`gradle.properties` sind sie wieder da.
- **Built-in Kotlin: migriert (v1.24.x).** Hier stand bis dahin „Die Warnung lügt: ihr Vorschlag
  zerlegt das Dreieck aus KSP 2.x, KGP 2.x und AGP 9.x, beide Flags bleiben auf `false`." Das war
  einmal richtig und ist es nicht mehr — am 13.08.2026 nachgemessen statt geglaubt. Der
  `BaseExtension`-Cast, an dem `newDsl=true` scheiterte, existiert weiterhin; er trifft aber nur
  das **`kotlin.android`-Plugin**, und genau das braucht AGP 9 nicht mehr. Der Weg ist deshalb
  nicht „Flags entfernen", sondern die Migration: Plugin aus `app/build.gradle.kts` **und** aus
  der Wurzel raus, `builtInKotlin=true`, `newDsl=true`. Bleibt genau ein enger Bypass,
  `android.disallowKotlinSourceSets=false` — KSP 2.3.2 meldet seine generierten Quellen noch über
  `kotlin.sourceSets` an. Verifiziert: KSP + Hilt laufen, Tests grün, `lintDebug`,
  `lintVitalRelease` und `assembleRelease` (R8) grün, APK gleich groß, App startet am Emulator —
  und die Deprecation-Warnungen sind **weg** statt unterdrückt. Wer die Flags künftig anfasst,
  misst wieder nach, statt dieser Zeile zu glauben.
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
- **`AlarmFullScreenSmokeTest` (`app/src/androidTest/`) startet den Weck-Bildschirm ÜBER DEN
  SERVICE-ZUSTAND, nicht als nackte Activity.** `AlarmFullScreenActivity` beobachtet
  `AlarmSoundService.alarmActive` und schließt sich sofort, solange dort `false` steht
  (`observeAlarmState()`, Absicht — sonst bliebe nach dem Stoppen über die Notification ein totes
  Vollbild stehen). Wer den Test auf ein blankes `ActivityScenario.launch` zurückbaut, misst nur
  noch, wie schnell sich die Activity beendet. Der Test spielt echten Weckton — vorher
  `cmd media_session volume --stream 4` herunterregeln. **Warum es ihn gibt:** dieser Bildschirm ist
  die EINZIGE Stelle der App, die an `androidx.appcompat` hängt (`AppCompatActivity` plus beide
  Themes aus `Theme.AppCompat.*`), und am Gerät ist er ohne Anmeldung gar nicht erreichbar — ein
  appcompat-, Theme- oder Compose-Bump war vorher nicht überprüfbar, ohne sich anzumelden und einen
  echten Kalender-Alarm abzuwarten.
- **Gegen zwei angesteckte Geräte hilft `installDebug`/`connectedDebugAndroidTest` nicht** — beide
  scheitern („more than one device/emulator") bzw. laufen auf ALLEN Geräten, also auch auf dem
  produktiven Fairphone. Sicherer Weg: `assembleDebug` + `assembleDebugAndroidTest`, dann
  `adb -s emulator-5554 install -r …` und `adb -s emulator-5554 shell am instrument -w -e class …`.
  Das trifft garantiert nur den Emulator und deinstalliert die App hinterher NICHT.
- Debug-SHA-1 ist in der Google Cloud Console eingetragen (verifiziert 14.07.).
- Getestet wird auf einem echten Gerät **und einem Emulator als Zweitgerät**; Logcat-Auszüge
  kommen vom Nutzer.
- **Agenten committen nicht selbst in einen gemeinsamen Baum.** In der Härtungs-Runde zu v1.23.0
  liefen sechs Fix-Pakete parallel im selben Arbeitsverzeichnis; ein Agent hat beim Committen die noch
  unkommittierten Dateien eines fremden Pakets mitgenommen (der Commit
  „fix(persistenz): stille Degradierung…" enthält deshalb auch das Paket „Erkennungs-Engine und
  Musterabgleich"). Künftig: entweder committet der Orchestrator EINMAL nach Abschluss aller Agenten,
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
- **`res/mipmap-anydpi-v26` bleibt, obwohl Lint den `-v26`-Qualifier bei `minSdk 26` als
  überflüssig meldet (`ObsoleteSdkInt`).** Gemessen, nicht vermutet (v1.24.0): nach dem Umzug nach
  `mipmap-anydpi` meldet Lint **zwei `IconXmlAndPng`-WARNUNGEN** — im qualifierlosen Bucket verdeckt
  die Adaptive-Icon-XML die `ic_launcher*.webp` der Dichte-Ordner. Der Umzug tauscht also einen
  kosmetischen Hinweis gegen zwei Warnungen und weicht zusätzlich von der
  Android-Studio-Standardstruktur ab. Die verdeckten Bitmaps stattdessen zu löschen wäre ein
  sichtbares Risiko am App-Icon ohne Gegenwert. Der eine verbleibende Hinweis ist Absicht.
- **`Logger.business()` loggt auf INFO** → PII (E-Mail, Kalendertitel) landet in Debug-Builds im
  Datei-Log (`Logger.business`, `util/Logger.kt`). Bewusst: Release-Logs enthalten nur WARN+.

- **Die tragfähigsten Funde kamen aus Gerätelogs, nicht aus Code-Inspektion.** Am 14.07. fanden sich
  doppelter Auth-Callback, doppelte Bridge-Init, stummer Retry und ein lügender Akku-Screen
  ausschließlich über Logcat und Screenshots. Muster, die sich wiederholt gelohnt haben:
  Initialisierungs-Zeilen, die ZWEIMAL erscheinen; WorkManager-Job-IDs, die in Sekunden hochzählen;
  `JobCancellationException` als ERROR; WARN für Normalfälle. Nach einem Debug-Build plus Logcat zu
  fragen lohnt in diesem Projekt fast immer.
- **CI-Fallstrick, nicht als roten Lauf missdeuten:** ein Push kann ZWEI Runs auslösen; die
  `concurrency`-Gruppe (`ci-${{ github.ref }}`, `cancel-in-progress: true`) bricht den ersten mit
  „Canceling since a higher priority waiting request exists" ab, während der zweite grün
  durchläuft. `gh run list --limit 1` erwischt dabei den ABGEBROCHENEN — beim Prüfen immer ALLE
  Runs zum Commit ansehen, sonst hält man einen grünen Stand für rot (am 14.08.2026 genau so
  passiert).
- **Der Emulator muss vor Instrumentationstests wach sein.** `mWakefulness=Asleep` heißt: die
  Activity bleibt bei CREATED und der Test misst nichts — kein App-Bug. Vorher aufwecken.

- **Dependabot: Vorgehen für Bumps.** `.github/dependabot.yml` ignoriert bewusst AGP /
  `org.jetbrains.kotlin*` / KSP, damit der Bot die drei nie einzeln bumpt — das so lassen.
  Dependabot-Branches stehen typischerweise auf altem Stand: **`main` in den Branch mergen,
  nicht rebasen** (kein Force-Push nötig, die PR bleibt erhalten). Für alles, was den Build
  anfasst, gilt die Verifikationsliste: `testDebugUnitTest lintDebug` · `assembleRelease` +
  `lintVitalRelease` **mit Netz** (R8 ist an) · APK-Größe gegen **10,96 MB** vergleichen ·
  `installDebug` und die App **wirklich starten** (grüne Tests haben hier schon einen
  Crash-on-Launch durchgelassen) · Logcat auf App-`WARN` · CI grün.
- **`cmd app_hibernation set-state <pkg> true` KOSTET den OAuth-Token.** Das Einfrieren ist genau
  dafür gebaut, Berechtigungen zurückzusetzen — danach stand die App auf „Kalender-Zugriff
  erforderlich", der verschlüsselte Token-Store war auf 33 Bytes (leer) zurückgesetzt. Kein
  Defekt: ein Tipp auf „Kalender-Zugriff erlauben" holte den Token ohne weiteren Dialog zurück
  (Googles Consent stand noch). Wer das Kommando benutzt, weiß jetzt, dass er hinterher einmal
  antippen muss.
- **Eine Neuinstallation schaltet den Dimm-Dienst AB** (gemessen 14.08.2026). Nach einem Update
  stand `enabled_accessibility_services` auf `null` und `accessibility_enabled` auf `0` — der
  `DimAccessibilityService` war nicht mehr gebunden. Der Dimmer rendert dann **gar nichts**, ohne
  Fehlermeldung und ohne einen einzigen Log-Eintrag. Nach jeder Installation prüfen und
  wiederherstellen: `adb shell settings put secure enabled_accessibility_services
  <pkg>/<pkg>.dimmer.DimAccessibilityService` plus `settings put secure accessibility_enabled 1`,
  Gegenprobe `dumpsys accessibility | grep 'CF-Alarm Schicht-Dimmer'`. NICHT dasselbe wie der
  ECM-Fall (dort steht der Dienst auf „An" und bindet trotzdem nicht).
- **Volles `/data` sieht wie ein Build-Fehler aus.** Ein Install schlägt dann mit
  `INSTALL_FAILED_INSUFFICIENT_STORAGE` fehl, Gradle meldet aber nur „BUILD FAILED".
  Deinstallieren + `adb install -r` reicht als Notbehelf; wer mehr Luft braucht, vergrößert das
  AVD-Image.
- **Drei Verdrahtungen sind bewusst ohne JVM-Test:** `BootReceiver`, `BackgroundServiceManager`
  und `CalendarPreAlarmRefreshWorker` — Android-Receiver bzw. Hilt-EntryPoints ohne sinnvollen
  Harnisch. Getestet ist jeweils die herausgezogene Entscheidungslogik
  (`CalendarFetchOutcome.isComplete`, `isEventListCompleteForAlarmSync`). Wer dort etwas ändert,
  prüft am Gerät nach — die Prüfrunde vom 18.08.2026 fand in genau diesem ungetesteten
  `BootReceiver`-Pfad einen Wecker-Verlust, den 662 grüne Tests nicht sahen.
- **Die Gate-Kette ist am Emulator nicht end-to-end prüfbar** (Umgebung, nicht Code).
  Nachgemessen: `cmd app_hibernation get-state --global` meldet dort **false** (die
  Unused-App-Einschränkung ist gar nicht aktiv, `isRestricted()` also immer false), und
  TimeOffice ist nicht installiert — Gate 3 und Gate 4 können dort nie feuern, mit oder ohne Fix.
  Der halbe Weg ist belegt (Akku-Ausnahme per `cmd deviceidle whitelist -<pkg>` entziehen → Gate
  erscheint → „Später" → Home). Die Kettenlogik hält stattdessen `NavigationViewModelTest` fest;
  per Mutationsprobe belegt: mit der alten Bedingung fallen **drei** Tests um. Echt prüfbar nur
  am Fairphone, und nur solange „Pause bei Nichtnutzung" dort aktiv ist.

## „[Hilt] @HiltAndroidApp base class must extend Application" — ein KSP-Fehler, kein Hilt-Fehler

**Symptom:** `./gradlew testDebugUnitTest` bricht in `hiltJavaCompileDebugUnitTest` ab mit

```
Fehler: [Hilt] @HiltAndroidApp base class must extend Application.
        Found: com.github.f1rlefanz.cf_alarmfortimeoffice.Hilt_CFAlarmApplication
```

**Die Meldung zeigt auf die falsche Datei.** `CFAlarmApplication.kt` ist völlig in Ordnung
(`class CFAlarmApplication : Application()`), und `assembleDebug` läuft weiter grün — nur die
**Unit-Tests** lassen sich nicht mehr übersetzen. Es hilft nicht: `clean`, das Löschen von
`app/build`, ein frischer Daemon, `--no-configuration-cache`. Mit
`hilt { enableAggregatingTask = false }` bricht stattdessen `kspDebugKotlin` ab, und dort steht
die eigentliche Ursache: `[ksp] Access to invalid … KotlinAlwaysAccessibleLifetimeToken: PSI has
changed since creation`.

**Auslöser (19.08.2026 eingegrenzt):** ein **top-level `private enum`**, das als Typargument in
eine generische Funktion einer anderen Datei geht (hier die Aktions-Enums der Hue-Bildschirme in
`rememberLocalNetworkPermissionGate<A : Enum<A>>`). `internal` statt `private` behebt es
vollständig. Bemerkenswert: von drei strukturgleichen Dateien löste nur EINE den Fehler aus —
verlass dich also nicht darauf, dass „die anderen gehen doch auch" etwas beweist.

**Wie es gefunden wurde, falls es wiederkommt:** Halbieren des Diffs über acht saubere Builds,
jedes Mal mit `rm -rf app/build`. Entscheidend war die Gegenprobe in einem **eigenen
`git worktree` auf `main`** — sie hat bewiesen, dass die Umgebung heil ist und der Fehler im
eigenen Diff liegt, statt weiter am Werkzeug zu suchen. Vorsicht dabei: mehrfaches
`git checkout` einzelner Dateien hin und her erzeugt Folgefehler
(`CFAlarmApplication_ComponentTreeDeps konnte nicht gefunden werden`), die wie ein neues Problem
aussehen — nach jedem Teil-Revert das Build-Verzeichnis löschen.

## Das Datei-Log auf dem Gerät des Nutzers ist das stärkste Beweismittel des Projekts

Bei einer Nutzermeldung führt der erste Weg **dorthin**, nicht in den Code. Am 21.08.2026 kamen
die drei tiefsten Fehler des Tages (v1.30.1 Wecker-Identität, v1.30.3 Namensbindung, die fehlende
Statuszeile v1.31.0) aus echter Nutzung und ihrem Log — **keiner** davon aus einer Prüfrunde. Der
Beleg für die Feed-Rotation war nur möglich, weil neun Tage Log vorlagen: am 20.08. um 09:30
11 Termine gelöscht, 11 angelegt, Schnittmenge der Event-IDs **null**, Schichten und Weckzeiten
unverändert. Aus dem Code allein war das nicht zu sehen.

- Debug-Build, **ein File pro Tag**, 8 Tage Aufbewahrung.
- Liegt unter `/sdcard/Android/data/<pkg>/files/`, erreichbar per `adb pull`.
- **`run-as … cat` scheitert dort an den Rechten** — nicht als „Log fehlt" fehldeuten.
- Gesicherte Stände liegen in `..Projektdateien/Logs/` (gitignored).

## Am Gerät belegt (19.–21.08.2026, Emulator, API 37)

Gemessen, nicht abgeleitet — der Bestand, auf den sich spätere Runden berufen dürfen:

- **Der Kanal-Migrationsfix trägt.** Beim ersten Klingeln entsteht `alarm_sound_service_v2` mit
  `mImportance=4` und `mBypassDnd=true`; die alte ID `alarm_sound_service` ist verschwunden.
- **Die Schlummer-Beschriftung stimmt.** Bei eingestellten 15 Minuten trägt der Vollbild-Knopf
  „15 MIN SPÄTER" und die Benachrichtigungs-Aktion „15 Min später" — beides aus derselben Quelle
  wie die Planung.
- **Der Wecker endet sauber:** MediaPlayer freigegeben, Vibration gestoppt, `stopSelf(startId=2)`,
  Notification 2002 weg, kein Zombie-Dienst.
- **Direct Boot: 10 von 10 Weckern nach dem Neustart ohne Entsperrung wieder armiert**
  (`tools/geraet/pruefe_direct_boot.py`). Damit ist der seit Runde 6 offene Punkt erledigt.
- **Die Nachholung nach dem Entsperren greift vollständig:** Bestand nachgeladen, alle 10 Wecker
  aus dem Direct-Boot-Spiegel neu armiert, Datei-Log aktiviert, voller Sync; rund 30 s später sind
  auch `DND_SCHED_TICK` und die Wartungskette wieder da.
- **Die 6h-Kette hat GENAU einen Planer** plus den Wiederanlauf-Wachhund (zwei Einträge, wie
  vorgesehen) — die übrigen Einträge in `dumpsys alarm` stehen in der Historie als `pi_cancelled`.
- **Ein fehlender Kanal wird korrekt als ERREICHBAR gewertet**, nicht als Warnung — die
  Status-Karte schlägt auf einer frischen Installation also keinen Fehlalarm.
- **Kein FATAL, keine ANR, kein StrictMode-Verstoß, keine verworfene Berechtigung.** Nach der
  Anmeldung 0 Fehler und 3 erwartbare Warnungen (Token vor der Autorisierung, zweimal die
  Hue-Subnetz-Heuristik).
- **Kalender-Abwahl räumt wirklich** (62 → 51 → 62 anstehende Alarme, „Next alarm clock" leer und
  zurück); **Master-Pause stoppt den klingelnden Wecker** (`STOP_ALARM` vor „Alarme gelöscht").

**Nicht geprüft, und warum:** Dimmer-Regelkonflikt (braucht einen Kalendertag mit zwei Diensten),
echter Schlummer-Fehlschlag (braucht Berechtigungsentzug im Flug), Abmelden selbst (nur am Gerät
des Nutzers möglich — ohne seine Zugangsdaten keine Wiederanmeldung).
