---
name: cfalarm-altlasten-abtragen
description: "Arbeitet EINEN Aufraeum-Blickwinkel ab - fuer autonome Sitzungen ohne Vorwissen. Zu verwenden, wenn ein Auftrag lautet 'raeum weiter auf', 'arbeite die Aufraeum-Liste ab', 'naechste Runde' oder wenn eine geplante/headless Sitzung ohne naeheren Auftrag startet. Enthaelt die Disziplin je Runde, die Leitplanken gegen Zuviel-Wegschneiden, die bereits VERWORFENEN Blickwinkel mit ihren Messzahlen (nicht noch einmal versuchen) und den Abschluss ueber Branch und Pull Request."
---

# Altlasten abtragen — eine Runde pro Sitzung

Diese App trägt Baugerüst aus einem Jahr: Code, den nie jemand aufgerufen hat, Konstanten auf
Vorrat, Kommentare ohne Deklaration. **Es ist kein Verfall** — in sieben Runden wurden 525 Zeilen
entfernt und **kein einziger Fehler** gefunden (0 `fix`-Commits). Wer das anders erzählt,
verunsichert den Eigentümer ohne Grund.

## Was du in dieser Sitzung tust

**Genau EINEN Blickwinkel.** Nicht zwei, nicht „schnell noch". Ein Blickwinkel ist eine Frage der
Form „welche Art von Leiche könnte es noch geben, die noch niemand gesucht hat?".

```bash
gh issue list --label aufraeumen --state open      # das ist die Warteschlange
```

Nimm den obersten offenen Eintrag. Gibt es keinen, ist die Liste leer — dann **nichts erfinden**,
sondern das melden und aufhören. Ein leerer Zustand ist das Ziel, kein Versagen.

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

## Verworfen — gemessen, nicht gatterfähig. NICHT noch einmal versuchen

| Blickwinkel | Messung | Warum |
|---|---|---|
| „Ungenutzte Funktion" über gezählte Namensreferenzen | **222 Kandidaten, praktisch alle falsch** | Hilt-Provider ruft niemand beim Namen, Compose-Funktionen dateiintern, Lebenszyklus vom Framework |
| Ungenutzter Funktionsparameter | 14 Kandidaten, **alle** falsch | allesamt `override` mit vom Framework vorgegebener Signatur |
| „Doku nennt Symbol, das der Code nicht hat" (ungefiltert) | 54 Treffer, **53 falsch** | die Doku nennt zu Recht Plattform-APIs (`startForeground()`) |
| Manifest-Berechtigung ohne Nennung im Code | 4 Kandidaten, **alle** falsch | implizit gebraucht (ConnectivityManager, startForeground, BootReceiver, Vibrator) |
| Tests ohne sichtbare Behauptung | 16 Kandidaten, **alle** falsch | sie behaupten über Helfer (`erwarteNachzug()`) |
| Doku nennt Datei, die es nicht gibt | 1 Treffer, falsch | stand in einem *historischen* Satz — das ist der Hergang, kein Fehler |
| Testdatei ohne `@Test` = ungenutztes Test-Double | **6 Kandidaten, 5 falsch** (Runde 8) | Fakes und Fixture-Helfer tragen naturgemäß kein `@Test` und werden trotzdem von 2–20 Testklassen benutzt. Die Klasse ist zu klein und zu falsch-positiv für ein Gatter — der eine echte Fund war kein Test-Double, sondern eine JUnit-`Suite` |

**Akzeptierte Dauermeldungen** (nicht als Fund melden): Lint `TrustAllX509TrustManager` ×2 (der
bewusste Hue-TrustManager), `PluralsCandidate` („%d Min" hat im Deutschen keine Pluralform),
`ObsoleteSdkInt` für `mipmap-anydpi-v26` (siehe oben). Compiler: `createEmptyComposeRule`, steht mit
Ausweg in `tools/aufraeumen/warnungen_geduldet.txt`.

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

## Diesen Skill lebendig halten

Jede Runde ergänzt hier: neue Leitplanke, wenn ein Fehlurteil passiert ist; neue Zeile in
„Verworfen", wenn ein Blickwinkel nichts taugt; Korrektur, wenn eine Aussage hier sich als falsch
erweist. **Danebenschreiben ist ein Fehler, nicht Verlauf.** Für den Hergang der Werkzeuge selbst
siehe `tools/aufraeumen/pruefe_reste.py` (die Kommentare dort tragen jede Messung) und
`cfalarm-arbeit-abschliessen`.
