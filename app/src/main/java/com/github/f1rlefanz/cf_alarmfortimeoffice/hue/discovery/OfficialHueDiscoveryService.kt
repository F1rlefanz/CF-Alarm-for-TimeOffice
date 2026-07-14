package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.discovery

import android.content.Context
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.DiscoveryMethod
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.DiscoveryStatus
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueBridge
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Official Philips Hue Bridge Discovery Service
 *
 * Implements the official discovery methods as recommended by Philips:
 * 1. mDNS Discovery (_hue._tcp.local) - lokal, schnell, ohne Internet -> ZUERST
 * 2. N-UPnP Discovery (https://discovery.meethue.com) - Cloud-Fallback, nur wenn lokal
 *    nichts gefunden wurde (Netze, die Multicast blocken)
 *
 * REIHENFOLGE BEWUSST SO: Frueher lief die Cloud-Suche zuerst und kostete bei jedem
 * Fehlschlag ihren vollen Timeout, bevor die lokale Suche ueberhaupt startete. Die Bridge
 * steht aber im selben WLAN und antwortet in ~170ms - die Cloud kann sie nur langsamer
 * bestaetigen. Im Log vom 14.07. liess sich discovery.meethue.com zudem gar nicht aufloesen,
 * waehrend die Bridge lokal sofort da war.
 *
 * This replaces the primitive IP scanning approach with official APIs
 * as documented at: https://developers.meethue.com/develop/get-started-2/
 */
class OfficialHueDiscoveryService(private val context: Context) {

    companion object {
        private const val NUPNP_TIMEOUT_MS = 15000L // 15 seconds for N-UPnP
        private const val MDNS_TIMEOUT_MS = 25000L // Obergrenze; mDNS bricht selbst frueher ab
    }
    
    private val nUpnpService = HueNUpnpDiscoveryService()
    private val mdnsService = HueMdnsDiscoveryService(context)
    
    // Discovery status flow
    private val _discoveryStatus = MutableSharedFlow<DiscoveryStatus>(replay = 1)
    
    fun getDiscoveryStatus(): Flow<DiscoveryStatus> = _discoveryStatus.asSharedFlow()
    
    /**
     * Discovers Hue bridges using official Philips methods.
     * Tries local mDNS first, then falls back to the N-UPnP cloud service if nothing was found.
     */
    suspend fun discoverBridges(): Result<List<HueBridge>> = withContext(Dispatchers.IO) {
        Logger.i(LogTags.HUE_DISCOVERY, "Starting official Hue bridge discovery")
        
        try {
            val allBridges = mutableSetOf<HueBridge>()
            
            // Emit starting status
            _discoveryStatus.emit(DiscoveryStatus(
                method = DiscoveryMethod.ONLINE_DISCOVERY,
                stage = "STARTING",
                message = "Starting bridge discovery...",
                progress = 0.0f
            ))
            
            // Phase 1: mDNS (lokal). BEWUSST ZUERST - die Bridge steht im selben WLAN und
            // antwortet in ~170ms, waehrend die Cloud-Suche einen funktionierenden
            // Internet-Zugang UND aufloesbares discovery.meethue.com braucht. Im Log vom
            // 14.07. scheiterte genau das (UnknownHostException), obwohl die Bridge lokal
            // sofort da war - 10s Wartezeit fuer nichts.
            Logger.d(LogTags.HUE_DISCOVERY, "Phase 1: Attempting mDNS discovery (local)")

            _discoveryStatus.emit(DiscoveryStatus(
                method = DiscoveryMethod.MDNS,
                stage = "MDNS_SEARCH",
                message = "Scanning local network via mDNS...",
                progress = 0.1f,
                currentMethod = "mDNS"
            ))

            val mdnsResult = withTimeoutOrNull(MDNS_TIMEOUT_MS) {
                mdnsService.discoverBridges()
            }

            if (mdnsResult?.isSuccess == true) {
                val mdnsBridges = mdnsResult.getOrNull() ?: emptyList()
                if (mdnsBridges.isNotEmpty()) {
                    allBridges.addAll(mdnsBridges)
                    Logger.i(LogTags.HUE_DISCOVERY, "mDNS discovery successful: ${mdnsBridges.size} bridges found")

                    _discoveryStatus.emit(DiscoveryStatus(
                        method = DiscoveryMethod.MDNS,
                        stage = "COMPLETED",
                        message = "Found ${mdnsBridges.size} bridge(s) on local network",
                        progress = 0.5f,
                        foundBridges = mdnsBridges.size
                    ))
                } else {
                    Logger.d(LogTags.HUE_DISCOVERY, "mDNS discovery returned no bridges")
                }
            } else {
                Logger.w(LogTags.HUE_DISCOVERY, "mDNS discovery failed or timed out")
            }

            // Phase 2: N-UPnP (Cloud) - NUR wenn lokal nichts gefunden wurde.
            // Fallback fuer Netze, die Multicast blocken (manche Gast-/Mesh-WLANs). Wurde die
            // Bridge lokal schon gefunden, liefert die Cloud dieselbe Bridge und kostet nur
            // Zeit - deshalb ueberspringen.
            if (allBridges.isEmpty()) {
                Logger.d(LogTags.HUE_DISCOVERY, "Phase 2: Attempting N-UPnP discovery (cloud fallback)")

                _discoveryStatus.emit(DiscoveryStatus(
                    method = DiscoveryMethod.ONLINE_DISCOVERY,
                    stage = "N_UPNP_SEARCH",
                    message = "Nothing found locally, contacting Philips discovery service...",
                    progress = 0.5f,
                    currentMethod = "N-UPnP"
                ))

                val nUpnpResult = withTimeoutOrNull(NUPNP_TIMEOUT_MS) {
                    nUpnpService.discoverBridges()
                }

                if (nUpnpResult?.isSuccess == true) {
                    val nUpnpBridges = nUpnpResult.getOrNull() ?: emptyList()
                    if (nUpnpBridges.isNotEmpty()) {
                        // Dedup gegen die lokal gefundenen (hier zwar leer, aber die Invariante
                        // soll halten, falls die Reihenfolge je wieder geaendert wird).
                        val existingIPs = allBridges.map { it.internalipaddress }.toSet()
                        val newBridges = nUpnpBridges.filter { it.internalipaddress !in existingIPs }
                        allBridges.addAll(newBridges)

                        Logger.i(LogTags.HUE_DISCOVERY, "N-UPnP discovery successful: ${nUpnpBridges.size} bridges found (${newBridges.size} new)")
                    } else {
                        Logger.d(LogTags.HUE_DISCOVERY, "N-UPnP discovery returned no bridges")
                    }
                } else {
                    Logger.w(LogTags.HUE_DISCOVERY, "N-UPnP discovery failed or timed out")

                    _discoveryStatus.emit(DiscoveryStatus(
                        method = DiscoveryMethod.ONLINE_DISCOVERY,
                        stage = "FAILED",
                        message = "Philips discovery service unavailable",
                        progress = 0.8f
                    ))
                }
            } else {
                Logger.d(LogTags.HUE_DISCOVERY, "Phase 2: Skipping N-UPnP - bridge already found locally")
            }

            // Final status
            val totalBridges = allBridges.size
            
            if (totalBridges > 0) {
                _discoveryStatus.emit(DiscoveryStatus(
                    method = if (allBridges.any { it.id.startsWith("mdns_") }) DiscoveryMethod.MDNS else DiscoveryMethod.ONLINE_DISCOVERY,
                    stage = "COMPLETED",
                    message = "Discovery completed: $totalBridges bridge(s) found",
                    progress = 1.0f,
                    isComplete = true,
                    foundBridges = totalBridges
                ))
                
                Logger.i(LogTags.HUE_DISCOVERY, "Official discovery completed successfully: $totalBridges bridges found")
            } else {
                _discoveryStatus.emit(DiscoveryStatus(
                    method = DiscoveryMethod.MDNS,
                    stage = "COMPLETED",
                    message = "No bridges found. Please check your network connection and ensure bridges are powered on.",
                    progress = 1.0f,
                    isComplete = true,
                    foundBridges = 0
                ))
                
                Logger.w(LogTags.HUE_DISCOVERY, "No bridges found with any discovery method")
            }
            
            Result.success(allBridges.toList())
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_DISCOVERY, "Official discovery failed", e)
            
            _discoveryStatus.emit(DiscoveryStatus(
                method = DiscoveryMethod.MDNS,
                stage = "FAILED",
                message = "Discovery failed: ${e.message}",
                isError = true,
                isComplete = true
            ))
            
            Result.failure(e)
        }
    }
    
}
