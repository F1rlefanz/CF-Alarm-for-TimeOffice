package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.DiscoveryStatus
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueBridge
import kotlinx.coroutines.flow.Flow

/**
 * Interface for Hue Bridge repository operations
 * Follows Clean Architecture principles with testable abstractions
 */
interface IHueBridgeRepository {
    
    /**
     * Discover Hue bridges on the network
     * @return Flow of discovery status updates
     */
    suspend fun discoverBridges(): Result<List<HueBridge>>
    
    /**
     * Get discovery status updates as flow
     */
    fun getDiscoveryStatus(): Flow<DiscoveryStatus>
    
    /**
     * Connect to a specific bridge and create user
     * @param bridge The bridge to connect to
     * @return Username token for API access
     */
    suspend fun connectToBridge(bridge: HueBridge): Result<String>
    
    /**
     * Initialize the repository connection from a persisted configuration.
     *
     * Replaces the legacy fire-and-forget setUsername()/setBridgeIp() setters:
     * sets bridge IP and username atomically and suspends until the connection
     * is persisted, so callers can rely on the connection being ready afterwards.
     */
    suspend fun initializeFromConfig(bridgeIp: String, username: String): Result<Unit>
    
    /**
     * Get current bridge IP address
     */
    fun getCurrentBridgeIp(): String?
    
    /**
     * Get current username
     */
    fun getCurrentUsername(): String?
    
    /**
     * Validate current connection to bridge
     */
    suspend fun validateConnection(): Result<Boolean>
    
    /**
     * Test connection to specific bridge
     */
    suspend fun testBridgeConnection(bridge: HueBridge): Result<Boolean>

    /**
     * "Verbindung trennen / Bridge vergessen" (UX FEATURE B): clears the persisted connection
     * (IP/username) and TLS trust pin, resetting the repository to the disconnected state.
     */
    suspend fun forgetConnection(): Result<Unit>
}
