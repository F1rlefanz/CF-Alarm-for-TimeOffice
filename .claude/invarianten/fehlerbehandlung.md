# Fehlerbehandlung

> Ausgelagert aus `CLAUDE.md` (17.08.2026). Dort steht die Kurzregel, hier der Hergang:
> warum die Regel existiert, welcher Bug sie erzwungen hat, welche Messung sie belegt.
> **Vor Änderungen in diesem Bereich lesen.**

---

### Fehlerbehandlung

- **`SafeExecutor.safeExecute()` wirft `CancellationException` WEITER, statt sie in einen `AppError`
  zu verpacken.** Eine Cancellation ist kein Fehler des Aufrufs, sondern die Ansage, dass die
  umgebende Coroutine beendet wird — sie muss die Aufrufkette hochlaufen, und `Result.failure` ist
  keine Aufrufkette. Verpackt verlor sie ihre Identität, und dadurch lief der ausdrückliche
  `catch (e: CancellationException) { throw e }` der Delta-Sync-Schleife ins Leere:
  `AlarmUseCase.scheduleSystemAlarm()` ist über `safeExecute` gewrappt, die Cancellation kam dort als
  gewöhnliches Failure an und wurde als Fehler EINES Events verbucht — die Schleife lief stur über
  alle restlichen Events weiter, ohne einen einzigen zu re-armieren, während die Abschlusszeile
  „complete" meldete. Dasselbe gilt für `AlarmRepository.getAllAlarms()`/`isPersistenceBlocked()`:
  eine Cancellation aus `awaitInitialLoad()` sagt nichts über den Bestand und darf nicht als
  „Persistenz gesperrt" gedeutet werden.
- **„Kein Token vorhanden" landet als `UnknownError` im Log — und das umzubiegen wäre ein Fehler.**
  `CalendarUseCase` wirft für alle `TokenException`-Fälle ein generisches `Exception(text)`
  (bei `getCalendarEventsWithStatus`); `toAppError()` hat dafür keinen Zweig und klassifiziert im
  `else` als `AppError.UnknownError` (Severity CRITICAL, Nutzertext „Ein unerwarteter Fehler ist
  aufgetreten"). Im Log steht deshalb „❓❌ Unknown Error … Calendar events require authorization"
  samt vollem Stacktrace auf ERROR — am Emulator im Boot-Pfad gesehen (14.08.2026), wenn der
  `BootReceiver` läuft, bevor eine Anmeldung besteht. Für die Log-Auswertung ist das Rauschen, denn
  Release-Logs enthalten WARN+.
  **Die naheliegende „Korrektur" — auf `AppError.AuthenticationError` abbilden — ist trotzdem
  falsch und darf nicht gemacht werden.** An genau diesem Typ hängt
  `CalendarUseCase.invalidateTokenIfRejectedByGoogle()`, und der ist bewusst NUR für den
  401-Fall gedacht (Google lehnt ein vorhandenes Token ab → Token samt
  Play-Services-Cache verwerfen). „Lokal gar kein Token" ist der andere Fall: da gibt es nichts zu
  invalidieren, und der Aufruf würde den GMS-Cache ohne Anlass leeren. Wer hier aufräumen will,
  braucht eine EIGENE Klassifizierung — nicht die vorhandene. Das eigentliche Verhalten ist
  korrekt: der Fehler wird geworfen und NICHT zur leeren Eventliste degradiert, die Wartung bricht
  ab („Token refresh failed, aborting maintenance"), und die bestehenden Alarme bleiben armiert
  (am Gerät gegengeprüft: nach einer Abmeldung lagen die 8 Alarme unverändert im AlarmManager).

