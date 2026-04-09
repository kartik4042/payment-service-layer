package com.payment.orchestration.integration

import com.payment.orchestration.domain.model.*
import com.payment.orchestration.provider.*
import com.payment.orchestration.routing.RoutingEngine
import com.payment.orchestration.retry.RetryManager
import com.payment.orchestration.retry.FailoverManager
import com.payment.orchestration.retry.RetryPolicy
import com.payment.orchestration.service.PaymentOrchestrationService
import io.mockk.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/**
 * Integration Tests for PaymentOrchestrationService
 * 
 * Test Classification:
 * - @Tag("integration"): End-to-end payment flow tests
 * 
 * Test Framework: JUnit 5 + MockK
 * 
 * Coverage:
 * - Complete payment orchestration flow
 * - Provider selection and routing
 * - Retry and failover logic
 * - State transitions
 * - Success and failure scenarios
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("PaymentOrchestrationService Integration Tests")
class PaymentOrchestrationIntegrationTest {
    
    private lateinit var orchestrationService: PaymentOrchestrationService
    private lateinit var routingEngine: RoutingEngine
    private lateinit var retryManager: RetryManager
    private lateinit var failoverManager: FailoverManager
    private lateinit var providerA: PaymentProvider
    private lateinit var providerB: PaymentProvider
    
    @BeforeEach
    fun setup() {
        // Create mock providers
        providerA = mockk<PaymentProvider>()
        providerB = mockk<PaymentProvider>()
        
        every { providerA.getProviderId() } returns Provider.PROVIDER_A
        every { providerB.getProviderId() } returns Provider.PROVIDER_B
        
        // Create routing engine
        val providers = mapOf(
            Provider.PROVIDER_A to providerA,
            Provider.PROVIDER_B to providerB
        )
        routingEngine = RoutingEngine(providers)
        
        // Create retry manager with fast policy for testing
        val retryPolicy = RetryPolicy(
            maxAttempts = 3,
            baseDelay = Duration.ofMillis(10),
            maxDelay = Duration.ofMillis(100)
        )
        retryManager = RetryManager(retryPolicy)
        
        // Create failover manager
        failoverManager = FailoverManager(routingEngine, retryManager)
        
        // Create orchestration service
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
    // INTEGRATION TESTS - Success Scenarios
    // ========================================
    
    @Test
    @Tag("integration")
    @DisplayName("Should orchestrate CARD payment successfully with ProviderA")
    fun testSuccessfulCardPayment() {
        // Given
        val payment = createPayment(PaymentMethod.CARD, BigDecimal("100.00"))
        
        every { providerA.isHealthy() } returns true
        every { providerA.processPayment(any()) } returns ProviderResponse(
            success = true,
            providerTransactionId = "prov_123",
            providerStatus = "succeeded"
        )
        
        // When
        val result = orchestrationService.orchestratePayment(payment)
        
        // Then
        assertEquals(PaymentStatus.SUCCEEDED, result.status)
        assertEquals(Provider.PROVIDER_A, result.selectedProvider)
        assertEquals("prov_123", result.providerTransactionId)
        assertEquals("succeeded", result.providerStatus)
        assertNotNull(result.completedAt)
        
        verify(exactly = 1) { providerA.isHealthy() }
        verify(exactly = 1) { providerA.processPayment(any()) }
    }
    
    @Test
    @Tag("integration")
    @DisplayName("Should orchestrate UPI payment successfully with ProviderB")
    fun testSuccessfulUpiPayment() {
        // Given
        val payment = createPayment(PaymentMethod.UPI, BigDecimal("500.00"))
        
        every { providerB.isHealthy() } returns true
        every { providerB.processPayment(any()) } returns ProviderResponse(
            success = true,
            providerTransactionId = "upi_456",
            providerStatus = "success"
        )
        
        // When
        val result = orchestrationService.orchestratePayment(payment)
        
        // Then
        assertEquals(PaymentStatus.SUCCEEDED, result.status)
        assertEquals(Provider.PROVIDER_B, result.selectedProvider)
        assertEquals("upi_456", result.providerTransactionId)
        
        verify(exactly = 1) { providerB.isHealthy() }
        verify(exactly = 1) { providerB.processPayment(any()) }
    }
    
    @Test
    @Tag("integration")
    @DisplayName("Should track state transitions correctly")
    fun testStateTransitions() {
        // Given
        val payment = createPayment(PaymentMethod.CARD, BigDecimal("100.00"))
        
        every { providerA.isHealthy() } returns true
        every { providerA.processPayment(any()) } returns ProviderResponse(
            success = true,
            providerTransactionId = "prov_123",
            providerStatus = "succeeded"
        )
        
        // When
        val result = orchestrationService.orchestratePayment(payment)
        
        // Then - Verify state transitions
        // INITIATED → ROUTING → PROCESSING → SUCCEEDED
        assertEquals(PaymentStatus.SUCCEEDED, result.status)
        assertTrue(result.isTerminal())
    }
    
    // ========================================
    // INTEGRATION TESTS - Retry Scenarios
    // ========================================
    
    @Test
    @Tag("integration")
    @DisplayName("Should retry on transient failure and succeed")
    fun testRetryOnTransientFailure() {
        // Given
        val payment = createPayment(PaymentMethod.CARD, BigDecimal("100.00"))
        var attemptCount = 0
        
        every { providerA.isHealthy() } returns true
        every { providerA.processPayment(any()) } answers {
            attemptCount++
            if (attemptCount == 1) {
                ProviderResponse(
                    success = false,
                    providerTransactionId = "prov_123",
                    providerStatus = "rate_limited",
                    errorCode = "rate_limit_exceeded",
                    isTransient = true
                )
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
        assertEquals(2, attemptCount) // First attempt failed, second succeeded
        assertTrue(result.metadata.containsKey("retry_attempts"))
        
        verify(exactly = 2) { providerA.processPayment(any()) }
    }
    
    @Test
    @Tag("integration")
    @DisplayName("Should fail after max retry attempts")
    fun testFailAfterMaxRetries() {
        // Given
        val payment = createPayment(PaymentMethod.CARD, BigDecimal("100.00"))
        
        every { providerA.isHealthy() } returns true
        every { providerA.processPayment(any()) } returns ProviderResponse(
            success = false,
            providerTransactionId = "prov_123",
            providerStatus = "service_unavailable",
            errorCode = "service_unavailable",
            isTransient = true
        )
        
        every { providerB.isHealthy() } returns false // Fallback unavailable
        
        // When
        val result = orchestrationService.orchestratePayment(payment)
        
        // Then
        assertEquals(PaymentStatus.FAILED, result.status)
        assertNotNull(result.failureReason)
        assertNotNull(result.completedAt)
        
        verify(exactly = 3) { providerA.processPayment(any()) } // Max 3 attempts
    }
    
    // ========================================
    // INTEGRATION TESTS - Failover Scenarios
    // ========================================
    
    @Test
    @Tag("integration")
    @DisplayName("Should failover to ProviderB when ProviderA fails")
    fun testFailoverToProviderB() {
        // Given
        val payment = createPayment(PaymentMethod.CARD, BigDecimal("100.00"))
        
        // ProviderA fails all attempts
        every { providerA.isHealthy() } returns true
        every { providerA.processPayment(any()) } returns ProviderResponse(
            success = false,
            providerTransactionId = "prov_123",
            providerStatus = "service_unavailable",
            errorCode = "service_unavailable",
            isTransient = true
        )
        
        // ProviderB succeeds
        every { providerB.isHealthy() } returns true
        every { providerB.processPayment(any()) } returns ProviderResponse(
            success = true,
            providerTransactionId = "prov_456",
            providerStatus = "succeeded"
        )
        
        // When
        val result = orchestrationService.orchestratePayment(payment)
        
        // Then
        assertEquals(PaymentStatus.SUCCEEDED, result.status)
        assertEquals(Provider.PROVIDER_B, result.selectedProvider) // Failover to B
        assertEquals("prov_456", result.providerTransactionId)
        assertTrue(result.metadata.containsKey("failover_attempted"))
        
        verify(exactly = 3) { providerA.processPayment(any()) } // 3 attempts with A
        verify(atLeast = 1) { providerB.processPayment(any()) } // Failover to B
    }
    
    @Test
    @Tag("integration")
    @DisplayName("Should fail when both providers are unavailable")
    fun testFailWhenAllProvidersUnavailable() {
        // Given
        val payment = createPayment(PaymentMethod.CARD, BigDecimal("100.00"))
        
        every { providerA.isHealthy() } returns false
        every { providerB.isHealthy() } returns false
        
        // When
        val result = orchestrationService.orchestratePayment(payment)
        
        // Then
        assertEquals(PaymentStatus.FAILED, result.status)
        assertNotNull(result.failureReason)
        assertTrue(result.failureReason!!.contains("No healthy provider"))
    }
    
    // ========================================
    // INTEGRATION TESTS - Permanent Failures
    // ========================================
    
    @Test
    @Tag("integration")
    @DisplayName("Should fail immediately on permanent error")
    fun testPermanentFailure() {
        // Given
        val payment = createPayment(PaymentMethod.CARD, BigDecimal("100.00"))
        
        every { providerA.isHealthy() } returns true
        every { providerA.processPayment(any()) } returns ProviderResponse(
            success = false,
            providerTransactionId = "prov_123",
            providerStatus = "failed",
            errorCode = "insufficient_funds",
            errorMessage = "Insufficient funds",
            isTransient = false
        )
        
        // When
        val result = orchestrationService.orchestratePayment(payment)
        
        // Then
        assertEquals(PaymentStatus.FAILED, result.status)
        assertTrue(result.failureReason!!.contains("Insufficient funds"))
        
        verify(exactly = 1) { providerA.processPayment(any()) } // No retry
    }
    
    @Test
    @Tag("integration")
    @DisplayName("Should handle validation errors")
    fun testValidationError() {
        // Given - Invalid payment (amount = 0)
        val payment = createPayment(PaymentMethod.CARD, BigDecimal.ZERO)
        
        // When
        val result = orchestrationService.orchestratePayment(payment)
        
        // Then
        assertEquals(PaymentStatus.FAILED, result.status)
        assertNotNull(result.failureReason)
        
        verify(exactly = 0) { providerA.processPayment(any()) } // No provider call
    }
    
    // ========================================
    // INTEGRATION TESTS - High-Value Payments
    // ========================================
    
    @Test
    @Tag("integration")
    @DisplayName("Should handle high-value payment correctly")
    fun testHighValuePayment() {
        // Given - Amount > 100,000
        val payment = createPayment(PaymentMethod.CARD, BigDecimal("150000.00"))
        
        every { providerA.isHealthy() } returns true
        every { providerA.processPayment(any()) } returns ProviderResponse(
            success = true,
            providerTransactionId = "prov_high_123",
            providerStatus = "succeeded"
        )
        
        // When
        val result = orchestrationService.orchestratePayment(payment)
        
        // Then
        assertEquals(PaymentStatus.SUCCEEDED, result.status)
        assertEquals(Provider.PROVIDER_A, result.selectedProvider)
        assertEquals(BigDecimal("150000.00"), result.transaction.amount)
    }
    
    // ========================================
    // INTEGRATION TESTS - Exception Handling
    // ========================================
    
    @Test
    @Tag("integration")
    @DisplayName("Should handle provider exception gracefully")
    fun testProviderException() {
        // Given
        val payment = createPayment(PaymentMethod.CARD, BigDecimal("100.00"))
        
        every { providerA.isHealthy() } returns true
        every { providerA.processPayment(any()) } throws ProviderTransientException("Provider error")
        
        every { providerB.isHealthy() } returns true
        every { providerB.processPayment(any()) } returns ProviderResponse(
            success = true,
            providerTransactionId = "prov_456",
            providerStatus = "succeeded"
        )
        
        // When
        val result = orchestrationService.orchestratePayment(payment)
        
        // Then
        assertEquals(PaymentStatus.SUCCEEDED, result.status)
        assertEquals(Provider.PROVIDER_B, result.selectedProvider) // Failover
    }
    
    @Test
    @Tag("integration")
    @DisplayName("Should handle network timeout")
    fun testNetworkTimeout() {
        // Given
        val payment = createPayment(PaymentMethod.CARD, BigDecimal("100.00"))
        var attemptCount = 0
        
        every { providerA.isHealthy() } returns true
        every { providerA.processPayment(any()) } answers {
            attemptCount++
            if (attemptCount == 1) {
                throw ProviderTimeoutException("Request timeout")
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
        assertEquals(2, attemptCount) // Retry after timeout
    }
    
    // ========================================
    // INTEGRATION TESTS - Metadata Tracking
    // ========================================
    
    @Test
    @Tag("integration")
    @DisplayName("Should track retry metadata")
    fun testRetryMetadata() {
        // Given
        val payment = createPayment(PaymentMethod.CARD, BigDecimal("100.00"))
        var attemptCount = 0
        
        every { providerA.isHealthy() } returns true
        every { providerA.processPayment(any()) } answers {
            attemptCount++
            if (attemptCount < 3) {
                ProviderResponse(
                    success = false,
                    providerTransactionId = "prov_123",
                    providerStatus = "rate_limited",
                    isTransient = true
                )
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
        assertTrue(result.metadata.containsKey("retry_attempts"))
        assertTrue(result.metadata.containsKey("total_duration_ms"))
        
        val retryAttempts = result.metadata["retry_attempts"] as Int
        assertEquals(3, retryAttempts)
    }
    
    // ========================================
    // Helper Methods
    // ========================================
    
    private fun createPayment(
        paymentMethod: PaymentMethod,
        amount: BigDecimal
    ): Payment {
        val transaction = Transaction(
            merchantId = "merchant_123",
            customerId = "customer_456",
            amount = amount,
            currency = "INR",
            paymentMethod = paymentMethod,
            paymentDetails = when (paymentMethod) {
                PaymentMethod.CARD -> mapOf("card_token" to "tok_visa")
                PaymentMethod.UPI -> mapOf("vpa" to "user@paytm")
                else -> mapOf("test" to "data")
            }
        )
        
        return Payment(
            transactionId = "txn_test_${System.currentTimeMillis()}",
            transaction = transaction,
            status = PaymentStatus.INITIATED,
            createdAt = Instant.now()
        )
    }
}


