package com.payment.orchestration.routing

import com.payment.orchestration.domain.model.*
import com.payment.orchestration.provider.PaymentProvider
import com.payment.orchestration.provider.ProviderResponse
import io.mockk.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.math.BigDecimal
import java.time.Instant

/**
 * Unit Tests for RoutingEngine
 * 
 * Test Classification:
 * - @Tag("sanity"): Basic functionality tests
 * - @Tag("regression"): Edge cases and error scenarios
 * 
 * Test Framework: JUnit 5 + MockK
 * 
 * Coverage:
 * - Provider selection by payment method
 * - Fallback provider selection
 * - Health check integration
 * - Amount-based routing
 * - No available provider scenarios
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("RoutingEngine Unit Tests")
class RoutingEngineTest {
    
    private lateinit var routingEngine: RoutingEngine
    private lateinit var providerA: PaymentProvider
    private lateinit var providerB: PaymentProvider
    private lateinit var providers: Map<Provider, PaymentProvider>
    
    @BeforeEach
    fun setup() {
        // Create mock providers
        providerA = mockk<PaymentProvider>()
        providerB = mockk<PaymentProvider>()
        
        // Configure provider IDs
        every { providerA.getProviderId() } returns Provider.PROVIDER_A
        every { providerB.getProviderId() } returns Provider.PROVIDER_B
        
        // Create provider map
        providers = mapOf(
            Provider.PROVIDER_A to providerA,
            Provider.PROVIDER_B to providerB
        )
        
        // Create routing engine
        routingEngine = RoutingEngine(providers)
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
    @DisplayName("Should route CARD payment to ProviderA")
    fun testRouteCardToProviderA() {
        // Given
        val payment = createPayment(PaymentMethod.CARD, BigDecimal("100.00"))
        every { providerA.isHealthy() } returns true
        
        // When
        val selectedProvider = routingEngine.selectProvider(payment)
        
        // Then
        assertEquals(Provider.PROVIDER_A, selectedProvider.getProviderId())
        verify(exactly = 1) { providerA.isHealthy() }
    }
    
    @Test
    @Tag("sanity")
    @DisplayName("Should route UPI payment to ProviderB")
    fun testRouteUpiToProviderB() {
        // Given
        val payment = createPayment(PaymentMethod.UPI, BigDecimal("500.00"))
        every { providerB.isHealthy() } returns true
        
        // When
        val selectedProvider = routingEngine.selectProvider(payment)
        
        // Then
        assertEquals(Provider.PROVIDER_B, selectedProvider.getProviderId())
        verify(exactly = 1) { providerB.isHealthy() }
    }
    
    @Test
    @Tag("sanity")
    @DisplayName("Should route WALLET payment to ProviderA")
    fun testRouteWalletToProviderA() {
        // Given
        val payment = createPayment(PaymentMethod.WALLET, BigDecimal("250.00"))
        every { providerA.isHealthy() } returns true
        
        // When
        val selectedProvider = routingEngine.selectProvider(payment)
        
        // Then
        assertEquals(Provider.PROVIDER_A, selectedProvider.getProviderId())
    }
    
    @Test
    @Tag("sanity")
    @DisplayName("Should route NET_BANKING payment to ProviderB")
    fun testRouteNetBankingToProviderB() {
        // Given
        val payment = createPayment(PaymentMethod.NET_BANKING, BigDecimal("1000.00"))
        every { providerB.isHealthy() } returns true
        
        // When
        val selectedProvider = routingEngine.selectProvider(payment)
        
        // Then
        assertEquals(Provider.PROVIDER_B, selectedProvider.getProviderId())
    }
    
    // ========================================
    // REGRESSION TESTS - Fallback Scenarios
    // ========================================
    
    @Test
    @Tag("regression")
    @DisplayName("Should fallback to ProviderB when ProviderA is unhealthy for CARD")
    fun testFallbackWhenPrimaryUnhealthy() {
        // Given
        val payment = createPayment(PaymentMethod.CARD, BigDecimal("100.00"))
        every { providerA.isHealthy() } returns false  // Primary unhealthy
        every { providerB.isHealthy() } returns true   // Fallback healthy
        
        // When
        val selectedProvider = routingEngine.selectProvider(payment)
        
        // Then
        assertEquals(Provider.PROVIDER_B, selectedProvider.getProviderId())
        verify(exactly = 1) { providerA.isHealthy() }
        verify(exactly = 1) { providerB.isHealthy() }
    }
    
    @Test
    @Tag("regression")
    @DisplayName("Should fallback to ProviderA when ProviderB is unhealthy for UPI")
    fun testFallbackForUpiPayment() {
        // Given
        val payment = createPayment(PaymentMethod.UPI, BigDecimal("500.00"))
        every { providerB.isHealthy() } returns false  // Primary unhealthy
        every { providerA.isHealthy() } returns true   // Fallback healthy
        
        // When
        val selectedProvider = routingEngine.selectProvider(payment)
        
        // Then
        assertEquals(Provider.PROVIDER_A, selectedProvider.getProviderId())
    }
    
    @Test
    @Tag("regression")
    @DisplayName("Should throw exception when all providers are unhealthy")
    fun testNoAvailableProvider() {
        // Given
        val payment = createPayment(PaymentMethod.CARD, BigDecimal("100.00"))
        every { providerA.isHealthy() } returns false
        every { providerB.isHealthy() } returns false
        
        // When & Then
        val exception = assertThrows<NoAvailableProviderException> {
            routingEngine.selectProvider(payment)
        }
        
        assertTrue(exception.message!!.contains("No healthy provider available"))
        verify(exactly = 1) { providerA.isHealthy() }
        verify(exactly = 1) { providerB.isHealthy() }
    }
    
    @Test
    @Tag("regression")
    @DisplayName("Should handle health check exceptions gracefully")
    fun testHealthCheckException() {
        // Given
        val payment = createPayment(PaymentMethod.CARD, BigDecimal("100.00"))
        every { providerA.isHealthy() } throws RuntimeException("Health check failed")
        every { providerB.isHealthy() } returns true
        
        // When
        val selectedProvider = routingEngine.selectProvider(payment)
        
        // Then
        assertEquals(Provider.PROVIDER_B, selectedProvider.getProviderId())
    }
    
    // ========================================
    // REGRESSION TESTS - Amount-Based Routing
    // ========================================
    
    @Test
    @Tag("regression")
    @DisplayName("Should route high-value transaction to primary provider")
    fun testHighValueTransaction() {
        // Given - Amount > 100,000
        val payment = createPayment(PaymentMethod.CARD, BigDecimal("150000.00"))
        every { providerA.isHealthy() } returns true
        
        // When
        val selectedProvider = routingEngine.selectProvider(payment)
        
        // Then
        assertEquals(Provider.PROVIDER_A, selectedProvider.getProviderId())
    }
    
    @Test
    @Tag("regression")
    @DisplayName("Should route low-value transaction to primary provider")
    fun testLowValueTransaction() {
        // Given - Amount < 100
        val payment = createPayment(PaymentMethod.CARD, BigDecimal("50.00"))
        every { providerA.isHealthy() } returns true
        
        // When
        val selectedProvider = routingEngine.selectProvider(payment)
        
        // Then
        assertEquals(Provider.PROVIDER_A, selectedProvider.getProviderId())
    }
    
    // ========================================
    // REGRESSION TESTS - Available Providers
    // ========================================
    
    @Test
    @Tag("regression")
    @DisplayName("Should return all healthy providers for payment method")
    fun testGetAvailableProviders() {
        // Given
        every { providerA.isHealthy() } returns true
        every { providerB.isHealthy() } returns true
        
        // When
        val availableProviders = routingEngine.getAvailableProviders(PaymentMethod.CARD)
        
        // Then
        assertEquals(2, availableProviders.size)
        assertTrue(availableProviders.any { it.getProviderId() == Provider.PROVIDER_A })
        assertTrue(availableProviders.any { it.getProviderId() == Provider.PROVIDER_B })
    }
    
    @Test
    @Tag("regression")
    @DisplayName("Should return only healthy providers")
    fun testGetAvailableProvidersWithUnhealthy() {
        // Given
        every { providerA.isHealthy() } returns true
        every { providerB.isHealthy() } returns false
        
        // When
        val availableProviders = routingEngine.getAvailableProviders(PaymentMethod.CARD)
        
        // Then
        assertEquals(1, availableProviders.size)
        assertEquals(Provider.PROVIDER_A, availableProviders[0].getProviderId())
    }
    
    @Test
    @Tag("regression")
    @DisplayName("Should return empty list when no providers are healthy")
    fun testGetAvailableProvidersAllUnhealthy() {
        // Given
        every { providerA.isHealthy() } returns false
        every { providerB.isHealthy() } returns false
        
        // When
        val availableProviders = routingEngine.getAvailableProviders(PaymentMethod.CARD)
        
        // Then
        assertTrue(availableProviders.isEmpty())
    }
    
    // ========================================
    // REGRESSION TESTS - Routing Statistics
    // ========================================
    
    @Test
    @Tag("regression")
    @DisplayName("Should return routing statistics for all providers")
    fun testGetRoutingStats() {
        // Given
        every { providerA.isHealthy() } returns true
        every { providerB.isHealthy() } returns false
        
        // When
        val stats = routingEngine.getRoutingStats()
        
        // Then
        assertEquals(2, stats.size)
        assertTrue(stats.containsKey(Provider.PROVIDER_A))
        assertTrue(stats.containsKey(Provider.PROVIDER_B))
        assertTrue(stats[Provider.PROVIDER_A]!!.isHealthy)
        assertFalse(stats[Provider.PROVIDER_B]!!.isHealthy)
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
            paymentDetails = mapOf("test" to "data")
        )
        
        return Payment(
            transactionId = "txn_test_${System.currentTimeMillis()}",
            transaction = transaction,
            status = PaymentStatus.INITIATED,
            createdAt = Instant.now()
        )
    }
}


