package com.payment.orchestration.events

import com.payment.orchestration.domain.model.PaymentMethod
import com.payment.orchestration.domain.model.PaymentStatus
import com.payment.orchestration.domain.model.Provider
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested
import org.springframework.context.ApplicationEventPublisher
import org.mockito.kotlin.*
import java.time.Instant
import java.util.UUID

/**
 * Event Publisher Tests
 * 
 * Test Coverage:
 * - Event publishing
 * - Event metrics tracking
 * - Multiple event publishing
 * - Error handling
 * - Event serialization
 * - All event types
 */
@DisplayName("Event Publisher Tests")
class EventPublisherTest {

    private lateinit var applicationEventPublisher: ApplicationEventPublisher
    private lateinit var eventPublisher: EventPublisher

    @BeforeEach
    fun setup() {
        applicationEventPublisher = mock()
        eventPublisher = EventPublisher(applicationEventPublisher)
    }

    @Nested
    @DisplayName("Payment Created Event Tests")
    inner class PaymentCreatedEventTests {

        @Test
        @DisplayName("Should publish PaymentCreated event")
        fun testPublishPaymentCreated() {
            // Given
            val event = PaymentEvent.PaymentCreated(
                eventId = UUID.randomUUID().toString(),
                timestamp = Instant.now(),
                paymentId = "pay_123",
                aggregateVersion = 1,
                customerId = "cust_456",
                amount = 10000L,
                currency = "USD",
                paymentMethod = PaymentMethod.CARD,
                provider = Provider.PROVIDER_A,
                description = "Test payment",
                metadata = mapOf("test" to "value")
            )

            // When
            eventPublisher.publish(event)

            // Then
            verify(applicationEventPublisher).publishEvent(event)
        }

        @Test
        @DisplayName("Should track metrics for PaymentCreated event")
        fun testPaymentCreatedMetrics() {
            // Given
            val event = PaymentEvent.PaymentCreated(
                eventId = UUID.randomUUID().toString(),
                timestamp = Instant.now(),
                paymentId = "pay_123",
                aggregateVersion = 1,
                customerId = "cust_456",
                amount = 10000L,
                currency = "USD",
                paymentMethod = PaymentMethod.CARD,
                provider = Provider.PROVIDER_A,
                description = null,
                metadata = emptyMap()
            )

            // When
            eventPublisher.publish(event)

            // Then
            val metrics = eventPublisher.getEventMetrics("PaymentCreated")
            assertNotNull(metrics)
            assertEquals(1L, metrics?.getCount())
        }

        @Test
        @DisplayName("Should serialize PaymentCreated event to JSON")
        fun testPaymentCreatedSerialization() {
            // Given
            val event = PaymentEvent.PaymentCreated(
                eventId = "evt_123",
                timestamp = Instant.parse("2024-01-01T00:00:00Z"),
                paymentId = "pay_123",
                aggregateVersion = 1,
                customerId = "cust_456",
                amount = 10000L,
                currency = "USD",
                paymentMethod = PaymentMethod.CARD,
                provider = Provider.PROVIDER_A,
                description = "Test",
                metadata = mapOf("key" to "value")
            )

            // When
            val json = event.toJson()

            // Then
            assertTrue(json.contains("\"eventId\": \"evt_123\""))
            assertTrue(json.contains("\"paymentId\": \"pay_123\""))
            assertTrue(json.contains("\"amount\": 10000"))
            assertTrue(json.contains("\"currency\": \"USD\""))
        }
    }

    @Nested
    @DisplayName("Payment Authorized Event Tests")
    inner class PaymentAuthorizedEventTests {

        @Test
        @DisplayName("Should publish PaymentAuthorized event")
        fun testPublishPaymentAuthorized() {
            // Given
            val event = PaymentEvent.PaymentAuthorized(
                eventId = UUID.randomUUID().toString(),
                timestamp = Instant.now(),
                paymentId = "pay_123",
                aggregateVersion = 2,
                provider = Provider.PROVIDER_A,
                providerTransactionId = "txn_789",
                authorizationCode = "AUTH123"
            )

            // When
            eventPublisher.publish(event)

            // Then
            verify(applicationEventPublisher).publishEvent(event)
        }
    }

