package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.DiscoveryStatus
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueBridge
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.IHueBridgeRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.IHueConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.BridgeConnectionInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.IHueBridgeUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * UseCase for Hue Bridge operations
 * Implements business logic layer with validation and error handling
 */
class HueBridgeUseCase(
    private val bridgeRepository: IHueBridgeRepository,
    private val configRepository: IHueConfigRepository
) : IHueBridgeUseCase {
    
    companion object {
        private const val DISCOVERY_TIMEOUT_MS = 45000L
        private const val CONNECTION_TIMEOUT_MS = 30000L
        private const val MIN_BRIDGES_FOR_SUCCESS = 1
    }
    
    override suspend fun discoverBridges(): Result<List<HueBridge>> {
        Logger.i(LogTags.HUE_USECASE, "Starting bridge discovery with business logic validation")
        
        return try {
            // Apply timeout to discovery process
            val discoveryResult = withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) {
                bridgeRepository.discoverBridges()
            }
            
            if (discoveryResult == null) {
                Logger.w(LogTags.HUE_USECASE, "Bridge discovery timed out after ${DISCOVERY_TIMEOUT_MS}ms")
                return Result.failure(Exception("Discovery timed out. Please check your network connection."))
            }
            
            // Validate discovery result
            if (discoveryResult.isFailure) {
                Logger.w(LogTags.HUE_USECASE, "Discovery failed at repository level")
                return discoveryResult
            }
            
            val bridges = discoveryResult.getOrNull() ?: emptyList()
            
            // Business logic validation
            when {
                bridges.isEmpty() -> {
                    Logger.i(LogTags.HUE_USECASE, "No bridges found during discovery")
                    Result.success(bridges) // Not an error, just no bridges found
                }
                bridges.size >= MIN_BRIDGES_FOR_SUCCESS -> {
                    Logger.i(LogTags.HUE_USECASE, "Discovery successful: ${bridges.size} bridges found")
                    Result.success(bridges)
                }
                else -> {
                    Logger.i(LogTags.HUE_USECASE, "Discovery completed with ${bridges.size} bridges")
                    Result.success(bridges)
                }
            }
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_USECASE, "Bridge discovery failed with exception", e)
            Result.failure(Exception("Bridge discovery failed: ${e.message}", e))
        }
    }
    
    override fun getDiscoveryStatus(): Flow<DiscoveryStatus> {
        Logger.d(LogTags.HUE_USECASE, "Providing discovery status stream")
        return bridgeRepository.getDiscoveryStatus()
    }
    
    override suspend fun setupBridge(bridge: HueBridge): Result<String> {
        Logger.i(LogTags.HUE_USECASE, "Starting bridge setup process for ${bridge.internalipaddress}")
        
        return try {
            // 1. Validate bridge parameter
            if (bridge.internalipaddress.isBlank()) {
                Logger.w(LogTags.HUE_USECASE, "Invalid bridge IP address provided")
                return Result.failure(IllegalArgumentException("Bridge IP address cannot be empty"))
            }
            
            // 2. Test connectivity first
            Logger.d(LogTags.HUE_USECASE, "Testing bridge connectivity")
            val connectivityResult = withTimeoutOrNull(CONNECTION_TIMEOUT_MS) {
                bridgeRepository.testBridgeConnection(bridge)
            }
            
            if (connectivityResult == null) {
                Logger.w(LogTags.HUE_USECASE, "Bridge connectivity test timed out")
                return Result.failure(Exception("Bridge connection timed out. Please check if the bridge is reachable."))
            }
            
            if (connectivityResult.isFailure || connectivityResult.getOrNull() != true) {
                Logger.w(LogTags.HUE_USECASE, "Bridge connectivity test failed")
                return Result.failure(Exception("Cannot reach bridge at ${bridge.internalipaddress}. Please check your network."))
            }
            
            // 3. Attempt to connect and create user
            Logger.d(LogTags.HUE_USECASE, "Attempting bridge connection and user creation")
            val connectionResult = bridgeRepository.connectToBridge(bridge)
            
            if (connectionResult.isFailure) {
                val error = connectionResult.exceptionOrNull()
                Logger.w(LogTags.HUE_USECASE, "Bridge connection failed", error)
                
                // Provide user-friendly error messages
                val userMessage = when {
                    error?.message?.contains("link button", ignoreCase = true) == true ->
                        "Please press the link button on your Hue bridge and try again."
                    error?.message?.contains("unauthorized", ignoreCase = true) == true ->
                        "Authorization failed. Please press the link button on your bridge."
                    else ->
                        "Failed to connect to bridge: ${error?.message}"
                }
                
                return Result.failure(Exception(userMessage, error))
            }
            
            val username = connectionResult.getOrNull()
            
            if (username.isNullOrBlank()) {
                Logger.w(LogTags.HUE_USECASE, "Bridge connection returned empty username")
                return Result.failure(Exception("Failed to create user on bridge"))
            }
            
            // 4. Save configuration for persistence
            Logger.d(LogTags.HUE_USECASE, "Saving bridge configuration")
            val saveResult = configRepository.saveBridgeConfig(bridge.internalipaddress, username)
            
            if (saveResult.isFailure) {
                Logger.w(LogTags.HUE_USECASE, "Failed to save bridge configuration", saveResult.exceptionOrNull())
                // Don't fail the setup, just log the warning
            }
            
            // 5. Validate the connection once more
            Logger.d(LogTags.HUE_USECASE, "Validating final bridge connection")
            val validationResult = bridgeRepository.validateConnection()
            
            if (validationResult.isFailure || validationResult.getOrNull() != true) {
                Logger.w(LogTags.HUE_USECASE, "Bridge connection validation failed")
                return Result.failure(Exception("Bridge setup completed but validation failed. Please try again."))
            }
            
            Logger.i(LogTags.HUE_USECASE, "Bridge setup completed successfully")
            Result.success(username)
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_USECASE, "Bridge setup failed with exception", e)
            Result.failure(Exception("Bridge setup failed: ${e.message}", e))
        }
    }
    
    override suspend fun validateBridgeConnection(): Result<Boolean> {
        Logger.d(LogTags.HUE_USECASE, "Validating bridge connection with business logic")
        
        return try {
            // 1. Check if configuration exists
            val config = configRepository.getConfiguration().first()
            
            if (!config.isConfigured) {
                Logger.i(LogTags.HUE_USECASE, "Bridge not configured")
                return Result.success(false)
            }
            
            // 2. Set repository credentials
            bridgeRepository.setBridgeIp(config.bridgeIp)
            bridgeRepository.setUsername(config.username)
            
            // 3. Test actual connection
            val validationResult = withTimeoutOrNull(CONNECTION_TIMEOUT_MS) {
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
        Logger.d(LogTags.HUE_USECASE, "Getting bridge connection information")
        
        return try {
            val config = configRepository.getConfiguration().first()
            
            val connectionInfo = if (config.isConfigured) {
                // Set repository credentials from saved config before validation
                bridgeRepository.setBridgeIp(config.bridgeIp)
                bridgeRepository.setUsername(config.username)
                
                // Test if connection is still valid
                val isConnected = validateBridgeConnection().getOrNull() ?: false
                
                BridgeConnectionInfo(
                    isConnected = isConnected,
                    bridgeIp = config.bridgeIp,
                    bridgeName = "Philips Hue Bridge",
                    username = config.username,
                    lastValidated = System.currentTimeMillis()
                )
            } else {
                BridgeConnectionInfo(
                    isConnected = false,
                    bridgeIp = null,
                    bridgeName = null,
                    username = null,
                    lastValidated = null
                )
            }
            
            Logger.d(LogTags.HUE_USECASE, "Bridge connection info: connected=${connectionInfo.isConnected}")
            Result.success(connectionInfo)
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_USECASE, "Failed to get bridge connection info", e)
            
            // Return a default info object instead of failing
            val defaultInfo = BridgeConnectionInfo(
                isConnected = false,
                bridgeIp = null,
                bridgeName = null,
                username = null,
                lastValidated = null
            )
            
            Result.success(defaultInfo)
        }
    }
}
