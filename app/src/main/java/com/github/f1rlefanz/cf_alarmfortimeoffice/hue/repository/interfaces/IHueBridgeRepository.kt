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
     * Set the username for authenticated requests
     */
    fun setUsername(username: String)
    
    /**
     * Set the bridge IP for API requests
     */
    fun setBridgeIp(bridgeIp: String)
    
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
}
