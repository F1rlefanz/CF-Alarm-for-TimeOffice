# Umgebung & Arbeitsweise

> Ausgelagert aus `CLAUDE.md` (17.08.2026). Dort steht die Kurzregel, hier der Hergang:
> warum die Regel existiert, welcher Bug sie erzwungen hat, welche Messung sie belegt.
> **Vor Änderungen in diesem Bereich lesen.**

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
