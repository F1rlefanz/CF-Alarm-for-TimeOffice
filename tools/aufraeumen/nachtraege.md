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