    @Nested
    @DisplayName("Payment Failed Event Tests")
    inner class PaymentFailedEventTests {

        @Test
        @DisplayName("Should publish PaymentFailed event")
        fun testPublishPaymentFailed() {
            // Given
            val event = PaymentEvent.PaymentFailed(
                eventId = UUID.randomUUID().toString(),
                timestamp = Instant.now(),
                paymentId = "pay_123",
                aggregateVersion = 2,
                provider = Provider.PROVIDER_A,
                errorCode = "INSUFFICIENT_FUNDS",
                errorMessage = "Insufficient funds",
                failureReason = "Card declined"
            )

            // When
            eventPublisher.publish(event)

            // Then
            verify(applicationEventPublisher).publishEvent(event)
        }
    }

    @Nested
    @DisplayName("Payment Status Changed Event Tests")
    inner class PaymentStatusChangedEventTests {

        @Test
        @DisplayName("Should publish PaymentStatusChanged event")
        fun testPublishPaymentStatusChanged() {
            // Given
            val event = PaymentEvent.PaymentStatusChanged(
                eventId = UUID.randomUUID().toString(),
                timestamp = Instant.now(),
                paymentId = "pay_123",
                aggregateVersion = 3,
                previousStatus = PaymentStatus.PENDING,
                newStatus = PaymentStatus.SUCCESS,
                reason = "Payment completed"
            )

            // When
            eventPublisher.publish(event)

            // Then
            verify(applicationEventPublisher).publishEvent(event)
        }
    }

    @Nested
    @DisplayName("Payment Retry Event Tests")
    inner class PaymentRetryEventTests {

        @Test
        @DisplayName("Should publish PaymentRetryAttempted event")
        fun testPublishPaymentRetryAttempted() {
            // Given
            val event = PaymentEvent.PaymentRetryAttempted(
                eventId = UUID.randomUUID().toString(),
                timestamp = Instant.now(),
                paymentId = "pay_123",
                aggregateVersion = 2,
                provider = Provider.PROVIDER_A,
                attemptNumber = 2,
                maxAttempts = 3
            )

            // When
            eventPublisher.publish(event)

            // Then
            verify(applicationEventPublisher).publishEvent(event)
        }

        @Test
        @DisplayName("Should publish PaymentRetryExhausted event")
        fun testPublishPaymentRetryExhausted() {
            // Given
            val event = PaymentEvent.PaymentRetryExhausted(
                eventId = UUID.randomUUID().toString(),
                timestamp = Instant.now(),
                paymentId = "pay_123",
                aggregateVersion = 4,
                provider = Provider.PROVIDER_A,
                totalAttempts = 3,
                lastError = "Connection timeout"
            )

            // When
            eventPublisher.publish(event)

            // Then
            verify(applicationEventPublisher).publishEvent(event)
        }
    }

    @Nested
    @DisplayName("Provider Failover Event Tests")
    inner class ProviderFailoverEventTests {

        @Test
        @DisplayName("Should publish ProviderFailover event")
        fun testPublishProviderFailover() {
            // Given
            val event = PaymentEvent.ProviderFailover(
                eventId = UUID.randomUUID().toString(),
                timestamp = Instant.now(),
                paymentId = "pay_123",
                aggregateVersion = 2,
                fromProvider = Provider.PROVIDER_A,
                toProvider = Provider.PROVIDER_B,
                failoverReason = "Provider A unavailable"
            )

            // When
            eventPublisher.publish(event)

            // Then
            verify(applicationEventPublisher).publishEvent(event)
        }
    }

    @Nested
    @DisplayName("Circuit Breaker Event Tests")
    inner class CircuitBreakerEventTests {

        @Test
        @DisplayName("Should publish CircuitBreakerOpened event")
        fun testPublishCircuitBreakerOpened() {
            // Given
            val event = PaymentEvent.CircuitBreakerOpened(
                eventId = UUID.randomUUID().toString(),
                timestamp = Instant.now(),
                paymentId = "pay_123",
                aggregateVersion = 1,
                provider = Provider.PROVIDER_A,
                failureRate = 0.6,
                threshold = 0.5
            )

            // When
            eventPublisher.publish(event)

            // Then
            verify(applicationEventPublisher).publishEvent(event)
        }
    }

