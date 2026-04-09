package com.payment.orchestration.events

import com.payment.orchestration.domain.model.PaymentMethod
import com.payment.orchestration.domain.model.PaymentStatus
import com.payment.orchestration.domain.model.Provider
import java.time.Instant

/**
 * Payment Event
 * 
 * Base class for all payment-related domain events.
 * 
 * Domain events represent significant business occurrences that have already happened.
 * They are immutable and contain all information needed to understand what happened.
 * 
 * Event Naming Convention:
 * - Use past tense (PaymentCreated, not CreatePayment)
 * - Be specific (PaymentAuthorized, not PaymentUpdated)
 * - Include context (PaymentFailedDueToInsufficientFunds)
 * 
 * Event Usage:
 * - Audit trail and compliance
 * - Analytics and reporting
 * - Downstream system integration
 * - Event sourcing (optional)
 * - CQRS read model updates
 * 
 * @property eventId Unique event identifier
 * @property eventType Type of event
 * @property timestamp When the event occurred
 * @property paymentId Payment identifier
 * @property aggregateVersion Version of the payment aggregate
 */
sealed class PaymentEvent(
    open val eventId: String,
    open val eventType: String,
    open val timestamp: Instant,
    open val paymentId: String,
    open val aggregateVersion: Int
) {
    /**
     * Payment Created Event
     * 
     * Published when a new payment is created.
     */
    data class PaymentCreated(
        override val eventId: String,
        override val timestamp: Instant,
        override val paymentId: String,
        override val aggregateVersion: Int,
        val customerId: String,
        val amount: Long,
        val currency: String,
        val paymentMethod: PaymentMethod,
        val provider: Provider,
        val description: String?,
        val metadata: Map<String, String>
    ) : PaymentEvent(
        eventId = eventId,
        eventType = "PaymentCreated",
        timestamp = timestamp,
        paymentId = paymentId,
        aggregateVersion = aggregateVersion
    )

    /**
     * Payment Authorized Event
     * 
     * Published when a payment is successfully authorized by the provider.
     */
    data class PaymentAuthorized(
        override val eventId: String,
        override val timestamp: Instant,
        override val paymentId: String,
        override val aggregateVersion: Int,
        val provider: Provider,
        val providerTransactionId: String,
        val authorizationCode: String?
    ) : PaymentEvent(
        eventId = eventId,
        eventType = "PaymentAuthorized",
        timestamp = timestamp,
        paymentId = paymentId,
        aggregateVersion = aggregateVersion
    )

    /**
     * Payment Captured Event
     * 
     * Published when a payment is captured (funds transferred).
     */
    data class PaymentCaptured(
        override val eventId: String,
        override val timestamp: Instant,
        override val paymentId: String,
        override val aggregateVersion: Int,
        val provider: Provider,
        val capturedAmount: Long,
        val currency: String
    ) : PaymentEvent(
        eventId = eventId,
        eventType = "PaymentCaptured",
        timestamp = timestamp,
        paymentId = paymentId,
        aggregateVersion = aggregateVersion
    )

    /**
     * Payment Failed Event
     * 
     * Published when a payment fails.
     */
    data class PaymentFailed(
        override val eventId: String,
        override val timestamp: Instant,
        override val paymentId: String,
        override val aggregateVersion: Int,
        val provider: Provider,
        val errorCode: String,
        val errorMessage: String,
        val failureReason: String
    ) : PaymentEvent(
        eventId = eventId,
        eventType = "PaymentFailed",
        timestamp = timestamp,
        paymentId = paymentId,
        aggregateVersion = aggregateVersion
    )

    /**
     * Payment Refunded Event
     * 
     * Published when a payment is refunded.
     */
    data class PaymentRefunded(
        override val eventId: String,
        override val timestamp: Instant,
        override val paymentId: String,
        override val aggregateVersion: Int,
        val provider: Provider,
        val refundAmount: Long,
        val currency: String,
        val refundReason: String?,
        val refundId: String
    ) : PaymentEvent(
        eventId = eventId,
        eventType = "PaymentRefunded",
        timestamp = timestamp,
        paymentId = paymentId,
        aggregateVersion = aggregateVersion
    )

    /**
     * Payment Status Changed Event
     * 
     * Published when payment status changes.
     */
    data class PaymentStatusChanged(
        override val eventId: String,
        override val timestamp: Instant,
        override val paymentId: String,
        override val aggregateVersion: Int,
        val previousStatus: PaymentStatus,
        val newStatus: PaymentStatus,
        val reason: String?
    ) : PaymentEvent(
        eventId = eventId,
        eventType = "PaymentStatusChanged",
        timestamp = timestamp,
        paymentId = paymentId,
        aggregateVersion = aggregateVersion
    )

    /**
     * Payment Retry Attempted Event
     * 
     * Published when a payment retry is attempted.
     */
    data class PaymentRetryAttempted(
        override val eventId: String,
        override val timestamp: Instant,
        override val paymentId: String,
        override val aggregateVersion: Int,
        val provider: Provider,
        val attemptNumber: Int,
        val maxAttempts: Int
    ) : PaymentEvent(
        eventId = eventId,
        eventType = "PaymentRetryAttempted",
        timestamp = timestamp,
        paymentId = paymentId,
        aggregateVersion = aggregateVersion
    )

    /**
     * Payment Retry Exhausted Event
     * 
     * Published when all retry attempts are exhausted.
     */
    data class PaymentRetryExhausted(
        override val eventId: String,
        override val timestamp: Instant,
        override val paymentId: String,
        override val aggregateVersion: Int,
        val provider: Provider,
        val totalAttempts: Int,
        val lastError: String
    ) : PaymentEvent(
        eventId = eventId,
        eventType = "PaymentRetryExhausted",
        timestamp = timestamp,
        paymentId = paymentId,
        aggregateVersion = aggregateVersion
    )

    /**
     * Provider Failover Event
     * 
     * Published when payment fails over to another provider.
     */
    data class ProviderFailover(
        override val eventId: String,
        override val timestamp: Instant,
        override val paymentId: String,
        override val aggregateVersion: Int,
        val fromProvider: Provider,
        val toProvider: Provider,
        val failoverReason: String
    ) : PaymentEvent(
        eventId = eventId,
        eventType = "ProviderFailover",
        timestamp = timestamp,
        paymentId = paymentId,
        aggregateVersion = aggregateVersion
    )

    /**
     * Webhook Received Event
     * 
     * Published when a webhook is received from a provider.
     */
    data class WebhookReceived(
        override val eventId: String,
        override val timestamp: Instant,
        override val paymentId: String,
        override val aggregateVersion: Int,
        val provider: Provider,
        val webhookType: String,
        val webhookPayload: String
    ) : PaymentEvent(
        eventId = eventId,
        eventType = "WebhookReceived",
        timestamp = timestamp,
        paymentId = paymentId,
        aggregateVersion = aggregateVersion
    )

    /**
     * Idempotency Key Reused Event
     * 
     * Published when a duplicate request is detected.
     */
    data class IdempotencyKeyReused(
        override val eventId: String,
        override val timestamp: Instant,
        override val paymentId: String,
        override val aggregateVersion: Int,
        val idempotencyKey: String,
        val originalRequestTime: Instant
    ) : PaymentEvent(
        eventId = eventId,
        eventType = "IdempotencyKeyReused",
        timestamp = timestamp,
        paymentId = paymentId,
        aggregateVersion = aggregateVersion
    )

    /**
     * Circuit Breaker Opened Event
     * 
     * Published when circuit breaker opens for a provider.
     */
    data class CircuitBreakerOpened(
        override val eventId: String,
        override val timestamp: Instant,
        override val paymentId: String,
        override val aggregateVersion: Int,
        val provider: Provider,
        val failureRate: Double,
        val threshold: Double
    ) : PaymentEvent(
        eventId = eventId,
        eventType = "CircuitBreakerOpened",
        timestamp = timestamp,
        paymentId = paymentId,
        aggregateVersion = aggregateVersion
    )

    /**
     * Convert event to JSON for serialization
     */
    fun toJson(): String {
        return when (this) {
            is PaymentCreated -> """
                {
                    "eventId": "$eventId",
                    "eventType": "$eventType",
                    "timestamp": "$timestamp",
                    "paymentId": "$paymentId",
                    "aggregateVersion": $aggregateVersion,
                    "customerId": "$customerId",
                    "amount": $amount,
                    "currency": "$currency",
                    "paymentMethod": "$paymentMethod",
                    "provider": "$provider",
                    "description": ${description?.let { "\"$it\"" } ?: "null"},
                    "metadata": ${metadata.entries.joinToString(",", "{", "}") { "\"${it.key}\":\"${it.value}\"" }}
                }
            """.trimIndent()
            is PaymentAuthorized -> """
                {
                    "eventId": "$eventId",
                    "eventType": "$eventType",
                    "timestamp": "$timestamp",
                    "paymentId": "$paymentId",
                    "aggregateVersion": $aggregateVersion,
                    "provider": "$provider",
                    "providerTransactionId": "$providerTransactionId",
                    "authorizationCode": ${authorizationCode?.let { "\"$it\"" } ?: "null"}
                }
            """.trimIndent()
            is PaymentCaptured -> """
                {
                    "eventId": "$eventId",
                    "eventType": "$eventType",
                    "timestamp": "$timestamp",
                    "paymentId": "$paymentId",
                    "aggregateVersion": $aggregateVersion,
                    "provider": "$provider",
                    "capturedAmount": $capturedAmount,
                    "currency": "$currency"
                }
            """.trimIndent()
            is PaymentFailed -> """
                {
                    "eventId": "$eventId",
                    "eventType": "$eventType",
                    "timestamp": "$timestamp",
                    "paymentId": "$paymentId",
                    "aggregateVersion": $aggregateVersion,
                    "provider": "$provider",
                    "errorCode": "$errorCode",
                    "errorMessage": "$errorMessage",
                    "failureReason": "$failureReason"
                }
            """.trimIndent()
            is PaymentRefunded -> """
                {
                    "eventId": "$eventId",
                    "eventType": "$eventType",
                    "timestamp": "$timestamp",
                    "paymentId": "$paymentId",
                    "aggregateVersion": $aggregateVersion,
                    "provider": "$provider",
                    "refundAmount": $refundAmount,
                    "currency": "$currency",
                    "refundReason": ${refundReason?.let { "\"$it\"" } ?: "null"},
                    "refundId": "$refundId"
                }
            """.trimIndent()
            is PaymentStatusChanged -> """
                {
                    "eventId": "$eventId",
                    "eventType": "$eventType",
                    "timestamp": "$timestamp",
                    "paymentId": "$paymentId",
                    "aggregateVersion": $aggregateVersion,
                    "previousStatus": "$previousStatus",
                    "newStatus": "$newStatus",
                    "reason": ${reason?.let { "\"$it\"" } ?: "null"}
                }
            """.trimIndent()
            is PaymentRetryAttempted -> """
                {
                    "eventId": "$eventId",
                    "eventType": "$eventType",
                    "timestamp": "$timestamp",
                    "paymentId": "$paymentId",
                    "aggregateVersion": $aggregateVersion,
                    "provider": "$provider",
                    "attemptNumber": $attemptNumber,
                    "maxAttempts": $maxAttempts
                }
            """.trimIndent()
            is PaymentRetryExhausted -> """
                {
                    "eventId": "$eventId",
                    "eventType": "$eventType",
                    "timestamp": "$timestamp",
                    "paymentId": "$paymentId",
                    "aggregateVersion": $aggregateVersion,
                    "provider": "$provider",
                    "totalAttempts": $totalAttempts,
                    "lastError": "$lastError"
                }
            """.trimIndent()
            is ProviderFailover -> """
                {
                    "eventId": "$eventId",
                    "eventType": "$eventType",
                    "timestamp": "$timestamp",
                    "paymentId": "$paymentId",
                    "aggregateVersion": $aggregateVersion,
                    "fromProvider": "$fromProvider",
                    "toProvider": "$toProvider",
                    "failoverReason": "$failoverReason"
                }
            """.trimIndent()
            is WebhookReceived -> """
                {
                    "eventId": "$eventId",
                    "eventType": "$eventType",
                    "timestamp": "$timestamp",
                    "paymentId": "$paymentId",
                    "aggregateVersion": $aggregateVersion,
                    "provider": "$provider",
                    "webhookType": "$webhookType",
                    "webhookPayload": "$webhookPayload"
                }
            """.trimIndent()
            is IdempotencyKeyReused -> """
                {
                    "eventId": "$eventId",
                    "eventType": "$eventType",
                    "timestamp": "$timestamp",
                    "paymentId": "$paymentId",
                    "aggregateVersion": $aggregateVersion,
                    "idempotencyKey": "$idempotencyKey",
                    "originalRequestTime": "$originalRequestTime"
                }
            """.trimIndent()
            is CircuitBreakerOpened -> """
                {
                    "eventId": "$eventId",
                    "eventType": "$eventType",
                    "timestamp": "$timestamp",
                    "paymentId": "$paymentId",
                    "aggregateVersion": $aggregateVersion,
                    "provider": "$provider",
                    "failureRate": $failureRate,
                    "threshold": $threshold
                }
            """.trimIndent()
        }
    }
}


