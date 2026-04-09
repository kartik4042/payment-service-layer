package com.payment.orchestration.circuitbreaker

import com.payment.orchestration.domain.model.Provider
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.kotlin.circuitbreaker.executeSuspendFunction
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Circuit Breaker Service
 * 
 * Wraps provider calls with circuit breaker pattern to prevent cascading failures.
 * 
 * Features:
 * - Automatic failure detection and circuit opening
 * - Configurable failure thresholds
 * - Automatic recovery with half-open state
 * - Provider-specific circuit breakers
 * - Metrics and monitoring support
 * 
 * Usage:
 * ```kotlin
 * val result = circuitBreakerService.executeWithCircuitBreaker(Provider.PROVIDER_A) {
 *     providerA.processPayment(request)
 * }
 * ```
 * 
 * @property circuitBreakerRegistry Registry managing all circuit breakers
 */
@Service
class CircuitBreakerService(
    private val circuitBreakerRegistry: CircuitBreakerRegistry
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Execute a provider call with circuit breaker protection
     * 
     * @param provider The payment provider
     * @param block The provider call to execute
     * @return Result of the provider call
     * @throws CircuitBreakerOpenException if circuit is open
     * @throws ProviderException if provider call fails
     */
    fun <T> executeWithCircuitBreaker(
        provider: Provider,
        block: () -> T
    ): T {
        val circuitBreaker = getCircuitBreaker(provider)
        
        // Check circuit state before executing
        if (circuitBreaker.state == CircuitBreaker.State.OPEN) {
            logger.warn("Circuit breaker is OPEN for provider: ${provider.name}")
            throw CircuitBreakerOpenException(
                "Circuit breaker is open for provider ${provider.name}. " +
                "Provider is temporarily unavailable due to high failure rate."
            )
        }
        
        return try {
            // Execute with circuit breaker
            val result = circuitBreaker.executeSupplier(block)
            
            // Log successful call
            if (circuitBreaker.state == CircuitBreaker.State.HALF_OPEN) {
                logger.info("Successful call in HALF_OPEN state for provider: ${provider.name}")
            }
            
            result
        } catch (e: Exception) {
            logger.error("Provider call failed for ${provider.name}: ${e.message}", e)
            
            // Log circuit state change
            val newState = circuitBreaker.state
            if (newState == CircuitBreaker.State.OPEN) {
                logger.error("Circuit breaker transitioned to OPEN for provider: ${provider.name}")
            }
            
            throw e
        }
    }

    /**
     * Execute a suspending provider call with circuit breaker protection
     * 
     * @param provider The payment provider
     * @param block The suspending provider call to execute
     * @return Result of the provider call
     */
    suspend fun <T> executeWithCircuitBreakerSuspend(
        provider: Provider,
        block: suspend () -> T
    ): T {
        val circuitBreaker = getCircuitBreaker(provider)
        
        if (circuitBreaker.state == CircuitBreaker.State.OPEN) {
            logger.warn("Circuit breaker is OPEN for provider: ${provider.name}")
            throw CircuitBreakerOpenException(
                "Circuit breaker is open for provider ${provider.name}"
            )
        }
        
        return circuitBreaker.executeSuspendFunction(block)
    }

    /**
     * Get circuit breaker for a specific provider
     * 
     * @param provider The payment provider
     * @return Circuit breaker instance
     */
    fun getCircuitBreaker(provider: Provider): CircuitBreaker {
        return circuitBreakerRegistry.circuitBreaker(provider.name)
    }

    /**
     * Get current state of circuit breaker for a provider
     * 
     * @param provider The payment provider
     * @return Current circuit breaker state
     */
    fun getCircuitBreakerState(provider: Provider): CircuitBreaker.State {
        return getCircuitBreaker(provider).state
    }

    /**
     * Check if circuit breaker is open for a provider
     * 
     * @param provider The payment provider
     * @return true if circuit is open, false otherwise
     */
    fun isCircuitOpen(provider: Provider): Boolean {
        return getCircuitBreaker(provider).state == CircuitBreaker.State.OPEN
    }

    /**
     * Check if circuit breaker is closed for a provider
     * 
     * @param provider The payment provider
     * @return true if circuit is closed, false otherwise
     */
    fun isCircuitClosed(provider: Provider): Boolean {
        return getCircuitBreaker(provider).state == CircuitBreaker.State.CLOSED
    }

    /**
     * Check if circuit breaker is half-open for a provider
     * 
     * @param provider The payment provider
     * @return true if circuit is half-open, false otherwise
     */
    fun isCircuitHalfOpen(provider: Provider): Boolean {
        return getCircuitBreaker(provider).state == CircuitBreaker.State.HALF_OPEN
    }

    /**
     * Manually transition circuit breaker to open state
     * 
     * Use this for manual intervention during incidents.
     * 
     * @param provider The payment provider
     */
    fun openCircuit(provider: Provider) {
        logger.warn("Manually opening circuit breaker for provider: ${provider.name}")
        getCircuitBreaker(provider).transitionToOpenState()
    }

    /**
     * Manually transition circuit breaker to closed state
     * 
     * Use this to force recovery after verifying provider health.
     * 
     * @param provider The payment provider
     */
    fun closeCircuit(provider: Provider) {
        logger.info("Manually closing circuit breaker for provider: ${provider.name}")
        getCircuitBreaker(provider).transitionToClosedState()
    }

    /**
     * Reset circuit breaker to initial state
     * 
     * Clears all metrics and transitions to closed state.
     * 
     * @param provider The payment provider
     */
    fun resetCircuit(provider: Provider) {
        logger.info("Resetting circuit breaker for provider: ${provider.name}")
        getCircuitBreaker(provider).reset()
    }

    /**
     * Get circuit breaker metrics for a provider
     * 
     * @param provider The payment provider
     * @return Circuit breaker metrics
     */
    fun getMetrics(provider: Provider): CircuitBreakerMetrics {
        val circuitBreaker = getCircuitBreaker(provider)
        val metrics = circuitBreaker.metrics
        
        return CircuitBreakerMetrics(
            provider = provider,
            state = circuitBreaker.state,
            failureRate = metrics.failureRate,
            slowCallRate = metrics.slowCallRate,
            numberOfSuccessfulCalls = metrics.numberOfSuccessfulCalls,
            numberOfFailedCalls = metrics.numberOfFailedCalls,
            numberOfSlowCalls = metrics.numberOfSlowCalls,
            numberOfNotPermittedCalls = metrics.numberOfNotPermittedCalls
        )
    }

    /**
     * Get metrics for all providers
     * 
     * @return List of circuit breaker metrics for all providers
     */
    fun getAllMetrics(): List<CircuitBreakerMetrics> {
        return Provider.values().map { getMetrics(it) }
    }
}

