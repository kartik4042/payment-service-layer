package com.payment.orchestration.idempotency

import com.payment.orchestration.domain.model.*
import com.payment.orchestration.repository.PaymentRepositoryAdapter
import io.mockk.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.math.BigDecimal
import java.time.Instant

/**
 * Unit Tests for IdempotencyService
 * 
 * Test Classification:
 * - @Tag("sanity"): Basic idempotency functionality
 * - @Tag("regression"): Duplicate prevention, concurrent requests, edge cases
 * 
 * Test Framework: JUnit 5 + MockK
 * 
 * Coverage:
 * - First request creates idempotency record
 * - Duplicate request returns cached payment
 * - Concurrent requests handled safely
 * - Request fingerprint validation
 * - Status transitions (PROCESSING, COMPLETED, FAILED)
 * - Failed request retry allowed
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("IdempotencyService Unit Tests")
class IdempotencyServiceTest {
    
    private lateinit var idempotencyService: IdempotencyService
    private lateinit var idempotencyStore: IdempotencyStore
    private lateinit var paymentRepository: PaymentRepositoryAdapter
    
    @BeforeEach
    fun setup() {
        idempotencyStore = mockk<IdempotencyStore>()
        paymentRepository = mockk<PaymentRepositoryAdapter>()
        idempotencyService = IdempotencyService(idempotencyStore, paymentRepository)
    }
    
    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }
    
    // ========================================
    // SANITY TESTS - Basic Functionality
    // ========================================
    
    @Test
    @Tag("sanity")
    @DisplayName("Should create idempotency record for new request")
    fun testCreateIdempotencyRecordForNewRequest() {
        // Given
        val idempotencyKey = "idem_test_123"
        val transaction = createTransaction()
        
        every { idempotencyStore.get(idempotencyKey) } returns null
        every { idempotencyStore.createOrGet(any()) } answers {
            firstArg<IdempotencyRecordRedis>()
        }
        
        // When
        val result = idempotencyService.checkIdempotency(idempotencyKey, transaction)
        
        // Then
        assertNull(result) // Should proceed with payment
        verify(exactly = 1) { idempotencyStore.get(idempotencyKey) }
        verify(exactly = 1) { idempotencyStore.createOrGet(any()) }
    }
    
    @Test
    @Tag("sanity")
    @DisplayName("Should return cached payment for completed request")
    fun testReturnCachedPaymentForCompletedRequest() {
        // Given
        val idempotencyKey = "idem_test_123"
        val transaction = createTransaction()
        val transactionId = "txn_123"
        
        val existingRecord = IdempotencyRecordRedis(
            idempotencyKey = idempotencyKey,
            transactionId = transactionId,
            requestFingerprint = RequestFingerprintGenerator.generate(transaction),
            status = IdempotencyStatus.COMPLETED
        )
        
        val cachedPayment = createPayment(transactionId)
        
        every { idempotencyStore.get(idempotencyKey) } returns existingRecord
        every { paymentRepository.findByTransactionId(transactionId) } returns cachedPayment
        
        // When
        val result = idempotencyService.checkIdempotency(idempotencyKey, transaction)
        
        // Then
        assertNotNull(result)
        assertEquals(transactionId, result!!.transactionId)
        verify(exactly = 1) { idempotencyStore.get(idempotencyKey) }
        verify(exactly = 1) { paymentRepository.findByTransactionId(transactionId) }
        verify(exactly = 0) { idempotencyStore.createOrGet(any()) }
    }
    
    @Test
    @Tag("sanity")
    @DisplayName("Should mark idempotency record as completed")
    fun testMarkIdempotencyRecordAsCompleted() {
        // Given
        val idempotencyKey = "idem_test_123"
        every { idempotencyStore.updateStatus(idempotencyKey, IdempotencyStatus.COMPLETED) } returns true
        
        // When
        idempotencyService.markCompleted(idempotencyKey)
        
        // Then
        verify(exactly = 1) { idempotencyStore.updateStatus(idempotencyKey, IdempotencyStatus.COMPLETED) }
    }
    
    @Test
    @Tag("sanity")
    @DisplayName("Should mark idempotency record as failed")
    fun testMarkIdempotencyRecordAsFailed() {
        // Given
        val idempotencyKey = "idem_test_123"
        every { idempotencyStore.updateStatus(idempotencyKey, IdempotencyStatus.FAILED) } returns true
        
        // When
        idempotencyService.markFailed(idempotencyKey)
        
        // Then
        verify(exactly = 1) { idempotencyStore.updateStatus(idempotencyKey, IdempotencyStatus.FAILED) }
    }
    
    // ========================================
    // REGRESSION TESTS - Duplicate Prevention
    // ========================================
    
    @Test
    @Tag("regression")
    @DisplayName("Should throw conflict exception for request in progress")
    fun testConflictForRequestInProgress() {
        // Given
        val idempotencyKey = "idem_test_123"
        val transaction = createTransaction()
        
        val existingRecord = IdempotencyRecordRedis(
            idempotencyKey = idempotencyKey,
            transactionId = "txn_123",
            requestFingerprint = RequestFingerprintGenerator.generate(transaction),
            status = IdempotencyStatus.PROCESSING
        )
        
        every { idempotencyStore.get(idempotencyKey) } returns existingRecord
        
        // When & Then
        val exception = assertThrows<IdempotencyConflictException> {
            idempotencyService.checkIdempotency(idempotencyKey, transaction)
        }
        
        assertTrue(exception.message!!.contains("already being processed"))
        verify(exactly = 1) { idempotencyStore.get(idempotencyKey) }
    }
    
    @Test
    @Tag("regression")
    @DisplayName("Should allow retry for failed request")
    fun testAllowRetryForFailedRequest() {
        // Given
        val idempotencyKey = "idem_test_123"
        val transaction = createTransaction()
        
        val existingRecord = IdempotencyRecordRedis(
            idempotencyKey = idempotencyKey,
            transactionId = "txn_123",
            requestFingerprint = RequestFingerprintGenerator.generate(transaction),
            status = IdempotencyStatus.FAILED
        )
        
        every { idempotencyStore.get(idempotencyKey) } returns existingRecord
        every { idempotencyStore.delete(idempotencyKey) } returns true
        every { idempotencyStore.createOrGet(any()) } answers {
            firstArg<IdempotencyRecordRedis>()
        }
        
        // When
        val result = idempotencyService.checkIdempotency(idempotencyKey, transaction)
        
        // Then
        assertNull(result) // Should proceed with payment
        verify(exactly = 1) { idempotencyStore.delete(idempotencyKey) }
        verify(exactly = 1) { idempotencyStore.createOrGet(any()) }
    }
    
    @Test
    @Tag("regression")
    @DisplayName("Should throw exception for fingerprint mismatch")
    fun testFingerprintMismatch() {
        // Given
        val idempotencyKey = "idem_test_123"
        val transaction = createTransaction()
        val differentTransaction = createTransaction(amount = BigDecimal("200.00"))
        
        val existingRecord = IdempotencyRecordRedis(
            idempotencyKey = idempotencyKey,
            transactionId = "txn_123",
            requestFingerprint = RequestFingerprintGenerator.generate(differentTransaction),
            status = IdempotencyStatus.COMPLETED
        )
        
        every { idempotencyStore.get(idempotencyKey) } returns existingRecord
        
        // When & Then
        val exception = assertThrows<IdempotencyFingerprintMismatchException> {
            idempotencyService.checkIdempotency(idempotencyKey, transaction)
        }
        
        assertTrue(exception.message!!.contains("Request payload differs"))
        verify(exactly = 1) { idempotencyStore.get(idempotencyKey) }
    }
    
    // ========================================
    // REGRESSION TESTS - Concurrent Requests
    // ========================================
    
    @Test
    @Tag("regression")
    @DisplayName("Should handle race condition when creating idempotency record")
    fun testRaceConditionOnCreate() {
        // Given
        val idempotencyKey = "idem_test_123"
        val transaction = createTransaction()
        val ourTransactionId = "txn_our_123"
        val theirTransactionId = "txn_their_456"
        
        every { idempotencyStore.get(idempotencyKey) } returns null
        
        // Simulate race condition: another thread created record first
        every { idempotencyStore.createOrGet(any()) } answers {
            val record = firstArg<IdempotencyRecordRedis>()
            // Return different transaction ID (another thread won)
            record.copy(transactionId = theirTransactionId)
        }
        
        // When & Then
        val exception = assertThrows<IdempotencyConflictException> {
            idempotencyService.checkIdempotency(idempotencyKey, transaction)
        }
        
        assertTrue(exception.message!!.contains("already being processed"))
    }
    
    @Test
    @Tag("regression")
    @DisplayName("Should handle concurrent requests with same idempotency key")
    fun testConcurrentRequestsWithSameKey() {
        // Given
        val idempotencyKey = "idem_test_123"
        val transaction = createTransaction()
        val transactionId = "txn_123"
        
        // First request: No existing record
        every { idempotencyStore.get(idempotencyKey) } returns null andThen IdempotencyRecordRedis(
            idempotencyKey = idempotencyKey,
            transactionId = transactionId,
            requestFingerprint = RequestFingerprintGenerator.generate(transaction),
            status = IdempotencyStatus.PROCESSING
        )
        
        every { idempotencyStore.createOrGet(any()) } answers {
            firstArg<IdempotencyRecordRedis>()
        }
        
        // When - First request
        val firstResult = idempotencyService.checkIdempotency(idempotencyKey, transaction)
        
        // Then - First request should proceed
        assertNull(firstResult)
        
        // When - Second request (concurrent)
        val exception = assertThrows<IdempotencyConflictException> {
            idempotencyService.checkIdempotency(idempotencyKey, transaction)
        }
        
        // Then - Second request should get conflict
        assertTrue(exception.message!!.contains("already being processed"))
    }
    
    // ========================================
    // REGRESSION TESTS - Edge Cases
    // ========================================
    
    @Test
    @Tag("regression")
    @DisplayName("Should throw exception when cached payment not found")
    fun testCachedPaymentNotFound() {
        // Given
        val idempotencyKey = "idem_test_123"
        val transaction = createTransaction()
        val transactionId = "txn_123"
        
        val existingRecord = IdempotencyRecordRedis(
            idempotencyKey = idempotencyKey,
            transactionId = transactionId,
            requestFingerprint = RequestFingerprintGenerator.generate(transaction),
            status = IdempotencyStatus.COMPLETED
        )
        
        every { idempotencyStore.get(idempotencyKey) } returns existingRecord
        every { paymentRepository.findByTransactionId(transactionId) } returns null
        
        // When & Then
        val exception = assertThrows<IllegalStateException> {
            idempotencyService.checkIdempotency(idempotencyKey, transaction)
        }
        
        assertTrue(exception.message!!.contains("Payment not found"))
    }
    
    @Test
    @Tag("regression")
    @DisplayName("Should handle update status failure gracefully")
    fun testUpdateStatusFailure() {
        // Given
        val idempotencyKey = "idem_test_123"
        every { idempotencyStore.updateStatus(idempotencyKey, IdempotencyStatus.COMPLETED) } returns false
        
        // When
        idempotencyService.markCompleted(idempotencyKey)
        
        // Then - Should not throw exception
        verify(exactly = 1) { idempotencyStore.updateStatus(idempotencyKey, IdempotencyStatus.COMPLETED) }
    }
    
    @Test
    @Tag("regression")
    @DisplayName("Should generate unique transaction IDs")
    fun testGenerateUniqueTransactionIds() {
        // Given
        val idempotencyKey1 = "idem_test_1"
        val idempotencyKey2 = "idem_test_2"
        val transaction = createTransaction()
        
        val capturedRecords = mutableListOf<IdempotencyRecordRedis>()
        
        every { idempotencyStore.get(any()) } returns null
        every { idempotencyStore.createOrGet(any()) } answers {
            val record = firstArg<IdempotencyRecordRedis>()
            capturedRecords.add(record)
            record
        }
        
        // When
        idempotencyService.checkIdempotency(idempotencyKey1, transaction)
        Thread.sleep(10) // Ensure different timestamp
        idempotencyService.checkIdempotency(idempotencyKey2, transaction)
        
        // Then
        assertEquals(2, capturedRecords.size)
        assertNotEquals(capturedRecords[0].transactionId, capturedRecords[1].transactionId)
        assertTrue(capturedRecords[0].transactionId.startsWith("txn_"))
        assertTrue(capturedRecords[1].transactionId.startsWith("txn_"))
    }
    
    // ========================================
    // REGRESSION TESTS - Request Fingerprint
    // ========================================
    
    @Test
    @Tag("regression")
    @DisplayName("Should generate same fingerprint for identical requests")
    fun testSameFingerprintForIdenticalRequests() {
        // Given
        val transaction1 = createTransaction()
        val transaction2 = createTransaction()
        
        // When
        val fingerprint1 = RequestFingerprintGenerator.generate(transaction1)
        val fingerprint2 = RequestFingerprintGenerator.generate(transaction2)
        
        // Then
        assertEquals(fingerprint1, fingerprint2)
    }
    
    @Test
    @Tag("regression")
    @DisplayName("Should generate different fingerprint for different requests")
    fun testDifferentFingerprintForDifferentRequests() {
        // Given
        val transaction1 = createTransaction(amount = BigDecimal("100.00"))
        val transaction2 = createTransaction(amount = BigDecimal("200.00"))
        
        // When
        val fingerprint1 = RequestFingerprintGenerator.generate(transaction1)
        val fingerprint2 = RequestFingerprintGenerator.generate(transaction2)
        
        // Then
        assertNotEquals(fingerprint1, fingerprint2)
    }
    
    @Test
    @Tag("regression")
    @DisplayName("Should validate fingerprint correctly")
    fun testValidateFingerprint() {
        // Given
        val transaction = createTransaction()
        val fingerprint = RequestFingerprintGenerator.generate(transaction)
        
        // When
        val isValid = RequestFingerprintGenerator.validate(transaction, fingerprint)
        
        // Then
        assertTrue(isValid)
    }
    
    @Test
    @Tag("regression")
    @DisplayName("Should reject invalid fingerprint")
    fun testRejectInvalidFingerprint() {
        // Given
        val transaction = createTransaction()
        val invalidFingerprint = "invalid_fingerprint"
        
        // When
        val isValid = RequestFingerprintGenerator.validate(transaction, invalidFingerprint)
        
        // Then
        assertFalse(isValid)
    }
    
    // ========================================
    // Helper Methods
    // ========================================
    
    private fun createTransaction(
        amount: BigDecimal = BigDecimal("100.00")
    ): Transaction {
        return Transaction(
            merchantId = "merchant_123",
            customerId = "customer_456",
            amount = amount,
            currency = "INR",
            paymentMethod = PaymentMethod.CARD,
            paymentDetails = mapOf("card_token" to "tok_visa")
        )
    }
    
    private fun createPayment(transactionId: String): Payment {
        return Payment(
            transactionId = transactionId,
            transaction = createTransaction(),
            status = PaymentStatus.SUCCEEDED,
            createdAt = Instant.now()
        )
    }
}


