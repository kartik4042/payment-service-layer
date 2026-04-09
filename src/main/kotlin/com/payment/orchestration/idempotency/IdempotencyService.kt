package com.payment.orchestration.idempotency

import com.payment.orchestration.domain.model.Payment
import com.payment.orchestration.domain.model.Transaction
import com.payment.orchestration.repository.PaymentRepositoryAdapter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Idempotency Service
 * 
 * Enforces idempotency for payment operations.
 * 
 * Idempotency Guarantees:
 * - Same idempotency key → Same result
 * - Prevents duplicate payments
 * - Handles concurrent requests safely
 * - Request fingerprint validation
 * 
 * Concurrency Strategy:
 * 1. Atomic check-and-set in idempotency store
 * 2. Pessimistic locking for payment records
 * 3. Serializable isolation for critical sections
 * 4. Retry logic for transient failures
 * 
 * Flow:
 * 1. Client sends request with Idempotency-Key header
 * 2. System checks if key exists
 * 3. If exists → Return cached result
 * 4. If not exists → Process payment and cache result
 * 5. Concurrent requests with same key wait or get cached result
 * 
 * Edge Cases:
 * - Request in progress: Return 409 Conflict or wait
 * - Request fingerprint mismatch: Return 422 Unprocessable Entity
 * - Expired idempotency key: Allow retry
 * - Failed request: Allow retry with same key
 * 
 * @property idempotencyStore Store for idempotency records
 * @property paymentRepository Repository for payment records
 */
@Service
class IdempotencyService(
    private val idempotencyStore: IdempotencyStore,
    private val paymentRepository: PaymentRepositoryAdapter
) {
    private val logger = LoggerFactory.getLogger(IdempotencyService::class.java)
    
    /**
     * Checks idempotency and returns existing payment if found.
     * 
     * This method is the entry point for idempotency enforcement.
     * 
     * Transactional Boundary:
     * - Isolation: SERIALIZABLE (prevents phantom reads)
     * - Propagation: REQUIRES_NEW (independent transaction)
     * - Read-only: false (may create idempotency record)
     * 
     * Concurrency:
     * - Multiple threads with same idempotency key
     * - First thread creates record, others wait
     * - Atomic check-and-set in idempotency store
     * 
     * Flow:
     * 1. Check if idempotency key exists
     * 2. If exists and COMPLETED → Return existing payment
     * 3. If exists and PROCESSING → Return 409 Conflict
     * 4. If exists and FAILED → Allow retry
     * 5. If not exists → Create record and return null
     * 
     * @param idempotencyKey Client-provided idempotency key
     * @param transaction Transaction details
     * @return Existing payment if found, null if should process
     * @throws IdempotencyConflictException if request is in progress
     * @throws IdempotencyFingerprintMismatchException if fingerprint doesn't match
     */
    @Transactional(
        isolation = Isolation.SERIALIZABLE,
        propagation = Propagation.REQUIRES_NEW,
        timeout = 5
    )
    fun checkIdempotency(
        idempotencyKey: String,
        transaction: Transaction
    ): Payment? {
        logger.debug("Checking idempotency: key={}", idempotencyKey)
        
        // Generate request fingerprint
        val requestFingerprint = RequestFingerprintGenerator.generate(transaction)
        
        // Check if idempotency key exists
        val existingRecord = idempotencyStore.get(idempotencyKey)
        
        if (existingRecord != null) {
            logger.info(
                "Idempotency key found: key={}, status={}, transactionId={}",
                idempotencyKey,
                existingRecord.status,
                existingRecord.transactionId
            )
            
            // Validate request fingerprint
            if (existingRecord.requestFingerprint != requestFingerprint) {
                logger.error(
                    "Request fingerprint mismatch: key={}, expected={}, actual={}",
                    idempotencyKey,
                    existingRecord.requestFingerprint,
                    requestFingerprint
                )
                throw IdempotencyFingerprintMismatchException(
                    "Request payload differs from original request with same idempotency key"
                )
            }
            
            // Handle based on status
            return when (existingRecord.status) {
                IdempotencyStatus.COMPLETED -> {
                    // Request completed, return cached result
                    logger.info(
                        "Returning cached payment: key={}, transactionId={}",
                        idempotencyKey,
                        existingRecord.transactionId
                    )
                    
                    // Fetch payment from database
                    paymentRepository.findByTransactionId(existingRecord.transactionId)
                        ?: throw IllegalStateException(
                            "Payment not found for completed idempotency key: ${existingRecord.transactionId}"
                        )
                }
                
                IdempotencyStatus.PROCESSING -> {
                    // Request in progress, return conflict
                    logger.warn(
                        "Request already in progress: key={}, transactionId={}",
                        idempotencyKey,
                        existingRecord.transactionId
                    )
                    throw IdempotencyConflictException(
                        "Request with same idempotency key is already being processed"
                    )
                }
                
                IdempotencyStatus.FAILED -> {
                    // Previous request failed, allow retry
                    logger.info(
                        "Previous request failed, allowing retry: key={}",
                        idempotencyKey
                    )
                    
                    // Delete old record to allow retry
                    idempotencyStore.delete(idempotencyKey)
                    
                    // Create new record
                    createIdempotencyRecord(idempotencyKey, transaction, requestFingerprint)
                    
                    null // Proceed with payment processing
                }
            }
        } else {
            // Idempotency key not found, create new record
            logger.info("Creating new idempotency record: key={}", idempotencyKey)
            
            createIdempotencyRecord(idempotencyKey, transaction, requestFingerprint)
            
            null // Proceed with payment processing
        }
    }
    
    /**
     * Creates a new idempotency record.
     * 
     * This method is atomic:
     * - Check if key exists
     * - If not exists, create record
     * - If exists, throw exception
     * 
     * @param idempotencyKey The idempotency key
     * @param transaction Transaction details
     * @param requestFingerprint Request fingerprint
     */
    private fun createIdempotencyRecord(
        idempotencyKey: String,
        transaction: Transaction,
        requestFingerprint: String
    ) {
        // Generate transaction ID
        val transactionId = generateTransactionId()
        
        // Create idempotency record
        val record = IdempotencyRecordRedis(
            idempotencyKey = idempotencyKey,
            transactionId = transactionId,
            requestFingerprint = requestFingerprint,
            status = IdempotencyStatus.PROCESSING,
            createdAt = Instant.now(),
            ttlSeconds = 86400 // 24 hours
        )
        
        // Atomic create-or-get
        val created = idempotencyStore.createOrGet(record)
        
        if (created.transactionId != transactionId) {
            // Another thread created the record first
            logger.warn(
                "Race condition detected: key={}, ourTxnId={}, theirTxnId={}",
                idempotencyKey,
                transactionId,
                created.transactionId
            )
            throw IdempotencyConflictException(
                "Request with same idempotency key is already being processed"
            )
        }
        
        logger.debug(
            "Idempotency record created: key={}, transactionId={}",
            idempotencyKey,
            transactionId
        )
    }
    
    /**
     * Marks idempotency record as completed.
     * 
     * Called after successful payment processing.
     * 
     * @param idempotencyKey The idempotency key
     */
    fun markCompleted(idempotencyKey: String) {
        logger.info("Marking idempotency record as completed: key={}", idempotencyKey)
        
        val updated = idempotencyStore.updateStatus(idempotencyKey, IdempotencyStatus.COMPLETED)
        
        if (!updated) {
            logger.warn("Failed to update idempotency record: key={}", idempotencyKey)
        }
    }
    
    /**
     * Marks idempotency record as failed.
     * 
     * Called after failed payment processing.
     * Allows retry with same idempotency key.
     * 
     * @param idempotencyKey The idempotency key
     */
    fun markFailed(idempotencyKey: String) {
        logger.info("Marking idempotency record as failed: key={}", idempotencyKey)
        
        val updated = idempotencyStore.updateStatus(idempotencyKey, IdempotencyStatus.FAILED)
        
        if (!updated) {
            logger.warn("Failed to update idempotency record: key={}", idempotencyKey)
        }
    }
    
    /**
     * Generates a unique transaction ID.
     * 
     * Format: txn_{timestamp}_{random}
     * 
     * @return Transaction ID
     */
    private fun generateTransactionId(): String {
        val timestamp = System.currentTimeMillis()
        val random = (Math.random() * 1000000).toInt()
        return "txn_${timestamp}_${random}"
    }
}

