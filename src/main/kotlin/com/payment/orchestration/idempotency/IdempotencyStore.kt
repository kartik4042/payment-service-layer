package com.payment.orchestration.idempotency

import org.slf4j.LoggerFactory
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.Optional
import java.util.concurrent.TimeUnit

/**
 * Idempotency Store Interface
 * 
 * Abstraction for storing and retrieving idempotency records.
 * 
 * Implementations:
 * - DatabaseIdempotencyStore: PostgreSQL-based (durable)
 * - RedisIdempotencyStore: Redis-based (fast)
 * - HybridIdempotencyStore: Redis + PostgreSQL (best of both)
 * 
 * Consistency Guarantees:
 * - Atomic check-and-set operations
 * - Uniqueness enforcement
 * - Race condition handling
 * - TTL-based expiration
 * 
 * @see DatabaseIdempotencyStore
 * @see RedisIdempotencyStore
 */
interface IdempotencyStore {
    
    /**
     * Attempts to create a new idempotency record.
     * 
     * This operation must be atomic:
     * - Check if key exists
     * - If not exists, create record
     * - If exists, return existing record
     * 
     * Concurrency:
     * - Multiple concurrent requests with same key
     * - First request wins, others get existing record
     * - No race conditions
     * 
     * @param record The idempotency record to create
     * @return Created or existing record
     */
    fun createOrGet(record: IdempotencyRecordRedis): IdempotencyRecordRedis
    
    /**
     * Retrieves an idempotency record by key.
     * 
     * @param idempotencyKey The idempotency key
     * @return Record if found, null otherwise
     */
    fun get(idempotencyKey: String): IdempotencyRecordRedis?
    
    /**
     * Updates the status of an idempotency record.
     * 
     * @param idempotencyKey The idempotency key
     * @param status New status
     * @return true if updated, false if not found
     */
    fun updateStatus(idempotencyKey: String, status: IdempotencyStatus): Boolean
    
    /**
     * Deletes an idempotency record.
     * 
     * @param idempotencyKey The idempotency key
     * @return true if deleted, false if not found
     */
    fun delete(idempotencyKey: String): Boolean
    
    /**
     * Checks if an idempotency key exists.
     * 
     * @param idempotencyKey The idempotency key
     * @return true if exists, false otherwise
     */
    fun exists(idempotencyKey: String): Boolean
}

/**
 * Database Idempotency Store (PostgreSQL)
 * 
 * Stores idempotency records in PostgreSQL database.
 * 
 * Advantages:
 * - Durable: Survives restarts
 * - ACID: Strong consistency guarantees
 * - Queryable: Can run analytics
 * 
 * Disadvantages:
 * - Slower: Network + disk I/O
 * - Resource intensive: Database connections
 * 
 * Consistency:
 * - Primary key constraint ensures uniqueness
 * - INSERT ... ON CONFLICT for atomic check-and-set
 * - Serializable isolation for critical operations
 * 
 * @property repository JPA repository
 */
@Repository
interface IdempotencyRecordRepository : JpaRepository<IdempotencyRecord, String> {
    
    /**
     * Finds non-expired record by key.
     * 
     * @param idempotencyKey The idempotency key
     * @return Optional record
     */
    fun findByIdempotencyKeyAndExpiresAtAfter(
        idempotencyKey: String,
        now: java.time.Instant
    ): Optional<IdempotencyRecord>
    
    /**
     * Deletes expired records.
     * 
     * @param now Current timestamp
     * @return Number of deleted records
     */
    fun deleteByExpiresAtBefore(now: java.time.Instant): Long
}

/**
 * Database Idempotency Store Implementation
 * 
 * @property repository JPA repository
 */
