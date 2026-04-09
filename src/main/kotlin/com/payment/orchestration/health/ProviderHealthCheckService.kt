package com.payment.orchestration.health

import com.payment.orchestration.domain.model.Provider
import com.payment.orchestration.provider.PaymentProvider
import com.payment.orchestration.provider.ProviderA
import com.payment.orchestration.provider.ProviderB
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Provider Health Check Service
 * 
 * Monitors the health of payment providers through periodic health checks.
 * 
 * Features:
 * - Scheduled health checks every 30 seconds
 * - Provider availability tracking
 * - Health status history
 * - Integration with routing engine
 * - Alerting on provider degradation
 * 
 * Health Check Process:
 * 1. Send lightweight health check request to provider
 * 2. Measure response time
 * 3. Update provider health status
 * 4. Store health check history
 * 5. Alert if provider is unhealthy
 * 
 * Health Status:
 * - HEALTHY: Provider responding normally (< 1s response time)
 * - DEGRADED: Provider slow but functional (1-3s response time)
 * - UNHEALTHY: Provider not responding or errors (> 3s or errors)
 * 
 * @property providerA Provider A instance
 * @property providerB Provider B instance
 */
@Service
class ProviderHealthCheckService(
    private val providerA: ProviderA,
    private val providerB: ProviderB
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    // Store current health status for each provider
    private val healthStatus = ConcurrentHashMap<Provider, ProviderHealthStatus>()

    // Store health check history (last 100 checks per provider)
    private val healthHistory = ConcurrentHashMap<Provider, MutableList<HealthCheckResult>>()

    init {
        // Initialize health status for all providers
        Provider.values().forEach { provider ->
            healthStatus[provider] = ProviderHealthStatus(
                provider = provider,
                status = HealthStatus.UNKNOWN,
                lastCheckTime = Instant.now(),
                consecutiveFailures = 0
            )
            healthHistory[provider] = mutableListOf()
        }
    }

    /**
     * Perform health checks for all providers
     * 
     * Scheduled to run every 30 seconds.
     */
    @Scheduled(fixedDelay = 30000, initialDelay = 5000)
    fun performHealthChecks() {
        logger.debug("Starting scheduled health checks for all providers")

        Provider.values().forEach { provider ->
            try {
                checkProviderHealth(provider)
            } catch (e: Exception) {
                logger.error("Error performing health check for provider: ${provider.name}", e)
            }
        }

        logger.debug("Completed health checks for all providers")
    }

    /**
     * Check health of a specific provider
     * 
     * @param provider Provider to check
     * @return Health check result
     */
    fun checkProviderHealth(provider: Provider): HealthCheckResult {
        val startTime = System.currentTimeMillis()
        
        return try {
            // Get provider instance
            val providerInstance = getProviderInstance(provider)

            // Perform health check
            val isHealthy = performHealthCheckRequest(providerInstance)
            val responseTime = System.currentTimeMillis() - startTime

            // Determine health status based on response time and result
            val status = when {
                !isHealthy -> HealthStatus.UNHEALTHY
                responseTime > 3000 -> HealthStatus.UNHEALTHY
                responseTime > 1000 -> HealthStatus.DEGRADED
                else -> HealthStatus.HEALTHY
            }

            // Create result
            val result = HealthCheckResult(
                provider = provider,
                status = status,
                responseTimeMs = responseTime,
                timestamp = Instant.now(),
                errorMessage = if (!isHealthy) "Health check failed" else null
            )

            // Update health status
            updateHealthStatus(provider, result)

            // Log result
            logHealthCheckResult(result)

            result

        } catch (e: Exception) {
            val responseTime = System.currentTimeMillis() - startTime
            
            val result = HealthCheckResult(
                provider = provider,
                status = HealthStatus.UNHEALTHY,
                responseTimeMs = responseTime,
                timestamp = Instant.now(),
                errorMessage = e.message ?: "Unknown error"
            )

            updateHealthStatus(provider, result)
            logger.error("Health check failed for provider: ${provider.name}", e)

            result
        }
    }

    /**
     * Perform actual health check request to provider
     * 
     * @param provider Provider instance
     * @return true if healthy, false otherwise
     */
    private fun performHealthCheckRequest(provider: PaymentProvider): Boolean {
        return try {
            // Perform lightweight health check
            // In production, this would call provider's health endpoint
            provider.isAvailable()
        } catch (e: Exception) {
            logger.warn("Provider health check request failed: ${e.message}")
            false
        }
    }

    /**
     * Update health status for a provider
     * 
     * @param provider Provider
     * @param result Health check result
     */
    private fun updateHealthStatus(provider: Provider, result: HealthCheckResult) {
        val currentStatus = healthStatus[provider]!!

        // Calculate consecutive failures
        val consecutiveFailures = if (result.status == HealthStatus.UNHEALTHY) {
            currentStatus.consecutiveFailures + 1
        } else {
            0
        }

        // Update status
        healthStatus[provider] = ProviderHealthStatus(
            provider = provider,
            status = result.status,
            lastCheckTime = result.timestamp,
            consecutiveFailures = consecutiveFailures,
            lastResponseTimeMs = result.responseTimeMs
        )

        // Add to history (keep last 100)
        val history = healthHistory[provider]!!
        history.add(result)
        if (history.size > 100) {
            history.removeAt(0)
        }

        // Alert if provider becomes unhealthy
        if (result.status == HealthStatus.UNHEALTHY && consecutiveFailures >= 3) {
            alertProviderUnhealthy(provider, consecutiveFailures)
        }

        // Alert if provider recovers
        if (result.status == HealthStatus.HEALTHY && currentStatus.status == HealthStatus.UNHEALTHY) {
            alertProviderRecovered(provider)
        }
    }

    /**
     * Get provider instance
     * 
     * @param provider Provider enum
     * @return Provider instance
     */
    private fun getProviderInstance(provider: Provider): PaymentProvider {
        return when (provider) {
            Provider.PROVIDER_A -> providerA
            Provider.PROVIDER_B -> providerB
        }
    }

    /**
     * Log health check result
     * 
     * @param result Health check result
     */
    private fun logHealthCheckResult(result: HealthCheckResult) {
        when (result.status) {
            HealthStatus.HEALTHY -> 
                logger.debug("Provider ${result.provider.name} is HEALTHY (${result.responseTimeMs}ms)")
            HealthStatus.DEGRADED -> 
                logger.warn("Provider ${result.provider.name} is DEGRADED (${result.responseTimeMs}ms)")
            HealthStatus.UNHEALTHY -> 
                logger.error("Provider ${result.provider.name} is UNHEALTHY: ${result.errorMessage}")
            HealthStatus.UNKNOWN -> 
                logger.info("Provider ${result.provider.name} status is UNKNOWN")
        }
    }

    /**
     * Alert when provider becomes unhealthy
     * 
     * @param provider Provider
     * @param consecutiveFailures Number of consecutive failures
     */
    private fun alertProviderUnhealthy(provider: Provider, consecutiveFailures: Int) {
        logger.error(
            "ALERT: Provider ${provider.name} is UNHEALTHY " +
            "($consecutiveFailures consecutive failures)"
        )
        // In production, send alert to PagerDuty, Slack, etc.
    }

    /**
     * Alert when provider recovers
     * 
     * @param provider Provider
     */
    private fun alertProviderRecovered(provider: Provider) {
        logger.info("Provider ${provider.name} has RECOVERED and is now HEALTHY")
        // In production, send recovery notification
    }

    /**
     * Get current health status for a provider
     * 
     * @param provider Provider
     * @return Current health status
     */
    fun getHealthStatus(provider: Provider): ProviderHealthStatus {
        return healthStatus[provider]!!
    }

    /**
     * Get health status for all providers
     * 
     * @return Map of provider to health status
     */
    fun getAllHealthStatus(): Map<Provider, ProviderHealthStatus> {
        return healthStatus.toMap()
    }

    /**
     * Get health check history for a provider
     * 
     * @param provider Provider
     * @param limit Maximum number of results (default: 10)
     * @return List of recent health check results
     */
    fun getHealthHistory(provider: Provider, limit: Int = 10): List<HealthCheckResult> {
        val history = healthHistory[provider] ?: return emptyList()
        return history.takeLast(limit)
    }

    /**
     * Check if provider is healthy
     * 
     * @param provider Provider
     * @return true if healthy, false otherwise
     */
    fun isProviderHealthy(provider: Provider): Boolean {
        return healthStatus[provider]?.status == HealthStatus.HEALTHY
    }

    /**
     * Check if provider is available (healthy or degraded)
     * 
     * @param provider Provider
     * @return true if available, false otherwise
     */
    fun isProviderAvailable(provider: Provider): Boolean {
        val status = healthStatus[provider]?.status
        return status == HealthStatus.HEALTHY || status == HealthStatus.DEGRADED
    }

    /**
     * Get provider uptime percentage
     * 
     * @param provider Provider
     * @param minutes Time window in minutes (default: 60)
     * @return Uptime percentage (0-100)
     */
    fun getProviderUptime(provider: Provider, minutes: Int = 60): Double {
        val history = healthHistory[provider] ?: return 0.0
        
        val cutoffTime = Instant.now().minusSeconds(minutes * 60L)
        val recentChecks = history.filter { it.timestamp.isAfter(cutoffTime) }
        
        if (recentChecks.isEmpty()) return 0.0
        
        val healthyChecks = recentChecks.count { 
            it.status == HealthStatus.HEALTHY || it.status == HealthStatus.DEGRADED 
        }
        
        return (healthyChecks.toDouble() / recentChecks.size) * 100.0
    }

    /**
     * Get average response time for a provider
     * 
     * @param provider Provider
     * @param minutes Time window in minutes (default: 60)
     * @return Average response time in milliseconds
     */
    fun getAverageResponseTime(provider: Provider, minutes: Int = 60): Long {
        val history = healthHistory[provider] ?: return 0L
        
        val cutoffTime = Instant.now().minusSeconds(minutes * 60L)
        val recentChecks = history.filter { it.timestamp.isAfter(cutoffTime) }
        
        if (recentChecks.isEmpty()) return 0L
        
        return recentChecks.map { it.responseTimeMs }.average().toLong()
    }

    /**
     * Force health check for a provider
     * 
     * Useful for manual testing or immediate status update.
     * 
     * @param provider Provider
     * @return Health check result
     */
    fun forceHealthCheck(provider: Provider): HealthCheckResult {
        logger.info("Forcing health check for provider: ${provider.name}")
        return checkProviderHealth(provider)
    }

    /**
     * Reset health status for a provider
     * 
     * Useful for testing or after manual intervention.
     * 
     * @param provider Provider
     */
    fun resetHealthStatus(provider: Provider) {
        logger.info("Resetting health status for provider: ${provider.name}")
        
        healthStatus[provider] = ProviderHealthStatus(
            provider = provider,
            status = HealthStatus.UNKNOWN,
            lastCheckTime = Instant.now(),
            consecutiveFailures = 0
        )
        
        healthHistory[provider]?.clear()
    }
}

