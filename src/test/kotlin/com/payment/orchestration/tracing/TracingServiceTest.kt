package com.payment.orchestration.tracing

import brave.Tracer
import brave.test.TestSpanHandler
import com.payment.orchestration.domain.model.PaymentMethod
import com.payment.orchestration.domain.model.Provider
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested
import zipkin2.Span

/**
 * Tracing Service Tests
 * 
 * Test Coverage:
 * - Payment processing tracing
 * - Provider API call tracing
 * - Database operation tracing
 * - Routing decision tracing
 * - Retry attempt tracing
 * - Webhook processing tracing
 * - Idempotency check tracing
 * - Circuit breaker check tracing
 * - Baggage operations
 * - Trace/Span ID retrieval
 * - Tag and annotation operations
 * - Error handling
 */
@DisplayName("Tracing Service Tests")
class TracingServiceTest {

    private lateinit var spanHandler: TestSpanHandler
    private lateinit var tracer: Tracer
    private lateinit var tracingService: TracingService

    @BeforeEach
    fun setup() {
        spanHandler = TestSpanHandler()
        tracer = Tracer.newBuilder()
            .addSpanHandler(spanHandler)
            .build()
        tracingService = TracingService(tracer)
    }

    @Nested
    @DisplayName("Payment Processing Tracing Tests")
    inner class PaymentProcessingTracingTests {

        @Test
        @DisplayName("Should create span for payment processing")
        fun testPaymentProcessingSpan() {
            // Given
            val paymentId = "pay_123"
            val provider = Provider.PROVIDER_A

            // When
            val result = tracingService.tracePaymentProcessing(
                paymentId = paymentId,
                provider = provider
            ) {
                "success"
            }

            // Then
            assertEquals("success", result)
            
            val spans = spanHandler.spans()
            assertEquals(1, spans.size)
            
            val span = spans[0]
            assertEquals(TracingConstants.SPAN_PAYMENT_PROCESSING, span.name())
            assertEquals(paymentId, span.tags()[TracingConstants.TAG_PAYMENT_ID])
            assertEquals(provider.name, span.tags()[TracingConstants.TAG_PROVIDER])
        }

        @Test
        @DisplayName("Should add all payment context tags")
        fun testPaymentContextTags() {
            // Given
            val paymentId = "pay_123"
            val customerId = "cust_456"
            val provider = Provider.PROVIDER_A
            val paymentMethod = PaymentMethod.CARD
            val amount = 10000L
            val currency = "USD"

            // When
            tracingService.tracePaymentProcessing(
                paymentId = paymentId,
                customerId = customerId,
                provider = provider,
                paymentMethod = paymentMethod,
                amount = amount,
                currency = currency
            ) {
                "success"
            }

            // Then
            val span = spanHandler.spans()[0]
            val tags = span.tags()
            
            assertEquals(paymentId, tags[TracingConstants.TAG_PAYMENT_ID])
            assertEquals(customerId, tags[TracingConstants.TAG_CUSTOMER_ID])
            assertEquals(provider.name, tags[TracingConstants.TAG_PROVIDER])
            assertEquals(paymentMethod.name, tags[TracingConstants.TAG_PAYMENT_METHOD])
            assertEquals(amount.toString(), tags[TracingConstants.TAG_AMOUNT])
            assertEquals(currency, tags[TracingConstants.TAG_CURRENCY])
        }

        @Test
        @DisplayName("Should set baggage for propagation")
        fun testBaggagePropagation() {
            // Given
            val paymentId = "pay_123"
            val customerId = "cust_456"
            val provider = Provider.PROVIDER_A

            // When
            tracingService.tracePaymentProcessing(
                paymentId = paymentId,
                customerId = customerId,
                provider = provider
            ) {
                // Verify baggage is set within span
                assertEquals(paymentId, tracingService.getBaggage(TracingConstants.BAGGAGE_PAYMENT_ID))
                assertEquals(customerId, tracingService.getBaggage(TracingConstants.BAGGAGE_CUSTOMER_ID))
                assertEquals(provider.name, tracingService.getBaggage(TracingConstants.BAGGAGE_PROVIDER))
                "success"
            }

            // Then - verify span was created
            assertEquals(1, spanHandler.spans().size)
        }

        @Test
        @DisplayName("Should handle errors in payment processing")
        fun testPaymentProcessingError() {
            // Given
            val paymentId = "pay_123"
            val provider = Provider.PROVIDER_A
            val errorMessage = "Payment failed"

            // When/Then
            assertThrows(RuntimeException::class.java) {
                tracingService.tracePaymentProcessing(
                    paymentId = paymentId,
                    provider = provider
                ) {
                    throw RuntimeException(errorMessage)
                }
            }

            // Verify error tags
            val span = spanHandler.spans()[0]
            val tags = span.tags()
            
            assertEquals(TracingConstants.TAG_ERROR_TRUE, tags[TracingConstants.TAG_ERROR])
            assertEquals("RuntimeException", tags[TracingConstants.TAG_ERROR_TYPE])
            assertEquals(errorMessage, tags[TracingConstants.TAG_ERROR_MESSAGE])
        }
    }

