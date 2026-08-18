# DND-Steuerung (Nicht stoeren) — Hergang

> Hergang zu den Kurzregeln in `CLAUDE.md` und in der `SKILL.md` daneben: welcher Bug die
> Regel erzwungen hat, welche Messung sie belegt, welche Alternative verworfen wurde.
> Jede Zeile hier hat einmal echten Schaden verhindert — im Zweifel gilt sie, nicht die Intuition.

## Inhalt

- Zwei Fenster-Trigger plus ein Klipp-Modifikator, kein Regel-Editor
- Rufbereitschaft-Cutoff (`DndOnCallCutoffResolver`, seit v1.20.0) klippt statt eine eigene
- Modus 1 dupliziert KEINE Fenster-Logik
- Das `…WithStatus` ist kein Luxus: der Lesefehler muss über die Dimmer-DND-Grenze kommen
- Modus 2 braucht `AlarmInfo.shiftStartTime`
- Ein Alarm ist ein Weckzeitpunkt, eine `ShiftSpan` ist ein DIENST — die Dienstzeit-Fenster
- `ShiftSpan.alarmTriggerTime` ist NICHT redundant
- Verstrichene Weckzeit ist KEINE entfernte Schicht
- `AutomaticZenRule`, nicht rohes `NotificationManager.setInterruptionFilter()`
- Nur ab API 30 (Android 11)
- Policy ist vollständig Nutzer-konfigurierbar (`DndPrefs.Policy`), NICHTS hart codiert
- `ensureZenRule()` aktualisiert eine bereits registrierte Regel bei JEDEM Tick mit
- Mehrere gleichzeitig aktive Zen-Regeln kombinieren sich vermutlich „freizügigste gewinnt" pro
- Eigener Request-Code `REQ_DND_TICK = 7712`
- `DndScheduleUseCase.CONDITION_ID` ist `by lazy`
- `ensureZenRule()` prüft `Build.VERSION.SDK_INT` direkt
- Am Fairphone 6 (Android 16) verifiziert (28.07.2026)
- Logcat-Fallstrick beim Debuggen, kein Bug

---

- **Zwei Fenster-Trigger plus ein Klipp-Modifikator, kein Regel-Editor.** `dnd/DndScheduleUseCase`
  kennt zwei unabhängig schaltbare Fenster-Quellen (`DndPrefs.Toggles`): „Schlaf-Fenster folgt dem
  Dimmer" und „Während der Dienstzeit". Kein `DndRule`-Modell — ein früherer, adversarial geprüfter
  Entwurf mit vollem Regel-Editor wurde zugunsten dieser einfacheren, tatsächlich angefragten Lösung
  verworfen. Der On-Call-Cutoff (siehe eigener Punkt unten) ist bewusst KEINE dritte Fenster-Quelle,
  sondern ein Klipp-Schritt, der auf das Ergebnis der beiden Quellen angewendet wird.
