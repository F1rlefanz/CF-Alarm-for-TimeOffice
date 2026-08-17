# Navigation und Zurueck-Verhalten — Hergang

> Hergang zu den Kurzregeln in `CLAUDE.md` und in der `SKILL.md` daneben: welcher Bug die
> Regel erzwungen hat, welche Messung sie belegt, welche Alternative verworfen wurde.
> Jede Zeile hier hat einmal echten Schaden verhindert — im Zweifel gilt sie, nicht die Intuition.

- **Zurueck gehoert dem `BackHandler` in `MainScreen`.** Die App navigiert ueber einen eigenen
  `NavigationState`, nicht ueber Navigation-Compose — es gibt **keinen Backstack**, der Zurueck
  von allein behandelt. Ohne Handler landet jeder Druck beim Activity-Default und **beendet die
  App**: aus „Kalender-Events" sprang der Nutzer auf den Android-Homescreen (am Fairphone 6
  gemeldet, 15.07.2026). Wer einen neuen `NavigationState` ergaenzt, muss ihn dort mitbedenken —
  der `else`-Zweig faengt jeden Unterscreen ab, die Sonderfaelle stehen davor. **VIER Gates sind
  nicht optional:** `BatteryExemption`, `UnusedAppRestrictions` und `TimeOfficeHealthCheck` muessen
  wie „Spaeter" wirken, also ihr jeweiliges Dismissed-Flag schreiben
  (`dismissBatteryPrompt()` bzw. `UnusedAppRestrictionsHelper.setDismissed`/
  `TimeOfficeHealthHelper.setPromptDismissed`) — sonst schickt `handleAuthenticationSuccess()` den
  Nutzer sofort zurueck und Zurueck sieht wirkungslos aus; `OEMWarning` muss wie „Verstanden" die
  Wartungskette anstossen (`finishOnboarding()`), sonst steht ein Nutzer ohne 6h-Wartung da.
  Dazu ein `MainContent`-Zweig: auf einem Nicht-Home-Tab fuehrt Zurueck auf HOME
  (Android-Konvention fuer Bottom-Navigation). Auf dem Home-Tab bleibt der Handler bewusst **aus**
  — dort ist Zurueck wirklich „App verlassen", und der Systemdefault kann das inkl.
  Predictive-Back besser.
- **„Später" beim Akku-Gate heißt ERLEDIGT, nicht abgebrochen.** Die Gate-Kette in
  `handleAuthenticationSuccess()` geht weiter, sobald das Akku-Gate **aufgelöst** ist —
  Ausnahme erteilt ODER vom Nutzer abgelehnt (`batteryGateResolved`). Vorher verlangten die Zweige
  3 und 4 beide `hasBatteryExemption`: wer „Später" tippte (ein ausdrücklich vorgesehener,
  persistierter Weg), fiel aus JEDEM Zweig heraus — Zweig 2 durch das Dismissed-Flag, Zweig 3/4
  durch die fehlende Ausnahme —, und `proceedPastGates()` erreicht diesen Nutzer nie wieder. Der
  Schritt „App bei Nichtnutzung pausieren" wurde ihm damit NIE angeboten, obwohl genau dieser
  Schalter am 20.07.2026 die App force-gestoppt und dabei alle AlarmManager-Alarme gelöscht hat.
  Der Kurzschluss in `MainScreen` (spart den Async-Call, solange das Gate noch offen ist) rechnet
  mit demselben `batteryGateResolved`. Eine Ablehnung des Akku-Gates ist eine Aussage über die
  Akku-Ausnahme, keine über die davon unabhängigen Gates dahinter.
- **`NavigationState.HueRuleConfig`/`DimmerRuleConfig` brauchen `cameFromSettingsList`, nicht nur
  `returnToTab`.** `HueRuleConfig` ist auf zwei Wegen erreichbar (direkt vom **HUE-Tab** „Neue
  Regel" ODER über `HueSettings` „Bearbeiten"), und der System-Back (`BackHandler`) UND der
  Screen-eigene Zurück-Pfeil/Speichern-Button MÜSSEN für denselben Einstiegspfad zum selben
  Ziel führen. Vor v1.22.0 taten sie das nicht: der Screen-eigene Weg ging immer zur Settings-Liste
  (falsch bei Direkteinstieg vom Tab), der System-Back-Weg ging immer direkt zum Tab (falsch bei
  Einstieg über die Settings-Liste) — zwei sich widersprechende, feste Annahmen statt einer
  gemeinsamen. Real am Fairphone verifiziert (05.08.2026, alle 4 Hue-Kombinationen).
  `DimmerRuleConfig` hat heute nur den Weg über `DimmerSettings` (Default `true`), trägt das Flag
  aber gleich mit, damit ein späterer Direktpfad automatisch korrekt zurückführt.