    @Nested
    @DisplayName("Provider API Call Tracing Tests")
    inner class ProviderApiCallTracingTests {

        @Test
        @DisplayName("Should create span for provider API call")
        fun testProviderApiCallSpan() {
            // Given
            val provider = Provider.PROVIDER_B
            val operation = "charge"

            // When
            val result = tracingService.traceProviderApiCall(provider, operation) {
                "api_response"
            }

            // Then
            assertEquals("api_response", result)
            
            val span = spanHandler.spans()[0]
            assertEquals(TracingConstants.SPAN_PROVIDER_API_CALL, span.name())
            assertEquals(provider.name, span.tags()[TracingConstants.TAG_PROVIDER])
            assertEquals(operation, span.tags()["operation"])
        }

        @Test
        @DisplayName("Should handle provider API errors")
        fun testProviderApiError() {
            // Given
            val provider = Provider.PROVIDER_A
            val operation = "refund"

            // When/Then
            assertThrows(RuntimeException::class.java) {
                tracingService.traceProviderApiCall(provider, operation) {
                    throw RuntimeException("API timeout")
                }
            }

            // Verify error tags
            val span = spanHandler.spans()[0]
            assertEquals(TracingConstants.TAG_ERROR_TRUE, span.tags()[TracingConstants.TAG_ERROR])
        }
    }

    @Nested
    @DisplayName("Database Operation Tracing Tests")
    inner class DatabaseOperationTracingTests {

        @Test
        @DisplayName("Should create span for database operation")
        fun testDatabaseOperationSpan() {
            // Given
            val operation = "insert_payment"

            // When
            val result = tracingService.traceDatabaseOperation(operation) {
                "db_result"
            }

            // Then
            assertEquals("db_result", result)
            
            val span = spanHandler.spans()[0]
            assertEquals(TracingConstants.SPAN_DATABASE_QUERY, span.name())
            assertEquals(operation, span.tags()["operation"])
        }

        @Test
        @DisplayName("Should handle database errors")
        fun testDatabaseError() {
            // Given
            val operation = "update_status"

            // When/Then
            assertThrows(RuntimeException::class.java) {
                tracingService.traceDatabaseOperation(operation) {
                    throw RuntimeException("Connection timeout")
                }
            }

            // Verify error tags
            val span = spanHandler.spans()[0]
            assertEquals(TracingConstants.TAG_ERROR_TRUE, span.tags()[TracingConstants.TAG_ERROR])
        }
    }

    @Nested
    @DisplayName("Routing Decision Tracing Tests")
    inner class RoutingDecisionTracingTests {

        @Test
        @DisplayName("Should create span for routing decision")
        fun testRoutingDecisionSpan() {
            // Given
            val selectedProvider = Provider.PROVIDER_A
            val routingReason = "country_routing_US"

            // When
            val result = tracingService.traceRoutingDecision(selectedProvider, routingReason) {
                "routed"
            }

            // Then
            assertEquals("routed", result)
            
            val span = spanHandler.spans()[0]
            assertEquals(TracingConstants.SPAN_ROUTING_DECISION, span.name())
            assertEquals(selectedProvider.name, span.tags()[TracingConstants.TAG_PROVIDER])
            assertEquals(routingReason, span.tags()[TracingConstants.TAG_ROUTING_REASON])
        }
    }

    @Nested
    @DisplayName("Retry Attempt Tracing Tests")
    inner class RetryAttemptTracingTests {

        @Test
        @DisplayName("Should create span for retry attempt")
        fun testRetryAttemptSpan() {
            // Given
            val provider = Provider.PROVIDER_B
            val attemptNumber = 2

            // When
            val result = tracingService.traceRetryAttempt(provider, attemptNumber) {
                "retry_success"
            }

            // Then
            assertEquals("retry_success", result)
            
            val span = spanHandler.spans()[0]
            assertEquals(TracingConstants.SPAN_RETRY_ATTEMPT, span.name())
            assertEquals(provider.name, span.tags()[TracingConstants.TAG_PROVIDER])
            assertEquals(attemptNumber.toString(), span.tags()[TracingConstants.TAG_RETRY_ATTEMPT])
        }

        @Test
        @DisplayName("Should track multiple retry attempts")
        fun testMultipleRetryAttempts() {
            // When - Simulate 3 retry attempts
            repeat(3) { attempt ->
                tracingService.traceRetryAttempt(Provider.PROVIDER_A, attempt + 1) {
                    "attempt_${attempt + 1}"
                }
            }

            // Then
            val spans = spanHandler.spans()
            assertEquals(3, spans.size)
            
            spans.forEachIndexed { index, span ->
                assertEquals((index + 1).toString(), span.tags()[TracingConstants.TAG_RETRY_ATTEMPT])
            }
        }
    }

