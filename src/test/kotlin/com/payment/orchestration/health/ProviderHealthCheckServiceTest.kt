package com.payment.orchestration.health

import com.payment.orchestration.domain.model.Provider
import com.payment.orchestration.provider.ProviderA
import com.payment.orchestration.provider.ProviderB
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Provider Health Check Service Tests
 * 
 * Tests health monitoring functionality including:
 * - Scheduled health checks
 * - Health status tracking
 * - Provider availability detection
 * - Uptime calculation
 * - Response time tracking
 * - Alerting on failures
 * - Manual health checks
 */
class ProviderHealthCheckServiceTest {

    private lateinit var providerA: ProviderA
    private lateinit var providerB: ProviderB
    private lateinit var healthCheckService: ProviderHealthCheckService

    @BeforeEach
    fun setup() {
        providerA = mockk()
        providerB = mockk()
        
        // Default: providers are healthy
        every { providerA.isAvailable() } returns true
        every { providerB.isAvailable() } returns true
        
        healthCheckService = ProviderHealthCheckService(providerA, providerB)
    }

    @AfterEach
    fun cleanup() {
        clearAllMocks()
    }

    // ========================================
    // Sanity Tests
    // ========================================

    @Test
    fun `should perform health check successfully`() {
        // Given
        every { providerA.isAvailable() } returns true

        // When
        val result = healthCheckService.checkProviderHealth(Provider.PROVIDER_A)

        // Then
        assertTrue(result.isSuccessful())
        assertEquals(HealthStatus.HEALTHY, result.status)
        assertNull(result.errorMessage)
        verify { providerA.isAvailable() }
    }

    @Test
    fun `should detect healthy provider`() {
        // Given
        every { providerA.isAvailable() } returns true

        // When
        healthCheckService.checkProviderHealth(Provider.PROVIDER_A)
        val status = healthCheckService.getHealthStatus(Provider.PROVIDER_A)

        // Then
        assertEquals(HealthStatus.HEALTHY, status.status)
        assertTrue(status.isHealthy())
        assertTrue(status.isAvailable())
        assertFalse(status.isUnhealthy())
    }

    @Test
    fun `should get health status for all providers`() {
        // Given
        every { providerA.isAvailable() } returns true
        every { providerB.isAvailable() } returns true

        // When
        healthCheckService.checkProviderHealth(Provider.PROVIDER_A)
        healthCheckService.checkProviderHealth(Provider.PROVIDER_B)
        val allStatus = healthCheckService.getAllHealthStatus()

        // Then
        assertEquals(2, allStatus.size)
        assertTrue(allStatus.containsKey(Provider.PROVIDER_A))
        assertTrue(allStatus.containsKey(Provider.PROVIDER_B))
    }

    @Test
    fun `should check if provider is healthy`() {
        // Given
        every { providerA.isAvailable() } returns true

        // When
        healthCheckService.checkProviderHealth(Provider.PROVIDER_A)

        // Then
        assertTrue(healthCheckService.isProviderHealthy(Provider.PROVIDER_A))
        assertTrue(healthCheckService.isProviderAvailable(Provider.PROVIDER_A))
    }

    @Test
    fun `should get health check history`() {
        // Given
        every { providerA.isAvailable() } returns true

        // When - Perform multiple health checks
        repeat(5) {
            healthCheckService.checkProviderHealth(Provider.PROVIDER_A)
        }

        val history = healthCheckService.getHealthHistory(Provider.PROVIDER_A, limit = 10)

        // Then
        assertEquals(5, history.size)
        history.forEach { result ->
            assertEquals(HealthStatus.HEALTHY, result.status)
        }
    }

    // ========================================
    // Regression Tests - Unhealthy Provider
    // ========================================

    @Test
    fun `should detect unhealthy provider`() {
        // Given
        every { providerA.isAvailable() } returns false

        // When
        val result = healthCheckService.checkProviderHealth(Provider.PROVIDER_A)

        // Then
        assertEquals(HealthStatus.UNHEALTHY, result.status)
        assertFalse(result.isSuccessful())
        assertNotNull(result.errorMessage)
    }

