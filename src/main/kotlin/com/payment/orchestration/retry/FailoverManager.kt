package com.payment.orchestration.retry

import com.payment.orchestration.domain.model.Payment
import com.payment.orchestration.domain.model.PaymentMethod
import com.payment.orchestration.provider.PaymentProvider
import com.payment.orchestration.provider.ProviderResponse
import com.payment.orchestration.routing.RoutingEngine
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Failover Manager
 * 
 * Handles provider failover when primary provider fails.
 * 
 * Failover Strategy:
 * 1. Primary provider fails after retries
 * 2. Select fallback provider via routing engine
 * 3. Attempt payment with fallback provider
 * 4. If fallback also fails, return final failure
 * 
 * Failover Scenarios:
 * - Provider downtime
 * - Circuit breaker open
 * - Repeated transient failures
 * - Provider rate limiting
 * 
 * Failover is NOT attempted for:
 * - Permanent failures (invalid card, insufficient funds)
 * - Business rule violations
 * - Validation errors
 * 
 * @property routingEngine For selecting fallback providers
 * @property retryManager For retrying with fallback provider
 */
@Component
class FailoverManager(
    private val routingEngine: RoutingEngine,
    private val retryManager: RetryManager
) {
    private val logger = LoggerFactory.getLogger(FailoverManager::class.java)
    
    /**
     * Attempts failover to alternative provider.
     * 
     * Flow:
     * 1. Check if failover is appropriate
     * 2. Get available fallback providers
     * 3. Attempt payment with each fallback
     * 4. Return first success or final failure
     * 
     * @param payment The payment to process
     * @param primaryProvider The failed primary provider
     * @param primaryResult The result from primary provider
     * @return FailoverResult with outcome
     */
    fun attemptFailover(
        payment: Payment,
        primaryProvider: PaymentProvider,
        primaryResult: RetryResult
    ): FailoverResult {
        val startTime = Instant.now()
        
        logger.info(
            "Attempting failover: transactionId={}, primaryProvider={}, reason={}",
            payment.transactionId,
            primaryProvider.getProviderId(),
            primaryResult.reason
        )
        
        // Check if failover is appropriate
        if (!shouldAttemptFailover(primaryResult)) {
            logger.info(
                "Failover not appropriate: transactionId={}, reason={}",
                payment.transactionId,
                primaryResult.reason
            )
            
            return FailoverResult(
                success = false,
                failoverAttempted = false,
                primaryProvider = primaryProvider.getProviderId(),
                primaryResult = primaryResult,
                reason = "Failover not appropriate for this failure type"
            )
        }
        
        // Get available fallback providers
        val availableProviders = routingEngine.getAvailableProviders(
            payment.transaction.paymentMethod
        )
        
        // Remove primary provider from list
        val fallbackProviders = availableProviders.filter { 
            it.getProviderId() != primaryProvider.getProviderId() 
        }
        
        if (fallbackProviders.isEmpty()) {
            logger.warn(
                "No fallback providers available: transactionId={}, method={}",
                payment.transactionId,
                payment.transaction.paymentMethod
            )
            
            return FailoverResult(
                success = false,
                failoverAttempted = false,
                primaryProvider = primaryProvider.getProviderId(),
                primaryResult = primaryResult,
                reason = "No fallback providers available"
            )
        }
        
        logger.info(
            "Found fallback providers: transactionId={}, count={}",
            payment.transactionId,
            fallbackProviders.size
        )
        
        // Attempt payment with each fallback provider
        for (fallbackProvider in fallbackProviders) {
            logger.info(
                "Attempting failover to provider: transactionId={}, provider={}",
                payment.transactionId,
                fallbackProvider.getProviderId()
            )
            
            try {
                // Attempt payment with fallback provider
                val fallbackResult = retryManager.executeWithRetry(payment) {
                    fallbackProvider.processPayment(payment)
                }
                
                if (fallbackResult.success) {
                    logger.info(
                        "Failover succeeded: transactionId={}, fallbackProvider={}, attempts={}",
                        payment.transactionId,
                        fallbackProvider.getProviderId(),
                        fallbackResult.getTotalAttempts()
                    )
                    
                    return FailoverResult(
                        success = true,
                        failoverAttempted = true,
                        primaryProvider = primaryProvider.getProviderId(),
                        primaryResult = primaryResult,
                        fallbackProvider = fallbackProvider.getProviderId(),
                        fallbackResult = fallbackResult,
                        fallbackResponse = fallbackResult.response
                    )
                } else {
                    logger.warn(
                        "Failover provider also failed: transactionId={}, provider={}, reason={}",
                        payment.transactionId,
                        fallbackProvider.getProviderId(),
                        fallbackResult.reason
                    )
                    
                    // If permanent failure, don't try other providers
                    if (!isTransientFailure(fallbackResult)) {
                        logger.info(
                            "Permanent failure from fallback, stopping: transactionId={}",
                            payment.transactionId
                        )
                        
                        return FailoverResult(
                            success = false,
                            failoverAttempted = true,
                            primaryProvider = primaryProvider.getProviderId(),
                            primaryResult = primaryResult,
                            fallbackProvider = fallbackProvider.getProviderId(),
                            fallbackResult = fallbackResult,
                            reason = "Permanent failure from fallback provider"
                        )
                    }
                    
                    // Continue to next fallback provider
                }
                
            } catch (e: Exception) {
                logger.error(
                    "Error during failover: transactionId={}, provider={}",
                    payment.transactionId,
                    fallbackProvider.getProviderId(),
                    e
                )
                // Continue to next fallback provider
            }
        }
        
        // All fallback providers failed
        logger.error(
            "All failover attempts failed: transactionId={}, fallbackCount={}",
            payment.transactionId,
            fallbackProviders.size
        )
        
        return FailoverResult(
            success = false,
            failoverAttempted = true,
            primaryProvider = primaryProvider.getProviderId(),
            primaryResult = primaryResult,
            reason = "All failover providers failed"
        )
    }
    
    /**
     * Determines if failover should be attempted.
     * 
     * Failover is appropriate for:
     * - Transient failures after max retries
     * - Provider unavailability
     * - Rate limiting
     * - Timeouts
     * 
     * Failover is NOT appropriate for:
     * - Permanent failures (invalid card, insufficient funds)
     * - Validation errors
     * - Business rule violations
     * 
     * @param primaryResult Result from primary provider
     * @return true if failover should be attempted
     */
    private fun shouldAttemptFailover(primaryResult: RetryResult): Boolean {
        // If succeeded, no failover needed
        if (primaryResult.success) {
            return false
        }
        
        // Check if failure is transient
        if (!isTransientFailure(primaryResult)) {
            logger.debug("Permanent failure, no failover: reason={}", primaryResult.reason)
            return false
        }
        
        // Check if max retries were attempted
        if (primaryResult.getTotalAttempts() < 2) {
            logger.debug("Insufficient retry attempts, no failover: attempts={}", primaryResult.getTotalAttempts())
            return false
        }
        
        return true
    }
    
    /**
     * Checks if a failure is transient (retryable).
     * 
     * @param result The retry result
     * @return true if transient, false if permanent
     */
    private fun isTransientFailure(result: RetryResult): Boolean {
        // Check response if available
        result.response?.let { response ->
            return response.isRetryable()
        }
        
        // Check reason string for transient indicators
        val reason = result.reason?.lowercase() ?: ""
        
        val transientIndicators = listOf(
            "timeout",
            "network",
            "unavailable",
            "rate limit",
            "service error",
            "server error"
        )
        
        return transientIndicators.any { indicator ->
            reason.contains(indicator)
        }
    }
    
    /**
     * Gets failover statistics for monitoring.
     * 
     * @return Failover statistics
     */
    fun getFailoverStats(): FailoverStats {
        // In production, this would return actual metrics from a metrics store
        return FailoverStats(
            totalFailovers = 0,
            successfulFailovers = 0,
            failedFailovers = 0,
            avgFailoverDuration = 0L
        )
    }
}

