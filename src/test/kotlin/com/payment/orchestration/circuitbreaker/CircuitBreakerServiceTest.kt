package com.payment.orchestration.circuitbreaker

import com.payment.orchestration.domain.model.Provider
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

/**
 * Circuit Breaker Service Tests
 * 
 * Tests circuit breaker functionality including:
 * - State transitions (CLOSED → OPEN → HALF_OPEN → CLOSED)
 * - Failure rate threshold detection
 * - Automatic recovery
 * - Manual circuit control
 * - Metrics collection
 */
class CircuitBreakerServiceTest {

    private lateinit var circuitBreakerRegistry: CircuitBreakerRegistry
    private lateinit var circuitBreakerService: CircuitBreakerService

    @BeforeEach
    fun setup() {
        // Create circuit breaker registry with test configuration
        val config = CircuitBreakerConfig.custom()
            .failureRateThreshold(50.0f)
            .slidingWindowSize(10)
            .minimumNumberOfCalls(5)
            .waitDurationInOpenState(Duration.ofMillis(100))
            .permittedNumberOfCallsInHalfOpenState(3)
            .automaticTransitionFromOpenToHalfOpenEnabled(true)
            .recordExceptions(
                ProviderTimeoutException::class.java,
                ProviderUnavailableException::class.java,
                ProviderException::class.java
            )
            .ignoreExceptions(
                PaymentDeclinedException::class.java
            )
            .build()

        circuitBreakerRegistry = CircuitBreakerRegistry.of(config)
        circuitBreakerService = CircuitBreakerService(circuitBreakerRegistry)
    }

    @AfterEach
    fun cleanup() {
        // Reset all circuit breakers
        Provider.values().forEach { provider ->
            circuitBreakerService.resetCircuit(provider)
        }
    }

    // ========================================
    // Sanity Tests
    // ========================================

