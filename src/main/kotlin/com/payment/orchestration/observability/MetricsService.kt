package com.payment.orchestration.observability

import com.payment.orchestration.domain.model.PaymentMethod
import com.payment.orchestration.domain.model.PaymentStatus
import com.payment.orchestration.domain.model.Provider
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Metrics Service
 * 
 * Provides comprehensive observability for the payment orchestration system.
 * 
 * Metrics Categories:
 * 1. Golden Signals (Latency, Traffic, Errors, Saturation)
 * 2. Business Metrics (Success rate, Revenue, Provider performance)
 * 3. Technical Metrics (Circuit breaker, Retries, Cache hits)
 * 
 * Integration:
 * - Prometheus for metrics collection
 * - Grafana for visualization
 * - AlertManager for alerting
 * 
 * Best Practices:
 * - Use tags for dimensional metrics
 * - Keep cardinality low (avoid user IDs in tags)
 * - Use histograms for latency percentiles
 * - Use counters for rates
 * - Use gauges for current values
 * 
 * @property meterRegistry Micrometer meter registry
 */
@Service
class MetricsService(private val meterRegistry: MeterRegistry) {

    // ========================================
    // Golden Signals - Latency
    // ========================================

    /**
     * Record payment processing latency
     * 
     * Tracks end-to-end payment processing time.
     * Used for: P50, P95, P99 latency monitoring
     */
    fun recordPaymentLatency(
        provider: Provider,
        paymentMethod: PaymentMethod,
        status: PaymentStatus,
        duration: Duration
    ) {
        Timer.builder("payment.processing.duration")
            .description("Payment processing duration")
            .tag("provider", provider.name)
            .tag("payment_method", paymentMethod.name)
            .tag("status", status.name)
            .register(meterRegistry)
            .record(duration)
    }

    /**
     * Record provider API call latency
     * 
     * Tracks individual provider API response times.
     */
    fun recordProviderApiLatency(
        provider: Provider,
        operation: String,
        duration: Duration
    ) {
        Timer.builder("provider.api.duration")
            .description("Provider API call duration")
            .tag("provider", provider.name)
            .tag("operation", operation)
            .register(meterRegistry)
            .record(duration)
    }

    /**
     * Record database query latency
     */
    fun recordDatabaseLatency(
        operation: String,
        duration: Duration
    ) {
        Timer.builder("database.query.duration")
            .description("Database query duration")
            .tag("operation", operation)
            .register(meterRegistry)
            .record(duration)
    }

    // ========================================
    // Golden Signals - Traffic
    // ========================================

    /**
     * Increment payment request counter
     * 
     * Tracks total payment requests (rate).
     */
    fun incrementPaymentRequests(
        provider: Provider,
        paymentMethod: PaymentMethod
    ) {
        Counter.builder("payment.requests.total")
            .description("Total payment requests")
            .tag("provider", provider.name)
            .tag("payment_method", paymentMethod.name)
            .register(meterRegistry)
            .increment()
    }

    /**
     * Increment payment completion counter
     */
    fun incrementPaymentCompletions(
        provider: Provider,
        paymentMethod: PaymentMethod,
        status: PaymentStatus
    ) {
        Counter.builder("payment.completions.total")
            .description("Total payment completions")
            .tag("provider", provider.name)
            .tag("payment_method", paymentMethod.name)
            .tag("status", status.name)
            .register(meterRegistry)
            .increment()
    }

    // ========================================
    // Golden Signals - Errors
    // ========================================

    /**
     * Increment error counter
     * 
     * Tracks all errors by type and provider.
     */
    fun incrementErrors(
        provider: Provider,
        errorType: String,
        errorCode: String? = null
    ) {
        Counter.builder("payment.errors.total")
            .description("Total payment errors")
            .tag("provider", provider.name)
            .tag("error_type", errorType)
            .tag("error_code", errorCode ?: "unknown")
            .register(meterRegistry)
            .increment()
    }

    /**
     * Increment provider failure counter
     */
    fun incrementProviderFailures(
        provider: Provider,
        failureReason: String
    ) {
        Counter.builder("provider.failures.total")
            .description("Total provider failures")
            .tag("provider", provider.name)
            .tag("reason", failureReason)
            .register(meterRegistry)
            .increment()
    }

    /**
     * Increment timeout counter
     */
    fun incrementTimeouts(
        provider: Provider,
        operation: String
    ) {
        Counter.builder("payment.timeouts.total")
            .description("Total payment timeouts")
            .tag("provider", provider.name)
            .tag("operation", operation)
            .register(meterRegistry)
            .increment()
    }