/**
 * Failover Result
 * 
 * Result of failover attempt.
 * 
 * @property success Whether payment ultimately succeeded
 * @property failoverAttempted Whether failover was attempted
 * @property primaryProvider The primary provider that failed
 * @property primaryResult Result from primary provider
 * @property fallbackProvider The fallback provider used (if any)
 * @property fallbackResult Result from fallback provider (if any)
 * @property fallbackResponse Provider response from fallback (if successful)
 * @property reason Failure reason (if failed)
 */
data class FailoverResult(
    val success: Boolean,
    val failoverAttempted: Boolean,
    val primaryProvider: com.payment.orchestration.domain.model.Provider,
    val primaryResult: RetryResult,
    val fallbackProvider: com.payment.orchestration.domain.model.Provider? = null,
    val fallbackResult: RetryResult? = null,
    val fallbackResponse: ProviderResponse? = null,
    val reason: String? = null
) {
    /**
     * Gets the final provider used (fallback if succeeded, otherwise primary).
     */
    fun getFinalProvider(): com.payment.orchestration.domain.model.Provider {
        return if (success && fallbackProvider != null) {
            fallbackProvider
        } else {
            primaryProvider
        }
    }
    
    /**
     * Gets the final response (from fallback if succeeded, otherwise from primary).
     */
    fun getFinalResponse(): ProviderResponse? {
        return if (success && fallbackResponse != null) {
            fallbackResponse
        } else {
            primaryResult.response
        }
    }
    
    /**
     * Gets total attempts across primary and fallback.
     */
    fun getTotalAttempts(): Int {
        val primaryAttempts = primaryResult.getTotalAttempts()
        val fallbackAttempts = fallbackResult?.getTotalAttempts() ?: 0
        return primaryAttempts + fallbackAttempts
    }
}

/**
 * Failover Statistics
 * 
 * @property totalFailovers Total number of failover attempts
 * @property successfulFailovers Number of successful failovers
 * @property failedFailovers Number of failed failovers
 * @property avgFailoverDuration Average failover duration in milliseconds
 */
data class FailoverStats(
    val totalFailovers: Long,
    val successfulFailovers: Long,
    val failedFailovers: Long,
    val avgFailoverDuration: Long
) {
    fun getSuccessRate(): Double {
        return if (totalFailovers > 0) {
            successfulFailovers.toDouble() / totalFailovers.toDouble()
        } else {
            0.0
        }
    }
}


