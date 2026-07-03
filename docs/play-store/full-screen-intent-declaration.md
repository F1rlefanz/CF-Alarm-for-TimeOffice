# Play Console – Deklaration `USE_FULL_SCREEN_INTENT`

> Play Console verlangt für Apps mit `USE_FULL_SCREEN_INTENT` (Android 14 / API 34+)
> eine Deklaration im Bereich **App-Inhalt → Vollbild-Intent-Berechtigung**. Nur Apps
> mit Kernfunktion „Wecker/Alarm" oder „Telefonie" erhalten den Standard-Grant; alle
> anderen müssen begründen. Der **englische Block** ist paste-ready.

---

## Betroffene Deklaration (AndroidManifest.xml)

```xml
<uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT" />

<activity
    android:name=".AlarmFullScreenActivity"
    android:showWhenLocked="true"
    android:turnScreenOn="true"
    android:directBootAware="true"
    android:excludeFromRecents="true"
    android:exported="false" />
```

- Der Vollbild-Intent wird ausschließlich im Weck-Moment ausgelöst
  (`AlarmReceiver` → `setFullScreenIntent` auf einer `CATEGORY_ALARM`-Notification),
  um `AlarmFullScreenActivity` über dem Sperrbildschirm anzuzeigen.
- Kein Einsatz für Werbung, Chats, Promotions oder sonstige nicht-zeitkritische Inhalte.

---

## Play-Console-Formular – empfohlene Auswahl

- **Kernfunktion der App:** „Alarm / Wecker" (Alarm clock).
- **Wird `USE_FULL_SCREEN_INTENT` nur für Wecker/eingehende Anrufe genutzt?** → **Ja.**
- **Kategorie im Store-Eintrag:** möglichst „Tools" mit klarer Alarm-Positionierung,
  damit der Alarm-Kernnutzen erkennbar ist.

---

## Paste-ready justification (English → Play Console)

> CF-Alarm for TimeOffice is an alarm clock for shift workers. Its core function is to
> wake the user for safety-critical shift start times (e.g. an early hospital shift).
>
> `USE_FULL_SCREEN_INTENT` is used **only** to present the alarm: when a scheduled
> shift alarm fires, the app posts a high-priority `CATEGORY_ALARM` notification with a
> full-screen intent that opens the alarm screen over the lock screen
> (`showWhenLocked` + `turnScreenOn`), so the user can see and dismiss/snooze the alarm
> even when the device is locked and the screen is off.
>
> The full-screen intent is triggered exclusively at alarm time. It is never used for
> advertising, promotions, chat/messages, or any non-time-critical content. This is the
> standard, expected behavior of an alarm-clock app and is required for the app to
> reliably wake shift workers.

---

## Paste-ready Begründung (Deutsch)

> CF-Alarm for TimeOffice ist ein Wecker für Schichtarbeiter. Kernfunktion ist das
> zuverlässige Wecken zu sicherheitskritischen Schichtbeginnen (z. B. Frühdienst im
> Krankenhaus).
>
> `USE_FULL_SCREEN_INTENT` wird **ausschließlich** für den Wecker verwendet: Löst ein
> geplanter Schicht-Wecker aus, zeigt die App eine hochpriorisierte
> `CATEGORY_ALARM`-Notification mit Full-Screen-Intent, die den Weckbildschirm über dem
> Sperrbildschirm öffnet (`showWhenLocked` + `turnScreenOn`). So kann der Nutzer den
> Wecker sehen und beenden/snoozen, auch wenn das Gerät gesperrt und der Bildschirm aus
> ist. Der Full-Screen-Intent wird nur zur Weckzeit ausgelöst – nie für Werbung,
> Promotions, Nachrichten oder andere nicht-zeitkritische Inhalte.

---

## Hinweise für den Reviewer / Demo-Schritte

1. Einen (Test-)Wecker in wenigen Minuten stellen und das Gerät sperren.
2. Zur Weckzeit öffnet sich `AlarmFullScreenActivity` über dem Sperrbildschirm,
   der Bildschirm geht an, der Weckton läuft.
3. Dismiss/Snooze beendet den Alarm.

---

## Checkliste vor Einreichung

- [ ] App-Kernfunktion im Formular als „Alarm clock" deklariert (Default-Grant-Pfad).
- [ ] Bestätigen, dass `USE_FULL_SCREEN_INTENT` nur für den Wecker genutzt wird
      (kein Overlay/Ad/Chat-Einsatz) — durch Code belegt: einziger Aufrufer ist
      `AlarmReceiver.setFullScreenIntent` im Weck-Pfad.
- [ ] Screenshot/Screen-Recording des Vollbild-Alarms über dem Sperrbildschirm beilegen.
- [ ] Getrennt halten von den FGS-Deklarationen (`specialUse`, `mediaPlayback`) —
      das ist eine eigenständige App-Content-Deklaration.