    // ========================================
    // Golden Signals - Saturation
    // ========================================

    /**
     * Record active payment count
     * 
     * Tracks concurrent payment processing.
     */
    fun recordActivePayments(count: Int) {
        meterRegistry.gauge(
            "payment.active.count",
            emptyList(),
            count
        )
    }

    /**
     * Record queue depth
     */
    fun recordQueueDepth(queueName: String, depth: Int) {
        meterRegistry.gauge(
            "payment.queue.depth",
            listOf(io.micrometer.core.instrument.Tag.of("queue", queueName)),
            depth
        )
    }

    /**
     * Record thread pool utilization
     */
    fun recordThreadPoolUtilization(poolName: String, utilization: Double) {
        meterRegistry.gauge(
            "payment.threadpool.utilization",
            listOf(io.micrometer.core.instrument.Tag.of("pool", poolName)),
            utilization
        )
    }

    // ========================================
    // Business Metrics
    // ========================================

    /**
     * Record payment amount
     * 
     * Tracks total payment volume (revenue).
     */
    fun recordPaymentAmount(
        provider: Provider,
        paymentMethod: PaymentMethod,
        currency: String,
        amount: Long
    ) {
        Counter.builder("payment.amount.total")
            .description("Total payment amount")
            .tag("provider", provider.name)
            .tag("payment_method", paymentMethod.name)
            .tag("currency", currency)
            .baseUnit("cents")
            .register(meterRegistry)
            .increment(amount.toDouble())
    }

    /**
     * Increment successful payment counter
     */
    fun incrementSuccessfulPayments(
        provider: Provider,
        paymentMethod: PaymentMethod
    ) {
        Counter.builder("payment.success.total")
            .description("Total successful payments")
            .tag("provider", provider.name)
            .tag("payment_method", paymentMethod.name)
            .register(meterRegistry)
            .increment()
    }

    /**
     * Increment failed payment counter
     */
    fun incrementFailedPayments(
        provider: Provider,
        paymentMethod: PaymentMethod,
        failureReason: String
    ) {
        Counter.builder("payment.failed.total")
            .description("Total failed payments")
            .tag("provider", provider.name)
            .tag("payment_method", paymentMethod.name)
            .tag("reason", failureReason)
            .register(meterRegistry)
            .increment()
    }

    /**
     * Record provider success rate
     * 
     * Calculated metric: successful / total requests
     */
    fun recordProviderSuccessRate(provider: Provider, rate: Double) {
        meterRegistry.gauge(
            "provider.success.rate",
            listOf(io.micrometer.core.instrument.Tag.of("provider", provider.name)),
            rate
        )
    }

    // ========================================
    // Technical Metrics - Circuit Breaker
    // ========================================

    /**
     * Increment circuit breaker state change
     */
    fun incrementCircuitBreakerStateChange(
        provider: Provider,
        fromState: String,
        toState: String
    ) {
        Counter.builder("circuit_breaker.state_change.total")
            .description("Circuit breaker state changes")
            .tag("provider", provider.name)
            .tag("from_state", fromState)
            .tag("to_state", toState)
            .register(meterRegistry)
            .increment()
    }

    /**
     * Record circuit breaker state
     */
    fun recordCircuitBreakerState(provider: Provider, state: String) {
        meterRegistry.gauge(
            "circuit_breaker.state",
            listOf(
                io.micrometer.core.instrument.Tag.of("provider", provider.name),
                io.micrometer.core.instrument.Tag.of("state", state)
            ),
            if (state == "OPEN") 1.0 else 0.0
        )
    }

    /**
     * Increment circuit breaker rejection
     */
    fun incrementCircuitBreakerRejections(provider: Provider) {
        Counter.builder("circuit_breaker.rejections.total")
            .description("Circuit breaker rejections")
            .tag("provider", provider.name)
            .register(meterRegistry)
            .increment()
    }

    // ========================================
    // Technical Metrics - Retries
    // ========================================

    /**
     * Increment retry attempt counter
     */
    fun incrementRetryAttempts(
        provider: Provider,
        attemptNumber: Int
    ) {
        Counter.builder("payment.retry.attempts.total")
            .description("Payment retry attempts")
            .tag("provider", provider.name)
            .tag("attempt", attemptNumber.toString())
            .register(meterRegistry)
            .increment()
    }

    /**
     * Increment retry success counter
     */
    fun incrementRetrySuccess(provider: Provider) {
        Counter.builder("payment.retry.success.total")
            .description("Successful payment retries")
            .tag("provider", provider.name)
            .register(meterRegistry)
            .increment()
    }

