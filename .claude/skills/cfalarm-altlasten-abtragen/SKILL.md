---
name: cfalarm-altlasten-abtragen
description: "Arbeitet EINEN Aufraeum-Blickwinkel ab - fuer autonome Sitzungen ohne Vorwissen. Zu verwenden, wenn ein Auftrag lautet 'raeum weiter auf', 'arbeite die Aufraeum-Liste ab', 'naechste Runde' oder wenn eine geplante/headless Sitzung ohne naeheren Auftrag startet. Enthaelt die Disziplin je Runde, die Leitplanken gegen Zuviel-Wegschneiden, die bereits VERWORFENEN Blickwinkel mit ihren Messzahlen (nicht noch einmal versuchen) und den Abschluss ueber Branch und Pull Request."
---

# Altlasten abtragen — eine Runde pro Sitzung

Diese App trägt Baugerüst aus einem Jahr: Code, den nie jemand aufgerufen hat, Konstanten auf
Vorrat, Kommentare ohne Deklaration. **Es ist kein Verfall** — in den Runden 1–7 wurden 525 Zeilen
entfernt und **kein einziger Fehler** gefunden. Diese Zeilenzahl ist seit Runde 7 nicht
fortgeschrieben (zuletzt geprüft 02.09.2026, inzwischen bei Runde 15); was weiter gilt, ist die
Aussage dahinter: **0 `fix`-Commits aus allen Aufräumrunden zusammen**. Wer das anders erzählt,
verunsichert den Eigentümer ohne Grund.

## Was du in dieser Sitzung tust

**Genau EINEN Blickwinkel.** Nicht zwei, nicht „schnell noch". Ein Blickwinkel ist eine Frage der
Form „welche Art von Leiche könnte es noch geben, die noch niemand gesucht hat?".

```bash
python tools/aufraeumen/blickwinkel_waehlen.py    # nennt den Blickwinkel dieser Runde
cat tools/aufraeumen/nachtraege.md                # bindend wie dieser Skill — vor dem Anfangen lesen
```

