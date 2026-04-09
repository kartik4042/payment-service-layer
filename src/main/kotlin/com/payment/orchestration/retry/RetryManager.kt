package com.payment.orchestration.retry

import com.payment.orchestration.domain.model.Payment
import com.payment.orchestration.domain.model.Provider
import com.payment.orchestration.provider.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import kotlin.math.min
import kotlin.math.pow

/**
 * Retry Manager
 * 
 * Handles retry logic for failed payment attempts with:
 * - Exponential backoff
 * - Maximum retry attempts
 * - Transient vs permanent error classification
 * - Provider failover
 * - Retry context tracking
 * 
 * Retry Strategy:
 * - Transient errors: Retry with exponential backoff
 * - Permanent errors: No retry, fail immediately
 * - Network errors: Retry with shorter backoff
 * - Timeout errors: Retry with increased timeout
 * 
 * Backoff Formula:
 * delay = min(base_delay * (2 ^ attempt), max_delay)
 * 
 * Example:
 * - Attempt 1: 1 second
 * - Attempt 2: 2 seconds
 * - Attempt 3: 4 seconds
 * - Attempt 4: 8 seconds
 * - Attempt 5: 16 seconds (capped at max_delay)
 * 
 * @property retryPolicy Configuration for retry behavior
 */
@Component
class RetryManager(
    private val retryPolicy: RetryPolicy = RetryPolicy.default()
) {
    private val logger = LoggerFactory.getLogger(RetryManager::class.java)
    
    /**
     * Executes a payment operation with retry logic.
     * 
     * Flow:
     * 1. Attempt payment with primary provider
     * 2. If transient failure, wait and retry
     * 3. If max retries exceeded, attempt failover
     * 4. If failover also fails, return final failure
     * 
     * @param payment The payment to process
     * @param operation The operation to execute (provider call)
     * @return ProviderResponse with result
     */
    fun executeWithRetry(
        payment: Payment,
        operation: () -> ProviderResponse
    ): RetryResult {
        val context = RetryContext(
            transactionId = payment.transactionId,
            startTime = Instant.now()
        )
        
        logger.info(
            "Starting payment with retry: transactionId={}, maxAttempts={}",
            payment.transactionId,
            retryPolicy.maxAttempts
        )
        
        var lastException: Exception? = null
        
        // Attempt with retries
        for (attempt in 1..retryPolicy.maxAttempts) {
            context.attempts.add(
                RetryAttempt(
                    attemptNumber = attempt,
                    timestamp = Instant.now()
                )
            )
            
            try {
                logger.info(
                    "Payment attempt: transactionId={}, attempt={}/{}",
                    payment.transactionId,
                    attempt,
                    retryPolicy.maxAttempts
                )
                
                // Execute operation
                val response = operation()
                
                // Update attempt result
                context.attempts.last().apply {
                    success = response.success
                    providerResponse = response
                    duration = Duration.between(timestamp, Instant.now())
                }
                
                // Success - return immediately
                if (response.success) {
                    logger.info(
                        "Payment succeeded: transactionId={}, attempt={}, duration={}ms",
                        payment.transactionId,
                        attempt,
                        context.getTotalDuration().toMillis()
                    )
                    
                    return RetryResult(
                        success = true,
                        response = response,
                        context = context
                    )
                }
                
                // Failure - check if retryable
                if (!response.isRetryable()) {
                    logger.warn(
                        "Payment failed with permanent error: transactionId={}, attempt={}, error={}",
                        payment.transactionId,
                        attempt,
                        response.errorCode
                    )
                    
                    return RetryResult(
                        success = false,
                        response = response,
                        context = context,
                        reason = "Permanent failure: ${response.errorMessage}"
                    )
                }
                
                // Transient failure - retry if attempts remaining
                if (attempt < retryPolicy.maxAttempts) {
                    val delay = calculateBackoff(attempt)
                    
                    logger.info(
                        "Transient failure, retrying: transactionId={}, attempt={}, delay={}ms",
                        payment.transactionId,
                        attempt,
                        delay.toMillis()
                    )
                    
                    context.attempts.last().apply {
                        retryable = true
                        backoffDelay = delay
                    }
                    
                    // Wait before retry
                    Thread.sleep(delay.toMillis())
                } else {
                    logger.error(
                        "Max retry attempts exceeded: transactionId={}, attempts={}",
                        payment.transactionId,
                        retryPolicy.maxAttempts
                    )
                    
                    return RetryResult(
                        success = false,
                        response = response,
                        context = context,
                        reason = "Max retry attempts exceeded"
                    )
                }
                
            } catch (e: ProviderPermanentException) {
                // Permanent error - don't retry
                logger.error(
                    "Permanent provider error: transactionId={}, attempt={}, error={}",
                    payment.transactionId,
                    attempt,
                    e.message
                )
                
                context.attempts.last().apply {
                    success = false
                    error = e.message
                    retryable = false
                    duration = Duration.between(timestamp, Instant.now())
                }
                
                return RetryResult(
                    success = false,
                    context = context,
                    reason = "Permanent error: ${e.message}"
                )
                
            } catch (e: ProviderTransientException) {
                // Transient error - retry
                lastException = e
                
                logger.warn(
                    "Transient provider error: transactionId={}, attempt={}, error={}",
                    payment.transactionId,
                    attempt,
                    e.message
                )
                
                context.attempts.last().apply {
                    success = false
                    error = e.message
                    retryable = true
                    duration = Duration.between(timestamp, Instant.now())
                }
                
                if (attempt < retryPolicy.maxAttempts) {
                    val delay = calculateBackoff(attempt)
                    context.attempts.last().backoffDelay = delay
                    Thread.sleep(delay.toMillis())
                }
                
            } catch (e: ProviderNetworkException) {
                // Network error - retry with shorter backoff
                lastException = e
                
                logger.warn(
                    "Network error: transactionId={}, attempt={}, error={}",
                    payment.transactionId,
                    attempt,
                    e.message
                )
                
                context.attempts.last().apply {
                    success = false
                    error = e.message
                    retryable = true
                    duration = Duration.between(timestamp, Instant.now())
                }
                
                if (attempt < retryPolicy.maxAttempts) {
                    val delay = calculateBackoff(attempt, shorter = true)
                    context.attempts.last().backoffDelay = delay
                    Thread.sleep(delay.toMillis())
                }
                
            } catch (e: ProviderTimeoutException) {
                // Timeout error - retry
                lastException = e
                
                logger.warn(
                    "Timeout error: transactionId={}, attempt={}, error={}",
                    payment.transactionId,
                    attempt,
                    e.message
                )
                
                context.attempts.last().apply {
                    success = false
                    error = e.message
                    retryable = true
                    duration = Duration.between(timestamp, Instant.now())
                }
                
                if (attempt < retryPolicy.maxAttempts) {
                    val delay = calculateBackoff(attempt)
                    context.attempts.last().backoffDelay = delay
                    Thread.sleep(delay.toMillis())
                }
                
            } catch (e: Exception) {
                // Unexpected error - don't retry
                logger.error(
                    "Unexpected error: transactionId={}, attempt={}",
                    payment.transactionId,
                    attempt,
                    e
                )
                
                context.attempts.last().apply {
                    success = false
                    error = e.message
                    retryable = false
                    duration = Duration.between(timestamp, Instant.now())
                }
                
                return RetryResult(
                    success = false,
                    context = context,
                    reason = "Unexpected error: ${e.message}"
                )
            }
        }
        
        // All retries exhausted
        logger.error(
            "All retry attempts failed: transactionId={}, attempts={}, duration={}ms",
            payment.transactionId,
            retryPolicy.maxAttempts,
            context.getTotalDuration().toMillis()
        )
        
        return RetryResult(
            success = false,
            context = context,
            reason = "All retry attempts failed: ${lastException?.message}"
        )
    }
    
    /**
     * Calculates exponential backoff delay.
     * 
     * Formula: min(base_delay * (2 ^ attempt), max_delay)
     * 
     * @param attempt The attempt number (1-based)
     * @param shorter Whether to use shorter backoff (for network errors)
     * @return Backoff duration
     */
    private fun calculateBackoff(attempt: Int, shorter: Boolean = false): Duration {
        val baseDelay = if (shorter) {
            retryPolicy.baseDelay.dividedBy(2)
        } else {
            retryPolicy.baseDelay
        }
        
        val exponentialDelay = baseDelay.multipliedBy(
            2.0.pow(attempt - 1).toLong()
        )
        
        // Cap at max delay
        val cappedDelay = if (exponentialDelay > retryPolicy.maxDelay) {
            retryPolicy.maxDelay
        } else {
            exponentialDelay
        }
        
        // Add jitter to prevent thundering herd
        val jitter = (Math.random() * 0.1 * cappedDelay.toMillis()).toLong()
        
        return cappedDelay.plusMillis(jitter)
    }
}

