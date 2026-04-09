package com.payment.orchestration.observability

import com.payment.orchestration.domain.model.PaymentMethod
import com.payment.orchestration.domain.model.PaymentStatus
import com.payment.orchestration.domain.model.Provider
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested
import java.time.Duration

/**
 * Metrics Service Tests
 * 
 * Test Coverage:
 * - Golden Signals (Latency, Traffic, Errors, Saturation)
 * - Business Metrics
 * - Technical Metrics (Circuit Breaker, Retries, Idempotency)
 * - Routing Metrics
 * - Webhook Metrics
 * - Health Metrics
 * - Timer utilities
 */
@DisplayName("Metrics Service Tests")
class MetricsServiceTest {

    private lateinit var meterRegistry: MeterRegistry
    private lateinit var metricsService: MetricsService

    @BeforeEach
    fun setup() {
        meterRegistry = SimpleMeterRegistry()
        metricsService = MetricsService(meterRegistry)
    }

    @Nested
    @DisplayName("Golden Signals - Latency Tests")
    inner class LatencyTests {

        @Test
        @DisplayName("Should record payment processing latency")
        fun testRecordPaymentLatency() {
            // Given
            val provider = Provider.PROVIDER_A
            val paymentMethod = PaymentMethod.CARD
            val status = PaymentStatus.SUCCESS
            val duration = Duration.ofMillis(500)

            // When
            metricsService.recordPaymentLatency(provider, paymentMethod, status, duration)

            // Then
            val timer = meterRegistry.find("payment.processing.duration")
                .tag("provider", provider.name)
                .tag("payment_method", paymentMethod.name)
                .tag("status", status.name)
                .timer()

            assertNotNull(timer)
            assertEquals(1, timer?.count())
            assertTrue(timer?.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)!! >= 500.0)
        }

        @Test
        @DisplayName("Should record provider API latency")
        fun testRecordProviderApiLatency() {
            // Given
            val provider = Provider.PROVIDER_B
            val operation = "charge"
            val duration = Duration.ofMillis(300)

            // When
            metricsService.recordProviderApiLatency(provider, operation, duration)

            // Then
            val timer = meterRegistry.find("provider.api.duration")
                .tag("provider", provider.name)
                .tag("operation", operation)
                .timer()

            assertNotNull(timer)
            assertEquals(1, timer?.count())
        }

        @Test
        @DisplayName("Should record database latency")
        fun testRecordDatabaseLatency() {
            // Given
            val operation = "insert_payment"
            val duration = Duration.ofMillis(50)

            // When
            metricsService.recordDatabaseLatency(operation, duration)

            // Then
            val timer = meterRegistry.find("database.query.duration")
                .tag("operation", operation)
                .timer()

            assertNotNull(timer)
            assertEquals(1, timer?.count())
        }