/**
 * Health Status enum
 */
enum class HealthStatus {
    HEALTHY,    // Provider is responding normally
    DEGRADED,   // Provider is slow but functional
    UNHEALTHY,  // Provider is not responding or has errors
    UNKNOWN     // Health status not yet determined
}

/**
 * Provider Health Status
 * 
 * Current health status of a provider.
 */
data class ProviderHealthStatus(
    val provider: Provider,
    val status: HealthStatus,
    val lastCheckTime: Instant,
    val consecutiveFailures: Int,
    val lastResponseTimeMs: Long? = null
) {
    /**
     * Check if provider is healthy
     */
    fun isHealthy(): Boolean = status == HealthStatus.HEALTHY

    /**
     * Check if provider is available (healthy or degraded)
     */
    fun isAvailable(): Boolean = status == HealthStatus.HEALTHY || status == HealthStatus.DEGRADED

    /**
     * Check if provider is unhealthy
     */
    fun isUnhealthy(): Boolean = status == HealthStatus.UNHEALTHY

    /**
     * Get health score (0-100)
     */
    fun getHealthScore(): Int {
        return when (status) {
            HealthStatus.HEALTHY -> 100
            HealthStatus.DEGRADED -> 50
            HealthStatus.UNHEALTHY -> 0
            HealthStatus.UNKNOWN -> 0
        }
    }
}

/**
 * Health Check Result
 * 
 * Result of a single health check.
 */
data class HealthCheckResult(
    val provider: Provider,
    val status: HealthStatus,
    val responseTimeMs: Long,
    val timestamp: Instant,
    val errorMessage: String? = null
) {
    /**
     * Check if health check was successful
     */
    fun isSuccessful(): Boolean = status != HealthStatus.UNHEALTHY

    /**
     * Check if response time is acceptable (< 1s)
     */
    fun isResponseTimeAcceptable(): Boolean = responseTimeMs < 1000
}