/**
 * Exception thrown when circuit breaker is open
 */
class CircuitBreakerOpenException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * Circuit Breaker Metrics
 * 
 * Contains metrics and state information for a circuit breaker.
 */
data class CircuitBreakerMetrics(
    val provider: Provider,
    val state: CircuitBreaker.State,
    val failureRate: Float,
    val slowCallRate: Float,
    val numberOfSuccessfulCalls: Int,
    val numberOfFailedCalls: Int,
    val numberOfSlowCalls: Int,
    val numberOfNotPermittedCalls: Long
) {
    /**
     * Check if circuit breaker is healthy
     * 
     * A circuit breaker is considered healthy if:
     * - State is CLOSED
     * - Failure rate < 25%
     * - Slow call rate < 25%
     */
    fun isHealthy(): Boolean {
        return state == CircuitBreaker.State.CLOSED &&
               failureRate < 25.0f &&
               slowCallRate < 25.0f
    }

    /**
     * Check if circuit breaker is degraded
     * 
     * A circuit breaker is considered degraded if:
     * - State is HALF_OPEN, or
     * - Failure rate between 25% and 50%, or
     * - Slow call rate between 25% and 50%
     */
    fun isDegraded(): Boolean {
        return state == CircuitBreaker.State.HALF_OPEN ||
               (failureRate >= 25.0f && failureRate < 50.0f) ||
               (slowCallRate >= 25.0f && slowCallRate < 50.0f)
    }

    /**
     * Check if circuit breaker is unhealthy
     * 
     * A circuit breaker is considered unhealthy if:
     * - State is OPEN, or
     * - Failure rate >= 50%, or
     * - Slow call rate >= 50%
     */
    fun isUnhealthy(): Boolean {
        return state == CircuitBreaker.State.OPEN ||
               failureRate >= 50.0f ||
               slowCallRate >= 50.0f
    }
}


