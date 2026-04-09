package com.payment.orchestration.routing

import com.payment.orchestration.circuitbreaker.CircuitBreakerService
import com.payment.orchestration.domain.model.Payment
import com.payment.orchestration.domain.model.PaymentMethod
import com.payment.orchestration.domain.model.Transaction
import com.payment.orchestration.provider.ProviderA
import com.payment.orchestration.provider.ProviderB
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

/**
 * Unit tests for RoutingEngine
 * 
 * Test Coverage:
 * - Provider selection based on payment method
 * - Circuit breaker integration
 * - Fallback when primary provider is unhealthy
 * - Exception when all providers are unhealthy
 * - Provider health status checking
 */
class RoutingEngineTest {
    
    private lateinit var providerA: ProviderA
    private lateinit var providerB: ProviderB
    private lateinit var circuitBreakerService: CircuitBreakerService
    private lateinit var routingEngine: RoutingEngine
    
    @BeforeEach
    fun setup() {
        providerA = mockk(relaxed = true)
        providerB = mockk(relaxed = true)
        circuitBreakerService = mockk(relaxed = true)
        
        every { providerA.getProviderId() } returns com.payment.orchestration.domain.model.Provider.PROVIDER_A
        every { providerB.getProviderId() } returns com.payment.orchestration.domain.model.Provider.PROVIDER_B
        
        routingEngine = RoutingEngine(
            providerA = providerA,
            providerB = providerB,
            circuitBreakerService = circuitBreakerService
        )
    }
    
    @Test
    fun `should select ProviderA for CARD payments when healthy`() {
        // Given
        val payment = createPayment(PaymentMethod.CARD)
        every { circuitBreakerService.isHealthy(com.payment.orchestration.domain.model.Provider.PROVIDER_A) } returns true
        
        // When
        val selectedProvider = routingEngine.selectProvider(payment)
        
        // Then
        assertEquals(com.payment.orchestration.domain.model.Provider.PROVIDER_A, selectedProvider.getProviderId())
    }
    
    @Test
    fun `should select ProviderB for UPI payments when healthy`() {
        // Given
        val payment = createPayment(PaymentMethod.UPI)
        every { circuitBreakerService.isHealthy(com.payment.orchestration.domain.model.Provider.PROVIDER_B) } returns true
        
        // When
        val selectedProvider = routingEngine.selectProvider(payment)
        
        // Then
        assertEquals(com.payment.orchestration.domain.model.Provider.PROVIDER_B, selectedProvider.getProviderId())
    }
    
    @Test
    fun `should fallback to ProviderB when ProviderA is unhealthy for CARD payments`() {
        // Given
        val payment = createPayment(PaymentMethod.CARD)
        every { circuitBreakerService.isHealthy(com.payment.orchestration.domain.model.Provider.PROVIDER_A) } returns false
        every { circuitBreakerService.isHealthy(com.payment.orchestration.domain.model.Provider.PROVIDER_B) } returns true
        
        // When
        val selectedProvider = routingEngine.selectProvider(payment)
        
        // Then
        assertEquals(com.payment.orchestration.domain.model.Provider.PROVIDER_B, selectedProvider.getProviderId())
    }
    
    @Test
    fun `should fallback to ProviderA when ProviderB is unhealthy for UPI payments`() {
        // Given
        val payment = createPayment(PaymentMethod.UPI)
        every { circuitBreakerService.isHealthy(com.payment.orchestration.domain.model.Provider.PROVIDER_B) } returns false
        every { circuitBreakerService.isHealthy(com.payment.orchestration.domain.model.Provider.PROVIDER_A) } returns true
        
        // When
        val selectedProvider = routingEngine.selectProvider(payment)
        
        // Then
        assertEquals(com.payment.orchestration.domain.model.Provider.PROVIDER_A, selectedProvider.getProviderId())
    }
    
    @Test
    fun `should throw exception when all providers are unhealthy`() {
        // Given
        val payment = createPayment(PaymentMethod.CARD)
        every { circuitBreakerService.isHealthy(com.payment.orchestration.domain.model.Provider.PROVIDER_A) } returns false
        every { circuitBreakerService.isHealthy(com.payment.orchestration.domain.model.Provider.PROVIDER_B) } returns false
        
        // When & Then
        val exception = assertThrows<NoHealthyProviderException> {
            routingEngine.selectProvider(payment)
        }
        
        assertTrue(exception.message!!.contains("No healthy provider available"))
    }
    
    @Test
    fun `should select ProviderA for NET_BANKING when healthy`() {
        // Given
        val payment = createPayment(PaymentMethod.NET_BANKING)
        every { circuitBreakerService.isHealthy(com.payment.orchestration.domain.model.Provider.PROVIDER_A) } returns true
        
        // When
        val selectedProvider = routingEngine.selectProvider(payment)
        
        // Then
        assertEquals(com.payment.orchestration.domain.model.Provider.PROVIDER_A, selectedProvider.getProviderId())
    }
    
    private fun createPayment(paymentMethod: PaymentMethod): Payment {
        val transaction = Transaction(
            amount = BigDecimal("100.00"),
            currency = "INR",
            paymentMethod = paymentMethod,
            customerId = "cust_123",
            merchantId = "merch_456",
            description = "Test payment",
            paymentDetails = mapOf("test" to "data")
        )
        
        return Payment.create(
            customerId = "cust_123",
            merchantId = "merch_456",
            transaction = transaction
        )
    }
}

