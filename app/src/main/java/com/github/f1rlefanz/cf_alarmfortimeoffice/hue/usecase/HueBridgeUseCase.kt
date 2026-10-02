package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.BridgeConnectionInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.DiscoveryStatus
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueBridge
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.IHueBridgeRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.IHueConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.IHueBridgeUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

/**
 * UseCase for Hue Bridge operations
 */
class HueBridgeUseCase @Inject constructor(
    private val bridgeRepository: IHueBridgeRepository,
    private val configRepository: IHueConfigRepository
) : IHueBridgeUseCase {
    
    companion object {
        private const val DISCOVERY_TIMEOUT_MS = 45000L
        private const val CONNECTION_TIMEOUT_MS = 30000L
    }
    
    override suspend fun discoverBridges(): Result<List<HueBridge>> {
        Logger.i(LogTags.HUE_USECASE, "Starting bridge discovery with business logic validation")
        
        return try {
            val discoveryResult = withTimeoutOrNull(DISCOVERY_TIMEOUT_MS.milliseconds) {
                bridgeRepository.discoverBridges()
            }
            
            if (discoveryResult == null) {
                Logger.w(LogTags.HUE_USECASE, "Bridge discovery timed out after ${DISCOVERY_TIMEOUT_MS}ms")
                return Result.failure(Exception("Die Suche hat zu lange gedauert. Prüfe die WLAN-Verbindung und versuche es erneut."))
            }
            
            if (discoveryResult.isFailure) {
                Logger.w(LogTags.HUE_USECASE, "Discovery failed at repository level")
                return discoveryResult
            }
            
            val bridges = discoveryResult.getOrNull() ?: emptyList()
            
            if (bridges.isEmpty()) {
                Logger.i(LogTags.HUE_USECASE, "No bridges found during discovery")
            } else {
                Logger.i(LogTags.HUE_USECASE, "Discovery successful: ${bridges.size} bridges found")
            }
            Result.success(bridges)
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_USECASE, "Bridge discovery failed with exception", e)
            Result.failure(Exception("Die Suche nach der Bridge ist fehlgeschlagen. Prüfe die WLAN-Verbindung und versuche es erneut.", e))
        }
    }
    
    override fun getDiscoveryStatus(): Flow<DiscoveryStatus> {
        return bridgeRepository.getDiscoveryStatus()
    }
    
    override suspend fun setupBridge(bridge: HueBridge): Result<String> {
        Logger.i(LogTags.HUE_USECASE, "Starting bridge setup process for ${bridge.ipAddress}")
        
        return try {
            if (bridge.ipAddress.isBlank()) {
                Logger.w(LogTags.HUE_USECASE, "Invalid bridge IP address provided")
                return Result.failure(IllegalArgumentException("Bridge IP address cannot be empty"))
            }
            
            val connectivityResult = withTimeoutOrNull(CONNECTION_TIMEOUT_MS.milliseconds) {
                bridgeRepository.testBridgeConnection(bridge)
            }
            
            if (connectivityResult == null) {
                Logger.w(LogTags.HUE_USECASE, "Bridge connectivity test timed out")
                return Result.failure(Exception("Die Bridge hat nicht rechtzeitig geantwortet. Ist sie eingeschaltet und im selben WLAN wie dein Handy?"))
            }
            
            if (connectivityResult.isFailure || connectivityResult.getOrNull() != true) {
                Logger.w(LogTags.HUE_USECASE, "Bridge connectivity test failed")
                return Result.failure(Exception("Die Bridge unter ${bridge.ipAddress} ist nicht erreichbar. Ist dein Handy im selben WLAN?"))
            }
            
            val connectionResult = bridgeRepository.connectToBridge(bridge)
            
            if (connectionResult.isFailure) {
                val error = connectionResult.exceptionOrNull()
                Logger.w(LogTags.HUE_USECASE, "Bridge connection failed", error)
                
                val userMessage = when {
                    error?.message?.contains("link button", ignoreCase = true) == true ->
                        "Drücke die Link-Taste auf der Hue-Bridge und tippe dann erneut auf „Jetzt verbinden“."
                    error?.message?.contains("unauthorized", ignoreCase = true) == true ->
                        "Die Bridge hat die Verbindung abgelehnt. Drücke die Link-Taste und versuche es erneut."
                    else ->
                        "Die Verbindung zur Bridge ist fehlgeschlagen. Bitte versuche es erneut."
                }
                
                return Result.failure(Exception(userMessage, error))
            }
            
            val username = connectionResult.getOrNull()
            
            if (username.isNullOrBlank()) {
                Logger.w(LogTags.HUE_USECASE, "Bridge connection returned empty username")
                return Result.failure(Exception("Die Bridge hat keinen Zugang für die App angelegt. Bitte versuche es erneut."))
            }
            
            val saveResult = configRepository.saveBridgeConfig(bridge.ipAddress, username)
            
            if (saveResult.isFailure) {
                Logger.w(LogTags.HUE_USECASE, "Failed to save bridge configuration", saveResult.exceptionOrNull())
                // Don't fail the setup, just log the warning
            }
            
            val validationResult = bridgeRepository.validateConnection()
            
            if (validationResult.isFailure || validationResult.getOrNull() != true) {
                Logger.w(LogTags.HUE_USECASE, "Bridge connection validation failed")
                return Result.failure(Exception("Die Bridge ist gekoppelt, antwortet aber nicht wie erwartet. Bitte versuche es erneut."))
            }
            
            Logger.i(LogTags.HUE_USECASE, "Bridge setup completed successfully")
            Result.success(username)
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_USECASE, "Bridge setup failed with exception", e)
            Result.failure(Exception("Die Einrichtung der Bridge ist fehlgeschlagen. Bitte versuche es erneut.", e))
        }
    }
    
    override suspend fun validateBridgeConnection(): Result<Boolean> {
        Logger.d(LogTags.HUE_USECASE, "Validating bridge connection with business logic")
        
        return try {
            val config = configRepository.getConfiguration().first()
            
            if (!config.isConfigured) {
                Logger.i(LogTags.HUE_USECASE, "Bridge not configured")
                return Result.success(false)
            }
            
            bridgeRepository.initializeFromConfig(config.bridgeIp, config.username)
            
            val validationResult = withTimeoutOrNull(CONNECTION_TIMEOUT_MS.milliseconds) {
                bridgeRepository.validateConnection()
            }
            
            if (validationResult == null) {
                Logger.w(LogTags.HUE_USECASE, "Bridge validation timed out")
                return Result.success(false)
            }
            
            val isValid = validationResult.getOrNull() ?: false
            
            Logger.i(LogTags.HUE_USECASE, "Bridge connection validation result: $isValid")
            Result.success(isValid)
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_USECASE, "Bridge validation failed with exception", e)
            Result.success(false) // Don't propagate exception, just return false
        }
    }
    
    override suspend fun getBridgeConnectionInfo(): Result<BridgeConnectionInfo> {
        return try {
            val config = configRepository.getConfiguration().first()
            
            val connectionInfo = if (config.isConfigured) {
                bridgeRepository.initializeFromConfig(config.bridgeIp, config.username)
                
                val isConnected = validateBridgeConnection().getOrNull() ?: false
                
                BridgeConnectionInfo(
                    isConnected = isConnected,
                    bridgeIp = config.bridgeIp
                )
            } else {
                BridgeConnectionInfo(
                    isConnected = false,
                    bridgeIp = null
                )
            }
            
            Result.success(connectionInfo)
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_USECASE, "Failed to get bridge connection info", e)
            
            // Return a default info object instead of failing
            val defaultInfo = BridgeConnectionInfo(
                isConnected = false,
                bridgeIp = null
            )
            
            Result.success(defaultInfo)
        }
    }

    override suspend fun forgetBridge(): Result<Unit> {
        Logger.i(LogTags.HUE_USECASE, "Forgetting bridge connection (user requested disconnect)")

        return try {
            val repoResult = bridgeRepository.forgetConnection()
            if (repoResult.isFailure) {
                Logger.w(LogTags.HUE_USECASE, "Failed to forget bridge connection at repository level", repoResult.exceptionOrNull())
                return repoResult
            }

            val configResult = configRepository.clearBridgeConfig()
            if (configResult.isFailure) {
                Logger.w(LogTags.HUE_USECASE, "Failed to clear persisted bridge config", configResult.exceptionOrNull())
                return configResult
            }

            Logger.i(LogTags.HUE_USECASE, "Bridge connection forgotten successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_USECASE, "Failed to forget bridge connection", e)
            Result.failure(Exception("Die Bridge konnte nicht vergessen werden. Bitte versuche es erneut.", e))
        }
    }
}
