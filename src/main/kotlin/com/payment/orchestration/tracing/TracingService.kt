package com.payment.orchestration.tracing

import brave.Span
import brave.Tracer
import com.payment.orchestration.domain.model.PaymentMethod
import com.payment.orchestration.domain.model.PaymentStatus
import com.payment.orchestration.domain.model.Provider
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Tracing Service
 * 
 * Provides simplified API for distributed tracing operations.
 * 
 * Features:
 * - Automatic span creation and management
 * - Consistent tag naming
 * - Baggage propagation helpers
 * - Error tracking
 * - Async span continuation
 * 
 * Usage:
 * ```kotlin
 * @Service
 * class PaymentService(private val tracingService: TracingService) {
 *     fun processPayment(request: PaymentRequest): PaymentResponse {
 *         return tracingService.tracePaymentProcessing(
 *             paymentId = request.paymentId,
 *             provider = request.provider
 *         ) {
 *             // Your payment processing logic
 *             doProcessPayment(request)
 *         }
 *     }
 * }
 * ```
 * 
 * @property tracer Brave tracer instance
 */
@Service
class TracingService(private val tracer: Tracer) {
    
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Trace payment processing operation
     * 
     * Creates a span for the entire payment processing flow.
     * Automatically adds payment context tags and handles errors.
     * 
     * @param paymentId Payment identifier
     * @param customerId Customer identifier
     * @param provider Payment provider
     * @param paymentMethod Payment method
     * @param amount Payment amount
     * @param currency Payment currency
     * @param block Code block to execute within span
     * @return Result of the block execution
     */
    fun <T> tracePaymentProcessing(
        paymentId: String,
        customerId: String? = null,
        provider: Provider,
        paymentMethod: PaymentMethod? = null,
        amount: Long? = null,
        currency: String? = null,
        block: () -> T
    ): T {
        val span = tracer.nextSpan()
            .name(TracingConstants.SPAN_PAYMENT_PROCESSING)
            .start()
        
        return try {
            // Add tags
            span.tag(TracingConstants.TAG_PAYMENT_ID, paymentId)
            span.tag(TracingConstants.TAG_PROVIDER, provider.name)
            customerId?.let { span.tag(TracingConstants.TAG_CUSTOMER_ID, it) }
            paymentMethod?.let { span.tag(TracingConstants.TAG_PAYMENT_METHOD, it.name) }
            amount?.let { span.tag(TracingConstants.TAG_AMOUNT, it.toString()) }
            currency?.let { span.tag(TracingConstants.TAG_CURRENCY, it) }
            
            // Set baggage for propagation
            setBaggage(TracingConstants.BAGGAGE_PAYMENT_ID, paymentId)
            customerId?.let { setBaggage(TracingConstants.BAGGAGE_CUSTOMER_ID, it) }
            setBaggage(TracingConstants.BAGGAGE_PROVIDER, provider.name)
            
            // Execute block
            tracer.withSpanInScope(span).use {
                val result = block()
                
                // Add result status if available
                if (result is PaymentStatus) {
                    span.tag(TracingConstants.TAG_STATUS, result.name)
                }
                
                result
            }
        } catch (e: Exception) {
            // Tag error
            span.tag(TracingConstants.TAG_ERROR, TracingConstants.TAG_ERROR_TRUE)
            span.tag(TracingConstants.TAG_ERROR_TYPE, e.javaClass.simpleName)
            span.tag(TracingConstants.TAG_ERROR_MESSAGE, e.message ?: "Unknown error")
            
            logger.error("Error in payment processing span", e)
            throw e
        } finally {
            span.finish()
        }
    }

    /**
     * Trace provider API call
     * 
     * Creates a span for external provider API calls.
     * 
     * @param provider Payment provider
     * @param operation API operation (e.g., "charge", "refund")
     * @param block Code block to execute within span
     * @return Result of the block execution
     */
    fun <T> traceProviderApiCall(
        provider: Provider,
        operation: String,
        block: () -> T
    ): T {
        val span = tracer.nextSpan()
            .name(TracingConstants.SPAN_PROVIDER_API_CALL)
            .start()
        
        return try {
            span.tag(TracingConstants.TAG_PROVIDER, provider.name)
            span.tag("operation", operation)
            
            tracer.withSpanInScope(span).use {
                block()
            }
        } catch (e: Exception) {
            span.tag(TracingConstants.TAG_ERROR, TracingConstants.TAG_ERROR_TRUE)
            span.tag(TracingConstants.TAG_ERROR_TYPE, e.javaClass.simpleName)
            span.tag(TracingConstants.TAG_ERROR_MESSAGE, e.message ?: "Unknown error")
            
            logger.error("Error in provider API call span", e)
            throw e
        } finally {
            span.finish()
        }
    }

