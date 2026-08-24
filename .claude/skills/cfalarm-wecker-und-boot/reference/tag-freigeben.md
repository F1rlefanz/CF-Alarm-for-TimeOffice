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
bleibt aus") — an einem freien Tag greift nachts weiterhin der Nacht-Standard, und DND-Modus 1
folgt dem Dimmer. Der Text sagt jetzt, was wirklich gilt: *der Tag zählt ab dann als freier Tag*.

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
gar nicht mehr an (`DatePickerDialog(fruehesterTag = …)`). Ein Bedienelement, das sichtbar nichts
tut, ist schlimmer als eines, das nicht angeboten wird.
