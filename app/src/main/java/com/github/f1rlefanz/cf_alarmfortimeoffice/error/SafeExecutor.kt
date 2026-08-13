package com.github.f1rlefanz.cf_alarmfortimeoffice.error

/**
 * Safe execution utilities for error handling
 * 
 * Provides a single, consistent way to execute suspend functions with
 * automatic error handling and Result wrapping.
 */
object SafeExecutor {
    
    /**
     * Execute a suspend function safely with error handling.
     * 
     * Wraps the block execution in a try-catch and converts any exception
     * to an AppError, returning a Result for clean error propagation.
     * 
     * @param context Optional context string for error logging/debugging
     * @param block The suspend function to execute
     * @return Result.success with the block's return value, or Result.failure with AppError
     */
    suspend inline fun <T> safeExecute(
        context: String = "",
        crossinline block: suspend () -> T
    ): Result<T> = try {
        Result.success(block())
    } catch (e: kotlinx.coroutines.CancellationException) {
        // WEITERWERFEN, nicht in einen AppError verwandeln.
        //
        // Eine Cancellation ist kein Fehler des Aufrufs, sondern die Ansage, dass die umgebende
        // Coroutine beendet wird - sie MUSS die Aufrufkette hochlaufen (und `Result.failure` ist
        // keine Aufrufkette). Als AppError verpackt verliert sie ihre Identitaet, und damit lief
        // der ausdrueckliche `catch (e: CancellationException) { throw e }` der
        // Delta-Sync-Schleife ins Leere: `AlarmUseCase.scheduleSystemAlarm()` ist ueber
        // `safeExecute` gewrappt, die Cancellation kam dort also als gewoehnliches
        // `Result.failure` an, wurde als Fehler EINES Events verbucht - und die Schleife lief stur
        // ueber alle restlichen Events weiter, ohne einen einzigen zu re-armieren, waehrend die
        // Abschlusszeile "complete" meldete. Genau die Verwechslung, die CLAUDE.md fuer den
        // Delta-Sync festhaelt ("wird die Cancellation als Event-Fehler verbucht, meldet die
        // Abschlusszeile complete mit unvollstaendiger Liste").
        //
        // Aufrufer, die bisher jeden Fehlschlag als Result erwarteten, sehen dadurch bei einer
        // ABGEBROCHENEN Coroutine eine Exception - das ist richtig: ihr eigener Scope ist in
        // diesem Moment ohnehin nicht mehr am Leben.
        throw e
    } catch (e: Exception) {
        val appError = ErrorHandler.handleError(e, context)
        Result.failure(appError)
    }
}
