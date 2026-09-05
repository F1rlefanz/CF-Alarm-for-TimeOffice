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

> **Korrigiert in Runde 17 (05.09.2026):** Hier stand „**Referenzen auf dem Rohtext zählen**".
> Das ist gemessen falsch und kostet den ganzen Blickwinkel — siehe den Abschnitt zu Runde 17
> unten. Richtig ist: **Kommentare auch beim Zählen ausblenden, String-Literale dagegen
> mitzählen** (ein Eintragsname in einer Test-JSON ist ein echter Verwender, weil er Teil eines
> gespeicherten Formats ist), und **reine Doku-Dateien zählen gar nicht mit**.

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

1. **Referenzen auf dem Rohtext zu zählen ergab „0 Rohbefunde".** Nicht ein Befund weniger —
   **alle neun** weg. Ursache: KDoc- und Prosanennungen zählen dann als Verwender, und jeder
   dieser Einträge wird irgendwo in einem Kommentar erwähnt. Das ist derselbe Mechanismus, den
   der Torwächter als Defekt (b) an Prüfung 7 nachgewiesen hat („sie entwaffnet sich durch die
   eigenen ENTFERNT-Notizen") — nur trifft er hier schon die **Messung**, nicht erst das Gatter.
   Deshalb die Korrektur oben. Wer das übersieht, meldet „nichts gefunden" und beendet die Runde
   mit einem sauberen Gewissen und null Ergebnis.
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
