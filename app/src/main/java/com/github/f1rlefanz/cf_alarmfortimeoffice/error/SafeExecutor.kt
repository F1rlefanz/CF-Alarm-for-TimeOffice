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
    } catch (e: Exception) {
        val appError = ErrorHandler.handleError(e, context)
        Result.failure(appError)
    }
}
