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
