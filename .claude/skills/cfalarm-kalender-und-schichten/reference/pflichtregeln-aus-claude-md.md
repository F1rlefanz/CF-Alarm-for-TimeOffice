# Pflichtregeln aus der CLAUDE.md, mit Begründung

Die CLAUDE.md nennt je Regel nur den Kernsatz, damit sie immer geladen bleibt. Hier steht der
volle Wortlaut, wie er bis zur Kürzung dort stand. Gilt immer; Änderungen an einer Regel
betreffen beide Stellen.

## Kalender und Schichterkennung

- **Eine unvollständige Eventliste ist KEINE Löschgrundlage.** Drei Quellen: Teilerfolg einzelner
  Kalender, das Lazy-Präfix (10 Events pro Kalender) und der Abruf-Horizont einer VERKLEINERTEN
  Vorausschau — Wecker dahinter sind „nicht gelesen“, nicht „gelöscht“ (`abrufHorizontEnde`). **Jeder löschende Konsument geht über
  `getCalendarEventsWithStatus()` und prüft `isComplete`** — einzige Ausnahme ist die
  ausdrückliche Kalender-ABWAHL (leere Auswahl aus dem Speicher rückgelesen, nicht leeres
  Ladeergebnis); Einzelheiten im Kalender-Skill; der `CalendarStateHolder` trägt
  `eventsComplete` mit. Wer einen neuen `syncAlarms()`-Aufrufer ergänzt, muss das beantworten.
- **Ein Zustand, der den Alarm-Sync DAUERHAFT anhält, muss sichtbar sein.** Die
  `isComplete`-Sperren verhindern nicht nur das Löschen, sondern auch jedes Anlegen; bleibt ein
  Kalender dauerhaft unerreichbar, versiegen die Wecker lautlos. Deshalb trägt
  `CalendarFetchOutcome` die `failedCalendarIds` (nicht nur ihre Zahl), die Status-Karte zeigt sie
  mit Folge und Ausweg, und ab dem ZWEITEN Wartungslauf in Folge warnt eine Benachrichtigung —
  **auch wenn ALLE Kalender scheitern**; nur ein reiner Verbindungsausfall fragt sie nicht (eine
  leere Menge hieße „erholt"). **Ihr „schon gemeldet"-Gedächtnis endet an der Gerätegrenze**, auf
  BEIDEN Wegen —
  `ConfigBackupFilter` (Export) und `DeviceLocalFlagsGuard` (Android-Backup). Mitgereist heilt es
  nicht, es hält sich selbst am Leben.
- **Kein Fehler darf als leeres Erfolgsergebnis durchrutschen** — „leer" ist für eine Wecker-App die
  gefährlichste Lüge und löscht ALLE Alarme.
- **Ein gescheiterter Konfigurations-Read darf NIE zur leeren Definitionsliste werden**
  (`getOrThrow()`), und es gibt **keinen stillen Default-Überschreiber**.
- **`ShiftRecognitionEngine`: ein unveränderliches Cache-Objekt, Prüfung UND Veröffentlichung hinter
  dem Mutex, PLUS Epochen-Kennung.** Sonst liest ein nebenläufiger Aufrufer eine leere Liste — und
  „leer" heißt „keine Schichten" heißt: alle Alarme weg.
- **`findDefinitionFor` und `matchesKeywords` nicht verwechseln** — die Verwechslung hat zweimal
  Wecker gekostet. Ersteres ordnet einem bestehenden Alarm zu (gestaffelt), Letzteres erkennt in
  Kalendertiteln (mit Wortgrenzen über Unicode-Kategorien, NICHT `\b`).
- **Wer einen Schichtnamen PERSISTENT speichert, traegt ihn in den Umbenennungs-Nachzug ein.**
  Drei Stellen binden ueber den Namen: Dimmer- und Hue-Regeln (`shiftPattern`) und die
  Dienstzeit-Ausnahmen. Letztere vergleicht EXAKT - eine reine Schreibweisen-Aenderung zaehlt
  deshalb als Umbenennung. Beim Namenstausch wird der falsch gewordene Eintrag geraeumt, ausser
  beide Namen stehen in derselben Liste (dann stimmt ihr Inhalt weiter). Rufbereitschaft ist ein
  Flag AM Schichttyp (`isOnCall`), keine Liste. Die vollstaendige Inventur steht im Kalender-Skill.
- **`CalendarStateHolder` ist eine Einbahnstraße**, und Laden gehört ausschließlich dem
  `CalendarViewModel`.
- **`loadEventsForSelectedCalendars()` braucht einen Generation-Counter** — die Prüfung VOR JEDEM
  Schreiben, auch vor dem ersten `isLoading = true`.
- **Neue Properties in ViewModels mit `init{}` gehören VOR den `init{}`-Block** — sonst NPE beim
  ersten App-Start, die kein Unit-Test fängt.
