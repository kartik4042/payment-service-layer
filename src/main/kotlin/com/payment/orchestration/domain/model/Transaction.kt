package com.payment.orchestration.domain.model

import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

/**
 * Represents a payment transaction in the system.
 * This is an immutable value object that captures the complete state of a payment.
 * 
 * Immutability ensures:
 * - Thread safety
 * - Predictable behavior
 * - Audit trail integrity
 * - Easy testing
 * 
 * @property transactionId Unique identifier for this transaction (e.g., "txn_1a2b3c4d5e")
 * @property idempotencyKey Client-provided key for idempotency (optional)
 * @property amount Payment amount in smallest currency unit (cents)
 * @property currency ISO 4217 currency code
 * @property paymentMethod Type of payment method used
 * @property status Current status of the transaction
 * @property provider Payment provider handling this transaction (if routed)
 * @property providerTransactionId Provider's transaction identifier (if available)
 * @property customerId Customer identifier (optional)
 * @property customerEmail Customer email (optional)
 * @property metadata Additional key-value metadata
 * @property createdAt Transaction creation timestamp
 * @property updatedAt Last update timestamp
 * @property completedAt Completion timestamp (for terminal states)
 * @property version Optimistic locking version number
 */
data class Transaction(
    val transactionId: String,
    val idempotencyKey: String? = null,
    val amount: Long,
    val currency: String,
    val paymentMethod: PaymentMethod,
    val status: PaymentStatus,
    val provider: Provider? = null,
    val providerTransactionId: String? = null,
    val customerId: String? = null,
    val customerEmail: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant? = null,
    val version: Int = 1
) {
    init {
        // Validation: Transaction ID must follow pattern
        require(transactionId.matches(Regex("^txn_[a-zA-Z0-9]{10,}$"))) {
            "Transaction ID must match pattern: txn_[alphanumeric]"
        }
        
        // Validation: Amount must be positive
        require(amount > 0) {
            "Amount must be greater than 0, got: $amount"
        }
        
        // Validation: Amount must be within reasonable bounds
        require(amount <= 99_999_999_999L) {
            "Amount exceeds maximum allowed: $amount"
        }
        
        // Validation: Currency must be valid ISO 4217 code
        require(currency.matches(Regex("^[A-Z]{3}$"))) {
            "Currency must be a valid ISO 4217 code (3 uppercase letters), got: $currency"
        }
        
        // Validation: Currency must be a recognized currency
        runCatching { Currency.getInstance(currency) }.getOrElse {
            throw IllegalArgumentException("Invalid currency code: $currency")
        }
        
        // Validation: Idempotency key length
        idempotencyKey?.let {
            require(it.length <= 255) {
                "Idempotency key must not exceed 255 characters, got: ${it.length}"
            }
        }
        
        // Validation: Customer email format
        customerEmail?.let {
            require(it.matches(Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"))) {
                "Invalid email format: $it"
            }
        }
        
        // Validation: Metadata size
        require(metadata.size <= 50) {
            "Metadata cannot exceed 50 entries, got: ${metadata.size}"
        }
        
        // Validation: Metadata key/value lengths
        metadata.forEach { (key, value) ->
            require(key.length <= 40) {
                "Metadata key must not exceed 40 characters: $key"
            }
            require(value.length <= 500) {
                "Metadata value must not exceed 500 characters for key: $key"
            }
        }
        
        // Validation: Completed timestamp only for terminal states
        if (status.isTerminal) {
            requireNotNull(completedAt) {
                "Completed timestamp is required for terminal status: $status"
            }
        } else {
            require(completedAt == null) {
                "Completed timestamp must be null for non-terminal status: $status"
            }
        }
        
        // Validation: Provider required for certain statuses
        if (status in setOf(PaymentStatus.PROCESSING, PaymentStatus.SUCCEEDED, PaymentStatus.PENDING)) {
            requireNotNull(provider) {
                "Provider is required for status: $status"
            }
        }
        
        // Validation: Timestamps order
        require(!updatedAt.isBefore(createdAt)) {
            "Updated timestamp cannot be before created timestamp"
        }
        
        completedAt?.let {
            require(!it.isBefore(createdAt)) {
                "Completed timestamp cannot be before created timestamp"
            }
        }
        
        // Validation: Version must be positive
        require(version > 0) {
            "Version must be positive, got: $version"
        }
    }
    
    /**
     * Returns the amount as a decimal value in the major currency unit.
     * For example, 10000 cents becomes 100.00 USD.
     * 
     * @return Amount in major currency unit
     */
    fun getAmountDecimal(): BigDecimal {
        val currencyInstance = Currency.getInstance(currency)
        val scale = currencyInstance.defaultFractionDigits
        return BigDecimal.valueOf(amount).movePointLeft(scale)
    }
    
    /**
     * Checks if this transaction is in a terminal state.
     * 
     * @return true if status is terminal (no further transitions)
     */
    fun isTerminal(): Boolean = status.isTerminal
    
    /**
     * Checks if this transaction is still active (not terminal).
     * 
     * @return true if status is not terminal
     */
    fun isActive(): Boolean = !status.isTerminal
    
    /**
     * Creates a new transaction with updated status.
     * Validates that the status transition is allowed.
     * 
     * @param newStatus The new status to transition to
     * @param newProvider Optional provider (if routing)
     * @param newProviderTransactionId Optional provider transaction ID
     * @return New transaction instance with updated status
     * @throws IllegalStateException if transition is not allowed
     */
    fun withStatus(
        newStatus: PaymentStatus,
        newProvider: Provider? = this.provider,
        newProviderTransactionId: String? = this.providerTransactionId
    ): Transaction {
        require(status.canTransitionTo(newStatus)) {
            "Invalid status transition from $status to $newStatus"
        }
        
        return copy(
            status = newStatus,
            provider = newProvider,
            providerTransactionId = newProviderTransactionId,
            updatedAt = Instant.now(),
            completedAt = if (newStatus.isTerminal) Instant.now() else null,
            version = version + 1
        )
    }
    
    /**
     * Creates a new transaction with updated metadata.
     * Merges new metadata with existing metadata.
     * 
     * @param additionalMetadata Metadata to add or update
     * @return New transaction instance with updated metadata
     */
    fun withMetadata(additionalMetadata: Map<String, String>): Transaction {
        val mergedMetadata = metadata + additionalMetadata
        require(mergedMetadata.size <= 50) {
            "Merged metadata cannot exceed 50 entries"
        }
        
        return copy(
            metadata = mergedMetadata,
            updatedAt = Instant.now()
        )
    }
    
    /**
     * Creates a new transaction with incremented version (for optimistic locking).
     * 
     * @return New transaction instance with incremented version
     */
    fun withIncrementedVersion(): Transaction = copy(version = version + 1)
    
    companion object {
        /**
         * Creates a new transaction with initial state.
         * 
         * @param transactionId Unique transaction identifier
         * @param amount Payment amount in smallest currency unit
         * @param currency ISO 4217 currency code
         * @param paymentMethod Payment method type
         * @param idempotencyKey Optional idempotency key
         * @param customerId Optional customer identifier
         * @param customerEmail Optional customer email
         * @param metadata Optional metadata
         * @return New transaction in INITIATED status
         */
        fun create(
            transactionId: String,
            amount: Long,
            currency: String,
            paymentMethod: PaymentMethod,
            idempotencyKey: String? = null,
            customerId: String? = null,
            customerEmail: String? = null,
            metadata: Map<String, String> = emptyMap()
        ): Transaction {
            val now = Instant.now()
            return Transaction(
                transactionId = transactionId,
                idempotencyKey = idempotencyKey,
                amount = amount,
                currency = currency,
                paymentMethod = paymentMethod,
                status = PaymentStatus.INITIATED,
                provider = null,
                providerTransactionId = null,
                customerId = customerId,
                customerEmail = customerEmail,
                metadata = metadata,
                createdAt = now,
                updatedAt = now,
                completedAt = null,
                version = 1
            )
        }
        
        /**
         * Generates a new transaction ID with the standard prefix.
         * 
         * @return New transaction ID (e.g., "txn_abc123def456")
         */
        fun generateTransactionId(): String {
            val randomPart = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
            return "txn_$randomPart"
        }
    }
}


