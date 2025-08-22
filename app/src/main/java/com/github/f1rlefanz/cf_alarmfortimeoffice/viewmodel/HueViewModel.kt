package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.*
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.*
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.BridgeConnectionInfo

/**
 * ViewModel for Hue Integration following Clean Architecture
 * Manages state for Bridge Setup, Light Control, and Rule Management
 */
class HueViewModel(
    private val hueBridgeUseCase: IHueBridgeUseCase,
    private val hueLightUseCase: IHueLightUseCase,
    private val hueRuleUseCase: IHueRuleUseCase
) : ViewModel() {
    
    // ==============================
    // STATE MANAGEMENT
    // ==============================
    
    private val _uiState = MutableStateFlow(HueUiState())
    val uiState: StateFlow<HueUiState> = _uiState.asStateFlow()
    
    private val _discoveryStatus = MutableStateFlow<DiscoveryStatus?>(null)
    val discoveryStatus: StateFlow<DiscoveryStatus?> = _discoveryStatus.asStateFlow()
    
    // ==============================
    // INITIALIZATION
    // ==============================
    
    init {
        Logger.i(LogTags.HUE_VIEWMODEL, "HueViewModel initialized")
        
        // Start observing discovery status
        viewModelScope.launch {
            hueBridgeUseCase.getDiscoveryStatus().collect { status ->
                _discoveryStatus.value = status
                Logger.d(LogTags.HUE_VIEWMODEL, "Discovery status updated: ${status.stage}")
            }
        }
        
        // Load initial state - but only if not connecting
        loadInitialState()
    }
    
    private fun loadInitialState() {
        viewModelScope.launch {
            try {
                // Try to get saved bridge connection info
                val result = hueBridgeUseCase.getBridgeConnectionInfo()
                if (result.isSuccess) {
                    val connectionInfo = result.getOrNull()
                    _uiState.update { it.copy(bridgeConnectionInfo = connectionInfo) }
                    Logger.d(LogTags.HUE_VIEWMODEL, "Initial bridge connection loaded: connected=${connectionInfo?.isConnected}")
                    
                    // Only refresh lights and rules if we have a valid connection
                    if (connectionInfo?.isConnected == true) {
                        refreshLightTargets()
                        refreshRules()
                    }
                }
            } catch (e: Exception) {
                Logger.e(LogTags.HUE_VIEWMODEL, "Failed to load initial state", e)
            }
        }
    }
    
    // ==============================
    // BRIDGE OPERATIONS
    // ==============================
    
    fun discoverBridges() {
        Logger.i(LogTags.HUE_VIEWMODEL, "Starting bridge discovery")
        
        _uiState.update { it.copy(isLoading = true, error = null) }
        
        viewModelScope.launch {
            try {
                val result = hueBridgeUseCase.discoverBridges()
                
                if (result.isSuccess) {
                    val bridges = result.getOrNull() ?: emptyList()
                    _uiState.update { 
                        it.copy(
                            isLoading = false,
                            discoveredBridges = bridges,
                            error = if (bridges.isEmpty()) "No bridges found. Please check your network connection." else null
                        )
                    }
                    Logger.i(LogTags.HUE_VIEWMODEL, "Bridge discovery completed: ${bridges.size} bridges found")
                } else {
                    val error = result.exceptionOrNull()?.message ?: "Discovery failed"
                    _uiState.update { it.copy(isLoading = false, error = error) }
                    Logger.w(LogTags.HUE_VIEWMODEL, "Bridge discovery failed: $error")
                }
            } catch (e: Exception) {
                val error = "Discovery failed: ${e.message}"
                _uiState.update { it.copy(isLoading = false, error = error) }
                Logger.e(LogTags.HUE_VIEWMODEL, "Bridge discovery exception", e)
            }
        }
    }
    
    fun setupBridge(bridge: HueBridge) {
        Logger.i(LogTags.HUE_VIEWMODEL, "Setting up bridge: ${bridge.internalipaddress}")
        
        _uiState.update { it.copy(isLoading = true, error = null) }
        
        viewModelScope.launch {
            try {
                val result = hueBridgeUseCase.setupBridge(bridge)
                
                if (result.isSuccess) {
                    _uiState.update { 
                        it.copy(
                            isLoading = false,
                            bridgeConnectionInfo = BridgeConnectionInfo(
                                isConnected = true,
                                bridgeIp = bridge.internalipaddress,
                                bridgeName = bridge.name,
                                username = result.getOrNull(),
                                lastValidated = System.currentTimeMillis()
                            )
                        )
                    }
                    
                    // DON'T call refreshLightTargets immediately - let UI show success first
                    Logger.i(LogTags.HUE_VIEWMODEL, "Bridge setup completed successfully")
                } else {
                    val error = result.exceptionOrNull()?.message ?: "Bridge setup failed"
                    _uiState.update { it.copy(isLoading = false, error = error) }
                    Logger.w(LogTags.HUE_VIEWMODEL, "Bridge setup failed: $error")
                }
            } catch (e: Exception) {
                val error = "Bridge setup failed: ${e.message}"
                _uiState.update { it.copy(isLoading = false, error = error) }
                Logger.e(LogTags.HUE_VIEWMODEL, "Bridge setup exception", e)
            }
        }
    }
    
    fun validateBridgeConnection() {
        Logger.d(LogTags.HUE_VIEWMODEL, "Validating bridge connection")
        
        viewModelScope.launch {
            try {
                val result = hueBridgeUseCase.validateBridgeConnection()
                val isValid = result.getOrNull() ?: false
                
                _uiState.update { currentState ->
                    currentState.copy(
                        bridgeConnectionInfo = currentState.bridgeConnectionInfo?.copy(
                            isConnected = isValid,
                            lastValidated = System.currentTimeMillis()
                        )
                    )
                }
                
                Logger.d(LogTags.HUE_VIEWMODEL, "Bridge connection validation result: $isValid")
                
                // If validation successful, refresh lights and rules
                if (isValid) {
                    refreshLightTargets()
                    refreshRules()
                }
            } catch (e: Exception) {
                Logger.e(LogTags.HUE_VIEWMODEL, "Bridge validation exception", e)
            }
        }
    }
    
    // REMOVED: refreshBridgeConnectionInfo() - replaced by loadInitialState() and direct setup
    
    // ==============================
    // LIGHT OPERATIONS
    // ==============================
    
    fun refreshLightTargets() {
        Logger.d(LogTags.HUE_VIEWMODEL, "Refreshing light targets")
        
        viewModelScope.launch {
            try {
                val result = hueLightUseCase.getAllLightTargets()
                
                if (result.isSuccess) {
                    val lightTargets = result.getOrNull() ?: LightTargets()
                    _uiState.update { it.copy(lightTargets = lightTargets) }
                    Logger.d(LogTags.HUE_VIEWMODEL, "Light targets refreshed: ${lightTargets.lights.size} lights, ${lightTargets.groups.size} groups")
                } else {
                    Logger.w(LogTags.HUE_VIEWMODEL, "Failed to refresh light targets", result.exceptionOrNull())
                }
            } catch (e: Exception) {
                Logger.e(LogTags.HUE_VIEWMODEL, "Light targets refresh exception", e)
            }
        }
    }
    
    fun executeLightAction(action: LightAction) {
        Logger.i(LogTags.HUE_VIEWMODEL, "Executing light action for ${action.targetId}")
        
        viewModelScope.launch {
            try {
                val result = hueLightUseCase.executeLightAction(action)
                
                if (result.isSuccess) {
                    val actionResult = result.getOrNull()
                    if (actionResult?.success == true) {
                        Logger.i(LogTags.HUE_VIEWMODEL, "Light action executed successfully")
                        // Optionally refresh light states
                        refreshLightTargets()
                    } else {
                        val error = actionResult?.error ?: "Light action failed"
                        _uiState.update { it.copy(error = error) }
                        Logger.w(LogTags.HUE_VIEWMODEL, "Light action failed: $error")
                    }
                } else {
                    val error = result.exceptionOrNull()?.message ?: "Failed to execute light action"
                    _uiState.update { it.copy(error = error) }
                    Logger.w(LogTags.HUE_VIEWMODEL, "Light action execution failed: $error")
                }
            } catch (e: Exception) {
                val error = "Light action failed: ${e.message}"
                _uiState.update { it.copy(error = error) }
                Logger.e(LogTags.HUE_VIEWMODEL, "Light action exception", e)
            }
        }
    }
    
    fun testLightConnection(targetId: String, isGroup: Boolean) {
        Logger.d(LogTags.HUE_VIEWMODEL, "Testing connection for ${if (isGroup) "group" else "light"} $targetId")
        
        viewModelScope.launch {
            try {
                val result = hueLightUseCase.testLightConnection(targetId, isGroup)
                val isConnected = result.getOrNull() ?: false
                
                val message = if (isConnected) {
                    "${if (isGroup) "Group" else "Light"} $targetId is reachable"
                } else {
                    "${if (isGroup) "Group" else "Light"} $targetId is not reachable"
                }
                
                // You could show this in a snackbar or similar UI element
                Logger.i(LogTags.HUE_VIEWMODEL, "Connection test result: $message")
                
            } catch (e: Exception) {
                Logger.e(LogTags.HUE_VIEWMODEL, "Connection test exception", e)
            }
        }
    }
    
    // ==============================
    // RULE OPERATIONS
    // ==============================
    
    fun refreshRules() {
        Logger.d(LogTags.HUE_VIEWMODEL, "Refreshing schedule rules")
        
        viewModelScope.launch {
            try {
                val result = hueRuleUseCase.getAllRules()
                
                if (result.isSuccess) {
                    val rules = result.getOrNull() ?: emptyList()
                    _uiState.update { it.copy(scheduleRules = rules) }
                    Logger.d(LogTags.HUE_VIEWMODEL, "Schedule rules refreshed: ${rules.size} rules")
                } else {
                    Logger.w(LogTags.HUE_VIEWMODEL, "Failed to refresh rules", result.exceptionOrNull())
                }
            } catch (e: Exception) {
                Logger.e(LogTags.HUE_VIEWMODEL, "Rules refresh exception", e)
            }
        }
    }
    
    fun loadRuleForEditing(ruleId: String) {
        Logger.d(LogTags.HUE_VIEWMODEL, "Loading rule for editing: $ruleId")
        
        _uiState.update { it.copy(isLoading = true, error = null) }
        
        viewModelScope.launch {
            try {
                val result = hueRuleUseCase.getRule(ruleId)
                
                if (result.isSuccess) {
                    val rule = result.getOrNull()
                    _uiState.update { 
                        it.copy(
                            isLoading = false,
                            editingRule = rule
                        )
                    }
                    Logger.d(LogTags.HUE_VIEWMODEL, "Rule loaded for editing: ${rule?.name}")
                } else {
                    val error = result.exceptionOrNull()?.message ?: "Failed to load rule"
                    _uiState.update { it.copy(isLoading = false, error = error) }
                    Logger.w(LogTags.HUE_VIEWMODEL, "Failed to load rule for editing: $error")
                }
            } catch (e: Exception) {
                val error = "Failed to load rule: ${e.message}"
                _uiState.update { it.copy(isLoading = false, error = error) }
                Logger.e(LogTags.HUE_VIEWMODEL, "Load rule for editing exception", e)
            }
        }
    }
    
    fun clearEditingRule() {
        Logger.d(LogTags.HUE_VIEWMODEL, "Clearing editing rule")
        _uiState.update { it.copy(editingRule = null) }
    }
    
    fun createRule(rule: HueSchedule) {
        Logger.i(LogTags.HUE_VIEWMODEL, "Creating new rule: ${rule.name}")
        
        _uiState.update { it.copy(isLoading = true, error = null) }
        
        viewModelScope.launch {
            try {
                val result = hueRuleUseCase.createRule(rule)
                
                if (result.isSuccess) {
                    _uiState.update { it.copy(isLoading = false) }
                    refreshRules() // Refresh to show new rule
                    Logger.i(LogTags.HUE_VIEWMODEL, "Rule created successfully: ${rule.name}")
                } else {
                    val error = result.exceptionOrNull()?.message ?: "Failed to create rule"
                    _uiState.update { it.copy(isLoading = false, error = error) }
                    Logger.w(LogTags.HUE_VIEWMODEL, "Rule creation failed: $error")
                }
            } catch (e: Exception) {
                val error = "Rule creation failed: ${e.message}"
                _uiState.update { it.copy(isLoading = false, error = error) }
                Logger.e(LogTags.HUE_VIEWMODEL, "Rule creation exception", e)
            }
        }
    }
    
    fun updateRule(rule: HueSchedule) {
        Logger.i(LogTags.HUE_VIEWMODEL, "Updating rule: ${rule.id}")
        
        _uiState.update { it.copy(isLoading = true, error = null) }
        
        viewModelScope.launch {
            try {
                val result = hueRuleUseCase.updateRule(rule)
                
                if (result.isSuccess) {
                    _uiState.update { it.copy(isLoading = false) }
                    refreshRules() // Refresh to show updated rule
                    Logger.i(LogTags.HUE_VIEWMODEL, "Rule updated successfully: ${rule.id}")
                } else {
                    val error = result.exceptionOrNull()?.message ?: "Failed to update rule"
                    _uiState.update { it.copy(isLoading = false, error = error) }
                    Logger.w(LogTags.HUE_VIEWMODEL, "Rule update failed: $error")
                }
            } catch (e: Exception) {
                val error = "Rule update failed: ${e.message}"
                _uiState.update { it.copy(isLoading = false, error = error) }
                Logger.e(LogTags.HUE_VIEWMODEL, "Rule update exception", e)
            }
        }
    }
    
    fun deleteRule(ruleId: String) {
        Logger.i(LogTags.HUE_VIEWMODEL, "Deleting rule: $ruleId")
        
        _uiState.update { it.copy(isLoading = true, error = null) }
        
        viewModelScope.launch {
            try {
                val result = hueRuleUseCase.deleteRule(ruleId)
                
                if (result.isSuccess) {
                    _uiState.update { it.copy(isLoading = false) }
                    refreshRules() // Refresh to remove deleted rule
                    Logger.i(LogTags.HUE_VIEWMODEL, "Rule deleted successfully: $ruleId")
                } else {
                    val error = result.exceptionOrNull()?.message ?: "Failed to delete rule"
                    _uiState.update { it.copy(isLoading = false, error = error) }
                    Logger.w(LogTags.HUE_VIEWMODEL, "Rule deletion failed: $error")
                }
            } catch (e: Exception) {
                val error = "Rule deletion failed: ${e.message}"
                _uiState.update { it.copy(isLoading = false, error = error) }
                Logger.e(LogTags.HUE_VIEWMODEL, "Rule deletion exception", e)
            }
        }
    }
    
    fun testRuleExecution(rule: HueSchedule) {
        Logger.i(LogTags.HUE_VIEWMODEL, "Testing rule execution: ${rule.name}")
        
        viewModelScope.launch {
            try {
                val result = hueRuleUseCase.testRuleExecution(rule)
                
                if (result.isSuccess) {
                    val actions = result.getOrNull() ?: emptyList()
                    Logger.i(LogTags.HUE_VIEWMODEL, "Rule test completed: ${actions.size} actions would be executed")
                    
                    // Optionally execute the actions or just show preview
                    if (actions.isNotEmpty()) {
                        hueLightUseCase.executeBatchLightActions(actions)
                        // Handle batch result...
                    }
                } else {
                    val error = result.exceptionOrNull()?.message ?: "Rule test failed"
                    _uiState.update { it.copy(error = error) }
                    Logger.w(LogTags.HUE_VIEWMODEL, "Rule test failed: $error")
                }
            } catch (e: Exception) {
                val error = "Rule test failed: ${e.message}"
                _uiState.update { it.copy(error = error) }
                Logger.e(LogTags.HUE_VIEWMODEL, "Rule test exception", e)
            }
        }
    }
    
    // ==============================
    // ERROR HANDLING
    // ==============================
    
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
    
    fun clearDiscoveredBridges() {
        _uiState.update { it.copy(discoveredBridges = emptyList()) }
    }
}

/**
 * UI State for Hue Integration
 */
@Immutable
data class HueUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    
    // Bridge Management
    val bridgeConnectionInfo: BridgeConnectionInfo? = null,
    val discoveredBridges: List<HueBridge> = emptyList(),
    
    // Light Management
    val lightTargets: LightTargets = LightTargets(),
    
    // Rule Management
    val scheduleRules: List<HueSchedule> = emptyList(),
    val editingRule: HueSchedule? = null
)
