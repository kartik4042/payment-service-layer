package com.payment.orchestration.circuitbreaker

import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * Circuit Breaker Configuration
 * 
 * Implements circuit breaker pattern to isolate failing providers and prevent cascading failures.
 * 
 * Configuration:
 * - Failure rate threshold: 50% (opens circuit if >50% of requests fail)
 * - Sliding window size: 100 requests
 * - Wait duration in open state: 30 seconds
 * - Permitted calls in half-open state: 10
 * - Slow call duration threshold: 5 seconds
 * - Slow call rate threshold: 50%
 * 
 * State Transitions:
 * - CLOSED → OPEN: When failure rate > 50% in sliding window
 * - OPEN → HALF_OPEN: After 30 seconds wait duration
 * - HALF_OPEN → CLOSED: When 10 test calls succeed
 * - HALF_OPEN → OPEN: When test calls fail
 * 
 * @see io.github.resilience4j.circuitbreaker.CircuitBreaker
 */
@Configuration
class CircuitBreakerConfig {

    /**
     * Create Circuit Breaker Registry with custom configuration
     * 
     * The registry manages circuit breakers for all payment providers.
     * Each provider gets its own circuit breaker instance.
     */
    @Bean
    fun circuitBreakerRegistry(): CircuitBreakerRegistry {
        val config = CircuitBreakerConfig.custom()
            // Open circuit when failure rate exceeds 50%
            .failureRateThreshold(50.0f)
            
            // Use sliding window of 100 requests to calculate failure rate
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(100)
            
            // Minimum number of calls before circuit breaker can calculate error rate
            .minimumNumberOfCalls(10)
            
            // Wait 30 seconds before transitioning from OPEN to HALF_OPEN
            .waitDurationInOpenState(Duration.ofSeconds(30))
            
            // Allow 10 test calls in HALF_OPEN state
            .permittedNumberOfCallsInHalfOpenState(10)
            
            // Consider calls slower than 5 seconds as failures
            .slowCallDurationThreshold(Duration.ofSeconds(5))
            .slowCallRateThreshold(50.0f)
            
            // Automatically transition from OPEN to HALF_OPEN
            .automaticTransitionFromOpenToHalfOpenEnabled(true)
            
            // Record these exceptions as failures
            .recordExceptions(
                ProviderTimeoutException::class.java,
                ProviderUnavailableException::class.java,
                ProviderException::class.java
            )
            
            // Ignore these exceptions (don't count as failures)
            .ignoreExceptions(
                PaymentDeclinedException::class.java,
                InvalidRequestException::class.java,
                ValidationException::class.java
            )
            
            .build()
        
        return CircuitBreakerRegistry.of(config)
    }

    /**
     * Create circuit breaker for Provider A
     */
    @Bean
    fun providerACircuitBreaker(registry: CircuitBreakerRegistry): CircuitBreaker {
        return registry.circuitBreaker("PROVIDER_A")
    }

    /**
     * Create circuit breaker for Provider B
     */
    @Bean
    fun providerBCircuitBreaker(registry: CircuitBreakerRegistry): CircuitBreaker {
        return registry.circuitBreaker("PROVIDER_B")
    }
}

/**
 * Exception thrown when provider times out
 */
class ProviderTimeoutException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * Exception thrown when provider is unavailable
 */
class ProviderUnavailableException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * Generic provider exception
 */
class ProviderException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * Exception thrown when payment is declined (not a circuit breaker failure)
 */
class PaymentDeclinedException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * Exception thrown for invalid requests (not a circuit breaker failure)
 */
class InvalidRequestException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * Exception thrown for validation errors (not a circuit breaker failure)
 */
class ValidationException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)


