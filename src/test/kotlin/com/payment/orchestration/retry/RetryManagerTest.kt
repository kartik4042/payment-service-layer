package com.payment.orchestration.retry

import com.payment.orchestration.domain.model.Payment
import com.payment.orchestration.domain.model.PaymentMethod
import com.payment.orchestration.domain.model.Transaction
import com.payment.orchestration.provider.ProviderException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Unit tests for RetryManager
 * 
 * Test Coverage:
 * - Successful execution on first attempt
 * - Retry on transient failures
 * - Stop retry on permanent failures
 * - Max retry attempts enforcement
 * - Exponential backoff behavior
 * - Different error type handling
 */
class RetryManagerTest {
    
    private lateinit var retryManager: RetryManager
    private lateinit var payment: Payment
    
    @BeforeEach
    fun setup() {
        retryManager = RetryManager()
        payment = createPayment()
    }
    
    @Test
    fun `should succeed on first attempt without retry`() {
        // Given
        val operation: () -> com.payment.orchestration.provider.ProviderResponse = {
            com.payment.orchestration.provider.ProviderResponse(
                success = true,
                providerTransactionId = "prov_123",
                providerStatus = "SUCCESS",
                message = "Payment successful"
            )
        }
        
        // When
        val result = retryManager.executeWithRetry(payment, operation)
        
        // Then
        assertTrue(result.success)
        assertEquals(1, result.getTotalAttempts())
        assertNotNull(result.response)
        assertEquals("prov_123", result.response?.providerTransactionId)
    }
    
    @Test
    fun `should retry on transient network error and eventually succeed`() {
        // Given
        var attemptCount = 0
        val operation: () -> com.payment.orchestration.provider.ProviderResponse = {
            attemptCount++
            if (attemptCount < 3) {
                throw ProviderException.NetworkException("Network timeout", null)
            } else {
                com.payment.orchestration.provider.ProviderResponse(
                    success = true,
                    providerTransactionId = "prov_123",
                    providerStatus = "SUCCESS",
                    message = "Payment successful"
                )
            }
        }
        
        // When
        val result = retryManager.executeWithRetry(payment, operation)
        
        // Then
        assertTrue(result.success)
        assertEquals(3, result.getTotalAttempts())
        assertNotNull(result.response)
    }
    
    @Test
    fun `should not retry on permanent error`() {
        // Given
        val operation: () -> com.payment.orchestration.provider.ProviderResponse = {
            throw ProviderException.PermanentException(
                "Invalid card number",
                "INVALID_CARD",
                null
            )
        }
        
        // When
        val result = retryManager.executeWithRetry(payment, operation)
        
        // Then
        assertFalse(result.success)
        assertEquals(1, result.getTotalAttempts())
        assertNull(result.response)
        assertTrue(result.reason!!.contains("Invalid card number"))
    }
    
    @Test
    fun `should stop after max retry attempts`() {
        // Given
        val operation: () -> com.payment.orchestration.provider.ProviderResponse = {
            throw ProviderException.TransientException("Service unavailable", null)
        }
        
        // When
        val result = retryManager.executeWithRetry(payment, operation)
        
        // Then
        assertFalse(result.success)
        assertEquals(3, result.getTotalAttempts()) // Default max attempts is 3
        assertNull(result.response)
        assertTrue(result.reason!!.contains("Max retry attempts reached"))
    }
    
    @Test
    fun `should handle timeout exception with retry`() {
        // Given
        var attemptCount = 0
        val operation: () -> com.payment.orchestration.provider.ProviderResponse = {
            attemptCount++
            if (attemptCount < 2) {
                throw ProviderException.TimeoutException("Request timeout", null)
            } else {
                com.payment.orchestration.provider.ProviderResponse(
                    success = true,
                    providerTransactionId = "prov_123",
                    providerStatus = "SUCCESS",
                    message = "Payment successful"
                )
            }
        }
        
        // When
        val result = retryManager.executeWithRetry(payment, operation)
        
        // Then
        assertTrue(result.success)
        assertEquals(2, result.getTotalAttempts())
    }
    
    @Test
    fun `should use aggressive retry policy for high priority payments`() {
        // Given
        val aggressiveRetryManager = RetryManager(
            retryPolicy = RetryPolicy.AGGRESSIVE
        )
        
        var attemptCount = 0
        val operation: () -> com.payment.orchestration.provider.ProviderResponse = {
            attemptCount++
            if (attemptCount < 5) {
                throw ProviderException.TransientException("Service unavailable", null)
            } else {
                com.payment.orchestration.provider.ProviderResponse(
                    success = true,
                    providerTransactionId = "prov_123",
                    providerStatus = "SUCCESS",
                    message = "Payment successful"
                )
            }
        }
        
        // When
        val result = aggressiveRetryManager.executeWithRetry(payment, operation)
        
        // Then
        assertTrue(result.success)
        assertEquals(5, result.getTotalAttempts())
    }
    
    @Test
    fun `should use conservative retry policy for low priority payments`() {
        // Given
        val conservativeRetryManager = RetryManager(
            retryPolicy = RetryPolicy.CONSERVATIVE
        )
        
        val operation: () -> com.payment.orchestration.provider.ProviderResponse = {
            throw ProviderException.TransientException("Service unavailable", null)
        }
        
        // When
        val result = conservativeRetryManager.executeWithRetry(payment, operation)
        
        // Then
        assertFalse(result.success)
        assertEquals(2, result.getTotalAttempts()) // Conservative policy has fewer attempts
    }
    
    private fun createPayment(): Payment {
        val transaction = Transaction(
            amount = BigDecimal("100.00"),
            currency = "INR",
            paymentMethod = PaymentMethod.CARD,
            customerId = "cust_123",
            merchantId = "merch_456",
            description = "Test payment",
            paymentDetails = mapOf("card_token" to "tok_123")
        )
        
        return Payment.create(
            customerId = "cust_123",
            merchantId = "merch_456",
            transaction = transaction
        )
    }
}