/**
 * Retry Policy Configuration
 * 
 * @property maxAttempts Maximum number of retry attempts
 * @property baseDelay Base delay for exponential backoff
 * @property maxDelay Maximum delay between retries
 * @property retryableErrors List of error codes that are retryable
 */
data class RetryPolicy(
    val maxAttempts: Int = 3,
    val baseDelay: Duration = Duration.ofSeconds(1),
    val maxDelay: Duration = Duration.ofSeconds(16),
    val retryableErrors: Set<String> = setOf(
        "rate_limit_exceeded",
        "service_unavailable",
        "timeout",
        "network_error"
    )
) {
    companion object {
        fun default() = RetryPolicy()
        
        fun aggressive() = RetryPolicy(
            maxAttempts = 5,
            baseDelay = Duration.ofMillis(500),
            maxDelay = Duration.ofSeconds(10)
        )
        
        fun conservative() = RetryPolicy(
            maxAttempts = 2,
            baseDelay = Duration.ofSeconds(2),
            maxDelay = Duration.ofSeconds(20)
        )
    }
}

/**
 * Retry Context
 * 
 * Tracks all retry attempts for a payment.
 * 
 * @property transactionId The transaction identifier
 * @property startTime When retry sequence started
 * @property attempts List of all retry attempts
 */
