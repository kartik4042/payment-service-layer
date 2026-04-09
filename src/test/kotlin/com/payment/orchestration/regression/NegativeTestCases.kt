package com.payment.orchestration.regression

import com.payment.orchestration.domain.model.*
import com.payment.orchestration.idempotency.*
import com.payment.orchestration.provider.*
import com.payment.orchestration.routing.RoutingEngine
import com.payment.orchestration.routing.NoAvailableProviderException
import com.payment.orchestration.retry.RetryManager
import com.payment.orchestration.retry.FailoverManager
import com.payment.orchestration.retry.RetryPolicy
import com.payment.orchestration.service.PaymentOrchestrationService
import com.payment.orchestration.repository.PaymentRepositoryAdapter
import io.mockk.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Negative Test Cases
 * 
 * Test Classification:
 * - @Tag("regression"): Edge cases, failures, error scenarios
 * 
 * Test Framework: JUnit 5 + MockK
 * 
 * Coverage:
 * - Duplicate payment prevention
 * - Concurrent request handling
 * - Provider failures and retries
 * - Network errors and timeouts
 * - Invalid input handling
 * - Race conditions
 * - Resource exhaustion
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Negative Test Cases - Failures, Retries, Duplicates")
class NegativeTestCases {
    
    private lateinit var orchestrationService: PaymentOrchestrationService
    private lateinit var idempotencyService: IdempotencyService
    private lateinit var idempotencyStore: IdempotencyStore
    private lateinit var paymentRepository: PaymentRepositoryAdapter
    private lateinit var routingEngine: RoutingEngine
    private lateinit var retryManager: RetryManager
    private lateinit var failoverManager: FailoverManager
    private lateinit var providerA: PaymentProvider
    private lateinit var providerB: PaymentProvider
    