    @Nested
    @DisplayName("Webhook Event Tests")
    inner class WebhookEventTests {

        @Test
        @DisplayName("Should publish WebhookReceived event")
        fun testPublishWebhookReceived() {
            // Given
            val event = PaymentEvent.WebhookReceived(
                eventId = UUID.randomUUID().toString(),
                timestamp = Instant.now(),
                paymentId = "pay_123",
                aggregateVersion = 2,
                provider = Provider.PROVIDER_A,
                webhookType = "payment.success",
                webhookPayload = "{\"status\":\"success\"}"
            )

            // When
            eventPublisher.publish(event)

            // Then
            verify(applicationEventPublisher).publishEvent(event)
        }
    }

    @Nested
    @DisplayName("Idempotency Event Tests")
    inner class IdempotencyEventTests {

        @Test
        @DisplayName("Should publish IdempotencyKeyReused event")
        fun testPublishIdempotencyKeyReused() {
            // Given
            val originalTime = Instant.now().minusSeconds(60)
            val event = PaymentEvent.IdempotencyKeyReused(
                eventId = UUID.randomUUID().toString(),
                timestamp = Instant.now(),
                paymentId = "pay_123",
                aggregateVersion = 1,
                idempotencyKey = "idem_456",
                originalRequestTime = originalTime
            )

            // When
            eventPublisher.publish(event)

            // Then
            verify(applicationEventPublisher).publishEvent(event)
        }
    }

    @Nested
    @DisplayName("Multiple Events Tests")
    inner class MultipleEventsTests {

        @Test
        @DisplayName("Should publish multiple events")
        fun testPublishMultipleEvents() {
            // Given
            val events = listOf(
                PaymentEvent.PaymentCreated(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = Instant.now(),
                    paymentId = "pay_123",
                    aggregateVersion = 1,
                    customerId = "cust_456",
                    amount = 10000L,
                    currency = "USD",
                    paymentMethod = PaymentMethod.CARD,
                    provider = Provider.PROVIDER_A,
                    description = null,
                    metadata = emptyMap()
                ),
                PaymentEvent.PaymentAuthorized(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = Instant.now(),
                    paymentId = "pay_123",
                    aggregateVersion = 2,
                    provider = Provider.PROVIDER_A,
                    providerTransactionId = "txn_789",
                    authorizationCode = null
                ),
                PaymentEvent.PaymentCaptured(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = Instant.now(),
                    paymentId = "pay_123",
                    aggregateVersion = 3,
                    provider = Provider.PROVIDER_A,
                    capturedAmount = 10000L,
                    currency = "USD"
                )
            )

            // When
            eventPublisher.publishAll(events)

            // Then
            verify(applicationEventPublisher, times(3)).publishEvent(any())
        }

        @Test
        @DisplayName("Should track metrics for multiple events")
        fun testMultipleEventsMetrics() {
            // Given
            val events = listOf(
                PaymentEvent.PaymentCreated(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = Instant.now(),
                    paymentId = "pay_123",
                    aggregateVersion = 1,
                    customerId = "cust_456",
                    amount = 10000L,
                    currency = "USD",
                    paymentMethod = PaymentMethod.CARD,
                    provider = Provider.PROVIDER_A,
                    description = null,
                    metadata = emptyMap()
                ),
                PaymentEvent.PaymentCreated(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = Instant.now(),
                    paymentId = "pay_456",
                    aggregateVersion = 1,
                    customerId = "cust_789",
                    amount = 5000L,
                    currency = "EUR",
                    paymentMethod = PaymentMethod.UPI,
                    provider = Provider.PROVIDER_B,
                    description = null,
                    metadata = emptyMap()
                )
            )

            // When
            eventPublisher.publishAll(events)

            // Then
            val metrics = eventPublisher.getEventMetrics("PaymentCreated")
            assertNotNull(metrics)
            assertEquals(2L, metrics?.getCount())
        }
    }

