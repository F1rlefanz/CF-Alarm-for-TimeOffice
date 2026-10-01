---
name: cfalarm-altlasten-abtragen
description: "Leitplanken fuer das Entfernen von Altlasten (toter Code, ueberholte Kommentare, Duplikate) in der CFAlarm-Wecker-App - fuer jede Aufraeum- oder Verschlankungsarbeit, auch fuer manuell gestartete Audit-Runden. Enthaelt die Disziplin vor jedem Schnitt, die Regeln gegen Zuviel-Wegschneiden, die bereits VERWORFENEN Blickwinkel mit ihren Messzahlen (nicht noch einmal versuchen) und die akzeptierten Lint-Dauermeldungen. Zu verwenden bei 'raeum auf', 'toten Code entfernen', 'verschlanken', 'Audit-Runde' oder bevor Code als ungenutzt entfernt wird."
---

# Altlasten abtragen — Leitplanken

Vom 25.08. bis 29.09.2026 liefen hier nächtliche autonome Aufräumrunden (`aufraeumen.yml`) mit
einem Torwächter (`torwaechter.yml`), der jeden PR selbst baute und widerlegen ließ: gut 35
Runden, 12 gemergte PRs, **kein einziger `fix`-Commit** aus allen Runden zusammen. Eingestellt, weil
der Ertrag zuletzt bei 1–3 Schnitten je Runde lag, die beiden Läufe aber täglich rund 40
Opus-Minuten über das Abo des Eigentümers kosteten. **Aufräumen läuft seither als manuell
gestartete Audit-Runde**. Die Arbeitsordner der Runden 1 und 2 sind am 01.10.2026 geloescht; was
davon noch offen war, steht als Issue (#128-#133, Label `aufraeumen`).
Geblieben sind die Lehren unten — sie gelten für jeden Schnitt, egal wer ihn macht.

**Es ist kein Verfall.** Diese App trägt Baugerüst aus einem Jahr Vibe-Coding: Code auf Vorrat,
Kommentare ohne Deklaration, Changelog-Prosa. Wer das anders erzählt, verunsichert den Eigentümer
ohne Grund.

## Die Disziplin — sie ist der ganze Wert

1. **Messen, bevor du etwas glaubst.** Wegwerf-Skript in den Scratchpad (nicht ins Repo), das den
   Blickwinkel über den ganzen Baum zählt. **Rohbefunde und Fehlalarme** notieren — beide Zahlen.
2. **Jeden Rohbefund einzeln am Code prüfen.** Referenzbasiert, nie annotationsbasiert.
   **Kommentare zählen nicht als Verwender, String-Literale schon** (ein Name in einer Test-JSON ist
   Teil eines gespeicherten Formats); reine Doku-Dateien zählen gar nicht.
3. **Erst dann schneiden.** Nach jedem Schnitt `./gradlew assembleDebug testDebugUnitTest lint` und
   `python tools/aufraeumen/pruefe_reste.py`.
4. **Den Schnitt allein liefern — ein neues Gatter ist eine eigene Arbeit.** Von den neun
   beurteilten Aufräum-PRs wurden alle vier gemergt, die kein blockierendes Gatter mitbrachten, und
   alle fünf geschlossen, die eins mitbrachten — nie am Befund, immer am Gatter.

## Leitplanken — sie stammen aus echten Fehlern, nicht aus Vorsicht

> **Grün heißt nicht richtig.** Build, Tests und `pruefe_reste.py` fangen Reste, nicht
> Zuviel-Wegschneiden. Die inhaltlichen Fehlurteile fielen nur auf, weil von Hand nachgesehen wurde.

- **Zähle die eigene Datei mit.** `getNextAlarmInfo()` und `checkAlarmPermissions()` galten als tot,
  weil nur außerhalb ihrer Datei gezählt wurde — beide waren in Betrieb.
- **Folge keinem Linter blind.** Lints Rat „`mipmap-anydpi-v26` zusammenlegen" ließ die Meldungszahl
  steigen. Nach jedem Rat neu messen.
- **Ein gespeichertes Format ist kein toter Code.** `TokenData.tokenType/issuedAt` liest niemand,
  sie stehen aber im verschlüsselten DataStore auf echten Geräten. Solche Fälle bleiben mit
  `OHNE VERWENDER` im eigenen KDoc (die Ausnahme, die `pruefe_reste.py` Prüfung 6 kennt). Ebenso
  gespeichert und damit tabu: WorkManager-Worker-Klassennamen und Unique-Work-Namen,
  PendingIntent-Request-Codes, Intent-Actions, Notification-Kanal-IDs.
- **Prüfe den Parser, bevor du ein Modellfeld entfernst.** Die Hue-Modelle füllt Gson, das
  unbekannte JSON-Schlüssel ignoriert — dort war Entfernen sicher. Bei einem strikten Parser nicht.
- **Die Weckerkette schneidest du nicht nebenbei.** `alarm/`, `service/`, Dimmer-Planung,
  `AlarmReceiver`, `BootReceiver`: nur mit Charakterisierungstest und Gerätenachweis
  (Direct Boot, Emulator). Ein stummer Wecker ist teurer als jede Altlast.
- **Nichts wird schöner gemacht, wenn es nicht beauftragt ist.** Umbenennen, umstrukturieren,
  „effizienter machen" ist eine Änderung mit eigenem Risiko, kein Aufräumen.
- **Ein Formatfehler, den das Werkzeug toleriert, bleibt ein Formatfehler.** `app/lint.xml` war
  29 Tage lang kein wohlgeformtes XML (`--` im Kommentar), Lint nahm es klaglos hin.
- **Jede neue Prüfung, die den Baum liest, muss den Konfliktzustand kennen.** Konfliktmarker sind
  nie wohlgeformt; eine blockierende Prüfung sperrt sonst ausgerechnet `git merge --abort`. Ausweg:
  `git ls-files -u` nicht leer → überspringen (offener Punkt #60 für `pruefe_reste.py`).
- **Ein leeres Strukturergebnis ist meist ein Parserfehler, kein Befund.** Kommentare und Strings
  vor der Struktursuche zeichenlängentreu ausblenden, und das Inventar einmal ausdrucken und
  ansehen — eine Selbstprüfung auf Zahlen belegt nicht die Namen.
- **Ein `except`-Tupel über einen fremden Parser ist eine Wette** — nur den Fremdaufruf umschließen
  und breit fangen. **Teste die Verdrahtung, nicht nur die reine Funktion.**
- **Datumsangaben misst man** (`git log -S`, jede Fassung parsen), statt zu schätzen.
- **`.claude/**` ist unbeaufsichtigt nicht schreibbar** (eingebauter Schutz, am 03.09.2026 headless
  nachgestellt). Nicht umgehen; Lehren für Skills dem Eigentümer vorlegen.

## Verworfen — gemessen, nicht gatterfähig. NICHT noch einmal versuchen

| Blickwinkel | Messung | Warum |
|---|---|---|
| „Ungenutzte Funktion" über gezählte Namensreferenzen | **222 Kandidaten, praktisch alle falsch** | Hilt-Provider ruft niemand beim Namen, Compose-Funktionen dateiintern, Lebenszyklus vom Framework |
| Ungenutzter Funktionsparameter | 14 Kandidaten, **alle** falsch | allesamt `override` mit vom Framework vorgegebener Signatur |
| „Doku nennt Symbol, das der Code nicht hat" (ungefiltert) | 54 Treffer, **53 falsch** | die Doku nennt zu Recht Plattform-APIs (`startForeground()`) |
| Manifest-Berechtigung ohne Nennung im Code | 4 Kandidaten, **alle** falsch | implizit gebraucht (ConnectivityManager, startForeground, BootReceiver, Vibrator) |
| Tests ohne sichtbare Behauptung | 16 Kandidaten, **alle** falsch | sie behaupten über Helfer (`erwarteNachzug()`) |
| Doku nennt Datei, die es nicht gibt | 1 Treffer, falsch | stand in einem *historischen* Satz |
| `@Suppress`/`@SuppressLint`, das nichts mehr unterdrückt | **24 Rohbefunde, 19 lebendig (79 % Fehlalarm)** | reine Textsuche taugt nicht; die Erkennung verlangt einen mutierenden Rebuild (~6 min) |
| Testdatei ohne `@Test` = ungenutztes Test-Double | **6 Kandidaten, 5 falsch** | Fakes und Fixture-Helfer tragen naturgemäß kein `@Test` |

## Akzeptierte Lint-Dauermeldungen — nicht als Fund melden

Gezählt aus `app/build/reports/lint-results-debug.sarif`. **Keine Gesamtzahl festschreiben**: die
Gradle-/AGP-Regeln (`AndroidGradlePluginVersion`, `GradleDependency`, `NewerVersionAvailable`)
kommen und gehen mit fremden Veröffentlichungen. Stand 29.09.2026: 18 Meldungen, davon 12 fremd.

| Regel | Anzahl | Warum sie steht |
|---|---|---|
| `TrustAllX509TrustManager` | 2 | aus `google-http-client-2.2.0.jar`, **nicht** der Hue-TrustManager (der validiert echt) |
| `PluralsCandidate` | 1 | „%d Min" hat im Deutschen keine Pluralform |
| `ObsoleteSdkInt` | 1 | `mipmap-anydpi-v26`, siehe „Folge keinem Linter blind" |
| `ConfigurationScreenWidthHeight` | 1 | `MainContentScreen`, reiner Stilrat |
| `AutoboxingStateCreation` | 1 | `StatusTabContent`, dito |

Die bewussten `commit()`-Aufrufe in `WeckbildschirmVerdraengungPrefs` und `DimAccessibilityService`
sind in `app/lint.xml` pfadgenau verankert, samt Begründung — nicht „aufräumen".
Compiler: `createEmptyComposeRule`, geduldet mit Ausweg in `tools/aufraeumen/warnungen_geduldet.txt`.
