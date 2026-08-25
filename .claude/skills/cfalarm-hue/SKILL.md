---
name: cfalarm-hue
description: "Zusicherungen fuer die Philips-Hue-Anbindung der CFAlarm-Wecker-App: die V1-API-Semantik (HTTP 200 auch bei Ablehnung, das Urteil steht im Body), Bridge-Verbindung und Erreichbarkeitsheuristik, Regelabgleich ueber Lampennamen statt bridge-lokaler IDs, das Auto-Aus als Bridge-Zeitplan sowie Vorschau und Lampentest. Zu verwenden bei Arbeit an HueApiClient, HueV1Envelope, HueBridgeConnectionManager, HueLightUseCase, HueRuleUseCase, HueSmartScheduler, HueTargetReconciler, HueViewModel oder HueRuleConfigScreen — und immer dann, wenn Licht am Wecktag nicht angeht, nicht wieder ausgeht, die falsche Lampe schaltet, die Bridge als nicht erreichbar gilt oder der Lampentest mehrfach blinkt."
---

# Philips Hue

Unten stehen die **Kurzregeln** dieses Bereichs — was gilt, und was bei Bruch passiert.
Die wecker-kritische Teilmenge davon steht zusätzlich in `CLAUDE.md` (dort immer geladen, als
Sicherheitsnetz für den Fall, dass dieser Skill nicht anspringt); **alles Übrige steht
ausschließlich hier.** **Reicht die Kurzregel nicht, oder willst du eine davon ändern oder
umgehen: lies vorher die Hergang-Datei.** Dort steht, welcher Bug die Regel erzwungen hat — ohne
das baut man dieselbe Falle in neuer Form nach.

## Hergang und Belege

- `reference/hue-api-und-regeln.md` — API-Semantik, Verbindung, Regelabgleich, Auto-Aus, Scopes
- `reference/vorschau-und-lampentest.md` — Regel-Vorschau und das Blitzen einzelner Lampen

---

## Kurzregeln

- **Das Auto-Aus gehört der BRIDGE, nicht dem Handy** — als Bridge-Zeitplan im selben Atemzug mit dem
  Einschalten. Der frühere `AutoOffWorker` ist ersatzlos weg; kein Fallback nachbauen.
