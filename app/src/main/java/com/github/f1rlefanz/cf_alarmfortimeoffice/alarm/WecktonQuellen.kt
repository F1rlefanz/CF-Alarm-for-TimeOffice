package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm

import android.net.Uri
import android.media.RingtoneManager

/**
 * Welche Tonquellen der Weckton der Reihe nach versucht - die EINZIGE Stelle, an der diese
 * Auswahl entsteht. Der echte Wecker ([com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmSoundService])
 * und das Probehoeren ([WecktonProbe]) lesen beide hier, damit man beim Probehoeren genau den Ton
 * hoert, mit dem man geweckt wird - eine Kopie der Liste wuerde irgendwann still abweichen.
 *
 * DIRECT BOOT: Der Wecker laeuft ggf. VOR der ersten Entsperrung. Die RingtoneManager-URIs sind
 * dann `content://media/...`-Verweise auf den MediaProvider und koennen unaufloesbar sein. Deshalb
 * liefert diese Funktion ALLE Kandidaten statt nur des ersten, und der Aufrufer probiert sie der
 * Reihe nach (frueher fing eine `?:`-Kette nur den null-Fall ab, nicht ein scheiterndes
 * `setDataSource()`). Die Reihenfolge ist tragend: Wecker-Ton vor Benachrichtigungs- vor
 * Klingelton.
 */
object WecktonQuellen {

    /** Die System-Vorgaben fuer Wecker-, Benachrichtigungs- und Klingelton, ohne Luecken und Dubletten. */
    fun kandidaten(): List<Uri> = auswahl(
        alarm = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
        benachrichtigung = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
        klingelton = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
    )

    /**
     * Reine Auswahlregel, generisch, damit sie ohne Android-`Uri` testbar ist: fehlende Quellen
     * fallen weg, doppelte (zeigen zwei Vorgaben auf denselben Ton) werden nur einmal versucht,
     * die Reihenfolge bleibt.
     */
    internal fun <T : Any> auswahl(alarm: T?, benachrichtigung: T?, klingelton: T?): List<T> =
        listOfNotNull(alarm, benachrichtigung, klingelton).distinct()
}
