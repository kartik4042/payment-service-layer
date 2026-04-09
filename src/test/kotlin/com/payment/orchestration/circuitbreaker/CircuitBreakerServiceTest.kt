package com.payment.orchestration.circuitbreaker

import com.payment.orchestration.domain.model.Provider
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Unit tests for CircuitBreakerService
 * 
 * Test Coverage:
 * - Circuit breaker state transitions (CLOSED -> OPEN -> HALF_OPEN -> CLOSED)
 * - Failure threshold enforcement
 * - Success threshold for recovery
 * - Timeout period enforcement
 * - Health status calculation
 * - Multiple provider isolation
 */
class CircuitBreakerServiceTest {
    
    private lateinit var circuitBreakerService: CircuitBreakerService
    
    @BeforeEach
    fun setup() {
        val config = CircuitBreakerConfig(
            failureThreshold = 3,
            successThreshold = 2,
            timeout = Duration.ofSeconds(5),
            halfOpenMaxAttempts = 3
        )
        circuitBreakerService = CircuitBreakerService(config)
    }
    
    @Test
    fun `should start in CLOSED state`() {
        // When
        val state = circuitBreakerService.getState(Provider.PROVIDER_A)
        
        // Then
        assertEquals(CircuitBreakerState.CLOSED, state)
    }
    
    @Test
    fun `should transition to OPEN after failure threshold`() {
        // Given
        val provider = Provider.PROVIDER_A
        
        // When - Record 3 failures (threshold)
        circuitBreakerService.recordFailure(provider)
        circuitBreakerService.recordFailure(provider)
        circuitBreakerService.recordFailure(provider)
        
        // Then
        assertEquals(CircuitBreakerState.OPEN, circuitBreakerService.getState(provider))
        assertFalse(circuitBreakerService.isHealthy(provider))
    }
    
    @Test
    fun `should not open circuit before failure threshold`() {
        // Given
        val provider = Provider.PROVIDER_A
        
        // When - Record 2 failures (below threshold of 3)
        circuitBreakerService.recordFailure(provider)
        circuitBreakerService.recordFailure(provider)
        
        // Then
        assertEquals(CircuitBreakerState.CLOSED, circuitBreakerService.getState(provider))
        assertTrue(circuitBreakerService.isHealthy(provider))
    }
    
    @Test
    fun `should reset failure count on success in CLOSED state`() {
        // Given
        val provider = Provider.PROVIDER_A
        
        // When - Record 2 failures, then success
        circuitBreakerService.recordFailure(provider)
        circuitBreakerService.recordFailure(provider)
        circuitBreakerService.recordSuccess(provider)
        
        // Then - Should still be CLOSED and healthy
        assertEquals(CircuitBreakerState.CLOSED, circuitBreakerService.getState(provider))
        assertTrue(circuitBreakerService.isHealthy(provider))
        
        // When - Record 2 more failures (should not open yet)
        circuitBreakerService.recordFailure(provider)
        circuitBreakerService.recordFailure(provider)
        
        // Then - Still CLOSED because count was reset
        assertEquals(CircuitBreakerState.CLOSED, circuitBreakerService.getState(provider))
    }
    
    @Test
    fun `should transition to HALF_OPEN after timeout`() {
        // Given
        val provider = Provider.PROVIDER_A
        val config = CircuitBreakerConfig(
            failureThreshold = 2,
            successThreshold = 2,
            timeout = Duration.ofMillis(100), // Short timeout for testing
            halfOpenMaxAttempts = 3
        )
        val service = CircuitBreakerService(config)
        
        // When - Open the circuit
        service.recordFailure(provider)
        service.recordFailure(provider)
        assertEquals(CircuitBreakerState.OPEN, service.getState(provider))
        
        // Wait for timeout
        Thread.sleep(150)
        
        // Then - Should transition to HALF_OPEN
        assertEquals(CircuitBreakerState.HALF_OPEN, service.getState(provider))
    }
    
    @Test
    fun `should close circuit after success threshold in HALF_OPEN`() {
        // Given
        val provider = Provider.PROVIDER_A
        val config = CircuitBreakerConfig(
            failureThreshold = 2,
            successThreshold = 2,
            timeout = Duration.ofMillis(100),
            halfOpenMaxAttempts = 3
        )
        val service = CircuitBreakerService(config)
        
        // Open the circuit
        service.recordFailure(provider)
        service.recordFailure(provider)
        
        // Wait for timeout to transition to HALF_OPEN
        Thread.sleep(150)
        assertEquals(CircuitBreakerState.HALF_OPEN, service.getState(provider))
        
        // When - Record success threshold (2 successes)
        service.recordSuccess(provider)
        service.recordSuccess(provider)
        
        // Then - Should close the circuit
        assertEquals(CircuitBreakerState.CLOSED, service.getState(provider))
        assertTrue(service.isHealthy(provider))
    }
    
    @Test
    fun `should reopen circuit on failure in HALF_OPEN`() {
        // Given
        val provider = Provider.PROVIDER_A
        val config = CircuitBreakerConfig(
            failureThreshold = 2,
            successThreshold = 2,
            timeout = Duration.ofMillis(100),
            halfOpenMaxAttempts = 3
        )
        val service = CircuitBreakerService(config)
        
        // Open the circuit
        service.recordFailure(provider)
        service.recordFailure(provider)
        
        // Wait for timeout to transition to HALF_OPEN
        Thread.sleep(150)
        assertEquals(CircuitBreakerState.HALF_OPEN, service.getState(provider))
        
        // When - Record failure in HALF_OPEN
        service.recordFailure(provider)
        
        // Then - Should reopen the circuit
        assertEquals(CircuitBreakerState.OPEN, service.getState(provider))
        assertFalse(service.isHealthy(provider))
    }
    
    @Test
    fun `should isolate circuit breakers per provider`() {
        // Given
        val providerA = Provider.PROVIDER_A
        val providerB = Provider.PROVIDER_B
        
        // When - Open circuit for Provider A only
        circuitBreakerService.recordFailure(providerA)
        circuitBreakerService.recordFailure(providerA)
        circuitBreakerService.recordFailure(providerA)
        
        // Then - Provider A should be OPEN, Provider B should be CLOSED
        assertEquals(CircuitBreakerState.OPEN, circuitBreakerService.getState(providerA))
        assertEquals(CircuitBreakerState.CLOSED, circuitBreakerService.getState(providerB))
        assertFalse(circuitBreakerService.isHealthy(providerA))
        assertTrue(circuitBreakerService.isHealthy(providerB))
    }
    
    @Test
    fun `should calculate health status correctly`() {
        // Given
        val provider = Provider.PROVIDER_A
        
        // When - CLOSED state
        var health = circuitBreakerService.getHealthStatus(provider)
        
        // Then
        assertEquals("HEALTHY", health.status)
        assertEquals(CircuitBreakerState.CLOSED, health.state)
        
        // When - Record some failures (degraded)
        circuitBreakerService.recordFailure(provider)
        health = circuitBreakerService.getHealthStatus(provider)
        
        // Then
        assertEquals("DEGRADED", health.status)
        
        // When - Open circuit
        circuitBreakerService.recordFailure(provider)
        circuitBreakerService.recordFailure(provider)
        health = circuitBreakerService.getHealthStatus(provider)
        
        // Then
        assertEquals("UNHEALTHY", health.status)
        assertEquals(CircuitBreakerState.OPEN, health.state)
    }
}


