package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.hilfe

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger

/**
 * Die Seiten der Website (`docs/`), auf die die App verweist.
 *
 * WARUM VERLINKT UND NICHT IN DER APP: Die Anleitung und die Problemhilfe gibt es genau einmal, auf
 * der Website. Ein zweiter Text in der App liefe garantiert auseinander. Der Preis ist, dass die
 * Hilfe Netz braucht - vertretbar, gelesen wird sie tagsueber und nicht im Moment des Weckens.
 * Ausnahme ist "Was ist neu": das gehoert zur INSTALLIERTEN Version und steckt deshalb in der APK
 * (siehe [Neuigkeiten]), die Website zeigt immer den Stand von `main`.
 */
enum class HilfeSeite(val datei: String) {
    ANLEITUNG("advanced-setup.html"),
    PROBLEME("troubleshooting.html"),
    DATENSCHUTZ("privacy.html"),
    ALLE_AENDERUNGEN("changelog.html")
}

/**
 * Jede Stelle, an der die App auf die Website verweist - an EINER Stelle, damit sie pruefbar ist.
 *
 * Ein Anker, den es auf der Seite nicht (mehr) gibt, faellt nicht auf: der Browser oeffnet dann
 * stumm den Seitenanfang. Deshalb prueft `tools/doku/pruefe_hilfe.py` (CI und Schleuse), dass jeder
 * Anker hier als `id` in der genannten Datei unter `docs/` steht. Wer eine Ueberschrift der Website
 * umbenennt, behaelt die `id` - oder zieht sie hier nach.
 */
enum class HilfeThema(val seite: HilfeSeite, val anker: String?) {
    ANLEITUNG(HilfeSeite.ANLEITUNG, null),
    ANLEITUNG_VORBEREITUNG(HilfeSeite.ANLEITUNG, "vorbereitung"),
    ANLEITUNG_SCHICHTEN(HilfeSeite.ANLEITUNG, "schichten"),
    ANLEITUNG_WECKER(HilfeSeite.ANLEITUNG, "wecker"),
    ANLEITUNG_DIMMER(HilfeSeite.ANLEITUNG, "dimmer"),
    ANLEITUNG_NICHT_STOEREN(HilfeSeite.ANLEITUNG, "nicht-stoeren"),
    ANLEITUNG_HUE(HilfeSeite.ANLEITUNG, "hue"),

    PROBLEME(HilfeSeite.PROBLEME, null),
    PROBLEME_AKKU(HilfeSeite.PROBLEME, "akku"),
    PROBLEME_NICHTNUTZUNG(HilfeSeite.PROBLEME, "nichtnutzung"),
    PROBLEME_TIMEOFFICE(HilfeSeite.PROBLEME, "timeoffice"),
    PROBLEME_BENACHRICHTIGUNGEN(HilfeSeite.PROBLEME, "ausserdem"),
    PROBLEME_DIMMER(HilfeSeite.PROBLEME, "dimmer"),

    DATENSCHUTZ(HilfeSeite.DATENSCHUTZ, null),
    ALLE_AENDERUNGEN(HilfeSeite.ALLE_AENDERUNGEN, null);

    val url: String
        get() = BASIS_URL + seite.datei + (anker?.let { "#$it" } ?: "")

    companion object {
        /** Die Domain aus `docs/CNAME` - die Pruefung vergleicht beides. */
        const val BASIS_URL = "https://cf-alarm.duckdns.org/"
    }
}

/**
 * Oeffnet das Thema im Browser. Kein Browser installiert ist selten, aber kein Grund fuer einen
 * Absturz - dann bleibt es beim Log.
 */
fun oeffneHilfe(context: Context, thema: HilfeThema) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, thema.url.toUri()))
    } catch (e: Exception) {
        Logger.e(LogTags.UI, "Hilfeseite nicht zu öffnen: ${thema.url}", e)
    }
}
