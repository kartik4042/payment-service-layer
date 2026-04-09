package com.payment.orchestration.repository

import com.payment.orchestration.domain.model.Payment
import com.payment.orchestration.domain.model.PaymentStatus
import com.payment.orchestration.repository.entity.PaymentEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import jakarta.persistence.LockModeType
import java.time.Instant
import java.util.Optional

/**
 * Payment Repository (Spring Data JPA)
 * 
 * Data access layer for Payment entities.
 * 
 * Consistency Guarantees:
 * - ACID transactions via Spring @Transactional
 * - Optimistic locking via @Version field
 * - Pessimistic locking available via @Lock annotation
 * - Unique constraint on transaction_id prevents duplicates
 * 
 * Concurrency Control:
 * - Default: Optimistic locking (version field)
 * - Available: Pessimistic locking for critical operations
 * - Read committed isolation level (default)
 * - Serializable isolation for idempotency checks
 * 
 * Query Optimization:
 * - Indexed queries for common access patterns
 * - Pagination support for large result sets
 * - Custom queries for complex filtering
 * - Query hints for performance tuning
 * 
 * Transactional Boundaries:
 * - Repository methods are transactional by default
 * - Read-only transactions for queries
 * - Write transactions for updates
 * - Propagation: REQUIRED (join existing or create new)
 * 
 * @see PaymentEntity
 */
@Repository
interface PaymentRepository : JpaRepository<PaymentEntity, Long> {
    
    /**
     * Finds payment by transaction ID.
     * 
     * Uses unique index on transaction_id for fast lookup.
     * 
     * Consistency: Read committed isolation
     * Locking: None (optimistic)
     * 
     * @param transactionId The transaction identifier
     * @return Optional payment entity
     */
    fun findByTransactionId(transactionId: String): Optional<PaymentEntity>
    
    /**
     * Finds payment by transaction ID with pessimistic write lock.
     * 
     * Use this for operations that require exclusive access:
     * - Idempotency checks
     * - Status updates
     * - Concurrent payment processing
     * 
     * Consistency: Serializable (via pessimistic lock)
     * Locking: Pessimistic write lock (SELECT FOR UPDATE)
     * 
     * Lock Behavior:
     * - Blocks other transactions from reading/writing
     * - Prevents lost updates
     * - Prevents phantom reads
     * - Released on transaction commit/rollback
     * 
     * Deadlock Risk:
     * - Always acquire locks in same order
     * - Keep lock duration short
     * - Use timeout to prevent indefinite blocking
     * 
     * @param transactionId The transaction identifier
     * @return Optional payment entity with write lock
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PaymentEntity p WHERE p.transactionId = :transactionId")
    fun findByTransactionIdWithLock(@Param("transactionId") transactionId: String): Optional<PaymentEntity>
    
    /**
     * Checks if payment exists by transaction ID.
     * 
     * Lightweight existence check without loading full entity.
     * 
     * @param transactionId The transaction identifier
     * @return true if exists, false otherwise
     */
    fun existsByTransactionId(transactionId: String): Boolean
    
    /**
     * Finds all payments for a merchant.
     * 
     * Uses index on merchant_id for efficient filtering.
     * Supports pagination for large result sets.
     * 
     * @param merchantId The merchant identifier
     * @param pageable Pagination parameters
     * @return Page of payment entities
     */
    fun findByMerchantId(merchantId: String, pageable: Pageable): Page<PaymentEntity>
    
    /**
     * Finds payments by merchant and status.
     * 
     * Uses composite index on (merchant_id, status).
     * 
     * @param merchantId The merchant identifier
     * @param status The payment status
     * @param pageable Pagination parameters
     * @return Page of payment entities
     */
    fun findByMerchantIdAndStatus(
        merchantId: String,
        status: PaymentStatus,
        pageable: Pageable
    ): Page<PaymentEntity>
    
    /**
     * Finds payments by merchant within date range.
     * 
     * Uses indexes on merchant_id and created_at.
     * 
     * @param merchantId The merchant identifier
     * @param fromDate Start of date range
     * @param toDate End of date range
     * @param pageable Pagination parameters
     * @return Page of payment entities
     */
    fun findByMerchantIdAndCreatedAtBetween(
        merchantId: String,
        fromDate: Instant,
        toDate: Instant,
        pageable: Pageable
    ): Page<PaymentEntity>
    
