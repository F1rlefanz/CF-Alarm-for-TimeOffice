package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.state.AppErrorState
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmSkipUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.ErrorHandler
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.business.DateTimeFormats
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.LocalDateTime
import java.time.Instant
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

data class AlarmUiState(
    val isLoading: Boolean = false,
    val activeAlarms: List<AlarmInfo> = emptyList(),
    val hasActiveAlarms: Boolean = false,
    val nextAlarmTime: String? = null,
    val error: String? = null
)

data class AlarmSkipUiState(
    val isNextAlarmSkipped: Boolean = false,
    val skippedAlarmId: Int? = null,
    val isLoading: Boolean = false,
    val error: AppErrorState? = null
)

/**
 * MANUAL ALARM UI STATE
 * 
 * State für manuelle Alarm-Erstellung nach Schichttausch
 */
data class ManualAlarmUiState(
    val selectedDate: LocalDate = LocalDate.now(),
    val selectedShift: ShiftDefinition? = null,
    val availableShifts: List<ShiftDefinition> = emptyList(),
    val calculatedAlarmTime: String? = null,
    val hasActiveManualAlarm: Boolean = false,
    val activeManualAlarm: AlarmInfo? = null,
    val isCreating: Boolean = false,
    val isDeleting: Boolean = false,
    val error: AppErrorState? = null
)

/**
 * MEMORY LEAK FIXED: AlarmViewModel with proper resource cleanup
 * 
 * CRITICAL FIXES:
 * ✅ Added onCleared() for proper cleanup
 * ✅ Job tracking for Flow collections
 * ✅ Resource cleanup on destruction
 * ✅ Memory leak prevention
 */
