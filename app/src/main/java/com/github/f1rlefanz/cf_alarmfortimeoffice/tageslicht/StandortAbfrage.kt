package com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * Einmalige, GROBE Ortsbestimmung fuer die Sonnenzeiten (#140) - ueber Androids `LocationManager`,
 * ohne Play-Dienste.
 *
 * NUR auf Knopfdruck aus dem Vordergrund ("Standort übernehmen" im Ort-Bildschirm), nie im
 * Hintergrund, keine laufenden Updates: genau das verspricht der Manifest-Kommentar zu
 * `ACCESS_COARSE_LOCATION` und die Datenschutzerklaerung. Wer hier einen Hintergrund-Aufrufer
 * ergaenzt, bricht beides.
 *
 * Das Ergebnis wird vom Aufrufer ueber [ITageslichtRepository.speichereStandort] auf 0,1°
 * gerundet gespeichert; hier wird nichts abgelegt und nichts ins Release-Log geschrieben.
 *
 * Nie werfend: jeder Fehlschlag wird zu einem [StandortErgebnis].
 */
class StandortAbfrage @Inject constructor(
    @ApplicationContext private val context: Context
) : IStandortAbfrage {

    override suspend fun einmalig(): StandortErgebnis {
        if (!hatBerechtigung()) return StandortErgebnis.KeineBerechtigung
        return try {
            val lm = context.getSystemService(LocationManager::class.java)
                ?: return StandortErgebnis.Abgeschaltet
            val provider = waehleProvider(
                sdk = Build.VERSION.SDK_INT,
                hatFused = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hatFused(lm),
                istAktiv = { lm.isProviderEnabled(it) }
            ) ?: return StandortErgebnis.Abgeschaltet

            val frisch = withTimeoutOrNull(ZEITLIMIT_MS) { aktuellerOrt(lm, provider) }
            val ort = frisch ?: letzterBekannter(lm, provider)
            if (ort == null) {
                Logger.i(LogTags.TAGESLICHT, "Standortabfrage ohne Ergebnis (Provider $provider)")
                StandortErgebnis.Zeitueberschreitung
            } else {
                Logger.d(LogTags.TAGESLICHT, "Standort ermittelt ueber $provider (frisch=${frisch != null})")
                StandortErgebnis.Gefunden(ort.latitude, ort.longitude)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: SecurityException) {
            // Berechtigung zwischen Pruefung und Abfrage entzogen
            Logger.w(LogTags.TAGESLICHT, "Standortabfrage abgelehnt (Berechtigung)")
            StandortErgebnis.KeineBerechtigung
        } catch (e: Exception) {
            Logger.w(LogTags.TAGESLICHT, "Standortabfrage gescheitert (${e.javaClass.simpleName})")
            StandortErgebnis.Zeitueberschreitung
        }
    }

    private fun hatBerechtigung(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("NewApi") // nur hinter dem SDK-Check in einmalig() gerufen
    private fun hatFused(lm: LocationManager): Boolean = lm.hasProvider(LocationManager.FUSED_PROVIDER)

    @SuppressLint("MissingPermission") // geprueft in einmalig(); SecurityException wird dort gefangen
    private suspend fun aktuellerOrt(lm: LocationManager, provider: String): Location? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                val sofort = Executor { it.run() }
                lm.getCurrentLocation(provider, signal, sofort) { ort ->
                    if (cont.isActive) cont.resume(ort)
                }
            }
        } else {
            suspendCancellableCoroutine { cont ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (cont.isActive) cont.resume(location)
                    }

                    // Vor API 30 abstrakt bzw. ohne Default - leer ueberschreiben, sonst AbstractMethodError
                    @Deprecated("Seit API 29 nicht mehr aufgerufen")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                    override fun onProviderEnabled(provider: String) = Unit
                    override fun onProviderDisabled(provider: String) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
                cont.invokeOnCancellation { lm.removeUpdates(listener) }
                // requestSingleUpdate ist ab API 30 veraltet - hier laeuft es nur auf API 26-29,
                // wo getCurrentLocation noch nicht existiert.
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            }
        }

    /** Rueckfall nach dem Zeitlimit: die letzte bekannte Position, sofern juenger als [MAX_ALTER_MS]. */
    @SuppressLint("MissingPermission")
    private fun letzterBekannter(lm: LocationManager, provider: String): Location? {
        val jetzt = System.currentTimeMillis()
        return listOf(provider, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .distinct()
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .filter { istFrischGenug(it.time, jetzt) }
            .maxByOrNull { it.time }
    }

    companion object {
        /** Grosszuegig: drinnen braucht der Netzwerk-Provider mitunter lange. */
        const val ZEITLIMIT_MS = 30_000L

        /** Sieben Tage: auf 0,1° gerundet ist eine Woche alte Position so gut wie eine frische. */
        const val MAX_ALTER_MS = 7L * 24 * 60 * 60 * 1000

        /**
         * Provider-Wahl: ab API 31 `fused` (falls vorhanden), sonst Netzwerk, sonst GPS - jeweils
         * nur, wenn eingeschaltet. `null` = Ortung aus.
         *
         * GPS erst ab API 31: davor verlangt der GPS-Provider `ACCESS_FINE_LOCATION`, und die
         * App fragt bewusst nur COARSE an - der Versuch endete in einer SecurityException, die
         * als "keine Berechtigung" falsch beim Nutzer ankaeme.
         */
        internal fun waehleProvider(sdk: Int, hatFused: Boolean, istAktiv: (String) -> Boolean): String? {
            val kandidaten = buildList {
                if (sdk >= Build.VERSION_CODES.S && hatFused) add(FUSED)
                add(LocationManager.NETWORK_PROVIDER)
                if (sdk >= Build.VERSION_CODES.S) add(LocationManager.GPS_PROVIDER)
            }
            return kandidaten.firstOrNull { p -> runCatching { istAktiv(p) }.getOrDefault(false) }
        }

        internal fun istFrischGenug(ortZeit: Long, jetzt: Long): Boolean =
            ortZeit > 0 && jetzt - ortZeit in 0..MAX_ALTER_MS

        /** Wert von `LocationManager.FUSED_PROVIDER` (API 31) - als Literal, damit der Test ohne SDK-Stub laeuft. */
        internal const val FUSED = "fused"
    }
}
