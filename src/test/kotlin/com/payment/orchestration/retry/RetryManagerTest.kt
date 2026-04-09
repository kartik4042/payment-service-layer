package com.payment.orchestration.retry

import com.payment.orchestration.domain.model.*
import com.payment.orchestration.provider.*
import io.mockk.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/**
 * Unit Tests for RetryManager
 * 
 * Test Classification:
 * - @Tag("sanity"): Basic retry functionality
 * - @Tag("regression"): Edge cases, failures, backoff logic
 * 
 * Test Framework: JUnit 5 + MockK
 * 
 * Coverage:
 * - Successful payment on first attempt
 * - Retry on transient failures
 * - Exponential backoff calculation
 * - Max retry attempts
 * - Permanent failure handling
 * - Network error handling
 * - Timeout error handling
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("RetryManager Unit Tests")
class RetryManagerTest {
    
    private lateinit var retryManager: RetryManager
    private lateinit var retryPolicy: RetryPolicy
    
    @BeforeEach
    fun setup() {
        // Create retry policy with short delays for testing
        retryPolicy = RetryPolicy(
            maxAttempts = 3,
            baseDelay = Duration.ofMillis(10),
            maxDelay = Duration.ofMillis(100)
        )
        
        retryManager = RetryManager(retryPolicy)
    }
    
    // ========================================
    // SANITY TESTS - Basic Functionality
    // ========================================
    
    @Test
    @Tag("sanity")
    @DisplayName("Should succeed on first attempt")
    fun testSuccessOnFirstAttempt() {
        // Given
        val payment = createPayment()
        val successResponse = ProviderResponse(
            success = true,
            providerTransactionId = "prov_123",
            providerStatus = "succeeded"
        )
        
        // When
        val result = retryManager.executeWithRetry(payment) {
            successResponse
        }
        
        // Then
        assertTrue(result.success)
        assertEquals(1, result.getTotalAttempts())
        assertEquals(successResponse, result.response)
    }
    
    @Test
    @Tag("sanity")
    @DisplayName("Should retry on transient failure and succeed")
    fun testRetryOnTransientFailure() {
        // Given
        val payment = createPayment()
        var attemptCount = 0
        
        val transientResponse = ProviderResponse(
            success = false,
            providerTransactionId = "prov_123",
            providerStatus = "rate_limited",
            errorCode = "rate_limit_exceeded",
            errorMessage = "Rate limit exceeded",
            isTransient = true
        )
        
        val successResponse = ProviderResponse(
            success = true,
            providerTransactionId = "prov_123",
            providerStatus = "succeeded"
        )
        
        // When
        val result = retryManager.executeWithRetry(payment) {
            attemptCount++
            if (attemptCount == 1) transientResponse else successResponse
        }
        
        // Then
        assertTrue(result.success)
        assertEquals(2, result.getTotalAttempts())
        assertEquals(successResponse, result.response)
    }
    
    @Test
    @Tag("sanity")
    @DisplayName("Should not retry on permanent failure")
    fun testNoRetryOnPermanentFailure() {
        // Given
        val payment = createPayment()
        val permanentResponse = ProviderResponse(
            success = false,
            providerTransactionId = "prov_123",
            providerStatus = "failed",
            errorCode = "insufficient_funds",
            errorMessage = "Insufficient funds",
            isTransient = false
        )
        
        // When
        val result = retryManager.executeWithRetry(payment) {
            permanentResponse
        }
        
        // Then
        assertFalse(result.success)
        assertEquals(1, result.getTotalAttempts())
        assertEquals("Permanent failure: Insufficient funds", result.reason)
    }
    
    // ========================================
    // REGRESSION TESTS - Max Retry Attempts
    // ========================================
    
    @Test
    @Tag("regression")
    @DisplayName("Should stop after max retry attempts")
    fun testMaxRetryAttempts() {
        // Given
        val payment = createPayment()
        val transientResponse = ProviderResponse(
            success = false,
            providerTransactionId = "prov_123",
            providerStatus = "service_unavailable",
            errorCode = "service_unavailable",
            errorMessage = "Service temporarily unavailable",
            isTransient = true
        )
        
        // When
        val result = retryManager.executeWithRetry(payment) {
            transientResponse // Always fail
        }
        
        // Then
        assertFalse(result.success)
        assertEquals(3, result.getTotalAttempts()) // maxAttempts = 3
        assertEquals("Max retry attempts exceeded", result.reason)
    }
    
    @Test
    @Tag("regression")
    @DisplayName("Should calculate exponential backoff correctly")
    fun testExponentialBackoff() {
        // Given
        val payment = createPayment()
        val transientResponse = ProviderResponse(
            success = false,
            providerTransactionId = "prov_123",
            providerStatus = "rate_limited",
            isTransient = true
        )
        
        // When
        val startTime = System.currentTimeMillis()
        val result = retryManager.executeWithRetry(payment) {
            transientResponse
        }
        val duration = System.currentTimeMillis() - startTime
        
        // Then
        assertFalse(result.success)
        assertEquals(3, result.getTotalAttempts())
        
        // Verify backoff delays were applied
        // Attempt 1: 10ms, Attempt 2: 20ms, Attempt 3: 40ms
        // Total minimum: 70ms (with jitter, could be slightly more)
        assertTrue(duration >= 70, "Duration should be at least 70ms, was ${duration}ms")
        
        // Verify backoff delays in context
        val attempts = result.context.attempts
        assertNotNull(attempts[0].backoffDelay)
        assertNotNull(attempts[1].backoffDelay)
        assertTrue(attempts[1].backoffDelay!! > attempts[0].backoffDelay!!)
    }
    
    // ========================================
    // REGRESSION TESTS - Exception Handling
    // ========================================
    
    @Test
    @Tag("regression")
    @DisplayName("Should handle ProviderTransientException and retry")
    fun testHandleTransientException() {
        // Given
        val payment = createPayment()
        var attemptCount = 0
        
        val successResponse = ProviderResponse(
            success = true,
            providerTransactionId = "prov_123",
            providerStatus = "succeeded"
        )
        
        // When
        val result = retryManager.executeWithRetry(payment) {
            attemptCount++
            if (attemptCount == 1) {
                throw ProviderTransientException("Temporary error")
            } else {
                successResponse
            }
        }
        
        // Then
        assertTrue(result.success)
        assertEquals(2, result.getTotalAttempts())
    }
    
    @Test
    @Tag("regression")
    @DisplayName("Should handle ProviderPermanentException and not retry")
    fun testHandlePermanentException() {
        // Given
        val payment = createPayment()
        
        // When
        val result = retryManager.executeWithRetry(payment) {
            throw ProviderPermanentException("Invalid card")
        }
        
        // Then
        assertFalse(result.success)
        assertEquals(1, result.getTotalAttempts())
        assertTrue(result.reason!!.contains("Permanent error"))
    }
    
    @Test
    @Tag("regression")
    @DisplayName("Should handle ProviderNetworkException and retry with shorter backoff")
    fun testHandleNetworkException() {
        // Given
        val payment = createPayment()
        var attemptCount = 0
        
        val successResponse = ProviderResponse(
            success = true,
            providerTransactionId = "prov_123",
            providerStatus = "succeeded"
        )
        
        // When
        val result = retryManager.executeWithRetry(payment) {
            attemptCount++
            if (attemptCount == 1) {
                throw ProviderNetworkException("Connection timeout")
            } else {
                successResponse
            }
        }
        
        // Then
        assertTrue(result.success)
        assertEquals(2, result.getTotalAttempts())
        
        // Verify shorter backoff was used
        val firstAttempt = result.context.attempts[0]
        assertNotNull(firstAttempt.backoffDelay)
        assertTrue(firstAttempt.backoffDelay!! < Duration.ofMillis(20))
    }
    
    @Test
    @Tag("regression")
    @DisplayName("Should handle ProviderTimeoutException and retry")
    fun testHandleTimeoutException() {
        // Given
        val payment = createPayment()
        var attemptCount = 0
        
        val successResponse = ProviderResponse(
            success = true,
            providerTransactionId = "prov_123",
            providerStatus = "succeeded"
        )
        
        // When
        val result = retryManager.executeWithRetry(payment) {
            attemptCount++
            if (attemptCount == 1) {
                throw ProviderTimeoutException("Request timeout")
            } else {
                successResponse
            }
        }
        
        // Then
        assertTrue(result.success)
        assertEquals(2, result.getTotalAttempts())
    }
    
    @Test
    @Tag("regression")
    @DisplayName("Should handle unexpected exception and not retry")
    fun testHandleUnexpectedException() {
        // Given
        val payment = createPayment()
        
        // When
        val result = retryManager.executeWithRetry(payment) {
            throw RuntimeException("Unexpected error")
        }
        
        // Then
        assertFalse(result.success)
        assertEquals(1, result.getTotalAttempts())
        assertTrue(result.reason!!.contains("Unexpected error"))
    }
    
    // ========================================
    // REGRESSION TESTS - Retry Context
    // ========================================
    
    @Test
    @Tag("regression")
    @DisplayName("Should track retry context correctly")
    fun testRetryContext() {
        // Given
        val payment = createPayment()
        var attemptCount = 0
        
        val transientResponse = ProviderResponse(
            success = false,
            providerTransactionId = "prov_123",
            providerStatus = "rate_limited",
            isTransient = true
        )
        
        val successResponse = ProviderResponse(
            success = true,
            providerTransactionId = "prov_123",
            providerStatus = "succeeded"
        )
        
        // When
        val result = retryManager.executeWithRetry(payment) {
            attemptCount++
            if (attemptCount < 3) transientResponse else successResponse
        }
        
        // Then
        assertTrue(result.success)
        assertEquals(3, result.getTotalAttempts())
        
        val context = result.context
        assertEquals(payment.transactionId, context.transactionId)
        assertEquals(3, context.attempts.size)
        
        // Verify first two attempts failed
        assertFalse(context.attempts[0].success)
        assertFalse(context.attempts[1].success)
        assertTrue(context.attempts[2].success)
        
        // Verify backoff delays
        assertNotNull(context.attempts[0].backoffDelay)
        assertNotNull(context.attempts[1].backoffDelay)
        assertNull(context.attempts[2].backoffDelay) // No backoff after success
        
        // Verify durations
        assertNotNull(context.attempts[0].duration)
        assertNotNull(context.attempts[1].duration)
        assertNotNull(context.attempts[2].duration)
        
        // Verify total duration
        assertTrue(context.getTotalDuration().toMillis() > 0)
    }
    
    @Test
    @Tag("regression")
    @DisplayName("Should track successful attempt in context")
    fun testSuccessfulAttemptInContext() {
        // Given
        val payment = createPayment()
        val successResponse = ProviderResponse(
            success = true,
            providerTransactionId = "prov_123",
            providerStatus = "succeeded"
        )
        
        // When
        val result = retryManager.executeWithRetry(payment) {
            successResponse
        }
        
        // Then
        val successfulAttempt = result.context.getSuccessfulAttempt()
        assertNotNull(successfulAttempt)
        assertEquals(1, successfulAttempt!!.attemptNumber)
        assertTrue(successfulAttempt.success)
        assertEquals(successResponse, successfulAttempt.providerResponse)
    }
    
    // ========================================
    // REGRESSION TESTS - Retry Policy
    // ========================================
    
    @Test
    @Tag("regression")
    @DisplayName("Should respect aggressive retry policy")
    fun testAggressiveRetryPolicy() {
        // Given
        val aggressivePolicy = RetryPolicy.aggressive()
        val aggressiveRetryManager = RetryManager(aggressivePolicy)
        val payment = createPayment()
        
        val transientResponse = ProviderResponse(
            success = false,
            providerTransactionId = "prov_123",
            providerStatus = "rate_limited",
            isTransient = true
        )
        
        // When
        val result = aggressiveRetryManager.executeWithRetry(payment) {
            transientResponse
        }
        
        // Then
        assertFalse(result.success)
        assertEquals(5, result.getTotalAttempts()) // Aggressive policy has 5 attempts
    }
    
    @Test
    @Tag("regression")
    @DisplayName("Should respect conservative retry policy")
    fun testConservativeRetryPolicy() {
        // Given
        val conservativePolicy = RetryPolicy.conservative()
        val conservativeRetryManager = RetryManager(conservativePolicy)
        val payment = createPayment()
        
        val transientResponse = ProviderResponse(
            success = false,
            providerTransactionId = "prov_123",
            providerStatus = "rate_limited",
            isTransient = true
        )
        
        // When
        val result = conservativeRetryManager.executeWithRetry(payment) {
            transientResponse
        }
        
        // Then
        assertFalse(result.success)
        assertEquals(2, result.getTotalAttempts()) // Conservative policy has 2 attempts
    }
    
    // ========================================
    // REGRESSION TESTS - Edge Cases
    // ========================================
    
    @Test
    @Tag("regression")
    @DisplayName("Should handle alternating success and failure")
    fun testAlternatingResults() {
        // Given
        val payment = createPayment()
        var attemptCount = 0
        
        val transientResponse = ProviderResponse(
            success = false,
            providerTransactionId = "prov_123",
            providerStatus = "rate_limited",
            isTransient = true
        )
        
        val successResponse = ProviderResponse(
            success = true,
            providerTransactionId = "prov_123",
            providerStatus = "succeeded"
        )
        
        // When
        val result = retryManager.executeWithRetry(payment) {
            attemptCount++
            // Fail on odd attempts, succeed on even
            if (attemptCount % 2 == 1) transientResponse else successResponse
        }
        
        // Then
        assertTrue(result.success)
        assertEquals(2, result.getTotalAttempts())
    }
    
    @Test
    @Tag("regression")
    @DisplayName("Should handle all attempts failing with different errors")
    fun testDifferentErrorsOnEachAttempt() {
        // Given
        val payment = createPayment()
        var attemptCount = 0
        
        // When
        val result = retryManager.executeWithRetry(payment) {
            attemptCount++
            when (attemptCount) {
                1 -> throw ProviderTransientException("Error 1")
                2 -> throw ProviderNetworkException("Error 2")
                else -> throw ProviderTimeoutException("Error 3")
            }
        }
        
        // Then
        assertFalse(result.success)
        assertEquals(3, result.getTotalAttempts())
        
        // Verify different errors were recorded
        assertEquals("Error 1", result.context.attempts[0].error)
        assertEquals("Error 2", result.context.attempts[1].error)
        assertEquals("Error 3", result.context.attempts[2].error)
    }
    
    // ========================================
    // Helper Methods
    // ========================================
    
    private fun createPayment(): Payment {
        val transaction = Transaction(
            merchantId = "merchant_123",
            customerId = "customer_456",
            amount = BigDecimal("100.00"),
            currency = "INR",
            paymentMethod = PaymentMethod.CARD,
            paymentDetails = mapOf("card_token" to "tok_visa")
        )
        
        return Payment(
            transactionId = "txn_test_${System.currentTimeMillis()}",
            transaction = transaction,
            status = PaymentStatus.INITIATED,
            createdAt = Instant.now()
        )
    }
}