- **Timer (`PT00:30:00`), nicht absolute Zeit** — läuft auf der Uhr der Bridge, immun gegen Drift/DST.
- **Die V1-API antwortet auch bei ABLEHNUNG mit HTTP 200** — das Urteil steht im Body (`HueV1Envelope`).
  `parseAll` ist streng (GET-Endpunkte, Fehlerhülle wird VOR dem Parsen erkannt), `parseControl`
  bewusst milder („mindestens ein `success`"), und **jedes vorhandene `success`-Feld zählt, auch als
  String** (DELETE antwortet mit einem String). **Auch die Steuer-Endpunkte werten den Body aus.**
- **Ein Fehlschlag der Bridge darf nicht zur leeren Liste degradieren** (`getAllLightTargets()`).
- **`getBridgeConfig` prüft die Antwort** (`bridgeid` oder `mac`), statt sie nur zu deserialisieren —
  Gson erzwingt Kotlins Non-Null-Deklarationen NICHT.
- **Der gesamte Hue-Pfad ist IPv4-only**; mDNS wählt die erste IPv4-Adresse, Nur-IPv6 liefert `null`.
- **Die Subnetz-Prüfung ist ein HINWEIS auf das Timeout, NIEMALS ein Veto** — sie liefert
  Falsch-Negative bei Gast-WLAN, VLAN, Mesh, Doppel-NAT und am Emulator. **Beide** Aufrufstellen
  (Einrichtung und Cache-Pfad) sind so gebaut; der Verbindungszustand wird dabei nicht auf `ERROR`
  herabgestuft.
- **`healthCheckScope` braucht einen `CoroutineExceptionHandler`** — ein `SupervisorJob` isoliert nur
  Geschwister, die Exception beendet trotzdem den PROZESS. Das `try/catch` des
  Netzwerk-Recovery-Collectors liegt **INNERHALB** des `collect`.
- **`cleanup()` auf Prozess-Singletons cancelt NUR Kinder** (`cancelChildren()`), nie den Scope.
- **`HueBridgeConnectionManager.initialize()` muss idempotent bleiben** (zwei Aufrufer ohne feste
  Reihenfolge).
- **Eine im Direct-Boot übersprungene Hue-Planung wird NACHGEHOLT**
  (`retrySkippedSchedulingIfNeeded()`, zwei Aufrufstellen).
- **`HueSmartScheduler.getInstance()` veröffentlicht `INSTANCE` erst NACH `initialize()`.**
- **„Bridge eingerichtet" und „Bridge verbunden" sind zwei Fragen** — der Scheduler prüft
  `hasStoredBridge()` (persistierter Wert), nicht `getCurrentConnectionInfo()`.
- **`findApplicableRules` matcht exakten Definitionsnamen ODER `UNIVERSAL_SHIFT_PATTERN`** — kein
  Keyword, kein Teiltreffer. **`autoOffTargetsOf()` filtert bewusst NICHT nach Schichtnamen**
  (sonst verlören UNIVERSAL-Regeln ihr Auto-Aus).
- **`UNIVERSAL_SHIFT_PATTERN = "ALL"` ist über den Regel-Editor erreichbar**; UI referenziert die
  Konstante, der Rücklesepfad nutzt denselben Maßstab (`ignoreCase`), Anzeige sagt „Alle Schichten".
  Das Matching selbst bleibt unverändert.
- **`HueLightAction.targetId` ist BRIDGE-LOKAL — der Anker über Geräte hinweg ist `targetName`.**
  Bei Mehrdeutigkeit lieber nicht zuordnen als falsch; Lampen- und Gruppen-Namensraum bleiben
  getrennt; eine gescheiterte Abfrage ändert und meldet NICHTS (`lightsFailed`/`groupsFailed`).
  Zurückschreiben INNERHALB einer `dataStore.edit{}`-Transaktion, nicht bei offenem Editor, und
  NICHT im Weckpfad.
- **`Result<RuleValidationResult>` hat zwei Ebenen** — `isFailure` heißt „Prüfung gescheitert", nicht
  „Regel ungültig".
- **`MIN_RULE_NAME_LENGTH = 1`**, nicht mehr (eine reale Regel heißt „FS").
- **Das 90-Zeichen-Limit für `command` aus der offiziellen Doku greift nicht** (gemessen gegen BSB002).
- **Auf der Bridge liegen fremde Zeitpläne** — aufgeräumt wird ausschließlich, was
  `BridgeTimer.NAME_PREFIX` trägt. Und aufgeräumt werden **muss**.
- **Die Regel-Vorschau räumt IMMER auf**, unabhängig vom Auto-Aus der Regel — anders als der echte
  Weckvorgang. Beim Sonnenaufgang hängt das Aus HINTER der Rampe.
- **`runLightTest()` blitzt LAMPEN, niemals Gruppen** (Gruppen überschneiden sich beliebig, auch
  untereinander). `flashLight(lightId)` nimmt deshalb bewusst kein `isGroup`-Flag mehr.
- **`flashLight` nutzt `lselect` und bricht es nach 4 s selbst ab** (`alert:"none"`).
- **Abbruch-Timer und Vorschau-Auto-Aus hängen an `followUpScope`, nicht am Aufrufer.**
- **Nichts in `runLightTest()` darf `refreshLightTargets()` benutzen** — das ist fire-and-forget.
  Wer Ziele braucht, ruft `getAllLightTargets()` und **wartet**.
- **Regel speichern navigiert sofort weg** — ein Fehler landet dadurch auf dem `HueSettingsScreen`
  statt im Formular (bekannt, bisher unkritisch).

### Szenen (seit v1.35.0)

- **Eine Szene ist KEIN vierter Zieltyp, sondern ein Zusatz zu einem GRUPPEN-Ziel.** Sie traegt
  `isGroup = true`, `targetId` = Gruppe und wird ueber `PUT /groups/<id>/action {"scene": ...}`
  angewendet — genau den Pfad, den `isGroup` ohnehin waehlt. Deshalb bleiben Ausfuehrung,
  `autoOffTargetsOf()` und `BridgeTimer` unveraendert.
- **`TargetType` wird NICHT um `SCENE` erweitert.** `ignoreUnknownKeys` deckt unbekannte
  *Schluessel* ab, nicht unbekannte *Enum-Werte*: ein APK-Downgrade waere ein harter
  Dekodierfehler, und `updateScheduleRules` faengt bewusst nicht ab. Diskriminator ist
  `HueLightAction.isScene` ueber das nullbare `sceneId`.
- **Das `on = true` einer Szenen-Aktion ist eine gespeicherte ZUSAGE, kein gesendeter Wert.**
  `autoOffTargetsOf()` filtert auf `on == true`; ohne das verloere jede Szenenregel ihr Auto-Aus.
  Gesendet wird ausschliesslich `{"scene": ...}` — nichts faehrt daneben mit.
- **`validateLightAction()` MUSS `sceneId` in „mindestens eine Eigenschaft" mitzaehlen**, sonst
  scheitert jede Szenenregel mit „At least one light property must be specified" — zur Weckzeit.
- **`executeActionsWithAutoRevert()` filtert `on == true || sceneId != null`.** Eine Szene traegt
  `on == null`, schaltet aber Licht an: ohne das zweite Glied liesse die VORSCHAU den Raum
  dauerhaft leuchten — derselbe Bug wie in `RulePreviewCleanupTest`, in neuer Gestalt.
- **Eine Regel darf MEHRERE Szenen schalten - je Raum eine** (seit v1.36.0). Die Kette darunter
  konnte das von Anfang an: `convertRuleToLightActions` laeuft ueber alle Aktionen,
  `autoOffTargetsOf()` flatMapt und dedupliziert, der Ziel-Abgleich behandelt jede Aktion einzeln.
  Begrenzt hat allein die Oberflaeche (`HueRuleFormState.szene` in der Einzahl). Am Geraet belegt:
  zwei PUTs, `2/2 successful`, Wohnzimmer `bri=254 ct=230` und Schlafzimmer `bri=26 ct=500` -
  jede Szene mit ihren eigenen Werten -, und die Vorschau raeumte `2/2 targets switched off`.
  **Zwei Szenen auf DEMSELBEN Raum bleiben ausgeschlossen**: das waeren zwei PUTs auf denselben
  Endpunkt, der zweite gewaenne. Eine neue Wahl im selben Raum ersetzt deshalb die alte.
- **Der Bridge-Wechsel und das Snooze-Stapeln sind am GERAET belegt** (26.08.2026, echte Bridge,
  Regel mit ZWEI Szenen):
  - *Bridge-Wechsel*: exportiert, die Datei ausserhalb der App verbogen (eine gueltige Szene mit
    fremden Ids, eine erfundene), importiert. Log: `1 Ziel(e) ueber den Namen neu zugeordnet,
    1 nicht zuordenbar (FD/Szene «Gibtsnicht» in Wohnzimmer: NOT_FOUND)`. Die gueltige war
    sofort wieder da, die erfundene stand NAMENTLICH MIT RAUM im Fertig-Dialog, in der
    Regel-Liste und im Editor - und nichts wurde geloescht.
  - *Snooze*: Wecker feuert → zwei Szenen, zwei Timer (`CFAlarm Auto-Off G82`, `G1`). Schlummern,
    zweites Feuern → `Alt-Zeitplan ... entfernt` fuer beide, dann zwei neue. Auf der Bridge liegen
    danach **2** statt 4, und die beiden fremden Dimmer-Schalter-Zeitplaene sind unangetastet.
- **Ueberlappende Bereiche werden BENANNT, nicht verboten.** Zonen ueberschneiden sich auf der
  Bridge des Nutzers real (Lampe 4 liegt in „Wohnzimmer", „Deckenlampe" UND „Zuhause"); fuer eine
  geteilte Lampe gewinnt die zuletzt gesendete Szene. Das ist keine Fehlbedienung, aber es
  ueberrascht - deshalb ein Hinweis in der Auswahl.
- **Ein fuehrendes Leerzeichen in einer String-Ressource ueberlebt nicht.** Android trimmt
  Rand-Leerzeichen beim Einlesen; am Geraet stand deshalb „Wohnzimmer· Auto-Aus". Abstaende
  gehoeren in den Code (oder die Zeile steht fuer sich).
- **Der Ziel-Abgleich einer Szene ist ZWEISTUFIG, und die Reihenfolge ist nicht verhandelbar:**
  erst die Gruppe ueber `targetName`, dann die Szene AUSSCHLIESSLICH innerhalb der aufgeloesten
  Gruppe. Scheitert die Gruppe, bleibt `sceneId` unangetastet — eine Szene in den falschen Raum zu
  schieben ist schlimmer als nichts zu tun. Eine bekannte `sceneId` schliesst nur kurz, wenn ihre
  `group` zur aufgeloesten Gruppe passt.
- **Der Anker ist das PAAR (Szenenname, Gruppenname).** An der Bridge des Nutzers gemessen gibt es
  „Nachtlicht" NEUN Mal und „Energie tanken" ZEHN Mal — je einmal pro Raum; innerhalb einer Gruppe
  kollidierte kein einziger Name. Der Szenenname allein waere in der Praxis immer mehrdeutig.
- **Das Auto-Aus einer Szene trifft den GANZEN Raum**, weil es zu einer Szene keinen Gegenbefehl
  gibt. `BridgeTimer` bleibt unveraendert (`/groups/<id>/action` + `{"on": false}`). Die
  `AutoOffCard` sagt das ausdruecklich — ein `/scenes/...`-Pfad waere erfunden.
- **Nur GroupScenes werden angeboten**, und das SICHTBAR: LightScenes haben weder Gruppen-Anker
  noch Auto-Aus-Ziel. Gruppe 0 wuerde zwar funktionieren (gemessen), aendert daran nichts.
- **`getScenes()` wirft bei Parserfehler, statt auf `emptyMap()` zu degradieren** — anders als
  `getLights`/`getGroups`. „Keine Szenen" und „Szenen nicht abrufbar" sind zwei Aussagen, und die
  Oberflaeche hat dafuer zwei getrennte Texte. Nicht „angleichen".
- **Ein VON HAND angelegter Wecker heisst "Fruehschicht (Manuell)" - wer damit zuordnet, findet
  nichts.** Das Anhaengsel steht bewusst im `AlarmInfo.shiftName` (die Weckerliste soll es
  zeigen), aber `ShiftConfig.findDefinitionFor()` kennt es nicht. Bis v1.35.1 fuehrte deshalb ein
  manueller Wecker **nie** seine Hue-Regeln aus: er klingelte normal, das Licht blieb aus, und im
  Log stand nur `No shift definition found ... (skipping Hue rules)` - das liest sich wie "keine
  Regel konfiguriert". Am Emulator gegen die echte Bridge gemessen (27.08.2026); kein Unit-Test
  und kein Blick in die Oberflaeche zeigte es, sondern erst ein echter Weckvorgang. Wer ueber den
  Schichtnamen ZUORDNET, nimmt `reinerSchichtname()`; wer ihn ANZEIGT, nimmt `shiftName`.
- **Ein Aktualisieren-Knopf, der stumm scheitert, ist von einem defekten nicht zu
  unterscheiden.** `HueViewModel.refreshLightTargets(userInitiated = true)` meldet den Fehlschlag;
  die automatischen Laeufe (Start, nach der Kopplung) bleiben bewusst still, dort erklaert die
  Verbindungs-Karte den Zustand ohnehin. Die LISTE bleibt in beiden Faellen unangetastet - "Bridge
  nicht erreichbar" ist keine Aussage darueber, welche Lampen es gibt. Folge davon: bei einer
  komplett unerreichbaren Bridge greift der `scenesFailed`-Zweig der Szenen-Karte NICHT (dann
  scheitert die Gesamtabfrage und die alte Liste bleibt stehen) - er ist fuer den Teilausfall da,
  in dem nur `/scenes` scheitert.
- **Die drei Regel-Modi (Szene, Manuell, Sonnenaufgang) schliessen sich aus, und zwar
  STRUKTURELL**: `HueRuleFormState.toRule()` liest nur die Felder des aktiven Modus. Der Modus
  wird an genau EINER Stelle hergeleitet (`HueScheduleRule.modus`) — Editor, Regel-Liste und Tab
  lesen dort, drei eigene Herleitungen waeren drei Wahrheiten.