    /**
     * Trace database operation
     * 
     * Creates a span for database queries.
     * 
     * @param operation Database operation (e.g., "insert_payment", "update_status")
     * @param block Code block to execute within span
     * @return Result of the block execution
     */
    fun <T> traceDatabaseOperation(
        operation: String,
        block: () -> T
    ): T {
        val span = tracer.nextSpan()
            .name(TracingConstants.SPAN_DATABASE_QUERY)
            .start()
        
        return try {
            span.tag("operation", operation)
            
            tracer.withSpanInScope(span).use {
                block()
            }
        } catch (e: Exception) {
            span.tag(TracingConstants.TAG_ERROR, TracingConstants.TAG_ERROR_TRUE)
            span.tag(TracingConstants.TAG_ERROR_MESSAGE, e.message ?: "Unknown error")
            
            logger.error("Error in database operation span", e)
            throw e
        } finally {
            span.finish()
        }
    }

    /**
     * Trace routing decision
     * 
     * Creates a span for provider routing logic.
     * 
     * @param selectedProvider Selected provider
     * @param routingReason Reason for selection
     * @param block Code block to execute within span
     * @return Result of the block execution
     */
    fun <T> traceRoutingDecision(
        selectedProvider: Provider,
        routingReason: String,
        block: () -> T
    ): T {
        val span = tracer.nextSpan()
            .name(TracingConstants.SPAN_ROUTING_DECISION)
            .start()
        
        return try {
            span.tag(TracingConstants.TAG_PROVIDER, selectedProvider.name)
            span.tag(TracingConstants.TAG_ROUTING_REASON, routingReason)
            
            tracer.withSpanInScope(span).use {
                block()
            }
        } catch (e: Exception) {
            span.tag(TracingConstants.TAG_ERROR, TracingConstants.TAG_ERROR_TRUE)
            span.tag(TracingConstants.TAG_ERROR_MESSAGE, e.message ?: "Unknown error")
            
            logger.error("Error in routing decision span", e)
            throw e
        } finally {
            span.finish()
        }
    }

    /**
     * Trace retry attempt
     * 
     * Creates a span for retry operations.
     * 
     * @param provider Payment provider
     * @param attemptNumber Retry attempt number
     * @param block Code block to execute within span
     * @return Result of the block execution
     */
    fun <T> traceRetryAttempt(
        provider: Provider,
        attemptNumber: Int,
        block: () -> T
    ): T {
        val span = tracer.nextSpan()
            .name(TracingConstants.SPAN_RETRY_ATTEMPT)
            .start()
        
        return try {
            span.tag(TracingConstants.TAG_PROVIDER, provider.name)
            span.tag(TracingConstants.TAG_RETRY_ATTEMPT, attemptNumber.toString())
            
            tracer.withSpanInScope(span).use {
                block()
            }
        } catch (e: Exception) {
            span.tag(TracingConstants.TAG_ERROR, TracingConstants.TAG_ERROR_TRUE)
            span.tag(TracingConstants.TAG_ERROR_MESSAGE, e.message ?: "Unknown error")
            
            logger.error("Error in retry attempt span", e)
            throw e
        } finally {
            span.finish()
        }
    }

    /**
     * Trace webhook processing
     * 
     * Creates a span for webhook handling.
     * 
     * @param provider Payment provider
     * @param webhookType Webhook type (e.g., "payment.success")
     * @param block Code block to execute within span
     * @return Result of the block execution
     */
    fun <T> traceWebhookProcessing(
        provider: Provider,
        webhookType: String,
        block: () -> T
    ): T {
        val span = tracer.nextSpan()
            .name(TracingConstants.SPAN_WEBHOOK_PROCESSING)
            .start()
        
        return try {
            span.tag(TracingConstants.TAG_PROVIDER, provider.name)
            span.tag("webhook.type", webhookType)
            
            tracer.withSpanInScope(span).use {
                block()
            }
        } catch (e: Exception) {
            span.tag(TracingConstants.TAG_ERROR, TracingConstants.TAG_ERROR_TRUE)
            span.tag(TracingConstants.TAG_ERROR_MESSAGE, e.message ?: "Unknown error")
            
            logger.error("Error in webhook processing span", e)
            throw e
        } finally {
            span.finish()
        }
    }

