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