    @Test
    fun `should execute successful call with circuit breaker`() {
        // Given
        val provider = Provider.PROVIDER_A
        val expectedResult = "success"

        // When
        val result = circuitBreakerService.executeWithCircuitBreaker(provider) {
            expectedResult
        }

        // Then
        assertEquals(expectedResult, result)
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreakerService.getCircuitBreakerState(provider))
    }

    @Test
    fun `should record failure when provider throws exception`() {
        // Given
        val provider = Provider.PROVIDER_A

        // When/Then
        assertThrows<ProviderException> {
            circuitBreakerService.executeWithCircuitBreaker(provider) {
                throw ProviderException("Provider error")
            }
        }

        // Verify circuit is still closed (not enough failures yet)
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreakerService.getCircuitBreakerState(provider))
    }

    @Test
    fun `should get circuit breaker for provider`() {
        // Given
        val provider = Provider.PROVIDER_A

        // When
        val circuitBreaker = circuitBreakerService.getCircuitBreaker(provider)

        // Then
        assertNotNull(circuitBreaker)
        assertEquals(provider.name, circuitBreaker.name)
    }

    @Test
    fun `should check if circuit is closed`() {
        // Given
        val provider = Provider.PROVIDER_A

        // When
        val isClosed = circuitBreakerService.isCircuitClosed(provider)

        // Then
        assertTrue(isClosed)
        assertFalse(circuitBreakerService.isCircuitOpen(provider))
        assertFalse(circuitBreakerService.isCircuitHalfOpen(provider))
    }

    @Test
    fun `should get metrics for provider`() {
        // Given
        val provider = Provider.PROVIDER_A

        // Execute some calls
        repeat(3) {
            circuitBreakerService.executeWithCircuitBreaker(provider) { "success" }
        }

        // When
        val metrics = circuitBreakerService.getMetrics(provider)

        // Then
        assertEquals(provider, metrics.provider)
        assertEquals(CircuitBreaker.State.CLOSED, metrics.state)
        assertEquals(3, metrics.numberOfSuccessfulCalls)
        assertEquals(0, metrics.numberOfFailedCalls)
        assertTrue(metrics.isHealthy())
    }

    // ========================================
    // Regression Tests - Circuit Opening
    // ========================================

    @Test
    fun `should open circuit when failure rate exceeds threshold`() {
        // Given
        val provider = Provider.PROVIDER_A

        // Execute 5 successful calls (minimum required)
        repeat(5) {
            circuitBreakerService.executeWithCircuitBreaker(provider) { "success" }
        }

        // When - Execute 6 failed calls (60% failure rate)
        repeat(6) {
            assertThrows<ProviderException> {
                circuitBreakerService.executeWithCircuitBreaker(provider) {
                    throw ProviderException("Provider error")
                }
            }
        }

        // Then - Circuit should be open
        assertEquals(CircuitBreaker.State.OPEN, circuitBreakerService.getCircuitBreakerState(provider))
        assertTrue(circuitBreakerService.isCircuitOpen(provider))

        val metrics = circuitBreakerService.getMetrics(provider)
        assertTrue(metrics.failureRate > 50.0f)
        assertTrue(metrics.isUnhealthy())
    }

    @Test
    fun `should throw CircuitBreakerOpenException when circuit is open`() {
        // Given
        val provider = Provider.PROVIDER_A

        // Open the circuit by causing failures
        repeat(5) {
            circuitBreakerService.executeWithCircuitBreaker(provider) { "success" }
        }
        repeat(6) {
            assertThrows<ProviderException> {
                circuitBreakerService.executeWithCircuitBreaker(provider) {
                    throw ProviderException("Provider error")
                }
            }
        }

        // When/Then - New calls should be rejected
        val exception = assertThrows<CircuitBreakerOpenException> {
            circuitBreakerService.executeWithCircuitBreaker(provider) { "success" }
        }

        assertTrue(exception.message!!.contains("Circuit breaker is open"))
        assertTrue(exception.message!!.contains(provider.name))
    }

    @Test
    fun `should not open circuit for ignored exceptions`() {
        // Given
        val provider = Provider.PROVIDER_A

        // Execute minimum calls
        repeat(5) {
            circuitBreakerService.executeWithCircuitBreaker(provider) { "success" }
        }

        // When - Execute many calls with ignored exception
        repeat(10) {
            assertThrows<PaymentDeclinedException> {
                circuitBreakerService.executeWithCircuitBreaker(provider) {
                    throw PaymentDeclinedException("Card declined")
                }
            }
        }

        // Then - Circuit should remain closed (ignored exceptions don't count)
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreakerService.getCircuitBreakerState(provider))
    }

    // ========================================
    // Regression Tests - Circuit Recovery
    // ========================================

    @Test
    fun `should transition to half-open after wait duration`() {
        // Given
        val provider = Provider.PROVIDER_A

        // Open the circuit
        repeat(5) {
            circuitBreakerService.executeWithCircuitBreaker(provider) { "success" }
        }
        repeat(6) {
            assertThrows<ProviderException> {
                circuitBreakerService.executeWithCircuitBreaker(provider) {
                    throw ProviderException("Provider error")
                }
            }
        }

        assertEquals(CircuitBreaker.State.OPEN, circuitBreakerService.getCircuitBreakerState(provider))

        // When - Wait for transition to half-open
        Thread.sleep(150) // Wait duration is 100ms

        // Then - Circuit should transition to half-open
        // Try a call to trigger state check
        val result = circuitBreakerService.executeWithCircuitBreaker(provider) { "success" }
        
        assertEquals("success", result)
        // After successful call in half-open, it might transition to closed
        assertTrue(
            circuitBreakerService.getCircuitBreakerState(provider) == CircuitBreaker.State.HALF_OPEN ||
            circuitBreakerService.getCircuitBreakerState(provider) == CircuitBreaker.State.CLOSED
        )
    }

    @Test
    fun `should close circuit after successful calls in half-open state`() {
        // Given
        val provider = Provider.PROVIDER_A

        // Open the circuit
        repeat(5) {
            circuitBreakerService.executeWithCircuitBreaker(provider) { "success" }
        }
        repeat(6) {
            assertThrows<ProviderException> {
                circuitBreakerService.executeWithCircuitBreaker(provider) {
                    throw ProviderException("Provider error")
                }
            }
        }

        // Wait for half-open
        Thread.sleep(150)

        // When - Execute successful calls in half-open state
        repeat(3) { // Permitted calls in half-open = 3
            circuitBreakerService.executeWithCircuitBreaker(provider) { "success" }
        }

        // Then - Circuit should be closed
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreakerService.getCircuitBreakerState(provider))
    }

    @Test
    fun `should reopen circuit if calls fail in half-open state`() {
        // Given
        val provider = Provider.PROVIDER_A

        // Open the circuit
        repeat(5) {
            circuitBreakerService.executeWithCircuitBreaker(provider) { "success" }
        }
        repeat(6) {
            assertThrows<ProviderException> {
                circuitBreakerService.executeWithCircuitBreaker(provider) {
                    throw ProviderException("Provider error")
                }
            }
        }

        // Wait for half-open
        Thread.sleep(150)

        // When - Execute failed call in half-open state
        assertThrows<ProviderException> {
            circuitBreakerService.executeWithCircuitBreaker(provider) {
                throw ProviderException("Still failing")
            }
        }

        // Then - Circuit should reopen
        assertEquals(CircuitBreaker.State.OPEN, circuitBreakerService.getCircuitBreakerState(provider))
    }

    // ========================================
    // Regression Tests - Manual Control
    // ========================================

    @Test
    fun `should manually open circuit`() {
        // Given
        val provider = Provider.PROVIDER_A
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreakerService.getCircuitBreakerState(provider))

        // When
        circuitBreakerService.openCircuit(provider)

        // Then
        assertEquals(CircuitBreaker.State.OPEN, circuitBreakerService.getCircuitBreakerState(provider))
        assertTrue(circuitBreakerService.isCircuitOpen(provider))
    }

    @Test
    fun `should manually close circuit`() {
        // Given
        val provider = Provider.PROVIDER_A

        // Open the circuit first
        circuitBreakerService.openCircuit(provider)
        assertEquals(CircuitBreaker.State.OPEN, circuitBreakerService.getCircuitBreakerState(provider))

        // When
        circuitBreakerService.closeCircuit(provider)

        // Then
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreakerService.getCircuitBreakerState(provider))
        assertTrue(circuitBreakerService.isCircuitClosed(provider))
    }

    @Test
    fun `should reset circuit breaker`() {
        // Given
        val provider = Provider.PROVIDER_A

        // Execute some calls and open circuit
        repeat(5) {
            circuitBreakerService.executeWithCircuitBreaker(provider) { "success" }
        }
        repeat(6) {
            assertThrows<ProviderException> {
                circuitBreakerService.executeWithCircuitBreaker(provider) {
                    throw ProviderException("Provider error")
                }
            }
        }

        val metricsBefore = circuitBreakerService.getMetrics(provider)
        assertTrue(metricsBefore.numberOfFailedCalls > 0)

        // When
        circuitBreakerService.resetCircuit(provider)

        // Then
        val metricsAfter = circuitBreakerService.getMetrics(provider)
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreakerService.getCircuitBreakerState(provider))
        assertEquals(0, metricsAfter.numberOfSuccessfulCalls)
        assertEquals(0, metricsAfter.numberOfFailedCalls)
    }

    // ========================================
    // Regression Tests - Metrics
    // ========================================

    @Test
    fun `should collect accurate metrics`() {
        // Given
        val provider = Provider.PROVIDER_A

        // When - Execute mixed calls
        repeat(7) {
            circuitBreakerService.executeWithCircuitBreaker(provider) { "success" }
        }
        repeat(3) {
            assertThrows<ProviderException> {
                circuitBreakerService.executeWithCircuitBreaker(provider) {
                    throw ProviderException("Provider error")
                }
            }
        }

        // Then
        val metrics = circuitBreakerService.getMetrics(provider)
        assertEquals(7, metrics.numberOfSuccessfulCalls)
        assertEquals(3, metrics.numberOfFailedCalls)
        assertEquals(30.0f, metrics.failureRate, 0.1f) // 3/10 = 30%
        assertTrue(metrics.isHealthy()) // < 50% failure rate
    }

    @Test
    fun `should get metrics for all providers`() {
        // Given - Execute calls on multiple providers
        circuitBreakerService.executeWithCircuitBreaker(Provider.PROVIDER_A) { "success" }
        circuitBreakerService.executeWithCircuitBreaker(Provider.PROVIDER_B) { "success" }

        // When
        val allMetrics = circuitBreakerService.getAllMetrics()

        // Then
        assertEquals(Provider.values().size, allMetrics.size)
        assertTrue(allMetrics.any { it.provider == Provider.PROVIDER_A })
        assertTrue(allMetrics.any { it.provider == Provider.PROVIDER_B })
    }

    @Test
    fun `should identify healthy circuit breaker`() {
        // Given
        val provider = Provider.PROVIDER_A

        // Execute mostly successful calls
        repeat(9) {
            circuitBreakerService.executeWithCircuitBreaker(provider) { "success" }
        }
        assertThrows<ProviderException> {
            circuitBreakerService.executeWithCircuitBreaker(provider) {
                throw ProviderException("Provider error")
            }
        }

        // When
        val metrics = circuitBreakerService.getMetrics(provider)

        // Then
        assertTrue(metrics.isHealthy()) // 10% failure rate
        assertFalse(metrics.isDegraded())
        assertFalse(metrics.isUnhealthy())
    }

    @Test
    fun `should identify degraded circuit breaker`() {
        // Given
        val provider = Provider.PROVIDER_A

        // Execute calls with 40% failure rate
        repeat(6) {
            circuitBreakerService.executeWithCircuitBreaker(provider) { "success" }
        }
        repeat(4) {
            assertThrows<ProviderException> {
                circuitBreakerService.executeWithCircuitBreaker(provider) {
                    throw ProviderException("Provider error")
                }
            }
        }

        // When
        val metrics = circuitBreakerService.getMetrics(provider)

        // Then
        assertTrue(metrics.isDegraded()) // 40% failure rate (between 25% and 50%)
        assertFalse(metrics.isHealthy())
        assertFalse(metrics.isUnhealthy())
    }

    @Test
    fun `should identify unhealthy circuit breaker`() {
        // Given
        val provider = Provider.PROVIDER_A

        // Open the circuit
        repeat(5) {
            circuitBreakerService.executeWithCircuitBreaker(provider) { "success" }
        }
        repeat(6) {
            assertThrows<ProviderException> {
                circuitBreakerService.executeWithCircuitBreaker(provider) {
                    throw ProviderException("Provider error")
                }
            }
        }

        // When
        val metrics = circuitBreakerService.getMetrics(provider)

        // Then
        assertTrue(metrics.isUnhealthy()) // Circuit is open
        assertFalse(metrics.isHealthy())
        assertFalse(metrics.isDegraded())
    }

    // ========================================
    // Regression Tests - Provider Isolation
    // ========================================

    @Test
    fun `should isolate circuit breakers per provider`() {
        // Given
        val providerA = Provider.PROVIDER_A
        val providerB = Provider.PROVIDER_B

        // When - Open circuit for Provider A only
        repeat(5) {
            circuitBreakerService.executeWithCircuitBreaker(providerA) { "success" }
        }
        repeat(6) {
            assertThrows<ProviderException> {
                circuitBreakerService.executeWithCircuitBreaker(providerA) {
                    throw ProviderException("Provider A error")
                }
            }
        }

        // Then - Provider A circuit is open, Provider B circuit is closed
        assertEquals(CircuitBreaker.State.OPEN, circuitBreakerService.getCircuitBreakerState(providerA))
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreakerService.getCircuitBreakerState(providerB))

        // Provider B should still work
        val result = circuitBreakerService.executeWithCircuitBreaker(providerB) { "success" }
        assertEquals("success", result)
    }
}