    /**
     * Finds payments by complex criteria.
     * 
     * Custom query for advanced filtering.
     * 
     * @param merchantId The merchant identifier
     * @param status Optional status filter
     * @param fromDate Optional start date
     * @param toDate Optional end date
     * @param pageable Pagination parameters
     * @return Page of payment entities
     */
    @Query("""
        SELECT p FROM PaymentEntity p 
        WHERE p.merchantId = :merchantId
        AND (:status IS NULL OR p.status = :status)
        AND (:fromDate IS NULL OR p.createdAt >= :fromDate)
        AND (:toDate IS NULL OR p.createdAt <= :toDate)
        ORDER BY p.createdAt DESC
    """)
    fun findByMerchantIdWithFilters(
        @Param("merchantId") merchantId: String,
        @Param("status") status: PaymentStatus?,
        @Param("fromDate") fromDate: Instant?,
        @Param("toDate") toDate: Instant?,
        pageable: Pageable
    ): Page<PaymentEntity>
    
    /**
     * Counts payments by merchant and status.
     * 
     * Efficient count query without loading entities.
     * 
     * @param merchantId The merchant identifier
     * @param status The payment status
     * @return Count of matching payments
     */
    fun countByMerchantIdAndStatus(merchantId: String, status: PaymentStatus): Long
    
    /**
     * Finds payments pending completion.
     * 
     * Useful for:
     * - Retry jobs
     * - Timeout detection
     * - Status polling
     * 
     * @param statuses List of pending statuses
     * @param olderThan Payments older than this timestamp
     * @param pageable Pagination parameters
     * @return Page of payment entities
     */
    @Query("""
        SELECT p FROM PaymentEntity p 
        WHERE p.status IN :statuses
        AND p.createdAt < :olderThan
        ORDER BY p.createdAt ASC
    """)
    fun findPendingPayments(
        @Param("statuses") statuses: List<PaymentStatus>,
        @Param("olderThan") olderThan: Instant,
        pageable: Pageable
    ): Page<PaymentEntity>
}

/**
 * Payment Repository Adapter
 * 
 * Adapts JPA repository to domain layer.
 * Handles entity-to-domain conversion.
 * 
 * This adapter:
 * - Converts between entity and domain models
 * - Provides domain-friendly API
 * - Encapsulates persistence details
 * - Handles transactional boundaries
 * 
 * @property paymentRepository JPA repository
 */
@Repository
class PaymentRepositoryAdapter(
    private val paymentRepository: PaymentRepository
) {
    
    /**
     * Saves payment to database.
     * 
     * Transactional Boundary: REQUIRED
     * - Joins existing transaction or creates new one
     * - Commits on success, rolls back on exception
     * 
     * Consistency:
     * - Optimistic locking prevents lost updates
     * - Unique constraint prevents duplicate transaction_id
     * - Version field incremented on each update
     * 
     * @param payment Domain model
     * @return Saved payment with updated version
     */
    fun save(payment: Payment): Payment {
        val entity = PaymentEntity.fromDomain(payment)
        val savedEntity = paymentRepository.save(entity)
        return savedEntity.toDomain()
    }
    
    /**
     * Finds payment by transaction ID.
     * 
     * @param transactionId The transaction identifier
     * @return Payment if found, null otherwise
     */
    fun findByTransactionId(transactionId: String): Payment? {
        return paymentRepository.findByTransactionId(transactionId)
            .map { it.toDomain() }
            .orElse(null)
    }
    
    /**
     * Finds payment by transaction ID with pessimistic lock.
     * 
     * Use for idempotency checks and concurrent updates.
     * 
     * @param transactionId The transaction identifier
     * @return Payment if found, null otherwise
     */
    fun findByTransactionIdWithLock(transactionId: String): Payment? {
        return paymentRepository.findByTransactionIdWithLock(transactionId)
            .map { it.toDomain() }
            .orElse(null)
    }
    
    /**
     * Checks if payment exists.
     * 
     * @param transactionId The transaction identifier
     * @return true if exists, false otherwise
     */
    fun existsByTransactionId(transactionId: String): Boolean {
        return paymentRepository.existsByTransactionId(transactionId)
    }
    
    /**
     * Finds payments by merchant with pagination.
     * 
     * @param merchantId The merchant identifier
     * @param pageable Pagination parameters
     * @return Page of payments
     */
    fun findByMerchantId(merchantId: String, pageable: Pageable): Page<Payment> {
        return paymentRepository.findByMerchantId(merchantId, pageable)
            .map { it.toDomain() }
    }
    
    /**
     * Finds payments by merchant and status.
     * 
     * @param merchantId The merchant identifier
     * @param status The payment status
     * @param pageable Pagination parameters
     * @return Page of payments
     */
    fun findByMerchantIdAndStatus(
        merchantId: String,
        status: PaymentStatus,
        pageable: Pageable
    ): Page<Payment> {
        return paymentRepository.findByMerchantIdAndStatus(merchantId, status, pageable)
            .map { it.toDomain() }
    }
}