**Nimm den Blickwinkel, den das Werkzeug nennt** — nicht den obersten aus `gh issue list`. Genau
das stand hier bis zum 03.09.2026, und es war zweimal falsch, beide Male an den echten Daten
gemessen: `gh issue list` sortiert **absteigend** nach Erstelldatum, die Warteschlange war also ein
Stapel (die neun Blickwinkel vom 25.08. kamen nie an die Reihe); und weil der Torwächter ein Issue
beim Schließen des PR **wieder öffnet**, ohne dass sich dessen Erstelldatum ändert, sperrte ein
gescheiterter Blickwinkel den Kopf der Liste dauerhaft — **Issue #39 hat so vier Runden
verbraucht** (PR #40, #48, #56, #58). Das Werkzeug nimmt den ältesten und stellt einen Blickwinkel
nach drei gescheiterten Anläufen zurück; der Hergang steht in seinem Kopfkommentar.

**Zurückgestellt heißt nicht verworfen.** Das Issue bleibt offen, der Blickwinkel gilt weiter als
gut — er kostet nur keine weitere Runde, bis ein Mensch entschieden hat. Wenn du meinst, die
Zurückstellung sei falsch, ist das eine Rückfrage ans Issue, kein Grund zum Übergehen.

Meldet das Werkzeug **nichts Wählbares** (Rückgabewert 1) oder scheitert es (2), dann **nichts
erfinden**, sondern das melden und aufhören. Ein leerer Zustand ist das Ziel, kein Versagen.

## Die Disziplin je Runde — sie ist der ganze Wert

1. **Messen, bevor du etwas glaubst.** Schreib ein kleines Wegwerf-Skript in den Scratchpad (NICHT
   ins Repo), das den Blickwinkel über den ganzen Baum zählt. Notiere **Rohbefunde und
   Fehlalarme** — beide Zahlen, immer.
2. **Jeden Rohbefund einzeln am Code prüfen.** Referenzbasiert, nie annotationsbasiert.
3. **Erst dann schneiden.** Nach jedem Schnitt: `./gradlew assembleDebug testDebugUnitTest` und
   `python tools/aufraeumen/pruefe_reste.py`.
4. **Entscheiden, ob die Klasse gatterfähig ist.** Faustregel aus der Messung: unter ~10 %
   Fehlalarm ja, darüber nein.
   - **Ja** → als Prüfung in `tools/aufraeumen/pruefe_reste.py` heben, mit Tests für **beide**
     Richtungen (meldet es, wo etwas ist? schweigt es, wo nichts ist?) und mit dem Hergang im
     Kommentar.
   - **Nein** → unten unter „Verworfen" eintragen, **mit den Zahlen**. Sonst versucht es die
     nächste Sitzung wieder.
5. **Neue Fragen, die unterwegs auftauchen, als neues Issue anhängen** (`--label aufraeumen`).
   Dadurch bleibt die Liste lebendig, ohne dass jemand sie pflegen muss.
6. **Das Issue schließen** — mit den Zahlen im Kommentar, nicht nur „erledigt".

## Leitplanken — sie stammen aus echten Fehlern, nicht aus Vorsicht

> **Das Gatter fängt RESTE, nicht ZUVIEL-WEGSCHNEIDEN.** In Runde 7 hat es drei eigene Fehler
> gestoppt. Die beiden *inhaltlichen* Fehlurteile hat es NICHT gefangen — die fielen nur auf, weil
> von Hand nachgesehen wurde. Verlass dich also nie darauf, dass Grün = Richtig.

- **Zähle die eigene Datei mit.** In Runde 6 galten `getNextAlarmInfo()` und
  `checkAlarmPermissions()` als tot, weil nur *außerhalb* ihrer Datei gezählt wurde. Beide waren in
  Betrieb. Tot waren nur ihre Felder.
- **Folge keinem Linter blind.** Lints Rat „`mipmap-anydpi-v26` zusammenlegen" wurde befolgt — die
  Meldungszahl stieg von 4 auf 5, weil XML- und PNG-Icon ohne Versions-Qualifier kollidieren.
  Formal richtig, in der Sache falsch. **Nach jedem Rat neu messen.**
- **Ein gespeichertes Format ist kein toter Code.** `TokenData.tokenType/issuedAt` liest niemand —
  sie stehen aber im verschlüsselten DataStore auf echten Geräten. Entfernen ändert die Datei für
  null Gewinn. Solche Fälle bleiben **mit `OHNE VERWENDER` im eigenen KDoc** stehen; das ist die
  Ausnahme, die Prüfung 6 kennt.
- **Prüfe den Parser, bevor du ein Modellfeld entfernst.** Die Hue-Modelle füllt **Gson**, und Gson
  ignoriert unbekannte JSON-Schlüssel — deshalb war das Entfernen dort sicher. Bei einem strikten
  Parser wäre es das nicht.
- **Die Weckerkette fasst du nicht an.** `alarm/`, `service/`, `dimmer/`-Planung, `AlarmReceiver`,
  `BootReceiver`: Findest du dort etwas, schneide **nicht**, sondern schreib es als Issue mit
  Belegen und dem Vermerk „braucht Rücksprache". Ein stummer Wecker ist teurer als jede Altlast.
- **Nichts wird schöner gemacht.** Umbenennen, umstrukturieren, „effizienter machen" ist NICHT
  Aufräumen — das ist eine Änderung mit eigenem Risiko und gehört besprochen. Entfernt wird nur,
  was **nachweislich niemand benutzt**.
- **Ein Formatfehler, den das produktive Werkzeug toleriert, bleibt ein Formatfehler.**
  `app/lint.xml` ist **bis heute kein wohlgeformtes XML** — nachgeprüft am 02.09.2026:
  `python -c "import xml.dom.minidom; xml.dom.minidom.parse('app/lint.xml')"` scheitert mit
  `not well-formed (invalid token): line 15, column 59`, dem `--offline` im Kopfkommentar.
  Android Lint nimmt das klaglos hin, die Datei *wirkt* also, und niemand hat einen Anlass
  hinzusehen. Gemerkt hat es erst das erste Werkzeug, das sie *parsen* wollte, und das musste sich
  mit Regexps behelfen. **„Es funktioniert ja" ist kein Beleg für Wohlgeformtheit**; das nächste
  Werkzeug zahlt — **wer hier einen XML-Parser ansetzt, stürzt ab, nicht die Datei.**
  Repariert wird es NICHT nebenbei: **vier** Runden haben es versucht (PR #40, #48, #56, #58), der
  Torwächter hat alle vier geschlossen — nie wegen des Befundes, immer wegen dessen, was die
  Reparatur neu hineinschrieb. Issue #39 ist deshalb seit dem 03.09.2026 **zurückgestellt** (siehe
  `blickwinkel_waehlen.py`): es bleibt offen und gut, aber es kostet keine weitere Runde, bis der
  Eigentümer entschieden hat. Fass es nicht von dir aus wieder an.
- **Zwei Lehren aus geschlossenen Anläufen — sie gelten für jedes neue Gatter.**
  1. **Ein `except`-Tupel über einen fremden Parser ist eine Wette.** PR #48 fing
     `(SyntaxError, ValueError)`; `<?xml … encoding="cp1252-de"?>` wirft `LookupError`, der
     entkam und riss **alle** Prüfungen mit — gemeldet als „Reste hinterlassen", also eine
     Fehldiagnose an einem blockierenden Gatter. Umschließe nur den Fremdaufruf und fang dann
     breit.
  2. **Teste die VERDRAHTUNG, nicht nur die reine Funktion.** Bei #48 blieben alle Tests grün,
     wenn man den Aufruf aus `main()` entfernte. Der Beleg ist billig: das echte Skript über
     einem Wegwerf-Repo laufen lassen und **jede Mutation einzeln rot sehen**.

  Und daneben, kleiner, aber teuer: **Datumsangaben misst man** (`git log -S` plus jede Fassung
  parsen) — PR #40 fiel über ein geschätztes „jahrelang" in genau der Zeile, die er reparierte.
  Runde 15 hat es richtig gemacht und die exakte Spanne belegt: 349 Tage wohlgeformt, 27 Tage
  kaputt.
- **Blickwinkel, die statisch entscheidbar sind, sind die guten.** Runde 11 hatte 36 Dateien,
  1 Rohbefund, **0 Fehlalarme** — eine Datei parst oder sie parst nicht, es gibt keinen
  Ermessensspielraum. Verworfen wurden bisher fast nur Blickwinkel, die *Absicht* erraten mussten
  (siehe die Tabelle unten: dort steht in jeder Zeile ein Ermessensspielraum).
- **Ein blockierendes Gatter muss den KONFLIKTZUSTAND kennen.** Die teuerste Lehre aus Runde 15
  (PR #56, geschlossen): eine Prüfung „parst diese Konfigurationsdatei?" ist inhaltlich
  tadellos — und sperrt das Repo trotzdem ein. Ein Konfliktmarker ist nie wohlgeformtes
  XML/JSON/TOML, betroffen wären `strings.xml`, `AndroidManifest.xml`, `libs.versions.toml` und
  `.claude/settings.json`; und weil der Schleusen-Hook auf `git\s+(merge|push)` triggert
  und nur `--dry-run` ausnimmt, werden ausgerechnet `git merge --abort` und `--continue`
  abgewiesen — die beiden **Rettungsbefehle**. Mit einer Fehldiagnose („Der Aufraeumdurchgang hat
  Reste hinterlassen") und einem unbefolgbaren Rat obendrauf. Der Torwächter hat das in einem
  Wegwerf-Repo nachgestellt, nicht vermutet.
  **Also bei JEDER neuen Prüfung, die den Baum liest: was tut sie, während ein Merge offen ist?**
  Ausweg ist billig (`git ls-files -u` nicht leer → überspringen), das Übersehen ist teuer.
  Bitter an dem Fall: der Code *benannte* die Lage im eigenen Docstring und behob trotzdem nur
  die Doppelmeldung, nicht die Blockade. **Eine Gefahr zu beschreiben ist nicht, sie zu bannen.**
- **Prüf vor dem Aufsetzen die offenen PRs, nicht nur die offenen Issues.** Runde 11 nummerierte
  ihre neue Prüfung als 7 — und der noch offene PR aus Runde 10 tat in derselben Datei dasselbe.
  Der Konflikt war klein und mechanisch, aber vermeidbar: `gh pr list` kostet eine Sekunde.

## Verworfen — gemessen, nicht gatterfähig. NICHT noch einmal versuchen

| Blickwinkel | Messung | Warum |
|---|---|---|
| „Ungenutzte Funktion" über gezählte Namensreferenzen | **222 Kandidaten, praktisch alle falsch** | Hilt-Provider ruft niemand beim Namen, Compose-Funktionen dateiintern, Lebenszyklus vom Framework |
| Ungenutzter Funktionsparameter | 14 Kandidaten, **alle** falsch | allesamt `override` mit vom Framework vorgegebener Signatur |
| „Doku nennt Symbol, das der Code nicht hat" (ungefiltert) | 54 Treffer, **53 falsch** | die Doku nennt zu Recht Plattform-APIs (`startForeground()`) |
| Manifest-Berechtigung ohne Nennung im Code | 4 Kandidaten, **alle** falsch | implizit gebraucht (ConnectivityManager, startForeground, BootReceiver, Vibrator) |
| Tests ohne sichtbare Behauptung | 16 Kandidaten, **alle** falsch | sie behaupten über Helfer (`erwarteNachzug()`) |
| Doku nennt Datei, die es nicht gibt | 1 Treffer, falsch | stand in einem *historischen* Satz — das ist der Hergang, kein Fehler |
| `@Suppress`/`@SuppressLint`, das nichts mehr unterdrückt | **24 Rohbefunde, 19 lebendig (79 % Fehlalarm)**, 5 tot (Runde 9) | reine Textsuche taugt nicht; die Erkennung verlangt einen mutierenden Rebuild (Annotation entfernen, neu kompilieren, `lint`, Zeilenverschiebungen zurückrechnen, ~6 min) — im Schleusen-Hook über einem geteilten Arbeitsbaum ausgeschlossen |
| Testdatei ohne `@Test` = ungenutztes Test-Double | **6 Kandidaten, 5 falsch** (Runde 8) | Fakes und Fixture-Helfer tragen naturgemäß kein `@Test` und werden trotzdem von 2–20 Testklassen benutzt. Die Klasse ist zu klein und zu falsch-positiv für ein Gatter — der eine echte Fund war kein Test-Double, sondern eine JUnit-`Suite` |

### Akzeptierte Dauermeldungen — nicht als Fund melden

Gemessen am 02.09.2026 auf dem Stand von v1.39.1, gezählt aus
`app/build/reports/lint-results-debug.sarif` (eindeutig zählbar, anders als der HTML-Bericht):
**6 bis 8 Befunde, kein `UnknownIssueId` — und die Schwankung ist der eigentliche Befund.**
Am 02.09.2026 auf demselben Stand dreimal gemessen: **6** am Vormittag, **7** zwei Stunden
später (`AndroidGradlePluginVersion` war dazugekommen, ohne dass sich am Repo etwas geändert
hatte), **8** im Lauf des Torwächters. **Schreib deshalb keine Gesamtzahl fest** — die
Gradle-/AGP-Regeln hängen an fremden Veröffentlichungen, nicht an unserem Code.

| Regel | Anzahl | Warum sie steht |
|---|---|---|
| `TrustAllX509TrustManager` | 2 | **aus `google-http-client-2.2.0.jar`**, nicht aus unserem Code |
| `PluralsCandidate` | 1 | „%d Min" hat im Deutschen keine Pluralform |
| `ObsoleteSdkInt` | 1 | `mipmap-anydpi-v26`, siehe Leitplanke „Folge keinem Linter blind" |
| `ConfigurationScreenWidthHeight` | 1 | `MainContentScreen`, reiner Stilrat → „Nichts wird schöner gemacht" |
| `AutoboxingStateCreation` | 1 | `StatusTabContent`, dito |
| `AndroidGradlePluginVersion` | 0–1 | **kommt und geht mit fremden Veröffentlichungen** |
| `NewerVersionAvailable` | 0–1 | dito; auf `informational` gestellt |

Compiler: `createEmptyComposeRule`, steht mit Ausweg in `tools/aufraeumen/warnungen_geduldet.txt`.

**Drei Korrekturen an dem, was hier bis zum 02.09.2026 stand** — jede gemessen, nicht vermutet:

1. `TrustAllX509TrustManager` ist **nicht** „der bewusste Hue-TrustManager". Der `HueTrustManager`
   validiert echt und wirft bei Fehlschlag; Lint meldet ihn gar nicht. Beide Treffer stammen aus
   einer Bibliothek. In `app/lint.xml` stand das seit Runde 9 richtig, hier war es falsch (#33).
2. Die Befundzahl hängt **nicht** an `--offline`, wie #49 vermutete. Sie hängt am Code und an den
   Abhängigkeiten: Runde 11 maß 7, Runde 13 maß 13 (v1.37.0/.2 brachten sechs Meldungen mit), heute
   sind es 6 — `NewerVersionAvailable` und `GradleDependency` sind weg, weil Dependabot die
   Abhängigkeiten aktuell hält. **Nimm keine Zahl von hier als Sollwert; miss selbst.**
3. `ApplySharedPref` ×2 und `UseKtx` ×4 stehen nicht mehr in der Liste, weil sie nicht mehr
   erscheinen: Runde 14 hat die vier bewussten `commit()`-Aufrufe in
   `WeckbildschirmVerdraengungPrefs` und `DimAccessibilityService` **pfadgenau** in `app/lint.xml`
   verankert, samt Begründung. Wer diese Verankerung wieder entfernt, entfernt die einzige Stelle,
   an der steht, dass dort eine Entscheidung getroffen wurde — die Begründung ist in `app/lint.xml`
   nachzulesen, bevor jemand „aufräumt".

## Abschluss — Branch und Pull Request, niemals direkt auf `main`

Entscheidung des Eigentümers (25.08.2026): Eine autonome Sitzung **pusht nicht nach `main`**. Sie
arbeitet auf `chore/aufraeumen-<blickwinkel>`, lässt die Schleuse laufen und öffnet einen PR. Der
Grund steht oben: das Gatter kann Zuviel-Wegschneiden nicht sehen, ein Mensch schon.

```bash
git checkout -b chore/aufraeumen-<kurzname>
# … arbeiten, committen (chore:, NIE fix: — sonst verlangt die Schleuse einen Changelog-Eintrag
#    für etwas, das kein Nutzer sieht) …
git push -u origin chore/aufraeumen-<kurzname>
gh pr create --fill
```

**Schlägt Build, Test oder Schleuse fehl und du kannst es nicht in wenigen Schritten sauber
beheben: verwirf die ganze Sitzung** (`git checkout main && git branch -D …`) und schreib ins
Issue, woran es lag. Ein halber Umbau ist schlimmer als keiner.

**Über deinen PR entscheidest nicht du.** `.github/workflows/torwaechter.yml` holt ihn sich,
baut und testet **selbst** (er glaubt keinem CI-Status), prüft ihn gegen die Leitplanken oben,
lässt ihn von drei unabhängigen Agenten *widerlegen* — und merged oder schließt. Wird er
geschlossen, öffnet der Torwächter das Aufräum-Issue wieder; der Blickwinkel geht also nicht
verloren. Deshalb ist die Commit-Nachricht kein Beiwerk: **ohne Rohbefund- und
Bestätigungszahl schließt er den PR.** Der Erzeuger läuft täglich als
`.github/workflows/aufraeumen.yml`.

## Diesen Skill lebendig halten

Jede Runde ergänzt hier: neue Leitplanke, wenn ein Fehlurteil passiert ist; neue Zeile in
„Verworfen", wenn ein Blickwinkel nichts taugt; Korrektur, wenn eine Aussage hier sich als falsch
erweist. **Danebenschreiben ist ein Fehler, nicht Verlauf.**

**Versuch es gar nicht erst — du kannst diese Datei nicht schreiben, und das ist Absicht.** Am
03.09.2026 headless nachgestellt (Claude Code 2.1.259, dieselben Schalter wie in `aufraeumen.yml`):
`Edit`, `Write` **und** `Bash` auf `.claude/**` werden abgelehnt, und weder eine Pfadregel in
`--allowedTools` noch `--permission-mode acceptEdits` heben das auf. Es ist ein eingebauter Schutz,
keine Fehleinstellung dieses Repos: **ein Agent darf seine eigene Anweisung nicht unbeaufsichtigt
umschreiben.** Die frühere Notiz hier — „lokal ist die Ursache nicht nachstellbar" — war eine
Vermutung und ist damit widerlegt. In den Runden 9, 11, 14 und 15 hat das je einen Nachtrag
gekostet (Issues #33, #41, #49, #57), und weil die nächste Runde sie nicht las, entstanden die vier
geschlossenen Anläufe an #39.

**Dein Weg ist `tools/aufraeumen/nachtraege.md`.** Die darfst du schreiben, sie liegt im Checkout,
die nächste Runde liest sie als Erstes, und sie geht mit deinem PR durch den Torwächter. Ein
eigenes Issue dafür brauchst du **nicht** mehr. Was NICHT zählt, ist den Nachtrag nur in einer
PR-Beschreibung zu lassen — die liest die nächste Runde nicht. Für den Hergang der Werkzeuge selbst
siehe `tools/aufraeumen/pruefe_reste.py` (die Kommentare dort tragen jede Messung) und
`cfalarm-arbeit-abschliessen`.
