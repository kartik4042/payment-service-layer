package com.payment.orchestration.tracing

import brave.sampler.Sampler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Distributed Tracing Configuration
 * 
 * Configures Spring Cloud Sleuth for distributed tracing across services.
 * 
 * Features:
 * - Automatic trace ID and span ID generation
 * - Request correlation across microservices
 * - Integration with Zipkin for visualization
 * - Baggage propagation for custom context
 * 
 * Trace Context:
 * - Trace ID: Unique identifier for entire request flow
 * - Span ID: Unique identifier for individual operation
 * - Parent Span ID: Links spans in a hierarchy
 * - Baggage: Custom key-value pairs propagated across services
 * 
 * Headers Propagated:
 * - X-B3-TraceId: Trace identifier
 * - X-B3-SpanId: Span identifier
 * - X-B3-ParentSpanId: Parent span identifier
 * - X-B3-Sampled: Sampling decision (0 or 1)
 * 
 * Zipkin Integration:
 * - Endpoint: http://localhost:9411
 * - UI: http://localhost:9411/zipkin
 * - Search by trace ID, service name, or tags
 * 
 * Dependencies Required:
 * ```kotlin
 * implementation("org.springframework.cloud:spring-cloud-starter-sleuth")
 * implementation("org.springframework.cloud:spring-cloud-sleuth-zipkin")
 * ```
 * 
 * Application Properties:
 * ```yaml
 * spring:
 *   application:
 *     name: payment-orchestration
 *   sleuth:
 *     sampler:
 *       probability: 1.0  # Sample 100% in dev, 0.1 (10%) in prod
 *     baggage:
 *       correlation-enabled: true
 *       correlation-fields:
 *         - payment-id
 *         - customer-id
 *         - provider
 *       remote-fields:
 *         - payment-id
 *         - customer-id
 *         - provider
 *   zipkin:
 *     base-url: http://localhost:9411
 *     enabled: true
 * ```
 */
@Configuration
class TracingConfig {

    /**
     * Configure sampling strategy
     * 
     * Sampling Strategies:
     * - ALWAYS_SAMPLE: Sample all requests (development)
     * - NEVER_SAMPLE: Sample no requests (disabled)
     * - Probability: Sample percentage of requests (production)
     * 
     * Production Recommendation:
     * - High traffic: 1-10% sampling
     * - Medium traffic: 10-50% sampling
     * - Low traffic: 100% sampling
     * 
     * @return Sampler configuration
     */
    @Bean
    fun defaultSampler(): Sampler {
        // Sample all requests in development
        // In production, use: Sampler.create(0.1) for 10% sampling
        return Sampler.ALWAYS_SAMPLE
    }
}

/**
 * Tracing Constants
 * 
 * Centralized constants for tracing tags and baggage keys.
 */
object TracingConstants {
    // Span Names
    const val SPAN_PAYMENT_PROCESSING = "payment-processing"
    const val SPAN_PROVIDER_API_CALL = "provider-api-call"
    const val SPAN_DATABASE_QUERY = "database-query"
    const val SPAN_ROUTING_DECISION = "routing-decision"
    const val SPAN_RETRY_ATTEMPT = "retry-attempt"
    const val SPAN_WEBHOOK_PROCESSING = "webhook-processing"
    const val SPAN_IDEMPOTENCY_CHECK = "idempotency-check"
    const val SPAN_CIRCUIT_BREAKER_CHECK = "circuit-breaker-check"
    
    // Tag Names
    const val TAG_PAYMENT_ID = "payment.id"
    const val TAG_CUSTOMER_ID = "customer.id"
    const val TAG_PROVIDER = "payment.provider"
    const val TAG_PAYMENT_METHOD = "payment.method"
    const val TAG_AMOUNT = "payment.amount"
    const val TAG_CURRENCY = "payment.currency"
    const val TAG_STATUS = "payment.status"
    const val TAG_ERROR_TYPE = "error.type"
    const val TAG_ERROR_MESSAGE = "error.message"
    const val TAG_RETRY_ATTEMPT = "retry.attempt"
    const val TAG_ROUTING_REASON = "routing.reason"
    const val TAG_CIRCUIT_BREAKER_STATE = "circuit_breaker.state"
    
    // Baggage Keys (propagated across services)
    const val BAGGAGE_PAYMENT_ID = "payment-id"
    const val BAGGAGE_CUSTOMER_ID = "customer-id"
    const val BAGGAGE_PROVIDER = "provider"
    const val BAGGAGE_CORRELATION_ID = "correlation-id"
    
    // Error Tags
    const val TAG_ERROR = "error"
    const val TAG_ERROR_TRUE = "true"
    const val TAG_ERROR_FALSE = "false"
}

/**
 * Tracing Best Practices
 * 
 * Guidelines for effective distributed tracing.
 */
object TracingBestPractices {
    /**
     * Span Naming Convention
     * 
     * Use descriptive, hierarchical names:
     * - Good: "payment-processing", "provider-api-call"
     * - Bad: "process", "call"
     * 
     * Format: <component>-<operation>
     */
    const val SPAN_NAMING = """
        Span names should be:
        1. Descriptive and specific
        2. Lowercase with hyphens
        3. Hierarchical (parent-child relationship)
        4. Consistent across services
    """
    