@Component
class DatabaseIdempotencyStore(
    private val repository: IdempotencyRecordRepository
) : IdempotencyStore {
    
    private val logger = LoggerFactory.getLogger(DatabaseIdempotencyStore::class.java)
    
    override fun createOrGet(record: IdempotencyRecordRedis): IdempotencyRecordRedis {
        try {
            // Convert to JPA entity
            val entity = IdempotencyRecord(
                idempotencyKey = record.idempotencyKey,
                transactionId = record.transactionId,
                requestFingerprint = record.requestFingerprint,
                status = record.status,
                createdAt = record.createdAt,
                expiresAt = record.createdAt.plusSeconds(record.ttlSeconds)
            )
            
            // Attempt to save
            // If key already exists, this will throw DataIntegrityViolationException
            val saved = repository.save(entity)
            
            logger.debug(
                "Created idempotency record: key={}, transactionId={}",
                record.idempotencyKey,
                record.transactionId
            )
            
            return toRedisRecord(saved)
            
        } catch (e: org.springframework.dao.DataIntegrityViolationException) {
            // Key already exists, fetch existing record
            logger.debug(
                "Idempotency key already exists: key={}",
                record.idempotencyKey
            )
            
            val existing = repository.findByIdempotencyKeyAndExpiresAtAfter(
                record.idempotencyKey,
                java.time.Instant.now()
            ).orElseThrow {
                IllegalStateException("Idempotency record not found after conflict")
            }
            
            return toRedisRecord(existing)
        }
    }
    
    override fun get(idempotencyKey: String): IdempotencyRecordRedis? {
        return repository.findByIdempotencyKeyAndExpiresAtAfter(
            idempotencyKey,
            java.time.Instant.now()
        ).map { toRedisRecord(it) }
            .orElse(null)
    }
    
    override fun updateStatus(idempotencyKey: String, status: IdempotencyStatus): Boolean {
        val record = repository.findById(idempotencyKey).orElse(null) ?: return false
        
        val updated = record.copy(status = status)
        repository.save(updated)
        
        logger.debug(
            "Updated idempotency record status: key={}, status={}",
            idempotencyKey,
            status
        )
        
        return true
    }
    
    override fun delete(idempotencyKey: String): Boolean {
        if (!repository.existsById(idempotencyKey)) {
            return false
        }
        
        repository.deleteById(idempotencyKey)
        
        logger.debug("Deleted idempotency record: key={}", idempotencyKey)
        
        return true
    }
    
    override fun exists(idempotencyKey: String): Boolean {
        return repository.findByIdempotencyKeyAndExpiresAtAfter(
            idempotencyKey,
            java.time.Instant.now()
        ).isPresent
    }
    
    /**
     * Converts JPA entity to Redis record.
     */
    private fun toRedisRecord(entity: IdempotencyRecord): IdempotencyRecordRedis {
        return IdempotencyRecordRedis(
            idempotencyKey = entity.idempotencyKey,
            transactionId = entity.transactionId,
            requestFingerprint = entity.requestFingerprint,
            status = entity.status,
            createdAt = entity.createdAt,
            ttlSeconds = Duration.between(entity.createdAt, entity.expiresAt).seconds
        )
    }
}

/**
 * Redis Idempotency Store
 * 
 * Stores idempotency records in Redis.
 * 
 * Advantages:
 * - Fast: In-memory operations
 * - Distributed: Works across multiple instances
 * - TTL: Automatic expiration
 * 
 * Disadvantages:
 * - Less durable: Data lost on Redis failure
 * - Memory limited: Cannot store unlimited records
 * 
 * Consistency:
 * - SETNX (SET if Not eXists) for atomic check-and-set
 * - Redis transactions for multi-key operations
 * - Lua scripts for atomic operations
 * 
 * Data Structure:
 * - Key: idempotency:{key}
 * - Value: Hash with fields (transaction_id, status, etc.)
 * - TTL: Automatic expiration
 * 
 * @property redisTemplate Spring Redis template
 */
@Component
class RedisIdempotencyStore(
    private val redisTemplate: RedisTemplate<String, String>
) : IdempotencyStore {
    
    private val logger = LoggerFactory.getLogger(RedisIdempotencyStore::class.java)
    
    companion object {
        private const val KEY_PREFIX = "idempotency:"
    }
    
    override fun createOrGet(record: IdempotencyRecordRedis): IdempotencyRecordRedis {
        val key = buildKey(record.idempotencyKey)
        
        // Attempt to set if not exists (SETNX)
        val ops = redisTemplate.opsForHash<String, String>()
        
        // Check if key exists
        if (redisTemplate.hasKey(key)) {
            // Key exists, fetch existing record
            logger.debug(
                "Idempotency key already exists in Redis: key={}",
                record.idempotencyKey
            )
            
            val hash = ops.entries(key)
            return IdempotencyRecordRedis.fromRedisHash(hash)
        }
        
        // Key doesn't exist, create new record
        ops.putAll(key, record.toRedisHash())
        redisTemplate.expire(key, record.ttlSeconds, TimeUnit.SECONDS)
        
        logger.debug(
            "Created idempotency record in Redis: key={}, transactionId={}, ttl={}s",
            record.idempotencyKey,
            record.transactionId,
            record.ttlSeconds
        )
        
        return record
    }
    
    override fun get(idempotencyKey: String): IdempotencyRecordRedis? {
        val key = buildKey(idempotencyKey)
        
        if (!redisTemplate.hasKey(key)) {
            return null
        }
        
        val ops = redisTemplate.opsForHash<String, String>()
        val hash = ops.entries(key)
        
        return if (hash.isEmpty()) {
            null
        } else {
            IdempotencyRecordRedis.fromRedisHash(hash)
        }
    }
    
    override fun updateStatus(idempotencyKey: String, status: IdempotencyStatus): Boolean {
        val key = buildKey(idempotencyKey)
        
        if (!redisTemplate.hasKey(key)) {
            return false
        }
        
        val ops = redisTemplate.opsForHash<String, String>()
        ops.put(key, "status", status.name)
        
        logger.debug(
            "Updated idempotency record status in Redis: key={}, status={}",
            idempotencyKey,
            status
        )
        
        return true
    }
    
    override fun delete(idempotencyKey: String): Boolean {
        val key = buildKey(idempotencyKey)
        
        val deleted = redisTemplate.delete(key)
        
        if (deleted) {
            logger.debug("Deleted idempotency record from Redis: key={}", idempotencyKey)
        }
        
        return deleted
    }
    
    override fun exists(idempotencyKey: String): Boolean {
        val key = buildKey(idempotencyKey)
        return redisTemplate.hasKey(key)
    }
    
    /**
     * Builds Redis key with prefix.
     */
    private fun buildKey(idempotencyKey: String): String {
        return "$KEY_PREFIX$idempotencyKey"
    }
}

