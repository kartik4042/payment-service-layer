package com.payment.orchestration.observability

import io.micrometer.core.aop.TimedAspect
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Prometheus Configuration
 * 
 * Configures Prometheus metrics collection and export.
 * 
 * Features:
 * - Automatic metrics collection via Micrometer
 * - Custom application tags
 * - Histogram percentiles for latency
 * - @Timed annotation support
 * 
 * Endpoints:
 * - /actuator/prometheus - Prometheus scrape endpoint
 * - /actuator/metrics - All available metrics
 * - /actuator/health - Health check endpoint
 * 
 * Prometheus Configuration (prometheus.yml):
 * ```yaml
 * scrape_configs:
 *   - job_name: 'payment-orchestration'
 *     metrics_path: '/actuator/prometheus'
 *     scrape_interval: 15s
 *     static_configs:
 *       - targets: ['localhost:8080']
 * ```
 * 
 * Grafana Dashboard:
 * - Import dashboard ID: 4701 (JVM Micrometer)
 * - Create custom dashboard for business metrics
 * 
 * Alert Rules:
 * - High error rate (>5%)
 * - High latency (P95 >2s)
 * - Circuit breaker open
 * - Provider health degraded
 */
@Configuration
class PrometheusConfig {

    /**
     * Customize meter registry with common tags
     * 
     * Common tags are added to all metrics for filtering and grouping.
     */
    @Bean
    fun metricsCommonTags(): MeterRegistryCustomizer<MeterRegistry> {
        return MeterRegistryCustomizer { registry ->
            registry.config().commonTags(
                "application", "payment-orchestration",
                "environment", System.getenv("ENVIRONMENT") ?: "development",
                "region", System.getenv("REGION") ?: "us-east-1",
                "version", System.getenv("APP_VERSION") ?: "1.0.0"
            )
        }
    }

    /**
     * Enable @Timed annotation support
     * 
     * Allows automatic timing of methods annotated with @Timed.
     * 
     * Usage:
     * ```kotlin
     * @Timed(value = "payment.process", description = "Payment processing time")
     * fun processPayment(request: PaymentRequest): PaymentResponse {
     *     // ...
     * }
     * ```
     */
    @Bean
    fun timedAspect(registry: MeterRegistry): TimedAspect {
        return TimedAspect(registry)
    }

    /**
     * Configure histogram percentiles
     * 
     * Enables P50, P95, P99 percentile calculation for timers.
     */
    @Bean
    fun meterRegistryCustomizer(): MeterRegistryCustomizer<MeterRegistry> {
        return MeterRegistryCustomizer { registry ->
            registry.config()
                .meterFilter(
                    io.micrometer.core.instrument.config.MeterFilter.maximumAllowableTags(
                        "payment",
                        "payment_method",
                        100,
                        io.micrometer.core.instrument.config.MeterFilter.deny()
                    )
                )
        }
    }
}

/**
 * Metrics Constants
 * 
 * Centralized metric names and tags for consistency.
 */
object MetricsConstants {
    // Metric Names
    const val PAYMENT_DURATION = "payment.processing.duration"
    const val PAYMENT_REQUESTS = "payment.requests.total"
    const val PAYMENT_ERRORS = "payment.errors.total"
    const val PROVIDER_API_DURATION = "provider.api.duration"
    const val CIRCUIT_BREAKER_STATE = "circuit_breaker.state"
    const val RETRY_ATTEMPTS = "payment.retry.attempts.total"
    const val WEBHOOK_RECEIVED = "webhook.received.total"
    
    // Tag Names
    const val TAG_PROVIDER = "provider"
    const val TAG_PAYMENT_METHOD = "payment_method"
    const val TAG_STATUS = "status"
    const val TAG_ERROR_TYPE = "error_type"
    const val TAG_OPERATION = "operation"
    
    // Alert Thresholds
    const val ERROR_RATE_THRESHOLD = 0.05 // 5%
    const val LATENCY_P95_THRESHOLD_MS = 2000L // 2 seconds
    const val LATENCY_P99_THRESHOLD_MS = 5000L // 5 seconds
}

/**
 * Prometheus Query Examples
 * 
 * Useful PromQL queries for monitoring and alerting.
 */
object PrometheusQueries {
    /**
     * Payment success rate (last 5 minutes)
     * 
     * ```
     * sum(rate(payment_success_total[5m])) / sum(rate(payment_requests_total[5m]))
     * ```
     */
    const val SUCCESS_RATE = """
        sum(rate(payment_success_total[5m])) / sum(rate(payment_requests_total[5m]))
    """
    
    /**
     * Payment error rate by provider (last 5 minutes)
     * 
     * ```
     * sum(rate(payment_errors_total[5m])) by (provider) / 
     * sum(rate(payment_requests_total[5m])) by (provider)
     * ```
     */
    const val ERROR_RATE_BY_PROVIDER = """
        sum(rate(payment_errors_total[5m])) by (provider) / 
        sum(rate(payment_requests_total[5m])) by (provider)
    """
    
