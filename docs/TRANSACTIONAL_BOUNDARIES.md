# Transactional Boundaries and Consistency Guarantees

## Overview

This document describes the transactional boundaries, consistency guarantees, and concurrency control mechanisms in the Payment Orchestration System.

## Table of Contents

1. [Transactional Boundaries](#transactional-boundaries)
2. [Consistency Guarantees](#consistency-guarantees)
3. [Concurrency Control](#concurrency-control)
4. [Isolation Levels](#isolation-levels)
5. [Locking Strategies](#locking-strategies)
6. [Race Condition Handling](#race-condition-handling)
7. [Failure Scenarios](#failure-scenarios)

---

## Transactional Boundaries

### 1. Payment Creation Flow

```kotlin
@Transactional(
    isolation = Isolation.READ_COMMITTED,
    propagation = Propagation.REQUIRED,
    timeout = 30
)
fun createPayment(transaction: Transaction, idempotencyKey: String?): Payment {
    // Transaction Boundary: OUTER
    // Scope: Entire payment creation flow
    // Commits: On success
    // Rollback: On any exception
    
    // Step 1: Check idempotency (REQUIRES_NEW transaction)
    val existingPayment = idempotencyService.checkIdempotency(idempotencyKey, transaction)
    if (existingPayment != null) return existingPayment
    
    // Step 2: Create payment domain object
    val payment = Payment.create(transaction)
    
    // Step 3: Save to database (joins outer transaction)
    val savedPayment = paymentRepository.save(payment)
    
    // Step 4: Orchestrate payment (joins outer transaction)
    val processedPayment = orchestrationService.orchestratePayment(savedPayment)
    
    // Step 5: Update idempotency record (joins outer transaction)
    if (idempotencyKey != null) {
        idempotencyService.markCompleted(idempotencyKey)
    }
    
    return processedPayment
}
```

**Consistency Guarantees:**
- All-or-nothing: Either entire payment flow succeeds or nothing is persisted
- Idempotency record and payment record are consistent
- No partial state in database

**Failure Handling:**
- Any exception rolls back entire transaction
- Idempotency record marked as FAILED
- Client can retry with same idempotency key

---

### 2. Idempotency Check

```kotlin
@Transactional(
    isolation = Isolation.SERIALIZABLE,
    propagation = Propagation.REQUIRES_NEW,
    timeout = 5
)
fun checkIdempotency(idempotencyKey: String, transaction: Transaction): Payment? {
    // Transaction Boundary: INNER (independent)
    // Scope: Idempotency check only
    // Commits: Immediately after check
    // Rollback: On exception
    
    // Atomic check-and-set
    val existingRecord = idempotencyStore.get(idempotencyKey)
    
    if (existingRecord != null) {
        // Validate fingerprint
        validateFingerprint(existingRecord, transaction)
        
        // Return cached result or throw conflict
        return handleExistingRecord(existingRecord)
    }
    
    // Create new record (atomic)
    createIdempotencyRecord(idempotencyKey, transaction)
    
    return null // Proceed with payment
}
```

**Why REQUIRES_NEW?**
- Independent transaction from payment creation
- Commits immediately after idempotency check
- Prevents long-running transaction holding locks
- Allows other requests to see idempotency record immediately

**Why SERIALIZABLE?**
- Prevents phantom reads
- Ensures no concurrent inserts with same key
- Strongest consistency guarantee
- Critical for idempotency enforcement

---

### 3. Payment Repository Operations

```kotlin
@Transactional(
    isolation = Isolation.READ_COMMITTED,
    propagation = Propagation.REQUIRED,
    readOnly = false
)
fun save(payment: Payment): Payment {
    // Transaction Boundary: Joins outer transaction
    // Scope: Single database write
    // Commits: With outer transaction
    // Rollback: With outer transaction
    
    val entity = PaymentEntity.fromDomain(payment)
    val saved = paymentRepository.save(entity)
    return saved.toDomain()
}

@Transactional(
    isolation = Isolation.READ_COMMITTED,
    propagation = Propagation.SUPPORTS,
    readOnly = true
)
fun findByTransactionId(transactionId: String): Payment? {
    // Transaction Boundary: Joins if exists, none if not
    // Scope: Single database read
    // Read-only: true (optimization)
    
    return paymentRepository.findByTransactionId(transactionId)
        ?.toDomain()
}
```

**Optimistic Locking:**
```kotlin
@Entity
data class PaymentEntity(
    @Version
    val version: Long = 0
    // ... other fields
)
```

- Version field incremented on each update
- Concurrent updates detected via version mismatch
- Throws `OptimisticLockException` on conflict
- Application retries with fresh data

---

## Consistency Guarantees

### 1. ACID Properties

#### Atomicity
- **Payment Creation**: All-or-nothing (payment + idempotency record)
- **Provider Call**: Idempotent via provider's idempotency key
- **Database Operations**: Single transaction boundary

#### Consistency
- **Unique Constraints**: transaction_id, idempotency_key
- **Foreign Keys**: payment_events → payments
- **Check Constraints**: amount > 0, valid status transitions
- **Application Logic**: State machine validation

#### Isolation
- **Read Committed**: Default for most operations
- **Serializable**: Idempotency checks
- **Pessimistic Locking**: Critical concurrent updates

#### Durability
- **Database**: PostgreSQL with WAL (Write-Ahead Logging)
- **Redis**: Optional persistence (RDB/AOF)
- **Replication**: Master-slave for high availability

---

### 2. Consistency Models

#### Strong Consistency (Database)
```
Client A: Write payment → Commit
Client B: Read payment → Sees latest version
```

**Guarantees:**
- Linearizability
- All reads see latest write
- No stale data

**Trade-offs:**
- Higher latency
- Lower throughput
- Requires synchronous replication

#### Eventual Consistency (Redis Cache)
```
Client A: Write payment → Update DB → Update Redis (async)
Client B: Read payment → May see stale data from Redis
```

**Guarantees:**
- Eventually consistent
- Reads may be stale
- Cache invalidation on updates

**Trade-offs:**
- Lower latency
- Higher throughput
- Requires cache invalidation strategy

---

## Concurrency Control

### 1. Optimistic Locking (Default)

**Mechanism:**
```kotlin
@Version
val version: Long = 0
```

**Flow:**
1. Read entity with version N
2. Modify entity
3. Update with WHERE version = N
4. If no rows updated → OptimisticLockException
5. Retry with fresh data

**Advantages:**
- No locks held
- High concurrency
- No deadlocks

**Disadvantages:**
- Retry overhead
- Not suitable for high contention

**Use Cases:**
- Payment status updates
- Metadata updates
- Low contention scenarios

---

### 2. Pessimistic Locking (Critical Sections)

**Mechanism:**
```kotlin
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT p FROM PaymentEntity p WHERE p.transactionId = :transactionId")
fun findByTransactionIdWithLock(@Param("transactionId") transactionId: String): Optional<PaymentEntity>
```

**SQL Generated:**
```sql
SELECT * FROM payments WHERE transaction_id = ? FOR UPDATE
```

**Flow:**
1. Acquire exclusive lock on row
2. Other transactions wait
3. Perform updates
4. Release lock on commit

**Advantages:**
- Prevents lost updates
- Suitable for high contention
- Deterministic behavior

**Disadvantages:**
- Lower concurrency
- Potential deadlocks
- Lock wait timeouts

**Use Cases:**
- Idempotency checks
- Balance updates
- Critical state transitions

---

### 3. Database-Level Uniqueness

**Mechanism:**
```sql
CREATE UNIQUE INDEX idx_transaction_id ON payments(transaction_id);
CREATE UNIQUE INDEX pk_idempotency_key ON idempotency_records(idempotency_key);
```

**Flow:**
1. Attempt INSERT
2. If key exists → DataIntegrityViolationException
3. Catch exception and handle

**Advantages:**
- Database-enforced
- No race conditions
- Atomic check-and-set

**Disadvantages:**
- Exception-based flow
- Requires exception handling

**Use Cases:**
- Duplicate prevention
- Idempotency enforcement
- Natural key constraints

---

## Isolation Levels

### 1. READ COMMITTED (Default)

**Guarantees:**
- No dirty reads
- Reads committed data only
- Allows non-repeatable reads
- Allows phantom reads

**Use Cases:**
- Most read operations
- Payment retrieval
- Status queries

**Example:**
```kotlin
@Transactional(isolation = Isolation.READ_COMMITTED)
fun getPayment(transactionId: String): Payment?
```

---

### 2. REPEATABLE READ

**Guarantees:**
- No dirty reads
- No non-repeatable reads
- Allows phantom reads
- Snapshot isolation

**Use Cases:**
- Report generation
- Batch processing
- Consistent snapshots

**Example:**
```kotlin
@Transactional(isolation = Isolation.REPEATABLE_READ)
fun generateReport(merchantId: String): Report
```

---

### 3. SERIALIZABLE (Strongest)

**Guarantees:**
- No dirty reads
- No non-repeatable reads
- No phantom reads
- Full serializability

**Use Cases:**
- Idempotency checks
- Critical sections
- Financial operations

**Example:**
```kotlin
@Transactional(isolation = Isolation.SERIALIZABLE)
fun checkIdempotency(key: String): Payment?
```

**Trade-offs:**
- Highest consistency
- Lowest concurrency
- Potential serialization failures
- Requires retry logic

---

## Locking Strategies

### 1. Row-Level Locking

**SELECT FOR UPDATE:**
```sql
SELECT * FROM payments WHERE transaction_id = ? FOR UPDATE;
```

**Behavior:**
- Locks specific rows
- Other transactions wait
- Released on commit/rollback

**Use Cases:**
- Payment updates
- Status transitions
- Balance modifications

---

### 2. Table-Level Locking

**LOCK TABLE:**
```sql
LOCK TABLE payments IN EXCLUSIVE MODE;
```

**Behavior:**
- Locks entire table
- Blocks all other operations
- Very low concurrency

**Use Cases:**
- Schema migrations
- Bulk operations
- Maintenance tasks

**Note:** Avoid in production code

---

### 3. Advisory Locks

**PostgreSQL Advisory Locks:**
```sql
SELECT pg_advisory_lock(12345);
-- Critical section
SELECT pg_advisory_unlock(12345);
```

**Behavior:**
- Application-level locks
- Not tied to transactions
- Manual lock management

**Use Cases:**
- Distributed locking
- Job scheduling
- Resource coordination

---

## Race Condition Handling

### 1. Duplicate Payment Prevention

**Scenario:**
```
Thread A: Check idempotency key → Not found
Thread B: Check idempotency key → Not found
Thread A: Create payment
Thread B: Create payment (DUPLICATE!)
```

**Solution:**
```kotlin
// Atomic check-and-set in idempotency store
val record = idempotencyStore.createOrGet(newRecord)

if (record.transactionId != newRecord.transactionId) {
    // Another thread won the race
    throw IdempotencyConflictException()
}
```

**Mechanism:**
- Database unique constraint
- Atomic INSERT ... ON CONFLICT
- First writer wins

---

### 2. Concurrent Status Updates

**Scenario:**
```
Thread A: Read payment (status=PROCESSING)
Thread B: Read payment (status=PROCESSING)
Thread A: Update status=SUCCEEDED
Thread B: Update status=FAILED (CONFLICT!)
```

**Solution:**
```kotlin
// Optimistic locking with version
@Version
val version: Long = 0

// Update with version check
UPDATE payments 
SET status = ?, version = version + 1 
WHERE transaction_id = ? AND version = ?
```

**Mechanism:**
- Version field
- Optimistic locking
- Retry on conflict

---

### 3. Lost Update Prevention

**Scenario:**
```
Thread A: Read balance=100
Thread B: Read balance=100
Thread A: Write balance=90 (deduct 10)
Thread B: Write balance=80 (deduct 20) - LOST UPDATE!
```

**Solution:**
```kotlin
// Pessimistic locking
@Lock(LockModeType.PESSIMISTIC_WRITE)
fun findByIdWithLock(id: Long): Account

// Or atomic update
UPDATE accounts 
SET balance = balance - ? 
WHERE id = ? AND balance >= ?
```

**Mechanism:**
- Pessimistic locking
- Atomic operations
- Check constraints

---

## Failure Scenarios

### 1. Database Connection Failure

**Scenario:** Database becomes unavailable during transaction

**Handling:**
```kotlin
@Transactional
fun createPayment(...): Payment {
    try {
        // Database operations
    } catch (e: DataAccessException) {
        // Transaction automatically rolled back
        logger.error("Database error", e)
        throw PaymentProcessingException("Database unavailable", e)
    }
}
```

**Recovery:**
- Automatic rollback
- Retry with exponential backoff
- Circuit breaker to prevent cascading failures

---

### 2. Provider Timeout

**Scenario:** Provider call times out during payment processing

**Handling:**
```kotlin
try {
    val response = provider.processPayment(payment)
} catch (e: ProviderTimeoutException) {
    // Mark payment as PENDING
    // Retry later via background job
    payment.copy(status = PaymentStatus.PENDING)
}
```

**Recovery:**
- Mark payment as PENDING
- Background job polls provider for status
- Update payment when status known

---

### 3. Partial Failure

**Scenario:** Payment succeeds at provider but database update fails

**Handling:**
```kotlin
@Transactional
fun orchestratePayment(payment: Payment): Payment {
    // Call provider (idempotent)
    val response = provider.processPayment(payment)
    
    // Update database
    val updated = payment.copy(
        status = PaymentStatus.SUCCEEDED,
        providerTransactionId = response.providerTransactionId
    )
    
    // If this fails, transaction rolls back
    // Retry will call provider again (idempotent)
    return paymentRepository.save(updated)
}
```

**Recovery:**
- Provider calls are idempotent
- Safe to retry entire transaction
- Provider deduplicates via transaction ID

---

### 4. Idempotency Record Inconsistency

**Scenario:** Idempotency record created but payment creation fails

**Handling:**
```kotlin
@Transactional(propagation = Propagation.REQUIRES_NEW)
fun checkIdempotency(...): Payment? {
    // Independent transaction
    // Commits immediately
    createIdempotencyRecord(...)
}

@Transactional
fun createPayment(...): Payment {
    // Outer transaction
    // If fails, idempotency record already committed
    // Next retry will see PROCESSING status
    // Return 409 Conflict or wait
}
```

**Recovery:**
- Idempotency record marked as FAILED on outer transaction failure
- Client can retry with same key
- Background job cleans up orphaned records

---

## Best Practices

### 1. Transaction Scope

✅ **DO:**
- Keep transactions short
- Minimize work inside transaction
- Use appropriate isolation level
- Handle exceptions properly

❌ **DON'T:**
- Call external APIs inside transaction
- Perform long computations
- Hold locks unnecessarily
- Use SERIALIZABLE everywhere

---

### 2. Locking Strategy

✅ **DO:**
- Use optimistic locking by default
- Use pessimistic locking for high contention
- Acquire locks in consistent order
- Set lock timeouts

❌ **DON'T:**
- Hold locks across network calls
- Use table-level locks
- Ignore deadlock exceptions
- Mix locking strategies

---

### 3. Idempotency

✅ **DO:**
- Use idempotency keys for all mutations
- Validate request fingerprints
- Handle concurrent requests
- Clean up expired records

❌ **DON'T:**
- Reuse idempotency keys
- Skip fingerprint validation
- Ignore race conditions
- Keep records forever

---

### 4. Error Handling

✅ **DO:**
- Catch specific exceptions
- Log errors with context
- Retry transient failures
- Return meaningful errors

❌ **DON'T:**
- Catch generic Exception
- Swallow exceptions
- Retry permanent failures
- Expose internal errors

---

## Summary

| Aspect | Strategy | Guarantee |
|--------|----------|-----------|
| **Atomicity** | Spring @Transactional | All-or-nothing |
| **Consistency** | Unique constraints + validation | No invalid state |
| **Isolation** | READ_COMMITTED (default) | No dirty reads |
| **Durability** | PostgreSQL WAL | Survives crashes |
| **Concurrency** | Optimistic locking | High throughput |
| **Idempotency** | Unique key + fingerprint | No duplicates |
| **Race Conditions** | Atomic operations | First writer wins |
| **Failures** | Retry + circuit breaker | Resilient |

---

## References

- [Spring Transaction Management](https://docs.spring.io/spring-framework/docs/current/reference/html/data-access.html#transaction)
- [PostgreSQL Isolation Levels](https://www.postgresql.org/docs/current/transaction-iso.html)
- [JPA Locking](https://docs.oracle.com/javaee/7/tutorial/persistence-locking.htm)
- [Idempotency Patterns](https://stripe.com/docs/api/idempotent_requests)