    @Test
    fun `should track consecutive failures`() {
        // Given
        every { providerA.isAvailable() } returns false

        // When - Perform 3 failed health checks
        repeat(3) {
            healthCheckService.checkProviderHealth(Provider.PROVIDER_A)
        }

        val status = healthCheckService.getHealthStatus(Provider.PROVIDER_A)

        // Then
        assertEquals(3, status.consecutiveFailures)
        assertEquals(HealthStatus.UNHEALTHY, status.status)
    }

    @Test
    fun `should reset consecutive failures on success`() {
        // Given
        every { providerA.isAvailable() } returns false

        // When - Fail twice, then succeed
        repeat(2) {
            healthCheckService.checkProviderHealth(Provider.PROVIDER_A)
        }

        every { providerA.isAvailable() } returns true
        healthCheckService.checkProviderHealth(Provider.PROVIDER_A)

        val status = healthCheckService.getHealthStatus(Provider.PROVIDER_A)

        // Then
        assertEquals(0, status.consecutiveFailures)
        assertEquals(HealthStatus.HEALTHY, status.status)
    }

    @Test
    fun `should mark provider as unhealthy when not available`() {
        // Given
        every { providerA.isAvailable() } returns false

        // When
        healthCheckService.checkProviderHealth(Provider.PROVIDER_A)

        // Then
        assertFalse(healthCheckService.isProviderHealthy(Provider.PROVIDER_A))
        assertFalse(healthCheckService.isProviderAvailable(Provider.PROVIDER_A))
    }

    // ========================================
    // Regression Tests - Degraded Provider
    // ========================================

    @Test
    fun `should detect degraded provider with slow response`() {
        // Given
        every { providerA.isAvailable() } answers {
            Thread.sleep(1500) // 1.5 seconds - degraded
            true
        }

        // When
        val result = healthCheckService.checkProviderHealth(Provider.PROVIDER_A)

        // Then
        assertEquals(HealthStatus.DEGRADED, result.status)
        assertTrue(result.responseTimeMs >= 1000)
        assertTrue(result.responseTimeMs < 3000)
    }

    @Test
    fun `should mark very slow provider as unhealthy`() {
        // Given
        every { providerA.isAvailable() } answers {
            Thread.sleep(3500) // 3.5 seconds - unhealthy
            true
        }

        // When
        val result = healthCheckService.checkProviderHealth(Provider.PROVIDER_A)

        // Then
        assertEquals(HealthStatus.UNHEALTHY, result.status)
        assertTrue(result.responseTimeMs >= 3000)
    }

    @Test
    fun `should consider degraded provider as available`() {
        // Given
        every { providerA.isAvailable() } answers {
            Thread.sleep(1500) // Degraded but available
            true
        }

        // When
        healthCheckService.checkProviderHealth(Provider.PROVIDER_A)

        // Then
        assertFalse(healthCheckService.isProviderHealthy(Provider.PROVIDER_A))
        assertTrue(healthCheckService.isProviderAvailable(Provider.PROVIDER_A))
    }

    // ========================================
    // Regression Tests - Exception Handling
    // ========================================

