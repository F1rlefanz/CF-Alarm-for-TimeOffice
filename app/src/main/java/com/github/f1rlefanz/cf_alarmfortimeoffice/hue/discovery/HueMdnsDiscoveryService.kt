package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueBridge
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Official mDNS-based Hue Bridge Discovery Service
 * Uses Android's NsdManager API to discover Hue bridges via mDNS
 * 
 * This implements the official Philips Hue discovery method as documented at:
 * https://developers.meethue.com/develop/get-started-2/
 */
class HueMdnsDiscoveryService(private val context: Context) {
    
    companion object {
        private const val HUE_SERVICE_TYPE = "_hue._tcp"

        /** Obergrenze, wenn gar nichts antwortet (Multicast geblockt, keine Bridge im Netz). */
        private const val DISCOVERY_TIMEOUT_MS = 10000L

        /**
         * Nachlauffrist nach dem ERSTEN Fund, um weitere Bridges einzusammeln.
         *
         * Ohne sie wuerde die Suche beim ersten Treffer abbrechen und in Haushalten mit
         * mehreren Bridges nur eine liefern. Mit ihr dauert die Suche im Normalfall
         * ~1,2s statt der vollen 10s Timeout - die Bridge antwortet real in ~170ms.
         */
        private const val GRACE_PERIOD_MS = 1000L
    }
    
    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }
    
    /**
     * Sucht Hue-Bridges per mDNS (Multicast DNS) - der offizielle, lokale Weg.
     *
     * ABLAUF: Discovery starten, auf den ersten Fund warten (oder auf den Timeout, falls
     * nichts antwortet), dann eine kurze Nachlauffrist fuer weitere Bridges. Im Normalfall
     * ist die Suche damit nach ~1,2s durch statt nach 10s - die Bridge antwortet real in
     * ~170ms.
     *
     * WARUM KEIN suspendCancellableCoroutine MEHR: Die fruehere Konstruktion beendete die
     * Continuation ausschliesslich in onDiscoveryStopped. Das feuert aber erst nach
     * stopServiceDiscovery(), das wiederum nur aus invokeOnCancellation kam - also erst,
     * NACHDEM der Timeout die Coroutine abgebrochen hatte. Das resume() lief damit garantiert
     * ins Leere und withTimeoutOrNull lieferte null: mDNS konnte NIE eine Bridge zurueckgeben.
     * Ein CompletableDeferred als Signal macht das Warten explizit und den Fehler unmoeglich.
     */
    suspend fun discoverBridges(): Result<List<HueBridge>> = withContext(Dispatchers.IO) {
        Logger.d(LogTags.HUE_DISCOVERY, "Starting mDNS discovery for Hue bridges")

        // Befuellt aus den NsdManager-Callback-Threads, gelesen aus dieser Coroutine.
        val discoveredBridges = CopyOnWriteArrayList<HueBridge>()

        // Signal: "es gibt ein Ergebnis" - entweder erste Bridge gefunden ODER Start gescheitert.
        val firstResult = CompletableDeferred<Unit>()

        var startedListener: NsdManager.DiscoveryListener? = null

        return@withContext try {
            val discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Logger.e(LogTags.HUE_DISCOVERY, "mDNS discovery start failed: $errorCode")
                    // Nicht 10s ins Leere warten, wenn die Suche gar nicht erst startet.
                    firstResult.complete(Unit)
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Logger.w(LogTags.HUE_DISCOVERY, "mDNS discovery stop failed: $errorCode")
                }

                override fun onDiscoveryStarted(serviceType: String) {
                    Logger.d(LogTags.HUE_DISCOVERY, "mDNS discovery started for: $serviceType")
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    Logger.d(LogTags.HUE_DISCOVERY, "mDNS discovery stopped")
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    Logger.d(LogTags.HUE_DISCOVERY, "mDNS service found: ${serviceInfo.serviceName}")

                    resolveService(serviceInfo) { resolvedService ->
                        val bridge = resolvedService?.let { createHueBridgeFromService(it) }
                        if (bridge != null) {
                            // Dieselbe Bridge kann ueber mehrere Interfaces annonciert werden.
                            if (discoveredBridges.none { it.ipAddress == bridge.ipAddress }) {
                                discoveredBridges.add(bridge)
                                Logger.i(LogTags.HUE_DISCOVERY, "Hue bridge discovered: ${bridge.ipAddress}")
                            }
                            firstResult.complete(Unit)
                        }
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    Logger.d(LogTags.HUE_DISCOVERY, "mDNS service lost: ${serviceInfo.serviceName}")
                }
            }

            nsdManager.discoverServices(HUE_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            startedListener = discoveryListener

            // Auf den ersten Fund warten - oder abbrechen, wenn nichts antwortet.
            withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) { firstResult.await() }

            // Nur wenn etwas gefunden wurde, kurz auf weitere Bridges nachwarten.
            if (discoveredBridges.isNotEmpty()) {
                delay(GRACE_PERIOD_MS)
            }

            val bridges = discoveredBridges.toList()
            Logger.i(LogTags.HUE_DISCOVERY, "mDNS discovery completed: ${bridges.size} bridges found")
            Result.success(bridges)

        } catch (e: CancellationException) {
            // Bricht der Aufrufer die Suche ab (Nutzer verlaesst den Screen), darf das nicht
            // als "Discovery fehlgeschlagen" verpackt werden - CancellationException gehoert
            // weitergereicht. Das finally unten stoppt die Discovery trotzdem.
            throw e
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_DISCOVERY, "mDNS discovery failed", e)
            Result.failure(e)
        } finally {
            // Immer stoppen - frueher passierte das nur bei Cancellation, ein normal beendeter
            // Lauf liess die Discovery weiterlaufen.
            startedListener?.let { listener ->
                try {
                    nsdManager.stopServiceDiscovery(listener)
                } catch (e: Exception) {
                    Logger.w(LogTags.HUE_DISCOVERY, "Failed to stop mDNS discovery", e)
                }
            }
        }
    }
    
    @Suppress("DEPRECATION")
    private fun resolveService(serviceInfo: NsdServiceInfo, callback: (NsdServiceInfo?) -> Unit) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Logger.w(LogTags.HUE_DISCOVERY, "Failed to resolve service: ${serviceInfo.serviceName}, error: $errorCode")
                callback(null)
            }
            
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                // Extract hostname/host for logging (backward compatible)
                val hostInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    serviceInfo.hostAddresses.firstOrNull()?.hostName ?: "unknown"
                } else {
                    @Suppress("DEPRECATION")
                    serviceInfo.host?.hostName ?: "unknown"
                }
                Logger.d(LogTags.HUE_DISCOVERY, "Service resolved: ${serviceInfo.serviceName} -> $hostInfo")
                callback(serviceInfo)
            }
        }
        
        try {
            @Suppress("DEPRECATION")
            nsdManager.resolveService(serviceInfo, resolveListener)
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_DISCOVERY, "Failed to resolve service", e)
            callback(null)
        }
    }
    
    /**
     * Creates HueBridge from resolved NsdServiceInfo
     * MODERNIZED: Uses hostname for API 34+ while maintaining backward compatibility
     */
    private fun createHueBridgeFromService(serviceInfo: NsdServiceInfo): HueBridge? {
        return try {
            // Modern approach: Use hostAddresses for API 34+, fallback to host for older versions
            val hostAddress = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // API 34+: Use hostAddresses array (first address)
                serviceInfo.hostAddresses.firstOrNull()?.hostAddress
            } else {
                // Legacy API: Use deprecated host property for backward compatibility
                @Suppress("DEPRECATION")
                serviceInfo.host?.hostAddress
            }
            
            if (hostAddress != null) {
                // Extract bridge ID from service name if possible
                // Hue service names typically contain the last 6 digits of bridge ID
                val bridgeId = extractBridgeIdFromServiceName(serviceInfo.serviceName) 
                    ?: "mdns_${hostAddress.replace(".", "_")}"
                
                HueBridge(
                    id = bridgeId,
                    ipAddress = hostAddress,
                    name = serviceInfo.serviceName,
                    discoveryMethod = com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.DiscoveryMethod.MDNS
                )
            } else {
                Logger.w(LogTags.HUE_DISCOVERY, "Service has no IP address: ${serviceInfo.serviceName}")
                null
            }
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_DISCOVERY, "Failed to create bridge from service", e)
            null
        }
    }
    
    private fun extractBridgeIdFromServiceName(serviceName: String): String? {
        return try {
            // Hue bridges typically advertise as "Philips Hue - XXXXXX" where XXXXXX is last 6 digits of bridge ID
            val regex = Regex(".*-\\s*(\\w{6}).*")
            val match = regex.find(serviceName)
            match?.groupValues?.get(1)
        } catch (e: Exception) {
            Logger.w(LogTags.HUE_DISCOVERY, "Could not extract bridge ID from service name: $serviceName", e)
            null
        }
    }
}
