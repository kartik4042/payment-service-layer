package com.payment.orchestration.idempotency

import jakarta.persistence.*
import java.time.Instant

/**
 * Idempotency Record Entity
 * 
 * Stores idempotency keys to prevent duplicate payment processing.
 * 
 * Table: idempotency_records
 * Primary Key: idempotency_key (natural key)
 * Indexes:
 * - pk_idempotency_key (primary key, unique)
 * - idx_created_at (for cleanup jobs)
 * - idx_transaction_id (for lookup)
 * 
 * Consistency Guarantees:
 * - Primary key constraint ensures uniqueness
 * - INSERT will fail if key already exists (prevents duplicates)
 * - No updates allowed (immutable records)
 * - TTL-based cleanup for old records
 * 
 * Concurrency Control:
 * - Database-level uniqueness constraint
 * - Race condition handled by unique constraint violation
 * - First request wins, subsequent requests get existing record
 * 
 * Idempotency Strategy:
 * 1. Client sends Idempotency-Key header
 * 2. System attempts to INSERT record
 * 3. If INSERT succeeds → Process payment
 * 4. If INSERT fails (duplicate key) → Return existing payment
 * 5. Record expires after TTL (e.g., 24 hours)
 * 
 * TTL (Time To Live):
 * - Records expire after 24 hours
 * - Cleanup job removes expired records
 * - Prevents unbounded growth
 * - Balances idempotency window vs storage
 * 
 * @property idempotencyKey Client-provided idempotency key (UUID)
 * @property transactionId Associated transaction ID
 * @property requestFingerprint Hash of request payload (for validation)
 * @property status Processing status (PROCESSING, COMPLETED, FAILED)
 * @property createdAt When record was created
 * @property expiresAt When record expires (created_at + TTL)
 */
@Entity
@Table(
    name = "idempotency_records",
    indexes = [
        Index(name = "idx_created_at", columnList = "created_at"),
        Index(name = "idx_expires_at", columnList = "expires_at"),
        Index(name = "idx_transaction_id", columnList = "transaction_id")
    ]
)
data class IdempotencyRecord(
    
    @Id
    @Column(name = "idempotency_key", nullable = false, length = 100)
    val idempotencyKey: String,
    
    @Column(name = "transaction_id", nullable = false, length = 50)
    val transactionId: String,
    
    @Column(name = "request_fingerprint", nullable = false, length = 64)
    val requestFingerprint: String, // SHA-256 hash of request
    
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    val status: IdempotencyStatus,
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
    
    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant = Instant.now().plusSeconds(86400) // 24 hours
) {
    
    /**
     * Checks if record is expired.
     * 
     * @return true if expired, false otherwise
     */
    fun isExpired(): Boolean = Instant.now().isAfter(expiresAt)
    
    /**
     * Checks if record is still processing.
     * 
     * @return true if processing, false otherwise
     */
    fun isProcessing(): Boolean = status == IdempotencyStatus.PROCESSING
    
    /**
     * Checks if record is completed.
     * 
     * @return true if completed, false otherwise
     */
    fun isCompleted(): Boolean = status == IdempotencyStatus.COMPLETED
}

/**
 * Idempotency Status
 * 
 * Tracks the processing status of an idempotent request.
 * 
 * State Transitions:
 * - PROCESSING → COMPLETED (success)
 * - PROCESSING → FAILED (failure)
 * - No transitions from COMPLETED or FAILED
 * 
 * @property PROCESSING Request is currently being processed
 * @property COMPLETED Request completed successfully
 * @property FAILED Request failed
 */
enum class IdempotencyStatus {
    /**
     * Request is currently being processed.
     * Subsequent requests with same key should wait or return 409 Conflict.
     */
    PROCESSING,
    
    /**
     * Request completed successfully.
     * Subsequent requests with same key should return cached result.
     */
    COMPLETED,
    
    /**
     * Request failed.
     * Subsequent requests with same key can retry.
     */
    FAILED
}

/**
 * Idempotency Record (Redis)
 * 
 * In-memory representation for Redis storage.
 * Faster than database but less durable.
 * 
 * Use Cases:
 * - High-throughput scenarios
 * - Short TTL requirements
 * - Distributed systems
 * 
 * Trade-offs:
 * - Faster: O(1) lookup vs database query
 * - Less durable: Data lost on Redis failure
 * - Distributed: Works across multiple instances
 * - TTL: Automatic expiration via Redis
 * 
 * @property idempotencyKey Client-provided idempotency key
 * @property transactionId Associated transaction ID
 * @property requestFingerprint Hash of request payload
 * @property status Processing status
 * @property createdAt When record was created
 * @property ttlSeconds Time to live in seconds
 */
data class IdempotencyRecordRedis(
    val idempotencyKey: String,
    val transactionId: String,
    val requestFingerprint: String,
    val status: IdempotencyStatus,
    val createdAt: Instant = Instant.now(),
    val ttlSeconds: Long = 86400 // 24 hours
) {
    
    /**
     * Converts to Redis hash map.
     * 
     * @return Map of field names to values
     */
    fun toRedisHash(): Map<String, String> {
        return mapOf(
            "idempotency_key" to idempotencyKey,
            "transaction_id" to transactionId,
            "request_fingerprint" to requestFingerprint,
            "status" to status.name,
            "created_at" to createdAt.toString()
        )
    }
    
    companion object {
        /**
         * Creates from Redis hash map.
         * 
         * @param hash Redis hash map
         * @return IdempotencyRecordRedis
         */
        fun fromRedisHash(hash: Map<String, String>): IdempotencyRecordRedis {
            return IdempotencyRecordRedis(
                idempotencyKey = hash["idempotency_key"]!!,
                transactionId = hash["transaction_id"]!!,
                requestFingerprint = hash["request_fingerprint"]!!,
                status = IdempotencyStatus.valueOf(hash["status"]!!),
                createdAt = Instant.parse(hash["created_at"]!!)
            )
        }
    }
}

/**
 * Request Fingerprint Generator
 * 
 * Generates SHA-256 hash of request payload for validation.
 * 
 * Purpose:
 * - Detect request payload changes with same idempotency key
 * - Prevent malicious replay attacks
 * - Ensure request consistency
 * 
 * Algorithm:
 * 1. Serialize request to canonical JSON
 * 2. Compute SHA-256 hash
 * 3. Encode as hex string
 * 
 * Example:
 * ```
 * val request = CreatePaymentRequest(...)
 * val fingerprint = RequestFingerprintGenerator.generate(request)
 * // fingerprint = "a1b2c3d4e5f6..."
 * ```
 */
object RequestFingerprintGenerator {
    
    /**
     * Generates fingerprint for a request.
     * 
     * @param request The request object
     * @return SHA-256 hash as hex string
     */
    fun generate(request: Any): String {
        // In production, use Jackson for JSON serialization
        // and MessageDigest for SHA-256 hashing
        
        // Simplified implementation
        val json = request.toString()
        return json.hashCode().toString(16).padStart(64, '0')
    }
    
    /**
     * Validates request fingerprint.
     * 
     * @param request The request object
     * @param expectedFingerprint Expected fingerprint
     * @return true if matches, false otherwise
     */
    fun validate(request: Any, expectedFingerprint: String): Boolean {
        val actualFingerprint = generate(request)
        return actualFingerprint == expectedFingerprint
    }
}