- **Rufbereitschaft-Cutoff (`DndOnCallCutoffResolver`, seit v1.20.0) klippt statt eine eigene
  Fensterlogik/Policy zu duplizieren.** Der Nutzer markiert bestimmte Schichten (`DndPrefs.
  onCallShifts`, z. B. „AD1") als On-Call; an einem so erkannten Tag wird JEDES aus den beiden
  bestehenden Quellen berechnete Fenster auf eine feste, konfigurierbare Uhrzeit
  (`DndPrefs.onCallCutoffMinutes`, Default 05:00) gekappt — unabhängig davon, welche Quelle das
  Fenster erzeugt hat. **Keine separate Policy für On-Call-Nächte:** dieselbe `AutomaticZenRule`
  gilt bis zum Cutoff unverändert (z. B. bleiben Anrufe geblockt, falls das die normale Policy so
  vorsieht — bewusste Nutzer-Entscheidung, kein Versehen). Zwei beim ersten Bau selbst adversarial
  gefundene und gefixte Fallen: (1) Der Cutoff-Tag darf NICHT unconditional der Kalendertag von
  `shiftStartTime` sein — bei abends beginnenden On-Call-Schichten (z. B. 21:00) läge die
  Cutoff-Uhrzeit (z. B. 05:00) sonst VOR Schichtbeginn und klippt die falsche, unbeteiligte Vornacht
  statt der eigentlichen On-Call-Nacht; der Tag muss auf den Folgetag rollen, sobald die Schicht
  ab/nach der Cutoff-Uhrzeit desselben Tages beginnt. (2) Der Cutoff-Zeitpunkt muss über
  `LocalTime.atZone()` als echte Wanduhrzeit aufgelöst werden, NICHT als Mitternacht-Instant plus
  fixer Minuten-Millis-Offset — sonst landet er an einem DST-Vorspringen-Tag eine Stunde zu spät.
  `DndOnCallCutoffResolverTest` hält beide Fälle fest.
- **Modus 1 dupliziert KEINE Fenster-Logik.** Er ruft `DimScheduleUseCase.previewTimelineWithStatus()`
  direkt auf (seiteneffektfrei) statt eine eigene Kopie der Dimmer-Fensterberechnung zu pflegen.
  Einbahnstraße wie `CalendarStateHolder`: `dnd/` liest von `dimmer/`, nie umgekehrt — der Dimmer
  bleibt unverändert und unwissend von DND. Wer hier eine eigene, „ähnliche" Fensterberechnung für DND
  einbaut, öffnet genau das Drift-Risiko (zwei Quellen der Wahrheit für „ist gerade Nacht"), vor dem
  die adversariale Kritikrunde gewarnt hat.
- **Das `…WithStatus` ist kein Luxus: der Lesefehler muss über die Dimmer-DND-Grenze kommen.** Nach
  einem transienten Lesefehler des Alarm-Bestands blieb DND-Modus 1 bis zu 6 h ohne „Nicht stören",
  obwohl der Dimmer sich planmäßig nach 15 min erholte und die Nacht dimmte: der Fehler passiert
  INNERHALB von `DimScheduleUseCase.computeWindows()` und kam bei DND als ununterscheidbar leere
  Fensterliste an, sein eigenes `alarmReadFailed` blieb `false` (der eigene Spannen-Zweig — bis
  v1.25.1 `getAllAlarms()`, seither `shiftSpanStore.spansNow()` — wird bei nur-Modus-1 nie
  betreten) und `fallbackTick()` plante den 6-Stunden-Keep-alive statt des 15-Minuten-Retry. Wer
  den Status wieder wegoptimiert, holt genau das zurück.
- **Modus 2 braucht `AlarmInfo.shiftStartTime`**, nicht `triggerTime` (Weckzeit, meist vor
  Schichtbeginn wegen Anfahrt) und nicht nur `shiftEndTime`. Gesetzt in
  `AlarmUseCase.createAlarmFromShiftMatch` aus `shiftMatch.calendarEvent.startTime` — exakt
  daneben, wo `shiftEndTime` aus `calendarEvent.endTime` gesetzt wird.
- **Ein Alarm ist ein Weckzeitpunkt, eine `ShiftSpan` ist ein DIENST — die Dienstzeit-Fenster
  kommen seit v1.25.2 aus `ShiftSpanStore`, NICHT mehr aus dem Alarm-Bestand.** Der überlebt die
  Weckzeit nicht, und das ist richtig so: `AlarmRepository` verwirft abgelaufene Alarme in BEIDEN
  Ladepfaden und lehnt das Speichern eines vergangenen Alarms ab — ein abgelaufener Alarm wäre
  genau die verwaiste, armierte Leiche, gegen die die übrigen Zusicherungen geschrieben sind. Bis
  v1.25.1 hing `DndShiftSpanResolver` aber genau daran: der erste `syncAlarms()` nach dem
  Klingeln räumte den Alarm, und mit ihm das Fenster der Schicht, die GERADE LÄUFT. Am Emulator
  gemessen (14.08.2026): 20.08. 08:00, mitten in der Frühschicht (Termin 06:00–14:12, Alarm
  05:30 bereits gefeuert) → `zen_mode=0`, Regel `STATE_FALSE`; nach dem Fix `zen_mode=1`,
  `STATE_TRUE`. Drei Dinge gehören zusammen: die Spannen werden in `syncAlarms()` **vor** dem
  Vergangenheits-Filter geschrieben (genau die Schichten, die der Alarm-Bestand nicht mehr
  hergibt), **auch in den beiden Leer-Zweigen** („keine Events" / „keine passende Schicht" —
  ohne das hält eine alte Spanne DND dauerhaft an, während die App „kein Dienst" anzeigt), und
  der Schreibvorgang ist **nicht-fatal gekapselt** (ein Nebenschauplatz darf den Alarm-Sync nie
  abbrechen). Eine Spanne kennt bewusst **kein `isActive` und kein „übersprungen"**: ein
  deaktivierter oder übersprungener Wecker ändert nichts daran, dass der Dienst stattfindet.
- **`ShiftSpan.alarmTriggerTime` ist NICHT redundant.** `DimWindowResolver` leitet den
  **Kalendertag** eines Slots aus der Weckzeit ab (`buildRuleSpans`/`buildDefaultNightSpans`,
  `Instant.ofEpochMilli(a.triggerTime)`). Wer die Spanne ohne diesen Wert baut und einen
  Platzhalter einsetzt, datiert den Slot auf 1970 und zerstört die Tagesverankerung ALLER
  Dimm-Fenster — dieselbe Fehlerklasse, die schon einmal falsche Dimm-Nächte erzeugt hat. Der
  Dimmer zieht deshalb Regel- und Nacht-Standard-Slots aus den Spannen, die **Wellness**-Quelle
  aber weiterhin aus dem echten Alarm-Bestand: sie dimmt VOR der Weckzeit, ihr Fenster ist nach
  dem Klingeln ohnehin vorbei.
- **Verstrichene Weckzeit ist KEINE entfernte Schicht.** Der Löschzweig des Delta-Syncs meldete
  jeden Schichtmorgen „Schicht entfernt" für den Dienst, den der Nutzer gerade antrat — beide
  Fälle landen im selben Zweig (`!newAlarmsMap.containsKey(eventId)`), aber nur einer ist eine
  Änderung des Dienstplans. `expiredEventIds` trennt sie: der Alarm wird weiterhin gecancelt und
  gelöscht (in dieser Reihenfolge), nur `notifyDeleted()` unterbleibt und das Log sagt „Weckzeit
  verstrichen, Termin läuft weiter". `AlarmUseCaseDeltaSyncTest` hält BEIDE Richtungen fest —
  der Regressionswächter für die echte Löschmeldung ist der wichtigere Teil.
- **`AutomaticZenRule`, nicht rohes `NotificationManager.setInterruptionFilter()`.** Der
  rohe Filter überschreibt kommentarlos das manuelle DND des Nutzers und jede fremde
  Automatisierung (Bixby/Tasker/System-Zeitplan) — kein Owner-Konzept, letzter Schreiber gewinnt.
  Die selbst registrierte Zen-Regel (eigene, rule-scoped `ZenPolicy`) erscheint stattdessen
  sichtbar unter Einstellungen → Ton → Nicht stören → Zeitpläne und koexistiert sauber.
- **Nur ab API 30 (Android 11).** Der 7-arg-`AutomaticZenRule`-Konstruktor mit
  `configurationActivity`-Ownership (kein `ConditionProviderService` nötig) existiert erst ab
  API 30; darunter bietet `DndPermissionHelper.isFeatureSupported()`/`DndScheduleUseCase.isSupported()`
  das Feature bewusst gar nicht an, statt einen zweiten Ownership-Pfad zu pflegen.
- **Policy ist vollständig Nutzer-konfigurierbar (`DndPrefs.Policy`), NICHTS hart codiert.**
  `buildAutomaticZenRule()` liest `prefs.policyNow()` und baut die `ZenPolicy` daraus — keine
  Kategorie ist im Code fest verdrahtet. **Vorfall, der zu dieser Entscheidung führte (28.07.2026):**
  ein erster Entwurf setzte `allowMedia(false)` und `allowAlarms(false)` hart, "um konsequent zu
  sein" — das schaltete live einen laufenden Podcast stumm und ließ sich vom Nutzer nicht mal mehr
  manuell zurückregeln (`allowMedia` wirkt auf die Medien-Audiospur, nicht nur auf Töne). Seither:
  Defaults `blockCalls`/`blockMessages`/`blockConversations`/`blockReminders`/`blockEvents` = `true`
  (das ist der eigentliche Zweck von „Nicht stören"), `blockSystem`/`blockMedia`/`blockAlarms` =
  `false` (unberührt, bis der Nutzer es explizit anschaltet). `allowRepeatCallers` bleibt eigene
  Nutzer-Option (Default an) — wiederholte Anrufer (Notfall) kommen durch, wenn `blockCalls` aktiv
  ist. Der Wecker selbst ist von `blockAlarms` unabhängig: `AlarmSoundService.setBypassDnd(true)`
  umgeht JEDE DND-Konfiguration, auch die eigene — `blockAlarms` betrifft nur FREMDE Wecker-Apps.
- **`ensureZenRule()` aktualisiert eine bereits registrierte Regel bei JEDEM Tick mit
  `updateAutomaticZenRule()`**, nicht nur bei der Erstregistrierung. Sonst wirkt eine
  Policy-Änderung des Nutzers erst nach einer Neuinstallation, weil die einmal registrierte Regel
  ihre alte `ZenPolicy` sonst dauerhaft behält — exakt der Fehler, der beim ersten Bau übersehen
  wurde (siehe Vorfall oben: die Regel hätte den Fix sonst erst nach Deinstallation bekommen).
- **Mehrere gleichzeitig aktive Zen-Regeln kombinieren sich vermutlich „freizügigste gewinnt" pro
  Kategorie** — beobachtet am 28.07.2026: während unsere Regel UND ein bereits vorhandener,
  fremder System-/Hersteller-Modus ("Schlafenszeit", 22–6 Uhr) gleichzeitig aktiv waren, blieben
  Anrufe/Klingelton hörbar (der andere Modus erlaubte sie, das gewann), aber Medien wurden stumm
  (nur unsere Regel hatte dazu überhaupt eine Meinung). Nicht durch eigenen Code behebbar — unsere
  Regel kann eine Kategorie nicht zuverlässiger blockieren, als es die am wenigsten strenge
  gleichzeitig aktive fremde Regel erlaubt. **"Koppeln mit Schlafenszeit" bewusst NICHT gebaut
  (29.07.2026, AOSP-Quellcode-verifiziert, geräteunabhängig):** Apps können laut Android-API nur
  EIGENE `AutomaticZenRule`s lesen/steuern (`getAutomaticZenRules()` ist auf das aufrufende Package
  beschränkt) — ein fremder/System-Modus kann weder ausgelesen noch direkt geschaltet werden. Auch
  ein systemweiter Theme-Wechsel (das eigentlich interessante an Schlafenszeit, da Dimmen bereits
  über den Schicht-Dimmer gelöst ist) ist unerreichbar: `UiModeManager.setNightMode()` verlangt
  `android.permission.MODIFY_DAY_NIGHT_MODE` (`protectionLevel="signature|privileged|role"`, dazu
  `@hide` — nicht mal Teil des öffentlichen SDK, siehe `core/res/AndroidManifest.xml` im
  AOSP-Quellcode). `setApplicationNightMode()` (die einzige App-erreichbare Variante) wirkt
  nachweislich nur auf die eigene App. Nicht erneut aufrollen ohne neuen Anlass.
- **Eigener Request-Code `REQ_DND_TICK = 7712`**, eigene rollierende Exact-Alarm-Kette
  (`DndScheduleReceiver`) — bewusst NICHT mit dem Dimmer-Tick (`REQ_TICK = 7710`,
  `DimScheduleUseCase`) oder der 6h-Wartung (Code 0) zusammengelegt. Zwei fachlich unabhängige
  Features, unabhängig deaktivierbar; ein Bug in einem darf nicht das andere mitreißen.
- **`DndScheduleUseCase.CONDITION_ID` ist `by lazy`.** Eager ausgewertet scheiterte `Uri.parse()` im
  Unit-Test-JVM bereits bei der Companion-Initialisierung und riss über die dauerhaft gescheiterte
  Klassen-Initialisierung auch fremde Tests mit, die die Klasse nur mocken wollten. Produktionsverhalten
  unverändert.
- **`ensureZenRule()` prüft `Build.VERSION.SDK_INT` direkt**, nicht nur über `isSupported()` –
  Lint verfolgt die Absicherung für `@RequiresApi`-Aufrufe (`buildAutomaticZenRule()`) nur bei
  einem lokalen, direkten SDK_INT-Vergleich zuverlässig durch mehrere Funktionsebenen.
- **Am Fairphone 6 (Android 16) verifiziert (28.07.2026):** Die Zen-Regel registriert sich echt,
  erscheint unter Einstellungen → Ton → Nicht stören → Zeitpläne mit funktionierendem
  `configurationActivity`-Link, `setAutomaticZenRuleState()` wird korrekt aufgerufen und der
  Zustand korrekt berechnet (`ZEN_MODE change value` je nach aktivem Fenster). **Zweiter Lauf
  (29.07.2026) nach dem Policy-Fix:** `adb shell dumpsys notification --noredact` zeigt die
  tatsächlich registrierte Regel mit `alarms=allow, media=allow, calls=disallow, messages=disallow,
  repeatCallers=allow` — exakt die neuen Defaults, `updateAutomaticZenRule()` nachweislich beim
  Tick aufgerufen. **Dritter Lauf (14.08.2026, Emulator): beide zuvor offenen Punkte belegt.**
  „Während der Dienstzeit" mit echten Kalenderzeiten: am AD1-Tag um 04:00 `zen_mode=1`/
  `STATE_TRUE`, um 05:30 `zen_mode=0`/`STATE_FALSE` — der Rufbereitschaft-Cutoff kappt, obwohl
  der Termin bis 24:00 läuft und der Alarm noch steht. Und die Anrufer-Ausnahme
  (`allowRepeatCallers`) per simuliertem Anruf (`adb emu gsm call`): erster Anruf
  `SKIP_RINGING (Inaudible: isVolumeOverZero=true, shouldRingForContact=false)` im
  Telecom-Log — also von UNSERER Regel unterdrückt, nicht von der Lautstärke —, der
  Wiederholungsanruf derselben Nummer 34 s später `START_RINGER` + `START_VIBRATOR`.
  Einschränkung: simulierte Telefonie, aber die Entscheidung fällt in Androids
  `matchesCallFilter` gegen die registrierte Regel, also im identischen Codepfad.
- **Logcat-Fallstrick beim Debuggen, kein Bug:** `W/System.err` mit `java.lang.Exception: Stack
  trace` + `Thread.dumpStack()` rund um `setAutomaticZenRuleState()` ist Androids eigenes internes
  Aufruf-Tracing für Zen-Änderungen — sieht wie ein Crash aus, ist keiner. Der direkt folgende
  `V/Settings: ZEN_MODE change value to X` sowie der eigene Erfolgs-Log bestätigen den echten
  Aufrufausgang.


## Verifikationsregel: einen alarmabgeleiteten Trigger IMMER nach dem Klingeln messen

Die Zusicherung „Die Dienstzeit-Fenster kommen aus `ShiftSpanStore`, nicht aus dem Alarm-Bestand"
stammt aus einem Nachweis, der **unvollständig war und deshalb einen echten Bug durchgelassen**
hat — das ist der lehrreiche Teil, nicht der Fix.

Geprüft worden war nur ein AD1-Tag, an dem der Wecker noch NICHT gefeuert hatte; dort sah alles
richtig aus. Am 20.08. um 08:00, mitten in der Frühschicht (Termin 06:00–14:12, Wecker 05:30
bereits gelaufen), war „Nicht stören" AUS: `zen_mode=0`, Regel `STATE_FALSE`. Ursache: die
Fenster kamen aus dem Alarm-Bestand, und ein Alarm überlebt seine Weckzeit nicht. Der Code war
seit v1.18.0 unverändert und hatte mehrere Code-Sweeps überstanden — im Code sah die Kopplung
plausibel aus. Aufgefallen ist sie erst beim Messen zum richtigen Zeitpunkt.

**Regel daraus:** Jeden Trigger, der aus dem Alarm-Bestand abgeleitet ist, mindestens einmal NACH
dem Feuern des Alarms messen — vorher misst man nur den halben Tag. Und: eine Code-Prüfrunde
ersetzt die Geräteverifikation nicht; das ist inzwischen der dritte Beleg dafür.
