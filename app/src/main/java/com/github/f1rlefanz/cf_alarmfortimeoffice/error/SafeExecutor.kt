package com.github.f1rlefanz.cf_alarmfortimeoffice.error

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf

/**
 * Safe execution utilities for error handling
 * 
 * PERFORMANCE OPTIMIZATIONS:
 * ✅ Cached ErrorHandler instance to reduce object creation
 * ✅ Reduced function call overhead
 * ✅ Optimized error handling flow
 */
object SafeExecutor {
    
    // PERFORMANCE: ErrorHandler ist jetzt ein Singleton-Object - kein Caching nötig
    
    /**
     * Execute a suspend function safely with error handling
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
    
    /**
     * Execute a suspend function that returns a nullable value
     */
    suspend inline fun <T> safeExecuteOrNull(
        context: String = "",
        crossinline block: suspend () -> T?
    ): T? = try {
        block()
    } catch (e: Exception) {
        ErrorHandler.handleError(e, context)
        null
    }
    
    /**
     * Execute a suspend function with a default value on error
     */
    suspend inline fun <T> safeExecuteOrDefault(
        default: T,
        context: String = "",
        crossinline block: suspend () -> T
    ): T = try {
        block()
    } catch (e: Exception) {
        ErrorHandler.handleError(e, context)
        default
    }
    
    /**
     * Execute a regular function safely
     */
    inline fun <T> safeCall(
        context: String = "",
        crossinline block: () -> T
    ): Result<T> = try {
        Result.success(block())
    } catch (e: Exception) {
        val appError = ErrorHandler.handleError(e, context)
        Result.failure(appError)
    }
    
    /**
     * PERFORMANCE: Helper methods to avoid ErrorHandler creation in extension functions
     */
    internal fun createAppError(throwable: Throwable, context: String): AppError {
        return ErrorHandler.handleError(throwable, context)
    }
    
    internal fun logError(throwable: Throwable, context: String) {
        ErrorHandler.handleError(throwable, context)
    }
}

/**
 * Extension function to safely collect from a Flow
 * PERFORMANCE: Uses cached ErrorHandler instance
 */
fun <T> Flow<T>.catchErrors(
    context: String = "",
    onError: ((AppError) -> Unit)? = null
): Flow<T> = this.catch { throwable ->
    val appError = SafeExecutor.createAppError(throwable, context)
    onError?.invoke(appError)
}

/**
 * Extension function to provide a default Flow on error
 * PERFORMANCE: Uses cached ErrorHandler instance
 */
fun <T> Flow<T>.catchWithDefault(
    default: T,
    context: String = ""
): Flow<T> = this.catch { throwable ->
    SafeExecutor.logError(throwable, context)
    emit(default)
}

/**
 * Extension function to handle Result with error callback
 */
inline fun <T> Result<T>.onError(
    crossinline action: (AppError) -> Unit
): Result<T> {
    if (isFailure) {
        val error = exceptionOrNull()
        if (error is AppError) {
            action(error)
        }
    }
    return this
}
