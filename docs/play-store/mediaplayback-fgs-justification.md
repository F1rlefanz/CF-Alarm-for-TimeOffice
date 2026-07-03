# Play Console – Begründung `FOREGROUND_SERVICE_MEDIA_PLAYBACK`

> Diese Datei enthält den Begründungstext für die Play-Console-Deklaration der
> `mediaPlayback`-Foreground-Service-Nutzung (Abspielen des Wecktons). Der
> **englische Block** ist 1:1 in das Play-Console-Formular kopierbar (Google-Review
> erfolgt auf Englisch). Die deutschen Abschnitte sind interne Erläuterung.

---

## Betroffene Deklaration (AndroidManifest.xml)

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />

<service
    android:name=".service.AlarmSoundService"
    android:foregroundServiceType="mediaPlayback"
    android:exported="false"
    android:directBootAware="true"
    android:stopWithTask="false" />
```

- **Service:** `AlarmSoundService` (nicht exportiert)
- **Zweck:** Abspielen des Weck-Audios (Wecker-Klingelton) über `MediaPlayer` mit
  `AudioAttributes` `USAGE_ALARM` / `CONTENT_TYPE_SONIFICATION`, in Schleife bis der
  Nutzer den Wecker bewusst per Dismiss/Snooze beendet.
- **Lebensdauer:** Nur aktiv, solange der Wecker klingelt (typisch Sekunden bis
  wenige Minuten). Wird durch `ACTION_STOP_ALARM` (Dismiss/Snooze) sauber beendet.

---

## Paste-ready justification (English → Play Console)

**App category:** Alarm clock for **shift workers** (nurses, healthcare, emergency
services, manufacturing).

**Why the app uses `FOREGROUND_SERVICE_MEDIA_PLAYBACK`:**

> CF-Alarm for TimeOffice is an alarm app for shift workers. When a scheduled shift
> alarm fires, the app must reliably play the alarm sound so the user wakes up for a
> safety-critical shift start.
>
> The `mediaPlayback` foreground service `AlarmSoundService` plays the alarm audio: it
> uses `MediaPlayer` with alarm audio attributes (`USAGE_ALARM`) to play the alarm
> ringtone in a loop, decoupled from the Activity lifecycle, until the user explicitly
> dismisses or snoozes the alarm. Playing the alarm tone is audio playback, which is
> exactly what the `mediaPlayback` foreground-service type is intended for.
>
> A foreground service is required so the sound continues reliably even if the screen
> turns off, a call comes in, or the alarm Activity is destroyed under memory pressure
> — a backgrounded service would be killed and the user could sleep through the alarm.
> The service is short-lived (only while the alarm rings), not exported, shows a
> foreground notification with a "stop" action, and performs no advertising, tracking,
> or unrelated work.

**Fallback argument (if the reviewer questions `mediaPlayback` for an alarm tone):**

> The service performs genuine audio playback through Android's `MediaPlayer` with
> `AudioAttributes.USAGE_ALARM`. This mirrors how the AOSP Clock/alarm apps play alarm
> audio. No other standard foreground-service type fits audio output. If Google
> nonetheless requires a different classification for alarm audio, we will adopt the
> type Google recommends for alarm-clock sound playback.

---

## Paste-ready Begründung (Deutsch, falls auf Deutsch eingereicht wird)

> CF-Alarm for TimeOffice ist eine Wecker-App für Schichtarbeiter. Wenn ein geplanter
> Schicht-Wecker auslöst, muss die App den Weckton zuverlässig abspielen, damit der
> Nutzer den sicherheitskritischen Schichtbeginn nicht verschläft.
>
> Der `mediaPlayback`-Foreground-Service `AlarmSoundService` spielt das Weck-Audio ab:
> über `MediaPlayer` mit Alarm-Audio-Attributen (`USAGE_ALARM`) wird der Klingelton in
> Schleife wiedergegeben – unabhängig vom Activity-Lebenszyklus, bis der Nutzer den
> Wecker bewusst beendet (Dismiss/Snooze). Das Abspielen des Wecktons ist Audio-
> Wiedergabe, wofür der `mediaPlayback`-Typ vorgesehen ist.
>
> Ein Foreground-Service ist nötig, damit der Ton auch bei ausgeschaltetem Display,
> eingehendem Anruf oder Zerstörung der Alarm-Activity (Speicherdruck) zuverlässig
> weiterläuft. Der Service ist kurzlebig (nur während des Klingelns), nicht exportiert,
> zeigt eine Foreground-Notification mit „Wecker aus"-Aktion und macht nichts
> Sachfremdes (keine Werbung, kein Tracking).

---

## Hinweise für den Reviewer / Demo-Schritte

1. Einen (Test-)Wecker in wenigen Minuten stellen (manueller Wecker genügt).
2. Zur Weckzeit erscheint der Vollbild-Alarm und der Weckton läuft über den
   Foreground-Service (Notification mit „Wecker aus" sichtbar).
3. „Wecker aus" bzw. Snooze beendet den Ton und den Service sauber.

---

## Checkliste vor Einreichung

- [ ] `mediaPlayback` NICHT mit der `specialUse`-Begründung des
      `AlarmMaintenanceService` vermischen — das sind zwei getrennte Services/Typen.
- [ ] Screenshot der Sound-Service-Notification (mit „Wecker aus"-Aktion) beilegen.
- [ ] Fallback-Argumentation bereithalten, falls Google `mediaPlayback` für einen
      Weckton hinterfragt (siehe oben).
