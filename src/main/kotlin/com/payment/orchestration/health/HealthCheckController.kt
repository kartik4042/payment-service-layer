package com.payment.orchestration.health

import com.payment.orchestration.domain.model.Provider
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Health Check Controller
 * 
 * Exposes health check endpoints for monitoring provider availability.
 * 
 * Endpoints:
 * - GET /api/v1/health - Overall system health
 * - GET /api/v1/health/providers - All provider health status
 * - GET /api/v1/health/providers/{provider} - Specific provider health
 * - POST /api/v1/health/providers/{provider}/check - Force health check
 * 
 * @property healthCheckService Provider health check service
 */
@RestController
@RequestMapping("/api/v1/health")
class HealthCheckController(
    private val healthCheckService: ProviderHealthCheckService
) {

    /**
     * Get overall system health
     * 
     * Returns aggregated health status of all providers.
     * 
     * @return System health response
     */
    @GetMapping
    fun getSystemHealth(): ResponseEntity<SystemHealthResponse> {
        val allStatus = healthCheckService.getAllHealthStatus()
        
        val overallStatus = when {
            allStatus.values.all { it.isHealthy() } -> "HEALTHY"
            allStatus.values.any { it.isAvailable() } -> "DEGRADED"
            else -> "UNHEALTHY"
        }
        
        val response = SystemHealthResponse(
            status = overallStatus,
            providers = allStatus.mapValues { (_, status) ->
                ProviderHealthResponse(
                    status = status.status.name,
                    lastCheckTime = status.lastCheckTime.toString(),
                    consecutiveFailures = status.consecutiveFailures,
                    lastResponseTimeMs = status.lastResponseTimeMs
                )
            }
        )
        
        return ResponseEntity.ok(response)
    }

    /**
     * Get health status for all providers
     * 
     * @return Map of provider to health status
     */
    @GetMapping("/providers")
    fun getAllProviderHealth(): ResponseEntity<Map<String, ProviderHealthResponse>> {
        val allStatus = healthCheckService.getAllHealthStatus()
        
        val response = allStatus.mapKeys { it.key.name }
            .mapValues { (_, status) ->
                ProviderHealthResponse(
                    status = status.status.name,
                    lastCheckTime = status.lastCheckTime.toString(),
                    consecutiveFailures = status.consecutiveFailures,
                    lastResponseTimeMs = status.lastResponseTimeMs,
                    uptime = healthCheckService.getProviderUptime(status.provider),
                    averageResponseTime = healthCheckService.getAverageResponseTime(status.provider)
                )
            }
        
        return ResponseEntity.ok(response)
    }

    /**
     * Get health status for a specific provider
     * 
     * @param provider Provider name
     * @return Provider health status
     */
    @GetMapping("/providers/{provider}")
    fun getProviderHealth(
        @PathVariable provider: String
    ): ResponseEntity<ProviderHealthDetailResponse> {
        val providerEnum = try {
            Provider.valueOf(provider.uppercase())
        } catch (e: IllegalArgumentException) {
            return ResponseEntity.badRequest().build()
        }
        
        val status = healthCheckService.getHealthStatus(providerEnum)
        val history = healthCheckService.getHealthHistory(providerEnum, limit = 20)
        
        val response = ProviderHealthDetailResponse(
            provider = providerEnum.name,
            status = status.status.name,
            lastCheckTime = status.lastCheckTime.toString(),
            consecutiveFailures = status.consecutiveFailures,
            lastResponseTimeMs = status.lastResponseTimeMs,
            uptime = healthCheckService.getProviderUptime(providerEnum),
            averageResponseTime = healthCheckService.getAverageResponseTime(providerEnum),
            healthScore = status.getHealthScore(),
            recentChecks = history.map { check ->
                HealthCheckSummary(
                    status = check.status.name,
                    responseTimeMs = check.responseTimeMs,
                    timestamp = check.timestamp.toString(),
                    errorMessage = check.errorMessage
                )
            }
        )
        
        return ResponseEntity.ok(response)
    }

    /**
     * Force health check for a specific provider
     * 
     * @param provider Provider name
     * @return Health check result
     */
    @PostMapping("/providers/{provider}/check")
    fun forceHealthCheck(
        @PathVariable provider: String
    ): ResponseEntity<HealthCheckResultResponse> {
        val providerEnum = try {
            Provider.valueOf(provider.uppercase())
        } catch (e: IllegalArgumentException) {
            return ResponseEntity.badRequest().build()
        }
        
        val result = healthCheckService.forceHealthCheck(providerEnum)
        
        val response = HealthCheckResultResponse(
            provider = providerEnum.name,
            status = result.status.name,
            responseTimeMs = result.responseTimeMs,
            timestamp = result.timestamp.toString(),
            errorMessage = result.errorMessage,
            successful = result.isSuccessful()
        )
        
        return ResponseEntity.ok(response)
    }

    /**
     * Reset health status for a provider
     * 
     * Admin endpoint for manual intervention.
     * 
     * @param provider Provider name
     * @return Success response
     */
    @PostMapping("/providers/{provider}/reset")
    fun resetProviderHealth(
        @PathVariable provider: String
    ): ResponseEntity<Map<String, String>> {
        val providerEnum = try {
            Provider.valueOf(provider.uppercase())
        } catch (e: IllegalArgumentException) {
            return ResponseEntity.badRequest().build()
        }
        
        healthCheckService.resetHealthStatus(providerEnum)
        
        return ResponseEntity.ok(mapOf(
            "message" to "Health status reset for provider: ${providerEnum.name}",
            "provider" to providerEnum.name
        ))
    }
}

/**
 * System Health Response
 */
data class SystemHealthResponse(
    val status: String,
    val providers: Map<Provider, ProviderHealthResponse>
)

/**
 * Provider Health Response
 */
data class ProviderHealthResponse(
    val status: String,
    val lastCheckTime: String,
    val consecutiveFailures: Int,
    val lastResponseTimeMs: Long? = null,
    val uptime: Double? = null,
    val averageResponseTime: Long? = null
)

/**
 * Provider Health Detail Response
 */
data class ProviderHealthDetailResponse(
    val provider: String,
    val status: String,
    val lastCheckTime: String,
    val consecutiveFailures: Int,
    val lastResponseTimeMs: Long?,
    val uptime: Double,
    val averageResponseTime: Long,
    val healthScore: Int,
    val recentChecks: List<HealthCheckSummary>
)

/**
 * Health Check Summary
 */
data class HealthCheckSummary(
    val status: String,
    val responseTimeMs: Long,
    val timestamp: String,
    val errorMessage: String?
)

/**
 * Health Check Result Response
 */
data class HealthCheckResultResponse(
    val provider: String,
    val status: String,
    val responseTimeMs: Long,
    val timestamp: String,
    val errorMessage: String?,
    val successful: Boolean
)


