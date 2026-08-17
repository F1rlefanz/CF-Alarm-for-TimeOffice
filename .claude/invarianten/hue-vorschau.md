# Hue-Vorschau & Lampentest

> Ausgelagert aus `CLAUDE.md` (17.08.2026). Dort steht die Kurzregel, hier der Hergang:
> warum die Regel existiert, welcher Bug sie erzwungen hat, welche Messung sie belegt.
> **Vor Änderungen in diesem Bereich lesen.**

---

### Hue-Vorschau & Test

- **Die Regel-Vorschau raeumt IMMER auf** — unabhaengig vom Auto-Aus der Regel. Das Aufraeumen
  haing frueher an `hasAutoOff`, und das steht bei einer **neuen** Regel auf `false`: Der
  Vorschau-Knopf schaltete das Licht an und liess es an, ohne Weg zurueck ausser der Hue-App.
  Der Unterschied zum echten Weckvorgang ist Absicht: der **laesst** ohne Auto-Aus an (so
  gewollt), nur die Vorschau raeumt auf — sie ist ein Ausprobieren, kein Lichtschalter. Der
  Hinweistext trennt beides. `RulePreviewCleanupTest` haelt das fest.
- **Beim Sonnenaufgang haengt das Aus HINTER der Rampe** (`SUNRISE_TEST_DURATION_MINUTES +
  AUTO_OFF_TEST_DURATION_SECONDS`), nicht bei flachen 20s. Sonst wuergt es die laufende native
  Bridge-Transition mitten im Aufblenden ab. Derselbe Gedanke wie `sunriseOffsetMinutes` in
  `autoOffTargetsOf()`.
- **`runLightTest()` blitzt LAMPEN, niemals Gruppen** — auch wenn eine Gruppe pro PUT mehrere
  Lampen erreicht und damit sparsamer waere. **Gruppen ueberschneiden sich beliebig, auch
  untereinander**: real (Bridge des Nutzers, verifiziert 15.07.2026) liegt Lampe 4 in
  „Wohnzimmer", „Deckenlampe" UND „Zuhause"; von 10 Gruppen decken sich mehrere. Jede Gruppe
  anzufunken heisst also mehrere Alerts auf derselben Lampe — und weil jedes Ziel ein eigener
  HTTP-PUT ist, kommen die zeitversetzt an: Aufleuchten, Pause, Blinken. Genau so wurde es
  gemeldet. Die Lampen-Ebene ist die **einzige**, auf der „jede Lampe genau einmal" strukturell
  gilt, egal wie die Gruppen geschnitten sind. Deshalb nimmt `flashLight(lightId)` bewusst
  **kein** `isGroup`-Flag mehr — der Fehler soll nicht wieder formulierbar sein.
  (Zwischenstand v1.11.1 „Gruppen + Lampen ohne Gruppe" war nur die halbe Miete: er entdoppelte
  Lampen gegen Gruppen, nicht Gruppen gegeneinander.)
- **`flashLight` nutzt `lselect` und bricht es nach `FLASH_DURATION` (4s) selbst ab.** `select`
  waere nur ein einzelner Blitz — als Beweis zu leise. `lselect` blinkt aber von sich aus **15s**,
  und das ist als Rueckmeldung zu lang (vom Tester gemeldet). Gegen die echte Bridge verifiziert:
  `alert:"none"` bricht ein laufendes `lselect` ab und die Lampe faellt in ihren vorherigen
  An/Aus-Zustand zurueck.
- **Der Abbruch-Timer und das Vorschau-Auto-Aus haengen an `followUpScope`, nicht am Aufrufer.**
  Beide muessen auch feuern, wenn der Nutzer den ausloesenden Bildschirm laengst verlassen hat —
  ein `viewModelScope` waere gecancelt, und das Licht bliebe an bzw. die Lampe am Blinken.
- **Nichts in `runLightTest()` darf `refreshLightTargets()` benutzen.** Das ist
  **fire-and-forget** (startet nur eine Coroutine); der `uiState` direkt danach ist immer noch
  leer. Genau das war „der erste Klick tut nichts, der zweite blinkt": Direkt nach dem Koppeln
  ist die Liste leer, der erste Klick stiess den Refresh an, las die leere Liste und meldete
  „Keine Lampen gefunden". Wer Ziele braucht, ruft `getAllLightTargets()` und **wartet**.