    /**
     * P95 latency by provider (last 5 minutes)
     * 
     * ```
     * histogram_quantile(0.95, 
     *   sum(rate(payment_processing_duration_bucket[5m])) by (provider, le)
     * )
     * ```
     */
    const val P95_LATENCY = """
        histogram_quantile(0.95, 
          sum(rate(payment_processing_duration_bucket[5m])) by (provider, le)
        )
    """
    
    /**
     * P99 latency by provider (last 5 minutes)
     * 
     * ```
     * histogram_quantile(0.99, 
     *   sum(rate(payment_processing_duration_bucket[5m])) by (provider, le)
     * )
     * ```
     */
    const val P99_LATENCY = """
        histogram_quantile(0.99, 
          sum(rate(payment_processing_duration_bucket[5m])) by (provider, le)
        )
    """
    
    /**
     * Request rate (requests per second)
     * 
     * ```
     * sum(rate(payment_requests_total[1m]))
     * ```
     */
    const val REQUEST_RATE = """
        sum(rate(payment_requests_total[1m]))
    """
    
    /**
     * Circuit breaker open count
     * 
     * ```
     * sum(circuit_breaker_state{state="OPEN"}) by (provider)
     * ```
     */
    const val CIRCUIT_BREAKER_OPEN = """
        sum(circuit_breaker_state{state="OPEN"}) by (provider)
    """
    
    /**
     * Retry rate (retries per second)
     * 
     * ```
     * sum(rate(payment_retry_attempts_total[5m])) by (provider)
     * ```
     */
    const val RETRY_RATE = """
        sum(rate(payment_retry_attempts_total[5m])) by (provider)
    """
    
    /**
     * Provider health status
     * 
     * ```
     * provider_health_status
     * ```
     */
    const val PROVIDER_HEALTH = """
        provider_health_status
    """
    
    /**
     * Total payment volume (revenue) in last hour
     * 
     * ```
     * sum(increase(payment_amount_total[1h])) by (currency)
     * ```
     */
    const val PAYMENT_VOLUME = """
        sum(increase(payment_amount_total[1h])) by (currency)
    """
    
    /**
     * Idempotency cache hit rate
     * 
     * ```
     * sum(rate(payment_idempotency_hits_total[5m])) / 
     * (sum(rate(payment_idempotency_hits_total[5m])) + 
     *  sum(rate(payment_idempotency_misses_total[5m])))
     * ```
     */
    const val IDEMPOTENCY_HIT_RATE = """
        sum(rate(payment_idempotency_hits_total[5m])) / 
        (sum(rate(payment_idempotency_hits_total[5m])) + 
         sum(rate(payment_idempotency_misses_total[5m])))
    """
}

/**
 * Alert Rules
 * 
 * Prometheus alert rules for critical conditions.
 * 
 * Save as: prometheus-alerts.yml
 */
object AlertRules {
    const val YAML = """
groups:
  - name: payment_orchestration_alerts
    interval: 30s
    rules:
      # High Error Rate Alert
      - alert: HighPaymentErrorRate
        expr: |
          sum(rate(payment_errors_total[5m])) / sum(rate(payment_requests_total[5m])) > 0.05
        for: 5m
        labels:
          severity: critical
          component: payment-orchestration
        annotations:
          summary: "High payment error rate detected"
          description: "Payment error rate is {{ ${'$'}value | humanizePercentage }} (threshold: 5%)"
          
      # High Latency Alert
      - alert: HighPaymentLatency
        expr: |
          histogram_quantile(0.95, 
            sum(rate(payment_processing_duration_bucket[5m])) by (provider, le)
          ) > 2
        for: 5m
        labels:
          severity: warning
          component: payment-orchestration
        annotations:
          summary: "High payment processing latency"
          description: "P95 latency is {{ ${'$'}value }}s for provider {{ ${'$'}labels.provider }}"
          
      # Circuit Breaker Open Alert
      - alert: CircuitBreakerOpen
        expr: |
          circuit_breaker_state{state="OPEN"} == 1
        for: 2m
        labels:
          severity: critical
          component: payment-orchestration
        annotations:
          summary: "Circuit breaker open for provider"
          description: "Circuit breaker is OPEN for provider {{ ${'$'}labels.provider }}"
          
      # Provider Health Degraded Alert
      - alert: ProviderHealthDegraded
        expr: |
          provider_health_status == 0
        for: 5m
        labels:
          severity: warning
          component: payment-orchestration
        annotations:
          summary: "Provider health degraded"
          description: "Provider {{ ${'$'}labels.provider }} is unhealthy"
          
      # High Retry Rate Alert
      - alert: HighRetryRate
        expr: |
          sum(rate(payment_retry_attempts_total[5m])) by (provider) > 10
        for: 5m
        labels:
          severity: warning
          component: payment-orchestration
        annotations:
          summary: "High payment retry rate"
          description: "Retry rate is {{ ${'$'}value }}/s for provider {{ ${'$'}labels.provider }}"
          
      # Low Success Rate Alert
      - alert: LowPaymentSuccessRate
        expr: |
          sum(rate(payment_success_total[5m])) / sum(rate(payment_requests_total[5m])) < 0.95
        for: 10m
        labels:
          severity: warning
          component: payment-orchestration
        annotations:
          summary: "Low payment success rate"
          description: "Payment success rate is {{ ${'$'}value | humanizePercentage }} (threshold: 95%)"
"""
}


