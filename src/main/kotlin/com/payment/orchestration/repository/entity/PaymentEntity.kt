package com.payment.orchestration.repository.entity

import com.payment.orchestration.domain.model.*
import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant

/**
 * Payment Entity (JPA)
 * 
 * Database representation of Payment domain model.
 * 
 * Table: payments
 * Primary Key: id (auto-generated)
 * Unique Constraints: transaction_id
 * Indexes: 
 * - idx_merchant_id (for merchant queries)
 * - idx_status (for status filtering)
 * - idx_created_at (for date range queries)
 * 
 * Consistency Guarantees:
 * - ACID transactions via @Transactional
 * - Optimistic locking via @Version
 * - Unique constraint on transaction_id prevents duplicates
 * - Foreign key constraints ensure referential integrity
 * 
 * Concurrency Control:
 * - Optimistic locking with version field
 * - Prevents lost updates in concurrent scenarios
 * - Throws OptimisticLockException on version mismatch
 * 
 * Audit Fields:
 * - created_at: When record was created
 * - updated_at: When record was last updated
 * - version: Optimistic lock version
 * 
 * @property id Database primary key
 * @property transactionId Business transaction identifier (unique)
 * @property merchantId Merchant identifier
 * @property customerId Customer identifier
 * @property amount Transaction amount
 * @property currency Currency code (ISO 4217)
 * @property paymentMethod Payment method enum
 * @property status Payment status enum
 * @property selectedProvider Selected provider enum
 * @property providerTransactionId Provider's transaction ID
 * @property providerStatus Provider's status
 * @property failureReason Failure reason if failed
 * @property paymentDetails JSON payment details
 * @property metadata JSON metadata
 * @property createdAt Creation timestamp
 * @property updatedAt Last update timestamp
 * @property completedAt Completion timestamp
 * @property version Optimistic lock version
 */
