package com.github.f1rlefanz.cf_alarmfortimeoffice.usecase

import com.github.f1rlefanz.cf_alarmfortimeoffice.error.SafeExecutor
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IShiftConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftMatch
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftRecognitionEngine
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * UseCase für alle Shift-bezogenen Operationen - implementiert IShiftUseCase
 * 
 * REFACTORED + OPTIMIZED:
 * ✅ Implementiert IShiftUseCase Interface für bessere Testbarkeit
 * ✅ Verwendet Repository-Interfaces statt konkrete Implementierungen
 * ✅ Erweiterte Business Logic für Shift-Management
 * ✅ Result-basierte API für konsistente Fehlerbehandlung
 * ✅ Integration mit ShiftRecognitionEngine für intelligente Shift-Erkennung
 * ✅ SINGLETON PATTERN: Cache-Invalidierung für optimale Performance
 */
class ShiftUseCase @Inject constructor(
    private val shiftConfigRepository: IShiftConfigRepository,
    private val shiftRecognitionEngine: ShiftRecognitionEngine
) : IShiftUseCase {
    
    override val shiftConfig: Flow<ShiftConfig> = shiftConfigRepository.shiftConfig
    
    /**
     * SINGLETON OPTIMIZATION: Invalidates both recognition cache and config cache
     */
    private fun invalidateAllCaches() {
        // Clear recognition cache
        shiftRecognitionEngine.clearRecognitionCache()
        
        // Clear config cache if repository supports it
        (shiftConfigRepository as? com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftConfigRepository)?.invalidateCache()
        
        Logger.d(LogTags.SHIFT_CONFIG, "🗑️ SINGLETON-INVALIDATE: All caches cleared due to config change")
    }
    
    override suspend fun saveShiftConfig(config: ShiftConfig): Result<Unit> = 
        shiftConfigRepository.saveShiftConfig(config).also { result ->
            if (result.isSuccess) {
                // SINGLETON OPTIMIZATION: Clear all caches when config changes
                invalidateAllCaches()
            }
        }
    
    override suspend fun getCurrentShiftConfig(): Result<ShiftConfig> = 
        shiftConfigRepository.getCurrentShiftConfig()
    
    override suspend fun recognizeShiftsInEvents(events: List<CalendarEvent>): Result<List<ShiftMatch>> = 
        SafeExecutor.safeExecute("ShiftUseCase.recognizeShiftsInEvents") {
            val shiftMatches = shiftRecognitionEngine.getAllMatchingShifts(events)
            Logger.d(LogTags.SHIFT_RECOGNITION, "Recognized ${shiftMatches.size} shifts from ${events.size} events")
            shiftMatches
        }
    
    override suspend fun resetToDefaults(): Result<Unit> = 
        shiftConfigRepository.resetToDefaults().also { result ->
            if (result.isSuccess) {
                // SINGLETON OPTIMIZATION: Clear all caches when resetting to defaults
                invalidateAllCaches()
            }
        }
    
    override suspend fun hasValidConfig(): Result<Boolean> =
        shiftConfigRepository.hasValidConfig()
}
