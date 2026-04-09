package com.payment.orchestration.provider

import com.payment.orchestration.domain.model.Payment
import com.payment.orchestration.domain.model.Provider
import java.math.BigDecimal

/**
 * Payment Provider Interface
 * 
 * Abstraction for all payment service providers (PSPs).
 * Each provider implementation handles communication with external payment gateways.
 * 
 * Design Principles:
 * - Provider-agnostic interface
 * - Consistent error handling
 * - Idempotency support
 * - Timeout handling
 * - Circuit breaker compatibility
 * 
 * Implementations:
 * - ProviderA: Handles CARD payments (Stripe-like)
 * - ProviderB: Handles UPI payments (Razorpay-like)
 * 
 * @see ProviderA
 * @see ProviderB
 */
interface PaymentProvider {
    
    /**
     * Returns the provider identifier.
     * Used by routing engine for provider selection.
     * 
     * @return Provider enum value
     */
    fun getProviderId(): Provider
    
    /**
     * Processes a payment transaction.
     * 
     * This method:
     * - Validates payment details
     * - Calls external payment gateway API
     * - Handles provider-specific responses
     * - Maps provider response to domain model
     * - Implements idempotency via provider's idempotency key
     * 
     * Idempotency:
     * - Uses payment.transactionId as idempotency key
     * - Provider must deduplicate requests with same key
     * - Safe to retry on network failures
     * 
     * Timeout:
     * - Must complete within 30 seconds
     * - Throws ProviderTimeoutException on timeout
     * 
     * Error Handling:
     * - Transient errors: Throw ProviderTransientException (retryable)
     * - Permanent errors: Throw ProviderPermanentException (not retryable)
     * - Network errors: Throw ProviderNetworkException (retryable)
     * 
     * @param payment The payment to process
     * @return ProviderResponse with transaction result
     * @throws ProviderTransientException Temporary failure, can retry
     * @throws ProviderPermanentException Permanent failure, cannot retry
     * @throws ProviderNetworkException Network failure, can retry
     * @throws ProviderTimeoutException Request timeout, can retry
     */
    fun processPayment(payment: Payment): ProviderResponse
    
    /**
     * Checks the health status of the provider.
     *
     * Used by:
     * - Circuit breaker to determine provider availability
     * - Health check endpoints
     * - Routing engine for provider selection
     *
     * Implementation should:
     * - Call provider's health/ping endpoint
     * - Complete within 5 seconds
     * - Return false on any error
     *
     * @return true if provider is healthy, false otherwise
     */
    fun isHealthy(): Boolean
    
    /**
     * Checks if the provider is available for processing payments.
     *
     * This is a lightweight check that doesn't make external calls.
     * Used by health check service for quick availability checks.
     *
     * @return true if provider is available, false otherwise
     */
    fun isAvailable(): Boolean = isHealthy()
    
    /**
     * Retrieves the current status of a transaction from the provider.
     * 
     * Used for:
     * - Reconciliation
     * - Status polling for async payments
     * - Webhook validation
     * 
     * @param transactionId The transaction identifier
     * @return ProviderResponse with current status
     * @throws ProviderException on any error
     */
    fun getTransactionStatus(transactionId: String): ProviderResponse
}

/**
 * Provider Response
 * 
 * Standardized response from payment providers.
 * Maps provider-specific responses to common format.
 * 
 * @property success Whether the payment was successful
 * @property providerTransactionId Provider's internal transaction ID
 * @property providerStatus Provider's status code/message
 * @property errorCode Standardized error code (if failed)
 * @property errorMessage Human-readable error message (if failed)
 * @property isTransient Whether the error is transient (retryable)
 * @property metadata Additional provider-specific data
 */
data class ProviderResponse(
    val success: Boolean,
    val providerTransactionId: String,
    val providerStatus: String,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val isTransient: Boolean = false,
    val metadata: Map<String, Any> = emptyMap()
) {
    /**
     * Checks if the response indicates a retryable failure.
     * 
     * @return true if failure is transient and can be retried
     */
    fun isRetryable(): Boolean = !success && isTransient
    
    /**
     * Checks if the response indicates a permanent failure.
     * 
     * @return true if failure is permanent and should not be retried
     */
    fun isPermanentFailure(): Boolean = !success && !isTransient
}

/**
 * Base exception for all provider errors.
 */
sealed class ProviderException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * Transient provider error (retryable).
 * Examples: Rate limiting, temporary service unavailability, timeout
 */
class ProviderTransientException(
    message: String,
    cause: Throwable? = null
) : ProviderException(message, cause)

/**
 * Permanent provider error (not retryable).
 * Examples: Invalid card, insufficient funds, fraud detection
 */
class ProviderPermanentException(
    message: String,
    cause: Throwable? = null
) : ProviderException(message, cause)

/**
 * Network communication error (retryable).
 * Examples: Connection timeout, DNS failure, socket error
 */
class ProviderNetworkException(
    message: String,
    cause: Throwable? = null
) : ProviderException(message, cause)

/**
 * Provider request timeout (retryable).
 * Request exceeded maximum allowed time.
 */
class ProviderTimeoutException(
    message: String,
    cause: Throwable? = null
) : ProviderException(message, cause)