    @Test
    fun `should handle provider exception gracefully`() {
        // Given
        every { providerA.isAvailable() } throws RuntimeException("Provider error")

        // When
        val result = healthCheckService.checkProviderHealth(Provider.PROVIDER_A)

        // Then
        assertEquals(HealthStatus.UNHEALTHY, result.status)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage!!.contains("Provider error"))
    }

    @Test
    fun `should continue checking other providers after one fails`() {
        // Given
        every { providerA.isAvailable() } throws RuntimeException("Provider A error")
        every { providerB.isAvailable() } returns true

        // When
        healthCheckService.checkProviderHealth(Provider.PROVIDER_A)
        healthCheckService.checkProviderHealth(Provider.PROVIDER_B)

        // Then
        assertFalse(healthCheckService.isProviderHealthy(Provider.PROVIDER_A))
        assertTrue(healthCheckService.isProviderHealthy(Provider.PROVIDER_B))
    }

    // ========================================
    // Regression Tests - Health History
    // ========================================

    @Test
    fun `should maintain health check history`() {
        // Given
        every { providerA.isAvailable() } returns true

        // When - Perform 10 health checks
        repeat(10) {
            healthCheckService.checkProviderHealth(Provider.PROVIDER_A)
        }

        val history = healthCheckService.getHealthHistory(Provider.PROVIDER_A)

        // Then
        assertEquals(10, history.size)
    }

    @Test
    fun `should limit health history to last 100 checks`() {
        // Given
        every { providerA.isAvailable() } returns true

        // When - Perform 150 health checks
        repeat(150) {
            healthCheckService.checkProviderHealth(Provider.PROVIDER_A)
        }

        val history = healthCheckService.getHealthHistory(Provider.PROVIDER_A, limit = 200)

        // Then
        assertEquals(100, history.size) // Should be capped at 100
    }

    @Test
    fun `should return limited history when requested`() {
        // Given
        every { providerA.isAvailable() } returns true

        // When
        repeat(20) {
            healthCheckService.checkProviderHealth(Provider.PROVIDER_A)
        }

        val history = healthCheckService.getHealthHistory(Provider.PROVIDER_A, limit = 5)

        // Then
        assertEquals(5, history.size)
    }

    @Test
    fun `should return most recent checks in history`() {
        // Given
        every { providerA.isAvailable() } returns true

        // When
        val firstCheckTime = Instant.now()
        healthCheckService.checkProviderHealth(Provider.PROVIDER_A)
        
        Thread.sleep(100)
        
        healthCheckService.checkProviderHealth(Provider.PROVIDER_A)
        val lastCheckTime = Instant.now()

        val history = healthCheckService.getHealthHistory(Provider.PROVIDER_A, limit = 2)

        // Then
        assertEquals(2, history.size)
        assertTrue(history.last().timestamp.isAfter(firstCheckTime))
        assertTrue(history.last().timestamp.isBefore(lastCheckTime.plusSeconds(1)))
    }

    // ========================================
    // Regression Tests - Uptime Calculation
    // ========================================

    @Test
    fun `should calculate 100 percent uptime for healthy provider`() {
        // Given
        every { providerA.isAvailable() } returns true

        // When
        repeat(10) {
            healthCheckService.checkProviderHealth(Provider.PROVIDER_A)
        }

        val uptime = healthCheckService.getProviderUptime(Provider.PROVIDER_A)

        // Then
        assertEquals(100.0, uptime, 0.1)
    }

    @Test
    fun `should calculate 0 percent uptime for unhealthy provider`() {
        // Given
        every { providerA.isAvailable() } returns false

        // When
        repeat(10) {
            healthCheckService.checkProviderHealth(Provider.PROVIDER_A)
        }

        val uptime = healthCheckService.getProviderUptime(Provider.PROVIDER_A)

        // Then
        assertEquals(0.0, uptime, 0.1)
    }

    @Test
    fun `should calculate partial uptime correctly`() {
        // Given
        every { providerA.isAvailable() } returns true

        // When - 7 successful, 3 failed
        repeat(7) {
            healthCheckService.checkProviderHealth(Provider.PROVIDER_A)
        }

        every { providerA.isAvailable() } returns false
        repeat(3) {
            healthCheckService.checkProviderHealth(Provider.PROVIDER_A)
        }

        val uptime = healthCheckService.getProviderUptime(Provider.PROVIDER_A)

        // Then
        assertEquals(70.0, uptime, 0.1) // 7/10 = 70%
    }

    @Test
    fun `should include degraded status in uptime calculation`() {
        // Given
        every { providerA.isAvailable() } answers {
            Thread.sleep(1500) // Degraded but available
            true
        }

        // When
        repeat(10) {
            healthCheckService.checkProviderHealth(Provider.PROVIDER_A)
        }

        val uptime = healthCheckService.getProviderUptime(Provider.PROVIDER_A)

        // Then
        assertEquals(100.0, uptime, 0.1) // Degraded counts as available
    }

    // ========================================
    // Regression Tests - Response Time
    // ========================================

    @Test
    fun `should track response time for health checks`() {
        // Given
        every { providerA.isAvailable() } returns true

        // When
        val result = healthCheckService.checkProviderHealth(Provider.PROVIDER_A)

        // Then
        assertTrue(result.responseTimeMs >= 0)
        assertTrue(result.isResponseTimeAcceptable()) // < 1s
    }

    @Test
    fun `should calculate average response time`() {
        // Given
        every { providerA.isAvailable() } returns true

        // When
        repeat(10) {
            healthCheckService.checkProviderHealth(Provider.PROVIDER_A)
        }

        val avgResponseTime = healthCheckService.getAverageResponseTime(Provider.PROVIDER_A)

        // Then
        assertTrue(avgResponseTime >= 0)
        assertTrue(avgResponseTime < 1000) // Should be fast
    }

    @Test
    fun `should store last response time in health status`() {
        // Given
        every { providerA.isAvailable() } returns true

        // When
        healthCheckService.checkProviderHealth(Provider.PROVIDER_A)
        val status = healthCheckService.getHealthStatus(Provider.PROVIDER_A)

        // Then
        assertNotNull(status.lastResponseTimeMs)
        assertTrue(status.lastResponseTimeMs!! >= 0)
    }

    // ========================================
    // Regression Tests - Manual Operations
    // ========================================

    @Test
    fun `should force health check on demand`() {
        // Given
        every { providerA.isAvailable() } returns true

        // When
        val result = healthCheckService.forceHealthCheck(Provider.PROVIDER_A)

        // Then
        assertEquals(HealthStatus.HEALTHY, result.status)
        verify { providerA.isAvailable() }
    }

    @Test
    fun `should reset health status`() {
        // Given
        every { providerA.isAvailable() } returns false

        // Perform failed checks
        repeat(3) {
            healthCheckService.checkProviderHealth(Provider.PROVIDER_A)
        }

        val statusBefore = healthCheckService.getHealthStatus(Provider.PROVIDER_A)
        assertEquals(3, statusBefore.consecutiveFailures)

        // When
        healthCheckService.resetHealthStatus(Provider.PROVIDER_A)

        // Then
        val statusAfter = healthCheckService.getHealthStatus(Provider.PROVIDER_A)
        assertEquals(HealthStatus.UNKNOWN, statusAfter.status)
        assertEquals(0, statusAfter.consecutiveFailures)
    }

    @Test
    fun `should clear history on reset`() {
        // Given
        every { providerA.isAvailable() } returns true

        repeat(10) {
            healthCheckService.checkProviderHealth(Provider.PROVIDER_A)
        }

        val historyBefore = healthCheckService.getHealthHistory(Provider.PROVIDER_A)
        assertEquals(10, historyBefore.size)

        // When
        healthCheckService.resetHealthStatus(Provider.PROVIDER_A)

        // Then
        val historyAfter = healthCheckService.getHealthHistory(Provider.PROVIDER_A)
        assertEquals(0, historyAfter.size)
    }

    // ========================================
    // Regression Tests - Provider Isolation
    // ========================================

    @Test
    fun `should track health independently per provider`() {
        // Given
        every { providerA.isAvailable() } returns true
        every { providerB.isAvailable() } returns false

        // When
        healthCheckService.checkProviderHealth(Provider.PROVIDER_A)
        healthCheckService.checkProviderHealth(Provider.PROVIDER_B)

        // Then
        assertTrue(healthCheckService.isProviderHealthy(Provider.PROVIDER_A))
        assertFalse(healthCheckService.isProviderHealthy(Provider.PROVIDER_B))
    }

    @Test
    fun `should maintain separate history per provider`() {
        // Given
        every { providerA.isAvailable() } returns true
        every { providerB.isAvailable() } returns true

        // When
        repeat(5) {
            healthCheckService.checkProviderHealth(Provider.PROVIDER_A)
        }
        repeat(3) {
            healthCheckService.checkProviderHealth(Provider.PROVIDER_B)
        }

        // Then
        val historyA = healthCheckService.getHealthHistory(Provider.PROVIDER_A)
        val historyB = healthCheckService.getHealthHistory(Provider.PROVIDER_B)
        
        assertEquals(5, historyA.size)
        assertEquals(3, historyB.size)
    }

    @Test
    fun `should calculate uptime independently per provider`() {
        // Given
        every { providerA.isAvailable() } returns true
        every { providerB.isAvailable() } returns false

        // When
        repeat(10) {
            healthCheckService.checkProviderHealth(Provider.PROVIDER_A)
            healthCheckService.checkProviderHealth(Provider.PROVIDER_B)
        }

        // Then
        assertEquals(100.0, healthCheckService.getProviderUptime(Provider.PROVIDER_A), 0.1)
        assertEquals(0.0, healthCheckService.getProviderUptime(Provider.PROVIDER_B), 0.1)
    }

    // ========================================
    // Regression Tests - Health Score
    // ========================================

    @Test
    fun `should calculate health score for healthy provider`() {
        // Given
        every { providerA.isAvailable() } returns true

        // When
        healthCheckService.checkProviderHealth(Provider.PROVIDER_A)
        val status = healthCheckService.getHealthStatus(Provider.PROVIDER_A)

        // Then
        assertEquals(100, status.getHealthScore())
    }

    @Test
    fun `should calculate health score for degraded provider`() {
        // Given
        every { providerA.isAvailable() } answers {
            Thread.sleep(1500) // Degraded
            true
        }

        // When
        healthCheckService.checkProviderHealth(Provider.PROVIDER_A)
        val status = healthCheckService.getHealthStatus(Provider.PROVIDER_A)

        // Then
        assertEquals(50, status.getHealthScore())
    }

    @Test
    fun `should calculate health score for unhealthy provider`() {
        // Given
        every { providerA.isAvailable() } returns false

        // When
        healthCheckService.checkProviderHealth(Provider.PROVIDER_A)
        val status = healthCheckService.getHealthStatus(Provider.PROVIDER_A)

        // Then
        assertEquals(0, status.getHealthScore())
    }

    // ========================================
    // Regression Tests - Edge Cases
    // ========================================

    @Test
    fun `should handle empty history gracefully`() {
        // When
        val history = healthCheckService.getHealthHistory(Provider.PROVIDER_A)
        val uptime = healthCheckService.getProviderUptime(Provider.PROVIDER_A)
        val avgResponseTime = healthCheckService.getAverageResponseTime(Provider.PROVIDER_A)

        // Then
        assertEquals(0, history.size)
        assertEquals(0.0, uptime, 0.1)
        assertEquals(0L, avgResponseTime)
    }

    @Test
    fun `should handle initial unknown status`() {
        // When - Get status before any health check
        val status = healthCheckService.getHealthStatus(Provider.PROVIDER_A)

        // Then
        assertEquals(HealthStatus.UNKNOWN, status.status)
        assertEquals(0, status.consecutiveFailures)
    }

    @Test
    fun `should handle rapid consecutive health checks`() {
        // Given
        every { providerA.isAvailable() } returns true

        // When - Perform many rapid checks
        repeat(50) {
            healthCheckService.checkProviderHealth(Provider.PROVIDER_A)
        }

        // Then - Should handle without errors
        val status = healthCheckService.getHealthStatus(Provider.PROVIDER_A)
        assertEquals(HealthStatus.HEALTHY, status.status)
        
        val history = healthCheckService.getHealthHistory(Provider.PROVIDER_A, limit = 100)
        assertEquals(50, history.size)
    }
}