    @Nested
    @DisplayName("Webhook Processing Tracing Tests")
    inner class WebhookProcessingTracingTests {

        @Test
        @DisplayName("Should create span for webhook processing")
        fun testWebhookProcessingSpan() {
            // Given
            val provider = Provider.PROVIDER_A
            val webhookType = "payment.success"

            // When
            val result = tracingService.traceWebhookProcessing(provider, webhookType) {
                "webhook_processed"
            }

            // Then
            assertEquals("webhook_processed", result)
            
            val span = spanHandler.spans()[0]
            assertEquals(TracingConstants.SPAN_WEBHOOK_PROCESSING, span.name())
            assertEquals(provider.name, span.tags()[TracingConstants.TAG_PROVIDER])
            assertEquals(webhookType, span.tags()["webhook.type"])
        }
    }

    @Nested
    @DisplayName("Idempotency Check Tracing Tests")
    inner class IdempotencyCheckTracingTests {

        @Test
        @DisplayName("Should create span for idempotency check - cache hit")
        fun testIdempotencyCheckHit() {
            // Given
            val idempotencyKey = "idem_123"
            val isHit = true

            // When
            val result = tracingService.traceIdempotencyCheck(idempotencyKey, isHit) {
                "cache_hit"
            }

            // Then
            assertEquals("cache_hit", result)
            
            val span = spanHandler.spans()[0]
            assertEquals(TracingConstants.SPAN_IDEMPOTENCY_CHECK, span.name())
            assertEquals(idempotencyKey, span.tags()["idempotency.key"])
            assertEquals("true", span.tags()["idempotency.hit"])
        }

        @Test
        @DisplayName("Should create span for idempotency check - cache miss")
        fun testIdempotencyCheckMiss() {
            // Given
            val idempotencyKey = "idem_456"
            val isHit = false

            // When
            tracingService.traceIdempotencyCheck(idempotencyKey, isHit) {
                "cache_miss"
            }

            // Then
            val span = spanHandler.spans()[0]
            assertEquals("false", span.tags()["idempotency.hit"])
        }
    }

    @Nested
    @DisplayName("Circuit Breaker Check Tracing Tests")
    inner class CircuitBreakerCheckTracingTests {

        @Test
        @DisplayName("Should create span for circuit breaker check")
        fun testCircuitBreakerCheckSpan() {
            // Given
            val provider = Provider.PROVIDER_A
            val state = "CLOSED"

            // When
            val result = tracingService.traceCircuitBreakerCheck(provider, state) {
                "check_passed"
            }

            // Then
            assertEquals("check_passed", result)
            
            val span = spanHandler.spans()[0]
            assertEquals(TracingConstants.SPAN_CIRCUIT_BREAKER_CHECK, span.name())
            assertEquals(provider.name, span.tags()[TracingConstants.TAG_PROVIDER])
            assertEquals(state, span.tags()[TracingConstants.TAG_CIRCUIT_BREAKER_STATE])
        }
    }

    @Nested
    @DisplayName("Baggage Operations Tests")
    inner class BaggageOperationsTests {

        @Test
        @DisplayName("Should set and get baggage")
        fun testSetGetBaggage() {
            // Given
            val key = "test-key"
            val value = "test-value"

            // When
            tracingService.tracePaymentProcessing("pay_123", provider = Provider.PROVIDER_A) {
                tracingService.setBaggage(key, value)
                val retrieved = tracingService.getBaggage(key)
                
                // Then
                assertEquals(value, retrieved)
            }
        }

        @Test
        @DisplayName("Should return null for non-existent baggage")
        fun testGetNonExistentBaggage() {
            // When
            tracingService.tracePaymentProcessing("pay_123", provider = Provider.PROVIDER_A) {
                val retrieved = tracingService.getBaggage("non-existent-key")
                
                // Then
                assertNull(retrieved)
            }
        }
    }

    @Nested
    @DisplayName("Trace and Span ID Tests")
    inner class TraceSpanIdTests {

        @Test
        @DisplayName("Should get current trace ID")
        fun testGetCurrentTraceId() {
            // When
            tracingService.tracePaymentProcessing("pay_123", provider = Provider.PROVIDER_A) {
                val traceId = tracingService.getCurrentTraceId()
                
                // Then
                assertNotNull(traceId)
                assertTrue(traceId!!.length > 0)
            }
        }

        @Test
        @DisplayName("Should get current span ID")
        fun testGetCurrentSpanId() {
            // When
            tracingService.tracePaymentProcessing("pay_123", provider = Provider.PROVIDER_A) {
                val spanId = tracingService.getCurrentSpanId()
                
                // Then
                assertNotNull(spanId)
                assertTrue(spanId!!.length > 0)
            }
        }

        @Test
        @DisplayName("Should return null when no active span")
        fun testNoActiveSpan() {
            // When
            val traceId = tracingService.getCurrentTraceId()
            val spanId = tracingService.getCurrentSpanId()

            // Then
            assertNull(traceId)
            assertNull(spanId)
        }
    }