    /**
     * Increment retry exhausted counter
     */
    fun incrementRetryExhausted(provider: Provider) {
        Counter.builder("payment.retry.exhausted.total")
            .description("Exhausted payment retries")
            .tag("provider", provider.name)
            .register(meterRegistry)
            .increment()
    }

    // ========================================
    // Technical Metrics - Idempotency
    // ========================================

    /**
     * Increment idempotency hit counter
     */
    fun incrementIdempotencyHits() {
        Counter.builder("payment.idempotency.hits.total")
            .description("Idempotency cache hits")
            .register(meterRegistry)
            .increment()
    }

    /**
     * Increment idempotency miss counter
     */
    fun incrementIdempotencyMisses() {
        Counter.builder("payment.idempotency.misses.total")
            .description("Idempotency cache misses")
            .register(meterRegistry)
            .increment()
    }

    /**
     * Increment duplicate request counter
     */
    fun incrementDuplicateRequests(provider: Provider) {
        Counter.builder("payment.duplicate.requests.total")
            .description("Duplicate payment requests")
            .tag("provider", provider.name)
            .register(meterRegistry)
            .increment()
    }

    // ========================================
    // Technical Metrics - Routing
    // ========================================

    /**
     * Increment routing decision counter
     */
    fun incrementRoutingDecisions(
        selectedProvider: Provider,
        routingReason: String
    ) {
        Counter.builder("payment.routing.decisions.total")
            .description("Payment routing decisions")
            .tag("provider", selectedProvider.name)
            .tag("reason", routingReason)
            .register(meterRegistry)
            .increment()
    }

    /**
     * Increment failover counter
     */
    fun incrementFailovers(
        fromProvider: Provider,
        toProvider: Provider
    ) {
        Counter.builder("payment.failover.total")
            .description("Payment provider failovers")
            .tag("from_provider", fromProvider.name)
            .tag("to_provider", toProvider.name)
            .register(meterRegistry)
            .increment()
    }

    // ========================================
    // Technical Metrics - Webhooks
    // ========================================

    /**
     * Increment webhook received counter
     */
    fun incrementWebhooksReceived(provider: Provider) {
        Counter.builder("webhook.received.total")
            .description("Webhooks received")
            .tag("provider", provider.name)
            .register(meterRegistry)
            .increment()
    }

    /**
     * Increment webhook verification failure
     */
    fun incrementWebhookVerificationFailures(provider: Provider, reason: String) {
        Counter.builder("webhook.verification.failures.total")
            .description("Webhook verification failures")
            .tag("provider", provider.name)
            .tag("reason", reason)
            .register(meterRegistry)
            .increment()
    }

    /**
     * Record webhook processing latency
     */
    fun recordWebhookProcessingLatency(provider: Provider, duration: Duration) {
        Timer.builder("webhook.processing.duration")
            .description("Webhook processing duration")
            .tag("provider", provider.name)
            .register(meterRegistry)
            .record(duration)
    }

    // ========================================
    // Health Metrics
    // ========================================

    /**
     * Record provider health status
     */
    fun recordProviderHealth(provider: Provider, isHealthy: Boolean) {
        meterRegistry.gauge(
            "provider.health.status",
            listOf(io.micrometer.core.instrument.Tag.of("provider", provider.name)),
            if (isHealthy) 1.0 else 0.0
        )
    }

    /**
     * Record provider uptime percentage
     */
    fun recordProviderUptime(provider: Provider, uptimePercentage: Double) {
        meterRegistry.gauge(
            "provider.uptime.percentage",
            listOf(io.micrometer.core.instrument.Tag.of("provider", provider.name)),
            uptimePercentage
        )
    }

    /**
     * Record provider response time
     */
    fun recordProviderResponseTime(provider: Provider, responseTimeMs: Long) {
        meterRegistry.gauge(
            "provider.response_time.ms",
            listOf(io.micrometer.core.instrument.Tag.of("provider", provider.name)),
            responseTimeMs.toDouble()
        )
    }

    // ========================================
    // Utility Methods
    // ========================================

    /**
     * Create a timer for measuring duration
     * 
     * Usage:
     * ```
     * val timer = metricsService.startTimer()
     * // ... do work ...
     * metricsService.recordPaymentLatency(provider, method, status, timer.stop())
     * ```
     */
    fun startTimer(): Timer.Sample {
        return Timer.start(meterRegistry)
    }

    /**
     * Stop timer and get duration
     */
    fun stopTimer(sample: Timer.Sample): Duration {
        val nanos = sample.stop(
            Timer.builder("temp.timer")
                .register(meterRegistry)
        )
        return Duration.ofNanos(nanos.toLong())
    }
}