    /**
     * Tag Usage Guidelines
     * 
     * Tags enable filtering and searching in Zipkin UI.
     * 
     * Good Tags:
     * - payment.id, customer.id (identifiers)
     * - payment.provider, payment.method (categories)
     * - error.type, error.message (debugging)
     * 
     * Avoid:
     * - High cardinality values (timestamps, random IDs)
     * - Sensitive data (card numbers, passwords)
     * - Large text (use annotations instead)
     */
    const val TAG_GUIDELINES = """
        Tags should be:
        1. Low cardinality (limited unique values)
        2. Searchable and filterable
        3. Non-sensitive
        4. Consistent naming (dot notation)
    """
    
    /**
     * Baggage Usage Guidelines
     * 
     * Baggage is propagated across service boundaries.
     * 
     * Use For:
     * - Request correlation (payment-id, customer-id)
     * - Feature flags
     * - Tenant identification
     * 
     * Avoid:
     * - Large payloads (increases overhead)
     * - Sensitive data (propagated in headers)
     * - Frequently changing values
     */
    const val BAGGAGE_GUIDELINES = """
        Baggage should be:
        1. Small (< 1KB total)
        2. Essential for correlation
        3. Non-sensitive
        4. Stable across request lifecycle
    """
    
    /**
     * Sampling Strategy
     * 
     * Balance between observability and performance.
     * 
     * Development: 100% sampling
     * Staging: 50% sampling
     * Production: 1-10% sampling (based on traffic)
     * 
     * Always sample:
     * - Error requests
     * - Slow requests (> P95 latency)
     * - Specific customers (VIP)
     */
    const val SAMPLING_STRATEGY = """
        Sampling considerations:
        1. Higher sampling = more data, more cost
        2. Lower sampling = less visibility
        3. Use adaptive sampling for errors
        4. Consider traffic volume
    """
}

/**
 * Zipkin Query Examples
 * 
 * Useful queries for debugging in Zipkin UI.
 */
object ZipkinQueries {
    /**
     * Find all traces for a payment
     * 
     * Tag: payment.id=<payment-id>
     */
    const val BY_PAYMENT_ID = "payment.id"
    
    /**
     * Find all traces for a customer
     * 
     * Tag: customer.id=<customer-id>
     */
    const val BY_CUSTOMER_ID = "customer.id"
    
    /**
     * Find all traces for a provider
     * 
     * Tag: payment.provider=<provider-name>
     */
    const val BY_PROVIDER = "payment.provider"
    
    /**
     * Find all error traces
     * 
     * Tag: error=true
     */
    const val ERRORS_ONLY = "error=true"
    
    /**
     * Find slow traces
     * 
     * Min Duration: 2000ms (2 seconds)
     */
    const val SLOW_REQUESTS = "minDuration=2000000" // microseconds
    
    /**
     * Find traces with retries
     * 
     * Tag: retry.attempt>0
     */
    const val WITH_RETRIES = "retry.attempt"
}

/**
 * Tracing Integration Examples
 * 
 * Code examples for common tracing scenarios.
 */
object TracingExamples {
    /**
     * Manual Span Creation
     * 
     * ```kotlin
     * @Service
     * class PaymentService(private val tracer: Tracer) {
     *     fun processPayment(request: PaymentRequest): PaymentResponse {
     *         val span = tracer.nextSpan().name("payment-processing").start()
     *         try {
     *             span.tag("payment.id", request.paymentId)
     *             span.tag("payment.provider", request.provider)
     *             
     *             // Process payment
     *             val response = doProcessPayment(request)
     *             
     *             span.tag("payment.status", response.status)
     *             return response
     *         } catch (e: Exception) {
     *             span.tag("error", "true")
     *             span.tag("error.message", e.message ?: "Unknown error")
     *             throw e
     *         } finally {
     *             span.finish()
     *         }
     *     }
     * }
     * ```
     */
    const val MANUAL_SPAN = "See code example above"
    
    /**
     * Baggage Propagation
     * 
     * ```kotlin
     * @Service
     * class PaymentService(private val tracer: Tracer) {
     *     fun processPayment(request: PaymentRequest): PaymentResponse {
     *         // Set baggage (propagated to downstream services)
     *         tracer.currentSpan()?.let { span ->
     *             span.context().baggageField("payment-id").updateValue(request.paymentId)
     *             span.context().baggageField("customer-id").updateValue(request.customerId)
     *         }
     *         
     *         // Call downstream service (baggage automatically propagated)
     *         return providerClient.charge(request)
     *     }
     * }
     * ```
     */
    const val BAGGAGE_PROPAGATION = "See code example above"
    
    /**
     * Async Span Continuation
     * 
     * ```kotlin
     * @Service
     * class PaymentService(
     *     private val tracer: Tracer,
     *     private val executor: ExecutorService
     * ) {
     *     fun processPaymentAsync(request: PaymentRequest): CompletableFuture<PaymentResponse> {
     *         val span = tracer.currentSpan()
     *         
     *         return CompletableFuture.supplyAsync({
     *             // Continue span in async context
     *             tracer.withSpanInScope(span).use {
     *                 processPayment(request)
     *             }
     *         }, executor)
     *     }
     * }
     * ```
     */
    const val ASYNC_CONTINUATION = "See code example above"
}