    @Nested
    @DisplayName("Metrics Tests")
    inner class MetricsTests {

        @Test
        @DisplayName("Should track metrics for different event types")
        fun testMetricsForDifferentEventTypes() {
            // Given
            val createdEvent = PaymentEvent.PaymentCreated(
                eventId = UUID.randomUUID().toString(),
                timestamp = Instant.now(),
                paymentId = "pay_123",
                aggregateVersion = 1,
                customerId = "cust_456",
                amount = 10000L,
                currency = "USD",
                paymentMethod = PaymentMethod.CARD,
                provider = Provider.PROVIDER_A,
                description = null,
                metadata = emptyMap()
            )
            
            val failedEvent = PaymentEvent.PaymentFailed(
                eventId = UUID.randomUUID().toString(),
                timestamp = Instant.now(),
                paymentId = "pay_456",
                aggregateVersion = 2,
                provider = Provider.PROVIDER_B,
                errorCode = "ERROR",
                errorMessage = "Failed",
                failureReason = "Test"
            )

            // When
            eventPublisher.publish(createdEvent)
            eventPublisher.publish(createdEvent)
            eventPublisher.publish(failedEvent)

            // Then
            val allMetrics = eventPublisher.getEventMetrics()
            assertEquals(2, allMetrics.size)
            assertEquals(2L, allMetrics["PaymentCreated"]?.getCount())
            assertEquals(1L, allMetrics["PaymentFailed"]?.getCount())
        }

        @Test
        @DisplayName("Should reset metrics")
        fun testResetMetrics() {
            // Given
            val event = PaymentEvent.PaymentCreated(
                eventId = UUID.randomUUID().toString(),
                timestamp = Instant.now(),
                paymentId = "pay_123",
                aggregateVersion = 1,
                customerId = "cust_456",
                amount = 10000L,
                currency = "USD",
                paymentMethod = PaymentMethod.CARD,
                provider = Provider.PROVIDER_A,
                description = null,
                metadata = emptyMap()
            )
            eventPublisher.publish(event)

            // When
            eventPublisher.resetMetrics()

            // Then
            val metrics = eventPublisher.getEventMetrics()
            assertTrue(metrics.isEmpty())
        }
    }

    @Nested
    @DisplayName("Error Handling Tests")
    inner class ErrorHandlingTests {

        @Test
        @DisplayName("Should handle publish errors gracefully")
        fun testPublishError() {
            // Given
            val event = PaymentEvent.PaymentCreated(
                eventId = UUID.randomUUID().toString(),
                timestamp = Instant.now(),
                paymentId = "pay_123",
                aggregateVersion = 1,
                customerId = "cust_456",
                amount = 10000L,
                currency = "USD",
                paymentMethod = PaymentMethod.CARD,
                provider = Provider.PROVIDER_A,
                description = null,
                metadata = emptyMap()
            )
            
            whenever(applicationEventPublisher.publishEvent(any()))
                .thenThrow(RuntimeException("Publish failed"))

            // When/Then - Should not throw exception
            assertDoesNotThrow {
                eventPublisher.publish(event)
            }
        }
    }

    @Nested
    @DisplayName("Event Serialization Tests")
    inner class EventSerializationTests {

        @Test
        @DisplayName("Should serialize all event types to JSON")
        fun testAllEventTypesSerialization() {
            // Given
            val events = listOf(
                PaymentEvent.PaymentCreated(
                    eventId = "evt_1",
                    timestamp = Instant.parse("2024-01-01T00:00:00Z"),
                    paymentId = "pay_123",
                    aggregateVersion = 1,
                    customerId = "cust_456",
                    amount = 10000L,
                    currency = "USD",
                    paymentMethod = PaymentMethod.CARD,
                    provider = Provider.PROVIDER_A,
                    description = null,
                    metadata = emptyMap()
                ),
                PaymentEvent.PaymentRefunded(
                    eventId = "evt_2",
                    timestamp = Instant.parse("2024-01-01T00:00:00Z"),
                    paymentId = "pay_123",
                    aggregateVersion = 4,
                    provider = Provider.PROVIDER_A,
                    refundAmount = 5000L,
                    currency = "USD",
                    refundReason = "Customer request",
                    refundId = "ref_789"
                )
            )

            // When/Then - Should not throw exception
            events.forEach { event ->
                assertDoesNotThrow {
                    val json = event.toJson()
                    assertNotNull(json)
                    assertTrue(json.isNotEmpty())
                }
            }
        }
    }
}


