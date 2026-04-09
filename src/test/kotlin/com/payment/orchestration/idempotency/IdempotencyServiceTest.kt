package com.payment.orchestration.idempotency

import com.payment.orchestration.domain.model.Payment
import com.payment.orchestration.domain.model.PaymentMethod
import com.payment.orchestration.domain.model.PaymentStatus
import com.payment.orchestration.domain.model.Transaction
import com.payment.orchestration.repository.PaymentRepositoryAdapter
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Instant

/**
 * Unit tests for IdempotencyService
 * 
 * Test Coverage:
 * - New idempotency key creates new record
 * - Existing completed request returns cached payment
 * - Existing processing request throws conflict
 * - Failed request allows retry
 * - Request fingerprint mismatch throws exception
 * - Concurrent duplicate key handling
 */
class IdempotencyServiceTest {
    
    private lateinit var idempotencyStore: IdempotencyStore
    private lateinit var paymentRepository: PaymentRepositoryAdapter
    private lateinit var idempotencyService: IdempotencyService
    private lateinit var transaction: Transaction
    
    @BeforeEach
    fun setup() {
        idempotencyStore = mockk(relaxed = true)
        paymentRepository = mockk(relaxed = true)
        idempotencyService = IdempotencyService(idempotencyStore, paymentRepository)
        
        transaction = Transaction(
            amount = BigDecimal("100.00"),
            currency = "INR",
            paymentMethod = PaymentMethod.CARD,
            customerId = "cust_123",
            merchantId = "merch_456",
            description = "Test payment",
            paymentDetails = mapOf("card_token" to "tok_123")
        )
    }
    
    @Test
    fun `should create new idempotency record for new key`() {
        // Given
        val idempotencyKey = "idem_new_key"
        every { idempotencyStore.get(idempotencyKey) } returns null
        every { idempotencyStore.createOrGet(any()) } answers { firstArg() }
        
        // When
        val result = idempotencyService.checkIdempotency(idempotencyKey, transaction)
        
        // Then
        assertNull(result)
        verify { idempotencyStore.createOrGet(any()) }
    }
    
    @Test
    fun `should return cached payment for completed idempotency key`() {
        // Given
        val idempotencyKey = "idem_completed"
        val transactionId = "txn_123"
        val requestFingerprint = RequestFingerprintGenerator.generate(transaction)
        
        val existingRecord = IdempotencyRecordRedis(
            idempotencyKey = idempotencyKey,
            transactionId = transactionId,
            requestFingerprint = requestFingerprint,
            status = IdempotencyStatus.COMPLETED,
            createdAt = Instant.now(),
            ttlSeconds = 86400
        )
        
        val completedPayment = Payment.create(
            customerId = "cust_123",
            merchantId = "merch_456",
            transaction = transaction
        ).copy(
            transactionId = transactionId,
            status = PaymentStatus.SUCCEEDED
        )
        
        every { idempotencyStore.get(idempotencyKey) } returns existingRecord
        every { paymentRepository.findByTransactionId(transactionId) } returns completedPayment
        
        // When
        val result = idempotencyService.checkIdempotency(idempotencyKey, transaction)
        
        // Then
        assertNotNull(result)
        assertEquals(transactionId, result?.transactionId)
        assertEquals(PaymentStatus.SUCCEEDED, result?.status)
    }
    
    @Test
    fun `should throw conflict for processing idempotency key`() {
        // Given
        val idempotencyKey = "idem_processing"
        val requestFingerprint = RequestFingerprintGenerator.generate(transaction)
        
        val existingRecord = IdempotencyRecordRedis(
            idempotencyKey = idempotencyKey,
            transactionId = "txn_123",
            requestFingerprint = requestFingerprint,
            status = IdempotencyStatus.PROCESSING,
            createdAt = Instant.now(),
            ttlSeconds = 86400
        )
        
        every { idempotencyStore.get(idempotencyKey) } returns existingRecord
        
        // When & Then
        assertThrows<IdempotencyConflictException> {
            idempotencyService.checkIdempotency(idempotencyKey, transaction)
        }
    }
    
    @Test
    fun `should allow retry for failed idempotency key`() {
        // Given
        val idempotencyKey = "idem_failed"
        val requestFingerprint = RequestFingerprintGenerator.generate(transaction)
        
        val existingRecord = IdempotencyRecordRedis(
            idempotencyKey = idempotencyKey,
            transactionId = "txn_123",
            requestFingerprint = requestFingerprint,
            status = IdempotencyStatus.FAILED,
            createdAt = Instant.now(),
            ttlSeconds = 86400
        )
        
        every { idempotencyStore.get(idempotencyKey) } returns existingRecord
        every { idempotencyStore.delete(idempotencyKey) } returns Unit
        every { idempotencyStore.createOrGet(any()) } answers { firstArg() }
        
        // When
        val result = idempotencyService.checkIdempotency(idempotencyKey, transaction)
        
        // Then
        assertNull(result)
        verify { idempotencyStore.delete(idempotencyKey) }
        verify { idempotencyStore.createOrGet(any()) }
    }
    
    @Test
    fun `should throw exception for fingerprint mismatch`() {
        // Given
        val idempotencyKey = "idem_mismatch"
        val differentFingerprint = "different_fingerprint"
        
        val existingRecord = IdempotencyRecordRedis(
            idempotencyKey = idempotencyKey,
            transactionId = "txn_123",
            requestFingerprint = differentFingerprint,
            status = IdempotencyStatus.COMPLETED,
            createdAt = Instant.now(),
            ttlSeconds = 86400
        )
        
        every { idempotencyStore.get(idempotencyKey) } returns existingRecord
        
        // When & Then
        assertThrows<IdempotencyFingerprintMismatchException> {
            idempotencyService.checkIdempotency(idempotencyKey, transaction)
        }
    }
    
    @Test
    fun `should mark idempotency as completed`() {
        // Given
        val idempotencyKey = "idem_complete"
        every { idempotencyStore.updateStatus(idempotencyKey, IdempotencyStatus.COMPLETED) } returns true
        
        // When
        idempotencyService.markCompleted(idempotencyKey)
        
        // Then
        verify { idempotencyStore.updateStatus(idempotencyKey, IdempotencyStatus.COMPLETED) }
    }
    
    @Test
    fun `should mark idempotency as failed`() {
        // Given
        val idempotencyKey = "idem_fail"
        every { idempotencyStore.updateStatus(idempotencyKey, IdempotencyStatus.FAILED) } returns true
        
        // When
        idempotencyService.markFailed(idempotencyKey)
        
        // Then
        verify { idempotencyStore.updateStatus(idempotencyKey, IdempotencyStatus.FAILED) }
    }
    
    @Test
    fun `should handle race condition with concurrent requests`() {
        // Given
        val idempotencyKey = "idem_race"
        val ourTransactionId = "txn_our"
        val theirTransactionId = "txn_their"
        
        every { idempotencyStore.get(idempotencyKey) } returns null
        every { idempotencyStore.createOrGet(any()) } returns IdempotencyRecordRedis(
            idempotencyKey = idempotencyKey,
            transactionId = theirTransactionId, // Different transaction ID (race condition)
            requestFingerprint = RequestFingerprintGenerator.generate(transaction),
            status = IdempotencyStatus.PROCESSING,
            createdAt = Instant.now(),
            ttlSeconds = 86400
        )
        
        // When & Then
        assertThrows<IdempotencyConflictException> {
            idempotencyService.checkIdempotency(idempotencyKey, transaction)
        }
    }
}