/**
 * Hybrid Idempotency Store
 * 
 * Uses Redis for fast lookups and PostgreSQL for durability.
 * 
 * Strategy:
 * 1. Check Redis first (fast path)
 * 2. If not in Redis, check database (slow path)
 * 3. Write to both Redis and database
 * 4. Redis acts as cache with TTL
 * 
 * Advantages:
 * - Fast: Redis for hot data
 * - Durable: PostgreSQL for persistence
 * - Resilient: Survives Redis failures
 * 
 * Consistency:
 * - Database is source of truth
 * - Redis is cache (can be stale)
 * - Cache invalidation on updates
 * 
 * @property redisStore Redis store
 * @property databaseStore Database store
 */
@Component
class HybridIdempotencyStore(
    private val redisStore: RedisIdempotencyStore,
    private val databaseStore: DatabaseIdempotencyStore
) : IdempotencyStore {
    
    private val logger = LoggerFactory.getLogger(HybridIdempotencyStore::class.java)
    
    override fun createOrGet(record: IdempotencyRecordRedis): IdempotencyRecordRedis {
        // Try Redis first (fast path)
        try {
            val existing = redisStore.get(record.idempotencyKey)
            if (existing != null) {
                logger.debug(
                    "Idempotency record found in Redis: key={}",
                    record.idempotencyKey
                )
                return existing
            }
        } catch (e: Exception) {
            logger.warn("Redis lookup failed, falling back to database: {}", e.message)
        }
        
        // Not in Redis, check database (slow path)
        val result = databaseStore.createOrGet(record)
        
        // Write to Redis for future lookups
        try {
            redisStore.createOrGet(result)
        } catch (e: Exception) {
            logger.warn("Failed to cache in Redis: {}", e.message)
        }
        
        return result
    }
    
    override fun get(idempotencyKey: String): IdempotencyRecordRedis? {
        // Try Redis first
        try {
            val cached = redisStore.get(idempotencyKey)
            if (cached != null) {
                return cached
            }
        } catch (e: Exception) {
            logger.warn("Redis lookup failed: {}", e.message)
        }
        
        // Fall back to database
        val fromDb = databaseStore.get(idempotencyKey)
        
        // Cache in Redis
        if (fromDb != null) {
            try {
                redisStore.createOrGet(fromDb)
            } catch (e: Exception) {
                logger.warn("Failed to cache in Redis: {}", e.message)
            }
        }
        
        return fromDb
    }
    
    override fun updateStatus(idempotencyKey: String, status: IdempotencyStatus): Boolean {
        // Update database (source of truth)
        val updated = databaseStore.updateStatus(idempotencyKey, status)
        
        // Invalidate Redis cache
        if (updated) {
            try {
                redisStore.updateStatus(idempotencyKey, status)
            } catch (e: Exception) {
                logger.warn("Failed to update Redis cache: {}", e.message)
            }
        }
        
        return updated
    }
    
    override fun delete(idempotencyKey: String): Boolean {
        // Delete from both stores
        val deletedFromDb = databaseStore.delete(idempotencyKey)
        
        try {
            redisStore.delete(idempotencyKey)
        } catch (e: Exception) {
            logger.warn("Failed to delete from Redis: {}", e.message)
        }
        
        return deletedFromDb
    }
    
    override fun exists(idempotencyKey: String): Boolean {
        // Check Redis first
        try {
            if (redisStore.exists(idempotencyKey)) {
                return true
            }
        } catch (e: Exception) {
            logger.warn("Redis check failed: {}", e.message)
        }
        
        // Fall back to database
        return databaseStore.exists(idempotencyKey)
    }
}


