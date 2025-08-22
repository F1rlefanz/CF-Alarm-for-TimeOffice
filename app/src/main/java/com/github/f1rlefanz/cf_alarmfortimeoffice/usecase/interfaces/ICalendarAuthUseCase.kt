package com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AndroidCalendar

/**
 * Interface für Calendar Authentication UseCase Operations
 * 
 * TESTING IMPROVEMENT: Interface ermöglicht Mock-Implementierungen
 * - Dependency Inversion: ViewModel abhängig von Abstraktion
 * - Testbarkeit: ViewModel kann mit Mock-UseCase getestet werden
 * - Business Logic Separation: Kapselt Auth-spezifische Geschäftslogik
 */
interface ICalendarAuthUseCase {
    
    /**
     * Lädt verfügbare Kalender mit Authentifizierungs-Fallback
     * 
     * @return Result mit Liste der verfügbaren Kalender oder Fehler
     */
    suspend fun getAvailableCalendarsWithAuth(): Result<List<AndroidCalendar>>
    
    /**
     * Überprüft und erneuert bei Bedarf das Access Token
     * 
     * @return Result mit Boolean (true wenn Token gültig/erneuert) oder Fehler
     */
    suspend fun validateAndRefreshToken(): Result<Boolean>
    
    /**
     * Prüft ob der User authentifiziert ist und Kalender-Zugriff hat
     * 
     * @return Boolean - true wenn authentifiziert und berechtigt
     */
    suspend fun isCalendarAccessAvailable(): Boolean
    
    /**
     * Testet die Authentifizierung durch einen einfachen API-Aufruf
     * 
     * @return Result mit Boolean (true wenn Auth erfolgreich) oder Fehler
     */
    suspend fun testAuthentication(): Result<Boolean>
}
