package com.github.f1rlefanz.cf_alarmfortimeoffice.error

/** Fuehrt suspend-Bloecke aus und liefert Fehler als [Result] mit [AppError]. */
object SafeExecutor {
    
    /**
     * Fuehrt [block] aus; jede Exception ausser CancellationException wird ueber
     * [ErrorHandler.handleError] zu `Result.failure(AppError)`. [context] erscheint im Fehlerlog.
     */
    suspend inline fun <T> safeExecute(
        context: String = "",
        crossinline block: suspend () -> T
    ): Result<T> = try {
        Result.success(block())
    } catch (e: kotlinx.coroutines.CancellationException) {
        // WEITERWERFEN, nicht in einen AppError verwandeln: als Result.failure verbuchte der
        // Delta-Sync sie als Event-Fehler. Hergang: Skill cfalarm-persistenz-und-auth, fehlerbehandlung.md.
        throw e
    } catch (e: Exception) {
        val appError = ErrorHandler.handleError(e, context)
        Result.failure(appError)
    }
}