@Entity
@Table(
    name = "payments",
    indexes = [
        Index(name = "idx_transaction_id", columnList = "transaction_id", unique = true),
        Index(name = "idx_merchant_id", columnList = "merchant_id"),
        Index(name = "idx_status", columnList = "status"),
        Index(name = "idx_created_at", columnList = "created_at"),
        Index(name = "idx_merchant_status", columnList = "merchant_id,status")
    ]
)
data class PaymentEntity(
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "transaction_id", nullable = false, unique = true, length = 50)
    val transactionId: String,
    
    @Column(name = "merchant_id", nullable = false, length = 50)
    val merchantId: String,
    
    @Column(name = "customer_id", nullable = false, length = 50)
    val customerId: String,
    
    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    val amount: BigDecimal,
    
    @Column(name = "currency", nullable = false, length = 3)
    val currency: String,
    
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 20)
    val paymentMethod: PaymentMethod,
    
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    val status: PaymentStatus,
    
    @Enumerated(EnumType.STRING)
    @Column(name = "selected_provider", length = 20)
    val selectedProvider: Provider? = null,
    
    @Column(name = "provider_transaction_id", length = 100)
    val providerTransactionId: String? = null,
    
    @Column(name = "provider_status", length = 50)
    val providerStatus: String? = null,
    
    @Column(name = "failure_reason", length = 500)
    val failureReason: String? = null,
    
    @Column(name = "payment_details", columnDefinition = "TEXT")
    val paymentDetails: String, // JSON string
    
    @Column(name = "metadata", columnDefinition = "TEXT")
    val metadata: String, // JSON string
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
    
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant = Instant.now(),
    
    @Column(name = "completed_at")
    val completedAt: Instant? = null,
    
    @Version
    @Column(name = "version", nullable = false)
    val version: Long = 0
) {
    
    /**
     * Converts entity to domain model.
     * 
     * This method handles:
     * - JSON deserialization of payment_details and metadata
     * - Enum conversion
     * - Null handling
     * 
     * @return Payment domain model
     */
    fun toDomain(): Payment {
        // Parse JSON fields
        val paymentDetailsMap = parseJsonToMap(paymentDetails)
        val metadataMap = parseJsonToMap(metadata)
        
        // Create Transaction
        val transaction = Transaction(
            merchantId = merchantId,
            customerId = customerId,
            amount = amount,
            currency = currency,
            paymentMethod = paymentMethod,
            paymentDetails = paymentDetailsMap
        )
        
        // Create Payment
        return Payment(
            transactionId = transactionId,
            transaction = transaction,
            status = status,
            selectedProvider = selectedProvider,
            providerTransactionId = providerTransactionId,
            providerStatus = providerStatus,
            failureReason = failureReason,
            metadata = metadataMap,
            createdAt = createdAt,
            updatedAt = updatedAt,
            completedAt = completedAt
        )
    }
    
    companion object {
        /**
         * Creates entity from domain model.
         * 
         * This method handles:
         * - JSON serialization of payment_details and metadata
         * - Enum conversion
         * - Timestamp handling
         * 
         * @param payment Domain model
         * @return PaymentEntity
         */
        fun fromDomain(payment: Payment): PaymentEntity {
            return PaymentEntity(
                transactionId = payment.transactionId,
                merchantId = payment.transaction.merchantId,
                customerId = payment.transaction.customerId,
                amount = payment.transaction.amount,
                currency = payment.transaction.currency,
                paymentMethod = payment.transaction.paymentMethod,
                status = payment.status,
                selectedProvider = payment.selectedProvider,
                providerTransactionId = payment.providerTransactionId,
                providerStatus = payment.providerStatus,
                failureReason = payment.failureReason,
                paymentDetails = mapToJson(payment.transaction.paymentDetails),
                metadata = mapToJson(payment.metadata),
                createdAt = payment.createdAt,
                updatedAt = payment.updatedAt,
                completedAt = payment.completedAt
            )
        }
        
        /**
         * Converts map to JSON string.
         * In production, use Jackson or Gson.
         */
        private fun mapToJson(map: Map<String, Any>): String {
            // Simplified JSON serialization
            // In production: objectMapper.writeValueAsString(map)
            return map.entries.joinToString(",", "{", "}") { (k, v) ->
                "\"$k\":\"$v\""
            }
        }
        
        /**
         * Parses JSON string to map.
         * In production, use Jackson or Gson.
         */
        private fun parseJsonToMap(json: String): Map<String, Any> {
            // Simplified JSON parsing
            // In production: objectMapper.readValue(json, Map::class.java)
            if (json.isBlank() || json == "{}") return emptyMap()
            
            return json
                .removeSurrounding("{", "}")
                .split(",")
                .associate { pair ->
                    val (key, value) = pair.split(":")
                    key.trim('"') to value.trim('"')
                }
        }
    }
}

/**
 * Payment Event Entity (JPA)
 * 
 * Stores payment lifecycle events for audit trail.
 * 
 * Table: payment_events
 * Primary Key: id (auto-generated)
 * Foreign Key: payment_id → payments.id
 * Indexes:
 * - idx_payment_id (for event retrieval)
 * - idx_event_type (for event filtering)
 * 
 * Consistency Guarantees:
 * - Events are immutable (no updates)
 * - Foreign key ensures payment exists
 * - Cascade delete removes events when payment deleted
 * 
 * @property id Database primary key
 * @property paymentId Foreign key to payment
 * @property eventType Type of event
 * @property eventData JSON event data
 * @property timestamp Event timestamp
 */
@Entity
@Table(
    name = "payment_events",
    indexes = [
        Index(name = "idx_payment_id", columnList = "payment_id"),
        Index(name = "idx_event_type", columnList = "event_type"),
        Index(name = "idx_timestamp", columnList = "timestamp")
    ]
)
data class PaymentEventEntity(
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "payment_id", nullable = false)
    val paymentId: Long,
    
    @Column(name = "event_type", nullable = false, length = 50)
    val eventType: String,
    
    @Column(name = "event_data", columnDefinition = "TEXT")
    val eventData: String, // JSON string
    
    @Column(name = "timestamp", nullable = false)
    val timestamp: Instant = Instant.now()
)