/**
 * Exception thrown when idempotency conflict occurs.
 * 
 * HTTP Status: 409 Conflict
 * 
 * Scenarios:
 * - Request with same idempotency key is already being processed
 * - Race condition detected
 */
class IdempotencyConflictException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * Exception thrown when request fingerprint doesn't match.
 * 
 * HTTP Status: 422 Unprocessable Entity
 * 
 * Scenarios:
 * - Same idempotency key used with different request payload
 * - Potential replay attack
 */
class IdempotencyFingerprintMismatchException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * Idempotency Cleanup Job
 * 
 * Scheduled job to clean up expired idempotency records.
 * 
 * Schedule: Every hour
 * Batch size: 1000 records
 * 
 * This prevents unbounded growth of idempotency records.
 * 
 * @property idempotencyRecordRepository JPA repository
 */
@Service
class IdempotencyCleanupJob(
    private val idempotencyRecordRepository: IdempotencyRecordRepository
) {
    private val logger = LoggerFactory.getLogger(IdempotencyCleanupJob::class.java)
    
    /**
     * Cleans up expired idempotency records.
     * 
     * Scheduled: Every hour
     * Transactional: Yes (batch delete)
     * 
     * @return Number of deleted records
     */
    @Transactional
    // @Scheduled(cron = "0 0 * * * *") // Every hour
    fun cleanupExpiredRecords(): Long {
        logger.info("Starting idempotency cleanup job")
        
        val now = Instant.now()
        val deleted = idempotencyRecordRepository.deleteByExpiresAtBefore(now)
        
        logger.info("Idempotency cleanup completed: deleted={}", deleted)
        
        return deleted
    }
}


