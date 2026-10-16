# Ablauf: wie der Umbau autonom abgearbeitet wird

Stand: 16.10.2026. Was gebaut wird, steht in [`SPEC.md`](SPEC.md). Diese Datei beschreibt, **wie**:
Agenten arbeiten viele kleine Issues nacheinander ab, sichern jeden Schritt in Git und machen nach
einem Limit oder Absturz von selbst dort weiter, wo der letzte Stand liegt.

Entscheidungen des Eigentümers (16.10.2026): Spec und Soll-Bilder liegen öffentlich im Repo · der
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
| Spec, Soll-Bilder, Prototyp-Quellen | `entwurf/umbau-schichttyp/` (öffentlich, nicht unter `docs/`, also nicht auf der Website) |
| Gesamtplan | Epic-Issue „Umbau: Schichttyp als Zentrale“ mit einer Checkliste der Etappen-Issues |
| Arbeitspakete | ein Issue je Paket, Label `umbau` + `etappe-N` |
| Zustand eines Pakets | Labels `bereit` → `in-arbeit` → (geschlossen); `blockiert` mit Grund im Kommentar |
| Integrations-Branch | `feature/umbau-schichttyp` (gepusht, Backup über Sitzungen hinweg) |
| Issue-Branch | `feature/umbau-<nr>-<kurz>`, wird nach Abnahme in den Integrations-Branch gemergt und gelöscht |
| Lauf-Protokoll | `..Projektdateien/umbau/logs/` (gitignored; je Lauf ein Log, `STATUS.txt`) |
| Unbelegte Fehlerhypothesen | `..Projektdateien/umbau/hypothesen.md` – **nie** in ein öffentliches Issue |
| Lehren für Skills | `entwurf/umbau-schichttyp/skill-nachtraege.md` (headless kann `.claude/**` nicht schreiben; der Eigentümer übernimmt sie interaktiv) |