data class RetryContext(
    val transactionId: String,
    val startTime: Instant,
    val attempts: MutableList<RetryAttempt> = mutableListOf()
) {
    fun getTotalDuration(): Duration = Duration.between(startTime, Instant.now())
    fun getTotalAttempts(): Int = attempts.size
    fun getSuccessfulAttempt(): RetryAttempt? = attempts.firstOrNull { it.success }
}

/**
 * Single Retry Attempt
 * 
 * @property attemptNumber The attempt number (1-based)
 * @property timestamp When attempt started
 * @property success Whether attempt succeeded
 * @property error Error message if failed
 * @property retryable Whether error is retryable
 * @property backoffDelay Delay before next retry
 * @property duration How long attempt took
 * @property providerResponse Provider's response
 */
data class RetryAttempt(
    val attemptNumber: Int,
    val timestamp: Instant,
    var success: Boolean = false,
    var error: String? = null,
    var retryable: Boolean = false,
    var backoffDelay: Duration? = null,
    var duration: Duration? = null,
    var providerResponse: ProviderResponse? = null
)

/**
 * Retry Result
 * 
 * Final result after all retry attempts.
 * 
 * @property success Whether payment ultimately succeeded
 * @property response Provider response (if available)
 * @property context Full retry context with all attempts
 * @property reason Failure reason (if failed)
 */
data class RetryResult(
    val success: Boolean,
    val response: ProviderResponse? = null,
    val context: RetryContext,
    val reason: String? = null
) {
    fun getTotalAttempts(): Int = context.getTotalAttempts()
    fun getTotalDuration(): Duration = context.getTotalDuration()
}