class AlarmViewModel(
    private val alarmUseCase: IAlarmUseCase,
    private val alarmSkipUseCase: IAlarmSkipUseCase,
    private val shiftUseCase: IShiftUseCase,
    private val errorHandler: ErrorHandler
) : ViewModel() {

    private val _uiState = MutableStateFlow(AlarmUiState())
    val uiState: StateFlow<AlarmUiState> = _uiState.asStateFlow()
    
    private val _skipState = MutableStateFlow(AlarmSkipUiState())
    val skipState: StateFlow<AlarmSkipUiState> = _skipState.asStateFlow()
    
    private val _manualAlarmState = MutableStateFlow(ManualAlarmUiState())
    val manualAlarmState: StateFlow<ManualAlarmUiState> = _manualAlarmState.asStateFlow()
    
    // MEMORY LEAK FIX: Track Flow collection job for proper cleanup
    private var alarmObservationJob: Job? = null

    init {
        observeAlarmStatus()
        observeSkipStatus()
        loadAvailableShifts()
        observeManualAlarms()
        // CLEANUP: Clean expired alarms on startup
        cleanupExpiredAlarmsOnStartup()
    }
    
    /**
     * CLEANUP: Remove expired alarms when ViewModel starts
     */
    private fun cleanupExpiredAlarmsOnStartup() {
        viewModelScope.launch {
            try {
                // Cast to concrete implementation to access cleanup method
                val repository = alarmUseCase as? com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.AlarmUseCase
                if (repository != null) {
                    // For now, trigger cleanup via deleteAll -> rebuild pattern
                    Logger.d(LogTags.ALARM, "Startup cleanup: checking for expired alarms")
                    
                    // Get all alarms and check for expired ones
                    alarmUseCase.getAllAlarms().onSuccess { allAlarms ->
                        val currentTime = System.currentTimeMillis()
                        val expiredAlarms = allAlarms.filter { it.triggerTime <= currentTime }
                        
                        if (expiredAlarms.isNotEmpty()) {
                            Logger.w(LogTags.ALARM, "Found ${expiredAlarms.size} expired alarms on startup, cleaning up")
                            // Delete each expired alarm
                            expiredAlarms.forEach { alarm ->
                                alarmUseCase.deleteAlarm(alarm.id)
                            }
                        } else {
                            Logger.d(LogTags.ALARM, "No expired alarms found on startup")
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.e(LogTags.ALARM, "Error during startup cleanup", e)
            }
        }
    }

    /**
     * MEMORY LEAK FIX: Proper Job tracking für Flow collections
     */
    private fun observeAlarmStatus() {
        alarmObservationJob?.cancel() // Cancel any existing observation
        
        alarmObservationJob = viewModelScope.launch {
            try {
                alarmUseCase.activeAlarms
                    .distinctUntilChanged()
                    .collect { alarms ->
                        // FIXED: Only consider future alarms for "next alarm" calculation
                        val currentTime = System.currentTimeMillis()
                        val futureAlarms = alarms.filter { it.triggerTime > currentTime }
                        val nextAlarm = futureAlarms.minByOrNull { it.triggerTime }
                        
                        _uiState.value = _uiState.value.copy(
                            activeAlarms = alarms, // Show all alarms for debugging
                            hasActiveAlarms = alarms.isNotEmpty(),
                            nextAlarmTime = nextAlarm?.formattedTime // Only future alarms
                        )
                        
                        Logger.d(LogTags.ALARM, "Active alarms updated: ${alarms.size} total, ${futureAlarms.size} future")
                        
                        // CLEANUP: Log expired alarms for debugging
                        val expiredAlarms = alarms.filter { it.triggerTime <= currentTime }
                        if (expiredAlarms.isNotEmpty()) {
                            Logger.w(LogTags.ALARM, "Found ${expiredAlarms.size} expired alarms: ${expiredAlarms.map { it.formattedTime }}")
                        }
                    }
            } catch (e: Exception) {
                Logger.e(LogTags.ALARM, "Error observing alarm status", e)
                _uiState.value = _uiState.value.copy(
                    error = errorHandler.getErrorMessage(e)
                )
            }
        }
    }

    private fun observeSkipStatus() {
        viewModelScope.launch {
            try {
                alarmSkipUseCase.skipStatusFlow
                    .catch { error ->
                        Logger.e(LogTags.ALARM_SKIP, "Error observing skip state", error)
                    }
                    .collect { skipState ->
                        _skipState.value = _skipState.value.copy(
                            isNextAlarmSkipped = skipState.isNextAlarmSkipped,
                            skippedAlarmId = skipState.skippedAlarmId,
                            isLoading = false
                        )
                    }
            } catch (e: Exception) {
                Logger.e(LogTags.ALARM_SKIP, "Error in skip status observation", e)
            }
        }
    }

    fun setAlarmsForShift(shift: ShiftInfo) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            
            try {
                // Legacy method - wird durch Interface nicht direkt unterstützt
                // Workaround: Konvertiere zu createAlarmsFromEvents call
                val alarmTime = shift.alarmTime
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
                
                val alarmInfo = AlarmInfo(
                    id = shift.id.hashCode(), // Convert String to Int
                    shiftId = shift.id,
                    shiftName = shift.shiftType.displayName,
                    triggerTime = alarmTime,
                    formattedTime = DateTimeFormatter
                        .ofPattern(DateTimeFormats.STANDARD_DATETIME)
                        .format(LocalDateTime.ofInstant(
                            Instant.ofEpochMilli(alarmTime), 
                            ZoneId.systemDefault()
                        ))
                )
                
                alarmUseCase.saveAlarm(alarmInfo)
                    .onSuccess {
                        _uiState.value = _uiState.value.copy(isLoading = false)
                        Logger.i(LogTags.ALARM, "Alarm set for shift: ${shift.shiftType.displayName}")
                    }
                    .onFailure { error ->
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = errorHandler.getErrorMessage(error)
                        )
                        Logger.e(LogTags.ALARM, "Failed to set alarm for shift", error)
                    }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = errorHandler.getErrorMessage(e)
                )
                Logger.e(LogTags.ALARM, "Exception setting alarm for shift", e)
            }
        }
    }

    fun cancelAlarm(alarmId: Int) {
        viewModelScope.launch {
            try {
                alarmUseCase.deleteAlarm(alarmId)
                    .onSuccess {
                        Logger.i(LogTags.ALARM, "Alarm cancelled: $alarmId")
                    }
                    .onFailure { error ->
                        _uiState.value = _uiState.value.copy(
                            error = errorHandler.getErrorMessage(error)
                        )
                        Logger.e(LogTags.ALARM, "Failed to cancel alarm: $alarmId", error)
                    }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = errorHandler.getErrorMessage(e)
                )
                Logger.e(LogTags.ALARM, "Exception cancelling alarm: $alarmId", e)
            }
        }
    }

    fun cancelAllAlarms() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            
            try {
                alarmUseCase.deleteAllAlarms()
                    .onSuccess {
                        _uiState.value = _uiState.value.copy(isLoading = false)
                        Logger.i(LogTags.ALARM, "All alarms cancelled")
                    }
                    .onFailure { error ->
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = errorHandler.getErrorMessage(error)
                        )
                        Logger.e(LogTags.ALARM, "Failed to cancel all alarms", error)
                    }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = errorHandler.getErrorMessage(e)
                )
                Logger.e(LogTags.ALARM, "Exception cancelling all alarms", e)
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
    
    fun skipNextAlarm() {
        viewModelScope.launch {
            _skipState.value = _skipState.value.copy(isLoading = true)
            
            alarmSkipUseCase.skipNextAlarm()
                .onSuccess { result ->
                    Logger.business(LogTags.ALARM_SKIP, "✅ Next alarm skip activated: ${result.alarmName}")
                    // State wird automatisch über skipStatusFlow aktualisiert
                }
                .onFailure { error ->
                    _skipState.value = _skipState.value.copy(
                        isLoading = false,
                        error = AppErrorState.validationError(error.message ?: "Failed to skip alarm")
                    )
                    Logger.e(LogTags.ALARM_SKIP, "❌ Failed to skip next alarm", error)
                }
        }
    }

    fun cancelSkip() {
        viewModelScope.launch {
            alarmSkipUseCase.cancelSkip()
                .onSuccess {
                    Logger.business(LogTags.ALARM_SKIP, "✅ Skip cancelled by user")
                    // State wird automatisch über skipStatusFlow aktualisiert
                }
                .onFailure { error ->
                    Logger.e(LogTags.ALARM_SKIP, "❌ Failed to cancel skip", error)
                }
        }
    }
    
    // ========================================
    // MANUAL ALARM FUNCTIONALITY
    // ========================================
    
    /**
     * Manual Alarm Constants - simplified approach using existing patterns
     */
    object ManualAlarmConstants {
        const val MANUAL_ALARM_PREFIX = "MANUAL_"
        const val MANUAL_SHIFT_ID_PREFIX = "manual_"
        
        fun createManualAlarmId(date: LocalDate, shiftId: String): Int {
            val dateString = date.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
            return "$MANUAL_ALARM_PREFIX$dateString$shiftId".hashCode()
        }
        
        fun createManualShiftId(originalShiftId: String, date: LocalDate): String {
            val dateString = date.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
            return "$MANUAL_SHIFT_ID_PREFIX${originalShiftId}_$dateString"
        }
        
        fun isManualAlarm(alarmInfo: AlarmInfo): Boolean {
            return alarmInfo.shiftId.startsWith(MANUAL_SHIFT_ID_PREFIX)
        }
    }
    
    private fun loadAvailableShifts() {
        viewModelScope.launch {
            try {
                // 🔍 CRITICAL DEBUG: Überprüfen was getCurrentShiftConfig() wirklich zurückgibt
                val shiftConfigResult = shiftUseCase.getCurrentShiftConfig()
                Logger.business(LogTags.ALARM, "🔍 SHIFT CONFIG RESULT: success=${shiftConfigResult.isSuccess}")
                
                shiftConfigResult.getOrNull()?.let { shiftConfig ->
                    // 🔍 DEBUG: Loaded shift config validation
                    Logger.business(LogTags.ALARM, "🔍 CONFIG LOADED: ${shiftConfig.definitions.size} definitions")
                    
                    Logger.business(LogTags.ALARM, "🔍 LOADED CONFIG has ${shiftConfig.definitions.size} definitions:")
                    shiftConfig.definitions.forEach { def ->
                        Logger.business(LogTags.ALARM, "   ${def.id}: ${def.name} -> ${def.alarmTime} (${def.getAlarmTimeFormatted()})")
                    }
                    
                    val availableShifts = shiftConfig.definitions.filter { it.isEnabled }
                    
                    // 🔍 LOG: Available shifts for manual alarm creation
                    
                    _manualAlarmState.value = _manualAlarmState.value.copy(
                        availableShifts = availableShifts,
                        selectedShift = availableShifts.firstOrNull() // Erste verfügbare Schicht
                    )
                    
                    // Update calculated alarm time
                    updateCalculatedAlarmTime()
                    
                    Logger.d(LogTags.ALARM, "Loaded ${availableShifts.size} user-configured shift definitions")
                } ?: run {
                    Logger.e(LogTags.ALARM, "🚨 CRITICAL: getCurrentShiftConfig() returned null!")
                    
                    // FALLBACK: Versuche direkt die Default-Config zu laden
                    val defaultConfig = com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig.getDefaultConfig()
                    Logger.w(LogTags.ALARM, "🔄 FALLBACK: Using default config with ${defaultConfig.definitions.size} definitions")
                    
                    val availableShifts = defaultConfig.definitions.filter { it.isEnabled }
                    _manualAlarmState.value = _manualAlarmState.value.copy(
                        availableShifts = availableShifts,
                        selectedShift = availableShifts.firstOrNull(),
                        error = AppErrorState.validationError("Warnung: Default-Konfiguration wird verwendet")
                    )
                    updateCalculatedAlarmTime()
                }
            } catch (e: Exception) {
                Logger.e(LogTags.ALARM, "🚨 ERROR loading shift definitions", e)
                _manualAlarmState.value = _manualAlarmState.value.copy(
                    error = AppErrorState.validationError("Fehler beim Laden: ${e.message}")
                )
            }
        }
    }

    private fun observeManualAlarms() {
        viewModelScope.launch {
            try {
                alarmUseCase.activeAlarms
                    .distinctUntilChanged()
                    .collect { alarms ->
                        // Filter für manuelle Alarme
                        val manualAlarms = alarms.filter { ManualAlarmConstants.isManualAlarm(it) }
                        val activeManualAlarm = manualAlarms.firstOrNull() // Nur einer zur Zeit
                        
                        _manualAlarmState.value = _manualAlarmState.value.copy(
                            hasActiveManualAlarm = activeManualAlarm != null,
                            activeManualAlarm = activeManualAlarm
                        )
                        
                        Logger.d(LogTags.ALARM, "Manual alarms updated: ${manualAlarms.size}")
                    }
            } catch (e: Exception) {
                Logger.e(LogTags.ALARM, "Error observing manual alarms", e)
            }
        }
    }

    fun selectManualAlarmDate(date: LocalDate) {
        _manualAlarmState.value = _manualAlarmState.value.copy(selectedDate = date)
        updateCalculatedAlarmTime()
    }

    fun selectManualAlarmShift(shift: ShiftDefinition) {
        Logger.d(LogTags.ALARM, "Manual alarm shift selected: ${shift.name}")
        
        _manualAlarmState.value = _manualAlarmState.value.copy(selectedShift = shift)
        updateCalculatedAlarmTime()
    }

    private fun updateCalculatedAlarmTime() {
        val state = _manualAlarmState.value
        val selectedShift = state.selectedShift
        val selectedDate = state.selectedDate
        
        if (selectedShift != null) {
            // 🔍 DEBUG: Log die verwendete Schicht-Zeit
            Logger.business(LogTags.ALARM, "🎯 CALCULATING alarm time for shift: ${selectedShift.name}")
            Logger.business(LogTags.ALARM, "   📅 Date: $selectedDate")
            Logger.business(LogTags.ALARM, "   ⏰ Shift alarmTime: ${selectedShift.alarmTime}")
            Logger.business(LogTags.ALARM, "   📋 Shift formatted: ${selectedShift.getAlarmTimeFormatted()}")
            
            // ✅ KORRIGIERT: Verwende die User-konfigurierte Zeit OHNE bescheuerten Offset
            val alarmDateTime = selectedDate.atTime(selectedShift.alarmTime)
            
            val formattedTime = alarmDateTime.format(
                DateTimeFormatter.ofPattern(DateTimeFormats.STANDARD_DATETIME)
            )
            
            Logger.business(LogTags.ALARM, "   🚨 FINAL calculated alarm: $formattedTime")
            
            _manualAlarmState.value = _manualAlarmState.value.copy(
                calculatedAlarmTime = formattedTime
            )
        } else {
            Logger.w(LogTags.ALARM, "⚠️ NO SHIFT selected for alarm calculation")
            _manualAlarmState.value = _manualAlarmState.value.copy(
                calculatedAlarmTime = null
            )
        }
    }

    fun createManualAlarm() {
        viewModelScope.launch {
            val state = _manualAlarmState.value
            val selectedShift = state.selectedShift
            val selectedDate = state.selectedDate
            
            if (selectedShift == null) {
                _manualAlarmState.value = state.copy(
                    error = AppErrorState.validationError("Bitte wählen Sie eine Schicht aus")
                )
                return@launch
            }
            
            _manualAlarmState.value = state.copy(isCreating = true, error = null)
            
            try {
                // Berechne Alarm-Zeit - ✅ OHNE bescheuerten Offset
                val alarmDateTime = selectedDate.atTime(selectedShift.alarmTime)
                val alarmTimeMillis = alarmDateTime
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
                
                // Prüfe ob in der Zukunft
                if (alarmTimeMillis <= System.currentTimeMillis()) {
                    _manualAlarmState.value = state.copy(
                        isCreating = false,
                        error = AppErrorState.validationError("Alarm-Zeit muss in der Zukunft liegen")
                    )
                    return@launch
                }
                
                // Lösche vorherigen manuellen Alarm (nur einer zur Zeit)
                state.activeManualAlarm?.let { existingAlarm ->
                    alarmUseCase.deleteAlarm(existingAlarm.id)
                }
                
                // Erstelle AlarmInfo
                val manualAlarmId = ManualAlarmConstants.createManualAlarmId(selectedDate, selectedShift.id)
                val manualShiftId = ManualAlarmConstants.createManualShiftId(selectedShift.id, selectedDate)
                
                val alarmInfo = AlarmInfo(
                    id = manualAlarmId,
                    shiftId = manualShiftId,
                    shiftName = "${selectedShift.name} (Manuell)",
                    triggerTime = alarmTimeMillis,
                    formattedTime = alarmDateTime.format(
                        DateTimeFormatter.ofPattern(DateTimeFormats.STANDARD_DATETIME)
                    )
                )
                
                // Speichere Alarm
                alarmUseCase.saveAlarm(alarmInfo)
                    .onSuccess {
                        // Schedule System Alarm
                        alarmUseCase.scheduleSystemAlarm(alarmInfo)
                            .onSuccess {
                                _manualAlarmState.value = _manualAlarmState.value.copy(
                                    isCreating = false
                                )
                                Logger.business(LogTags.ALARM, "✅ Manual alarm created: ${selectedShift.name} for $selectedDate")
                            }
                            .onFailure { error ->
                                _manualAlarmState.value = _manualAlarmState.value.copy(
                                    isCreating = false,
                                    error = AppErrorState.networkError(error.message ?: "Fehler beim Schedulen des Alarms")
                                )
                                Logger.e(LogTags.ALARM, "❌ Failed to schedule manual alarm", error)
                            }
                    }
                    .onFailure { error ->
                        _manualAlarmState.value = _manualAlarmState.value.copy(
                            isCreating = false,
                            error = AppErrorState.validationError(error.message ?: "Fehler beim Speichern des Alarms")
                        )
                        Logger.e(LogTags.ALARM, "❌ Failed to save manual alarm", error)
                    }
                
            } catch (e: Exception) {
                _manualAlarmState.value = _manualAlarmState.value.copy(
                    isCreating = false,
                    error = AppErrorState.validationError(e.message ?: "Unbekannter Fehler beim Erstellen des Alarms")
                )
                Logger.e(LogTags.ALARM, "❌ Exception creating manual alarm", e)
            }
        }
    }

    fun deleteManualAlarm() {
        viewModelScope.launch {
            val activeAlarm = _manualAlarmState.value.activeManualAlarm
            if (activeAlarm == null) {
                Logger.w(LogTags.ALARM, "No active manual alarm to delete")
                return@launch
            }
            
            _manualAlarmState.value = _manualAlarmState.value.copy(isDeleting = true, error = null)
            
            try {
                alarmUseCase.deleteAlarm(activeAlarm.id)
                    .onSuccess {
                        _manualAlarmState.value = _manualAlarmState.value.copy(isDeleting = false)
                        Logger.business(LogTags.ALARM, "✅ Manual alarm deleted: ${activeAlarm.shiftName}")
                    }
                    .onFailure { error ->
                        _manualAlarmState.value = _manualAlarmState.value.copy(
                            isDeleting = false,
                            error = AppErrorState.validationError(error.message ?: "Fehler beim Löschen des Alarms")
                        )
                        Logger.e(LogTags.ALARM, "❌ Failed to delete manual alarm", error)
                    }
            } catch (e: Exception) {
                _manualAlarmState.value = _manualAlarmState.value.copy(
                    isDeleting = false,
                    error = AppErrorState.validationError(e.message ?: "Unbekannter Fehler beim Löschen des Alarms")
                )
                Logger.e(LogTags.ALARM, "❌ Exception deleting manual alarm", e)
            }
        }
    }

    fun clearManualAlarmError() {
        _manualAlarmState.value = _manualAlarmState.value.copy(error = null)
    }
    
    /**
     * MEMORY LEAK PREVENTION: Comprehensive resource cleanup
     * CRITICAL FIX: This was missing and causing memory leaks!
     */
    override fun onCleared() {
        super.onCleared()
        
        try {
            // MEMORY LEAK FIX: Cancel alarm observation job
            alarmObservationJob?.cancel()
            alarmObservationJob = null
            
            // MEMORY OPTIMIZATION: Clear state to release references
            _uiState.value = AlarmUiState()
            _skipState.value = AlarmSkipUiState()
            _manualAlarmState.value = ManualAlarmUiState()
            
            Logger.d(LogTags.LIFECYCLE, "AlarmViewModel cleared - cleaning up alarm observations and resources")
        } catch (e: Exception) {
            Logger.e(LogTags.LIFECYCLE, "Error during AlarmViewModel cleanup", e)
        }
        
        // Note: ViewModelScope automatically cancels all remaining coroutines
    }
}