        @Test
        @DisplayName("Should record multiple latency measurements")
        fun testMultipleLatencyMeasurements() {
            // Given
            val provider = Provider.PROVIDER_A
            val paymentMethod = PaymentMethod.CARD
            val status = PaymentStatus.SUCCESS

            // When - Record 5 measurements
            repeat(5) {
                metricsService.recordPaymentLatency(
                    provider,
                    paymentMethod,
                    status,
                    Duration.ofMillis(100 + it * 50L)
                )
            }

            // Then
            val timer = meterRegistry.find("payment.processing.duration")
                .tag("provider", provider.name)
                .timer()

            assertNotNull(timer)
            assertEquals(5, timer?.count())
        }
    }

    @Nested
    @DisplayName("Golden Signals - Traffic Tests")
    inner class TrafficTests {

        @Test
        @DisplayName("Should increment payment requests")
        fun testIncrementPaymentRequests() {
            // Given
            val provider = Provider.PROVIDER_A
            val paymentMethod = PaymentMethod.CARD

            // When
            metricsService.incrementPaymentRequests(provider, paymentMethod)
            metricsService.incrementPaymentRequests(provider, paymentMethod)

            // Then
            val counter = meterRegistry.find("payment.requests.total")
                .tag("provider", provider.name)
                .tag("payment_method", paymentMethod.name)
                .counter()

            assertNotNull(counter)
            assertEquals(2.0, counter?.count())
        }

        @Test
        @DisplayName("Should increment payment completions")
        fun testIncrementPaymentCompletions() {
            // Given
            val provider = Provider.PROVIDER_A
            val paymentMethod = PaymentMethod.CARD
            val status = PaymentStatus.SUCCESS

            // When
            metricsService.incrementPaymentCompletions(provider, paymentMethod, status)

            // Then
            val counter = meterRegistry.find("payment.completions.total")
                .tag("provider", provider.name)
                .tag("status", status.name)
                .counter()

            assertNotNull(counter)
            assertEquals(1.0, counter?.count())
        }

        @Test
        @DisplayName("Should track traffic by provider")
        fun testTrafficByProvider() {
            // When - Different providers
            metricsService.incrementPaymentRequests(Provider.PROVIDER_A, PaymentMethod.CARD)
            metricsService.incrementPaymentRequests(Provider.PROVIDER_A, PaymentMethod.CARD)
            metricsService.incrementPaymentRequests(Provider.PROVIDER_B, PaymentMethod.UPI)

            // Then
            val providerACounter = meterRegistry.find("payment.requests.total")
                .tag("provider", Provider.PROVIDER_A.name)
                .counter()
            val providerBCounter = meterRegistry.find("payment.requests.total")
                .tag("provider", Provider.PROVIDER_B.name)
                .counter()

            assertEquals(2.0, providerACounter?.count())
            assertEquals(1.0, providerBCounter?.count())
        }
    }

    @Nested
    @DisplayName("Golden Signals - Errors Tests")
    inner class ErrorsTests {

        @Test
        @DisplayName("Should increment error counter")
        fun testIncrementErrors() {
            // Given
            val provider = Provider.PROVIDER_A
            val errorType = "timeout"
            val errorCode = "TIMEOUT_ERROR"

            // When
            metricsService.incrementErrors(provider, errorType, errorCode)

            // Then
            val counter = meterRegistry.find("payment.errors.total")
                .tag("provider", provider.name)
                .tag("error_type", errorType)
                .tag("error_code", errorCode)
                .counter()

            assertNotNull(counter)
            assertEquals(1.0, counter?.count())
        }

        @Test
        @DisplayName("Should increment provider failures")
        fun testIncrementProviderFailures() {
            // Given
            val provider = Provider.PROVIDER_B
            val failureReason = "connection_refused"

            // When
            metricsService.incrementProviderFailures(provider, failureReason)

            // Then
            val counter = meterRegistry.find("provider.failures.total")
                .tag("provider", provider.name)
                .tag("reason", failureReason)
                .counter()

            assertNotNull(counter)
            assertEquals(1.0, counter?.count())
        }

        @Test
        @DisplayName("Should increment timeouts")
        fun testIncrementTimeouts() {
            // Given
            val provider = Provider.PROVIDER_A
            val operation = "charge"

            // When
            metricsService.incrementTimeouts(provider, operation)

            // Then
            val counter = meterRegistry.find("payment.timeouts.total")
                .tag("provider", provider.name)
                .tag("operation", operation)
                .counter()

            assertNotNull(counter)
            assertEquals(1.0, counter?.count())
        }

        @Test
        @DisplayName("Should track errors by type")
        fun testErrorsByType() {
            // When - Different error types
            metricsService.incrementErrors(Provider.PROVIDER_A, "timeout", "TIMEOUT")
            metricsService.incrementErrors(Provider.PROVIDER_A, "timeout", "TIMEOUT")
            metricsService.incrementErrors(Provider.PROVIDER_A, "validation", "INVALID_CARD")

            // Then
            val timeoutCounter = meterRegistry.find("payment.errors.total")
                .tag("error_type", "timeout")
                .counter()
            val validationCounter = meterRegistry.find("payment.errors.total")
                .tag("error_type", "validation")
                .counter()

            assertEquals(2.0, timeoutCounter?.count())
            assertEquals(1.0, validationCounter?.count())
        }
    }

    @Nested
    @DisplayName("Golden Signals - Saturation Tests")
    inner class SaturationTests {

        @Test
        @DisplayName("Should record active payments")
        fun testRecordActivePayments() {
            // When
            metricsService.recordActivePayments(10)

            // Then
            val gauge = meterRegistry.find("payment.active.count").gauge()
            assertNotNull(gauge)
            assertEquals(10.0, gauge?.value())
        }

        @Test
        @DisplayName("Should record queue depth")
        fun testRecordQueueDepth() {
            // Given
            val queueName = "payment-processing"
            val depth = 25

            // When
            metricsService.recordQueueDepth(queueName, depth)

            // Then
            val gauge = meterRegistry.find("payment.queue.depth")
                .tag("queue", queueName)
                .gauge()

            assertNotNull(gauge)
            assertEquals(25.0, gauge?.value())
        }

        @Test
        @DisplayName("Should record thread pool utilization")
        fun testRecordThreadPoolUtilization() {
            // Given
            val poolName = "payment-executor"
            val utilization = 0.75

            // When
            metricsService.recordThreadPoolUtilization(poolName, utilization)

            // Then
            val gauge = meterRegistry.find("payment.threadpool.utilization")
                .tag("pool", poolName)
                .gauge()

            assertNotNull(gauge)
            assertEquals(0.75, gauge?.value())
        }
    }

    @Nested
    @DisplayName("Business Metrics Tests")
    inner class BusinessMetricsTests {

        @Test
        @DisplayName("Should record payment amount")
        fun testRecordPaymentAmount() {
            // Given
            val provider = Provider.PROVIDER_A
            val paymentMethod = PaymentMethod.CARD
            val currency = "USD"
            val amount = 10000L // $100.00

            // When
            metricsService.recordPaymentAmount(provider, paymentMethod, currency, amount)

            // Then
            val counter = meterRegistry.find("payment.amount.total")
                .tag("provider", provider.name)
                .tag("currency", currency)
                .counter()

            assertNotNull(counter)
            assertEquals(10000.0, counter?.count())
        }

        @Test
        @DisplayName("Should increment successful payments")
        fun testIncrementSuccessfulPayments() {
            // Given
            val provider = Provider.PROVIDER_A
            val paymentMethod = PaymentMethod.CARD

            // When
            metricsService.incrementSuccessfulPayments(provider, paymentMethod)

            // Then
            val counter = meterRegistry.find("payment.success.total")
                .tag("provider", provider.name)
                .counter()

            assertNotNull(counter)
            assertEquals(1.0, counter?.count())
        }

        @Test
        @DisplayName("Should increment failed payments")
        fun testIncrementFailedPayments() {
            // Given
            val provider = Provider.PROVIDER_B
            val paymentMethod = PaymentMethod.UPI
            val failureReason = "insufficient_funds"

            // When
            metricsService.incrementFailedPayments(provider, paymentMethod, failureReason)

            // Then
            val counter = meterRegistry.find("payment.failed.total")
                .tag("provider", provider.name)
                .tag("reason", failureReason)
                .counter()

            assertNotNull(counter)
            assertEquals(1.0, counter?.count())
        }

        @Test
        @DisplayName("Should record provider success rate")
        fun testRecordProviderSuccessRate() {
            // Given
            val provider = Provider.PROVIDER_A
            val rate = 0.95

            // When
            metricsService.recordProviderSuccessRate(provider, rate)

            // Then
            val gauge = meterRegistry.find("provider.success.rate")
                .tag("provider", provider.name)
                .gauge()

            assertNotNull(gauge)
            assertEquals(0.95, gauge?.value())
        }

        @Test
        @DisplayName("Should track revenue by currency")
        fun testRevenueTracking() {
            // When - Different currencies
            metricsService.recordPaymentAmount(Provider.PROVIDER_A, PaymentMethod.CARD, "USD", 10000)
            metricsService.recordPaymentAmount(Provider.PROVIDER_A, PaymentMethod.CARD, "USD", 5000)
            metricsService.recordPaymentAmount(Provider.PROVIDER_B, PaymentMethod.UPI, "INR", 100000)

            // Then
            val usdCounter = meterRegistry.find("payment.amount.total")
                .tag("currency", "USD")
                .counter()
            val inrCounter = meterRegistry.find("payment.amount.total")
                .tag("currency", "INR")
                .counter()

            assertEquals(15000.0, usdCounter?.count())
            assertEquals(100000.0, inrCounter?.count())
        }
    }

    @Nested
    @DisplayName("Circuit Breaker Metrics Tests")
    inner class CircuitBreakerMetricsTests {

        @Test
        @DisplayName("Should increment circuit breaker state change")
        fun testIncrementCircuitBreakerStateChange() {
            // Given
            val provider = Provider.PROVIDER_A
            val fromState = "CLOSED"
            val toState = "OPEN"

            // When
            metricsService.incrementCircuitBreakerStateChange(provider, fromState, toState)

            // Then
            val counter = meterRegistry.find("circuit_breaker.state_change.total")
                .tag("provider", provider.name)
                .tag("from_state", fromState)
                .tag("to_state", toState)
                .counter()

            assertNotNull(counter)
            assertEquals(1.0, counter?.count())
        }

        @Test
        @DisplayName("Should record circuit breaker state")
        fun testRecordCircuitBreakerState() {
            // Given
            val provider = Provider.PROVIDER_A
            val state = "OPEN"

            // When
            metricsService.recordCircuitBreakerState(provider, state)

            // Then
            val gauge = meterRegistry.find("circuit_breaker.state")
                .tag("provider", provider.name)
                .tag("state", state)
                .gauge()

            assertNotNull(gauge)
            assertEquals(1.0, gauge?.value())
        }

        @Test
        @DisplayName("Should increment circuit breaker rejections")
        fun testIncrementCircuitBreakerRejections() {
            // Given
            val provider = Provider.PROVIDER_B

            // When
            metricsService.incrementCircuitBreakerRejections(provider)

            // Then
            val counter = meterRegistry.find("circuit_breaker.rejections.total")
                .tag("provider", provider.name)
                .counter()

            assertNotNull(counter)
            assertEquals(1.0, counter?.count())
        }
    }

    @Nested
    @DisplayName("Retry Metrics Tests")
    inner class RetryMetricsTests {

        @Test
        @DisplayName("Should increment retry attempts")
        fun testIncrementRetryAttempts() {
            // Given
            val provider = Provider.PROVIDER_A
            val attemptNumber = 2

            // When
            metricsService.incrementRetryAttempts(provider, attemptNumber)

            // Then
            val counter = meterRegistry.find("payment.retry.attempts.total")
                .tag("provider", provider.name)
                .tag("attempt", attemptNumber.toString())
                .counter()

            assertNotNull(counter)
            assertEquals(1.0, counter?.count())
        }

        @Test
        @DisplayName("Should increment retry success")
        fun testIncrementRetrySuccess() {
            // Given
            val provider = Provider.PROVIDER_A

            // When
            metricsService.incrementRetrySuccess(provider)

            // Then
            val counter = meterRegistry.find("payment.retry.success.total")
                .tag("provider", provider.name)
                .counter()

            assertNotNull(counter)
            assertEquals(1.0, counter?.count())
        }

        @Test
        @DisplayName("Should increment retry exhausted")
        fun testIncrementRetryExhausted() {
            // Given
            val provider = Provider.PROVIDER_B

            // When
            metricsService.incrementRetryExhausted(provider)

            // Then
            val counter = meterRegistry.find("payment.retry.exhausted.total")
                .tag("provider", provider.name)
                .counter()

            assertNotNull(counter)
            assertEquals(1.0, counter?.count())
        }
    }

    @Nested
    @DisplayName("Idempotency Metrics Tests")
    inner class IdempotencyMetricsTests {

        @Test
        @DisplayName("Should increment idempotency hits")
        fun testIncrementIdempotencyHits() {
            // When
            metricsService.incrementIdempotencyHits()

            // Then
            val counter = meterRegistry.find("payment.idempotency.hits.total").counter()
            assertNotNull(counter)
            assertEquals(1.0, counter?.count())
        }

        @Test
        @DisplayName("Should increment idempotency misses")
        fun testIncrementIdempotencyMisses() {
            // When
            metricsService.incrementIdempotencyMisses()

            // Then
            val counter = meterRegistry.find("payment.idempotency.misses.total").counter()
            assertNotNull(counter)
            assertEquals(1.0, counter?.count())
        }

        @Test
        @DisplayName("Should increment duplicate requests")
        fun testIncrementDuplicateRequests() {
            // Given
            val provider = Provider.PROVIDER_A

            // When
            metricsService.incrementDuplicateRequests(provider)

            // Then
            val counter = meterRegistry.find("payment.duplicate.requests.total")
                .tag("provider", provider.name)
                .counter()

            assertNotNull(counter)
            assertEquals(1.0, counter?.count())
        }
    }

    @Nested
    @DisplayName("Routing Metrics Tests")
    inner class RoutingMetricsTests {

        @Test
        @DisplayName("Should increment routing decisions")
        fun testIncrementRoutingDecisions() {
            // Given
            val selectedProvider = Provider.PROVIDER_A
            val routingReason = "country_routing_US"

            // When
            metricsService.incrementRoutingDecisions(selectedProvider, routingReason)

            // Then
            val counter = meterRegistry.find("payment.routing.decisions.total")
                .tag("provider", selectedProvider.name)
                .tag("reason", routingReason)
                .counter()

            assertNotNull(counter)
            assertEquals(1.0, counter?.count())
        }

        @Test
        @DisplayName("Should increment failovers")
        fun testIncrementFailovers() {
            // Given
            val fromProvider = Provider.PROVIDER_A
            val toProvider = Provider.PROVIDER_B

            // When
            metricsService.incrementFailovers(fromProvider, toProvider)

            // Then
            val counter = meterRegistry.find("payment.failover.total")
                .tag("from_provider", fromProvider.name)
                .tag("to_provider", toProvider.name)
                .counter()

            assertNotNull(counter)
            assertEquals(1.0, counter?.count())
        }
    }

    @Nested
    @DisplayName("Webhook Metrics Tests")
    inner class WebhookMetricsTests {

        @Test
        @DisplayName("Should increment webhooks received")
        fun testIncrementWebhooksReceived() {
            // Given
            val provider = Provider.PROVIDER_A

            // When
            metricsService.incrementWebhooksReceived(provider)

            // Then
            val counter = meterRegistry.find("webhook.received.total")
                .tag("provider", provider.name)
                .counter()

            assertNotNull(counter)
            assertEquals(1.0, counter?.count())
        }

        @Test
        @DisplayName("Should increment webhook verification failures")
        fun testIncrementWebhookVerificationFailures() {
            // Given
            val provider = Provider.PROVIDER_B
            val reason = "invalid_signature"

            // When
            metricsService.incrementWebhookVerificationFailures(provider, reason)

            // Then
            val counter = meterRegistry.find("webhook.verification.failures.total")
                .tag("provider", provider.name)
                .tag("reason", reason)
                .counter()

            assertNotNull(counter)
            assertEquals(1.0, counter?.count())
        }

        @Test
        @DisplayName("Should record webhook processing latency")
        fun testRecordWebhookProcessingLatency() {
            // Given
            val provider = Provider.PROVIDER_A
            val duration = Duration.ofMillis(100)

            // When
            metricsService.recordWebhookProcessingLatency(provider, duration)

            // Then
            val timer = meterRegistry.find("webhook.processing.duration")
                .tag("provider", provider.name)
                .timer()

            assertNotNull(timer)
            assertEquals(1, timer?.count())
        }
    }

    @Nested
    @DisplayName("Health Metrics Tests")
    inner class HealthMetricsTests {

        @Test
        @DisplayName("Should record provider health status")
        fun testRecordProviderHealth() {
            // Given
            val provider = Provider.PROVIDER_A
            val isHealthy = true

            // When
            metricsService.recordProviderHealth(provider, isHealthy)

            // Then
            val gauge = meterRegistry.find("provider.health.status")
                .tag("provider", provider.name)
                .gauge()

            assertNotNull(gauge)
            assertEquals(1.0, gauge?.value())
        }

        @Test
        @DisplayName("Should record provider uptime")
        fun testRecordProviderUptime() {
            // Given
            val provider = Provider.PROVIDER_A
            val uptimePercentage = 99.5

            // When
            metricsService.recordProviderUptime(provider, uptimePercentage)

            // Then
            val gauge = meterRegistry.find("provider.uptime.percentage")
                .tag("provider", provider.name)
                .gauge()

            assertNotNull(gauge)
            assertEquals(99.5, gauge?.value())
        }

        @Test
        @DisplayName("Should record provider response time")
        fun testRecordProviderResponseTime() {
            // Given
            val provider = Provider.PROVIDER_B
            val responseTimeMs = 250L

            // When
            metricsService.recordProviderResponseTime(provider, responseTimeMs)

            // Then
            val gauge = meterRegistry.find("provider.response_time.ms")
                .tag("provider", provider.name)
                .gauge()

            assertNotNull(gauge)
            assertEquals(250.0, gauge?.value())
        }
    }

    @Nested
    @DisplayName("Timer Utility Tests")
    inner class TimerUtilityTests {

        @Test
        @DisplayName("Should start and stop timer")
        fun testStartStopTimer() {
            // When
            val sample = metricsService.startTimer()
            Thread.sleep(100) // Simulate work
            val duration = metricsService.stopTimer(sample)

            // Then
            assertTrue(duration.toMillis() >= 100)
        }

        @Test
        @DisplayName("Should measure accurate duration")
        fun testTimerAccuracy() {
            // When
            val sample = metricsService.startTimer()
            Thread.sleep(50)
            val duration = metricsService.stopTimer(sample)

            // Then
            assertTrue(duration.toMillis() >= 50)
            assertTrue(duration.toMillis() < 200) // Allow some margin
        }
    }
}


