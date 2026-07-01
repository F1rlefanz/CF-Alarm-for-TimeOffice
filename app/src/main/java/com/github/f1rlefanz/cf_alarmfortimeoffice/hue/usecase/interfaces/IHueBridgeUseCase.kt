package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.BridgeConnectionInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.DiscoveryStatus
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueBridge
import kotlinx.coroutines.flow.Flow

/**
 * Interface for Hue Bridge UseCase operations
 * Business logic layer following Clean Architecture
 */
interface IHueBridgeUseCase {
    
    /**
     * Discover available Hue bridges
     * Handles business logic like retries, timeouts, and validation
     */
    suspend fun discoverBridges(): Result<List<HueBridge>>
    
    /**
     * Get live discovery status updates
     */
    fun getDiscoveryStatus(): Flow<DiscoveryStatus>
    
    /**
     * Complete bridge setup process
     * Includes connection, user creation, and validation
     */
    suspend fun setupBridge(bridge: HueBridge): Result<String>
    
    /**
     * Validate existing bridge connection
     * Tests connectivity and authentication
     */
    suspend fun validateBridgeConnection(): Result<Boolean>
    
    /**
     * Get current bridge connection status
     */
    suspend fun getBridgeConnectionInfo(): Result<BridgeConnectionInfo>

    /**
     * "Verbindung trennen / Bridge vergessen" (UX FEATURE B): clears the persisted bridge
     * IP/username, the in-memory connection state, and the TLS trust pin, returning the app
     * to the discovery/onboarding state. Saved schedule rules are intentionally kept.
     */
    suspend fun forgetBridge(): Result<Unit>
}
