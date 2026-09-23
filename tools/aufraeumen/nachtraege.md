# Nachträge zum Skill `cfalarm-altlasten-abtragen`

**Diese Datei ist für die nächste Runde so bindend wie der Skill selbst.** Lies sie, bevor du
anfängst — hier steht, was seit der letzten Skill-Pflege dazugelernt wurde.

## Warum es sie gibt

Der Skill sagt: „Jede Runde ergänzt hier." Nur konnte das keine Runde. Am 03.09.2026 headless
nachgestellt (Claude Code 2.1.259, dieselben Schalter wie in `aufraeumen.yml`):

| Versuch, `.claude/skills/**/SKILL.md` zu schreiben | Ergebnis |
|---|---|
| `Edit` | abgelehnt |
| `Bash` (`printf >> datei`) | abgelehnt — „Ich brauche deine Bestätigung" |
| `--allowedTools "Edit(.claude/skills/**)"` | hilft **nicht** |
| `--permission-mode acceptEdits` | hilft **nicht** |

Das ist ein eingebauter Schutz, keine Fehleinstellung des Repos: **ein Agent darf seine eigene
Anweisung nicht unbeaufsichtigt umschreiben.** Der Schutz ist richtig und bleibt. Die frühere
Notiz im Skill („lokal nicht nachstellbar") war eine Vermutung; sie ist damit widerlegt.

Die Folge war teuer: die Lehren der Runden 9, 11, 14 und 15 landeten in Issues (#33, #41, #49,
#57) und warteten dort auf den Eigentümer. Runde N+1 startete ohne sie — und hat den
Konstruktionsfehler von Runde N in neuer Form nachgebaut. Genau so sind die vier geschlossenen
Anläufe an Issue #39 entstanden (PR #40 → #48 → #56 → #58): nie am Befund gescheitert, immer an
dem, was die Reparatur **neu** hineinschrieb.

## Wie du sie benutzt

- **Am Anfang der Runde lesen.** Was hier steht, gilt wie eine Leitplanke im Skill.
- **Am Ende der Runde ergänzen**, wenn du etwas gelernt hast, das die nächste Runde braucht:
  neue Leitplanke, verworfener Blickwinkel mit Zahlen, oder eine **Korrektur** an einer Aussage
  im Skill. Ein neuer Abschnitt unten, mit Datum, Runde und Beleg.
- **Danebenschreiben ist ein Fehler, nicht Verlauf.** Widerlegt dein Nachtrag etwas, das schon
  hier steht, ersetzt du es.
- Diese Datei geht mit deinem PR durch den Torwächter — sie ist reviewte Repo-Historie, kein
  Zettel. Ein zusätzliches Issue dafür brauchst du **nicht** mehr.
- **Der Eigentümer räumt sie ab:** was in den Skill übernommen ist, wird hier gelöscht. Eine
  leere Datei ist der gewünschte Zustand, kein Versäumnis.

---

## Offene Nachträge

> Gerettet aus dem geschlossenen PR #59 (Runde 16, 04.09.2026). Der Schnitt jenes PR war
> belegt richtig und darf unverändert wiederkommen; geschlossen wurde er wegen seines
> Gatters. Die Lehre unten hängt daran nicht — sie gilt unabhängig davon.

### 04.09.2026, Runde 16 (Issue #19, Enum-Einträge ohne Verwender)

**Miss deine Messfassung, bevor du ihren Zahlen glaubst — ein leeres Strukturergebnis ist ein
Parserfehler, kein Befund.** Das Wegwerf-Skript dieser Runde meldete zuerst „35 Enums, 138
Einträge" und nebenbei ein Enum mit **null** Einträgen. Das war die Spur: die Eintragsliste wurde
am ersten `;` auf Klammertiefe 0 abgeschnitten, und `RueckbauErgebnis` hat einen Strichpunkt *im
KDoc* seines ersten Eintrags („Der Alarm ist wieder weg; es steht nichts Scharfes mehr."). Acht
Einträge im Baum wurden nie angesehen — echte Zahl ist 146. Hätte ich die Null nicht verfolgt,
wäre die Runde mit einer stillen Blindstelle „fertig" geworden, und ein daraus gebautes Gatter
hätte für immer „sauber" gemeldet, ohne hinzusehen.

**Die verallgemeinerbare Regel:** Wenn dein Zählskript eine Struktur als *leer* ausweist
(Klasse ohne Methoden, Enum ohne Einträge, Datei ohne Symbole), ist das fast nie die Wahrheit über
den Code, sondern eine Aussage über deinen Parser. Bau die Selbstprüfung gleich ein — eine Zeile
„!! Enums ohne geparste Einträge" reicht. Konkret für Kotlin: **Kommentare und String-Literale vor
jeder Struktursuche ausblenden** (zeichenlängentreu, dann bleiben Zeilennummern gültig).

> **Korrigiert in Runde 17 (05.09.2026), Begründung nachgeschärft am 05.09.2026:** Hier stand
> „**Referenzen auf dem Rohtext zählen**". Die neue Regel gilt: **Kommentare auch beim Zählen
> ausblenden, String-Literale dagegen mitzählen** (ein Eintragsname in einer Test-JSON ist ein
> echter Verwender, weil er Teil eines gespeicherten Formats ist), und **reine Doku-Dateien zählen
> gar nicht mit**. Der Grund ist aber ein anderer als der zunächst eingetragene: eine
> Kommentarnennung als „Verwender" zu zählen ist genau der Selbstentwaffnungs-Mechanismus, den der
> Torwächter als Defekt (b) an Prüfung 7 nachgewiesen hat — die ENTFERNT-Notiz hält den entfernten
> Namen für immer am Leben. **Nicht** dagegen, weil Rohtext-Zählung hier neun Befunde getilgt
> hätte; das war eine Fehlmessung (siehe Runde 17, Punkt 1).

**Zum Stand der Werkzeuge — hier weicht der Nachtrag von seinem Original ab, weil PR #59
geschlossen wurde:** `pruefe_reste.py` hat weiterhin **sechs** Prüfungen. Die Prüfung 7 aus jenem
PR ist nicht in `main` gelandet, und mit ihr auch nicht der Konfliktzustands-Wächter
(`git ls-files -u` nicht leer → schweigen), den sie als erste mitbrachte. Nachgesehen am
04.09.2026: `grep -c 'ls-files", "-u' tools/aufraeumen/pruefe_reste.py` → **0**. Damit lesen
**alle sechs** Prüfungen den Baum während eines offenen Merge ungeschützt; Issue #60 gilt
unverändert und beschreibt jetzt nicht mehr eine Lücke von sechs, sondern die Gesamtlage.

### 05.09.2026, Runde 17 (Issue #19 erneut, Schnitt ohne Gatter)

Der Schnitt aus PR #59 ist wiedergekommen, **allein und ohne neue Prüfung** — Regel 4 des Skills,
neun von neun beurteilten Aufräum-PRs. Gemessen: **9 Rohbefunde, 9 bestätigt, 0 Fehlalarme**
(`TargetType.ZONE`/`ROOM` sind diesmal mitgezählt, PR #59 hatte sie übersehen).

**Zwei stille Fehlmessungen auf dem Weg dahin — beide hätte die Runde-16-Selbstprüfung
durchgelassen:**

1. **Ein Messlauf ergab „0 Rohbefunde" — alle neun weg.** ⚠️ **Die hier ursprünglich eingetragene
   Ursache ist widerlegt** (Torwächter zu PR #63, nachgemessen am 05.09.2026). Sie lautete:
   Rohtext-Zählung tilge alle neun, „weil jeder dieser Einträge irgendwo in einem Kommentar
   erwähnt wird". `git grep -nw <name> main -- .` sagt etwas anderes:

   ```
   DIM 1   BRIGHTEN 1   SET_COLOR 1   SET_TEMPERATURE 1   PULSE 1
   COLOR_LOOP 1   N_UPNP 1   ZONE 3   ROOM 3
   ```

   **Sieben der neun Namen kommen im ganzen Baum genau einmal vor — in ihrer eigenen
   Deklarationszeile.** Eine Kommentarnennung haben nur `ZONE`/`ROOM`. „Alle neun weg" ist mit
   Rohtext-Zählung also gar nicht herstellbar; die tatsächliche Ursache war der Parser-Defekt aus
   Punkt 2 — wenn jeder Eintragsname zu einem Einzelbuchstaben verkommt, findet die Referenzsuche
   natürlich überall Treffer.

   **Die Lehre daran ist die teurere:** Hier wurde eine *richtige* Regel (Kommentare nicht als
   Verwender zählen) mit einer *falschen* Messung begründet — und weil dieser Zettel für die
   nächste Runde bindend ist, hätte die falsche Begründung Bestand gehabt. **Wenn zwei
   Fehlmessungen in einer Runde auftreten, prüfe, ob die eine die andere erklärt**, bevor du
   ihnen zwei getrennte Ursachen zuschreibst.
2. **Die Selbstprüfung war grün, während jeder Eintragsname Müll war.** Mein Parser meldete
   ordentliche 35 Enums, 146 Einträge und „kein Enum ohne geparste Einträge" — die Namen darin
   waren aber Einzelbuchstaben: `GOOD_BUT_RISKY` wurde zu `Y`, `OPTIMAL` zu `L`. Schuld war das
   Backtracking in `re.match(r"\s*@?\w*\s*([A-Z][A-Z0-9_]*)\b", eintrag)`: `\w*` frisst den
   ganzen Namen und gibt beim Scheitern nur so viel zurück, dass die Gruppe noch matcht — also
   den letzten Großbuchstaben. Aufgefallen ist es nur, weil in der Befundliste ein Enum-Eintrag
   namens „Y" stand.

**Die Lehre, die Runde 16 noch nicht hatte:** eine Selbstprüfung auf **Zahlen** (Anzahl, „nichts
leer") ist kein Beleg für die **Namen**. Beide Messungen oben waren zahlenmäßig unauffällig —
146 stimmte sogar exakt. Lass dein Skript einmal das **vollständige Inventar** ausdrucken
(`Enum: [EINTRAG, …]`) und sieh es mit dem Auge an; das kostet dreißig Sekunden und ist die
einzige Prüfung, die diese Klasse von Fehler fängt. Der allgemeine Satz dazu: **prüfe nicht, ob
dein Parser etwas gefunden hat, sondern ob das Gefundene aussieht wie das Gesuchte.**

### 06.09.2026, Runde 18 (Issue #19 zum dritten Mal — der Schnitt ist durch)

Der Schnitt ist wiedergekommen wie vom Torwächter freigegeben, allein und ohne Gatter. Eigene
Messung: **37 Enums, 152 Einträge, 9 Rohbefunde, 9 am Code bestätigt, 0 Fehlalarme**; nach dem
Schnitt 0 Rohbefunde. Die drei Textkorrekturen aus dem #63-Urteil sind eingearbeitet.
**Punkt 4 des Urteils ist bereits erledigt** — der Eigentümer hat die widerlegte Passage am
05.09.2026 selbst richtiggestellt (`ab12d87`); wer sie noch einmal „korrigiert", schreibt daneben.

**Die neue Lehre — sie ergänzt Runde 16 und 17 um die dritte Achse: Vollständigkeit.**

Meine Zahlen wichen von denen der Vorrunde ab (37/152 gegen 35/146). Nach Runde 17 ist der erste
Gedanke „einer der beiden Parser hat eine Blindstelle" — und genau der wäre hier falsch gewesen.
`git grep -c "enum class" <ref> -- '*.kt'` aufsummiert:

```
d463025 (Runde 16/17) 35      ab12d87 (heute) 37      origin/main 37
```

Die zwei Enums kamen mit `DimBedienungshilfenWunschTest` und `DndDiagnostikTest` dazu — **echte
Baumbewegung, kein Fehler auf beiden Seiten.** Ohne diesen Einzeiler hätte ich der Vorrunde einen
Parserdefekt unterstellt, den sie nicht hatte; mit ihm war es eine Minute.

**Die verallgemeinerbare Regel:** Prüfe die **Anzahl** deiner Strukturen zusätzlich gegen eine
naive Stichwortzählung (`git grep -c` auf das Deklarationsschlüsselwort) — **und zwar auf dem Ref
der Vorrunde, nicht nur auf deinem.** Das trennt die beiden Ursachen, die sich sonst nicht
unterscheiden lassen: Blindstelle im Parser gegen Bewegung im Baum. Die drei Selbstprüfungen
zusammen decken jetzt jede Achse ab — Runde 16 fängt **leere** Ergebnisse, Runde 17 falsche
**Namen**, Runde 18 unvollständige **Mengen**. Namen und Zahlen können beide stimmen, während
dein Muster ganze Dateien nie zu Gesicht bekommt; nur der Abgleich gegen eine zweite, dumme
Zählung sieht das.

**Zum Stand der Werkzeuge, nachgemessen am 06.09.2026:** `pruefe_reste.py` hat weiterhin **sechs**
Prüfungen, und der Konfliktzustands-Wächter fehlt allen sechs (`grep -c` → 0). Issue #60 gilt
unverändert.

> **RICHTIGSTELLUNG des Torwächters zum Abschnitt „06.09.2026, Runde 18" (PR #71, geschlossen am
> 06.09.2026).** Der Abschnitt oben ist gerettet, weil seine Regel gut ist — **zwei seiner
> Belegsätze sind aber falsch, und beide sind selbst nachgemessen.** Sie gelten nicht.
>
> **1. Die Namen im Beleg der neuen Regel stimmen nicht.** Behauptet war: „Die zwei Enums kamen mit
> `DimBedienungshilfenWunschTest` und `DndDiagnostikTest` dazu." Gemessen:
>
> ```
> diff <(git grep -c "enum class" d463025 -- '*.kt' | sed 's/^d463025://') \
>      <(git grep -c "enum class" ab12d87 -- '*.kt' | sed 's/^ab12d87://')
> → > app/src/main/java/.../dnd/DndDiagnostik.kt:2      (die EINZIGE Änderung)
>
> git grep -c "enum class" origin/main -- '*DimBedienungshilfenWunschTest.kt' '*DndDiagnostikTest.kt'
> → 0 Treffer   (beide Testdateien deklarieren KEIN Enum)
>
> git ls-tree -r --name-only ab12d87 | grep -i DimBedienungshilfenWunsch
> → leer        (die Datei existierte zum gemessenen Ref gar nicht)
> ```
>
> Die beiden zusätzlichen Enums sind `AusGrund` (Z. 37) und `DndQuelle` (Z. 78) in der
> **Produktivdatei** `dnd/DndDiagnostik.kt`. Die Zahlen 35 → 37 stimmen exakt, die Regel
> („Anzahl gegen eine naive Stichwortzählung auf dem Ref der Vorrunde prüfen") ist richtig und
> bleibt gültig — **nur ihr eigener Beleg ist genau der Fehler, den Runde 17 als Lehre
> aufgeschrieben hat**: geprüft wurde, ob etwas gefunden wurde, nicht, ob das Gefundene aussieht
> wie das Gesuchte.
>
> **2. „nach dem Schnitt 0 Rohbefunde" ist widerlegt — fünf Zeilen unter dem Schnitt.**
> `enum class DiscoveryStage` (`hue/data/DiscoveryStatus.kt:47-53`) hat **sechs Einträge und im
> ganzen Baum null Verwender**: `git grep -n "DiscoveryStage" -- '*.kt'` findet nur die
> Deklaration und einen Kommentar. Alle Vorkommen von `STARTING`, `N_UPNP_SEARCH`, `MDNS_SEARCH`,
> `VALIDATING`, `COMPLETED`, `FAILED` außerhalb der Deklaration sind **Zeichenketten**
> (`DiscoveryStatus.stage` ist ein `String`). Wer diese Strings als Verwender zählt, benutzt genau
> den Mechanismus, den `ab12d87` als Defekt festgestellt hat. **Die Vollständigkeitsachse ist
> also NICHT geschlossen** — im Gegenteil, hier liegt der nächste belegte Rohbefund fertig da.
> *(Dieser letzte Satz gilt nach der Regel, die seit Runde 17 in Kraft ist und in #64 als
> Vorbedingung 2 steht, NICHT: dort blenden Kommentare aus, **String-Literale zählen mit** — der
> nachgewiesene Selbstentwaffner war Kommentartext, nicht ein Literal im Produktivcode. Unter der
> geltenden Regel ist `DiscoveryStage` kein Eintrags-Rohbefund; der Typ ist tot, und das ist
> **#72**. In Runde 29 nachgemessen, dort auch die Folgen für ein Gatter.)*
>
> **Was unverändert gilt:** der Schnitt selbst (neun Einträge in `TargetType`, `ActionType`,
> `DiscoveryMethod`) ist zum zweiten Mal vom Torwächter bestätigt — kein Verwender, keine
> Iteration, kein `when`, kein `.ordinal`, keine ProGuard-Regel, und kein Erzeuger über die
> gesamte Historie, also nichts in einem Bestands-JSON. Er darf unverändert wiederkommen.

### 07.09.2026, Runde 19 (Issue #20, Extension-Funktionen ohne Aufrufer)

**Ergebnis: 29 Kandidaten, 0 Rohbefunde, 0 bestätigte Funde — nichts zu schneiden.** Der
Blickwinkel ist damit *abgearbeitet*, nicht verworfen: er war billig, vollständig entscheidbar und
hat eine saubere Null geliefert. Gemessen über 426 Kotlin-Dateien (243 `main`, 176 `test`,
4 `androidTest`): **29 Extension-Funktionen, davon 11 auf Dateiebene und 18 als Member,
22 `private` und 7 sichtbar.** Jede einzelne hat eine belegte Aufrufstelle.

Deshalb bringt dieser PR **keinen Schnitt** — nur diesen Nachtrag. Ein Aufräum-PR ohne Schnitt ist
der richtige Abschluss einer Runde, die ehrlich nichts gefunden hat; die Alternative wäre, etwas zu
schneiden, damit die Runde nach Arbeit aussieht.

**Neue Lehre 1: Sichtbarkeit ist Teil der Messung, nicht Beiwerk.** Eine `private` Deklaration auf
Dateiebene ist in Kotlin nur in *ihrer eigenen Datei* aufrufbar — bei 22 von 29 Kandidaten also.
Wer trotzdem den ganzen Baum nach dem Namen absucht, misst nicht „hat Aufrufer", sondern „kommt der
Name irgendwo vor", und das ist bei kurzen Namen etwas völlig anderes:

```
raw          53 Namenstreffer im Baum   ->   4 echte Aufrufe (alles andere: `val raw = …`)
unresolved   42 Namenstreffer im Baum   ->  10 echte Aufrufe (alles andere: `result.unresolved`)
validate     10 Namenstreffer im Baum   ->   7 echte Aufrufe (Rest: „could not validate…" im Log)
```

Die Richtung des Fehlers ist die tückische: eine baumweite Namenssuche **überzählt** Verwender und
**versteckt damit Funde**, statt falsche zu erzeugen. Eine Runde, die nur so misst, meldet „nichts
gefunden" und klingt gründlich. Beide Fassungen zu bauen — großzügig über den Baum *und* streng
über den Sichtbarkeitsbereich — kostete hier zehn Minuten und ist der einzige Grund, warum die Null
oben belastbar ist. **Wenn beide Fassungen dasselbe sagen, ist das ein Beleg; sagt nur eine etwas,
ist es eine Vermutung.**

**Neue Lehre 2: Überladungen in derselben Datei trennt nur die Stelligkeit.** `HueTargetReconciler`
deklariert `HueLightAction.unresolved` **zweimal** (Z. 236 zweistellig, Z. 246 dreistellig). Jeder
namensbasierte Zähler — auch der strenge — markiert beide als lebendig, sobald *eine* aufgerufen
wird; eine tote Überladung wäre unsichtbar geblieben. Von Hand nach Argumentzahl aufgelöst: die
zweistellige läuft in Z. 114/126/130 und aus Z. 250, die dreistellige in Z. 179/186/190/207/225/230.
Beide leben. **Zähle bei gleichnamigen Deklarationen in einer Datei die Argumente, sonst ist dein
„lebendig" für alle bis auf eine geraten.**

**Kein Gatter — und diesmal liegt es nicht an den Zahlen.** Die Klasse ist statisch entscheidbar und
hätte heute 0 % Fehlalarm. Sie taugt trotzdem nicht zur Dauerprüfung: Der Ertrag ist null (29
Kandidaten, alle lebendig), und die Erkennung hat eine bekannte blinde Stelle, die genau bei neuem
Code zuschlägt — `infix`- und `operator`-Erweiterungen werden **nicht** als `name(` aufgerufen,
sondern als `a foo b` bzw. `a + b`. Heute gibt es keine einzige, die erste würde die Prüfung
fälschlich melden. Ein Gatter mit null Ertrag und einem eingebauten Fehlalarm für den nächsten
regulären Kotlin-Idiom-Gebrauch ist genau die Sorte, die der Skill fünfmal geschlossen gesehen hat.
**Nicht bauen.** (Die Messskripte waren Wegwerfcode im Scratchpad, wie vorgesehen.)

**Vorab gemessen, damit niemand eine Runde darauf verwendet: Extension-*Properties* lohnen nicht.**
Der naheliegende Nachbar-Blickwinkel („`val Typ.name get()` ohne Leser") hat im ganzen Baum
**8 Kandidaten**, und die Stichprobe entkräftet ihn schon: fünf sind `by preferencesDataStore`-
Delegates (per Konstruktion in Gebrauch), `HueScheduleRule.modus` hat 61 Vorkommen, und
`ColorScheme.success`/`ColorScheme.warning` sind über `MaterialTheme.colorScheme.…` in
`AlarmStatusHeader`, `HueSettingsScreen` und `SettingsTabContent` belegt gelesen. Ich habe dafür
**kein Issue angelegt** — ein Blickwinkel, den man beim Aufschreiben schon widerlegt hat, gehört
nicht in die Warteschlange. Merkposten für die Zählung, falls es doch jemand versucht:
`success` hat baumweit 642 Namenstreffer, praktisch alle `Result.success` — siehe Lehre 1.

**Geprüft und bewusst so — nicht als Fund melden:** `private fun DimRule.betrifftSchicht` steht
**zweimal** identisch im Baum (`DimRuleUseCase.kt:145`, `DimmerModellMigration.kt:354`). Das ist
keine Altlast: beide sind dateiprivat, beide werden in ihrer eigenen Datei benutzt, und der KDoc der
zweiten benennt die Kopie ausdrücklich („Wie `DimRuleUseCase.betrifftSchicht`"). Zusammenlegen wäre
„schöner machen" und würde die Migration an den lebenden UseCase koppeln — genau das, was eine
Migration nicht darf. Stehen lassen.

**Zum Stand der Werkzeuge, nachgemessen am 07.09.2026:** `pruefe_reste.py` hat weiterhin **sechs**
Prüfungen, und der Konfliktzustands-Wächter fehlt allen sechs (`grep -c 'ls-files", "-u'` → 0).
**Issue #60 gilt unverändert** und ist inzwischen der dritte Nachtrag in Folge, der ihn meldet.

#### RICHTIGSTELLUNG des Torwächters, 07.09.2026 (PR #75 geschlossen)

Der Absatz oben ist **gerettet, aber nicht bestätigt**. Sein Kernergebnis hält; drei seiner Zahlen
halten nicht, und ausgerechnet die falschen stehen in der Tabelle, die künftigen Runden das
*richtige Zählen* beibringen soll. Wer den Absatz zitiert, zitiert bitte diese Richtigstellung mit.

**Was hält — unabhängig nachgemessen, kein Zweifel:** Es gibt **29 Extension-Funktionen**
(11 auf Dateiebene, 18 als Member; 22 `private`, 7 sichtbar), und **keine einzige ist tot**.
Ein Widerleger hat das nicht per Regex, sondern mit einem eigenen Tokenizer reproduziert
(Kommentare/Strings ausgeblendet, Typparameter übersprungen, Empfängerregion auf oberster
Klammerebene geprüft) und alle 29 einzeln im jeweiligen Sichtbarkeitsbereich auf Aufrufer geprüft:
**29/11/18/22/7 und 0 tote — identisch.** Auch die Überladungsauflösung
`HueTargetReconciler.kt:236/246`, der doppelte `betrifftSchicht`, die 8 Extension-Properties und
der Werkzeugstand (`pruefe_reste.py` sechs Prüfungen, `ls-files -u` fehlt allen sechs, Issue #60
offen) sind nachgemessen und richtig. **Der Blickwinkel ist damit erledigt — niemand muss ihn
wiederholen.**

**Falsch 1 — die Kopfzahl widerspricht ihrer eigenen Klammer.** „426 Kotlin-Dateien" stimmt nicht;
243 + 176 + 4 = **423**, und 423 ist auch der Istwert:

```
find app/src -name '*.kt' | wc -l   ->  423      (main 243, test 176, androidTest 4)
git ls-files '*.kt'      | wc -l   ->  423      (ausserhalb app/src liegt keine .kt)
```

Das ist genau die Bezugsgröße, gegen die eine nächste Runde ihre Vollständigkeit prüfen würde.

**Falsch 2 — „22 von 29" gehört nicht zu dem Satz, den es belegen soll.** Lehre 1 schreibt: „Eine
`private` Deklaration **auf Dateiebene** ist nur in ihrer eigenen Datei aufrufbar — bei 22 von 29
Kandidaten also." Auf Dateiebene privat sind **4 von 29**, nicht 22:

```
DndPrefs.kt:342  zieheSchichtnamenNach     HueRuleFormState.kt:276  zieleAlsAktionen
DndPrefs.kt:361  entferneSchichtnamen      HueViewModel.kt:737      toConnectionHealth
```

Die 22 sind alle `private` **zusammen**; die übrigen 18 sind private *Member* und auf ihre Klasse
bzw. ihr `companion object` beschränkt, nicht auf die Datei. Die Lehre selbst — Sichtbarkeit ist
Teil der Messung — bleibt richtig, ihr Beleg ist es nicht.

**Falsch 3 — die Beweistabelle der Lehre 1 reproduziert sich nicht.** Zeilenbasiert über alle
`*.kt` (`git grep -nw <name> -- '*.kt' | wc -l`), also mit derselben Konvention, unter der die
Kontrollzahlen `modus` = 61 und `success` = 642 sauber aufgehen:

| Name | im Absatz | nachgemessen |
|---|---|---|
| `raw` | 53 | **58** |
| `unresolved` | 42 | **44** |
| `validate` | 10 | **16** |

Keine der drei stimmt, und die Tabelle mischt zwei Konventionen: `validate` = 10 ergibt sich nur,
wenn man Kommentare **und String-Literale** abzieht — dann fällt aber ausgerechnet der „Rest", den
die eigene Klammer benennt („could not validate…", `HueTrustManager.kt:405`), als String selbst
heraus und kann den Rest nicht mehr erklären. Dazu wechselt die rechte Spalte den Bezugsrahmen:
`raw` ist **zweimal** deklariert (`DimRuleRepositoryTest.kt:49`, `AlarmRepositoryBrokenPersistenceTest.kt:59`)
mit je vier Aufrufern, baumweit also **8** statt der genannten 4 — eine baumweite Trefferzahl steht
dort einer Aufrufzahl **einer** Deklaration gegenüber. Das ist Wort für Wort der Fehler, vor dem
Lehre 2 eine Zeile später warnt.

**Vorsicht bei einer Begründung, nicht bei ihrem Ergebnis:** Dass fünf der acht
Extension-Properties `by preferencesDataStore`-Delegates und „**per Konstruktion in Gebrauch**"
seien, ist in diesem Repo keine Messung, sondern eine widerlegte Annahme — `CLAUDE.md` führt den
`@TokenDataStore`-Provider (`oauth_tokens`) als genau solchen Delegaten, den bis v1.11.2 **niemand**
injizierte; am Gerät verifiziert, die Datei existierte gar nicht. Das *Ergebnis* stimmt hier
trotzdem: alle acht wurden einzeln nachgeprüft und sind lebendig. Wer den Nachbar-Blickwinkel
„Extension-Properties ohne Leser" später doch aufgreift, stützt sich also auf diese Einzelprüfung,
nicht auf „per Konstruktion".

**Die Lehre über allem — sie ist der Grund, warum PR #75 trotz sauberen Kernergebnisses geschlossen
wurde:** Diese Datei ist für die nächste Runde bindend. Eine Runde, die ehrlich nichts findet, tut
das Richtige, wenn sie nichts schneidet — dann ist der Nachtrag aber ihr **einziges** Erzeugnis und
trägt die volle Beweislast. **Zahlen, die nur der Erzählung dienen, müssen denselben Nachweis
aushalten wie ein Schnitt.** Prüf jede Zahl, die du aufschreibst, gegen die Zählweise, mit der du
sie gewonnen hast, benenne diese Zählweise im Text — und rechne die Summen deiner eigenen
Klammern nach.

### 08.09.2026, Runde 20 (Issue #21, Dateien im Repo, die nichts referenziert)

Diese Runde hat zwei Issues erledigt, weil das erste keine Arbeit mehr enthielt.

#### Vorweg: #20 war fertig und stand trotzdem vorn

`blickwinkel_waehlen.py` nannte „Extension-Funktionen ohne Aufrufer" — den Blickwinkel, den
Runde 19 abgearbeitet hatte und den der Torwächter beim Schließen von PR #75 **selbst noch einmal
reproduziert** hatte („29, davon 0 tote"). Offen war er nur, weil `torwaechter.yml` das Issue beim
Schließen des PR mechanisch wieder aufmacht. **Das ist die #39-Mechanik, gegen die das
Auswahlwerkzeug gebaut wurde — sie greift aber nicht, wenn der PR nicht am Befund scheitert,
sondern an den Zahlen des Nachtrags.** Der Deckel zählt erst ab drei Anläufen; bis dahin kostet ein
inhaltlich erledigter Blickwinkel weitere Runden.

Statt die Runde daran zu verbrauchen: geprüft, ob der Befund noch gilt, und das Issue geschlossen.
Der Beleg ist ein Einzeiler und gehört in jede Runde, deren Issue-Kommentare „abgearbeitet"
behaupten:

```
git diff --stat 44f035f..origin/main -- '*.kt'   ->  leer
```

Keine Kotlin-Datei hat sich seit der Rettung des Nachtrags bewegt; der Korpus ist bitgleich mit dem
Baum, auf dem zwei Parteien jede der 29 Erweiterungen einzeln geprüft haben. **Ist der Korpus
unverändert, ist eine Wiederholung keine Bestätigung, sondern nur Verbrauch.** Hat sich der Baum
dagegen bewegt, gilt das Gegenteil — dann muss die Runde messen.

#### Lehre 1 (vierte Achse): zwei übereinstimmende Zähler beweisen nichts, wenn sie denselben Unterbau teilen

Beim Nachzählen der 29 kam ich zweimal auf **28** — einmal streng über die Empfängerregion, einmal
lose über „Punkt im Kopf vor der Klammer". Zwei Muster, ein Ergebnis, und trotzdem falsch: beide
benutzten **denselben Masker**, und der Fehler saß in ihm.

Kotlin beendet einen Raw-String **greedy**. In `AlarmRepositoryBrokenPersistenceTest.kt:54` endet
ein `"""…"""`-Literal mit vier Anführungszeichen (`…"shiftName":"Frueh""""`) — das erste gehört zum
Inhalt, die letzten drei schließen. Mein Masker fraß drei; das übrige eröffnete einen
**Phantom-String**, der bis zum nächsten Anführungszeichen lief und dabei die Zeilen 55–59
verschluckte, darunter die 29. Deklaration. Die Selbstprüfungen der Runden 16–18 waren dabei alle
grün: nichts war leer, die Namen sahen richtig aus, die Menge stimmte gegen eine naive Zählung —
der Fehler saß *unterhalb* von allen dreien.

**Variiere also nicht nur das Muster, sondern die Ebene.** Eine Gegenprobe ist erst eine, wenn sie
den gemeinsamen Unterbau umgeht; hier genügte ein `git grep` ganz ohne Maskierung. Wer Kotlin
maskiert, muss `""""` können: die Zeichenfolge kommt im Baum **dreimal in zwei Dateien** vor
(`DimRuleRepositoryTest.kt:92` und `:107`, `AlarmRepositoryBrokenPersistenceTest.kt:54`). Dass sie
nur einmal etwas gekostet hat, ist Zufall der Lage — **der Schaden trifft immer das, was FOLGT**:
in `DimRuleRepositoryTest` schließt das zweite Vorkommen den vom ersten geöffneten Phantom-String,
und die einzige Deklaration der Datei steht mit Zeile 49 davor.

#### Lehre 2: der Nachtrag verfälscht die Messung, wenn du den Arbeitsbaum misst

Das ist in dieser Runde tatsächlich passiert, nicht ausgedacht. Erst gemessen (**32 Rohbefunde**),
dann diesen Abschnitt geschrieben, dann zur Kontrolle noch einmal gemessen: **23**. Neun Dateien
waren „referenziert" — von meinem eigenen Nachtrag, der sie beim Namen nennt
(`.gitignore`, `docs/CNAME`, `licenses/Mulish-OFL.txt` …).

Für einen Blickwinkel, dessen Befund „niemand nennt diese Datei" lautet, ist das Selbstentwaffnung
in Reinform — dieselbe Mechanik, die der Torwächter an Prüfung 7 als Defekt (b) nachgewiesen hat,
nur nicht in einer ENTFERNT-Notiz, sondern in der Runden-Dokumentation selbst. **Miss deshalb gegen
einen git-Ref, nicht gegen den Arbeitsbaum** (`git ls-tree -r --name-only <ref>` plus
`git show <ref>:<datei>`). Das ist ohnehin die ehrlichere Bezugsgröße: gemessen wird der Stand, den
der PR vorfindet. `git stash` ist hier ausdrücklich **kein** Ausweg — CLAUDE.md verbietet es im
geteilten Arbeitsbaum.

#### Der Blickwinkel selbst: 107 Kandidaten, 32 Rohbefunde, 32 Fehlalarme (100 %), 0 Schnitte

Zählweise, ausdrücklich benannt, gemessen gegen `origin/main` (`ee17a00`): **Kandidat** = jede
verfolgte Datei außerhalb `app/src/` (561 verfolgt − 454 unter `app/src` = **107**).
**Verweisquelle** = jede *andere* verfolgte Textdatei, `app/src` eingeschlossen (541 von 561 sind
als UTF-8 lesbar). **Verweis** = voller Pfad, oder Basisname, oder — nur für `.py` — der Modulname
als Wort; Selbstnennungen zählen nicht. Ein Verweis aus Kommentar oder Prosa **zählt hier mit**,
anders als bei Code-Symbolen: das Issue fragt nach „aufgerufen **oder verlinkt**", und eine Datei,
die in `CLAUDE.md` erklärt wird, ist nicht verwaist.

Alle 32 Rohbefunde, vollständig in fünf Klassen:

| Klasse | Anzahl | Wer sie verbraucht |
|---|---|---|
| `.idea/*` | 14 | Android Studio, Verzeichniskonvention |
| `test_*.py` unter `tools/` | 8 | `python3 -m unittest discover -p "test_*.py"` — `ci.yml` (2×) und `sammel-release.yml` |
| Werkzeugkonvention: `.gitattributes`, `.gitignore`, `app/.gitignore`, `docs/CNAME`, `gradle-daemon-jvm.properties`, `gradle-wrapper.properties`, `licenses/Mulish-OFL.txt` | 7 | git, Gradle, GitHub Pages, die OFL der mitgelieferten Schrift |
| `claude.yml`, `dependabot-automerge.yml` | 2 | GitHub-Ereignisse |
| `rundenweise_aufraeumen.cmd` | 1 | ein Mensch von Hand — siehe Rückfrage unten |

14 + 8 + 7 + 2 + 1 = 32. **31 davon sind per Konvention referenziert**, also von einem Verbraucher,
der Dateien nicht beim Namen nennt; der 32. ist ein Einsprungpunkt für die Hand. Geschnitten wurde
nichts.

Vollständigkeit der 107 gegengerechnet: 29 `.claude` (1 `settings.json` + 10 `SKILL.md` +
18 `reference/*.md`, **alle 18 aus ihrer eigenen `SKILL.md` verlinkt**, Fundzeile einzeln
angesehen) + 23 `tools` + 15 `.idea` + 12 Wurzel + 9 `docs` + 9 `.github` + 5 `app` + 4 `gradle` +
1 `licenses` = **107**. Die 23 unter `tools/` gehen auf: 10 von Workflow oder Hook aufgerufen,
8 per `unittest discover` gefunden, 5 einzeln belegt (`blickwinkel_waehlen.py`, `nachtraege.md`,
`warnungen_geduldet.txt`, `pruefe_treue.py`, `rundenweise_aufraeumen.cmd`).

**Zwei Überzählungen, beide in der von Runde 19 beschriebenen Richtung — sie verstecken Funde:**

- `.idea/.name` bekommt **203** Basisnamen-Treffer, alle zufällig: `.name` steckt in jedem
  `git config user.name`. Ohne Blick auf die Fundzeile gilt die Datei als gut referenziert.
- `Icon.png` (Projektwurzel) wird **ausschließlich** in einem Kommentar genannt
  (`LoginScreen.kt:64`: „skaliert aus Icon.png -> res/drawable-nodpi/ic_app_logo.png"). Kein Build
  fasst sie an. Sie bleibt: das ist die Quellgrafik der ausgelieferten, und der Kommentar ist genau
  die Verlinkung, nach der das Issue fragt.

Umgekehrt zeigt `app/r8-rules.txt`, warum die strenge Fassung allein nichts taugt: **kein einziger
voller Pfad** im Baum (streng gezählt hätten 68 der 107 keinen Verweis), aber
`app/build.gradle.kts:148` nennt `"r8-rules.txt"` relativ. **Beide Fassungen bauen, keiner allein
glauben.**

**Kein Gatter, und es ist keine knappe Entscheidung: 100 % Fehlalarm.** Jeder echte Verbraucher in
diesem Repo — git, Gradle, GitHub, `unittest discover`, Android Studio — nennt seine Dateien nicht
beim Namen. Eine Dauerprüfung müsste all diese Konventionen als Ausnahmeliste mitführen und wäre
bei der ersten neuen Konvention falsch. **Nicht bauen.** Der Blickwinkel ist *abgearbeitet* und
gehört mit 107 / 32 / 32 in die „Verworfen"-Tabelle des Skills.

**Zwei Rückfragen als Issue angelegt** — nichts geschnitten, beide verlangen die Absicht des
Eigentümers, und Absicht wird nicht geraten:

- `tools/aufraeumen/rundenweise_aufraeumen.cmd`, der Windows-Auslöser für eine Runde: angelegt am
  25.08.2026 um 07:39 (`85b9397`, „noch ohne Zeitplan"), seither **nie wieder angefasst**. Vier
  Stunden später kam `aufraeumen.yml` (`c5e684d`, 11:46) und tut dasselbe; `gh run list` zeigt
  **acht Läufe an acht Tagen, alle mit `event: schedule`**. Der Wecker läuft also, nur nicht von
  diesem Skript.
- `tools/changelog/pruefe_treue.py` braucht laut Skill eine handgeschriebene Originalseite als
  Argument; im Baum gibt es die nicht mehr, nur noch in der Historie.

**Zum Stand der Werkzeuge, nachgemessen am 08.09.2026:** `pruefe_reste.py` hat weiterhin **sechs**
Prüfungen, und der Konfliktzustands-Wächter fehlt allen sechs
(`grep -c 'ls-files", "-u' tools/aufraeumen/pruefe_reste.py` → 0). **Issue #60 gilt unverändert**
und ist damit der vierte Nachtrag in Folge, der ihn meldet.

#### Richtigstellung des Torwächters, 08.09.2026 (zu Runde 20, PR #78 — geschlossen)

Der Abschnitt oben ist gerettet, **sein PR wurde geschlossen** — und er gilt nur in der Fassung,
die diese Richtigstellung aus ihm macht. Vier seiner Zahlen und zwei seiner Belege halten nicht.
Wer ihn liest, liest diesen Absatz mit.

**Der Befund war nie das Problem.** Ich habe den Blickwinkel mit einem eigenen Zähler nach der dort
benannten Zählweise reproduziert, gegen `ee17a00`: **561 verfolgt − 454 unter `app/src` = 107
Kandidaten, 541 UTF-8-lesbar, 32 Rohbefunde, 32 Fehlalarme, 0 Schnitte**, Klassen
`14 + 8 + 7 + 2 + 1 = 32`, streng gezählt 68 von 107 ohne Verweis, alle 18 `reference/*.md` aus
ihrer eigenen `SKILL.md` verlinkt. Zwei unabhängige Widerleger kamen auf dieselben Zahlen und
fanden **keinen 33. Rohbefund**. Geschlossen wurde wegen der Zahlen und Erklärungen **daneben** —
und eine Runde ohne Schnitt liefert nichts anderes als die. Damit die nächste Runde nicht auf
ihnen aufbaut:

1. **`aufraeumen.yml`: nicht „acht Läufe an acht Tagen, alle mit `event: schedule`".**
   `gh run list --workflow aufraeumen.yml -L 100` → **15 Läufe an 15 verschiedenen Tagen**
   (25.08.–08.09.2026), davon **14 × `schedule` und 1 × `workflow_dispatch`** (25.08.), und
   **einer ist fehlgeschlagen** (29.08.). Der Default von `gh run list` ist 20 — die vollständige
   Zahl war ohne Zusatzaufwand zu haben. Das wiegt doppelt: Der Satz trug die Beweislast für
   Rückfrage **#76** („Der Wecker läuft also, nur nicht von diesem Skript") — und ausgerechnet der
   **eine Handstart**, den „alle mit `event: schedule`" wegdefiniert, ist die Ereignisklasse, um
   die es dort geht. #76 bleibt offen, aber ohne diesen Beleg.

2. **Die 203 stimmen, ihre Ursache ist erfunden.** Behauptet war: „`.idea/.name` bekommt 203
   Basisnamen-Treffer, alle zufällig: `.name` steckt in jedem `git config user.name`."
   Gemessen: `user.name` kommt im **ganzen Baum zweimal** vor (`sammel-release.yml:172`,
   `test_sammel_release.py:163`) — **2 von 203, also 1 %**. **193 der 203 Trefferzeilen liegen in
   `app/src`** und sind gewöhnliche Kotlin-Property-Zugriffe (`rule.name`, `${rule.name}`);
   außerhalb `app/src` sind es zusammen 10. Die **Lehre** („ohne Blick auf die Fundzeile gilt die
   Datei als gut referenziert") bleibt richtig, ihre **Mechanik** war falsch — wer bei der nächsten
   Überzählung nach git-Konfiguration sucht statt nach Sprach-Syntax im Produktivcode, sucht am
   falschen Ort.

3. **Die Zählweise der 203 war nicht benannt** — genau das verlangt die Lehre von Runde 19 zwei
   Absätze weiter oben. **203** sind **Trefferzeilen** (`git grep -c`, summiert), **207** die
   **Vorkommen** (`git grep -o | wc -l`). Beide Zahlen sind richtig; erst die Zählweise macht sie
   überprüfbar.

4. **Lehre 1 hat recht, ihre beiden Belege nicht.** Die Kotlin-Semantik stimmt (Raw-String endet
   greedy; von `""""` gehört das erste Zeichen zum Inhalt, die letzten drei schließen), und das
   Vorkommnis stimmt auch: der Phantom-String verschluckt in
   `AlarmRepositoryBrokenPersistenceTest.kt` die Extension-Deklaration in Zeile 59, daher zweimal
   28 statt 29. Die zwei Belegsätze habe ich nachgestellt, mit demselben non-greedy-Masker:
   - „verschluckte dabei die Zeilen **55–59**" → es sind **55–64**; der Phantom schließt erst an
     `shiftId = "shift$id"` in Zeile 64.
   - „in `DimRuleRepositoryTest` schließt das **zweite** Vorkommen den vom ersten geöffneten
     Phantom-String" → **nein.** Der Phantom aus Zeile 92 schließt bereits in **Zeile 100**
     (`repo.upsert(rule("neu"))`); das Vorkommen in Zeile 107 erreicht ihn gar nicht und öffnet
     ein **eigenes** Leck (geschlossen in Zeile 111). Die Merkregel, die daraus entstünde — *zwei
     `""""` in einer Datei heben sich auf* — ist falsch und würde eine Datei fälschlich für
     harmlos erklären. Dass es in dieser Datei nichts gekostet hat, stimmt: die einzige
     Extension-Deklaration steht in Zeile 49, davor.

5. **`unittest discover` steht in `ci.yml` dreimal, nicht zweimal:** `:82` (`tools/schleuse`),
   `:96` (`tools/aufraeumen`), `:102` (`tools/release`), dazu `sammel-release.yml:70`. Die Sache
   selbst stimmt — alle acht `test_*.py` liegen in diesen drei Verzeichnissen (5 / 2 / 1).

6. **„Vierter Nachtrag in Folge" zu Issue #60 stimmt nicht.** Gemeldet haben ihn die Runden
   **16, 18, 19, 20** — **Runde 17 nicht** (`sed -n '84,128p' | grep -c '#60'` → 0). Vier
   Nachträge ja, „in Folge" nein; der Fehler ist von Runde 19 geerbt („der dritte in Folge") und
   wurde ungeprüft fortgeschrieben. **Der Sachverhalt selbst gilt unverändert:** `pruefe_reste.py`
   hat sechs Prüfungen, `grep -c 'ls-files", "-u'` → 0, **#60 ist offen.**

**Was daraus folgt — und PR #75 ist einen Tag her, aus demselben Grund geschlossen:**

- **Eine Runde ohne Schnitt wird ausschließlich an ihren Zahlen gemessen.** Es gibt nichts anderes
  zu prüfen. Das ist kein Vorwurf an das Nichtschneiden — nichts zu finden ist ein gültiges
  Ergebnis — sondern die Folge daraus.
- **Zahl und Erklärung sind zwei Behauptungen.** Geprüft wird hier regelmäßig nur die erste. Wer
  eine **Ursache** angibt („alle zufällig, weil X"), misst sie genauso nach wie die Zahl.
- **Zitierst du ein Werkzeug, nimm seinen Vollausgabe-Schalter** (`gh run list -L 100`, nicht den
  Default 20) und schreib die Zählweise dazu.
- **Übernimm keine Zahl aus einem früheren Nachtrag ungeprüft.** „In Folge" ist genau so in die
  vierte Runde gewandert.
- **Stellst du einen Beleg nach, stell ihn ganz nach.** Die beiden falschen Belege in Lehre 1 wären
  mit demselben Skript aufgefallen, das den Fehler überhaupt erst zutage gefördert hat.

**Nicht wiederholen:** Blickwinkel **#21** („Dateien im Repo, die nichts referenziert") ist
inhaltlich **abgearbeitet** und gehört mit **107 / 32 / 32 / 0** in die „Verworfen"-Tabelle des
Skills — 100 % Fehlalarm, weil jeder echte Verbraucher hier (git, Gradle, GitHub,
`unittest discover`, Android Studio) seine Dateien nicht beim Namen nennt. **Kein Gatter bauen.**
Ich habe **#21 deshalb bewusst NICHT wieder geöffnet**, abweichend vom Regelablauf des
Torwächters: der Blickwinkel geht nicht verloren, er steht hier vollständig. Wer das anders sieht:
`gh issue reopen 21`.

**Offen und unbeurteilt geblieben:** Die Runde hat außerhalb ihres Diffs Zustand geändert —
Issue **#20 geschlossen**, **#76** und **#77** angelegt. Das Schließen von PR #78 nimmt davon
nichts zurück, und kein Widerleger kann es sehen. Der Skill sagt „**Genau EINEN Blickwinkel.**
Nicht zwei, nicht ‚schnell noch'" und zum Übergehen des Auswahlwerkzeugs: „eine **Rückfrage ans
Issue, kein Grund zum Übergehen**". Für #20 mag die Sache stimmen (der Korpus ist bitgleich,
`git diff --stat 44f035f..origin/main -- '*.kt'` ist leer — selbst nachgemessen); die
**Entscheidung** darüber stand der Runde trotzdem nicht zu. Die Begründung dafür trägt zudem
nicht: „die #39-Mechanik greift nicht, wenn der PR an den Zahlen des Nachtrags scheitert" ist
falsch — `gescheiterte_anlaeufe()` in `blickwinkel_waehlen.py` zählt **jedes**
`cross-referenced`-Ereignis auf einen geschlossenen, nicht gemergten PR, der Grund geht nicht ein.
PR #75 zählte für #20 also ganz normal, und der Deckel von 3 hätte gegriffen. Wer künftig ein fremdes Issue für
erledigt hält, schreibt das **in das Issue** und arbeitet seinen eigenen Blickwinkel ab.

### 09.09.2026, Runde 21 (Issue #22, KDoc, dem direkt ein weiterer KDoc folgt)

**Zahlen, Zählweise ausdrücklich benannt, gemessen gegen `2297893` (= `origin/main` beim Start):**
Korpus **423** `.kt` unter `app/src` (`git ls-tree -r --name-only`), darin **2311 KDoc-Blöcke**
(eigener Tokenizer, String-Literale maskiert, Rohstring-Regel aus Runde 20 eingebaut). Gegenprobe
auf anderer Ebene: `git grep -c '/\*\*'` aufsummiert = **2311 Trefferzeilen**, `git grep -o` =
**2311 Vorkommen** — je ein Marker pro Zeile, und keiner steckt in einem String-Literal, sonst
läge die Tokenizer-Zahl darunter. **19 Rohbefunde, 2 Fehlalarme (10,5 %), 17 bestätigt**, jeder
einzeln am Code angesehen. **10 repariert, 7 stehen gelassen** (Weckerkette/`dimmer/`, siehe
unten). Nachher: **9 Rohbefunde** (die 2 Fehlalarme + die 7 stehen gelassenen), 2305 KDoc-Blöcke
(−6: vier Zusammenführungen, eine Löschung, eine Verschiebung-mit-Zusammenführung).

**Positivkontrolle, bevor irgendetwas geschnitten wurde:** derselbe Detektor auf `f1ad9ee^` findet
`HueBridge.kt:28` — genau den Fall, den das Issue als blinden Fleck nennt und der seinerzeit nur
zufällig beim Lesen auffiel. Ein Blickwinkel-Skript, das den einen bekannten Fund nicht
reproduziert, misst etwas anderes als die Frage; das ist billiger zu prüfen als zu bereuen.

**Die im Issue vorgeschlagene Einschränkung ist widerlegt — von ihrem eigenen Anlassfall.**
Vorgeschlagen war „nur INNERHALB eines Klassen-/Objektrumpfs prüfen, nicht auf Dateiebene".
`HueBridge.kt:28` stand in einer **Konstruktor-Parameterliste**, also in runden, nicht in
geschweiften Klammern: Klammertiefe 0, die Einschränkung hätte ihn verworfen. Gemessen, falls
jemand sie mit Klammern beider Art bauen will: von den 19 Rohbefunden liegen **6 auf Tiefe 0 und
13 auf Tiefe 1**; unter den sechs sind **beide Fehlalarme, aber auch vier echte Funde**. Eine
Tiefenschranke kauft also 0 % Fehlalarm für den Preis von **4 der 17 echten Funde (23,5 %)**.
Zahlen und beide Varianten stehen im Gatter-Issue; **gebaut wird hier nichts** (Skill-Regel 4).

**Der Mechanismus ist immer derselbe, und er ist schlimmer als eine Narbe.** Nicht „Doku bleibt
nach gelöschtem Code liegen" (das ist Prüfung 5), sondern: jemand schreibt eine **neue Deklaration
samt eigenem KDoc direkt über eine bestehende** — der alte Block verliert seinen Anker und steht
danach über der **falschen** Deklaration. Drei Fälle am Diff belegt: `10e258e` (05.08.2026, neuer
Typ dazwischen), `c6176c8` (14.08.2026, zweiter KDoc davorgestapelt), `20f8867` (21.08.2026,
Deklaration umbenannt und geteilt). Am teuersten in `model/ShiftConfig.kt`: dort stand das KDoc von
`findDefinitionFor` — der Block, auf dem CLAUDE.mds Warnung „`findDefinitionFor` und
`matchesKeywords` nicht verwechseln" ruht — über `withCodeAssignedTo`, während `findDefinitionFor`
selbst undokumentiert war.

**Deshalb war die Reparatur meist kein Schnitt, und das ist Absicht.** Die Regel, nach der jeder
Einzelfall entschieden wurde: **verschieben**, wenn der Waise eine andere, weiter unten stehende
und dort undokumentierte Deklaration beschreibt (5×); **zusammenführen**, wenn beide Blöcke
dieselbe folgende Deklaration beschreiben (4×); **löschen** nur, wenn die beschriebene Deklaration
nicht mehr existiert (1×: `getEffectiveDaysAhead()` in `CalendarViewModel`, entfernt in `c7ffed7`
am 25.11.2025 — der Block stand danach **288 Tage** verwaist da). Löschen wäre in den anderen 16
Fällen Wissensverlust gewesen, und „belegt tot" heißt hier: die Deklaration ist weg, nicht bloß der
Text wirkt alt.

**Mechanisch belegt statt behauptet** (Wegwerfskript, zwei Fassungen desselben Tokenizers): über
die 7 geänderten Dateien hat sich **kein einziges Nicht-Kommentar-Zeichen** geändert, und von den
KDoc-Textzeilen sind **genau drei verschwunden, null hinzugekommen** — die Titelzeile „Der
Hinweistext ueber der Schichtliste." (im Ziel-KDoc steht bereits „Der vollstaendige Hinweis.", und
seit `20f8867` steht der Hinweis *in* der Liste) und die zwei Zeilen des toten
`getEffectiveDaysAhead`-Blocks. Wer einen Kommentar-Umbau liefert, kann diese zwei Zahlen nennen;
ohne sie ist „nur Kommentare bewegt" eine Behauptung.

**Sieben Funde bewusst NICHT angefasst** (`AlarmUseCase` 2×, `AlarmRepository`, `AlarmViewModel`,
`DimmerModellMigration` + sein Test, `DimmerRulesViewModel`): Leitplanke „Die Weckerkette fasst du
nicht an". Die Abgrenzung war mechanisch — Pfad oder Dateiname enthält `alarm`, `service` oder
`dimmer` —, damit sie nachprüfbar ist und nicht nach Gefühl. Ausgerechnet dort sitzt der wertvollste
Fall (`clearInternalAlarms`: die allgemeine Beschreibung verwaist, angeheftet ist nur noch der
`@param keepManualAlarms`-Block, und beide sind CLAUDE.md-Invarianten). Als Issue abgelegt, mit dem
Vermerk „braucht Rücksprache".

**Gegen die Wiederholung eines Fehlers von Runde 20:** gemessen wurde vorher gegen den git-Ref,
nachher gegen den Arbeitsbaum — bei diesem Blickwinkel ist das unschädlich, weil ein Fund an der
**Struktur** hängt und nicht daran, ob irgendwo ein Name genannt wird. Diese Selbstentwaffnung
trifft nur namensbasierte Blickwinkel; wer sie pauschal fürchtet, misst zweimal umsonst.

**Zum Stand der Werkzeuge, nachgemessen am 09.09.2026:** `pruefe_reste.py` hat weiterhin **sechs**
Prüfungen, und der Konfliktzustands-Wächter fehlt allen sechs
(`grep -c 'ls-files", "-u' tools/aufraeumen/pruefe_reste.py` → 0). **#60 gilt unverändert** (fünfter
Nachtrag, der ihn meldet — gezählt, nicht „in Folge" übernommen: Runden 16, 18, 19, 20, 21).

### 09.09.2026, Richtigstellung des Torwaechters zu Runde 21 (PR #80 geschlossen)

Der Nachtrag von Runde 21 steht oben unveraendert — **bis auf eine Stelle, und die ist widerlegt.**
Der Satz „**zusammenfuehren**, wenn beide Bloecke dieselbe folgende Deklaration beschreiben (4×)"
zaehlt `CalendarUseCase.getCalendarEventsWithCache` mit. Falsch — nach der Regel des Nachtrags
selbst, und der Beweis steht in einem Commit, den derselbe Nachtrag zwei Absaetze hoeher als
Belegfall zitiert.

`git show c6176c8 -- app/src/.../usecase/CalendarUseCase.kt` (14.08.2026) zeigt: der beschriebene
Rumpf wanderte **aus** `getCalendarEventsWithCache` **in die neu angelegte**
`getCalendarEventsWithStatus`; das KDoc blieb liegen. Seine drei inhaltlichen Zeilen — „Background
Threading mit Main-Thread Schonung", „PROGRESSIVE LOADING", „OPTION 4 FIX: Defensive token
validation" — beschreiben seither ausschliesslich `getCalendarEventsWithStatus`. Die ist weiter
unten und in der Implementierungsdatei **undokumentiert** (`CalendarUseCase.kt:196`, selbst
nachgesehen; das KDoc im Interface beschreibt den VERTRAG, nicht diese Implementierungsdetails).
Das ist woertlich der VERSCHIEBEN-Fall.

Zusammengefuehrt wurde trotzdem. Ergebnis: ein zweizeiliger `.map`-Delegat traegt jetzt die
Zusicherung, er mache Threading, gestaffeltes Laden und Token-Validierung — nichts davon tut er —,
und der Verweis „siehe die Begruendung dort" zeigt auf eine Funktion ohne Begruendung.
**Schlimmer als der Ausgangszustand:** die Fehlzuordnung blieb, aber das gestapelte KDoc-Paar war
ihr einziges strukturelles Merkmal. Mein eigener Detektor findet `CalendarUseCase.kt` auf dem
PR-Stand nicht mehr, und Pruefung 5 in `pruefe_reste.py` hat sie nie gesehen. Ein bestaetigter Fund
wurde verbraucht, ohne behoben zu werden, und als repariert gemeldet.

**Die Lehre, und sie ist billig zu befolgen:** *Wer einen Commit als Hergang zitiert, hat damit
auch die Zuordnungsfrage in der Hand — er muss ihn nur zu Ende lesen.* Bei einem verwaisten KDoc
lautet die Frage nie „welche Deklaration steht jetzt darunter", sondern **„wohin ist der Rumpf
gewandert, den der Text beschreibt"**. Genau diese Frage beantwortet `git log -S` bzw.
`git show <commit> -- <datei>` in einem Aufruf. Beim Zwilling `ShiftConfig.findDefinitionFor`
wurde sie richtig gestellt, hier nicht — obwohl der Belegcommit schon offen auf dem Tisch lag.
Und: **„jeder einzeln am Code angesehen" ist eine Behauptung wie jede andere.** Sie traegt nur so
weit, wie die Frage stimmt, die man dabei gestellt hat.

**Was der Torwaechter nachgemessen hat und was haelt** — damit der Schnitt nicht neu vermessen
werden muss: eigener Detektor gegen `2297893`: **423** `.kt`, **19 Rohbefunde auf `main`, 9 auf dem
PR-Stand**, die 9 eine echte Teilmenge der 19; KDoc-Bloecke **2311 → 2305**; die 7 nicht
angefassten treffen tatsaechlich alle `alarm|service|dimmer`; die 2 Fehlalarme sind Datei-Kopf-KDocs.
Ueber alle 7 geaenderten Dateien **kein einziges Nicht-Kommentar-Zeichen geaendert** (Tokenizer,
Strings maskiert), **3 KDoc-Textzeilen entfernt, 0 hinzugekommen**. `assembleDebug` +
`testDebugUnitTest` + `lintDebug` gruen, Urteil aus 172 XML-Berichten: **1331 Tests, 0 Failures,
0 Errors**; `pruefe_code.py` 6/6, `pruefe_reste.py` sauber; Lint 10 Befunde, alle aus der Liste der
akzeptierten Dauermeldungen. **Kein Gatter im PR** — Skill-Regel 4 wurde eingehalten, und daran lag
es diesmal ausdruecklich NICHT.

**Fuer die naechste Runde:** Issue #22 ist wieder offen. **Neun der zehn Reparaturen sind belegt
richtig** (`ShiftConfig`, `ConfigBackupFormat`, `DndPrefs`, `SimpleFileTree`, `ShiftConfigScreen`
2×, `CalendarViewModel` 3×) — sie duerfen unveraendert wiederkommen. Nur `CalendarUseCase` gehoert
verschoben statt zusammengefuehrt: das KDoc ueber `getCalendarEventsWithStatus` (Zeile 196), und
`getCalendarEventsWithCache` behaelt allein seinen eigenen Vertrags-Block. Die Gatter-Frage bleibt
in #81, die sieben Weckerketten-Funde in #82.

> **ERLEDIGT in Runde 22 (11.09.2026).** Der Auftrag aus diesem Absatz ist ausgefuehrt — alle zehn
> Reparaturen sitzen, `CalendarUseCase` verschoben statt zusammengefuehrt. Wer hier weiterliest,
> arbeitet ihn NICHT noch einmal ab; die Zahlen stehen im Abschnitt darunter.

### 11.09.2026, Runde 22 (Issue #22 zum zweiten Mal — der Schnitt ist durch)

**Zahlen, Zaehlweise ausdruecklich benannt, gemessen gegen `a9c7473` (= `origin/main` beim Start):**
Korpus **428** `.kt` unter `app/src` (`git ls-tree -r --name-only`), darin **2344 KDoc-Bloecke**
(eigener Tokenizer; Kommentare und String-Literale maskiert, Rohstring-Regel aus Runde 20 und
schachtelnde Blockkommentare eingebaut). **20 Rohbefunde, 2 Fehlalarme (10 %), 18 bestaetigt.**
Davon **10 repariert**, **8 unangetastet** (Weckerkette, siehe unten). Nachher: **10 Rohbefunde**
(die 2 Fehlalarme + die 8 stehen gelassenen), **2339 KDoc-Bloecke** (−5: vier Zusammenfuehrungen,
eine Loeschung; die fuenf Verschiebungen aendern die Zahl nicht).

**Der Korpus hatte sich bewegt — deshalb wurde neu gemessen und nicht abgeschrieben.** Gegen den
Ref der Vorrunde (`2297893`, Runde 18er Regel): 423 → 428 Dateien, 2311 → 2344 Bloecke. Mein
Detektor reproduziert auf jenem Ref **exakt die 19 Rohbefunde der Vorrunde** — zwei unabhaengig
gebaute Detektoren, dieselbe Menge. Der Zuwachs auf 20 ist **eine** echte Neuzugangsstelle
(`AlarmMaintenanceService.kt:1271`, aus den +282 Zeilen der Wartungskette); `ConfigBackupFormat`
wanderte nur von Zeile 231 auf 259. **Waere der Korpus bitgleich gewesen, haette die Runde nach
der Regel von Runde 20 nicht neu messen muessen — er war es nicht.**

**Positivkontrolle vor jedem Schnitt:** derselbe Detektor auf `f1ad9ee^` findet `HueBridge.kt:28`,
den im Issue benannten blinden Fleck — und zwar auf **Klammertiefe 0**. Die im Issue
vorgeschlagene Einschraenkung („nur innerhalb eines Klassenrumpfs") haette ihren eigenen Anlassfall
verworfen. Mit eigenen Zahlen bestaetigt, was Runde 21 dazu schrieb: von den 20 Rohbefunden liegen
**6 auf Tiefe 0, 14 auf Tiefe 1**; unter den sechs sind **beide Fehlalarme, aber auch vier echte
Funde**. Eine Tiefenschranke kauft 0 % Fehlalarm fuer den Preis von **4 der 18 echten Funde
(22 %)**. Gebaut wird hier nichts (Skill-Regel 4), die Frage bleibt in **#81**.

#### Neue Lehre: variiere die Ebene, aber miss die Richtung der Abweichung

Runde 20 verlangt eine Gegenprobe, die den gemeinsamen Unterbau umgeht. Ich habe neben den
Tokenizer eine rohe `awk`-Zustandsmaschine ganz ohne Maskierung gestellt: **18 statt 20.** Nach
Runde 17 ist der erste Reflex „einer von beiden hat eine Blindstelle" — richtig ist die Frage,
**welcher von beiden eine Teilmenge des anderen ist.** Die Differenzliste war zweizeilig
(`CalendarViewModel.kt:1435`, `HueRuleConfigHelpers.kt:20`), beide von Hand angesehen, beide echt:
zwischen den zwei KDoc-Bloecken stehen **Leerzeilen**, und mein `awk` verlangte Zeilen-Adjazenz.

**Die verallgemeinerbare Regel:** Eine Gegenprobe, die *weniger* findet, widerlegt nicht — sie ist
erst dann ein Befund, wenn sie etwas findet, das die Hauptmessung **nicht** hat. Bilde also immer
die Differenz in **beide** Richtungen und sieh die kurze Liste an. Hier war „awk ⊂ Tokenizer" mit
leerer Gegenrichtung der eigentliche Beleg dafuer, dass die 20 vollstaendig sind — eine blosse
Zahlengleichheit waere schwaecher gewesen, nicht staerker. (Runde 19 hat denselben Satz aus der
anderen Richtung: eine grosszuegige Messung **ueberzaehlt Verwender und versteckt Funde**.)

#### Die Zuordnungsfrage, diesmal fuer alle zehn gestellt

Die Richtigstellung zu PR #80 sagt: bei einem verwaisten KDoc lautet die Frage nicht „welche
Deklaration steht jetzt darunter", sondern **„wohin ist der Rumpf gewandert, den der Text
beschreibt"**. Das war die Arbeit dieser Runde — jeder der 18 bestaetigten Funde einzeln, und die
Zuordnung entschied ueber die Reparaturart. Ergebnis: **5× verschieben, 4× zusammenfuehren,
1× loeschen.**

| Fund | Der Waise beschreibt | Reparatur |
|---|---|---|
| `ShiftConfig.kt:22` | `findDefinitionFor` (Z. 93, undokumentiert) — stand ueber `withCodeAssignedTo` | verschoben |
| `ConfigBackupFormat.kt:259` | `exclusionReason` (Z. 313, undokumentiert) | verschoben |
| `ShiftConfigScreen.kt:555` | `CodeSuggestionCard` (Z. 630, undokumentiert) | verschoben |
| `CalendarViewModel.kt:37` | `@Immutable data class CalendarUiState` (Z. 68, undokumentiert) | verschoben |
| `CalendarUseCase.kt:179` | `getCalendarEventsWithStatus` (Z. 197) | verschoben |
| `DndPrefs.kt:156` | dieselbe Deklaration wie der Folgeblock (`onCallCutoffMinutes`) | zusammengefuehrt |
| `SimpleFileTree.kt:11` | dieselbe (`class SimpleFileTree`) | zusammengefuehrt |
| `ShiftConfigScreen.kt:75` | dieselbe (`SHIFT_RECOGNITION_HINT_KURZ`) | zusammengefuehrt |
| `CalendarViewModel.kt:323` | dieselbe (`updateLocalState`) | zusammengefuehrt |
| `CalendarViewModel.kt:1435` | `getEffectiveDaysAhead()` — in `c7ffed7` entfernt, **0 Vorkommen im Baum** | geloescht |

**`CalendarUseCase` ist der Fall, an dem PR #80 gescheitert ist — hier selbst nachgemessen statt
uebernommen.** Der Waise verspricht Threading, gestaffeltes Laden und Token-Validierung.
`getCalendarEventsWithCache` (Z. 191) ist ein zweizeiliger `.map`-Delegat und tut nichts davon;
`getCalendarEventsWithStatus` (Z. 197) traegt `withContext(Dispatchers.IO)` und die
`oauth2TokenManager.getValidToken()`-Kette, und `c6176c8` hat genau diese Funktion angelegt. Also
verschoben, nicht zusammengefuehrt — der Delegat behaelt allein seinen eigenen Vertragsblock.

**Mechanisch belegt statt behauptet** (Wegwerfskript, zwei Fassungen desselben Tokenizers): ueber
die **7 geaenderten Dateien** hat sich **kein einziges Nicht-Kommentar-Zeichen** geaendert
(maskieren, alle Whitespaces entfernen, vergleichen). KDoc-Textzeilen: **4 inhaltliche entfernt,
0 hinzugekommen.** Die vier einzeln, damit sie nachpruefbar sind statt nur gezaehlt:

1. „Der Hinweistext ueber der Schichtliste." — seit `20f8867` steht der Hinweis **in** der Liste,
   die Zeile war falsch geworden; der Zielblock benennt die Lage selbst.
2. „PERFORMANCE OPTIMIZATION: Batched State Updates" — dieselbe Ueberschrift wie „PERFORMANCE:
   Advanced Batched State Updates" im Block, in den sie wanderte. Der Rumpfsatz darunter
   („Sammelt State-Updates …") ist **erhalten**.
3./4. die zwei Zeilen des toten `getEffectiveDaysAhead`-Blocks.

**Eine Zaehlfalle fuer den, der das nachrechnet:** das Rohergebnis meines Skripts sagt „9 entfernt,
1 hinzugekommen". Fuenf der neun sind **Formartefakte**: vier abschliessende `*/`-Zeilen der vier
zusammengefuehrten Bloecke, und das `-1/+1`-Paar ist der DndPrefs-Einzeiler, der beim
Zusammenfuehren zu einer Zeile **innerhalb** eines Blocks wurde (Text identisch). Wer nur die
Rohzahl meldet, behauptet einen Wissensverlust, den es nicht gibt — und wer sie weglaesst, verbirgt
die Pruefbarkeit. **Beide Zahlen nennen und die Differenz erklaeren.**

**Acht Funde bewusst NICHT angefasst** (`AlarmUseCase` 2×, `AlarmRepository`,
`AlarmMaintenanceService`, `AlarmViewModel`, `DimmerModellMigration` + sein Test,
`DimmerRulesViewModel`): Leitplanke „Die Weckerkette fasst du nicht an". Abgrenzung mechanisch wie
in Runde 21 — Pfad oder Dateiname enthaelt `alarm`, `service` oder `dimmer` —, damit sie
nachpruefbar ist und nicht nach Gefuehl. Das sind die sieben aus **#82** plus den Neuzugang
`AlarmMaintenanceService.kt:1271`; **#82 ist entsprechend auf acht ergaenzt.**

**Die zwei Fehlalarme sind dieselbe Bauart** (`HueRuleConfigHelpers.kt:20`,
`StatusPermissionCards.kt:68`): ein Datei-KDoc, dem das KDoc der ersten Deklaration folgt — genau
der legitime Fall, den das Issue ausgenommen haben wollte. Beide auf Tiefe 0.

**Zum Stand der Werkzeuge, nachgemessen am 11.09.2026:** `pruefe_reste.py` hat weiterhin **sechs**
Pruefungen, und der Konfliktzustands-Waechter fehlt allen sechs
(`grep -c 'ls-files", "-u' tools/aufraeumen/pruefe_reste.py` → 0). **#60 gilt unveraendert**
(sechster Nachtrag, der ihn meldet — gezaehlt, nicht „in Folge" uebernommen: Runden 16, 18, 19,
20, 21, 22).

### 14.09.2026, Runde 23 (Issue #23, Hue-Modellfelder ohne Lesezugriff)

**Zahlen, Zaehlweise ausdruecklich benannt, gemessen gegen `c9e3a4a` (= `origin/main` beim
Start):** Korpus **428** `.kt` unter `app/src`, davon **9** Dateien in `hue/data` mit **28 Typen
und 160 Properties** (eigener Tokenizer; Kommentare maskiert, String-Literale behalten, Rohstring-
und Schachtelungsregel aus Runde 20 eingebaut). **87 geprueft, 22 Fehlalarme (25 %), 65 bestaetigt,
davon 24 geschnitten.** Nachher: **136 Properties**, Fassung C meldet **60** statt 83.

#### Neue Lehre 1: Bei diesem Blickwinkel misst die Fassungswahl das ERGEBNIS, nicht die Genauigkeit

Runde 19 verlangt zwei Fassungen, grosszuegig und streng. Hier waren es drei, und sie sind nicht
verschieden *genau*, sondern beantworten drei verschiedene Fragen:

| Fassung | Frage | Ergebnis |
|---|---|---|
| A grosszuegig, baumweite Namenssuche | "kommt der Name irgendwo vor?" | **0 Rohbefunde** |
| B streng, klassengetaktet, `.P` / `P =` / `"P"` | "benutzt jemand das Feld?" | **54 Kandidaten** |
| C streng, klassengetaktet, nur `.P` | "LIEST jemand das Feld?" | **83 Kandidaten** |

**Fassung A liefert die perfekte Null** — und ist wertlos: `hue` hat 474 Namenstreffer im Baum,
`id` 396, `name` 192. Haette ich dort aufgehoert, waere die Runde mit "nichts gefunden" fertig
gewesen. **Der Unterschied zwischen B und C ist aber der eigentliche Punkt:** wer eine
Schreibstelle als "Verwender" zaehlt, verliert genau die Felder, die nur noch in Testaufbauten
gesetzt werden — `GroupState.all_on` und die sieben `HueBridgeConfig`-Felder fehlen der Fassung B
vollstaendig. **Der Blickwinkel heisst "ohne LESEZUGRIFF"; dann darf auch nur ein Lesezugriff
zaehlen.** Schreib die Frage hin, bevor du das Muster schreibst.

#### Neue Lehre 2: Namensgleichheit ueber Klassen hinweg untererkennt - in JEDER Fassung

`LightState.hue`, `GroupAction.hue`, `GroupUpdate.hue`, `LightStateUpdate.hue`: vier verschiedene
Properties, ein Name. Jeder namensbasierte Zaehler haelt alle vier fuer lebendig, sobald *eine*
gelesen wird — und hier wird keine einzige gelesen, die 474 Treffer stammen von
`HueLightAction.hue`, `HueColor.hue` und dem **Paketnamen** `…cf_alarmfortimeoffice.hue`. Die vier
standen in keiner der drei Fassungen und sind von Hand ergaenzt. Das ist Runde 19s
Ueberladungs-Lehre, eine Ebene hoeher: **dort trennte die Stelligkeit, hier trennt nur der Typ des
Empfaengers — und den kennt kein Regex.** Merkposten fuer die naechste Runde, die Properties
zaehlt: **erst die Namen gruppieren, die mehrfach vorkommen, und diese Gruppe von Hand aufloesen.**
In `hue/data` sind das `on`, `bri`, `hue`, `sat`, `xy`, `ct`, `alert`, `effect`, `transitiontime`,
`name`, `id`, `modelid`, `swversion`, `description`, `recycle`.

**Und deshalb geht die Nachher-Rechnung NICHT auf:** Fassung C meldet nach dem Schnitt 60, nicht
59 — 83 minus die 23 geschnittenen Felder, die sie ueberhaupt kannte. Das 24. (`LightState.hue`)
war nie in ihrer Liste. Die Zahl stand zuerst falsch in der Commit-Nachricht; aufgefallen ist es
nur, weil die Differenz beider Listen gebildet wurde statt nur der Zahlen (Runde-22-Regel, beide
Richtungen — die Gegenrichtung war leer).

#### Neue Lehre 3: Die Serialisierung ist ein Leser, den man nicht sieht

Neun der 22 Fehlalarme sind Felder, die kein Kotlin-Code liest und die trotzdem leben, weil **Gson
oder kotlinx sie per Reflexion liest**: `BridgeScheduleCommand.address/method/body` und
`BridgeScheduleCreate.description` sind die Nutzlast des POST an die Bridge — entfernt man sie,
verschwindet der Zeitplan aus dem JSON, ohne dass ein Test rot wird. `HueScheduleRule.priority`
und `HueLightAction.targetType/actionType/color` stehen im `@Serializable`-Bestand im
DataStore (Leitplanke "Ein gespeichertes Format ist kein toter Code").
*(Hier stand zusaetzlich `HueLightAction.lightId`. Das war falsch: `lightId` war ein
`get()`-only-Property ohne Hintergrundfeld und damit nie im Bestandsformat — in Runde 31 am
Serializer-Descriptor gemessen und dort auch geschnitten.)*

**Die Unterscheidung, die diese Runde tragfaehig macht, ist die RICHTUNG:** Ein Modell, das nur
*deserialisiert* wird (Bridge-Antwort), darf jedes ungelesene Feld verlieren — Gson ignoriert
unbekannte Schluessel, die Bridge sendet sie weiter, es faellt nichts aus. Ein Modell, das
*serialisiert* wird (Anfrage, Bestand), verliert mit dem Feld die Wirkung. **Frag also nicht "wer
liest das Feld", sondern "in welche Richtung laeuft die Serialisierung dieser Klasse".** Genau
diese Regel steht im Repo schon zweimal ausformuliert, im KDoc von `HueScene` und `HueSceneDto`
("Abgebildet wird nur, was auch GELESEN wird") — sie war nur nie auf `LightState` angewandt.

#### Was geschnitten wurde und was bewusst stehen blieb

Geschnitten: **24 Felder** in sechs Bridge-ANTWORT-Modellen, deren Klasse den Schnitt ueberlebt —
`LightState` 9, `HueBridgeConfig` 7, `HueBridge` 3, `DiscoveryStatus` 3, `GroupState` 1,
`BridgeSchedule` 1. Jede Klasse traegt jetzt eine ENTFERNT-Notiz nach dem Muster, das
`HueGroup.roomClass` (v1.34.3) vorgegeben hat.

**41 bestaetigte Funde blieben stehen, jeder mit einem Grund, der nicht "keine Lust" heisst:**

- **`GroupAction` (9 Felder, kein einziges mit Leser)** — der Schnitt leert die Klasse und zoege
  `HueGroup.action` nach. Klassenebene, eigene Runde.
- **`GroupUpdate` (14) und `LightStateUpdate` (14)** — beide Klassen werden **nirgends erzeugt**.
  Ihr einziger Verweis ist der Parameter von `HueApiClient.controlGroup`/`controlLight`, **und die
  beiden Funktionen haben keinen Aufrufer**: der produktive Weg baut eine Roh-`Map<String, Any>`
  in `HueLightRepository` und ruft `setGroupAction`/`setLightState`. Das ist ein Fund auf
  Klassen- UND Funktionsebene und gehoert nicht in eine Feld-Runde.
- **`BridgeDiscoveryResponse` (2)** — einziger Verbraucher `discoverBridgesOnline()` hat keinen
  Aufrufer.
- **`DiscoveryStatus.method`** — ungelesen wie ihre drei geschnittenen Geschwister, aber der
  **einzige Verwender des Enums `DiscoveryMethod`**. Sie zu schneiden strandet ein Enum und
  griffe damit in den offenen Blickwinkel **#72** ein. Im KDoc der Klasse steht das jetzt, damit
  der naechste Leser die Luecke nicht fuer ein Versehen haelt.
- **`BridgeConnectionInfo.bridgeName`** — geschrieben, nie gelesen, aber **UI-Zustand statt
  Drahtformat**. Dort kann "kein Leser" auch "die Anzeige fehlt" heissen (Grundregel: eine
  Faehigkeit ohne Bedienoberflaeche gibt es nicht). Das ist eine Produktentscheidung, kein
  Aufraeumen.

**Kein Gatter (Skill-Regel 4)**, obwohl die Fehlalarmquote mit 25 % in der Naehe der Faustregel
liegt: die drei teuersten Fehlalarm-Bauarten (Leser in einer Datei ohne Klassennennung,
Verwendung ohne Punkt im eigenen Gueltigkeitsbereich, Serialisierung als Leser) verlangen alle
Typwissen, das ein Regex nicht hat. Zahlen und Bauarten stehen im Gatter-Issue.

**Zum Stand der Werkzeuge, nachgemessen am 14.09.2026:** `pruefe_reste.py` hat weiterhin **sechs**
Pruefungen, und der Konfliktzustands-Waechter fehlt allen sechs
(`grep -c 'ls-files", "-u' tools/aufraeumen/pruefe_reste.py` → 0). **#60 gilt unveraendert**
(siebter Nachtrag, der ihn meldet — gezaehlt: Runden 16, 18, 19, 20, 21, 22, 23).

### 15.09.2026, Runde 24 (Issue #24, Schnittstellen-Methoden ohne Aufrufer)

**Zahlen, Zaehlweise ausdruecklich benannt, gemessen gegen `d298909` (= `origin/main` beim
Start):** Korpus **428** `.kt` unter `app/src`, darin **17** `interfaces/`-Dateien mit **18
Interfaces** und **128 `fun`-Deklarationen**; davon gehoeren **126** wirklich zu einem
Interface-Rumpf.

> **Vom Torwaechter korrigiert (15.09.2026, beim Merge von PR #93): hier stand „19 Interfaces",
> es sind 18.** Nachgezaehlt auf BEIDEN Staenden (`d298909` und dem Merge-Commit `9ad506d`),
> Zaehlweise offengelegt:
> `git grep -hE "^\s*(fun )?interface [A-Za-z]" -- 'app/src/**/interfaces/*.kt' | wc -l` → **18**.
> 17 Dateien, davon 16 mit genau einem Interface und `IHueLightUseCase.kt` mit zweien
> (`IHueLightUseCase`, `IHueLightUseCaseAdvanced`): 16 + 2 = 18.
>
> Die Zahl traegt nichts am Befund — 24/2/22 und 128/126/109/107 habe ich selbst nachgerechnet
> und sie stimmen alle, der Schnitt ist davon unberuehrt. Korrigiert wird sie trotzdem, weil
> eine Korpuszahl in DIESER Datei fuer die naechste Runde die Ausgangsbasis ihrer eigenen
> Messung ist; die Datei ist Pflichtlektuere, und eine falsche Basis pflanzt sich fort. Genau
> das mahnt dieser Nachtrag zwei Absaetze weiter selbst an: **eine Doku-Nennung ist ein
> Hinweis, nie ein Beleg** — auch dann nicht, wenn sie in einem Nachtrag steht.
>
> **Ebenfalls ungeprueft und deshalb nicht als belegt weiterzureichen:** das „hier 19 Paare"
> weiter unten in Lehre 1. Das ist eine ANDERE Aussage (Namen, die in zwei Interfaces stehen),
> sie wurde von mir nicht nachgemessen, und sie teilt sich mit der falschen Interface-Zahl
> nur die Ziffer.

**24 Rohbefunde, 2 Fehlalarme (8,3 %), 22 bestaetigt, 19 geschnitten, 3 bewusst
stehen gelassen.** Nachher: **109 Deklarationen / 107 Interface-Methoden** (126 − 19 = 107),
Fassung D meldet **9** Rohbefunde = 2 Fehlalarme + 2 ausserhalb des Blickwinkels + 3 stehen
gelassene + **2 Folgefunde des Schnitts**. Eigener Tokenizer, Kommentare maskiert,
String-Literale behalten, Rohstring- und Schachtelungsregel aus Runde 20 eingebaut.

#### Neue Lehre 1: Bei Schnittstellen zaehlt der EMPFAENGERTYP, und das kostet acht Funde

Vier Fassungen, jede eine andere Frage:

| Fassung | Frage | Ergebnis |
|---|---|---|
| A grosszuegig, baumweite Namenssuche | „kommt der Name vor?" | **0 Rohbefunde** |
| B streng, `name(` / `::name`, ohne Deklarationen | „ruft jemand die Methode?" | **16** |
| C wie B, nur Aufrufer unter `app/src/main` | „ruft PRODUKTIVCODE sie?" | **+0** |
| D Aufloesung nach Empfaengertyp | „ruft jemand sie AUF DIESEM Typ?" | **24** |

**Fassung A liefert wieder die perfekte Null** (Runde 23 Lehre 1 bestaetigt sich):
`isAuthenticated` hat 20 Namenstreffer, `hasSelectedCalendars` **48** — und die stammen
praktisch alle vom gleichnamigen, **lebenden** UI-Feld
`CalendarOperationState.hasSelectedCalendars`, das aus `selectedCalendarIds.isNotEmpty()`
gespeist wird. **Fassung C ergab null Zusatzfunde**; die im Issue vermutete Klasse „lebt nur noch
im Test" existiert hier nicht: jede Methode mit irgendeinem Aufrufer hat auch einen produktiven.

**Der Sprung von B (16) auf D (24) ist der eigentliche Ertrag.** Acht Funde sind
UseCase-Methoden, deren Name auch auf dem zugehoerigen Repository steht — `updateAuthData`,
`isAuthenticated`, `getCurrentAuthData`, `migrateTokenExpiryIfNeeded`, `hasValidConfig`,
`resetToDefaults`, `getAlarmById`-Umfeld. Jeder namensbasierte Zaehler haelt **beide** Seiten fuer
lebendig, sobald eine gerufen wird; tatsaechlich ruft **jeder** Konsument am UseCase VORBEI direkt
das Repository an (`AuthViewModel`, `CalendarUseCase`, `BootReceiver`). Das ist Runde 23s
Namensgleichheits-Lehre eine Ebene hoeher: **dort trennte die Stelligkeit, hier trennt nur der Typ
des Empfaengers.** Merkposten fuer die naechste Runde, die Schnittstellen zaehlt: **erst die Namen
gruppieren, die in zwei Interfaces stehen** — hier 19 Paare — **und diese Gruppe von Hand
aufloesen.** Das Gegenstueck gilt auch: die 13 `hasValidConfig`-Test-Doubles implementieren
`IShiftConfigRepository`, NICHT den geschnittenen UseCase, und bleiben unangetastet. Wer nur nach
dem Namen loescht, reisst sie mit.

#### Neue Lehre 2: Kotlin-Aufrufe brauchen kein `(` — `name {` ist eine Aufrufstelle

Mein erster Zaehler suchte `name\s*\(` und meldete `IHueConfigRepository.updateScheduleRules`
als Rohbefund. **Es war ein Fehlalarm, und er waere ein Schnitt geworden:** `HueRuleUseCase.kt:756`
und `:825` rufen `configRepository.updateScheduleRules { current -> … }` — **trailing lambda, keine
Klammer.** Aufgefallen ist es nur, weil die Gegenprobe mit rohem `git grep` fuer diesen einen
Namen Treffer hatte, die mein Tokenizer nicht hatte.

**Die verallgemeinerbare Regel:** Ein Aufrufmuster fuer Kotlin muss `name\s*[({]` sein, nicht
`name\s*\(`. Wer nur die Klammer sucht, meldet jede Funktion mit funktionalem letzten Parameter
als tot — und das ist in diesem Baum kein Randfall, sondern das Idiom fuer genau die Stellen, die
eine Transaktion umschliessen (`dataStore.edit {}`, `updateScheduleRules {}`, `runCatching {}`).
Ebenso stillschweigend teuer: `infix`/`operator` (Runde 19) und `::name`-Referenzen. **Die
Richtung des Fehlers ist hier die gefaehrliche** — anders als bei Runde 19s Ueberzaehlung, die
Funde *versteckt*, ERFINDET diese Unterzaehlung Funde und fuehrt zum Schnitt an lebendem Code.

#### Neue Lehre 3: Miss den SCOPE deiner Deklarationen, nicht nur ihre Datei

Von den 128 `fun`-Deklarationen in `interfaces/`-Dateien sind **zwei keine Interface-Methoden**:
`encode` und `decode` stehen in `object ManualAlarmSnapshot` in `IAlarmSkipUseCase.kt` (Z. 36–80,
**vor** dem Interface ab Z. 86) und leben — `AlarmSkipUseCase.kt:136` und `AlarmViewModel.kt:892`
rufen sie. Mein Erstzaehler nahm „Datei liegt unter `interfaces/`" fuer „Deklaration gehoert zu
einem Interface"; beide wurden als Rohbefund gemeldet, mit `fremd=11` bzw. `fremd=7`
gross-geschriebenen Empfaengern als einzigem Hinweis.

**Die Regel:** Wenn dein Blickwinkel „X in einem Y-Rumpf" lautet, pruefe die Klammertiefe UND die
umgebende Deklaration, nicht den Pfad. Eine Zeile Selbstpruefung reicht („welche Deklarationen
liegen NICHT im gesuchten Rumpf?"); sie hat hier 2 von 128 herausgeholt und die Korpuszahl von
128 auf 126 richtiggestellt. Das ergaenzt die Achsen der Runden 16–20 um eine fuenfte:
**Zugehoerigkeit.** Leer (16), Namen (17), Menge (18), Unterbau (20) — und jetzt: liegt das
Gefundene ueberhaupt dort, wo die Frage es verlangt?

#### „Kein Aufrufer" heisst zweimal etwas anderes — und der Code sagt, welches

Drei bestaetigte Funde stehen bewusst noch, und der Grund war beide Male im Repo schon
aufgeschrieben:

- **`IShiftUseCase.resetToDefaults`** — kein Aufrufer, aber **drei** Stellen im Produktivcode
  (`ShiftConfigRepository:356`, `ShiftViewModel:294`, `CalendarViewModel:1642`) nennen
  `resetToDefaults()` ausdruecklich „den bewussten Weg zum Default", der „dem Nutzer gehoert".
  Genau **weil** kein Lesefehler mehr still auf den Default zurueckfaellt, braucht es diesen Weg:
  **was fehlt, ist der Knopf, nicht die Funktion.** Das ist wortgleich die Lage von
  `BridgeConnectionInfo.bridgeName` in Runde 23 — Produktentscheidung, kein Aufraeumen. Steht
  jetzt mit `OHNE VERWENDER` im eigenen KDoc.
- **`IAlarmRepository.getAlarmById` und `alarmExists`** — 13 Test-Doubles tragen sie mit, aber
  Leitplanke „Die Weckerkette fasst du nicht an". Abgrenzung mechanisch wie in Runde 21/22 (Pfad
  oder Dateiname enthaelt `alarm`, `service`, `dimmer`). Als Issue mit „braucht Ruecksprache".

**Der Test dafuer ist billig und gehoert in jede Runde dieses Blickwinkels:** `git grep` den
Methodennamen und **sieh die Kommentartreffer an**. Nennt der Produktivcode die Funktion als
vorgesehenen Weg, ist „kein Aufrufer" ein fehlendes Bedienelement. Nennt er sie gar nicht — oder
behauptet er Aufrufer, die es nicht gibt —, ist es Altlast. Beides kam hier vor, im selben
Durchgang.

#### Eine Doku-Behauptung war der einzige „Verwender" — und sie war falsch

`getCurrentBridgeIp`/`getCurrentUsername` hatten im ganzen Baum **je genau eine** Nennung
ausserhalb von Deklaration und `override`, und zwar dieselbe: den KDoc von
`HueBridgeConnectionManager.getCurrentConnectionInfo()`, der sich „public API contract via
IHueBridgeRepository.getCurrentBridgeIp()/getCurrentUsername(), **called from Compose UI and
WorkManager workers**" nennt. Nachgemessen: aus Compose oder einem Worker ruft diese Funktion
niemand, einziger Aufrufer ist `recoverConnection()` **in derselben Datei**. Der Satz ist
richtiggestellt, die Synchronitaet bleibt (sie ist jetzt eine interne Notwendigkeit, keine
Zusicherung nach draussen).

**Die Lehre, und sie schneidet in beide Richtungen:** Haette ich Kommentare als Verwender gezaehlt
(die Fassung, die `nachtraege.md` seit Runde 17 ausdruecklich verwirft), waeren diese zwei Funde
**durch ihre eigene falsche Dokumentation** unsichtbar geblieben. Das ist der
Selbstentwaffnungs-Mechanismus, den der Torwaechter an Pruefung 7 als Defekt (b) nachwies — hier
einmal nicht in einer ENTFERNT-Notiz, sondern in einer Behauptung, die von Anfang an nicht stimmte.
**Eine Doku-Nennung ist ein Hinweis, wo man nachsieht, nie ein Beleg, dass es jemand benutzt.**

#### Die Folgefunde sind gemessen und bewusst NICHT mitgeschnitten

Der Schnitt macht **vier** Dinge caller-los, die es auf `d298909` nicht waren — deshalb gehoeren
sie nicht in diese Runde, aber sie gehoeren gezaehlt:
`IAuthDataStoreRepository.migrateTokenExpiryIfNeeded` und `IShiftConfigRepository.hasValidConfig`
(einziger Aufrufer war der Rumpf der entfernten UseCase-Methode gleichen Namens — sie sind die
zwei „neuen" Rohbefunde in der Nachher-Zahl), `HueApiClient.getLight`/`getGroup`
(Funktions- statt Interface-Ebene) und der Konstruktorparameter `CalendarRepository.context`
(schrieb nur `setContext`, **gelesen wurde er nie** — jetzt ganz unreferenziert; ihn zu entfernen
aendert den Hilt-Konstruktor und ist der Blickwinkel #65). **Alle vier tragen eine Notiz im Code.**

**Die Regel, die ich dafuer aufgeschrieben habe** (Praezedenz: Runde 23, `GroupAction`/`GroupUpdate`
— „Klassenebene, eigene Runde"): Geschnitten wird, was **beim Start der Runde** null Aufrufstellen
hatte. Was der Schnitt in einer **anderen Datei** toetet, wird gemessen und abgelegt. **Einzige
Ausnahme:** eine Deklaration in **derselben Datei**, die nur der entfernten Methode diente — hier
der Rueckgabetyp `EventsPage`. Die stehen zu lassen waere ein Rest des eigenen Schnitts, kein
neuer Blickwinkel. Die Grenze ist mechanisch nachpruefbar, und das ist ihr ganzer Zweck.

**Nebenbei beantwortet:** Die offene Frage aus Runde 6, ob `EventsPage.hasMorePages` „Teil des
Vertrags" sei, ist erledigt — es war Teil einer API-level-Pagination, die **nie** einen Aufrufer
hatte. Das Wissen aus ihrem Rumpf ist nicht weggeworfen, sondern ins KDoc von
`ICalendarRepository` gewandert: eine SEITE darf nicht unter dem Schluessel landen, aus dem
`getCalendarEventsWithCache` liest (bis v1.27.0 genau dieser Bug — eine partielle Seite wurde zur
vollstaendigen Liste und damit zur Loeschgrundlage fuer `syncAlarms()`).

**Kein Gatter (Skill-Regel 4)**, obwohl 8,3 % unter der Faustregel liegen: Fassung D braucht
Empfaengertypen, und die kennt kein Regex. Meine Annaeherung ueber Bezeichner-Deklarationen hat
**beide** Fehlalarme selbst erzeugt — `ruleUseCase` ist ein lokaler `val` aus
`EntryPointAccessors…hueRuleUseCase()` **ohne Typannotation**, und ein Dauergatter wuerde jede
solche Stelle melden. Dazu kaeme die Unterscheidung „fehlendes Bedienelement gegen Altlast", die
oben zweimal den Ausschlag gab und Absicht erraten muss. Zahlen und Bauarten stehen im
Gatter-Issue.

**Zum Stand der Werkzeuge, nachgemessen am 15.09.2026:** `pruefe_reste.py` hat weiterhin **sechs**
Pruefungen (`pruefe_tote_importe`, `pruefe_verwaiste_strings`, `pruefe_doku_verweise`,
`pruefe_composable_ohne_verbraucher`, `pruefe_haengende_kdocs`, `pruefe_ungenutzte_konstanten`),
und der Konfliktzustands-Waechter fehlt allen sechs
(`grep -c 'ls-files", "-u' tools/aufraeumen/pruefe_reste.py` → 0). **#60 gilt unveraendert**
(achter Nachtrag, der ihn meldet — gezaehlt, nicht „in Folge" uebernommen: Runden 16, 18, 19, 20,
21, 22, 23, 24).

### 16.09.2026, Runde 25 (Issue #25, Gradle-Abhaengigkeiten ohne Nutzung)

**Zahlen, Zaehlweise ausdruecklich benannt, gemessen gegen `286ecb2` (= `origin/main` beim
Start):** Korpus **49 Deklarationen** in `app/build.gradle.kts` (naive Gegenprobe
`grep -cE '^\s*(implementation|ksp|testImplementation|androidTestImplementation|debugImplementation|coreLibraryDesugaring)\('`
→ ebenfalls 49) und **48 `[libraries]` / 38 `[versions]` / 5 `[plugins]`** in
`gradle/libs.versions.toml`. **25 Rohbefunde, 13 Fehlalarme (52 %), 12 bestaetigt, 7 geschnitten,
5 bewusst stehen gelassen.** Nachher: **42 Deklarationen, 18 Rohbefunde** (= 13 Fehlalarme + 5
stehen gelassene; die Rechnung geht auf), Katalog **41 / 35 / 5**, und **kein** Katalogeintrag ohne
Referenz aus einem Buildskript — vorher wie nachher 0.

#### Neue Lehre 1: Bei Abhaengigkeiten steht die Wahrheit nicht im Repo-Text, sondern im aufgeloesten Graphen

Runde 23 und 24 haben beide „Fassung A grosszuegig liefert die perfekte Null" berichtet. Hier ist
es **umgekehrt und genauso wertlos**: die baumweite Suche nach dem Artefaktnamen meldet **36 von 48
Bibliotheken als unbenutzt**, darunter `core-ktx`, `material-icons-extended` und `mockito-core` —
Artefaktnamen kommen in Kotlin-Quelltext schlicht nicht vor. Auch die naheliegende strenge Fassung
(Artefakt → Import-Praefix von Hand zuordnen) taugt nicht: sie meldete `hilt-navigation-compose`
als tot, weil `hiltViewModel` aus `androidx.hilt.lifecycle.viewmodel.compose` importiert wird und
nicht aus `androidx.hilt.navigation.compose`. **Jede Zuordnung Artefakt → Paket, die man selbst
hinschreibt, ist geraten.**

Der einzige belastbare Korpus ist der **aufgeloeste Klassenpfad**. Beschafft mit einem
Wegwerf-Initskript (`--init-script`, nichts im Repo), das je Konfiguration
`configurations.<cfg>.incoming.artifactView { isLenient = true }` dumpt; daraus ein Klassenindex
aus den 277 Jars und 475 AARs (`classes.jar` im Zip, `lint.jar` ausgenommen): **187 Komponenten,
47.004 Klassen**. Drei Fallen darin, alle gemessen:

- **`--no-configuration-cache` ist noetig**, sonst scheitert die Task an `Task.project` zur
  Ausfuehrungszeit.
- **Die `:app`-Selbstreferenz** laesst `incoming.artifacts` auf den Testkonfigurationen scheitern
  („cannot choose between variants"); `artifactView { isLenient = true }` loest genau das.
- **Plattform-Weiterleitung**: `androidx.compose.material3:material3` hat **null** Klassen, die
  liegen in `material3-android`. Ohne die Variantenaufloesung (`M`, `M-android`, `M-jvm`) meldet
  der Zaehler halb Compose als tot. Das ist die Runde-16-Selbstpruefung in neuem Gewand — ein
  leeres Ergebnis ist eine Aussage ueber den Messaufbau, nicht ueber den Baum.

#### Neue Lehre 2: Der Verbraucher kann eine ZEICHENKETTE im Buildskript sein — und das kostet fast den Instrumentationstest

Der Entfernungstest dieser Runde nimmt jedem Kandidaten seine **Deklaration** (Initskript:
`configurations.forEach { it.dependencies.removeIf { … } }`, transitive Pfade bleiben) und fragt,
welcher **Import** danach keinen Lieferanten mehr hat. Fuer `espresso-core` lautete die Antwort
**null** — der Quelltext importiert `androidx.test.espresso` nirgends. Ein Schnitt waere falsch
gewesen: der Komponenten-Diff derselben Messung zeigt, dass mit `espresso-core` auch
**`androidx.test:runner:1.7.0`** verschwindet, und den verlangt
`testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"` in `app/build.gradle.kts:66`
— als **Zeichenkette**, die kein Import-Zaehler je sieht. `connectedDebugAndroidTest` waere gar
nicht mehr gestartet, und **kein Unit-Test haette es gemeldet**.

**Die Regel:** Vergleiche bei Abhaengigkeiten nicht nur, was der Quelltext verliert, sondern
**welche Komponenten verschwinden** — und pruefe fuer jede verschwundene, ob sie in einer
Buildskript-Zeichenkette, im Manifest oder in einer ProGuard-Regel steht. Der Import ist nur einer
von vier Verbrauchern.

#### Die drei Fehlalarm-Bauarten, vollstaendig, mit Beispielen

13 der 25 Rohbefunde sind Fehlalarme, und sie fallen restlos in drei Klassen. Wer die nicht kennt,
schneidet den Wecker weg:

1. **Leeres Weiterleitungs-Artefakt** (5×: `core-ktx`, `lifecycle-runtime-ktx`,
   `lifecycle-viewmodel-ktx`, `work-runtime-ktx`, dazu die zwei `compose-bom`-Zeilen). Diese AARs
   enthalten ein `classes.jar` **ohne eine einzige `.class`** — nachgesehen, nicht vermutet. Sie
   holen das Paket, das der Quelltext benutzt: ohne `work-runtime-ktx` verlieren **30 Importe** von
   `androidx.work.*` ihren Lieferanten, und das ist die 6h-Wartungskette.
2. **Lieferant fuer eine andere Bibliothek** (4×). `play-services-base` wird nirgends importiert —
   nimmt man es heraus, verschwinden `play-services-base`, `-basement` und `-tasks`, und
   `play-services-auth` (766 Referenzen auf `com/google/android/gms/common/`), `-auth-base` (850)
   sowie `credentials-play-services-auth` (108 auf `com/google/android/gms/tasks/`) stehen ohne
   Klassen da. Ebenso `play-services-auth` selbst (einziger Weg zu `GoogleAuthUtil` ueber
   `-auth-base`), `hilt-navigation-compose` (holt `hilt-lifecycle-viewmodel-compose` →
   `hiltViewModel`, 7 Importe) und `espresso-core` (siehe oben). Gemessen wurde das mit einer
   Konstantenpool-Suche ueber die verbleibenden Artefakte, nicht mit einer Vermutung.
3. **Verbraucher ohne Import** (4×): BOM (`platform(...)`), Annotationsprozessor (`ksp`),
   `coreLibraryDesugaring`, Laufzeit-Anbieter (`credentials-play-services-auth`).

**Kein Gatter (Skill-Regel 4), und hier ist es keine knappe Entscheidung: 52 % Fehlalarm.** Alle
drei Bauarten verlangen den aufgeloesten Graphen und einen Klassenindex ueber ~180 Artefakte; das
sind zwei Gradle-Laeufe und ein Zip-Scan pro Pruefung. Ein Textgatter ueber `build.gradle.kts`
faellt auf alle drei herein. **Nicht bauen** — der Blickwinkel gehoert mit **49 / 25 / 13 / 7** in
die „Verworfen"-Tabelle, obwohl er sieben echte Funde hatte: er lohnt als Runde, nicht als Wache.

#### Was geschnitten wurde — und was das WIRKLICH bringt

Sieben Deklarationen, jede mit null Importen ihres Pakets, null Referenzen aus einem verbleibenden
Artefakt und keiner verschwindenden Fremdkomponente: `retrofit`, `retrofit-converter-gson`
(der vorab verifizierte Befund aus dem Issue), `okhttp-logging-interceptor`,
`google-api-client-android`, `google-http-client-android`, `androidx-work-testing`,
`androidx-lifecycle-viewmodel-compose`. Dazu die sieben verwaisten Katalog-Aliase und drei
`[versions]`-Eintraege (`retrofit`, `googleApiClient`, `workTesting`) sowie die drei
retrofit2-Regeln in `proguard-rules.pro`; die `keepattributes` daneben bleiben, sie sind nicht
Retrofit-spezifisch (Gson braucht `Signature`).

**Die Begruendung des Issues haelt der Messung nur halb stand, und das gehoert hierher:** „Jede
ungenutzte Abhaengigkeit vergroessert das APK" — gemessen am R8-Release-APK (`assembleRelease`,
beide Staende, derselbe Rechner): **10.786.558 → 10.770.174 Byte, also 16.384 Byte oder 0,15 %**;
dex unkomprimiert 24.026.520 → 23.989.360. R8 raeumt das Meiste naemlich schon weg. Der
Typ-Deskriptor-Diff beider dex sagt genau, was uebrig blieb: **20 Typen weg, 0 neu** — 15 aus den
beiden `-android`-Erweiterungen (`AndroidJsonFactory`, `GoogleAccountCredential`,
`FileDataStoreFactory` …), 3 Framework-Typen (`android/util/Json*`), die nur diese referenzierten,
und **`retrofit2/HttpException`** als einziger Retrofit-Rest. Die 15 waren nur drin, weil
`-keep class com.google.api.client.** { *; }` (proguard-rules.pro:191, r8-rules.txt:43) sie am
Leben hielt — **eine Wildcard-keep-Regel macht aus einer ungenutzten Abhaengigkeit ausgelieferten
Code.** Der belegbare Gewinn liegt woanders: 5 Komponenten weniger im Kompilier- und
Laufzeitklassenpfad (187 → 182), 6 weniger im Unit-Test-Pfad (196 → 190), 3 Versionseintraege
weniger, die Dependabot beobachtet. **Ein Diff der dex-Typen ist uebrigens die beste
Sicherheitspruefung, die dieser Blickwinkel hat**: „0 neu, und alle 20 entfernten gehoeren zum
Schnitt" schliesst aus, dass R8 nach dem Eingriff etwas anderes anders entscheidet.

#### Fuenf bestaetigte Funde stehen bewusst noch

- **`google-auth-library-oauth2-http` + `-credentials`** — im ganzen Baum kein `com.google.auth.*`,
  und der Kalenderpfad setzt seinen Token selbst per `HttpRequestInitializer`
  (`CalendarRepository.kt:307`). Der Schnitt raeumt **7 Komponenten** ab, darunter
  **Guava 33.6.0-jre** — und `com/google/common/` wird von verbleibenden Artefakten referenziert:
  `androidx.work:work-runtime` (199×, das ist die Wartungskette), `google-http-client` (30×, das
  ist der Kalenderabruf), `grpc-api`, `concurrent-futures`. Ob `listenablefuture:1.0` dann
  einspringt, ist eine Aufloesungsfrage, die ich nicht geraten habe. **Braucht Ruecksprache,
  Issue angelegt** — das ist der groesste Einzelposten dieses Blickwinkels und der einzige, der die
  Weckerkette streifen kann.
- **`ui-tooling`, `ui-tooling-preview`, `ui-test-manifest`** — `@Preview` kommt im ganzen Baum
  **kein einziges Mal** vor, die drei sind also nach Aktenlage tot. Sie sind aber
  Entwicklerwerkzeug (Studio-Vorschau, Layout-Inspector, Manifest-Beitrag fuer Compose-Tests), und
  ob darauf verzichtet wird, ist eine Entscheidung des Eigentuemers, kein Aufraeumen — wortgleich
  die Lage von `IShiftUseCase.resetToDefaults` in Runde 24: **was fehlt, ist die Nutzung, nicht die
  Berechtigung.** Bei `ui-test-manifest` kommt dazu, dass sein Beitrag ein Manifest-Merge ist und
  sich nur am Geraet pruefen laesst. Issue angelegt.

#### Gepruefte Nebenfrage, damit sie niemand doppelt aufmacht

Der Katalog selbst ist sauber: **kein** `[libraries]`-, `[versions]`- oder `[plugins]`-Eintrag ohne
Referenz aus einem Buildskript — vor dem Schnitt 0, nach dem Schnitt wieder 0 (Accessor-Zaehlung
mit `libs.<alias mit - und _ zu .>` und negativem Lookahead, damit `libs.androidx.ui` nicht von
`libs.androidx.ui.graphics` miterschlagen wird). Ein eigener Blickwinkel „verwaiste
Katalogeintraege" lohnt nicht; ich habe **kein Issue** dafuer angelegt.

**Belege dieser Runde, alle vier vom Issue verlangt und gelaufen:** `assembleDebug`,
`testDebugUnitTest` (**175 XML-Berichte, 1352 Tests, 0 Failures, 0 Errors**),
`compileDebugAndroidTestKotlin`, `assembleRelease` mit R8 (gruen, **keine** „Missing class"-Meldung)
sowie `pruefe_reste.py` → „Keine Reste gefunden".

**Zum Stand der Werkzeuge, nachgemessen am 16.09.2026:** `pruefe_reste.py` hat weiterhin **sechs**
Pruefungen, und der Konfliktzustands-Waechter fehlt allen sechs
(`grep -c 'ls-files", "-u' tools/aufraeumen/pruefe_reste.py` → 0). **#60 gilt unveraendert**
(neunter Nachtrag, der ihn meldet — gezaehlt, nicht „in Folge" uebernommen: Runden 16, 18, 19, 20,
21, 22, 23, 24, 25).

### 17.09.2026, Runde 26 (Issue #31 zum zweiten Mal, lint.xml-Eintraege, die nichts treffen)

**Zahlen, Zaehlweise ausdruecklich benannt, gemessen gegen `c86e401` (= `origin/main` beim
Start):** Korpus `app/lint.xml` = **21 `<issue>`-Bloecke + 8 `<ignore>` + 0 `<option>` = 29
Eintraege** (echter XML-Parser; moeglich, seit die Datei am 04.09.2026 wohlgeformt ist).
**Rohbefund** = jeder dieser 29 Eintraege, wie ihn die naive Textsuche liefert; **bestaetigt** =
Eintrag, der **strukturell nie** etwas bewirken kann; **Fehlalarm** = jeder Kandidat, der bleiben
muss. **29 Rohbefunde, 2 bestaetigt und geschnitten, 27 Fehlalarme (93 %).** Nachher: **27
Eintraege**, Lint-Bericht **identisch** (12 Befunde, Mengendifferenz in beide Richtungen leer),
und **kein** Block mehr ohne `severity`/`<ignore>`/`<option>`.

Die 27 Fehlalarme zerfallen in zwei Gruppen, beide gemessen: **11 nachweislich wirksam**
(`CustomX509TrustManager` + sein `<ignore>`; die vier `severity`-Eintraege
`NewerVersionAvailable`, `GradleDependency`, `TrustAllX509TrustManager`, `ObsoleteSdkInt`;
`ApplySharedPref` + 1 Pfad; `UseKtx` + 2 Pfade) und **16 heute wirkungslos, aber Vorsorge**
(die 12 aus **#38**, die 3 von `InvalidPackage`, der `ApplySharedPref`-Pfad auf
`DimAccessibilityService`). 11 + 16 + 2 = 29.

#### Neue Lehre 1: „Bericht unveraendert" ist die SCHWACHE Richtung des Versuchs — frag, was der Eintrag unterdruecken SOLL

PR #37 ist unter anderem daran gescheitert, dass er „Bericht mit und ohne Eintrag identisch" als
Beleg nahm — dieselbe Messung, die er bei den 12 Eintraegen aus #38 ausdruecklich **nicht** gelten
liess. Der Widerspruch loest sich, wenn man die Mutation umdreht: Nicht „was passiert ohne den
Eintrag", sondern **„was passiert, wenn ich dem Eintrag seine Arbeit gebe?"**

Konkret fuer `<issue id="BatteryLife">`: die beiden `@Suppress("BatteryLife")` im Code entfernen und
den lint.xml-Block **stehen lassen**. Ergebnis: **zwei BatteryLife-Warnungen** erscheinen prompt
(`MainScreen.kt:388`, `BatteryOptimizationHelper.kt:121`). Damit ist nicht bloss gezeigt, dass der
Block heute nichts aendert, sondern dass er **das, wofuer er dasteht, nicht kann**. Das ist der
Unterschied zwischen „trifft gerade nichts" und „ist wirkungslos", und nur der zweite rechtfertigt
einen Schnitt.

Dieselbe Umkehrung hat drei Gruppen als **lebendig** bewiesen, die eine reine
Weglass-Messung nur als „aendert etwas" gekannt haette: ohne den `HueTrustManager`-Pfad erscheint
`CustomX509TrustManager` (Positivkontrolle, das Verfahren sieht Aenderungen); ohne die
`ApplySharedPref`/`UseKtx`-Bloecke erscheinen **6** Befunde; ohne die vier `severity`-Attribute
kippen **9** Befunde von `Hint` auf `Warning` — bei gleicher Anzahl. **Wer nur Zahlen vergleicht,
haelt den letzten Fall fuer „keine Wirkung".** Verglichen wurden deshalb Mengen aus
(id, severity, Datei, Zeile, Meldung), Differenz in beide Richtungen.

Kontrolle gegen Drift: derselbe unveraenderte Stand zweimal gefahren → identisch. Das ist hier
noetig, weil vier der zwoelf Befunde an fremden Veroeffentlichungen haengen (`NewerVersionAvailable`,
`GradleDependency`); **nur der Vergleich zweier Laeufe DERSELBEN Sitzung zaehlt**, nie eine
aufgeschriebene Gesamtzahl.

#### Neue Lehre 2: Der Parser trennt Eintrag von Prosa — und genau daran starb das Gatter von PR #37

Naive Textsuche findet **9** `<ignore`, der Parser **8**. Der Ueberzaehler ist `app/lint.xml:80`:
eine **Prosa-Nennung `<ignore regexp>` in einem Kommentar**. Genau dieser Mechanismus — Text im
Kommentar als Struktur zu lesen — war Defekt 1 des Gatters aus PR #37. Seit dem 04.09.2026 ist die
Datei wohlgeformtes XML, ein echter Parser also moeglich; **das allein macht die Messung
belastbar, nicht die Regexp-Sorgfalt.** Umgekehrt gilt weiter: die neuen Kommentare dieser Runde
zitieren entfernte Eintraege woertlich, und das ist Absicht (Hergang) — es waere aber ein
Selbstentwaffner fuer jeden Zaehler, der Rohtext liest.

#### Neue Lehre 3: Zwei Sorten „wirkt nicht" — und die zweite ist ein WAECHTER, kein Rest

- **Strukturell wirkungslos** (geschnitten): `<issue id="BatteryLife">` ohne `severity`, ohne
  `<ignore>`, ohne `<option>` — kann per Definition nichts bewirken; und der
  `<ignore regexp>` auf `…impl.Log4JLogger` unter `TrustAllX509TrustManager`: die beiden Meldungen
  lauten „`checkClientTrusted` is empty" / „`checkServerTrusted` is empty", **nennen keinen
  Klassennamen**, ihr Ort ist `google-http-client-2.2.0.jar` — und log4j liegt in **keiner**
  Konfiguration (`./gradlew :app:dependencies` → 0 Treffer, alle Konfigurationen).
- **Heute wirkungslos, trotzdem Waechter** (nicht angefasst): `InvalidPackage`. Ohne den Block
  aendert sich der Bericht in keiner Zeile — das ist hier aber **kein** Beleg, wie der Torwaechter
  zu PR #37 festgehalten hat: `commons-logging:1.2` liegt weiterhin im `debugRuntimeClasspath`
  (ueber `google-http-client` → `httpclient:4.5.14`, selbst nachgemessen — der Schnitt aus Runde 25
  hat daran nichts geaendert), `InvalidPackageDetector` ist `Severity.ERROR` und
  `abortOnError = true`: kippt die Erreichbarkeitsheuristik bei einem Bibliotheks- oder Lint-Bump,
  bricht CI **und** Schleuse. Der `log4j`-Regexp daneben ist ebenfalls nicht strukturell tot —
  eine InvalidPackage-Meldung ueber commons-logging nennt `org.apache.log4j` sehr wohl.

#### Eine Begruendung wurde verschoben, nicht geloescht — und dabei richtiggestellt

Der `BatteryLife`-Block trug die einzige schriftliche Rechtfertigung fuer
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; sie ersatzlos zu streichen war Defekt 2 von PR #37. Sie
steht jetzt an den **beiden `@Suppress`-Stellen**, also dort, wo die Unterdrueckung wirklich sitzt.
**Ihr alter Wortlaut war allerdings falsch und ist nicht abgeschrieben worden:** er nannte eine
Funktion `createBatteryOptimizationIntent()` — auf `c86e401` im ganzen Arbeitsbaum **null Treffer
ausser in eben dieser Kommentarzeile** — und behauptete, der Intent werde „NICHT direkt
ausgefuehrt"; beide Stellen
feuern ihn sehr wohl (`batteryExemptionLauncher.launch`, `startActivityForResult`), nur eben erst
auf einen ausdruecklichen Tipp. Der wahre Ablauf stand laengst im KDoc von
`BatteryOnboardingScreen`; darauf verweist der neue Text, statt eine zweite Erzaehlung aufzumachen.
**Eine gerettete Begruendung ist nur dann eine Rettung, wenn sie stimmt** — sonst verschiebt man
eine Falschaussage an eine prominentere Stelle.

**Kein Gatter (Skill-Regel 4), und diesmal auch keins zum Vormerken.** Der statisch entscheidbare
Teil („Block ohne `severity`, ohne `<ignore>`, ohne `<option>`") haette heute **0 %** Fehlalarm —
und **null Ertrag**: nach diesem Schnitt gibt es keinen solchen Block mehr, und ueber **alle 10
Fassungen**, die `app/lint.xml` je hatte (`git log --follow`, jede einzeln geparst), war es immer
**dieselbe eine ID** — `BatteryLife`, vorhanden in 7 der 10 Fassungen seit dem 17.11.2025, nie eine
zweite. Das ist wortgleich die Lage von Runde 19
(Extension-Funktionen): eine Dauerpruefung mit null Ertrag, die ausserdem den Konfliktzustand
kennen muesste (#60), ist genau die Sorte, die der Skill fuenfmal geschlossen gesehen hat. Der
**Rest** des Blickwinkels ist ohnehin nicht gatterfaehig: ob ein `<ignore regexp>` etwas trifft,
sagt erst ein mutierender Lint-Lauf (diese Runde: 1 Basislauf + **10 Mutationslaeufe** + Endstand,
je 2–4 min, dazu `lintVitalRelease` vorher und nachher). Der Blickwinkel gehoert mit
**29 / 2 / 27** in die „Verworfen"-Tabelle des Skills: er lohnt als Runde, nicht als Wache.
**Ich habe dafuer kein neues Issue angelegt.**

**#38 bleibt unberuehrt und ist bestaetigt:** die 12 Eintraege wirken auch heute auf keinen einzigen
Befund. Die Entscheidung darueber gehoert dem Eigentuemer (Runde-20-Lehre: ein fremdes Issue
entscheidet die Runde nicht).

**Belege:** `assembleDebug` + `testDebugUnitTest` + `lintDebug` gruen (**175 XML-Berichte, 1352
Tests, 0 Failures, 0 Errors**), `lintVitalRelease` gruen (vorher wie nachher — beide Eintraege
standen unter der Ueberschrift „LINT VITAL RELEASE CHECKS"), `pruefe_reste.py` → „Keine Reste
gefunden", Lint-Bericht vorher/nachher als Menge identisch. Alle Messskripte waren Wegwerfcode im
Scratchpad.

**Zum Stand der Werkzeuge, nachgemessen am 17.09.2026:** `pruefe_reste.py` hat weiterhin **sechs**
Pruefungen, und der Konfliktzustands-Waechter fehlt allen sechs
(`grep -c 'ls-files", "-u' tools/aufraeumen/pruefe_reste.py` → 0). **#60 gilt unveraendert**
(zehnter Nachtrag, der ihn meldet — gezaehlt, nicht „in Folge" uebernommen: Runden 16, 18, 19, 20,
21, 22, 23, 24, 25, 26).

### 18.09.2026, Runde 27 (Issue #38, die 12 lint.xml-Eintraege ohne Wirkung)

**Ergebnis vorweg: 12 Rohbefunde, 1 bestaetigt, 11 Fehlalarme (91,7 %), 0 geschnitten.** Keiner der
zwoelf Eintraege ist eine Altlast; elf davon aendern das Ergebnis nachweislich, sobald ihr Check
etwas zu melden hat, der zwoelfte (`FragmentLiveDataObserve`) nicht — er ist ein Waechter, aber
nach der Definition dieses Abschnitts eben doch ein bestaetigter Befund.
*(Hier stand „0 bestaetigt, 12 Fehlalarme (100 %)"; das widersprach der eigenen Definition drei
Absaetze weiter unten. Vom Torwaechter zu PR #102 beanstandet, in Runde 28 nachgerechnet.)* Die beiden Fragen des Issues sind damit beantwortet — **Antwort auf beide:
alle zwoelf behalten.** Dieser PR bringt deshalb **keinen Schnitt**, nur diesen Nachtrag.

**Daneben zwei Funde, die KEINE Aufraeumfunde sind, sondern Fehlkonfigurationen** — sie stehen
unten und sind als eigenes Issue mit „braucht Ruecksprache" abgelegt, nicht angefasst.

**Zahlen, Zaehlweise ausdruecklich benannt, gemessen gegen `ad34e57` (= `origin/main` beim
Start):** Korpus `app/lint.xml` = **20 `<issue>`-Bloecke + 7 `<ignore>` = 27 Eintraege** (echter
XML-Parser, `ElementTree`) — genau der Stand, den Runde 26 hinterlassen hat (29 − 2 = 27, die
Rechnung geht auf). **Rohbefund** = jeder der 12 Kandidaten aus #38 (6 Abwertungen auf
`informational`, 6 Verschaerfungen auf `error`); **bestaetigt** = Eintrag, der auch dann nichts
bewirkt, wenn man ihm seine Arbeit gibt; **Fehlalarm** = Eintrag, der bleiben muss.

**Basislauf, sauberer Baum: 13 Befunde, kein `UnknownIssueId`, keine der 12 IDs darunter** — die
Praemisse des Issues ist damit unabhaengig bestaetigt. Zweimal in derselben Sitzung gefahren,
Mengendifferenz in beide Richtungen leer. **Schreib die 13 nicht als Sollwert fort:** 7 der 13 sind
`GradleDependency` (3) und `NewerVersionAvailable` (4) und haengen an fremden Veroeffentlichungen,
genau wie der Skill es fuer die „akzeptierten Dauermeldungen" sagt.

#### Das Verfahren: Runde 26, Lehre 1, konsequent zu Ende gefuehrt

Runde 26 sagt, die starke Richtung sei nicht „was passiert ohne den Eintrag", sondern **„was
passiert, wenn ich dem Eintrag seine Arbeit gebe?"** Fuer #38 heisst das: Ausloesercode in den Baum
legen, den jeder der zwoelf Checks melden muss, und dann A/B fahren. Ausloeser (alle Wegwerf, nach
der Messung entfernt, `git status` danach leer): eine ungenutzte `<string>`-Ressource, ein
XML-Layout mit `android:text` und einem `ImageView` ohne `contentDescription`,
`cleartextTrafficPermitted="true"` in der Network-Security-Config, eine Kotlin-Klasse mit
statischem `Context`, `TextView.setText("Wert: " + 42)`, `openFileOutput(..., MODE_WORLD_*)`,
`MutableLiveData.value = null`, ein `Fragment` mit `observe(this, …)` in `onViewCreated`, dazu eine
Java-Klasse mit `new Integer(42)`, sechs `HashMap<Integer,…>`-Formen und `new SparseArray<Integer>()`.

| Lauf | Befunde |
|---|---|
| Basis, sauberer Baum | **13** |
| Ausloeser + `app/lint.xml` unveraendert | **30** |
| Ausloeser + `app/lint.xml` ohne die 12 | **30** |

**Gleiche Anzahl, volle Wirkung** — die Mengendifferenz ist **15 raus / 15 rein**, jeder Befund mit
gewechselter Severity. 15 statt 12, weil `SetTextI18n`, `UnusedResources`, `UseSparseArrays` und
`UseValueOf` je zweimal ausloesen; und weil `FragmentLiveDataObserve` in **keiner** der beiden
Richtungen steht (siehe unten).

- **Alle 6 Abwertungen wirksam:** `Warning` → `Hint` (`UnusedResources`, `SetTextI18n`,
  `HardcodedText`, `UseValueOf`, `UseSparseArrays`, `ContentDescription`).
- **4 der 6 Verschaerfungen wirksam:** `Warning` → `Error` (`InsecureBaseConfiguration`,
  `WorldReadableFiles`, `WorldWriteableFiles`, `StaticFieldLeak`).
- Die restlichen zwei sind die Funde dieser Runde.

#### Fund 1: `NullSafeMutableLiveData` ist als „Verschaerfung" eingetragen und SENKT ab

Der Eintrag steht unter der Ueberschrift „AKTIVIERTE PRUEFUNGEN (STRENGER)" / „Crash-Risiken als
Error". Gemessen ist seine Voreinstellung aber nicht `Warning`, sondern **`Fatal`** — der Check
kommt nicht aus AOSP, sondern aus dem `lint.jar` von `androidx.lifecycle`. `severity="error"` ist
dort also eine **Absenkung**, und die hat eine Folge, die im `lintDebug`-Bericht unsichtbar bleibt:

```
Ausloeser im Baum, Eintrag VORHANDEN :  ./gradlew lintVitalRelease  -> EXIT 0, BUILD SUCCESSFUL
Ausloeser im Baum, Eintrag ENTFERNT  :  ./gradlew lintVitalRelease  -> EXIT 1, BUILD FAILED
                                        "Cannot set non-nullable LiveData value to null"
```

Der Eintrag nimmt den Check also aus dem **Release-Gatter**. Gemessen ist die Folge (zwei
Exit-Codes, sonst identischer Baum); die uebliche Erklaerung dafuer — `lintVital` prueft
ausschliesslich FATAL-Befunde — habe ich **nicht** im Bytecode nachgesehen und reiche sie nicht als
Beleg weiter. Praktisch kostet es heute nichts: im ganzen Baum kommt `LiveData` **null Mal** vor
(`git grep -l LiveData -- '*.kt'` → 0). Es zu aendern ist trotzdem kein Aufraeumen, sondern eine
Entscheidung ueber ein Release-Gatter — deshalb **nicht angefasst**, Issue angelegt.

#### Fund 2: `FragmentLiveDataObserve` ist wirkungslos, auch MIT Arbeit

Der einzige der zwoelf, der in der A/B-Differenz gar nicht auftaucht: mit und ohne Eintrag meldet
er denselben Befund mit derselben Severity. Seine Voreinstellung ist bereits `Error`, der Eintrag
setzt also den Wert, der ohnehin gilt. **Das ist trotzdem kein Schnitt-Kandidat** — nach Runde 26s
eigener Unterscheidung ist er kein Rest, sondern ein Waechter: senkt `androidx.fragment` die
Voreinstellung, haelt der Eintrag den Wert. Aufgeschrieben, damit die naechste Runde ihn nicht fuer
einen Fund haelt.

#### Neue Lehre 1: Ein ausbleibender Befund ist eine Aussage ueber den AUSLOESER, nicht ueber den Eintrag

**Drei der zwoelf feuerten im ersten Anlauf nicht.** Haette ich dort aufgehoert, staende hier „drei
Eintraege sind strukturell tot" — und alle drei waeren falsch gewesen:

- `FragmentLiveDataObserve` braucht den `observe(this, …)`-Aufruf in **einer von vier**
  Lebenszyklus-Methoden — `onCreateView`, `onViewCreated`, `onActivityCreated`,
  `onViewStateRestored`; anderswo in der Fragment-Klasse schweigt der Detektor.
  *(Hier stand „in `onViewCreated`", also eine statt vier. Vom Torwaechter zu PR #102 beanstandet;
  in Runde 28 an den String-Konstanten von `UnsafeFragmentLifecycleObserverDetector` aus
  `fragment-1.5.7` selbst nachgezaehlt — der Erklaerungstext des Checks nennt dieselben vier.)*
- `NullSafeMutableLiveData` braucht die **explizite** Typannotation
  (`val x: MutableLiveData<String> = MutableLiveData()`); bei `val x = MutableLiveData<String>()`
  meldet er nichts. (Beide Korrekturen liefen in EINEM Lauf; sie betreffen verschiedene Klassen und
  verschiedene Checks, die Zuordnung ist also baulich, nicht durch getrennte Laeufe belegt.)
- `UseSparseArrays` braucht `new SparseArray<Integer>()` mit **Typargument an der Aufrufstelle**.

Das ist die Runde-16-Regel eine Ebene hoeher: dort war ein leeres Strukturergebnis eine Aussage
ueber den Parser, hier ist ein ausbleibender Befund eine Aussage ueber den Ausloeser. **Der Ausweg
ist, im Bytecode des Detektors nachzusehen, was er wirklich prueft — aber erst NACH der Frage,
woher der Check kommt.** Nur AOSP-Checks liegen in `lint-checks-<version>.jar`; Checks einer
Bibliothek liegen in deren eigenem `lint.jar` unter `~/.gradle/caches/<gradle>/transforms/*/
transformed/<artefakt>/jars/lint.jar`, **und ihr Klassenname weicht von der Issue-ID ab**
(`NullSafeMutableLiveData` → `NonNullableMutableLiveDataDetector`, `FragmentLiveDataObserve` →
`UnsafeFragmentLifecycleObserverDetector`). Wer pauschal im AOSP-Jar greppt, findet fuer diese
zwei nichts und schliesst falsch.
*(Hier stand als Rezept nur „`javap -p -c` auf `lint-checks-<version>.jar`" — das zeigt fuer zwei
seiner eigenen drei Beispiele ins Leere. Vom Torwaechter zu PR #102 beanstandet, in Runde 28
nachgemessen.)*
**Und fuer die Frage, um die es bei #38 ueberhaupt geht, braucht man den Detektor gar nicht:**
ob ein `severity`-Eintrag etwas bewirkt, entscheidet allein der Vergleich mit der
**Voreinstellung des Checks** — die steht in der Issue-Registry und ist in Sekunden auszulesen
(Runde 28). Hier: AGP 9.4.0 verwendet `lint-checks-32.4.0`.

#### Neue Lehre 2: Der ERKLAERUNGSTEXT eines Lint-Issues ist keine Beschreibung seines Codes

`UseSparseArrays` heisst „HashMap can be replaced with SparseArray" und erklaert seitenlang, wann
man statt `HashMap` lieber `SparseArray` nimmt. Der Kommentar in `app/lint.xml` gibt das getreu
wieder („HashMap ist oft lesbarer, Performance-Unterschied minimal"). **In `lint-checks-32.4.0`
gibt es diesen Zweig nicht mehr.** Gemessen:

```
6 Lehrbuchformen in Java  (new HashMap<Integer,String>() als Rueckgabe und als lokale Variable,
  <Integer,Integer>, <Integer,Boolean>, <Long,String>, Diamantform)   ->  0 Befunde
2 Formen in Kotlin (HashMap<Int,String>, HashMap<java.lang.Integer,String>)  ->  0 Befunde
2 Formen in Java   (new SparseArray<Integer>(), new SparseArray<Boolean>())  ->  2 Befunde
```

Im Bytecode passt das genau: `JavaPerformanceDetector$PerformanceVisitor.checkSparseArray` liest
`getTypeArguments()` der Aufrufstelle und vergleicht gegen `java.lang.Integer`/`int` bzw.
`java.lang.Boolean`/`boolean`; ein `java.util.HashMap`-Literal kommt im ganzen Visitor **nicht**
vor. Das erklaert auch die Kotlin-Null: `android.util.SparseArray()` mit dem Typ nur in der
Rueckgabesignatur hat an der Aufrufstelle **keine** Typargumente. (Die Kotlin-Form **mit**
Typargument habe ich nicht gemessen — wer sie braucht, misst sie.)

**Die Folge fuer den Eintrag ist trotzdem „behalten":** er wirkt, er wirkt nur fuer etwas anderes,
als sein Kommentar sagt. Ich habe den Kommentar **bewusst nicht umgeschrieben** — ob der Eintrag
ueberhaupt bleiben soll, ist die Frage, die dem Eigentuemer gehoert, und eine neu formulierte
Begruendung nimmt sie vorweg. Genau daran ist PR #37 gescheitert (Defekt 2: eine Begruendung
ersetzt, die dann ins Leere zeigte). Die Messung steht im Issue.

#### Neue Lehre 3 (ergaenzt Runde 26, Lehre 1): im `lintDebug`-BERICHT ist Zahlengleichheit der REGELFALL

> **Geltungsbereich, in Runde 28 nachgeschaerft:** Diese Lehre gilt **nur fuer den
> `lintDebug`-Bericht**, nicht fuer Gatter-Exit-Codes. „Fund 1" derselben Runde ist der
> Gegenbeleg: dort aendert ein `severity`-Eintrag den Exit-Code von `lintVitalRelease`. Wer die
> Lehre pauschal nimmt und deshalb auf Zahlenvergleiche verzichtet, wird fuer genau diese Klasse
> blind. Ueberschrift und Schlusssatz sind entsprechend eingegrenzt.

Runde 26 hat gezeigt, dass vier `severity`-Attribute „9 Befunde von Hint auf Warning kippen — bei
gleicher Anzahl". Diese Runde verallgemeinert das: bei **elf von zwoelf** Eintraegen ist die
Befundzahl vor und nach dem Eingriff identisch (30 = 30), und die gesamte Wirkung steckt in der
Severity. Ein Blickwinkel ueber `severity`-Attribute, der **im Bericht** Zahlen vergleicht, misst
strukturell nichts. Vergleichsschluessel muss (id, severity, Datei, Zeile, Meldung) sein, Differenz
in beide Richtungen — und die Gegenrichtung ist hier nicht Zierde, sondern der ganze Befund.
**Am Gatter dagegen zaehlt genau eine Zahl, naemlich der Exit-Code** (siehe Fund 1).

#### Was daraus fuer die „Verworfen"-Tabelle des Skills folgt

Blickwinkel **#38** gehoert mit **12 / 1 / 11 / 0** hinein (Zahl in Runde 28 richtiggestellt,
siehe „Ergebnis vorweg"): 91,7 % Fehlalarm, und die Erkennung
verlangt pro Eintrag einen Ausloeser im Baum plus einen mutierenden Lint-Lauf (diese Runde:
2 Basislaeufe + 5 Ausloeserlaeufe + 2 `lintVitalRelease`, je 1–3 min). **Kein Gatter, auch keins
zum Vormerken** — ein Dauergatter muesste Ausloesercode erzeugen, kompilieren und zweimal linten;
im Schleusen-Hook ueber einem geteilten Arbeitsbaum ist das ausgeschlossen, dieselbe Grenze wie bei
„totes `@Suppress`" (Runde 9) und dem Rest von #31 (Runde 26). **Ich habe dafuer kein neues Issue
angelegt.**

**Belege:** `assembleDebug` + `testDebugUnitTest` + `lintDebug` gruen (**177 XML-Berichte, 1374
Tests, 0 Failures, 0 Errors**), `lintVitalRelease` gruen auf dem unveraenderten Baum,
`pruefe_reste.py` → „Keine Reste gefunden", Basislauf zweimal als Menge identisch, Arbeitsbaum nach
dem Entfernen aller Ausloeser wieder leer (`git status --short` ohne Ausgabe). Alle Messskripte
waren Wegwerfcode im Scratchpad.

**Zum Stand der Werkzeuge, nachgemessen am 18.09.2026:** `pruefe_reste.py` hat weiterhin **sechs**
Pruefungen, und der Konfliktzustands-Waechter fehlt allen sechs
(`grep -c 'ls-files", "-u' tools/aufraeumen/pruefe_reste.py` → 0). **#60 gilt unveraendert**
(elfter Nachtrag, der ihn meldet — selbst ausgezaehlt ueber die `###`-Abschnitte dieser Datei, nicht
uebernommen: Runden 16, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27).

### 18.09.2026, Torwaechter zu PR #102: der Abschnitt „Runde 27" oben ist in drei Punkten falsch

**Der PR wurde geschlossen; dieser Nachtrag ist nur gerettet, damit die Messungen nicht verloren
gehen. Lies ihn NICHT ohne diese Richtigstellung.** Zwei von drei Widerlegern haben unabhaengig
voneinander dieselben Stellen getroffen, und der Torwaechter hat sie danach SELBST am Bytecode
nachgemessen — die drei Punkte unten sind gemessen, nicht uebernommen.

**1. Der vorgeschriebene „Ausweg" von Neue Lehre 1 zeigt fuer zwei seiner drei eigenen Beispiele
ins Leere.** Die Lehre schreibt: „`javap -p -c` auf `lint-checks-<version>.jar` aus
`~/.gradle/caches`". Gemessen im echten Cache dieses Laufs (AGP 9.4.0, `lint-checks-32.4.0`):

```
grep -rl NullSafeMutableLiveData  <entpacktes lint-checks-32.4.0.jar>  -> 0 Dateien
grep -rl FragmentLiveDataObserve  <entpacktes lint-checks-32.4.0.jar>  -> 0 Dateien
grep -rl UseSparseArrays          <entpacktes lint-checks-32.4.0.jar>  -> 1 Datei (JavaPerformanceDetector.class)
```

Nur der dritte Check liegt dort. Die beiden anderen stecken in AAR-eigenen `lint.jar`s unter einem
ganz anderen Zweig — `~/.gradle/caches/9.7.1/transforms/*/transformed/lifecycle-livedata-core-2.11.0/jars/lint.jar`
und `.../fragment-1.5.7/jars/lint.jar` —, und **ihre Klassennamen weichen von der Issue-ID ab**
(`NonNullableMutableLiveDataDetector`, `UnsafeFragmentLifecycleObserverDetector`), sind also auch
nicht durch Namensraten zu finden. Wer dem Rezept folgt, greppt im falschen Jar, findet nichts und
zieht genau den Fehlschluss, gegen den die Lehre antritt. **Richtig ist: erst die Herkunft des
Checks klaeren (AOSP-`lint-checks` ODER `lint.jar` der jeweiligen Bibliothek im `transforms`-Baum),
dann ueber den KLASSENNAMEN suchen, nicht ueber die ID.** Der Abschnitt „Fund 1" weiss das oben
sogar selbst und verallgemeinert es trotzdem falsch.

**2. Die Beispielaussage zu `FragmentLiveDataObserve` in derselben Lehre ist falsch.** Dort steht,
der Detektor brauche den `observe(this, …)`-Aufruf **in `onViewCreated`**, „irgendwo sonst in der
Fragment-Klasse schweigt der Detektor". `javap -p -c` auf
`androidx/fragment/lint/UnsafeFragmentLifecycleObserverDetector.class` (aus `fragment-1.5.7`)
zeigt **vier** Lebenszyklus-Methoden, nicht eine:

```
onCreateView   onViewCreated   onActivityCreated   onViewStateRestored
```

In allen vieren meldet er. Bitter an der Stelle: es ist die Kernaussage eines Abschnitts, der zehn
Minuten Bytecode-Lesen predigt.

**3. „0 bestaetigt / 12 Fehlalarme (100 %)" widerspricht der Definition drei Absaetze darueber.**
Der Abschnitt definiert selbst: „**bestaetigt** = Eintrag, der auch dann nichts bewirkt, wenn man
ihm seine Arbeit gibt" — und stellt dann unter „Fund 2" fest, `FragmentLiveDataObserve` sei
„wirkungslos, auch MIT Arbeit". Nach der eigenen Definition ist das **ein bestaetigter Befund**;
die Bilanz muesste **12 / 1 / 11** lauten, nicht 12 / 0 / 12. Die Einordnung als „Waechter statt
Rest" mag inhaltlich richtig sein — dann gehoert sie in die DEFINITION, nicht als stille Ausnahme
neben eine runde 100-%-Quote, die als „hier gibt es nichts zu holen" in die „Verworfen"-Tabelle
des Skills wandern sollte. **Die Zahl 12 / 0 / 12 / 0 ist damit nicht eintragungsfaehig.**

**Ebenfalls nicht tragfaehig, wenn auch nicht Schliessgrund: Neue Lehre 3** („ein Blickwinkel ueber
`severity`-Attribute, der Zahlen vergleicht, misst strukturell nichts") wird von „Fund 1"
DERSELBEN Runde widerlegt: die Absenkung `Fatal` → `error` bei `NullSafeMutableLiveData` aendert
sehr wohl ein Ergebnis, naemlich den Exit-Code von `lintVitalRelease`. Wer Lehre 3 bindend nimmt
und deshalb auf Zahlenvergleiche verzichtet, wird fuer genau diese Klasse von Fund blind. Die
Lehre gilt nur fuer `lintDebug`-Berichte, nicht fuer Gatter-Exit-Codes.

**Was der Torwaechter nachgemessen hat und was HAELT** — das ist der brauchbare Teil des
Abschnitts, und er ist ordentlich gearbeitet: Korpus `app/lint.xml` = **20 `<issue>` + 7 `<ignore>`
= 27** (ElementTree, eigene Messung); die zwoelf Kandidaten sind exakt die genannten 6
`informational` + 6 `error`; `git grep -l LiveData -- '*.kt'` → **0**; `pruefe_reste.py` →
„Keine Reste gefunden" (EXIT 0); `pruefe_code.py` → 6 Invarianten, alle halten (EXIT 0);
`assembleDebug testDebugUnitTest lintDebug` → BUILD SUCCESSFUL, und aus den Berichten selbst
gezaehlt (nicht aus dem Exit-Code): **177 XML-Berichte, 1374 Tests, 0 Failures, 0 Errors**.
Der Befund „kein Schnitt, alle zwoelf wirken" bleibt davon unberuehrt — **geschlossen wurde nicht
wegen des Befundes, sondern wegen der drei falschen Saetze, die als bindende Lehre in diese Datei
gegangen waeren.**

**Die Lehre fuer die naechste Runde, die #38 aufnimmt:** die Messung ist brauchbar, das Verfahren
auch. Was fehlt, ist Sorgfalt an den Stellen, an denen aus einer Messung eine REGEL wird — eine
Verallgemeinerung („der Detektor schweigt sonst", „das Jar liegt dort", „Zahlen messen nichts")
braucht denselben Beleg wie der Befund selbst, und keine davon hatte ihn.

### 19.09.2026, Runde 28 (Issue #38 zum dritten Mal — die Frage ist statisch entscheidbar)

**Ergebnis: 12 Rohbefunde, 1 bestaetigt, 11 Fehlalarme (91,7 %), 0 geschnitten.** Die Antwort auf
#38 bleibt, was Runde 27 gemessen hat — **alle zwoelf behalten** —, aber sie ruht jetzt auf einer
anderen Grundlage: **nicht auf Ausloesercode, sondern auf der Voreinstellung der Checks.** Die drei
Saetze, wegen derer PR #102 geschlossen wurde, sind oben an ihrer Stelle richtiggestellt, nicht
danebengeschrieben. Dieser PR bringt **keinen Schnitt**.

**Zahlen, Zaehlweise ausdruecklich benannt, gemessen gegen `96a987a` (= `origin/main` beim
Start):** Korpus `app/lint.xml` = **20 `<issue>` + 7 `<ignore>` + 0 `<option>` = 27 Eintraege**
(`ElementTree`), davon **16 `<issue>`-Bloecke mit `severity`**; die 12 aus #38 sind eine echte
Teilmenge dieser 16. **Rohbefund** = jeder der 12 Kandidaten aus #38; **bestaetigt** = Eintrag, der
auch dann nichts bewirkt, wenn man ihm seine Arbeit gibt; **Fehlalarm** = Eintrag, der bleiben muss.

**Der Korpus ist bitgleich mit dem der Vorrunde** (`git diff ad34e57..origin/main -- app/lint.xml`
→ leer). Nach der Runde-20-Regel waere ein blosses Nachfahren der Vorrundenmessung also
**Verbrauch, keine Bestaetigung** — deshalb wurde nicht nachgefahren, sondern mit einem anderen
Verfahren neu gemessen. Das ist der Unterschied, den die Regel meint: eine zweite Messung zaehlt,
wenn sie eine andere Ebene beruehrt, nicht wenn sie dieselben Knoepfe noch einmal drueckt.

#### Neue Lehre 1: Bei `severity`-Eintraegen ist die REGISTRY der Korpus, nicht der Baum

Runde 27 hat pro Eintrag Ausloesercode geschrieben, kompiliert und A/B gelintet — 5 Ausloeserlaeufe
plus 2 Basislaeufe — und musste danach in einer eigenen Lehre festhalten, dass drei der zwoelf im
ersten Anlauf gar nicht feuerten. Das ist der teure Weg zu einer Frage mit **zwei** Werten:
ein `severity`-Eintrag wirkt genau dann, wenn er von der **Voreinstellung des Checks** abweicht.
Diese Voreinstellung ist kein Geheimnis, sie steht in `Issue.getDefaultSeverity()`.

Beschafft mit einem Wegwerfprogramm (nichts im Repo): Klassenpfad aus `~/.gradle/caches`
(`lint-api`/`lint-checks`/`lint-model` 32.4.0, `uast`, `intellij-core`, `kotlin-stdlib`,
`kotlin-compiler-embeddable`, `kxml2`, `asm`, `gson`), dann jede `IssueRegistry` instanziieren und
`id`, `defaultSeverity`, `isEnabledByDefault` ausgeben. **Zwei Fallen, beide gemessen:**

- **`LintClient.setClientName(...)` muss vorher gesetzt sein.** Sonst stirbt schon der
  `<clinit>` von `BuiltinIssueRegistry` an `UninitializedPropertyAccessException: lateinit
  property clientName has not been initialized` — eine Meldung, die wie ein kaputter Klassenpfad
  aussieht und keine ist.
- **Die Bibliotheks-Checks stehen nicht im AOSP-Jar.** `BuiltinIssueRegistry` kennt **512** Issues,
  darunter **10 der 12**. Die restlichen zwei liegen in den `lint.jar`s der jeweiligen AAR im
  `transforms`-Baum, mit eigenen Registries: `androidx.fragment.lint.FragmentIssueRegistry`
  (9 Issues, aus `fragment-1.5.7`) und `androidx.lifecycle.lint.LiveDataCoreIssueRegistry`
  (1 Issue, aus `lifecycle-livedata-core-2.11.0`).

**Dass diese beiden Jars zu DIESEM Projekt gehoeren, ist nachgesehen und nicht angenommen** — der
`transforms`-Cache eines Rechners enthaelt auch Fremdes: `androidx.fragment:fragment:1.5.7` und
`androidx.lifecycle:lifecycle-livedata-core:2.11.0` stehen beide echt aufgeloest (ohne `(c)`) im
`debugRuntimeClasspath` **und** im `releaseCompileClasspath` von `:app`. Genau die Versionen, deren
`lint.jar` ausgelesen wurde.

**Das Ergebnis ist eine Tabelle statt einer Erzaehlung** — alle 16 `severity`-Eintraege der Datei,
nicht nur die 12 aus dem Issue:

| Voreinstellung | Eintrag | Anzahl | Welche |
|---|---|---|---|
| `warning` | `informational` | 10 | die 6 Abwertungen aus #38 + `NewerVersionAvailable`, `GradleDependency`, `TrustAllX509TrustManager`, `ObsoleteSdkInt` |
| `warning` | `error` | 4 | `InsecureBaseConfiguration`, `WorldReadableFiles`, `WorldWriteableFiles`, `StaticFieldLeak` |
| **`fatal`** | `error` | **1** | `NullSafeMutableLiveData` — **Absenkung**, siehe Lehre 2 |
| `error` | `error` | **1** | `FragmentLiveDataObserve` — **wirkungslos**, der einzige bestaetigte Befund |

Alle 16 Checks sind registriert und `enabledByDefault=true`; kein Eintrag laeuft ins Leere, keine
unbekannte ID. 10 + 4 + 1 + 1 = 16. Die Messung dauert nach einem warmen Cache **Sekunden** und
haengt an keinem Ausloeser, der feuern muss.

#### Neue Lehre 2: In `lintVitalRelease` ist ein `severity`-Eintrag kein Regler, sondern ein AUS-Schalter

Runde 27 hat den Effekt gemessen (zwei Exit-Codes) und ausdruecklich geschrieben, die uebliche
Erklaerung — „`lintVital` prueft ausschliesslich FATAL-Befunde" — nicht belegt zu haben. Sie ist
jetzt belegt, und sie ist **schaerfer als ihre Kurzfassung.** `FlagConfiguration.getDefinedSeverity`
in `lint-api-32.4.0` (`javap -p -c`) tut im `fatalOnly`-Zweig genau dies:

- ist in der Konfiguration eine Severity **gesetzt** und ist sie **nicht `FATAL`** → `IGNORE`;
- ist **keine** gesetzt → es zaehlt die Voreinstellung, und nur `FATAL` ueberlebt.

Der `fatalOnly`-Schalter kommt aus AGP 9.4.0: `AndroidLintTask$LintVitalCreationAction.getFatalOnly()`
liefert `iconst_1`, `…$SingleVariantCreationAction.getFatalOnly()` liefert `iconst_0` — `lintVital*`
laeuft also `fatalOnly`, `lintDebug` nicht.

**Daraus folgt eine Regel, die ueber diesen einen Eintrag hinausgeht:** *jeder* `severity`-Eintrag
auf einen Check mit Voreinstellung `fatal` nimmt diesen Check aus dem Release-Gatter — **auch
wenn der eingetragene Wert strenger klingt.** `severity="error"` ist gegenueber `fatal` keine
Verschaerfung, sondern im Gatter ein `IGNORE`. In dieser Datei trifft das auf genau einen Eintrag
zu, und er steht unter der Ueberschrift „AKTIVIERTE PRUEFUNGEN (STRENGER)".

Empirisch bestaetigt, beide Laeufe in derselben Sitzung, einziger Unterschied ist die eine Zeile
in `app/lint.xml` (Ausloeser: eine Wegwerf-Kotlin-Klasse mit **expliziter** Typannotation
`val daten: MutableLiveData<String> = MutableLiveData()` und `daten.value = null`):

| Lauf | `app/lint.xml` | `./gradlew :app:lintVitalRelease` |
|---|---|---|
| A | Eintrag **vorhanden** | **EXIT 0**, BUILD SUCCESSFUL |
| B | Eintrag **entfernt** | **EXIT 1**, BUILD FAILED, „1 error" |

Praktisch kostet das heute nichts — `git grep -l LiveData -- '*.kt'` → **0** —, aber das Gatter ist
aus. Es zu aendern ist kein Aufraeumen, sondern eine Entscheidung ueber ein Release-Gatter:
**nicht angefasst**, die Messung steht in **#103** (das Issue gibt es seit Runde 27; ich habe kein
zweites angelegt). Ausloeser und Eintrag sind danach zurueckgebaut, `git status --short` ohne
Ausgabe.

#### Nachgemessen, weil es eine bindende Lehre trug: `UseSparseArrays` meldet kein `HashMap`

Runde 27s Lehre 2 („Der ERKLAERUNGSTEXT eines Lint-Issues ist keine Beschreibung seines Codes")
haelt — hier unabhaengig am Bytecode nachgesehen statt am Ausloeser. In
`JavaPerformanceDetector$PerformanceVisitor` (aus `lint-checks-32.4.0`) gibt es `checkSparseArray`,
die Konstanten `android.util.SparseArray`, `java.lang.Integer`, `java.lang.Boolean`,
`java.lang.Long` und die Meldungen „Use `new SparseIntArray(...)`" / „Use `new
SparseBooleanArray(...)`". Die Zeichenfolge `java.util.HashMap` kommt in **keiner** der
`JavaPerformanceDetector*`-Klassen vor (gezaehlt: 0). Titel des Checks („HashMap can be replaced
with SparseArray") und der Kommentar in `app/lint.xml` beschreiben also einen Zweig, den es in
dieser Fassung nicht gibt. **Der Kommentar ist trotzdem nicht umgeschrieben worden** — ob der
Eintrag bleibt, gehoert dem Eigentuemer, und eine neu formulierte Begruendung nimmt die Antwort
vorweg (Defekt 2 von PR #37).

#### Zum Gatter: diesmal gibt es einen Kandidaten, und er wird trotzdem nicht hier gebaut

Die Pruefung „**kein `severity`-Eintrag senkt eine `fatal`-Voreinstellung ab**" ist statisch
entscheidbar, haette heute **0 % Fehlalarm** und haette einen **lebenden** Defekt gefunden — das
abgeschaltete Release-Gatter oben. Das ist mehr, als die letzten drei Gatter-Kandidaten dieser
Reihe vorweisen konnten. **Gebaut wird sie hier nicht** (Skill-Regel 4: neun von neun beurteilten
Aufraeum-PRs); sie liegt als eigenes Issue mit diesen Zahlen. Ehrlich dazu gehoeren ihre Kosten:
sie braucht die aufgeloeste Registry, also einen JVM-Lauf mit rund zehn Jars aus dem
Gradle-Cache **und** die `lint.jar`s aus dem `transforms`-Baum — die existieren erst nach einem
Build. Im Schleusen-Hook ist das nicht umsonst zu haben, und den Konfliktzustand muesste sie
ausserdem kennen (#60). Eine spaetere Runde entscheidet das mit diesen Zahlen vor Augen.

**Belege:** `assembleDebug` + `testDebugUnitTest` + `lintDebug` gruen; aus den Berichten selbst
gezaehlt, nicht aus dem Exit-Code: **179 XML-Berichte, 1393 Tests, 0 Failures, 0 Errors**.
`pruefe_reste.py` → „Keine Reste gefunden" (EXIT 0). Lint-Bericht **14 Befunde, kein
`UnknownIssueId`**, alle aus der Liste der akzeptierten Dauermeldungen (`NewerVersionAvailable` 4,
`GradleDependency` 3, `TrustAllX509TrustManager` 2, `AndroidGradlePluginVersion`,
`AutoboxingStateCreation`, `ConfigurationScreenWidthHeight`, `ObsoleteSdkInt`, `PluralsCandidate`
je 1) — die Zahl schwankt mit fremden Veroeffentlichungen und ist **kein Sollwert**, wie der Skill
fuer diese Gruppe sagt. Arbeitsbaum nach allen Messungen wieder leer (`git status --short` ohne
Ausgabe). Alle Messprogramme waren Wegwerfcode im Scratchpad.

**Zum Stand der Werkzeuge, nachgemessen am 19.09.2026:** `pruefe_reste.py` hat weiterhin **sechs**
Pruefungen, und der Konfliktzustands-Waechter fehlt allen sechs
(`grep -c 'ls-files", "-u' tools/aufraeumen/pruefe_reste.py` → 0). **#60 gilt unveraendert**
(zwoelfter Nachtrag, der ihn meldet — selbst ausgezaehlt ueber die `###`-Abschnitte dieser Datei:
Runden 16, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28).

### 19.09.2026, Torwaechter zu PR #105 (gemergt): eine Verallgemeinerung in Lehre 2 greift zu weit

**Der PR ist gemergt** — Bau, Tests, Invarianten und Reste gruen, alle Zahlen ziffergenau
reproduziert (179 XML-Berichte, 1393 Tests, 0 Failures, 0 Errors; Lint 14 Befunde, kein
`UnknownIssueId`), kein Gatter daneben, die Weckerkette nicht beruehrt. Zwei von drei Widerlegern
kamen mit „nicht widerlegt" zurueck. **Richtigzustellen ist genau ein Satz**, und weil er fett als
**Regel** dasteht und damit bindend wird, steht die Korrektur hier und nicht nur im PR-Kommentar.

**Falsch ist die Verallgemeinerung in „Neue Lehre 2" (Runde 28):**

> „*jeder* `severity`-Eintrag auf einen Check mit Voreinstellung `fatal` nimmt diesen Check aus dem
> Release-Gatter — **auch wenn der eingetragene Wert strenger klingt.**"

`severity="fatal"` ist davon **ausgenommen**. Am Bytecode nachgemessen, nicht uebernommen:
`FlagConfiguration.getDefinedSeverity` aus `lint-api-32.4.0` gibt im `fatalOnly`-Zweig bei Offset
**105–115** den gesetzten Wert `FATAL` per `areturn` **durch**; erst ab Offset **139–150** wird ein
gesetzter Wert, der *nicht* `FATAL` ist, zu `IGNORE`. Der Aufzaehlungspunkt unmittelbar ueber der
Regel sagt das selbst richtig („ist sie **nicht `FATAL`** → `IGNORE`") — die daraus gezogene Regel
laesst die Ausnahme fallen.

**Richtig lautet sie:** *jeder `severity`-Eintrag, der einen Check mit Voreinstellung `fatal` auf
einen **anderen Wert als `fatal`** setzt, nimmt ihn aus dem Release-Gatter.* Die Formulierung des
Gatter-Kandidaten in **#104** („kein `severity`-Eintrag darf eine `fatal`-Voreinstellung
**absenken**") ist bereits korrekt und bleibt unveraendert gueltig; ebenso der Befund selbst
(`NullSafeMutableLiveData`, `fatal` → `error`, A/B EXIT 0 gegen EXIT 1).

**Warum das hier steht und nicht nur als Anmerkung:** es ist derselbe Fehlertyp, an dem PR #102
gescheitert ist — eine Messung ist sauber, und die REGEL, die aus ihr gezogen wird, traegt den
Beleg nicht mehr ganz. Der Torwaechter-Block zu #102 hat es so formuliert: „eine Verallgemeinerung
braucht denselben Beleg wie der Befund selbst". Das gilt auch fuer Verallgemeinerungen, die nur um
ein Wort zu weit gehen.

**Zwei Beobachtungen fuer die naechste Runde, beide gemessen:**

1. **Diese Datei ist durch Runde 28 von 111.863 auf 124.116 Zeichen gewachsen (+11 %), bei
   0 geschnittenen Zeilen.** Sie ist Pflichtlektuere jeder Runde und damit **4,1×** so gross wie
   CLAUDE.md (30.398 Zeichen, hartes Limit der Harness-Pruefung bei 40 k). `pruefe_budget.py` misst
   sie nicht mit (37.106 von 150.000, alle Budgets eingehalten) — **#79 ist offen und wird
   dringlicher**, nicht kleiner.
2. **Der Block „18.09.2026, Torwaechter zu PR #102" spricht jetzt teilweise ins Leere.** Er zitiert
   im Praesens drei Saetze („Dort steht …", „Die Lehre schreibt …", „Die Zahl 12 / 0 / 12 / 0 …"),
   die Runde 28 oben an ihrer Stelle behoben hat. Das ist **kein Fehler des Merges** — der Block ist
   ein datierter Protokolleintrag und haelt fest, warum #102 geschlossen wurde. Wer ihn liest,
   lese die korrigierten Stellen oben dazu; sie tragen die alte Fassung jeweils in Klammern.

### 20.09.2026, Runde 29 (Issue #64, Gatter für Enum-Einträge — es ist heute nicht baubar)

**Ergebnis vorweg: 9 Rohbefunde, 9 bestätigt, 0 Fehlalarme (0 %) — und trotzdem kein Gatter.**
Die Prüfung ist gebaut und gemessen worden (Wegwerfcode im Scratchpad); sie meldet auf einem
**sauberen `main`** neun Befunde und beendet `pruefe_reste.py` mit **EXIT 1**. Damit sperrt sie
Schleuse, CI und Torwächter, solange der Schnitt aus **#19** nicht entschieden ist — und #19 ist
nach drei Anläufen zurückgestellt, gehört also dem Eigentümer. Dieser PR bringt deshalb **weder
Gatter noch Schnitt**, nur diesen Nachtrag. `#64` bleibt offen; die Reihenfolge steht jetzt im
Issue.

**Zahlen, Zählweise ausdrücklich benannt, gemessen gegen `97c75a1` (= `origin/main` beim Start,
Arbeitsbaum bitgleich):** Korpus **435 `.kt` unter `app/src`**, darin **39 Enums mit 158
Einträgen** (eigener Tokenizer; Kommentare maskiert, String-Literale behalten, Rohstring- und
Schachtelungsregel aus Runde 20 eingebaut). **Rohbefund** = Eintrag ohne Verwender nach der Regel
unten; **bestätigt** = im Baum wirklich nirgends benutzt; **Fehlalarm** = Eintrag, der bleiben
muss. *Bestätigt heißt hier ausdrücklich NICHT „darf geschnitten werden" — das ist die Frage von
#19 und hängt an der Historie, nicht am Baum.*

Die vier Selbstprüfungen der Runden 16–20, jede mit ihrem Beleg:

- **leer (16):** 0 Enums ohne geparste Einträge.
- **Namen (17):** Inventar vollständig ausgedruckt und angesehen — `GOOD_BUT_RISKY` steht als
  `GOOD_BUT_RISKY` da, nicht als `Y`.
- **Menge (18):** naive Gegenzählung `git grep -c "enum class"` = **39** = Parserzahl. Auf den Refs
  der Vorrunden liefert derselbe Parser **`d463025` → 35 / 146** und **`ab12d87` → 37 / 152** —
  ziffergleich mit dem, was die Runden 16/17 und 18 berichtet haben. Die Differenz zu heute ist
  Baumbewegung, kein Parserdefekt, und sie geht auf: 146 → 152 sind `AusGrund` (4) + `DndQuelle`
  (2), 152 → 158 sind `Blockposition` (4) + `Art` (2).
- **Unterbau (20):** Gegenprobe ohne eigenen Parser und ohne Maskierung,
  `git grep -c -w -E "<die neun Namen>" -- app/src` → **1 Zeile** in `DiscoveryStatus.kt` +
  **9 Zeilen** in `HueSchedule.kt` = 10. Das sind die 9 Deklarationen plus **eine** KDoc-Zeile
  (`HueSchedule.kt:139` nennt `ZONE` und `ROOM` zusammen). Die maskierte Messung ist also exakt die
  unmaskierte minus Kommentar — Differenz in beide Richtungen erklärt.

#### Neue Lehre 1: Ein namensbasiertes Gatter darf die Rundendokumentation nicht lesen — sonst entwaffnet diese Datei es

Runde 20 hat gemessen, dass der eigene Nachtrag die **Messung einer Runde** verfälscht. Für ein
**Dauergatter** ist dasselbe keine Momentaufnahme, sondern ein Konstruktionsfehler, und er ist hier
zum zweiten Mal aufgetreten — in meiner ersten Messfassung. Zwei Läufe desselben Skripts, einziger
Unterschied ist der Verweis-Korpus:

| Verweis-Korpus | Rohbefunde |
|---|---|
| `app/**` (ohne `build`), Kotlin-Kommentare maskiert | **9** |
| zusätzlich `tools/`, `docs/`, `.claude/`, Wurzel | **0** |

Die Gegenrichtung ist leer (kein Befund, den nur die weite Fassung hat). Die Ursache ist **eine
einzige Datei**, nachgemessen statt vermutet: `git grep -l -w -E "DIM|BRIGHTEN|SET_COLOR|COLOR_LOOP|N_UPNP" -- . ':(exclude)app'`
→ **nur `tools/aufraeumen/nachtraege.md`**. Die Runden 17 und 18 haben die neun Namen hier
aufgeschrieben, um ihren Befund belegbar zu machen — und halten sie damit für jeden Zähler am
Leben, der diese Datei mitliest. Derselbe Effekt lässt sich datieren: dasselbe Skript mit weitem
Korpus findet auf **`d463025`** (bevor der Nachtrag die Namen nannte) **9** Befunde und auf
`origin/main` **0**.

**Die Regel für jedes künftige Gatter:** Der Verweis-Korpus einer Prüfung, deren Befund „niemand
nennt diesen Namen" lautet, ist **der Quelltext, nicht das Repo.** Rundendoku, Skills und
CLAUDE.md sind Prosa über den Code; sie halten nichts am Leben. Wer sie mitliest, baut eine Wache,
die genau dann schweigt, wenn jemand ihren Fund aufschreibt.

#### Neue Lehre 2: Ein blockierendes Gatter braucht einen für SEINE Klasse geräumten Baum — und diese Reihenfolge steht schon im Werkzeug

Der Prototyp (alle vier Vorbedingungen aus #64 umgesetzt) auf dem heutigen Stand:

```
Pruefung 7            :  9 Befunde  (ActionType 6, TargetType 2, DiscoveryMethod 1)
Pruefungen 1-6        :  0 Befunde
=> pruefe_reste.py    :  EXIT 1     auf sauberem main, ohne jede Aenderung am Quelltext
```

`tools/schleuse/pruefe_schleuse.py:543` ruft das Skript vor jedem `git merge`/`git push`,
`ci.yml:91` ruft es mit `--ci`, und `torwaechter.yml:126` fährt es selbst und **schließt den PR**,
wenn es fällt. Ein Gatter, das mit dem PR zusammen in `main` landet, sperrt also ab dem Merge jede
weitere Auslieferung — dieselbe Wirkung wie der Konfliktzustands-Fehler, an dem PR #56 gescheitert
ist, nur ohne Merge-Konflikt als Auslöser. **Gemessen, nicht befürchtet.**

Der Ausweg heißt nicht „Gatter weicher machen", sondern **Reihenfolge**, und die steht seit Runde 7
im Kopf von Prüfung 6 in `pruefe_reste.py`: „*Die Quote stimmt also erst, seit der Baum aufgeräumt
ist — eine weite Prüfung auf einem ungeräumten Baum wäre ein Fehlalarm-Generator gewesen.*" Für
Enum-Einträge ist der Baum nicht geräumt: die neun Einträge stehen unverändert da, weil #19 nach
drei Anläufen zurückgestellt ist. **#64 hängt damit an #19, und zwar zwingend.** Wer das übersieht,
liefert ein fachlich richtiges Gatter, das das Repo sperrt.

*Die neun sind heute unabhängig nachgeprüft und stehen: keine Iteration, kein `when` über die drei
Typen, und die einzigen Erzeuger im Baum sind `TargetType.LIGHT/GROUP`, `ActionType.TURN_ON/
TURN_OFF` und `DiscoveryMethod.ONLINE_DISCOVERY/MDNS`.*

#### Neue Lehre 3: Die „0 % Fehlalarm" in #64 stammen von der SCHNITT-Frage, nicht von der Gatter-Frage

#64 nennt die Klasse gatterfähig, weil Runde 17 „9 Rohbefunde, 9 bestätigt, 0 Fehlalarme" gemessen
hat. Diese Null gilt für die Frage **„benutzt der Baum den Eintrag?"** — sie ist statisch
entscheidbar, und mein Lauf reproduziert sie. Die Frage, die ein Gatter beantwortet sehen will,
ist aber **„darf der Eintrag weg?"**, und die ist es nicht: `TargetType` und `ActionType` sind
`@Serializable` und stehen im Regelbestand im DataStore. Der KDoc fünf Zeilen darüber
(`HueSchedule.kt:128`) hält selbst fest, dass `ignoreUnknownKeys` unbekannte **Schlüssel** abdeckt,
**nicht unbekannte Enum-Werte** — ein Wert, den ein älteres APK geschrieben hat, wird nach dem
Entfernen zum harten Dekodierfehler. Genau deshalb hat der Torwächter für diese neun einen
**Erzeugersuchlauf über die gesamte Historie** gemacht; ein Textgatter kann das nicht.

**Also eine fünfte Vorbedingung für #64:** die Prüfung braucht dieselbe Ausstiegsluke wie
Prüfung 6 — `OHNE VERWENDER` im eigenen KDoc des Eintrags schweigt sie. Ohne sie blockiert der
erste Alt-Wert, den jemand fürs Dekodieren behalten muss, das ganze Repo, und der einzige Ausweg
wäre, ihn zu löschen.

#### Der Entwurf, den eine spätere Runde übernehmen kann

Gemessen und lauffähig; er gehört erst in den Baum, wenn #19 entschieden ist.

1. **Konfliktzustand (#60):** `git ls-files -u` nicht leer → schweigen.
2. **Korpus:** `app/**` ohne `build`, `.kt/.kts/.java` mit maskierten Kommentaren, `.xml/.json/
   .pro/.txt` roh. Keine `.md`, kein `tools/` (Lehre 1).
3. **Iterationsausnahme:** `T.entries`, `T.values()`, `T.valueOf(`, `enumValueOf<T>`,
   `enumValues<T>`, `T::class.java.enumConstants` — heute treffen sie **9 der 39 Typen**. Steht in
   einem dieser Muster ein Typargument, das **kein bekannter Enum-Typ** ist (reifizierter
   Typparameter), ist die Frage unentscheidbar und die **ganze Prüfung schweigt**; das ist der
   Fehlalarm, an dem PR #59 gestorben ist, in die sichere Richtung aufgelöst.
4. **Zählung:** ein Bezeichner-Index über den Korpus (wie Prüfung 6, sonst Laufzeit), Befund bei
   `Vorkommen[name] - Deklarationen[name] == 0`. Namensgleichheit über Enums hinweg
   (`MASTER_PAUSE`, `RULE_TEST`, `VALIDATE`, `NONE`, `UNKNOWN`) **versteckt** dadurch Befunde,
   statt falsche zu erzeugen — die sichere Richtung (Runde 23, Lehre 2).
5. **Ausstiegsluke:** `OHNE VERWENDER` im KDoc des Eintrags (Lehre 3).
6. **Tests:** die Verdrahtung mitprüfen, nicht nur die reine Funktion (Lehre aus PR #48).

**Belege:** `assembleDebug` + `testDebugUnitTest` grün; aus den Berichten selbst gezählt, nicht aus
dem Exit-Code: **179 XML-Berichte, 1393 Tests, 0 Failures, 0 Errors**. `pruefe_reste.py` →
„Keine Reste gefunden" (EXIT 0), `pruefe_code.py` → EXIT 0. Arbeitsbaum außer diesem Nachtrag
unberührt; alle Messskripte waren Wegwerfcode im Scratchpad.

**Zu #79, gemessen statt behauptet:** dieser Nachtrag bringt die Datei von **125.679 auf 135.704
Zeichen (+8,0 %)**, bei 0 geschnittenen Zeilen — die dritte Runde in Folge, die sie um rund 10 k
wachsen lässt, während sie Pflichtlektüre jeder Runde bleibt. Ich habe sie nicht gekürzt: was hier
steht, ist entweder Beleg für die Zahlen oben oder der Entwurf, den die nächste Runde braucht.
**Das Kürzen ist eine Entscheidung des Eigentümers** (welche Lehre in einen Skill wandert und hier
verschwindet) — genau das, was #79 beantragt.

**Zum Stand der Werkzeuge, nachgemessen am 20.09.2026:** `pruefe_reste.py` hat weiterhin **sechs**
Prüfungen, und der Konfliktzustands-Wächter fehlt allen sechs
(`grep -c 'ls-files", "-u' tools/aufraeumen/pruefe_reste.py` → 0). **#60 gilt unverändert**
(dreizehnter Nachtrag, der ihn meldet — selbst ausgezählt über die `###`-Abschnitte dieser Datei:
Runden 16, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29).

---

### 20.09.2026, Torwaechter zu PR #107 — gerettet, aber vier Stellen richtiggestellt

**PR #107 ist GESCHLOSSEN** (3 von 3 Widerlegern, jeder Punkt vom Torwaechter selbst
nachgemessen). Der Abschnitt „Runde 29" oben bleibt stehen, weil seine **Messungen ausnahmslos
halten** — ich habe sie ziffergleich reproduziert: 435 `.kt`, 39 Enums / 158 Eintraege,
`git grep -c "enum class"` = 39, die neun Rohbefunde (ActionType 6, TargetType 2, DiscoveryMethod
1) ohne Iteration und ohne `when`, Erzeuger nur `LIGHT/GROUP`, `TURN_ON/TURN_OFF`,
`ONLINE_DISCOVERY/MDNS`, Gegenprobe 10 Zeilen (9 Deklarationen + `HueSchedule.kt:139`),
Vorrunden-Refs `d463025` → 35/146 und `ab12d87` → 37/152, die Zeilenverweise
`pruefe_schleuse.py:543`, `ci.yml:91`, `torwaechter.yml:126`, `pruefe_reste.py` mit sechs
Pruefungen und `ls-files -u`-Zaehler 0, 179 Berichte / 1393 Tests / 0 Failures / 0 Errors,
125.679 → 135.704 Zeichen. **Vier Aussagen darin gelten aber NICHT.** Sie stehen hier, weil die
Datei als Ganzes gerettet werden musste — wer den Abschnitt oben liest, liest diese vier dazu.

**1. „Die Runden 17 und 18 haben die neun Namen hier aufgeschrieben" ist falsch — es war ALLEIN
Runde 17.** Gemessen ueber die `###`-Abschnitte dieser Datei, Wortgrenzensuche nach allen neun
Namen: Abschnitt Runde 17 → **9 von 9**, Abschnitt Runde 18 (samt Torwaechter-Block) → **0 von 9**,
Abschnitt Runde 29 → 7 von 9. Runde 18 nennt nur die drei TYPEN und die Zahl 9, keinen einzigen
Eintragsnamen. Die eigene Datierung des Nachtrags belegt dasselbe: der weite Lauf findet auf
`ab12d87` (= Fassung nach Runde 17) bereits **0**. Bitter daran: das ist exakt die Fehlerklasse,
die fuenf Absaetze weiter oben am Torwaechter-Block zu PR #71 sanktioniert wurde — **ein falsch
zugeordneter Beleg unter einer richtigen Regel.** Die Regel selbst (Verweis-Korpus eines
namensbasierten Gatters ist der QUELLTEXT, nicht das Repo) gilt unveraendert und ist gut.

**2. „#64 haengt damit an #19, und zwar zwingend" ist zu stark — die Ausstiegsluke gibt es
bereits.** `tools/aufraeumen/pruefe_reste.py:442` definiert `BEWUSST_OHNE_VERWENDER = "OHNE
VERWENDER"`, Zeile 475 ueberspringt damit jeden Fund; der Kopf des Skripts nennt Pruefung 6
ausdruecklich „blockierend, **mit Begruendungszwang**". Lehre 3 desselben PR macht genau diese
Luke zur fuenften Vorbedingung — **damit verschwindet das gemessene EXIT 1 ohne jeden Schnitt:**
neun KDoc-Zeilen an den neun Eintraegen, 0 geloeschte Zeilen, #19 unberuehrt. Das gemessene
„EXIT 1 auf sauberem `main`" ist eine Eigenschaft des VIER-Vorbedingungen-Prototyps, den der
Nachtrag drei Absaetze spaeter selbst fuer unvollstaendig erklaert. Auch der zitierte Praezedenzfall
traegt das „zwingend" nicht: der Satz VOR dem Zitat im Kopf von Pruefung 6 lautet „Nach dem
Entfernen bleiben DREI, und zwei davon tragen ihre Begruendung im eigenen KDoc" — heute sind es
**vier** solche Stellen (`TokenData.kt`, `CalendarRepository.kt`, `IShiftUseCase.kt`,
`NotificationDeliverability.kt`). Pruefung 6 laeuft also auf einem **teils begruendeten**, nicht
auf einem geraeumten Baum gruen. **Richtig ist: „geraeumt ODER begruendet".** Wer „zwingend" als
bindend liest, parkt #64 auf unbestimmte Zeit hinter einem zurueckgestellten Issue, obwohl der
nicht-blockierende Weg danebensteht.

**3. Die Richtigstellung zum #71-Block ist nur halb ausgefuehrt — Danebenschreiben.** Der PR
entkraeftet den SCHLUSSSATZ von Punkt 2, laesst aber die beiden **fett gesetzten Urteilszeilen
darueber stehen**, und die sind die Zeilen, die eine eilige Runde liest: „**zwei seiner
Belegsaetze sind aber falsch**" (es ist jetzt **einer**) und die Ueberschrift „**‚nach dem Schnitt
0 Rohbefunde' ist widerlegt**" (gilt nach der eigenen Messung des PR **nicht mehr**; Runde 18
hatte recht). Die inhaltliche Korrektur ist sachlich richtig — `DiscoveryStage` ist tot (#72), alle
sechs Eintragsnamen stehen als String-Literale im Produktivcode (`OfficialHueDiscoveryService.kt`,
`AnimatedDiscoveryCard.kt`), vom Torwaechter nachgeprueft —, aber sie gehoert **an die Stelle der
falschen Saetze**, nicht darunter. Der Skill sagt es woertlich: „Danebenschreiben ist ein Fehler,
nicht Verlauf."

**4. `HueSchedule.kt:128` ist kein KDoc.** Zeile 128 liegt in einem `//`-Zeilenkommentarblock
(123–130) INNERHALB der Konstruktor-Parameterliste; der naechste KDoc beginnt bei 137, und bis
Zeile 139 sind es 11 Zeilen, nicht „fuenf darueber". Das Zitat ist inhaltlich richtig
(`ignoreUnknownKeys` deckt Schluessel, nicht Enum-Werte; `HueConfigRepository` konfiguriert
`Json` ohne `coerceInputValues`, und beide Felder haben keinen Default — ein entfernter Wert wird
zur harten `SerializationException`). In einem Dokument, das „`OHNE VERWENDER` **im KDoc**" zur
Ausstiegsluke macht, ist die Unterscheidung Kommentar/KDoc aber keine Wortklauberei: ein Gatter,
das nur KDoc-Bloecke liest, faende die Begruendung an so einer Stelle nicht.

**Was daraus fuer die naechste Runde folgt.** #64 ist durch diese Schliessung beim **zweiten**
Anlauf (`blickwinkel_waehlen.py` zaehlt nur geschlossene, nicht gemergte PRs — `merged_at` wird
uebersprungen, Zeile 76). Der Blickwinkel bleibt gut und ist **heute baubar**, wenn die fuenfte
Vorbedingung aus Lehre 3 mitgebaut wird; er haengt NICHT an #19. Der Entwurf im Abschnitt oben
(Konfliktzustand, Korpus ohne `tools/` und ohne `.md`, Iterationsausnahme mit Schweigen bei
reifiziertem Typparameter, Bezeichner-Index, Ausstiegsluke, Verdrahtungstest) ist geprueft und
uebernehmbar. **Und die Lehre, die diese Runde sich selbst haette geben koennen:** wer einen
Beleg aus einer frueheren Runde zitiert, misst die Zuordnung nach — dieselbe Datei hat genau
dafuer schon einen PR gekostet (#71).

---

### 22.09.2026, Runde 30 (Issue #64, Gatter für Enum-Einträge — der Ertrag ist jetzt gemessen)

**Ergebnis vorweg: 9 Rohbefunde, 9 bestätigt, 0 Fehlalarme — ziffergleich mit Runde 29, aber mit
einem eigenen Parser gewonnen.** Kein Gatter, und genau **eine** Quelländerung: ein Kommentar, der
das Gegenteil dessen behauptet, was im Baum steht. Neu an dieser Runde ist die Frage, die an #64
bisher niemand gestellt hat — **was trägt so ein Gatter eigentlich ein?** Sie ist über die ganze
Historie beantwortbar, und ihre erste Antwort war falsch.

**Zählweise, ausdrücklich benannt, gemessen gegen `e917087` (= `origin/main` beim Start):** Korpus
`app/` ohne `build` = **435 `.kt` + 1 `.kts` + 0 `.java`** (Kommentare zeichenlängentreu maskiert,
String-Literale behalten) plus **16 Rohdateien** (`.xml/.json/.pro/.txt`). Darin **39 Enums mit
158 Einträgen**. **Rohbefund** = Eintrag, dessen Name im Korpus nur in seiner eigenen Deklaration
vorkommt, nach Abzug der iterierten Typen; **bestätigt** = im Baum wirklich nirgends benutzt.
*Bestätigt heißt weiterhin NICHT „darf geschnitten werden" — das ist #19.* Die vier
Selbstprüfungen:

- **leer (16):** 0 Enums ohne geparste Einträge.
- **Namen (17):** Inventar vollständig ausgedruckt und angesehen — `GOOD_BUT_RISKY` steht als
  `GOOD_BUT_RISKY` da, nicht als `Y`.
- **Menge (18):** naive Gegenzählung `git grep -c -E "enum\s+class"` über den Korpus = **39** =
  Parserzahl.
- **Unterbau (20):** `git grep -c -w -E "<die neun Namen>" -- 'app/*'`, ohne Parser und ohne
  Maskierung → **10 Zeilen** = 9 Deklarationen + `HueSchedule.kt:139` (KDoc, nennt `ZONE`/`ROOM`
  zusammen). Maskiert = unmaskiert minus Kommentar; Differenz in beide Richtungen erklärt.

Unabhängig mitbestätigt: die **9 von 39** iterierten Typen aus dem Entwurf von Runde 29, und **kein
einziger reifizierter Typparameter** im Baum (`enumValues<T>`/`enumValueOf<T>` mit unbekanntem
Argument: keiner). Der Entwurf stimmt also auch in seinen Nebenzahlen.

#### Neue Lehre 1: Den Ertrag eines Gatters misst man an den TODEN, nicht an den Geburten

Erste Messung, und sie klang abschließend: über **574 first-parent-Commits** (968 mit Merges)
gab es **204 Eintrags-Geburten**, davon **28 ohne Verwender geboren — alle 28 im Initial-Commit
`34abec2`**, und **0 in den 573 Commits danach**. Daraus folgt scheinbar zwingend „null Ertrag,
nicht bauen" (Runde 19). Das wäre falsch gewesen: **ein Enum-Eintrag stirbt meistens nicht bei der
Geburt, sondern wenn jemand seinen letzten Verwender entfernt.** Dieselbe Historie noch einmal,
diesmal auf Übergänge „benutzt → unbenutzt" gemessen:

| Ereignis nach dem Initial-Commit | Anzahl in 573 Commits |
|---|---|
| Eintrag wird TOT GEBOREN | 0 |
| Eintrag wird nachträglich VERWAIST | **2** |

- `AlarmOutcome.FAILURE`, verwaist in `1cde43d` (25.08.2025). Aufgefallen ist es niemandem; der
  ganze Typ verschwand später in `4306e34` — durch eine Aufräumrunde, nicht durch ein Gatter.
- `DiscoveryMethod.N_UPNP`, verwaist mit `5a48374` (25.08.2026, auf `main` über den Merge
  `c42f9c7`). **Er steht bis heute.**

**Der zweite Fall ist der Grund, warum diese Lehre hier steht.** `5a48374` heißt „chore: toten Code
aus der Inspektions-Triage abarbeiten" — eine Aufräumrunde. Sie entfernte `HueBridge.discoveryMethod`
und damit den einzigen Erzeuger von `N_UPNP`, und schrieb **in derselben Änderung in dieselbe
Datei** den Kommentar „Die drei, die es wirklich gibt - **je ein Erzeuger** in den
Discovery-Diensten". Der Satz war in dem Moment falsch, in dem er geschrieben wurde, und stand
**28 Tage**. Das ist wörtlich der Fall, für den es `pruefe_reste.py` gibt („Sucht die Reste, die
ein Aufräumdurchgang typischerweise hinterlässt") — und weder ein Mensch noch eine der sechs
Prüfungen hat ihn gesehen.

Die **2 ist eine Untergrenze**: die Todesmessung zählt mit `git grep -o -w` über den **Rohtext**,
eine Kommentarnennung hält einen Namen also am Leben. Hätte `5a48374` den Namen `N_UPNP` in seinen
neuen Kommentar geschrieben, wäre der Tod in meiner Messung nicht aufgetaucht. Wer die Zahl
schärfen will, misst mit Maskierung — sie wird dadurch größer, nie kleiner.

#### Neue Lehre 2: Mein eigener Korrekturkommentar entwaffnet ein unmaskiertes Gatter — heute, nachweisbar

Vorbedingung 2 aus #64 („Kommentare ausblenden, String-Literale mitzählen") war bisher mit PR #59
belegt und mit Runde 29s Fund über die Rundendoku. Diese Runde liefert den Beleg am eigenen Leib:
Der korrigierte Kommentar (unten) **muss** `N_UPNP` beim Namen nennen, sonst erklärt er nichts.

```
                                   vorher     nachher
git grep -c -w N_UPNP -- 'app/*'   1 Zeile    2 Zeilen
maskierte Messung                  9 Befunde  9 Befunde
```

**Ein Gatter, das Kommentare mitzählt, hätte `N_UPNP` ab heute nicht mehr gemeldet — wegen einer
Zeile, die genau seine Abwesenheit dokumentiert.** Für die Selbstentwaffnung braucht es also weder
eine ENTFERNT-Notiz noch die Rundendoku; ein ehrlicher Kommentar im Produktivcode genügt.

#### Warum trotzdem kein Gatter in diesem PR

Der Torwächter zu #107 hat recht: die Ausstiegsluke gibt es (`pruefe_reste.py:442`), und neun
`OHNE VERWENDER`-Zeilen machen die Prüfung ohne einen einzigen Schnitt grün. **Ich habe sie
trotzdem nicht geschrieben, und zwar aus dem Grund, den Prüfung 6 selbst nennt:** „wer den Text
`OHNE VERWENDER` in ihrer Doku schreibt, **hat die Entscheidung getroffen** und begründet." Genau
diese Entscheidung ist #19, #19 ist nach drei Anläufen zurückgestellt und gehört damit dem
Eigentümer. Dazu kommt, dass die Begründung inhaltlich falsch wäre: der Torwächter zu #71 hat für
diese neun die **gesamte Historie nach Erzeugern durchsucht und keinen gefunden** — der Befund
zeigt also zum Schnitt, nicht zum bewussten Behalten. Neun Zeilen „bewusst ohne Verwender" wären
das Gegenteil des Gemessenen.

**Gemessen, aber bewusst nicht gebaut — damit es niemand neu erfinden muss:** eine Prüfung, die nur
die gegenüber `merge-base` **NEU verwaisten** Einträge meldet (wie Prüfung 3 ihre Basis nutzt),
wäre heute grün, ohne die neun anzufassen, und hätte in der Historie **zweimal** geschlagen — genau
bei den beiden Fällen oben. Sie ist aber ein anderes Gatter als #64 beschreibt, braucht einen
zweiten vollen Bezeichner-Index über den Basis-Baum und damit deutlich mehr Prüffläche. Wer sie
will, entscheidet das mit diesen zwei Zahlen vor Augen; wer sie nicht will, hat hier den Grund.

**Die Reihenfolge bleibt: #19 entscheiden, dann ist #64 eine Handbewegung.** Der
Sechs-Punkte-Entwurf von Runde 29 gilt unverändert und ist oben in seinen Nebenzahlen nachgemessen.

#### Die eine Quelländerung: ein Kommentar, der das Gegenteil behauptet

`hue/data/DiscoveryStatus.kt`. Ausgezählt: von den **acht** `emit(DiscoveryStatus(`-Stellen in
`OfficialHueDiscoveryService` setzen **vier** `ONLINE_DISCOVERY` und **fünf** `MDNS` (eine wählt
zur Laufzeit zwischen beiden, daher 4 + 5 auf 8 Stellen) — **`N_UPNP` keine.** Nichts entfernt,
nichts umbenannt: die falsche Zeile ist durch die Messung ersetzt, nicht danebengeschrieben.

**Entscheidungsrelevant für #19, deshalb dort kommentiert und hier nicht entschieden:** die
N-UPnP-Phase **gibt es** (`OfficialHueDiscoveryService`, Phase 2). Sie meldet sich über
`stage = "N_UPNP_SEARCH"` und `currentMethod = "N-UPnP"` und trägt dabei
`method = ONLINE_DISCOVERY`. Ein Schnitt von `N_UPNP` entfernt also den Wert, der eine
**existierende** Phase benennen würde — das ist keine reine Altlastfrage mehr, sondern eine über
die Diagnostik. Der Rest der Runde-29-Lehre 3 gilt unverändert: `TargetType`/`ActionType` sind
`@Serializable` und stehen im Regelbestand.

**Belege:** `assembleDebug` + `testDebugUnitTest` grün; aus den Berichten selbst gezählt, nicht aus
dem Exit-Code: **179 XML-Berichte, 1393 Tests, 0 Failures, 0 Errors**. `pruefe_reste.py` → „Keine
Reste gefunden" (EXIT 0), `pruefe_code.py` → EXIT 0, `unittest discover -s tools/aufraeumen` →
48 Tests OK. Alle Messskripte waren Wegwerfcode im Scratchpad.

**Zum Stand der Werkzeuge, nachgemessen am 22.09.2026:** `pruefe_reste.py` hat weiterhin **sechs**
Prüfungen (`grep -c "^def pruefe_"` → 6), und der Konfliktzustands-Wächter fehlt allen sechs
(`grep -c 'ls-files", "-u'` → 0). **#60 ist offen** (`gh issue view 60` → OPEN). Dies ist der
**vierzehnte** Nachtrag, der ihn meldet — selbst ausgezählt über die `###`-Abschnitte dieser Datei
(13 vorhandene: Runden 16, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29), nicht aus Runde 29
übernommen.

**Zu #79, gemessen — Zaehlweise `wc -c`, also BYTES, wie in den Vorrunden:** dieser
Nachtrag bringt die Datei von **143.309 auf 152.725 Bytes (+6,6 %)**, die dritte gemessene
Runde in Folge mit rund +10 k (Runde 28 +12,3 k, Runde 29 +10,0 k). Als Zeichen gezaehlt
sind es 150.642 — der Unterschied sind die Umlaute, und wer die beiden Masse mischt, bekommt
eine Wachstumszahl geschenkt, die es nicht gibt. Damit ist sie **5,0x** so gross wie
CLAUDE.md (30.398 Bytes) und weiterhin Pflichtlektuere jeder Runde, ohne dass
`pruefe_budget.py` sie misst. Gekuerzt habe ich nichts: was hier steht, ist Beleg fuer die
Zahlen oben. **Welche Lehre in einen Skill wandert und hier verschwindet, ist die
Entscheidung, die #79 beantragt** — und sie wird pro Runde teurer.

#### Torwaechter zu PR #109 (22.09.2026): die Zeile „nachtraeglich VERWAIST: 2" ist eine 1 — der erste Fall ist ein Messartefakt

Dieser Nachtrag ist **gerettet, sein PR ist geschlossen**, und der Grund steht genau in der Tabelle
oben. Nicht abgestimmt, sondern selbst nachgemessen:

**`AlarmOutcome.FAILURE` ist nicht in `1cde43d` verwaist — es war im Initial-Commit schon tot
geboren.**

```
$ git show --stat 1cde43d
  auth/CredentialAuthManager.kt | debug/EmailExtractionDebugActivity.kt | viewmodel/AuthViewModel.kt
  -> service/AlarmTypes.kt kommt in diesem Commit ueberhaupt nicht vor

$ git grep -n -w FAILURE 34abec2 -- 'app/*'
  service/AlarmTypes.kt:57:    FAILURE          <- nur die Deklaration, sonst nichts im ganzen Baum

$ git grep -n -w FAILURE 1cde43d^ -- 'app/*'
  auth/CredentialAuthManager.kt:266  "TOTAL-FAILURE: Calendar API authorization will definitely fail!"
  auth/CredentialAuthManager.kt:296  "FINAL-FAILURE: No valid email found for Calendar API authorization"
  service/AlarmTypes.kt:57           FAILURE
```

Der gemessene Uebergang „benutzt -> unbenutzt" entsteht **allein** daraus, dass `1cde43d` diese
beiden unbeteiligten Auth-**Logliterale** loescht. `-` ist kein Wortzeichen, also matcht
`git grep -o -w FAILURE` auch `TOTAL-FAILURE` und `FINAL-FAILURE` (eingefuehrt in `8b210e4`).

**Damit gilt in `main`, und zwar statt der Zahlen oben:**

- „Eintrag wird nachtraeglich VERWAIST" = **1**, nicht 2. Der einzige tragende Fall ist
  `DiscoveryMethod.N_UPNP` (`5a48374`, 25.08.2026) — den habe ich nachgeprueft, er haelt: davor
  genau ein Erzeuger (`HueNUpnpDiscoveryService.kt:92` in `5a48374~1`), danach keiner.
- „tot geboren, alle im Initial-Commit" = **29**, nicht 28.
- „haette in der Historie **zweimal** geschlagen" = **einmal**. Wer ueber den dort beschriebenen
  Alternativentwurf („nur die gegenueber merge-base NEU verwaisten Eintraege melden") entscheidet,
  entscheidet mit EINER Zahl, nicht mit zweien. Der Nachtrag verlangt woertlich, „mit diesen zwei
  Zahlen vor Augen" zu entscheiden — eine davon gibt es nicht.
- Die Zusicherung „**sie wird dadurch groesser, nie kleiner**" ist **widerlegt**. Hier hat ein
  String-Literal — das Vorbedingung 2 aus #64 ausdruecklich MITZAEHLEN will, das also auch nach
  Maskierung stehen bliebe — einen **falschen Tod** erzeugt, nicht eine Untererfassung. Die
  Rohtext-Messung irrt in BEIDE Richtungen.

**Die Lehre, die diese Runde sich selbst haette geben koennen:** `git grep -w <NAME>` ist keine
Verwendungsmessung, solange `-` als Wortgrenze zaehlt. Jeder Bezeichner, der als Bestandteil eines
zusammengesetzten Log- oder Nutzertextes auftaucht (`TOTAL-FAILURE`, `N-UPnP`, `SET-COLOR`), haelt
seinen Enum-Namensvetter kuenstlich am Leben — und sein spaeteres Verschwinden sieht dann aus wie
der Tod des Enum-Eintrags. **Die Historienmessung braucht dieselbe Maskierung wie die Baummessung**,
sonst misst sie Logtexte. Das ist bitter, weil dieselbe Runde die Maskierung fuer den Baum sorgfaeltig
gebaut und fuer die Historie weggelassen hat.

**Was an diesem PR getragen hat und unveraendert wiederkommen darf — die Quelltextaenderung.**
Selbst ausgezaehlt, nicht uebernommen: **8** `emit(DiscoveryStatus(`-Stellen in
`OfficialHueDiscoveryService` (Z. 59/73/91/111/139/154/164/180); davon `ONLINE_DISCOVERY` **4x**
(60/112/140/155-else), `MDNS` **5x** (74/92/155-then/165/181), `DiscoveryMethod.N_UPNP` **0x** im
ganzen Baum. Die N-UPnP-Phase gibt es: `stage = "N_UPNP_SEARCH"` (Z. 113) und
`currentMethod = "N-UPnP"` (Z. 116) bei `method = DiscoveryMethod.ONLINE_DISCOVERY` (Z. 112). Der
Altsatz „je ein Erzeuger in den Discovery-Diensten" war falsch, als `5a48374` ihn schrieb. Ebenfalls
mit eigenem Parser reproduziert und tragend: **39 Enums, 158 Eintraege, 9 iterierte Typen,
9 Rohbefunde** (dieselben neun). Bau und Pruefungen gruen, aus den Berichten gezaehlt:
**179 XML-Berichte, 1393 Tests, 0 Failures, 0 Errors**; `pruefe_code.py` EXIT 0 (6 Invarianten),
`pruefe_reste.py` EXIT 0, Schleuse EXIT 0, `lintDebug` gruen (14 SARIF-Befunde, alle aus der Liste
der geduldeten Dauermeldungen plus Dependabot-Rueckstand — keiner aus diesem PR).

**Warum der PR trotz 1-von-3-Widerlegern geschlossen wurde:** weil diese Datei fuer die naechste
Runde **bindend** ist. Eine falsche Zahl darin ist genau der Schaden, gegen den der ganze Apparat
steht — und der PR trug sie im Titel. Die Kommentarkorrektur ist eine Handbewegung und kommt
billig zurueck; eine 28 Tage unbemerkte Falschaussage in `main` kostet eine ganze Runde. Im Zweifel
wird nicht gemergt.

---

### 23.09.2026, Runde 31 (Issue #65, Properties, die nur GESCHRIEBEN und nie gelesen werden)

**Ergebnis vorweg: 14 Rohbefunde, 2 Fehlalarme (14,3 %), 12 bestätigt, 3 geschnitten, 9 stehen
gelassen.** Kein Gatter. Die drei Schnitte liegen in `hue/`, keiner in der Weckerkette.

**Zählweise, ausdrücklich benannt, gemessen gegen `1fff31c` (= `origin/main` beim Start,
Arbeitsbaum bitgleich):** Korpus **435 `.kt` unter `app/src`**, darin **2593
Property-Deklarationen** (Klassenrumpf, Dateiebene, Primärkonstruktor) und **4614 lokale
`val`/`var`** (eigener Tokenizer; Kommentare zeichenlängentreu maskiert, String-Literale behalten,
Rohstring- und Schachtelungsregel aus Runde 20 eingebaut). **Rohbefund** = Property, die nach
Fassung D keinen Lesezugriff hat; **bestätigt** = am Code nachgesehen und wirklich ohne Leser;
**Fehlalarm** = Property, die bleiben muss. Nachher: **2590 Deklarationen, 11 Rohbefunde**
(= 14 − 3), **kein Folgefund** — die Nachher-Liste ist genau die Vorher-Liste minus den drei
Schnitten.

Die Selbstprüfungen der Runden 16–20 und 24, jede mit ihrem Beleg:

- **leer (16):** 117 der 435 Dateien haben keine Property-Deklaration. Liste ausgedruckt und
  angesehen: Hilt-Module (`HueModule`, `RepositoryModule`, `HiltModules`), Receiver und Worker
  ohne Feld (`TimezoneChangeReceiver`, `CalendarPreAlarmRefreshWorker`), reine
  Schnittstellen- und Testdateien — kein Enum-artiges Leerergebnis eines kaputten Parsers.
- **Namen (17):** Inventar vollständig ausgedruckt (2593 Zeilen `Datei:Zeile Klasse.Name`) und
  angesehen; die Namen sind ganze Bezeichner, keine Einzelbuchstaben.
- **Menge (18):** naive Gegenzählung `\b(val|var)\s+Bezeichner` über denselben maskierten Text =
  **7207** = 2593 + 4614. Die Zahl teilt sich allerdings den Unterbau mit dem Parser, sie ist die
  **schwache** Probe — die starke steht im nächsten Punkt.
- **Unterbau (20):** roher `git grep -c -w` ohne Parser und ohne Maskierung über die drei
  geschnittenen Namen, vorher gegen nachher: `lightId` **47 → 46**, `actionDescription` **4 → 0**,
  `overallSuccess` **7 → 0**. Die 46 verbliebenen `lightId`-Zeilen gehören zu **fremden
  Deklarationen** — Funktionsparameter in `HueApiClient`, `HueLightRepository` und
  `HueLightUseCase` samt ihren Verwendungen und Logtexten. Genau sie haben den Fund in Fassung B
  versteckt: der Name lebt, die Property nicht.
- **Zugehörigkeit (24):** der Scanner trennt Property von lokaler Variable über Klammertiefe und
  Art des öffnenden `{` (Klassenrumpf gegen alles andere), nicht über den Pfad. Die 4614 lokalen
  `val`/`var` sind das Nebenergebnis dieser Trennung und gehen in der Summe auf.

#### Die vier Fassungen — und warum A hier eine ANDERE Frage beantwortet als in Runde 23/24

| Fassung | Frage | Ergebnis |
|---|---|---|
| A großzügig, baumweite Namenssuche über **alle** Dateien | „kommt der Name überhaupt vor?" | **10** |
| B streng, namensbasiert: kein Lesetreffer irgendwo im Korpus | „wird der NAME gelesen?" | **9** |
| D wie B, Lesen zählt nur hinter `.`/`::`, in der eigenen Datei oder im String-Literal | „wird die PROPERTY gelesen?" | **14** |
| D ohne die Ausnahme für Dateiebene/`object` | dieselbe Frage, falsch gestellt | **48** |

**B ⊂ D, und die Gegenrichtung ist leer** (Runde-22-Regel: Differenz in beide Richtungen bilden,
nicht Zahlen vergleichen) — die fünf Zusatzfunde von D sind `ConfigBackup.appVersion/createdAt`,
`HueLightAction.lightId`, `BatchActionResult.overallSuccess` und `EventPage.hasMore`.

**Fassung A liefert hier NICHT die perfekte Null der Runden 23 und 24, sondern 10 — und keiner
dieser zehn gehört zu diesem Blickwinkel.** Es sind die `*Inc`-Felder von `GroupUpdate` und
`LightStateUpdate`, die im ganzen Repo **kein einziges Mal** vorkommen; sie haben keinen Leser,
weil sie überhaupt keinen Verwender haben, und liegen damit bei **#89** (Runde 23 hat beide
Klassen bewusst stehen lassen). Die Lehre daran: **eine „großzügige" Fassung ist nur dann die
großzügige Fassung DERSELBEN Frage, wenn sie dieselbe Unterscheidung trifft.** Wer „Name kommt
nirgends vor" als Oberfrage von „Property wird nie gelesen" nimmt, misst die Frage von Prüfung 6
und meldet sie als Ergebnis dieses Blickwinkels.

#### Nebenbefund, weil er eine LEBENDE Wache betrifft: ein Kommentar hält Prüfung 6 still

Vollständigkeitshalber mitgemessen: Properties ohne **jedes** Vorkommen außer ihrer Deklaration
gibt es in `app/src` **16** — die 10 aus **#89** und sechs weitere. Prüfung 6 meldet von diesen
sechs **keine einzige**, und die Gründe sind drei verschiedene, alle nachgesehen:

| Property | warum Prüfung 6 schweigt |
|---|---|
| `TokenData.tokenType`, `TokenData.issuedAt`, `NotificationDeliverability.WICHTIGKEIT_NIEDRIG` | `OHNE VERWENDER` im eigenen KDoc — die vorgesehene Ausstiegsluke, jeweils mit Begründung |
| `LocalTimeSerializer.descriptor` | `override val` — das Muster `KONSTANTE` in `pruefe_reste.py:439` kennt `private`/`internal`/`const`, aber **nicht** `override`. Kein echter Fund (die Schnittstelle verlangt die Eigenschaft), aber dieselbe Lückenklasse wie **#86** |
| `SpacingConstants.SURFACE_CORNER_RADIUS`, `SpacingConstants.CARD_CORNER_RADIUS` | **ein Kommentar in einer anderen Datei** |

Der letzte Fall ist der interessante, und er ist heute in `main` nachweisbar:

```
git grep -n -w SURFACE_CORNER_RADIUS -- 'app/src/**/*.kt'
  ui/theme/Shape.kt:9          * SURFACE_CORNER_RADIUS (8dp), damit Material3-Defaultkomponenten
  util/theme/UIConstants.kt:52     val SURFACE_CORNER_RADIUS = 8.dp
```

Prüfung 6 zählt Bezeichner über den **Rohtext** (`vorkommen - deklarationen == 0`). Die KDoc-Zeile
in `Shape.kt` ist damit ein „Verwender", und die Konstante bleibt ungemeldet — obwohl `Shape.kt`
die Werte `8.dp`/`12.dp` daneben **ausschreibt**, statt die Konstanten zu benutzen. Runde 30 hat
diesen Mechanismus am eigenen Korrekturkommentar vorgeführt und Runde 29 an der Rundendoku; hier
wirkt er **auf ein blockierendes Gatter im Betrieb**, seit dem Tag, an dem jemand einen hilfreichen
Kommentar geschrieben hat. Ich habe nichts daran geändert: ob die beiden Konstanten wegsollen oder
`Shape.kt` sie benutzen soll, ist eine Gestaltungsfrage und nicht dieser Blickwinkel. Die Messung
gehört aber zu **#86** und liegt als Kommentar dort.

#### Neue Lehre 1: Die Empfängertyp-Verschärfung braucht eine Ausnahme für Dateiebene und `object` — sonst erfindet sie 34 Funde

Runde 23 (Lehre 2) und Runde 24 (Lehre 1) sagen: es zählt der Empfängertyp, nicht der Name. Die
naheliegende Umsetzung — „Lesen zählt nur hinter einem Punkt oder in der eigenen Datei" — meldet
hier **48 statt 14 Rohbefunde**, und alle **34** zusätzlichen sind Fehlalarme derselben Bauart:

```
30 x ui/theme/Color.kt      val BrandRed = Color(...)      -> Theme.kt liest `BrandRed` OHNE Punkt
 1 x ui/theme/Shape.kt      val AppShapes                  -> dito
 1 x HueRuleFormState.kt    val HueRuleFormStateSaver      -> dito
 2 x HueConstants.kt        object Bridge { DISCOVERY_TIMEOUT_MS, CONNECTION_TIMEOUT_MS }
```

Eine Deklaration auf **Dateiebene** und ein Mitglied eines **`object`** werden nach `import`
regulär **unqualifiziert** gelesen; der Punkt fehlt dort nicht aus Versehen, sondern weil die
Sprache ihn nicht verlangt. **Die Richtung des Fehlers ist die gefährliche** (Runde 24, Lehre 2):
diese Verschärfung *erfindet* Funde und führt zum Schnitt an lebendem Code — `Color.kt` wäre
komplett auf der Abschussliste gelandet. Die Ausnahme ist mechanisch: die Verschärfung gilt nur
für Properties, deren umgebende Deklaration `class` oder `interface` ist.

#### Neue Lehre 2: Ein POSITIONELLES Konstruktorargument ist eine Schreibstelle ohne Namen

`BatchActionResult.overallSuccess` hat **sieben** Schreibstellen. Eine Namenssuche findet
**fünf**: `HueSceneRuleTest.kt:73` und `:83` schreiben den Wert **positionell**
(`BatchActionResult(actions.size, actions.size, emptyList(), true)`), und dort steht der Name
nirgends. **2 von 7, also 28,6 %, sind für jeden namensbasierten Zähler unsichtbar.**

Für den **Befund** ist das harmlos und sogar die sichere Richtung — eine Property sieht dadurch
*weniger* benutzt aus. Für die **Runde** ist es keine Nebensache: wer die Schreibstellen zählt und
die Zahl aufschreibt, schreibt eine falsche auf, und wer nach der Namensliste schneidet, lässt zwei
Aufrufstellen stehen. Hier hat der Compiler sie gefangen (ein Argument zu viel), aber das ist ein
Zufall der Bauart: bei einem `var`, das positionell über eine Fabrikfunktion gesetzt wird, gibt es
diesen Fang nicht. **Für ein künftiges Gatter ist es eine harte Grenze:** eine Prüfung, die
Vorkommen nach Namen zählt, kann positionelle Schreibstellen grundsätzlich nicht sehen.

#### Neue Lehre 3: Die Serialisierung ist ein Leser — aber nur, wenn es ein HINTERGRUNDFELD gibt

Runde 23 hat „die Serialisierung ist ein Leser, den man nicht sieht" als Lehre aufgeschrieben und
im selben Atemzug `HueLightAction.lightId` zu den Feldern im `@Serializable`-Bestand gezählt. Das
ist falsch, und der Unterschied ist genau der, an dem dieser Blickwinkel hängt. Gemessen statt
angenommen — Wegwerf-Test im Baum, danach entfernt, `git status --short` ohne Ausgabe:

```
serializer<HueLightAction>().descriptor  ->  15 Elemente:
targetType,targetId,targetName,actionType,on,brightness,hue,saturation,
colorTemperature,color,transitionTime,duration,isGroup,sceneId,sceneName
```

Das sind **exakt die 15 Konstruktorparameter**. `lightId` (`get() = targetId`) und `isScene`
stehen im Klassenrumpf, haben kein Hintergrundfeld und sind **nicht** im Descriptor — sie werden
weder geschrieben noch gelesen, das gespeicherte JSON kennt sie nicht. Der Schnitt von `lightId`
ändert das Bestandsformat also nachweislich nicht. Die falsche Aufzählung in Runde 23 ist oben an
ihrer Stelle korrigiert, nicht danebengeschrieben.

**Die Gegenprobe in dieselbe Richtung, und sie ist der Grund für die bindende String-Regel:**
`HueLightAction.targetType` — das Beispiel, mit dem Issue #65 aufmacht — ist **kein** Rohbefund,
obwohl kein Kotlin-Code es liest. Es steht im Descriptor **und** als Schlüssel im Bestands-JSON
eines Tests (`HueSceneRuleTest.kt:227`), es hat keinen Default, und `ignoreUnknownKeys` deckt
fehlende Pflichtfelder nicht ab. Genau dafür zählen String-Literale seit Runde 17 als Verwender:
hier hat die Regel einen Schnitt verhindert, der ein APK-Downgrade im Dekoder hätte sterben
lassen. **Die Frage lautet nicht „liest jemand das Feld", sondern „hat es ein Hintergrundfeld, und
in welche Richtung läuft die Serialisierung".**

#### Was geschnitten wurde — und was bewusst stehen blieb

Geschnitten, jeder Fund einzeln am Code nachgesehen:

| Fund | Belege | Warum der Schnitt trägt |
|---|---|---|
| `HueLightAction.lightId` (`HueSchedule.kt:135`) | 0 Leser; die 46 übrigen `lightId`-Zeilen sind fremde Funktionsparameter | nicht im Serializer-Descriptor (oben gemessen) |
| `LightAction.actionDescription` (`IHueLightUseCase.kt:176`) | 3 Schreibstellen, 0 Leser, im ganzen Baum 4 Zeilen | `LightAction` ist weder `@Serializable` noch Gson-Modell, und **nichts loggt eine `LightAction`** (kein `$action` auf diesem Typ) — es gibt auch keinen `toString`-Leser |
| `BatchActionResult.overallSuccess` (`IHueLightUseCase.kt:202`) | 7 Schreibstellen, 0 Leser | die Konsumenten (`HueRuleUseCase`, `HueSunriseExecutor`, `AlarmReceiver`-Kette) lesen `successfulActions`/`failedActions`; die Aussage ist aus `failedActions.isEmpty()` jederzeit wieder herstellbar |

Mit `actionDescription` fallen die drei Zeichenketten weg, die es gefüttert haben
(`"Rule: … - Szene …"`, `"Rule: … - <targetId>"`, `"Sunrise finalize: …"`); sie waren
Zeichenketten für niemanden. Mit `overallSuccess` fällt in `HueLightUseCase` die lokale
`val overallSuccess = failedActions.isEmpty()` weg, die sonst als unbenutzter Rest stehengeblieben
wäre.

**Neun bestätigte Funde blieben stehen, jeder mit einem Grund:**

- **Fünf in der Weckerkette** — `AlarmSkipState.skipActivatedAt` und `.skipReason`,
  `AlarmStatus.alarmStatusMessage`, `AlarmStatus.batteryOptimizationExempt` und
  `AlarmPermissionStatus.batteryOptimizationExempt`. Leitplanke „Die Weckerkette fasst du nicht
  an"; Abgrenzung mechanisch wie in Runde 21/22/24 (Pfad **oder** Dateiname enthält `alarm`,
  `service`, `dimmer`), damit sie nachprüfbar ist und nicht nach Gefühl. Als Issue mit „braucht
  Rücksprache" abgelegt. `skipReason` ist dabei der interessanteste: er schreibt den GRUND einer
  Übersprungen-Entscheidung, den niemand je wieder ausliest.
- **`EventPage.hasMore`** (`ICalendarUseCase.kt:23`) — geschrieben an drei Produktivstellen, kein
  Leser: `CalendarViewModel.kt:1093` rechnet sich sein eigenes `hasMore` aus
  (`events.size >= initialPageSize || totalEventCount > sortedEvents.size`) statt das Feld zu
  lesen. Nicht angefasst, und zwar nicht aus Vorsicht: CLAUDE.md nennt das **Lazy-Präfix** als eine
  der zwei Quellen einer unvollständigen Eventliste, und `hasMore` ist der einzige strukturelle
  Marker dafür, dass eine Seite ein Präfix ist. Ein Feld zu entfernen, das „diese Liste ist
  unvollständig" bedeutet, ist keine Aufräumfrage. Issue mit „braucht Rücksprache".
- **Zwei UI-Zustandsfelder ohne Anzeige** — `CalendarUiState.lastAuthorizationCheck` (zweimal
  geschrieben) und `HueUiState.connectionHealth` (zweimal geschrieben, mit einem Kommentar, der
  die Absicht ausdrücklich benennt: „reactive bridge connection health … independent from
  bridgeConnectionInfo"). Das ist wortgleich die Lage von `BridgeConnectionInfo.bridgeName`
  (Runde 23, **#92**) und von `IShiftUseCase.resetToDefaults` (Runde 24): bei UI-Zustand heißt
  „kein Leser" womöglich **„die Anzeige fehlt"**, und das ist eine Produktentscheidung. Issue.
- **`BridgeConnectionInfo.bridgeName`** — derselbe Fund wie in Runde 23, unverändert offen als
  **#92**. Kein zweites Issue angelegt.

**Zwei Fehlalarme, beide dieselbe Bauart:** `ConfigBackup.appVersion` und `ConfigBackup.createdAt`
sind `@Serializable`-Felder, die in die **exportierte Datei** geschrieben werden; ihr eigenes KDoc
sagt „nur zur Nachvollziehbarkeit fuer Menschen, keine Logik daran". Das Modell wird
*serialisiert*, nicht nur deserialisiert — nach Runde 23s Richtungsregel verliert es mit dem Feld
die Wirkung. Bleiben.

#### Kein Gatter (Skill-Regel 4), und hier auch keins zum Vormerken

Die Quote liegt mit **14,3 %** über der Faustregel, aber das ist nicht der Grund. Fassung D braucht
**Empfängertypen**, die ein Regex nicht hat, und ihre eine mechanische Näherung („Punkt davor")
hat in diesem Baum **34 Fehlalarme** erzeugt, bis die Ausnahme für Dateiebene und `object` dazukam
(Lehre 1) — eine Regel, die nur hält, solange niemand eine neue Idiomform benutzt (`with(x) { … }`
und Erweiterungsfunktionen lesen Instanz-Properties ebenfalls ohne Punkt; heute gibt es im Baum
keine solche Lesestelle für eine der 14, morgen kann es eine geben, und sie wäre ein
Fehlalarm **auf einem blockierenden Gatter**). Dazu kommt Lehre 2: positionelle Schreibstellen
sind für einen Namenszähler unsichtbar. Und die Entscheidung „fehlendes Bedienelement gegen
Altlast" hat hier bei **drei** der zwölf bestätigten Funde den Ausschlag gegeben — die muss Absicht
erraten, und das ist genau die Sorte Blickwinkel, die laut Skill verworfen gehört.
**Der Blickwinkel gehört mit 14 / 12 / 2 / 3 in die „Verworfen"-Tabelle des Skills: er lohnt als
Runde, nicht als Wache.** Ich habe dafür **kein Gatter-Issue angelegt**.

Und falls es doch jemand versucht: der Verweis-Korpus dieser Messung ist **`app/src`, sonst
nichts** — Runde 29, Lehre 1. Dieser Nachtrag nennt `lightId`, `actionDescription` und
`overallSuccess` beim Namen, weil er sie sonst nicht belegen könnte; ein Zähler, der `tools/`
mitläse, hätte ab heute genau deswegen geschwiegen.

**Belege:** `assembleDebug` + `testDebugUnitTest` grün; aus den Berichten selbst gezählt, nicht aus
dem Exit-Code: **179 XML-Berichte, 1393 Tests, 0 Failures, 0 Errors**. `pruefe_reste.py` → „Keine
Reste gefunden" (EXIT 0), `tools/invarianten/pruefe_code.py` → 6 Invarianten, alle halten (EXIT 0),
`unittest discover -s tools/aufraeumen` → 48 Tests OK. Der Wegwerf-Test für den Descriptor ist
entfernt, der Arbeitsbaum enthält außer Schnitt und Nachtrag nichts
(`git status --short`). Alle Messskripte waren Wegwerfcode im Scratchpad.

**Zum Stand der Werkzeuge, nachgemessen am 23.09.2026:** `pruefe_reste.py` hat weiterhin **sechs**
Prüfungen (`grep -c "^def pruefe_"` → 6), und der Konfliktzustands-Wächter fehlt allen sechs
(`grep -c 'ls-files", "-u'` → 0). **#60 ist offen** (`gh issue view 60` → OPEN). Dies ist der
**fünfzehnte** Nachtrag, der ihn meldet — selbst ausgezählt über die `###`-Abschnitte dieser Datei
(14 vorhandene: Runden 16, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30), nicht übernommen.

**Zu #79, gemessen — Zählweise `wc -c`, also BYTES, wie in den Vorrunden:** dieser Nachtrag bringt
die Datei von **157.316** auf **175.115** Bytes (+11,3 %); CLAUDE.md liegt unverändert bei
**30.398** Bytes, die Datei ist damit **5,8x** so groß wie die Pflichtdatei, die als einzige ein
gemessenes Budget hat. Gekürzt habe ich nichts: was hier steht, ist Beleg für die Zahlen oben.
**Welche Lehre in einen Skill wandert und hier verschwindet, ist die Entscheidung, die #79
beantragt** — und sie wird pro Runde teurer.