    /**
     * Trace idempotency check
     * 
     * Creates a span for idempotency validation.
     * 
     * @param idempotencyKey Idempotency key
     * @param isHit Whether it's a cache hit
     * @param block Code block to execute within span
     * @return Result of the block execution
     */
    fun <T> traceIdempotencyCheck(
        idempotencyKey: String,
        isHit: Boolean,
        block: () -> T
    ): T {
        val span = tracer.nextSpan()
            .name(TracingConstants.SPAN_IDEMPOTENCY_CHECK)
            .start()
        
        return try {
            span.tag("idempotency.key", idempotencyKey)
            span.tag("idempotency.hit", isHit.toString())
            
            tracer.withSpanInScope(span).use {
                block()
            }
        } catch (e: Exception) {
            span.tag(TracingConstants.TAG_ERROR, TracingConstants.TAG_ERROR_TRUE)
            span.tag(TracingConstants.TAG_ERROR_MESSAGE, e.message ?: "Unknown error")
            
            logger.error("Error in idempotency check span", e)
            throw e
        } finally {
            span.finish()
        }
    }

    /**
     * Trace circuit breaker check
     * 
     * Creates a span for circuit breaker evaluation.
     * 
     * @param provider Payment provider
     * @param state Circuit breaker state
     * @param block Code block to execute within span
     * @return Result of the block execution
     */
    fun <T> traceCircuitBreakerCheck(
        provider: Provider,
        state: String,
        block: () -> T
    ): T {
        val span = tracer.nextSpan()
            .name(TracingConstants.SPAN_CIRCUIT_BREAKER_CHECK)
            .start()
        
        return try {
            span.tag(TracingConstants.TAG_PROVIDER, provider.name)
            span.tag(TracingConstants.TAG_CIRCUIT_BREAKER_STATE, state)
            
            tracer.withSpanInScope(span).use {
                block()
            }
        } catch (e: Exception) {
            span.tag(TracingConstants.TAG_ERROR, TracingConstants.TAG_ERROR_TRUE)
            span.tag(TracingConstants.TAG_ERROR_MESSAGE, e.message ?: "Unknown error")
            
            logger.error("Error in circuit breaker check span", e)
            throw e
        } finally {
            span.finish()
        }
    }

    /**
     * Set baggage value
     * 
     * Baggage is propagated across service boundaries.
     * 
     * @param key Baggage key
     * @param value Baggage value
     */
    fun setBaggage(key: String, value: String) {
        tracer.currentSpan()?.let { span ->
            span.context().baggageField(key).updateValue(value)
        }
    }

    /**
     * Get baggage value
     * 
     * @param key Baggage key
     * @return Baggage value or null if not found
     */
    fun getBaggage(key: String): String? {
        return tracer.currentSpan()?.context()?.baggageField(key)?.value()
    }

    /**
     * Get current trace ID
     * 
     * Useful for logging and correlation.
     * 
     * @return Trace ID or null if no active span
     */
    fun getCurrentTraceId(): String? {
        return tracer.currentSpan()?.context()?.traceIdString()
    }

    /**
     * Get current span ID
     * 
     * @return Span ID or null if no active span
     */
    fun getCurrentSpanId(): String? {
        return tracer.currentSpan()?.context()?.spanIdString()
    }

    /**
     * Add tag to current span
     * 
     * @param key Tag key
     * @param value Tag value
     */
    fun addTag(key: String, value: String) {
        tracer.currentSpan()?.tag(key, value)
    }

    /**
     * Add annotation to current span
     * 
     * Annotations are timestamped events.
     * 
     * @param message Annotation message
     */
    fun addAnnotation(message: String) {
        tracer.currentSpan()?.annotate(message)
    }

    /**
     * Create a new span
     * 
     * For custom span creation when helper methods don't fit.
     * 
     * @param name Span name
     * @return New span (must be finished manually)
     */
    fun createSpan(name: String): Span {
        return tracer.nextSpan().name(name).start()
    }

    /**
     * Execute block with span in scope
     * 
     * Ensures span is properly scoped for the duration of the block.
     * 
     * @param span Span to put in scope
     * @param block Code block to execute
     * @return Result of the block execution
     */
    fun <T> withSpanInScope(span: Span, block: () -> T): T {
        return tracer.withSpanInScope(span).use {
            block()
        }
    }
}