    @Nested
    @DisplayName("Tag and Annotation Tests")
    inner class TagAnnotationTests {

        @Test
        @DisplayName("Should add custom tag to current span")
        fun testAddTag() {
            // Given
            val tagKey = "custom.tag"
            val tagValue = "custom_value"

            // When
            tracingService.tracePaymentProcessing("pay_123", provider = Provider.PROVIDER_A) {
                tracingService.addTag(tagKey, tagValue)
            }

            // Then
            val span = spanHandler.spans()[0]
            assertEquals(tagValue, span.tags()[tagKey])
        }

        @Test
        @DisplayName("Should add annotation to current span")
        fun testAddAnnotation() {
            // Given
            val message = "Custom annotation"

            // When
            tracingService.tracePaymentProcessing("pay_123", provider = Provider.PROVIDER_A) {
                tracingService.addAnnotation(message)
            }

            // Then
            val span = spanHandler.spans()[0]
            assertTrue(span.annotations().any { it.value() == message })
        }
    }

    @Nested
    @DisplayName("Custom Span Creation Tests")
    inner class CustomSpanCreationTests {

        @Test
        @DisplayName("Should create custom span")
        fun testCreateCustomSpan() {
            // Given
            val spanName = "custom-operation"

            // When
            val span = tracingService.createSpan(spanName)
            try {
                span.tag("custom.key", "custom.value")
            } finally {
                span.finish()
            }

            // Then
            val finishedSpan = spanHandler.spans()[0]
            assertEquals(spanName, finishedSpan.name())
            assertEquals("custom.value", finishedSpan.tags()["custom.key"])
        }

        @Test
        @DisplayName("Should execute block with span in scope")
        fun testWithSpanInScope() {
            // Given
            val span = tracingService.createSpan("scoped-operation")

            // When
            val result = try {
                tracingService.withSpanInScope(span) {
                    tracingService.addTag("scoped.tag", "scoped.value")
                    "scoped_result"
                }
            } finally {
                span.finish()
            }

            // Then
            assertEquals("scoped_result", result)
            val finishedSpan = spanHandler.spans()[0]
            assertEquals("scoped.value", finishedSpan.tags()["scoped.tag"])
        }
    }

    @Nested
    @DisplayName("Nested Span Tests")
    inner class NestedSpanTests {

        @Test
        @DisplayName("Should create nested spans")
        fun testNestedSpans() {
            // When
            tracingService.tracePaymentProcessing("pay_123", provider = Provider.PROVIDER_A) {
                tracingService.traceProviderApiCall(Provider.PROVIDER_A, "charge") {
                    tracingService.traceDatabaseOperation("insert_payment") {
                        "nested_result"
                    }
                }
            }

            // Then
            val spans = spanHandler.spans()
            assertEquals(3, spans.size)
            
            // Verify span hierarchy
            val dbSpan = spans[0]
            val apiSpan = spans[1]
            val paymentSpan = spans[2]
            
            assertEquals(TracingConstants.SPAN_DATABASE_QUERY, dbSpan.name())
            assertEquals(TracingConstants.SPAN_PROVIDER_API_CALL, apiSpan.name())
            assertEquals(TracingConstants.SPAN_PAYMENT_PROCESSING, paymentSpan.name())
            
            // Verify parent-child relationships
            assertEquals(apiSpan.id(), dbSpan.parentId())
            assertEquals(paymentSpan.id(), apiSpan.parentId())
        }
    }

    @Nested
    @DisplayName("Error Propagation Tests")
    inner class ErrorPropagationTests {

        @Test
        @DisplayName("Should propagate errors through nested spans")
        fun testErrorPropagation() {
            // When/Then
            assertThrows(RuntimeException::class.java) {
                tracingService.tracePaymentProcessing("pay_123", provider = Provider.PROVIDER_A) {
                    tracingService.traceProviderApiCall(Provider.PROVIDER_A, "charge") {
                        throw RuntimeException("Nested error")
                    }
                }
            }

            // Verify both spans have error tags
            val spans = spanHandler.spans()
            assertEquals(2, spans.size)
            
            spans.forEach { span ->
                assertEquals(TracingConstants.TAG_ERROR_TRUE, span.tags()[TracingConstants.TAG_ERROR])
            }
        }
    }
}


