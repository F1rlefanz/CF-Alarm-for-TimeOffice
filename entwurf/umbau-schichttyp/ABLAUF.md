# Ablauf: wie der Umbau autonom abgearbeitet wird

Stand: 16.10.2026. Was gebaut wird, steht in [`SPEC.md`](SPEC.md). Diese Datei beschreibt, **wie**:
Agenten arbeiten viele kleine Issues nacheinander ab, sichern jeden Schritt in Git und machen nach
einem Limit oder Absturz von selbst dort weiter, wo der letzte Stand liegt.

Entscheidungen des Eigentümers (16.10.2026): Spec und Prototyp-Code liegen öffentlich im Repo · der
Runner läuft **lokal und headless** · **Etappen werden vollautonom ausgeliefert** · Haiku nur für
mechanische Arbeit.

## 1. Grundsätze

1. **Der Zustand steht in GitHub und Git, nirgends sonst.** Kein Handoff, keine Fortschrittsdatei,
   die jemand pflegen muss. Welches Issue offen, in Arbeit oder fertig ist, sagen Labels und der
   Issue-Status; was gebaut ist, sagt der Branch. Deshalb kann jede Sitzung jederzeit abbrechen.
2. **Ein Issue = ein frischer Lauf = ein kleiner Branch.** Klein heißt: in einem Lauf von höchstens
   etwa 60 Turns fertig, mit eigener Abnahme. Was größer ist, wird vorher geteilt.
3. **Nach jedem grünen Schritt committen und pushen** (Issue-Branch). Ein Limit kostet höchstens den
   letzten halben Schritt.
4. **Umziehen vor Neuschreiben.** Das meiste existiert schon (Regel-Editor, Fenster-Editor,
   DateRangePicker, Hue-Regeln). Ein Issue nennt die Quelldatei, aus der verschoben wird.
5. **Die Pflichtregeln gelten für Agenten genauso.** Jeder Lauf liest `CLAUDE.md` und den Skill
   seines Bereichs; die Schleuse prüft vor jedem Merge und Push.

## 2. Wo was liegt

| Was | Wo |
|---|---|
| Spec, Prototyp-Code | `entwurf/umbau-schichttyp/` (öffentlich, nicht unter `docs/`, also nicht auf der Website) |
| Pflichttexte je Bildschirm | `entwurf/umbau-schichttyp/BILDSCHIRME.md`, geprüft mit `tools/geraet/pruefe_texte.py` (Skill `cfalarm-ui-pruefen`) |
| Laufzähler eines Pakets | Label `lauf-1` / `lauf-2` (überlebt einen Absturz des Runners) |
| Gesamtplan | Epic-Issue „Umbau: Schichttyp als Zentrale“ mit einer Checkliste der Etappen-Issues |
| Arbeitspakete | ein Issue je Paket, Label `umbau` + `etappe-N` |
| Zustand eines Pakets | Labels `bereit` → `in-arbeit` → (geschlossen); `blockiert` mit Grund im Kommentar |
| Integrations-Branch | `feature/umbau-schichttyp` (gepusht, Backup über Sitzungen hinweg) |
| Issue-Branch | `feature/umbau-<nr>-<kurz>`, wird nach Abnahme in den Integrations-Branch gemergt und gelöscht |
| Lauf-Protokoll | `..Projektdateien/umbau/logs/` (gitignored; je Lauf ein Log, `STATUS.txt`) |
| Unbelegte Fehlerhypothesen | `..Projektdateien/umbau/hypothesen.md` – **nie** in ein öffentliches Issue |
| Lehren für Skills | `entwurf/umbau-schichttyp/skill-nachtraege.md` (headless kann `.claude/**` nicht schreiben; der Eigentümer übernimmt sie interaktiv) |

`tools/sitzungsstart.py` zeigt ohne Netzzugriff einen Abschnitt „Umbau“: Pfad dieses Ablaufs,
Vorsprung des Integrations-Branches und die letzte Zeile von `STATUS.txt` (die der Runner schreibt:
Etappe, laufendes Paket, seit wann). Den Stand der Pakete liefert `gh issue list --label umbau`.

