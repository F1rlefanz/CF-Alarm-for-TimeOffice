---
name: cfalarm-arbeit-abschliessen
description: "Was Fertigsein in der CFAlarm-Wecker-App heisst und wo das Ergebnis abgelegt wird. Zu verwenden, sobald eine Aufgabe fertig scheint - vor dem Merge, vor dem Push, vor einem /clear, oder bevor eine Arbeit als erledigt gemeldet wird. Nennt insbesondere das, was die Schleuse (tools/schleuse/pruefe_schleuse.py) NICHT pruefen kann: ob die Aenderung am Geraet wirklich wirkt, ob eine neue Faehigkeit eine Bedienoberflaeche hat, ob ein Nutzertext etwas behauptet, das es nicht gibt, ob der Hergang im passenden Skill steht und ob Altlasten und widerlegte Notizen beseitigt wurden."
---

# Arbeit abschliessen

## Die Schleuse ist die untere Schranke, nicht das Ziel

`tools/schleuse/pruefe_schleuse.py` laeuft automatisch vor `git merge` und `git push`. Sie prueft
**Mechanik**: Geheimnisse, Skill-Frontmatter, Doku-Budget, Changelog-Seite gegen Markdown,
Code-Invarianten, Unit-Tests, Lint — und beim Push von `main` zusaetzlich Changelog-Eintrag,
Versionsbump und `versionCode` gegen `origin/main`.

**Sie kann nichts davon beurteilen, was diese App tatsaechlich zu einer Wecker-App macht.** Ein
gruener Lauf heisst: nichts Offensichtliches ist kaputt. Er heisst nicht: die Aufgabe ist erledigt.

## Was die Schleuse NICHT prueft — und was hier deshalb von Hand faellig ist

**1. Ob es am Geraet wirkt.** 1121 gruene Unit-Tests sind kein Startbeweis; die Hilt-Graph-,
Direct-Boot- und Notification-Pfade fallen erst auf einem echten System um. Neue oder riskante
Interaktionen zuerst am **Emulator** vollstaendig durchspielen, am produktiven Fairphone
bevorzugt read-only (Dialog oeffnen, mit "Abbrechen" schliessen). Direct Boot geht nur ueber
`python tools/geraet/pruefe_direct_boot.py`. Einzelheiten im Skill `cfalarm-bauen-und-testen`.

**2. Ob die Faehigkeit eine Bedienoberflaeche hat.** Grundregel aus `CLAUDE.md`: *Eine Funktion
ohne Bedienoberflaeche gibt es fuer den Nutzer nicht.* Wer etwas einbaut, baut die Stelle mit, an
der man es **sieht**, **ausloest** und seinen **Zustand abliest**. Kein Test faellt darueber.

**3. Ob die Texte die Wahrheit sagen.** Die Schleuse prueft, **DASS** ein Changelog-Eintrag kam —
nie, ob er in Nutzersprache geschrieben ist. Und kein Werkzeug prueft, ob ein Oberflaechentext
eine Anzeige oder einen Ablauf behauptet, den es nicht gibt. Beides ist hier schon schiefgegangen.

**4. Ob die Version richtig gestuft ist.** Geprueft wird, **DASS** gebumpt wurde. Patch fuer Fixes,
Minor fuer Features, Major nur nach Ruecksprache — das entscheidest du.

**5. Ob der Hergang aufgeschrieben ist.** Eine neue Zusicherung gehoert als normative Zeile in
`CLAUDE.md` (nur wenn ihr Bruch den Wecker kostet) **und** mit Hergang in den zustaendigen Skill:
welcher Bug sie erzwungen hat, welche Messung sie belegt, welche Alternative verworfen wurde. Ohne
das Warum baut die naechste Sitzung dieselbe Falle in neuer Form nach.

**6. Ob aufgeraeumt ist.** Am Ende eines zusammenhaengenden Durchgangs: toter Code, nicht mehr
aufgerufene Funktionen, ueberholte Kommentare, jetzt redundante Abstraktionen — tatsaechlich
entfernen. Und **widerlegte Notizen korrigieren, nicht danebenschreiben**. Zwei Aussagen zum selben
Sachverhalt sind ein Fehler, kein Verlauf.

**7. Ob offene Punkte eine Heimat haben.** Alles, was offen bleibt, gehoert ins Memory
`project_offene_punkte` — nicht in eine Datei im Repo (es ist oeffentlich) und nicht in den
Sitzungskontext (den gibt es nach `/clear` nicht mehr).

## Warnzeichen

| Gedanke | Wirklichkeit |
|---|---|
| „Die Schleuse war gruen, also bin ich fertig" | Sie prueft Mechanik. Die sieben Punkte oben prueft sie nicht. |
| „Die Tests sind gruen, es laeuft" | Gruene Unit-Tests sind kein Startbeweis. Am Geraet nachsehen. |
| „Den Changelog schreibe ich beim Release" | Dann fehlt der Kontext. Jetzt schreiben, in Nutzersprache. |
| „Das schreibe ich lieber zusaetzlich dazu" | Widerspruch stehenlassen ist Drift. Die alte Stelle korrigieren. |
| „Das merke ich mir fuer die naechste Sitzung" | Es gibt keine naechste Sitzung mit diesem Gedaechtnis. Ins Memory. |
| „Erst noch schnell die naechste Aufgabe" | Dann schleppst du alten Kontext mit. Erst abschliessen, dann `/clear`. |
| „Der Emulator hat es gezeigt, das reicht" | Nur, wenn der Punkt nicht geraetespezifisch ist. Sonst: offener Punkt. |
| „Ein Refutation-Voting hat es widerlegt" | Das ist ein Hinweis, kein Freispruch. Bei bekannten Mustern selbst nachsehen. |

## Wohin das Ergebnis gehoert

| Was | Wohin |
|---|---|
| Nutzersichtbare Aenderung | `CHANGELOG.md` → Generator → `docs/changelog.html` |
| Technische Historie | Git-Commit, aussagekraeftige Nachricht |
| Dauerhafte Zusicherung | `CLAUDE.md` (Kurzregel) **und** der zustaendige Skill (Hergang) |
| Umgebungs-/Werkzeugwissen | Memory `env_*` bzw. `cfalarm-bauen-und-testen` |
| Ergebnis einer Pruefrunde | Memory `project_prueflauf_historie` (Rohbefund- und Bestaetigungszahlen) |
| Offener Punkt, Nebenbefund | Memory `project_offene_punkte` |
| Projektstand (Version, Tests, Branch) | **nirgends** — `tools/sitzungsstart.py` leitet ihn ab |

## Der Ablauf

1. Zugehoerigen Skill gelesen und, wenn noetig, um den Hergang ergaenzt.
2. Am Geraet verifiziert, was sich nur dort zeigt — oder als offener Punkt notiert, warum nicht.
3. Aufgeraeumt (Punkt 6 oben), widerlegte Notizen korrigiert.
4. Auf `main` gemergt, dort gebumpt und den Changelog geschrieben, Generator gelaufen.
   Reihenfolge und Vorlage im Skill `cfalarm-release-und-changelog`.
5. Gepusht — die Schleuse laeuft dabei von allein. Blockiert sie, ist das eine mechanische
   Auskunft, keine Meinung.
6. Gemergte Branches lokal und remote aufgeraeumt.
7. Offene Punkte ins Memory, **dann** `/clear`.
