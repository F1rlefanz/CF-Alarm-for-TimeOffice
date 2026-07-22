# Handoff — CF-Alarm for TimeOffice

**Lebendes Dokument.** Erledigtes wird gelöscht, nicht abgehakt oder durchgestrichen — was hier steht, ist offen.
Die Historie steht im Git-Log, nicht hier.

**Stand:** 22.07.2026 · `main` = **v1.15.1 / versionCode 64** · alles gemerged und gepusht.

> **Ungeprüft:** der WLAN-Subnetz-Fix (v1.15.1) ist per Unit-Test gegen die realen Log-Fälle
> (192.168.44.x, 10.0.9.x, CGNAT-Adresse) abgesichert und CI-grün, aber **nicht** am Gerät in
> einem echten fremden WLAN bestätigt. Alles Übrige aus v1.15.0/v1.15.1 ist bereits am Gerät
> bzw. im Log verifiziert.

---

## Offen

### 1. WLAN-Subnetz-Fix am Gerät bestätigen ← wichtigster Punkt

Auslöser: drei Debug-Logs (20.–22.07.) zeigten wiederholte 10-Sekunden-Timeouts beim
Hue-Bridge-Zugriff, obwohl der WLAN-Check aus v1.9.5 schon existierte. Ursache war doppelt:
der Check prüfte nur „irgendein WLAN", nicht das richtige Subnetz, und griff im
Alarm-Hot-Path (gecachte Verbindung, 30-Minuten-Cache) gar nicht.

**Test:** Bridge zuhause verbinden (Status-Tab zeigt „Verbunden"), dann Handy in ein fremdes
WLAN oder auf Mobilfunk umschalten, während die Verbindung noch als „verbunden" gilt (siehe
30-Minuten-Cache). Zu prüfen:
1. Kommt im Log sofort `"not reachable from current network"` statt eines
   `SocketTimeoutException` nach 10s?
2. Bleibt der Verbindungsstatus im Hue-Tab bei „Verbunden" stehen (kein falscher
   „Fehler"-Banner), bis man wirklich wieder im Heim-WLAN ist?
3. Funktioniert die Lichtsteuerung wieder sofort, sobald man zurück im Heim-WLAN ist (kein
   unnötiger Reconnect-Zyklus)?

### 2. Log-Rauschen bei „Nicht verwendete Apps"-Check

Beim Prüfen der drei Logs aus Punkt 1 aufgefallen, aber bewusst nicht mit angefasst: Wenn man
den Status-Tab verlässt, während `UnusedAppRestrictionsHelper.isRestricted()` noch läuft,
wird die dabei entstehende `CancellationException` als `E/CFAlarm.UnusedAppRestrictions:
Failed to check unused-app-restrictions status` samt zwei
`LeftCompositionCancellationException`-Stacktraces geloggt. Funktional harmlos (fail-open,
liefert dann `false`), aber irreführend fürs Log-Lesen. Ursache:
`UnusedAppRestrictionsHelper.kt:73-78`, `catch (e: Exception)` fängt auch die normale
Coroutine-Cancellation ab. Fix wäre, `CancellationException` separat durchzureichen statt zu
loggen.