## 3. Etappen

Jede Etappe ist ein **in sich fertiger, auslieferbarer Stand**: nach ihr ist die App benutzbar, auch
wenn noch nicht alles neu aussieht. Innerhalb einer Etappe laufen die Pakete auf dem
Integrations-Branch; erst die fertige Etappe geht nach `main`.

| Etappe | Inhalt | Risiko |
|---|---|---|
| 0 Pilot | #151 (Test liegt schon) und #150 als Probeläufe des Runners; daraus werden Turn-Caps und Verbrauch geschätzt | niedrig |
| 1 Kategorien | Kategorie und mehrere Muster am Typ, feste Zeile „Ohne Kalendereintrag“ (wie <Typ>), Sonderfälle Krank/Ruf in der Erkennung, gruppierte Typ-Liste, Einordnen-Dialog; Migration der bestehenden Typen | mittel |
| 2 Typ-Seite | Typ-Seite mit Wecker-Abschnitt, stummer Wecker, Tagesleiste 18–18 | mittel |
| 3 Dimmen + DND am Typ | Dimm-Fenster und DND-Schalter am Typ, Migration der Regeln (FREI/UNIVERSAL → Typen), globale Schalter fallen weg, Dimmer-/DND-Bereich schrumpft auf Geräte-Einstellungen | **hoch** (Persistenz, Kerntests) |
| 4 Licht am Typ | Hue-Regeln am Typ, „Licht startet mit dem Wecker“ | mittel |
| 5 Rufdienst-Vortag | #152 | mittel |
| 6 Bereich Wecker | neue Reihenfolge, Manueller Wecker inkl. wiederkehrend (#153), „Dienst fällt aus“ mit Codes, Tagesbänder | mittel |
| 7 Übersicht | Zeitstrahl | niedrig |
| 8 Ersteinrichtung | neuer Ablauf mit Kategorien und „gehört zu“ | mittel |
| 9 Abschluss | alte Bildschirme und toten Code entfernen, Doku, Website, Skill-Nachträge, Prüfrunde über unverändertem Code | niedrig |

Die Etappen-Issues werden **erst zu Beginn der jeweiligen Etappe** in Pakete geschnitten (vom
Betreuer, siehe unten), nicht alle vorab. Der Code sieht nach Etappe 3 anders aus als heute; vorab
geschnittene Pakete für Etappe 6 wären dann veraltet.

Das Risiko der Etappe ist **nicht** das Risiko jedes Pakets: Der Betreuer stuft jedes Paket einzeln
ein. Schon Etappe 1 enthält Migration und Erkennung (Opus) und berührt Kerntests
(`ShiftRecognitionEngineTest`, `AlarmReceiverSilentGateTest`, `KrankSyncTest`,
`ShiftConfigDecodeTest`) – dort ist das Feld „bewusste Verhaltensänderung“ Pflicht.

**Vor Etappe 3 und vor Etappe 9 ist der Eigentümer dran (interaktiv, etwa 15 Minuten):** Diese
Etappen entfernen Symbole (globaler Dimm-Schalter, Regel-Editor, DND-Seite, alte Bildschirme), die
die Skills `cfalarm-dimmer-und-dnd`, `cfalarm-hue` und `cfalarm-ui-und-navigation` nennen.
`tools/aufraeumen/pruefe_reste.py` meldet jeden Skill-Absatz, der ein entferntes Symbol nennt, und
die Schleuse blockiert dann den Merge. Headless kann `.claude/**` nicht schreiben. Deshalb werden
die Skills vorher interaktiv auf den Zielzustand gebracht (oder die Absätze mit „entfernt (Umbau,
Etappe N)“ als historisch markiert). Der Runner wartet mit `bereit` dieser Etappen, bis das Epic
das Label `skills-bereit-N` trägt.

## 4. Aufbau eines Paket-Issues

```
Teil von: #<Etappe>          Blockiert von: #<nr>, #<nr>
Umfang: <ein Satz, was danach anders ist>
Spec: SPEC.md Abschnitt <x>
Dateien: <vollständige Liste; nur diese werden angefasst>
Umziehen aus: <Quelldatei:Zeilen>, falls zutreffend
Skill: <cfalarm-...>
Abnahme:
- [ ] ./gradlew testDebugUnitTest grün (Ausgabe im Log)
- [ ] neuer Test, der ohne die Änderung rot ist
- [ ] UI-Prüfung: <Bildschirm> gegen prototyp/<Datei> (Texte und Reihenfolge per uiautomator, Screenshot bewertet >= 14/16)   (nur bei UI)
- [ ] Prüfer ohne Umsetzungskontext: keine offenen Befunde
Kerntests: unberührt | bewusste Verhaltensänderung: <welcher Test, warum>
Größe: S (40 Turns) | M (60) | L -> vorher teilen
Risiko: niedrig/mittel (Sonnet setzt um) | hoch (Opus setzt um)
```

Das Feld **Dateien** ist nötig, damit zwei Pakete parallel laufen können (nur ohne gemeinsame
Dateien) und damit der Prüfer merkt, wenn ein Lauf ausufert.

## 5. Die Besetzung

| Rolle | Modell | Aufgabe |
|---|---|---|
| **Betreuer** | Opus 5.5, Effort xhigh, Advisor Fable | Zu Beginn einer Etappe: Etappe in Pakete schneiden (Issues anlegen), Abhängigkeiten setzen. Am Ende: Etappen-Abschluss (Abschnitt 7). Er setzt nichts selbst um. |
| **Umsetzer** | nach Risiko des Pakets: **Sonnet 5.5** (niedrig/mittel: UI-Umzug, Texte, Zeitstrahl, Bildschirme) oder **Opus 5.5** (hoch: Wecker, Persistenz, Migration, Erkennung), jeweils xhigh | Ein Paket pro Lauf. Fragt den Advisor an genau drei Stellen: vor dem Plan, beim zweiten gleichen Fehler, vor der Fertigmeldung. |
| **Handlanger** | Haiku 5.5 als Subagent | Nur Mechanisches: Code umziehen samt Importen, Texte, Screenshot-Läufe, Doku nachziehen. Nach jedem Handlanger-Schritt laufen Tests und der Prüfer. |
| **Prüfer** | immer ein anderes Modell als der Umsetzer: Opus prüft Sonnet, Sonnet prüft Opus | Sieht nur `git diff`, Issue, Spec und Prototyp-Code, nicht den Weg. Befund nur mit Beleg (Datei, Zeile, Eingabe, Soll/Ist). |
| **Gegenprüfer** | Fable 5.1 (Etappen-Review) + 3er-Widerlegung | Wie bisher: Dimensionen nach Risiko, jeder Befund von drei unabhängigen Agenten zu widerlegen versucht. |

Zu Haiku: Die Prompt-Werkstatt (`stand-pruefen/references/annahmen.md`, geprüft 12.10.2026, offizielle
Quelle) ordnet Haiku 5.5 für eng umrissene Teilaufgaben ein, nicht für komplexes agentisches Coding.
Dieser Code hat viele stille Invarianten (Wecker, Persistenz); deshalb setzen Sonnet oder Opus um, und Haiku zieht
nur um. Nach Etappe 0 lässt sich an echten Zahlen prüfen, ob Haiku mehr übernehmen kann.

Kosten: Fable läuft auf Max über Nutzungsguthaben und liest bei jedem Advisor-Aufruf das ganze
Transcript ungecacht. Deshalb nur an festen Stellen.

## 6. Der Runner

`tools/umbau/runner.ps1` (nur ASCII, PowerShell 5.1), gebaut nach der Vorlage `run-all.ps1` der
Prompt-Werkstatt. Er arbeitet in einer Schleife:

0. **Beim Start:** Sperrdatei `..Projektdateien/umbau/runner.lock` (mit PID) anlegen; läuft schon
   ein Runner, sofort beenden. Jedes Issue `in-arbeit` ohne lebenden Runner ist ein Absturzrest: es
   wird **fortgesetzt** (sein Branch und `lauf-N` sagen, wo es steht), nicht vergessen.
   Emulator läuft nicht → selbst starten und mit der Test-PIN entsperren.
1. **Vorabprüfung vor jedem Lauf:** `adb devices` zeigt nur `emulator-5554`. Hängt das Fairphone am
   Kabel, laufen nur Pakete mit Label `ohne-geraet`, sonst wartet er. Notbremse gesetzt? (Label
   `umbau-pause` am Epic oder Datei `..Projektdateien/umbau/STOP`) → Ende.
2. **Nächstes Paket wählen:** offenes Issue mit `umbau` + `bereit`, alle „Blockiert von“ geschlossen,
   kleinste Nummer zuerst. Kein Paket wählbar → nur dann Betreuer-Lauf „Etappe abschließen“, wenn
   **kein** Issue der Etappe offen ist (auch kein `in-arbeit`, kein `blockiert`); sonst **Stopp mit
   Bericht im Epic**. Keine Etappe offen → **Ende**.
3. **Lauf:** Labels `in-arbeit` und `lauf-1` (bzw. `lauf-2`) setzen, Worktree unter
   `..\CFAlarm-umbau-wt\<nr>` anlegen, `keystore.properties` und `local.properties` hineinkopieren
   (belegt 16.10.2026: so baut er), Gradle vorwärmen (`testDebugUnitTest lintDebug`), dann
   `claude -p "/goal …" --model <claude-sonnet-5-5 | claude-opus-5-5 nach Risiko> --effort xhigh
   --max-turns <Cap+5>`. Der Prozess bekommt `ANDROID_SERIAL=emulator-5554` (adb und Gradle-Install
   treffen dann nur den Emulator, auch wenn das Fairphone währenddessen angesteckt wird),
   `--strict-mcp-config` ohne MCP-Server (headless laden die Emulator-MCPs ohnehin nicht, am
   16.10.2026 gemessen; alles läuft über `adb`) und `--disallowedTools` für die PowerShell- und
   Desktop-Werkzeuge, damit jedes `git` durch die Bash-Schleuse muss.
   Das Ziel enthält: Issue-Text, Abnahme-Liste, Turn-Cap, „keine Rückfragen, Entscheidungen im Issue
   begründen“, „nach jedem grünen Schritt committen und pushen“, die drei Advisor-Stellen.
4. **Ergebnis:** Fertig heißt: Abnahme erfüllt, Prüfer ohne offenen Befund, Branch in
   `feature/umbau-schichttyp` gemergt (Schleuse grün), Issue mit Abschlusskommentar geschlossen.
   Endet der Lauf anders (Exit ≠ 0, Turn-Grenze), schreibt **der Runner selbst** den Stand ins Issue
   (letzte Commits des Branches, Ende des Logs) – der Agent hat nach `--max-turns` keinen Zug mehr.
   Dann zweiter Lauf mit „Teilarbeit liegt vor, prüfen und fortsetzen“. Scheitert auch der: Label
   `blockiert`, Grund ins Issue, weiter mit dem nächsten unabhängigen Paket.
   **Sonderfall Schleuse meldet „Doku nennt entferntes Symbol“:** kein zweiter Lauf, sondern
   `blockiert` mit „braucht Eigentümer (Skill)“ – das kann headless niemand beheben.
5. **Limit erreicht** (5-h- oder Wochenlimit): Reset-Zeit aus der Meldung lesen, bis dahin
   schlafen, dann **dasselbe Paket** fortsetzen; das zählt nicht als Lauf. Kein blindes
   Wiederholen vorher.
6. Nach jedem Schritt eine Zeile in `STATUS.txt` (Zeit, Etappe, Paket, Ergebnis).

**Wo gemergt wird:** Der Runner hat zwei feste Worktrees, `..\CFAlarm-umbau-wt\integration`
(`feature/umbau-schichttyp`) und `..\CFAlarm-umbau-wt\main` (`main`). Nur dort wird gemergt und
gepusht, damit die Schleuse den Branch-Namen sieht, den sie für ihre Release-Prüfungen braucht.
Git erlaubt einen Branch nur in einem Worktree: **solange der Runner läuft, wird `main` im
Hauptbaum nicht ausgecheckt** (der Runner prüft `git worktree list` vor dem Abschluss).

**Start und Wiederaufnahme sind derselbe Befehl.** Weil der Zustand in GitHub liegt, braucht der
Runner kein Gedächtnis: nach PC-Neustart, Absturz oder Limit einfach erneut starten. Als Sicherheitsnetz
startet eine Windows-Aufgabe ihn stündlich, falls er nicht läuft.

**Parallel:** Mit `-Parallel 2` laufen zwei Pakete gleichzeitig, aber nur, wenn ihre Dateilisten sich
nicht überschneiden und höchstens eines den Emulator braucht. Empfehlung: Etappe 0 und 1 einzeln,
danach nach Verbrauch entscheiden. Cloud-Sitzungen können Pakete mit `ohne-geraet` übernehmen;
dieselben Labels verhindern Doppelarbeit.

## 7. Etappen-Abschluss (vollautonome Auslieferung)

Ein Bump auf `main` liefert an die Tester aus. Weil die Auslieferung ohne Abnahme am Fairphone
läuft, sind die Wachen hier strenger als bisher. Der Betreuer-Lauf macht der Reihe nach:

1. `git fetch`, `origin/main` in den Integrations-Branch mergen (parallele Sitzungen!).
2. **Gegenprüfung** per Workflow über den Etappen-Diff: Dimensionen nach Risiko (mindestens
   Wecker-Invarianten, Persistenz/Migration, UI 320 dp), Fable, 3er-Widerlegung, bestätigte Befunde
   beheben. Zahlen nach `project_prueflauf_historie`.
3. **Volle Prüfung am Emulator**, jede mit Beleg im Log:
   voller Testlauf + Lint · Release-Build (R8) Kaltstart ohne Absturz · alle Tabs einmal
   durchklicken · Wecker per Zeitsprung auslösen (Vollbild, Schlummern, Aus) · Direct Boot mit PIN ·
   Migration aus der Export-Vorlage verlustfrei · UI-Prüfung aller Bildschirme der Etappe gegen den Prototyp.
4. Nur wenn alles grün ist: Merge nach `main`, **Minor-Bump**, Changelog in Nutzersprache, Push. Die
   Auslieferung übernimmt `veroeffentlichen.yml`. Lehnt GitHub den Push ab, weil `main` sich
   inzwischen bewegt hat (Sammel-Release 09:00 UTC, Cloud-Sitzungen): fetch, erneut mergen, erneut
   pushen – das zählt **nicht** als gescheiterter Versuch. Nur ein Konflikt zählt.
5. Etappen-Issue schließen, nächste Etappe auf `bereit`, Bericht als Kommentar im Epic (was
   ausgeliefert ist, was am Fairphone anzusehen lohnt).

Fällt danach im Alltag etwas auf: Kommentar im Epic oder Label `umbau-pause`. Rücknahme heißt Revert
des Etappen-Merges plus Patch-Bump – nie Force-Push. Das trägt nur, weil jede Migration **kopiert
statt verschiebt** (SPEC Abschnitt 4): der zurückgenommene Code findet seinen alten Bestand
unverändert vor.

## 8. Harte Grenzen

| Grenze | Wert | Folge |
|---|---|---|
| Turns je Lauf | S 40 · M 60 | Stand ins Issue, zweiter Lauf |
| Läufe je Paket | 2 | `blockiert`, weiter mit dem nächsten |
| zwei Pakete hintereinander `blockiert` | – | **Runner stoppt**: die Spec ist das Problem, nicht der Code |
| neue Issues je Paket | 3, nur als Teilung desselben Pakets | mehr = Paket war zu groß, `blockiert` |
| neue Issues je Etappe | 15 | Runner stoppt |
| Fund außerhalb des Umfangs | – | Issue mit Label `spaeter`, wird in diesem Umbau **nicht** abgearbeitet (sonst endet die Schleife nie) |
| Etappen-Abschluss gescheitert | 2 Versuche | Runner stoppt, Bericht im Epic |
| Etappe hat offene oder blockierte Pakete, aber kein wählbares | – | Runner stoppt, Bericht im Epic – **kein** Abschluss mit Lücke |
| verboten | Force-Push, History umschreiben, Push auf `main` außer im Etappen-Abschluss, Bump außerhalb des Etappen-Abschlusses, Fairphone ansprechen, Kerntests ändern ohne benannte Verhaltensänderung, `.claude/**` schreiben, `CFALARM_SCHLEUSE=aus` | Wo möglich mechanisch erzwungen, nicht nur im Ziel-Text: `ANDROID_SERIAL`, gesperrte Werkzeuge, Deny-Regel für `CFALARM_SCHLEUSE` (Abschnitt 9) |

**Ende des Umbaus:** alle Etappen-Issues geschlossen, Abschnitt 5 der Spec erfüllt, Epic
geschlossen. Dann beendet sich der Runner und sagt das in `STATUS.txt` und im Epic.

## 9. Vorbereitung vor dem Start (interaktiv, nicht durch den Runner)

Erledigt (16.10.2026):
- Zuordnung Bildschirm → Prototyp-Stelle und Pflichttexte: `BILDSCHIRME.md`.
- Skill `cfalarm-ui-pruefen` und `tools/geraet/pruefe_texte.py` (mit Test, live am Emulator belegt).
- Sitzungsstart zeigt den Umbau (ohne Netz).
- Ein frischer Worktree baut, wenn die zwei Schlüsseldateien hineinkopiert werden.
- Headless hat das Workflow-Tool, aber anfangs keine Emulator-MCPs → alles über `adb`.
- Unabhängige Prüfung dieses Ablaufs (Fable, ohne Umsetzungskontext): 3 blockierende, 6 wichtige,
  3 kleine Befunde, alle eingearbeitet.

Offen:
1. **Schleuse fail-closed machen** (`.claude/settings.json`, braucht die Freigabe des Eigentümers):
   Die Schleuse braucht im schlechtesten Fall 300 + 300 + 180 = 780 s, der Hook-Timeout ist 600 s,
   und ein abgelaufener Hook **lässt den Befehl durch**. Ein kalter Worktree ist genau dieser Fall.
   Änderung: Timeout 900 s, `"onFailure": "block"`, Matcher `Bash|PowerShell`, Deny-Regel für
   `CFALARM_SCHLEUSE`. Wirkt auch für interaktive Sitzungen.
2. **`tools/umbau/runner.ps1`** nach Abschnitt 6, die stündliche Windows-Aufgabe, die Labels.
3. ~~Migrations-Testvorlage~~ erledigt: der Export vom 16.10.2026 liegt lokal unter
   `..Projektdateien/umbau/testvorlagen/` (Eigentümer: nur lokal; der Umbau läuft ohnehin auf
   diesem PC). Die nachgebaute Vorlage fürs Repo entsteht im ersten Migrations-Paket.
4. **Epic und Etappe-0-Issues** anlegen.
5. **Probelauf** Etappe 0 beobachtet: prüfen, ob `/goal`, Advisor und `--max-turns` headless wie
   erwartet greifen; Turn-Caps und Verbrauch danach kalibrieren.