`tools/sitzungsstart.py` zeigt zusätzlich: aktuelle Etappe, laufendes Issue (seit wann) und das
nächste freie Issue. So weiß jede neue Sitzung – lokal oder Cloud – sofort, wo der Umbau steht.

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
- [ ] verify-ui: <Bildschirm> gegen soll/<bild>.png, Bewertung >= 14/16   (nur bei UI)
- [ ] Sonnet-Prüfer ohne Umsetzungskontext: keine offenen Befunde
Kerntests: unberührt | bewusste Verhaltensänderung: <welcher Test, warum>
Größe: S (40 Turns) | M (60) | L -> vorher teilen
```

Das Feld **Dateien** ist nötig, damit zwei Pakete parallel laufen können (nur ohne gemeinsame
Dateien) und damit der Prüfer merkt, wenn ein Lauf ausufert.

## 5. Die Besetzung

| Rolle | Modell | Aufgabe |
|---|---|---|
| **Betreuer** | Opus 5.5, Effort xhigh, Advisor Fable | Zu Beginn einer Etappe: Etappe in Pakete schneiden (Issues anlegen), Abhängigkeiten setzen. Am Ende: Etappen-Abschluss (Abschnitt 7). Er setzt nichts selbst um. |
| **Umsetzer** | Opus 5.5, xhigh | Ein Paket pro Lauf. Fragt den Advisor an genau drei Stellen: vor dem Plan, beim zweiten gleichen Fehler, vor der Fertigmeldung. |
| **Handlanger** | Haiku 5.5 als Subagent | Nur Mechanisches: Code umziehen samt Importen, Texte, Screenshot-Läufe, Doku nachziehen. Nach jedem Handlanger-Schritt laufen Tests und der Sonnet-Prüfer. |
| **Prüfer** | Sonnet 5.5 als Subagent | Sieht nur `git diff`, Issue und Soll-Bild, nicht den Weg. Befund nur mit Beleg (Datei, Zeile, Eingabe, Soll/Ist). |
| **Gegenprüfer** | Fable 5.1 (Etappen-Review) + 3er-Widerlegung | Wie bisher: Dimensionen nach Risiko, jeder Befund von drei unabhängigen Agenten zu widerlegen versucht. |

Zu Haiku: Die Prompt-Werkstatt (`stand-pruefen/references/annahmen.md`, geprüft 12.10.2026, offizielle
Quelle) ordnet Haiku 5.5 für eng umrissene Teilaufgaben ein, nicht für komplexes agentisches Coding.
Dieser Code hat viele stille Invarianten (Wecker, Persistenz); deshalb setzt Opus um und Haiku zieht
nur um. Nach Etappe 0 lässt sich an echten Zahlen prüfen, ob Haiku mehr übernehmen kann.

Kosten: Fable läuft auf Max über Nutzungsguthaben und liest bei jedem Advisor-Aufruf das ganze
Transcript ungecacht. Deshalb nur an festen Stellen.

## 6. Der Runner

`tools/umbau/runner.ps1` (nur ASCII, PowerShell 5.1), gebaut nach der Vorlage `run-all.ps1` der
Prompt-Werkstatt. Er arbeitet in einer Schleife:

1. **Vorabprüfung:** `adb devices` zeigt nur `emulator-5554`. Hängt das Fairphone am Kabel, laufen
   nur Pakete ohne Gerät (Label `ohne-geraet`), sonst wartet er.
   Notbremse gesetzt? (Label `umbau-pause` am Epic oder Datei `..Projektdateien/umbau/STOP`) → Ende.
2. **Nächstes Paket wählen:** offenes Issue mit `umbau` + `bereit`, alle „Blockiert von“ geschlossen,
   kleinste Nummer zuerst. Kein Paket da, aber die Etappe ist offen → Betreuer-Lauf „Etappe
   abschließen“. Keine Etappe offen → **Ende**.
3. **Lauf:** Label `in-arbeit` setzen, eigener Worktree, `claude -p "/goal …" --model
   claude-opus-5-5 --effort xhigh`. Das Ziel enthält: Issue-Text, Abnahme-Liste, Turn-Cap nach
   Größe, „keine Rückfragen, Entscheidungen im Issue begründen“, „nach jedem grünen Schritt
   committen und pushen“, die drei Advisor-Stellen.
4. **Ergebnis:** Fertig heißt: Abnahme erfüllt, Sonnet-Prüfer ohne offenen Befund, Branch in
   `feature/umbau-schichttyp` gemergt (Schleuse grün), Issue mit Abschlusskommentar geschlossen.
   Sonst ein zweiter Lauf mit „Teilarbeit liegt vor, prüfen und fortsetzen“. Scheitert auch der:
   Label `blockiert`, Grund ins Issue, weiter mit dem nächsten unabhängigen Paket.
5. **Limit erreicht** (5-h- oder Wochenlimit): Reset-Zeit aus der Meldung lesen, bis dahin
   schlafen, dann **dasselbe Paket** fortsetzen. Kein blindes Wiederholen vorher.

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
   Migration aus der Export-Vorlage verlustfrei · Soll-Ist-Vergleich aller Bildschirme der Etappe.
4. Nur wenn alles grün ist: Merge nach `main`, **Minor-Bump**, Changelog in Nutzersprache, Push. Die
   Auslieferung übernimmt `veroeffentlichen.yml`.
5. Etappen-Issue schließen, nächste Etappe auf `bereit`, Bericht als Kommentar im Epic (was
   ausgeliefert ist, was am Fairphone anzusehen lohnt).

Fällt danach im Alltag etwas auf: Kommentar im Epic oder Label `umbau-pause`. Rücknahme heißt Revert
des Etappen-Merges plus Patch-Bump – nie Force-Push.

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
| verboten | Force-Push, History umschreiben, Push auf `main` außer im Etappen-Abschluss, Bump außerhalb des Etappen-Abschlusses, Fairphone ansprechen, Kerntests ändern ohne benannte Verhaltensänderung, `.claude/**` schreiben, `CFALARM_SCHLEUSE=aus` | |

**Ende des Umbaus:** alle Etappen-Issues geschlossen, Abschnitt 5 der Spec erfüllt, Epic
geschlossen. Dann beendet sich der Runner und sagt das in `STATUS.txt` und im Epic.

## 9. Was vor dem Start noch zu bauen ist (interaktiv, nicht durch den Runner)

1. **Soll-Bilder** je Bildschirm aus dem Prototyp nach `soll/`.
2. **Skill `cfalarm-ui-pruefen`** (`.claude/skills/`, deshalb interaktiv): Debug-Build, nur auf
   `emulator-5554` installieren, Bildschirm ansteuern, Screenshot, Vergleich mit dem Soll-Bild per
   Bewertungsliste (16 Punkte: Inhalt, Reihenfolge, Texte, 320 dp, Dunkelmodus …), Sonnet bewertet.
3. **`tools/umbau/runner.ps1`** und die Windows-Aufgabe; Labels anlegen; `sitzungsstart.py` erweitern.
   Was der Runner dabei selbst können muss, sonst steht er am ersten Tag:
   - **Worktrees bauen nicht von allein:** `keystore.properties` und `local.properties` sind
     gitignored; der Runner kopiert beide in jeden neuen Worktree (ohne `googleWebClientId` wirft
     der Build). Vorher mit einem Wegwerf-Worktree und `assembleDebug` belegen.
   - **Emulator nach PC-Neustart:** selbst starten (`-avd Medium_Phone -timezone Europe/Berlin`) und
     mit der Test-PIN entsperren.
   - **Sperrdatei:** die stündliche Aufgabe darf keinen zweiten Runner starten, auch nicht während
     eines tagelangen Wartens auf das Wochenlimit.
   - **Workflow-Tool headless:** es startet nur auf ausdrücklichen Wunsch – das Ziel des
     Etappen-Abschlusses muss „use a workflow“ wörtlich enthalten.
   - **Werkzeuge im `-p`-Modus:** ob die Emulator-MCP-Werkzeuge headless laden, ist unbelegt. Das
     ist die erste Prüfung des Probelaufs; sonst `adb` direkt.
   Erst mit der Erweiterung von `sitzungsstart.py` finden Cloud-Sitzungen den Umbau (Memory ist
   lokal); bis dahin gibt es keine Pakete `ohne-geraet` für die Cloud.
4. **Export-Vorlage** einer echten Konfiguration (vom Eigentümer, Fairphone → Export), bereinigt als
   Testvorlage für die Migration.
5. **Epic und Etappe-0-Issues** anlegen; die Spec von Fable gegenlesen lassen (wie bei #142: Muss/Soll).
6. **Probelauf** Etappe 0 beobachtet; Turn-Caps und Verbrauch danach kalibrieren.