    @BeforeEach
    fun setup() {
        // Create mocks
        idempotencyStore = mockk<IdempotencyStore>()
        paymentRepository = mockk<PaymentRepositoryAdapter>()
        providerA = mockk<PaymentProvider>()
        providerB = mockk<PaymentProvider>()
        
        every { providerA.getProviderId() } returns Provider.PROVIDER_A
        every { providerB.getProviderId() } returns Provider.PROVIDER_B
        
        // Create services
        idempotencyService = IdempotencyService(idempotencyStore, paymentRepository)
        
        val providers = mapOf(
            Provider.PROVIDER_A to providerA,
            Provider.PROVIDER_B to providerB
        )
        routingEngine = RoutingEngine(providers)
        
        val retryPolicy = RetryPolicy(
            maxAttempts = 3,
            baseDelay = Duration.ofMillis(10),
            maxDelay = Duration.ofMillis(100)
        )
        retryManager = RetryManager(retryPolicy)
        failoverManager = FailoverManager(routingEngine, retryManager)
        
        orchestrationService = PaymentOrchestrationService(
            routingEngine,
            retryManager,
            failoverManager
        )
    }
    
    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }
    
    // ========================================
    // REGRESSION - Duplicate Prevention
    // ========================================
    
    @Test
    @Tag("regression")
    @DisplayName("Should prevent duplicate payment with same idempotency key")
    fun testPreventDuplicatePayment() {
        // Given
        val idempotencyKey = "idem_duplicate_test"
        val transaction = createTransaction()
        val existingTransactionId = "txn_existing_123"
        
        val existingRecord = IdempotencyRecordRedis(
            idempotencyKey = idempotencyKey,
            transactionId = existingTransactionId,
            requestFingerprint = RequestFingerprintGenerator.generate(transaction),
            status = IdempotencyStatus.COMPLETED
        )
        
        val existingPayment = createPayment(existingTransactionId, PaymentStatus.SUCCEEDED)
        
        every { idempotencyStore.get(idempotencyKey) } returns existingRecord
        every { paymentRepository.findByTransactionId(existingTransactionId) } returns existingPayment
        
        // When
        val result = idempotencyService.checkIdempotency(idempotencyKey, transaction)
        
        // Then
        assertNotNull(result)
        assertEquals(existingTransactionId, result!!.transactionId)
        assertEquals(PaymentStatus.SUCCEEDED, result.status)
        
        // Verify no new payment was created
        verify(exactly = 0) { idempotencyStore.createOrGet(any()) }
    }
    
    @Test
    @Tag("regression")
    @DisplayName("Should reject duplicate with different payload")
    fun testRejectDuplicateWithDifferentPayload() {
        // Given
        val idempotencyKey = "idem_duplicate_test"
        val originalTransaction = createTransaction(amount = BigDecimal("100.00"))
        val differentTransaction = createTransaction(amount = BigDecimal("200.00"))
        
        val existingRecord = IdempotencyRecordRedis(
            idempotencyKey = idempotencyKey,
            transactionId = "txn_123",
            requestFingerprint = RequestFingerprintGenerator.generate(originalTransaction),
            status = IdempotencyStatus.COMPLETED
        )
        
        every { idempotencyStore.get(idempotencyKey) } returns existingRecord
        
        // When & Then
        val exception = assertThrows<IdempotencyFingerprintMismatchException> {
            idempotencyService.checkIdempotency(idempotencyKey, differentTransaction)
        }
        
        assertTrue(exception.message!!.contains("Request payload differs"))
    }
    
    @Test
    @Tag("regression")
    @DisplayName("Should handle concurrent duplicate requests")
    fun testConcurrentDuplicateRequests() {
        // Given
        val idempotencyKey = "idem_concurrent_test"
        val transaction = createTransaction()
        val transactionId = "txn_concurrent_123"
        
        val latch = CountDownLatch(2)
        val results = mutableListOf<Payment?>()
        val exceptions = mutableListOf<Exception>()
        
        // First request creates record
        every { idempotencyStore.get(idempotencyKey) } returns null andThen IdempotencyRecordRedis(
            idempotencyKey = idempotencyKey,
            transactionId = transactionId,
            requestFingerprint = RequestFingerprintGenerator.generate(transaction),
            status = IdempotencyStatus.PROCESSING
        )
        
        every { idempotencyStore.createOrGet(any()) } answers {
            firstArg<IdempotencyRecordRedis>()
        }
        
        // When - Simulate concurrent requests
        thread {
            try {
                results.add(idempotencyService.checkIdempotency(idempotencyKey, transaction))
            } catch (e: Exception) {
                exceptions.add(e)
            } finally {
                latch.countDown()
            }
        }
        
        thread {
            Thread.sleep(50) // Slight delay
            try {
                results.add(idempotencyService.checkIdempotency(idempotencyKey, transaction))
            } catch (e: Exception) {
                exceptions.add(e)
            } finally {
                latch.countDown()
            }
        }
        
        latch.await(5, TimeUnit.SECONDS)
        
        // Then - One should succeed, one should get conflict
        assertTrue(exceptions.any { it is IdempotencyConflictException })
    }
    
    // ========================================
    // REGRESSION - Provider Failures
    // ========================================
    
    @Test
    @Tag("regression")
    @DisplayName("Should handle all providers down")
    fun testAllProvidersDown() {
        // Given
        val payment = createPayment("txn_test", PaymentStatus.INITIATED)
        
        every { providerA.isHealthy() } returns false
        every { providerB.isHealthy() } returns false
        
        // When
        val result = orchestrationService.orchestratePayment(payment)
        
        // Then
        assertEquals(PaymentStatus.FAILED, result.status)
        assertTrue(result.failureReason!!.contains("No healthy provider"))
        
        verify(exactly = 0) { providerA.processPayment(any()) }
        verify(exactly = 0) { providerB.processPayment(any()) }
    }
    
    @Test
    @Tag("regression")
    @DisplayName("Should handle provider throwing unexpected exception")
    fun testProviderUnexpectedException() {
        // Given
        val payment = createPayment("txn_test", PaymentStatus.INITIATED)
        
        every { providerA.isHealthy() } returns true
        every { providerA.processPayment(any()) } throws RuntimeException("Unexpected error")
        
        every { providerB.isHealthy() } returns false
        
        // When
        val result = orchestrationService.orchestratePayment(payment)
        
        // Then
        assertEquals(PaymentStatus.FAILED, result.status)
        assertNotNull(result.failureReason)
    }
    
    @Test
    @Tag("regression")
    @DisplayName("Should handle provider returning null response")
    fun testProviderNullResponse() {
        // Given
        val payment = createPayment("txn_test", PaymentStatus.INITIATED)
        
        every { providerA.isHealthy() } returns true
        every { providerA.processPayment(any()) } throws NullPointerException("Null response")
        
        every { providerB.isHealthy() } returns false
        
        // When
        val result = orchestrationService.orchestratePayment(payment)
        
        // Then
        assertEquals(PaymentStatus.FAILED, result.status)
    }
    
    // ========================================
    // REGRESSION - Retry Exhaustion
    // ========================================
    
    @Test
    @Tag("regression")
    @DisplayName("Should exhaust retries on persistent transient failures")
    fun testExhaustRetriesOnTransientFailures() {
        // Given
        val payment = createPayment("txn_test", PaymentStatus.INITIATED)
        
        every { providerA.isHealthy() } returns true
        every { providerA.processPayment(any()) } returns ProviderResponse(
            success = false,
            providerTransactionId = "prov_123",
            providerStatus = "rate_limited",
            errorCode = "rate_limit_exceeded",
            isTransient = true
        )
        
        every { providerB.isHealthy() } returns false
        
        // When
        val result = orchestrationService.orchestratePayment(payment)
        
        // Then
        assertEquals(PaymentStatus.FAILED, result.status)
        verify(exactly = 3) { providerA.processPayment(any()) } // Max 3 attempts
    }
    
    @Test
    @Tag("regression")
    @DisplayName("Should handle alternating transient and permanent failures")
    fun testAlternatingFailures() {
        // Given
        val payment = createPayment("txn_test", PaymentStatus.INITIATED)
        var attemptCount = 0
        
        every { providerA.isHealthy() } returns true
        every { providerA.processPayment(any()) } answers {
            attemptCount++
            if (attemptCount == 1) {
                ProviderResponse(
                    success = false,
                    providerTransactionId = "prov_123",
                    providerStatus = "rate_limited",
                    isTransient = true
                )
            } else {
                ProviderResponse(
                    success = false,
                    providerTransactionId = "prov_123",
                    providerStatus = "failed",
                    errorCode = "insufficient_funds",
                    isTransient = false
                )
            }
        }
        
        // When
        val result = orchestrationService.orchestratePayment(payment)
        
        // Then
        assertEquals(PaymentStatus.FAILED, result.status)
        assertEquals(2, attemptCount) // Retry once, then permanent failure
    }
    
    // ========================================
    // REGRESSION - Network Issues
    // ========================================
    
    @Test
    @Tag("regression")
    @DisplayName("Should handle network timeout on all attempts")
    fun testNetworkTimeoutOnAllAttempts() {
        // Given
        val payment = createPayment("txn_test", PaymentStatus.INITIATED)
        
        every { providerA.isHealthy() } returns true
        every { providerA.processPayment(any()) } throws ProviderTimeoutException("Request timeout")
        
        every { providerB.isHealthy() } returns false
        
        // When
        val result = orchestrationService.orchestratePayment(payment)
        
        // Then
        assertEquals(PaymentStatus.FAILED, result.status)
        verify(exactly = 3) { providerA.processPayment(any()) }
    }
    
    @Test
    @Tag("regression")
    @DisplayName("Should handle intermittent network failures")
    fun testIntermittentNetworkFailures() {
        // Given
        val payment = createPayment("txn_test", PaymentStatus.INITIATED)
        var attemptCount = 0
        
        every { providerA.isHealthy() } returns true
        every { providerA.processPayment(any()) } answers {
            attemptCount++
            if (attemptCount % 2 == 1) {
                throw ProviderNetworkException("Connection failed")
            } else {
                ProviderResponse(
                    success = true,
                    providerTransactionId = "prov_123",
                    providerStatus = "succeeded"
                )
            }
        }
        
        // When
        val result = orchestrationService.orchestratePayment(payment)
        
        // Then
        assertEquals(PaymentStatus.SUCCEEDED, result.status)
        assertEquals(2, attemptCount) // Fail, then succeed
    }
    
    // ========================================
    // REGRESSION - Invalid Input
    // ========================================
    
    @Test
    @Tag("regression")
    @DisplayName("Should reject payment with zero amount")
    fun testRejectZeroAmount() {
        // Given
        val payment = createPayment("txn_test", PaymentStatus.INITIATED, BigDecimal.ZERO)
        
        // When
        val result = orchestrationService.orchestratePayment(payment)
        
        // Then
        assertEquals(PaymentStatus.FAILED, result.status)
        assertTrue(result.failureReason!!.contains("greater than zero"))
    }
    
    @Test
    @Tag("regression")
    @DisplayName("Should reject payment with negative amount")
    fun testRejectNegativeAmount() {
        // Given
        val payment = createPayment("txn_test", PaymentStatus.INITIATED, BigDecimal("-100.00"))
        
        // When
        val result = orchestrationService.orchestratePayment(payment)
        
        // Then
        assertEquals(PaymentStatus.FAILED, result.status)
    }
    
    @Test
    @Tag("regression")
    @DisplayName("Should reject CARD payment without card token")
    fun testRejectCardPaymentWithoutToken() {
        // Given
        val transaction = Transaction(
            merchantId = "merchant_123",
            customerId = "customer_456",
            amount = BigDecimal("100.00"),
            currency = "INR",
            paymentMethod = PaymentMethod.CARD,
            paymentDetails = emptyMap() // Missing card_token
        )
        
        val payment = Payment(
            transactionId = "txn_test",
            transaction = transaction,
            status = PaymentStatus.INITIATED,
            createdAt = Instant.now()
        )
        
        // When
        val result = orchestrationService.orchestratePayment(payment)
        
        // Then
        assertEquals(PaymentStatus.FAILED, result.status)
        assertTrue(result.failureReason!!.contains("Card token is required"))
    }
    
    @Test
    @Tag("regression")
    @DisplayName("Should reject UPI payment without VPA")
    fun testRejectUpiPaymentWithoutVPA() {
        // Given
        val transaction = Transaction(
            merchantId = "merchant_123",
            customerId = "customer_456",
            amount = BigDecimal("100.00"),
            currency = "INR",
            paymentMethod = PaymentMethod.UPI,
            paymentDetails = emptyMap() // Missing vpa
        )
        
        val payment = Payment(
            transactionId = "txn_test",
            transaction = transaction,
            status = PaymentStatus.INITIATED,
            createdAt = Instant.now()
        )
        
        // When
        val result = orchestrationService.orchestratePayment(payment)
        
        // Then
        assertEquals(PaymentStatus.FAILED, result.status)
        assertTrue(result.failureReason!!.contains("VPA is required"))
    }
    
    // ========================================
    // REGRESSION - Failover Failures
    // ========================================
    
    @Test
    @Tag("regression")
    @DisplayName("Should fail when both primary and fallback fail")
    fun testBothPrimaryAndFallbackFail() {
        // Given
        val payment = createPayment("txn_test", PaymentStatus.INITIATED)
        
        every { providerA.isHealthy() } returns true
        every { providerA.processPayment(any()) } returns ProviderResponse(
            success = false,
            providerTransactionId = "prov_123",
            providerStatus = "service_unavailable",
            isTransient = true
        )
        
        every { providerB.isHealthy() } returns true
        every { providerB.processPayment(any()) } returns ProviderResponse(
            success = false,
            providerTransactionId = "prov_456",
            providerStatus = "service_unavailable",
            isTransient = true
        )
        
        // When
        val result = orchestrationService.orchestratePayment(payment)
        
        // Then
        assertEquals(PaymentStatus.FAILED, result.status)
        assertTrue(result.metadata.containsKey("failover_attempted"))
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
    
    private fun createPayment(
        transactionId: String,
        status: PaymentStatus,
        amount: BigDecimal = BigDecimal("100.00")
    ): Payment {
        val transaction = createTransaction(amount)
        
        return Payment(
            transactionId = transactionId,
            transaction = transaction,
            status = status,
            createdAt = Instant.now()
        )
    }
}


