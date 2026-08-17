# TimeOffice-Abhaengigkeit — Hergang

> Hergang zu den Kurzregeln in `CLAUDE.md` und in der `SKILL.md` daneben: welcher Bug die
> Regel erzwungen hat, welche Messung sie belegt, welche Alternative verworfen wurde.
> Jede Zeile hier hat einmal echten Schaden verhindert — im Zweifel gilt sie, nicht die Intuition.

- **CFAlarms gesamte Funktion hängt an einer Kette außerhalb dieser App**: TimeOffice
  (`de.pradtke.timeoffice`) schreibt den Dienstplan (inkl. Krankschreibungen) lokal in einen
  eigenen Google-Kalender ("Timeoffice Dienstplanfeed", ein besessener Sekundärkalender im
  Google-Konto, keine URL-Subscription — verifiziert per Sharing-/Zugriffsberechtigungs-Optionen
  in dessen Kalender-Einstellungen). CFAlarm liest von dort. **Live am 30.07.2026 nachgewiesen**:
  TimeOffice selbst war von "App bei Nichtnutzung pausieren" (aktiv) UND Akku-Optimierung
  "Optimiert" betroffen — der Sync blieb ~4 Tage stehen, obwohl TimeOffice die Krankschreibung
  längst intern kannte (🤒-Symbol im eigenen Dienstplan sichtbar). CFAlarms eigene Alarme liefen
  in der Zwischenzeit einwandfrei — das Problem war unsichtbar, bis man gezielt danach sucht.
- **`TimeOfficeHealthHelper`/`TimeOfficeHealthCard`/`TimeOfficeHealthOnboardingScreen`** (seit
  v1.19.0) spiegeln dieses Muster aus `BatteryOptimizationHelper`/`UnusedAppRestrictionsHelper`
  auf die externe Abhängigkeit. **Wichtige Einschränkung, absichtlich so gelassen**: Es gibt
  **keine öffentliche API**, um den "Nicht verwendete Apps"/Hibernation-Status einer ANDEREN App
  abzufragen (`PackageManagerCompat.getUnusedAppRestrictionsStatus()` funktioniert nur für die
  aufrufende App selbst) — nur die Akku-Optimierungs-Ausnahme ist für fremde Pakete prüfbar
  (`PowerManager.isIgnoringBatteryOptimizations(anderesPackage)` akzeptiert jeden Package-Namen,
  siehe `BatteryOptimizationHelper.isExempted(context, packageName)`). Die Karte zeigt deshalb für
  Akku-Optimierung echtes Grün/Rot, für "Nicht verwendete Apps" bewusst **keinen** vorgetäuschten
  Status. Wer hier versucht, einen Hibernation-Status für TimeOffice "irgendwie" zu ermitteln,
  jagt einer API hinterher, die nicht existiert.
- **Kein `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`-Dialog für TimeOffice.** Anders als beim
  eigenen Akku-Onboarding (das einen Ein-Klick-System-Dialog auslöst) führt der Aktions-Button
  hier IMMER auf TimeOffices eigene App-Info-Seite (`ACTION_APPLICATION_DETAILS_SETTINGS`) — nicht
  dokumentiert/getestet, ob Android den Bestätigungsdialog für ein fremdes Package überhaupt
  zulässt. Der App-Info-Weg ist der einzige, der am 30.07.2026 live nachweislich funktioniert hat.
- **`<queries>` in AndroidManifest.xml ist Pflicht** für `TimeOfficeHealthHelper.isInstalled()`
  (Android 11+ Package-Visibility) — ohne die Deklaration liefert `getPackageInfo()` für
  `de.pradtke.timeoffice` immer `NameNotFoundException`, unabhängig davon ob installiert.
- **Kein Unit-Test für `TimeOfficeHealthHelper`** — bewusst, gleiche Konvention wie
  `BatteryOptimizationHelper`/`UnusedAppRestrictionsHelper` (siehe deren fehlende/minimale Tests):
  dünne Android-Wrapper ohne eigene Logik werden hier nicht getestet, nur reine Funktionen wie
  `UnusedAppRestrictionsHelper.needsPrompt()`.
- **Das automatische Onboarding-Gate für den TimeOffice-Health-Prompt hängt an
  `NavigationViewModel.handleAuthenticationSuccess()`, NICHT nur an `proceedPastGates()`.**
  `proceedPastGates()` (MainScreen) verkettet die Gates nur, wenn der Nutzer die vorherigen Screens
  gerade durchläuft. `handleAuthenticationSuccess()` ist der EINZIGE Pfad, der bei jedem
  App-Vordergrund/Auth-Erfolg automatisch prüft, ob noch ein Gate offen ist — ohne einen eigenen
  Zweig dafür sehen Bestandsnutzer, die Kalender/Akku/Unused-App-Gates schon vor diesem Feature
  durchlaufen hatten, den TimeOffice-Prompt NIE automatisch (nur noch über die permanente
  Status-Tab-Karte). Genau der Fall, für den das Feature gebaut wurde. Fix seit v1.22.0: vierter
  `else if`-Zweig in `handleAuthenticationSuccess()`, gleiche Gate-Reihenfolge wie
  `proceedPastGates()`.

