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

## Navigationsschublade statt unterer Leiste (v1.38.0)

- **Sechs Ziele passen nicht in eine `NavigationBar`.** Material 3 sieht dort drei bis fuenf vor.
  Auf dem Geraet des Eigentuemers (hochgestellte Anzeigegroesse: Dichte 558 statt 480 bei
  `font_scale = 1.0`, aus 1116 px werden 320 dp) blieben **53,3 dp pro Fach**, waehrend die
  aktive Pille hinter dem Symbol im Standard **64 dp** breit ist. Sie war damit breiter als ihr
  eigenes Fach: das erste Element wurde links angeschnitten, benachbarte Pillen ueberschnitten
  sich, die Beschriftungen kuerzten zu "Dimm..." und "Einste...". Auf einem gewoehnlichen
  411-dp-Geraet faellt das kaum auf - **320 dp ist die reale untere Grenze, nicht 360.**
  Die Tabs nach OBEN zu verschieben loest das nicht: sechs Reiter sind auch oben sechs, und die
  Hauptnavigation verliesse den Daumenbereich. Die Ursache ist die Anzahl, nicht die Position.

- **Die Schublade gehoert in `MainContentScreen`, NICHT in `MainScreen` oder `MainActivity`.**
  Die vier Onboarding-Gates sind Zweige DESSELBEN `when` wie `MainContentScreen` - sie ersetzen
  ihn, sie liegen nicht darueber. Nur deshalb existiert die Schublade waehrend eines Gates gar
  nicht. Eine Ebene hoeher liesse sich per Wischgeste mitten aus einem Gate herausnavigieren,
  ohne dessen Dismissed-Flag zu schreiben; `handleAuthenticationSuccess()` wuerfe den Nutzer
  beim naechsten Durchlauf zurueck - dauerhaft. **Am Geraet ist das nicht pruefbar:** am
  Emulator waren am 01.09.2026 alle Gate-Bedingungen erfuellt, es liess sich keines
  provozieren. Deshalb haelt `SchubladeErreichbarkeitTest` die Platzierung mechanisch fest.

- **Es MUSS `ModalDrawerSheet(drawerState = ...)` sein, nicht die parameterlose Ueberladung.**
  In Material3 1.4.0 (`NavigationDrawer.kt`) enthaelt `ModalNavigationDrawer` selbst **keinen**
  Back-Handler (Z. 332) - es schliesst nur bei Scrim-Klick und Wischen. Den
  `PredictiveBackHandler(enabled = drawerState.isOpen)` registriert ausschliesslich die
  Ueberladung mit `drawerState` (Z. 633 -> `DrawerPredictiveBackHandler` Z. 643 -> Z. 955); die
  parameterlose (Z. 590) uebergibt `drawerPredictiveBackState = null`. **Ein einziger Parameter
  entscheidet, ob die App sich beendet:** auf dem Home-Tab ist der `BackHandler` in `MainScreen`
  bewusst aus (`enabled = !onHomeTab`), ein Zurueck bei offener Schublade traefe dort sonst den
  Systemdefault. Am Emulator verifiziert (01.09.2026): mit der richtigen Ueberladung schliesst
  Zurueck die Schublade, die App bleibt im Vordergrund - der bestehende `BackHandler` brauchte
  **keine** Aenderung.

- **Die Kopfzeile klappt ein (`enterAlwaysScrollBehavior`) statt zu scrollen oder zu stehen.**
  Drei Moeglichkeiten standen zur Wahl: mitscrollen (dann scrollt der Hamburger weg, und weit
  unten in einer langen Liste bliebe nur die Randwischgeste - die bei Gestennavigation mit dem
  System-Zurueck konkurriert), fest stehen (kostet dauerhaft Platz), oder einklappen. Der
  Eigentuemer hat die dritte gewaehlt. Die Kopplung sitzt als
  `Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)` am `Scaffold` und wirkt dadurch
  fuer alle sechs Tabs - auch fuer die beiden mit `LazyColumn` (Dimmen, Hue), am Geraet geprueft.

- **Ein Name je Bereich, aus einer Liste.** Vorher gab es drei Quellen: die Beschriftung in der
  Leiste, die Ueberschrift im Tab-Inhalt und die Reihenfolge des `MainTab`-Enums - und sie
  wichen voneinander ab ("Home" gegen "Uebersicht", "Dimmen" gegen "Schicht-Dimmer"). Seit
  v1.38.0 traegt `ui/navigation/MainTabZiele.kt` beides. Das Enum bleibt unangetastet: es ist
  das Rueckweg-Gedaechtnis von 13 `NavigationState`-Zustaenden und geht die Darstellung nichts
  an. `MainTabZieleTest` haelt fest, dass jeder Enum-Wert genau einmal vorkommt - ein
  vergessener siebter Bereich waere sonst schlicht unerreichbar.

- **Keine Direkteinstiege aus der Schublade zu den Regel-Editoren.** `HueRuleConfig` und
  `DimmerRuleConfig` unterscheiden ihren Rueckweg ueber ein einzelnes `cameFromSettingsList`-
  Boolean, das an drei Stellen dupliziert ist. Ein dritter Einstiegspfad sprengt es und
  braeuchte erst einen `Herkunft`-Enum. Die Schublade enthaelt genau die sechs Bereiche.

