package com.payment.orchestration.domain.model

import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

/**
 * Represents a payment request and its processing result.
 * This is the primary aggregate root in the payment domain.
 * 
 * A Payment encapsulates:
 * - The payment request details (amount, currency, method)
 * - The transaction lifecycle (status, provider, timestamps)
 * - Customer information
 * - Routing and retry context
 * 
 * This class is immutable to ensure thread safety and predictable behavior.
 * All state changes create new instances via copy methods.
 * 
 * @property id Unique payment identifier
 * @property transaction The underlying transaction details
 * @property routingStrategy Strategy used to select provider (e.g., "geographic_preference")
 * @property fallbackProviders List of fallback providers for retry
 * @property retryCount Number of retry attempts made
 * @property statusReason Human-readable reason for current status
 */
data class Payment(
    val id: String,
    val transaction: Transaction,
    val routingStrategy: String? = null,
    val fallbackProviders: List<Provider> = emptyList(),
    val retryCount: Int = 0,
    val statusReason: String? = null
) {
    init {
        // Validation: Payment ID must follow pattern
        require(id.matches(Regex("^pay_[a-zA-Z0-9]{10,}$"))) {
            "Payment ID must match pattern: pay_[alphanumeric]"
        }
        
        // Validation: Retry count must be non-negative
        require(retryCount >= 0) {
            "Retry count must be non-negative, got: $retryCount"
        }
        
        // Validation: Retry count should not exceed reasonable limit
        require(retryCount <= 10) {
            "Retry count exceeds maximum allowed (10), got: $retryCount"
        }
        
        // Validation: Routing strategy length
        routingStrategy?.let {
            require(it.length <= 100) {
                "Routing strategy must not exceed 100 characters"
            }
        }
        
        // Validation: Fallback providers should not include current provider
        transaction.provider?.let { currentProvider ->
            require(currentProvider !in fallbackProviders) {
                "Fallback providers should not include current provider: $currentProvider"
            }
        }
        
        // Validation: Status reason for failed/cancelled payments
        if (transaction.status in setOf(PaymentStatus.FAILED, PaymentStatus.CANCELLED)) {
            requireNotNull(statusReason) {
                "Status reason is required for ${transaction.status} payments"
            }
        }
    }
    
    // Delegate common properties to transaction for convenience
    val transactionId: String get() = transaction.transactionId
    val amount: Long get() = transaction.amount
    val currency: String get() = transaction.currency
    val paymentMethod: PaymentMethod get() = transaction.paymentMethod
    val status: PaymentStatus get() = transaction.status
    val provider: Provider? get() = transaction.provider
    val providerTransactionId: String? get() = transaction.providerTransactionId
    val customerId: String? get() = transaction.customerId
    val customerEmail: String? get() = transaction.customerEmail
    val metadata: Map<String, String> get() = transaction.metadata
    val createdAt: Instant get() = transaction.createdAt
    val updatedAt: Instant get() = transaction.updatedAt
    val completedAt: Instant? get() = transaction.completedAt
    
    /**
     * Returns the amount as a decimal value in the major currency unit.
     * 
     * @return Amount in major currency unit (e.g., 100.00 USD)
     */
    fun getAmountDecimal(): BigDecimal = transaction.getAmountDecimal()
    
    /**
     * Checks if this payment is in a terminal state.
     * 
     * @return true if payment cannot transition to another state
     */
    fun isTerminal(): Boolean = transaction.isTerminal()
    
    /**
     * Checks if this payment is still active (not terminal).
     * 
     * @return true if payment can still transition
     */
    fun isActive(): Boolean = transaction.isActive()
    
    /**
     * Checks if this payment has been retried.
     * 
     * @return true if retry count > 0
     */
    fun hasBeenRetried(): Boolean = retryCount > 0
    
    /**
     * Checks if this payment can be retried based on current state and retry count.
     * 
     * @param maxRetries Maximum allowed retries (default: 5)
     * @return true if payment can be retried
     */
    fun canRetry(maxRetries: Int = 5): Boolean {
        return !isTerminal() && 
               retryCount < maxRetries && 
               status in setOf(PaymentStatus.PROCESSING, PaymentStatus.RETRYING)
    }
    
    /**
     * Creates a new payment with updated transaction status.
     * 
     * @param newStatus The new status to transition to
     * @param newProvider Optional provider (if routing/failover)
     * @param newProviderTransactionId Optional provider transaction ID
     * @param reason Optional reason for status change
     * @return New payment instance with updated status
     * @throws IllegalStateException if transition is not allowed
     */
    fun withStatus(
        newStatus: PaymentStatus,
        newProvider: Provider? = this.provider,
        newProviderTransactionId: String? = this.providerTransactionId,
        reason: String? = null
    ): Payment {
        val updatedTransaction = transaction.withStatus(
            newStatus = newStatus,
            newProvider = newProvider,
            newProviderTransactionId = newProviderTransactionId
        )
        
        return copy(
            transaction = updatedTransaction,
            statusReason = reason
        )
    }
    
    /**
     * Creates a new payment with routing information.
     * Transitions status to ROUTING.
     * 
     * @param strategy Routing strategy used
     * @param primaryProvider Selected primary provider
     * @param fallbacks List of fallback providers
     * @return New payment instance with routing info
     */
    fun withRouting(
        strategy: String,
        primaryProvider: Provider,
        fallbacks: List<Provider>
    ): Payment {
        val updatedTransaction = transaction.withStatus(
            newStatus = PaymentStatus.ROUTING,
            newProvider = primaryProvider
        )
        
        return copy(
            transaction = updatedTransaction,
            routingStrategy = strategy,
            fallbackProviders = fallbacks
        )
    }
    
    /**
     * Creates a new payment with incremented retry count.
     * Transitions status to RETRYING.
     * 
     * @param reason Reason for retry
     * @return New payment instance with incremented retry count
     */
    fun withRetry(reason: String): Payment {
        require(canRetry()) {
            "Payment cannot be retried: status=$status, retryCount=$retryCount"
        }
        
        val updatedTransaction = transaction.withStatus(
            newStatus = PaymentStatus.RETRYING
        )
        
        return copy(
            transaction = updatedTransaction,
            retryCount = retryCount + 1,
            statusReason = reason
        )
    }
    
    /**
     * Creates a new payment with failover to next provider.
     * Uses the next available fallback provider.
     * 
     * @param reason Reason for failover
     * @return New payment instance with next provider
     * @throws IllegalStateException if no fallback providers available
     */
    fun withFailover(reason: String): Payment {
        require(fallbackProviders.isNotEmpty()) {
            "No fallback providers available for failover"
        }
        
        val nextProvider = fallbackProviders.first()
        val remainingFallbacks = fallbackProviders.drop(1)
        
        val updatedTransaction = transaction.withStatus(
            newStatus = PaymentStatus.PROCESSING,
            newProvider = nextProvider,
            newProviderTransactionId = null // Reset provider transaction ID
        )
        
        return copy(
            transaction = updatedTransaction,
            fallbackProviders = remainingFallbacks,
            retryCount = retryCount + 1,
            statusReason = reason
        )
    }
    
    /**
     * Creates a new payment with additional metadata.
     * 
     * @param additionalMetadata Metadata to add or update
     * @return New payment instance with updated metadata
     */
    fun withMetadata(additionalMetadata: Map<String, String>): Payment {
        val updatedTransaction = transaction.withMetadata(additionalMetadata)
        return copy(transaction = updatedTransaction)
    }
    
    /**
     * Creates a new payment marked as succeeded.
     * 
     * @param providerTransactionId Provider's transaction ID
     * @return New payment instance with succeeded status
     */
    fun markAsSucceeded(providerTransactionId: String): Payment {
        return withStatus(
            newStatus = PaymentStatus.SUCCEEDED,
            newProviderTransactionId = providerTransactionId,
            reason = "Payment completed successfully"
        )
    }
    
    /**
     * Creates a new payment marked as failed.
     * 
     * @param reason Reason for failure
     * @return New payment instance with failed status
     */
    fun markAsFailed(reason: String): Payment {
        return withStatus(
            newStatus = PaymentStatus.FAILED,
            reason = reason
        )
    }
    
    /**
     * Creates a new payment marked as cancelled.
     * 
     * @param reason Reason for cancellation
     * @return New payment instance with cancelled status
     */
    fun markAsCancelled(reason: String): Payment {
        return withStatus(
            newStatus = PaymentStatus.CANCELLED,
            reason = reason
        )
    }
    
    companion object {
        /**
         * Creates a new payment from a transaction.
         * 
         * @param transaction The underlying transaction
         * @return New payment instance
         */
        fun fromTransaction(transaction: Transaction): Payment {
            val paymentId = "pay_${transaction.transactionId.removePrefix("txn_")}"
            return Payment(
                id = paymentId,
                transaction = transaction
            )
        }
        
        /**
         * Creates a new payment with initial state.
         * 
         * @param amount Payment amount in smallest currency unit
         * @param currency ISO 4217 currency code
         * @param paymentMethod Payment method type
         * @param idempotencyKey Optional idempotency key
         * @param customerId Optional customer identifier
         * @param customerEmail Optional customer email
         * @param metadata Optional metadata
         * @return New payment in INITIATED status
         */
        fun create(
            amount: Long,
            currency: String,
            paymentMethod: PaymentMethod,
            idempotencyKey: String? = null,
            customerId: String? = null,
            customerEmail: String? = null,
            metadata: Map<String, String> = emptyMap()
        ): Payment {
            val transactionId = Transaction.generateTransactionId()
            val transaction = Transaction.create(
                transactionId = transactionId,
                amount = amount,
                currency = currency,
                paymentMethod = paymentMethod,
                idempotencyKey = idempotencyKey,
                customerId = customerId,
                customerEmail = customerEmail,
                metadata = metadata
            )
            
            return fromTransaction(transaction)
        }
        
        /**
         * Generates a new payment ID with the standard prefix.
         * 
         * @return New payment ID (e.g., "pay_abc123def456")
         */
        fun generatePaymentId(): String {
            val randomPart = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
            return "pay_$randomPart"
        }
    }
}


