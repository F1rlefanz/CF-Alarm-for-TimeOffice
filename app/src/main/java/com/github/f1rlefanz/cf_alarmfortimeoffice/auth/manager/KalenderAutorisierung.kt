package com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import com.google.api.services.calendar.CalendarScopes
import java.util.concurrent.TimeUnit

/**
 * Duenne Naht um den Token-Abruf der Play-Dienste, damit [OAuth2TokenManager] ohne sie testbar ist.
 *
 * Beide Aufrufe BLOCKIEREN und duerfen nicht auf dem Hauptthread laufen (`Tasks.await` wirft dort).
 * Sie werfen, was `Tasks.await` wirft - eingestuft wird in [AutorisierungsEinstufung].
 */
interface KalenderAutorisierung {

    /** `hatResolution` heisst: GMS verlangt eine Zustimmung ODER ist offline (siehe Einstufung). */
    data class Antwort(
        val accessToken: String?,
        val hatResolution: Boolean,
        val zustimmungsDialog: PendingIntent?
    )

    fun autorisiere(email: String): Antwort

    /** Leert nur den Cache der Play-Dienste; widerruft nichts (Spike: die App synchronisierte weiter). */
    fun leereCache(accessToken: String)
}

/**
 * Umsetzung ueber `Identity.getAuthorizationClient` - der Weg, den Google fuer den Zugriff auf
 * Google-Daten vorsieht; `GoogleAuthUtil.getToken/clearToken` sind seit play-services-auth 21.3.0
 * abgekuendigt. Hergang und Messwerte: Skill cfalarm-persistenz-und-auth, reference/auth-und-token.md.
 *
 * GMS fuehrt EINEN Token-Cache fuer beide Wege (Spike S1: identisches Token). Eine per
 * GoogleAuthUtil erteilte Zustimmung wird erkannt - ein Update braucht keine Neuanmeldung.
 *
 * Der Client wird erst im Aufruf geholt, nie beim Bauen: [OAuth2TokenManager] haengt als Singleton
 * am Application-Graphen, der auch im Direct-Boot-Prozess entsteht.
 */
class PlayDiensteKalenderAutorisierung(private val context: Context) : KalenderAutorisierung {

    override fun autorisiere(email: String): KalenderAutorisierung.Antwort {
        val anfrage = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(CalendarScopes.CALENDAR_READONLY)))
            .setAccount(Account(email, "com.google"))
            .build()
        val ergebnis = Tasks.await(
            Identity.getAuthorizationClient(context).authorize(anfrage),
            WARTEZEIT_S,
            TimeUnit.SECONDS
        )
        return KalenderAutorisierung.Antwort(
            accessToken = ergebnis.accessToken,
            hatResolution = ergebnis.hasResolution(),
            zustimmungsDialog = ergebnis.pendingIntent
        )
    }

    override fun leereCache(accessToken: String) {
        Tasks.await(
            Identity.getAuthorizationClient(context)
                .clearToken(ClearTokenRequest.builder().setToken(accessToken).build()),
            WARTEZEIT_S,
            TimeUnit.SECONDS
        )
    }

    private companion object {
        /**
         * Eigener Deckel: `GoogleAuthUtil` blockierte bis zu seinem internen Timeout, `Tasks.await`
         * ohne Frist wartet unbegrenzt - z. B. waehrend sich die Play-Dienste aktualisieren.
         * Gemessen wurden 40-500 ms; 20 s lassen reichlich Luft und halten den Wartungslauf frei.
         */
        const val WARTEZEIT_S = 20L
    }
}

/**
 * Hat das aktive Netz Internet UND ist es von Android validiert? Dieselbe Definition wie
 * `NetworkStateMonitor` und die Status-Karte. Ein Fehler beim Fragen zaehlt als "nicht validiert" -
 * die Richtung, die keine Behauptung ueber die Anmeldung erlaubt.
 */
internal fun istNetzValidiert(context: Context): Boolean = try {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
    caps != null &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
} catch (e: Exception) {
    false
}
