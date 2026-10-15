# „Tag freigeben" — Hergang

> Hergang zu den Kurzregeln in `CLAUDE.md` und in der `SKILL.md` daneben: welcher Bug die
> Regel erzwungen hat, welche Messung sie belegt, welche Alternative verworfen wurde.

## Inhalt

- Der Vorfall: „Nicht stören" um 14:48 Uhr an einem freigegebenen Tag
- Warum das kein Fehler war — und trotzdem behoben gehört
- Die Freigabe bindet an das DATUM, nicht an Schicht oder Kennung
- Gate UND Backstop, wie beim Überspringen — und warum beide
- MANUELLE Wecker nehmen nicht teil (in beide Richtungen)
- Die Direct-Boot-Grenze, bewusst akzeptiert
- Hue braucht keinen eigenen Zweig
- Was am Emulator gemessen wurde
- Mehrere Tage in einem Schritt (#142)
- Krank aus dem Kalender: die zweite Quelle (#142)

---

## Der Vorfall: „Nicht stören" um 14:48 Uhr an einem freigegebenen Tag

Am **24.08.2026** meldete der Nutzer, dass auf dem Fairphone 6 „Nicht stören" lief, obwohl sein
Chef ihm freigegeben hatte. Er hatte den Wecker per **„Überspringen"** ausgeschaltet, und der
blieb auch korrekt stumm — aber am Gerät gemessen: `zen_mode=1`, Regel `CFAlarm Ruhezeit`
`STATE_TRUE`, **aktiviert um 14:48 Uhr**. Das ist auf die Minute der Beginn der Schicht `S2`
(14:48–22:00) aus dem `ShiftSpanStore`. Beide DND-Trigger waren an, keine Schicht ausgeschlossen.

## Warum das kein Fehler war — und trotzdem behoben gehört

Eine `ShiftSpan` kennt seit v1.25.2 bewusst **kein** „übersprungen": *ein ausgelassener Weckruf
ändert nichts daran, dass der Dienst stattfindet* — gedacht für den Morgen, an dem man ohne
Wecker wach ist und trotzdem Ruhe und Dimmen will. Die Entscheidung ist richtig und bleibt.

Der Fehler lag eine Ebene höher: **für den umgekehrten Fall — der Dienst findet NICHT statt —
gab es keine Geste.** Der Nutzer griff zur einzigen vorhandenen, und die tat genau das, was sie
verspricht. Die Lehre ist nicht „die Spanne war falsch modelliert", sondern: *wenn zwei Lagen
verschiedene Antworten brauchen und es nur eine Geste gibt, wählen Nutzer die falsche — und die
Oberfläche sagte nirgends, dass „Überspringen" ausschließlich den Wecker betrifft.*

Deshalb kam mit der Funktion auch die **Erklärung** in die Wecker-Karte (ausklappbar, Muster
`SchichterkennungsHinweis()`). Ihr Text war im ersten Wurf selbst zu absolut („„Nicht stören"
bleibt aus") — an einem freien Tag greift nachts weiterhin die Dimm-Regel für freie Tage
(bis v1.33.0 war es der eingebaute Nacht-Standard), und DND-Modus 1 folgt dem Dimmer. Der Text sagt jetzt, was wirklich gilt: *der Tag zählt ab dann als freier Tag*.

## Die Freigabe bindet an das DATUM, nicht an Schicht oder Kennung

`FreieTageStore` speichert ISO-Datumsstrings im bestehenden `@MainDataStore`. Der Grund ist der
abonnierte Dienstplan-Feed: Google vergibt alle paar Tage neue Event-IDs für dieselben Termine
(am Gerät belegt: 11 gelöscht, 11 angelegt, Schnittmenge null). Eine an Kennung oder Schichtnamen
hängende Freigabe wäre nach so einer Rotation **lautlos wirkungslos** — und ein lautlos
wirkungsloser freier Tag ist ein Wecker am freien Morgen.

Ein Tag gilt für den **ganzen Kalendertag**: hat er zwei Schichten, fallen beide weg. Der
Tagesanker ist überall die **Weckzeit** — vier Stellen, ein Anker (`FreieTageStore.tagVon`,
`TagFreigabeUseCase.gehoertZuTag`, `AlarmUseCase.istTagFreigegeben`, `berechneWeckerAnzeige`).
Wären es verschiedene, gäbe es bei einer abends beginnenden Schicht einen halb freigegebenen Tag:
Wecker weg, „Nicht stören" trotzdem an.

**Degradationsrichtung: Lesefehler → leere Menge**, also „kein Tag ist freigegeben". Dieselbe
Abwägung wie bei `MasterPausePrefs`: ein fälschlich gestellter Wecker klingelt hörbar, ein
fälschlich unterdrückter ist still.

**Aufräumgrenze `heute − 1`, nicht `heute`** (`RUECKSCHAU_TAGE`, spiegelt `LOOKBACK_DAYS` und
`ShiftSpanStore.RETENTION_MS`). Verschwände die Freigabe um 00:00, kippte eine über Mitternacht
laufende, ausgesetzte Nacht zurück in „dimmen + Nicht stören an" — mitten im Schlaf. Gefiltert
wird **beim Lesen** und aufgeräumt in der 6h-Wartung vor dem Master-Pause-Gate; „nur beim
Schreiben mitprunen" wie beim `ShiftSpanStore` reicht nicht, denn geschrieben wird nur, wenn der
Nutzer einen Tag anfasst.

## Gate UND Backstop, wie beim Überspringen — und warum beide

- **Gate in `syncAlarms()`**, neben dem Skip-Zweig: der Wecker entsteht gar nicht erst. Ein
  BESTEHENDER Wecker des Tages wird dort **gecancelt und gelöscht** (in dieser Reihenfolge) —
  anders als beim Skip, wo `skipNextAlarm()` das schon erledigt hat. **Ohne Meldung „Schicht
  entfernt"**: der Dienstplan hat sich nicht geändert, der Nutzer hat selbst freigegeben. Dieselbe
  Fehlklasse wie die verstrichene Weckzeit im Löschzweig.
- **Backstop in `scheduleSystemAlarm()`**, außerhalb von `SafeExecutor`: das ist der einzige Weg
  in den `AlarmManager` und deckt das ungefilterte Re-Arming des `BootReceiver` (`:877`, `:942`)
  sowie den `TimezoneChangeReceiver` ab. Er meldet einen **Fehler** statt still zurückzukehren —
  „abgewiesen" darf für den Aufrufer nicht wie „armiert" aussehen (eigener Typ
  `FreigegebenerTagNichtArmiertException`, damit die Oberfläche ihn vom Überspringen unterscheidet).

Gate allein: ein Neustart holt den Wecker zurück. Backstop allein: der Eintrag bleibt im Bestand
liegen — und Direct-Boot-Spiegel wie Hue-Tagesplanung lesen den ungefiltert.

## MANUELLE Wecker nehmen nicht teil (in beide Richtungen)

`eventId.isEmpty()` schließt sie aus, in `gehoertZuTag` **und** in `istTagFreigegeben`. Beide
Richtungen waren im ersten Wurf falsch, und der zweite Fehler ist der lehrreiche:

1. **Löschen:** Die Freigabe hätte einen manuellen Wecker desselben Tages mitgelöscht — und der
   kommt **nie** zurück. `zuruecknehmen()` baut über den Kalender wieder auf, in dem er nicht
   steht; `syncAlarms()` schont ihn per `keepManualAlarms` nur, es legt ihn nicht neu an. Genau
   deshalb sichert `AlarmSkipUseCase` für ihn einen `ManualAlarmSnapshot`.
2. **Anlegen:** Derselbe Anker hätte im Backstop dazu geführt, dass sich an einem freigegebenen
   Tag **überhaupt kein** eigener Wecker mehr stellen lässt (Zahnarzt, Zug). Ein freier Tag, an
   dem man keinen Wecker stellen kann, ist absurd.

**Dieser zweite Befund wurde vom Refutation-Voting mit 2:3 verworfen** und war trotzdem echt — er
ist der Zwilling des ersten in der Schwesterfunktion. Belegt zum wiederholten Mal die
`CLAUDE.md`-Regel: *ein „widerlegt" ist ein Hinweis, kein Freispruch; spiegelt ein Befund ein
bereits bestätigtes Muster, selbst am Code nachsehen.*

## Die Direct-Boot-Grenze, bewusst akzeptiert

`AlarmManagerService.rescheduleFromDirectBoot()` armiert vor der ersten Entsperrung aus dem
Direct-Boot-Spiegel und kann den Backstop nicht befragen — der Speicher liegt im CE-Storage.
In der Praxis greift das kaum, weil der Wecker des freigegebenen Tages gelöscht ist und damit
beim nächsten erfolgreichen Load aus dem Spiegel verschwindet. **Dieselbe Grenze hat das
Überspringen heute schon.** Nicht weggeschwiegen, sondern hier notiert.

## Hue braucht keinen eigenen Zweig

`HueSmartScheduler` zieht seine Zeiten ausschließlich aus `alarmUseCase.getAllAlarms()` (`:493`,
`:591`), und die Regelausführung hängt am `AlarmReceiver`. Entsteht am freien Tag kein Wecker,
fallen Sonnenaufgang und Lichtregeln automatisch weg. Wer hier „sicherheitshalber" einen eigenen
Hue-Zweig ergänzt, baut eine zweite Wahrheit.

**Eine Lücke gab es trotzdem (Krankheitstag-Prüfung, 14.10.2026):** einen schon GEPLANTEN
Vorab-Sonnenaufgang (`SunriseStartWorker`) räumte nur der Alarm-Beobachter im `HueSmartScheduler`
ab — 1,5 s entprellt und nur im lebenden Prozess. Wird die App direkt nach „Dienst fällt aus“
weggewischt, blieb der Auftrag stehen, und das Licht fuhr am freien Morgen ohne Auto-Aus hoch.
Seitdem trägt der Auftrag seine Weckzeit und fragt beim Feuern
`HueSmartScheduler.sonnenaufgangNochGedeckt` — dieselben Bedingungen wie beim Planen, kein
Freigabe-Zweig. Bekannt und offen: eine Rampe, die bereits läuft, wird nicht zurückgedreht.

## Ein schon geschlummerter Wecker (1.50.3)

**Der Fall:** Wecker klingelt 05:30, der Nutzer schlummert, merkt, dass er krank ist, und wählt
„Dienst fällt aus“ für heute. Bis 1.50.2 klingelte es trotzdem nach Ablauf des Schlummerns. Zwei
Gründe, beide strukturell: der gefeuerte Wecker steht nicht mehr im Bestand (er wirft
Verstrichenes beim Laden weg, `AlarmRepository:322`), also findet ihn `betroffene` nicht; und der
Snooze liegt in einem EIGENEN PendingIntent-Slot, den `cancelSystemAlarm` nie trifft.

**Die Lösung hat zwei Schichten, wie jede Freigabe:**
- **Gate:** `freigeben()` bricht jeden schwebenden Snooze ab, der zum Tag gehört
  (`AlarmManagerService.cancelSnoozesFuerFreigabe`, über `cancelSnooze` samt Merker - ein
  Neustart holt ihn nicht zurück). **Erst NACH der Löschschleife** (Review 14.10.2026): scheitert
  dort ein Löschen, wird die Freigabe zurückgenommen, und ein vorher abgebrochener Snooze wäre an
  einem nicht freigegebenen Tag samt Merker verloren.
- **Backstop:** der `AlarmReceiver` prüft beim Feuern eines Snooze (`snoozeAlarmAction`), ob sein
  Tag freigegeben ist, und klingelt dann nicht. Nötig, weil die Markierung zuerst steht: stirbt
  der Prozess zwischen Markierung und Abbruch, räumt bei Weckern das Gate im nächsten Sync auf,
  bei einem Snooze niemand. Nur bei entsperrtem Speicher (`FreieTageStore` ist CE); jeder Fehler =
  klingeln.

**Der Anker ist die WECKZEIT, gespeichert im Snooze-Merker (fünftes Feld).** Die Schlummer-Zeit
wäre falsch: 23:50 geweckt, 00:05 erneut geschlummert, und der Snooze gehörte zum Folgetag - eine
Freigabe des Folgetags bräche dann einen Wecker eines Tages ab, den niemand freigegeben hat. Woher
die Weckzeit kommt (`weckzeitFuerEintrag`): beim erneuten Schlummern und beim Wiederherstellen nach
einem Neustart vom Vorgänger-Eintrag derselben ID (VOR dem Kürzen gelesen, sonst ist er schon
weg); beim ersten Schlummern die jüngste geklingelte Weckzeit aus `GeklingelteWeckzeiten`.
Damit der Vorgänger-Eintrag dafür noch da ist, behält jedes ZURÜCKSCHREIBEN des Merkers gerade
gefeuerte Einträge zwei Stunden lang (`kuerzeZumSchreiben`); neu armiert wird weiterhin nur
Zukünftiges (`pruneSnoozeEntries`). Vorher konnte ein fremdes Schreiben (Abbruch eines anderen
Snooze, Boot-Restore) die Spur wegräumen, und der Anker fiel auf die Schlummer-Zeit - um
Mitternacht der falsche Tag. **Bewusst kein „jetzt“ als Ersatz** - fehlt beides, bleibt der Anker leer, und der Snooze nimmt an
keiner Freigabe teil (er klingelt).

**Verworfen:** die Zuordnung über `betroffene` (Abgleich der Snooze-ID mit den Weckern des Tages).
Sie fängt nur den Fall, in dem der Prozess seit dem Klingeln lebt - danach ist der Wecker aus dem
Bestand. Ebenso verworfen: eine neue Intent-Extra quer durch Receiver, Dienst und Vollbild - der
Merker kann sich die Weckzeit selbst merken, ohne drei geschützte Komponenten anzufassen.

**Manuelle Wecker** erkennt der Merker am Anhängsel `MANUELLER_ALARM_SUFFIX` (er kennt keine
Kalender-Kennung). Die Richtung ist sicher: ein Schichtwecker, der fälschlich als manuell gilt,
klingelt.

**Am Emulator gemessen (14.10.2026, Uhr vorgestellt auf Sa 17.10. 05:29):** Wecker 05:30 klingelt,
„30 Min später“ - Merker `…|17.10.2026 06:00|1792207800000` (Weckzeit 05:30). „Dienst fällt aus“
für Sa 17.10. - Log `FREIGABE: schwebender Snooze … gehoert zum freigegebenen 2026-10-17`, Merker
leer, `dumpsys alarm` führt den Slot nur noch unter `Reason=alarm_cancelled`. Uhr auf 06:02 - kein
Klingeln. Den Backstop allein (Prozesstod zwischen Markierung und Abbruch) kann die Shell nicht
herbeiführen; er ist nur per Unit-Test (`gehoertSnoozeZuFreiemTag`) belegt.

## Was am Emulator gemessen wurde (24.08.2026)

Ausgangslage nachgestellt (S2 12:48–20:00 GMT laufend, Trigger „Während der Dienstzeit" an):
`zen_mode=1`, Regel `STATE_TRUE` — **der gemeldete Vorfall, reproduziert**.

| Schritt | Ergebnis |
|---|---|
| Tag freigeben (laufender Dienst) | `zen_mode=0`, `STATE_FALSE`, **sofort**; nächster DND-Wechsel korrekt auf Mi 26.08. 12:48 |
| Aufheben | `zen_mode=1` wieder |
| Tag MIT Wecker freigeben | „1 Wecker entfernt"; 26.08. 14:30 verschwindet aus `dumpsys alarm`, alle anderen bleiben |
| Aufheben | Wecker steht wieder in `dumpsys alarm` und in der Karte |

**Nebenbefund aus dem Test:** Ein Tag in der VERGANGENHEIT ließ sich freigeben und blieb stehen.
Zwei Antworten: der Store filtert schon beim Lesen, und die Datumsauswahl bietet vergangene Tage
gar nicht mehr an (seit #142 `DienstFaelltAusDialog`, früheste Auswahl = heute). Ein Bedienelement, das sichtbar nichts
tut, ist schlimmer als eines, das nicht angeboten wird.

## Mehrere Tage in einem Schritt (#142)

**Der Anlass:** wer eine Woche krank ist, musste „Dienst fällt aus“ fünfmal einzeln wählen. Der
Eigentümer wollte ein Enddatum (kein „bis auf Weiteres“ - ein vergessenes offenes Ende ist ein
verschlafener erster Arbeitstag) und den Knopf im bestehenden Ablauf, keine zweite Geste.

- **Ein Zeitraum ist eine Folge von Einzeltagen**, kein eigener Speichertyp:
  `TagFreigabeUseCase.freigebenZeitraum` geht je Tag dieselbe Schrittfolge wie bisher
  (Persistenz-Sperre, Markierung zuerst, cancel→delete, Snoozes, Skip). Damit gilt jede Regel oben
  unverändert für jeden Tag. Höchstens 31 Tage: der Picker kann die Grenze nicht erzwingen
  (`SelectableDates` sieht nur einen Tag), also prüft der Dialog nach der Auswahl.
- **Teilfehler: die vorherigen Tage BLEIBEN frei** (Eigentümer-Entscheidung 15.10.2026). Der
  gescheiterte Tag wird wie bisher zurückgerollt und seine Wecker neu gestellt, spätere Tage werden
  nicht angefasst, und der Hinweis nennt, bis wann freigegeben ist. „Alles oder nichts“ wurde
  verworfen: das Zurücknehmen kann selbst scheitern, der Zustand würde unklarer statt klarer.
  **Ist der gescheiterte Tag zugleich Krank laut Kalender, weist der Backstop das Wiederstellen
  ab** - dann darf der Text nicht „diese Wecker klingeln“ sagen (Review S6, eigener Text).
- **Die Neben-Ketten laufen EINMAL am Ende**, auch im Fehlerfall, in `NonCancellable` - wie beim
  Einzeltag, damit „Nicht stören“ nicht weiterläuft, wenn der Nutzer die App sofort schließt.
- **Der Picker liefert UTC-Mitternacht.** Umgerechnet wird über `ZoneOffset.UTC`, nie über
  `ZoneId.systemDefault()` - sonst wählt man in Berlin den Vortag. Dieselbe Falle saß im alten
  `DatePickerDialog` und ist mit behoben.
- **Der Dialog ist ein eigener Vollbild-Dialog**, nicht der M3-`DatePickerDialog`: der erzwingt
  360 dp Breite und schnitt bei 320 dp ab (Review). Zurück schließt ihn (`onDismissRequest`).
- **Die Übersicht zeigt einen laufenden Ausfall** (Block freier Tage, der heute oder morgen
  enthält, `laufenderAusfall`) ganz OBEN - vor den #141-Karten, deren „Karte darunter“-Texte sonst
  falsch würden. Bei Master-Pause entfällt sie (die Pause hat ihre eigene Anzeige, und eigene
  Wecker bleiben dort NICHT). „Zurücknehmen“ dort nimmt die eigenen Freigaben DES Blocks zurück.
- **„Nächste Schicht“ überspringt ausgefallene Tage** (`naechsteStattfindendeSchicht`, Anker
  Weckzeit, offline ebenso). Vorher stand unter „Dienst fällt aus bis So 18.10.“ die Schicht vom
  Sa 17.10. - am Emulator aufgefallen, galt für Einzeltage schon seit 1.50.0.

## Krank aus dem Kalender: die zweite Quelle (#142)

**Der Anlass:** TimeOffice trägt eine Krankmeldung uneinheitlich ein - mal ERSETZT sie die Schicht
(dann räumt der Abgleich den Wecker schon immer weg), mal steht „K“ NEBEN der Schicht, und der
Wecker klingelte weiter. Gelöst über einen Schalter am Schichttyp (`ShiftDefinition.isSickDay`,
„Krankmeldung“); jeder Nutzer verbindet ihn über sein eigenes Muster. Ab Werk aus.

- **Eine Wahrheit, zwei Schlüssel.** `syncAlarms()` schreibt die Krank-Tage in
  `krank_tage_kalender`, `freieTage` liefert die Vereinigung mit den Hand-Freigaben. Verworfen:
  (a) Krank als zeitliche Überschneidung „Spanne ∩ Termin“ - jeder Leser bräuchte eigene Logik,
  der Backstop Kalenderdaten; (b) Krank in den Hand-Schlüssel schreiben - ein verschwundener Termin
  wäre vom Nutzerwillen nicht mehr zu trennen; (c) ein Krank-Flag an der `ShiftSpan` - die
  Hand-Tage blieben ein zweiter Leseweg, und der Backstop müsste zu jedem Wecker die Spanne suchen.
  `zuruecknehmen` fasst nur den Hand-Schlüssel an; ein Tag in beiden bleibt frei, solange eine
  Quelle ihn trägt.
- **Die Engine trennt** (`ShiftRecognitionEngine.erkenne` → `Erkennung(schichten, krankTermine)`,
  ein Cache-Objekt, ein Mutex, eine Epoche). Krank hat Vorrang vor jeder Schicht im selben Termin.
  `getAllMatchingShifts` liefert keine Krank-Treffer mehr - deshalb ändert sich kein Konsument
  (Nächste Schicht, Boot-Weckpunkte, Diagnose). Ein Krank-Typ erzeugt keinen Wecker und keine
  Spanne; Listen zur Typauswahl (manueller Wecker, Dimmer, DND, Hue) blenden ihn aus.
- **Tage auffächern:** ein mehrtägiger Termin ist EIN Treffer. Ganztägig endet er um 23:59 des
  letzten Tages; zeitgebunden mit Ende exakt 00:00 zählt der Folgetag NICHT (Review S3). Ein
  zeitgebundener Termin über Mitternacht gibt beide Tage frei (bewusst, Datumsgranularität).
- **Weckzeit-Anker (Review M1):** eine Schicht kurz nach Mitternacht hat ihre Weckzeit am Vortag -
  ohne Anker klingelte sie in der kranken Nacht. Frei wird deshalb auch der Weckzeit-Tag jeder
  Schicht, die an einem Krank-Tag BEGINNT, auch eines schon gestellten Weckers (ersetzt TimeOffice
  die Schicht durch „K“, kennt nur noch der Wecker seinen Beginn). **Der Anker wird mit seiner
  Quelle gespeichert** (`krank_tage_kalender_anker`, „anker>quelle“): im ersten Wurf galt er nur
  einen Abgleich lang - der Lauf löscht genau den Wecker, aus dem er stammt, und der nächste Lauf
  hätte den Vortag wieder herausgenommen (Prüfrunde 2: Wecker im Wechsel gelöscht und neu
  gestellt). Er gilt, solange die Quelle Krank-Tag ist, nicht länger. **Bekannter Rest:**
  Datumsgranularität wie bei Hand-Freigaben - eine andere Schicht mit Weckzeit an diesem Vortag
  fällt mit aus, und die Tagesliste zeigt den Vortag als „Krank (Kalender)“.
- **Mischsemantik beim Schreiben** (`mischeKalenderKrank`, ein `edit{}`): Tage `< heute` bleiben
  (Rückschau, Nacht über Mitternacht), Tage `>= heute` bis vor den Horizont-Tag werden ERSETZT
  (Termin weg → Tag weg, auch HEUTE; Review M2: ganztägig ist bis 23:59 in jeder Lesung sichtbar),
  Tage ab dem Horizont-Tag (der Tag, der `horizontEnde` enthält) bleiben - nicht gelesen ist nicht
  gelöscht. **Ausnahme:** ein heute schon BEENDETER zeitgebundener Termin fehlt in jeder späteren
  Lesung (der Abruf beginnt bei jetzt) - sein Ende wird gemerkt
  (`krank_tage_kalender_zeitgebunden_ende`), und heute bleibt frei. Verschwindet er VOR seinem Ende,
  gilt er als gelöscht. Eine leere Eventliste (Abwahl) räumt alles ab heute; Master-Pause und
  Automatik aus lassen den Schlüssel unangetastet (Richtung „hell“).
- **Neben-Ketten:** ändert sich die Krank-Menge, ruft `syncAlarms` `armiere("KRANK")` - NACH dem
  Mutex (die Ketten lesen Alarme), im `finally` unabbrechbar. Der Vordergrund-Sync armiert sonst
  nur bei geänderten Spannen, der Vorab-Refresh-Worker gar nicht (Review M3).
  `dagger.Lazy<ZeitkettenArmierer>` bricht den Zyklus über `DimScheduleUseCase` → `IAlarmUseCase`
  und fasst beim Bauen des Graphen nichts an.
- **Meldung (`KrankmeldungNotifier`, ID 2204):** Auslöser sind NEU hinzugekommene Krank-Tage mit
  Wecker oder Schicht; „Schicht entfernt“ wird für Wecker an Krank-Tagen unterdrückt (TimeOffice
  ersetzt die Schicht durch K - das ist eine Krankmeldung). Der Titel nennt nur echte
  Kalender-Krank-Tage, nicht den Anker-Vortag; schon von Hand freigegebene Tage werden nicht
  gemeldet. **Vorgemerkt im Lock, quittiert erst nach ZUGESTELLTER Meldung**
  (`krank_tage_zu_melden`) - der Diff kommt nur einmal vorbei. Der allererste Abgleich meldet
  nicht (leerer Bestand UND noch nie ein vollständiger Sync).
- **Gerätegrenze:** alle Krank-Schlüssel sind Laufzeitzustand (`ConfigBackupFormat`);
  `krank_tage_zu_melden` ist zusätzlich gerätelokal (`DeviceLocalFlagsGuard`) - mitgereist meldete
  das neue Gerät Tage, deren Wecker es nie hatte. Ein älteres App-Release liest einen Krank-Typ aus
  einem Export als normale Schicht (kein Formatbump, bewusst nicht gelöst).

**Prüfrunden:** Spec-Review (Fable) 3 Muss- und 6 Soll-Punkte, alle übernommen. Runde 1 nach der
Umsetzung 18 Rohbefunde / 15 bestätigt, Runde 2 auf unverändertem Code 10 / 9 - eine Runde ohne
neuen Befund steht für #142 also noch aus.

**Am Emulator gemessen (15.10.2026):** Zeitraum Sa 17.–So 18.10. → beide Wecker aus
`dumpsys alarm`, Mo 19.10. und 02.11. bleiben; Übersicht-Karte „Dienst fällt ab morgen aus, bis So
18.10.“, „Zurücknehmen“ dort → beide Wecker zurück. Krank-Typ „CFTEST“, ganztägiger Termin Sa 17.10.
in einem ausgewählten Kalender → `Krank laut Kalender: +[2026-10-17]`, Wecker 05:30 weg,
Benachrichtigung „Krankmeldung erkannt: Sa 17.10. / Der Wecker um 05:30 entfällt.“, Tagesliste
„Krank (Kalender)“ ohne Knopf; Termin gelöscht → `-[2026-10-17]`, Wecker wieder gestellt.
