---
name: cfalarm-bauen-und-testen
description: "Build-, Emulator- und Testverfahren der CFAlarm-Wecker-App: welche Gradle-Tasks Netz brauchen und welche nicht offline laufen, warum der Konfigurations-Cache Deprecation-Warnungen verschluckt, wie bei zwei angesteckten Geraeten gezielt nur der Emulator getroffen wird statt zusaetzlich das produktive Handy, warum ein Emulator ohne Bildschirmsperre keinen brauchbaren Direct-Boot-Test erlaubt, warum gruene Unit-Tests kein Startbeweis sind, und welches Log-Level wo landet (Logger.business auf INFO mit PII nur im Debug-Datei-Log, Release-Logs nur WARN+). Zu verwenden vor dem Bauen, Installieren, Instrumentieren oder Verifizieren am Geraet, bei Gradle- oder Lint-Auffaelligkeiten, beim Planen einer Geraeteverifikation, bei Fragen zu Log-Level, Datei-Log, Logcat-Auswertung oder PII im Log, und bevor Testergebnisse als Beleg behauptet werden."
---

# Bauen, Emulator und Geraetetests

Unten stehen die **Kurzregeln** dieses Bereichs — was gilt, und was bei Bruch passiert.
Die wecker-kritische Teilmenge davon steht zusätzlich in `CLAUDE.md` (dort immer geladen, als
Sicherheitsnetz für den Fall, dass dieser Skill nicht anspringt); **alles Übrige steht
ausschließlich hier.** **Reicht die Kurzregel nicht, oder willst du eine davon ändern oder
umgehen: lies vorher die Hergang-Datei.** Dort steht, welcher Bug die Regel erzwungen hat — ohne
das baut man dieselbe Falle in neuer Form nach.

## Hergang und Belege

- `reference/umgebung-und-tests.md` — Gradle-Eigenheiten, Emulator-Verfahren, Smoke-Tests, Logging

---

## Kurzregeln

- **Gradle UND der Emulator sind erreichbar.** `--offline` nutzen (Cache ist warm), außer bei
  `assembleRelease`/`connectedDebugAndroidTest`. Selbst bauen, installieren, messen, A/B-testen statt
  nur durch Inspektion zu verifizieren. `emulator`-Binary:
  `C:\Users\Christoph\AppData\Local\Android\Sdk\emulator\emulator.exe`.
- **Die Arbeitskopie lügt bei Zeilenenden.** Windows checkt CRLF aus, in Git liegt LF, und der
  Linux-CI-Runner sieht LF. Ein Werkzeug, das Dateiinhalt byteweise vergleicht oder erzeugt, kann
  deshalb lokal grün und in der CI rot sein — genau so am 17.08.2026 passiert: der
  Changelog-Generator hatte `ZEILENENDE = "\r\n"` fest verdrahtet, die CI war drei Commits lang
  rot, und lokal lief `--pruefen` sauber durch. **Für alles Format-Empfindliche gegen
  `git show HEAD:<datei>` testen, nicht gegen die Arbeitskopie** — das ist die Form, die der
  Runner bekommt. Und: plattformabhängige Werte gehören nicht in eine Konstante, sondern werden
  aus dem vorgefundenen Inhalt abgeleitet.
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
- **Der Direct-Boot-Test ist ein Befehl, kein Ritual**: `python tools/geraet/pruefe_direct_boot.py`.
  Er prüft Vorbedingungen, rebootet, wartet auf `RUNNING_LOCKED`, zählt die wieder armierten
  Wecker und wertet das Boot-Log aus. **Er verweigert jedes Ziel, das nicht `emulator-*` heißt** —
  er rebootet, und auf dem Fairphone wäre das der echte Wecker des Nutzers; einen
  Umgehungsschalter gibt es bewusst nicht. Am 17.08.2026 hat er beim ERSTEN Lauf einen echten
  Befund geliefert (CE-Zugriff in `BackgroundServiceManager` vor der Entsperrung, ERROR mit
  Stacktrace im Release-Log).
- **Ein Emulator OHNE Bildschirmsperre kann Direct Boot NICHT prüfen** — ohne Credential gilt der
  Nutzer beim `LOCKED_BOOT_COMPLETED` schon als entsperrt, die Exception bleibt aus und der Test
  belegt nichts. Deshalb bricht das Skript ohne gesetzte Sperre ab. Testdaten VORHER schreiben
  (`run-as` kommt nur entsperrt an das CE-Verzeichnis).
- **Nicht jede Meldung im gesperrten Boot-Log ist ein Defekt.** `WorkManager is not initialized`
  (WARN) ist die vorgesehene Degradation — `HueSmartScheduler` löst ihn erst beim Gebrauch auf und
  überspringt sich. CE-Datei-/DataStore-Zugriffe auf WARN sind vor der Entsperrung erwartbar.
  **Tödlich ist genau eines: `Unable to create application`** — dann stirbt der Prozess, und der
  Restore läuft nie. Das Skript trennt beides; wer die Hinweise zu Fehlschlägen macht, baut einen
  Wächter, der bei jedem Lauf schreit und deshalb ignoriert wird.
- **`Logger.business()` loggt auf INFO** → PII (E-Mail, Kalendertitel) landet in Debug-Builds im
  Datei-Log. Bewusst; Release-Logs enthalten nur WARN+.
- **`res/mipmap-anydpi-v26` bleibt**, obwohl Lint den Qualifier bei `minSdk 26` als überflüssig
  meldet: der Umzug tauscht einen kosmetischen Hinweis gegen zwei `IconXmlAndPng`-Warnungen
  (gemessen, nicht vermutet). Der verbleibende Hinweis ist Absicht.
- Debug-SHA-1 ist in der Google Cloud Console eingetragen. Getestet wird auf einem echten Gerät
  **und** einem Emulator; Logcat-Auszüge kommen vom Nutzer.
