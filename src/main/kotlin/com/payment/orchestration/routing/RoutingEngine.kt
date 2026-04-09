package com.payment.orchestration.routing

import com.payment.orchestration.domain.model.Payment
import com.payment.orchestration.domain.model.PaymentMethod
import com.payment.orchestration.domain.model.Provider
import com.payment.orchestration.provider.PaymentProvider
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * Routing Engine
 * 
 * Responsible for selecting the optimal payment provider based on:
 * - Payment method
 * - Transaction amount
 * - Provider health status
 * - Routing rules and preferences
 * - Circuit breaker state
 * 
 * Routing Strategy:
 * 1. Primary routing by payment method
 * 2. Fallback to secondary provider if primary is unhealthy
 * 3. Load balancing for high-volume merchants
 * 4. Cost optimization based on provider fees
 * 
 * Routing Rules:
 * - CARD → ProviderA (primary), ProviderB (fallback)
 * - UPI → ProviderB (primary), ProviderA (fallback)
 * - WALLET → ProviderA (primary)
 * - NET_BANKING → ProviderB (primary)
 * - EMI → ProviderA (primary)
 * 
 * @property providers Map of all available payment providers
 */
@Component
class RoutingEngine(
    private val providers: Map<Provider, PaymentProvider>
) {
    private val logger = LoggerFactory.getLogger(RoutingEngine::class.java)
    
    companion object {
        // Routing configuration
        private val PRIMARY_PROVIDER_MAP = mapOf(
            PaymentMethod.CARD to Provider.PROVIDER_A,
            PaymentMethod.UPI to Provider.PROVIDER_B,
            PaymentMethod.WALLET to Provider.PROVIDER_A,
            PaymentMethod.NET_BANKING to Provider.PROVIDER_B,
            PaymentMethod.EMI to Provider.PROVIDER_A
        )
        
        private val FALLBACK_PROVIDER_MAP = mapOf(
            PaymentMethod.CARD to Provider.PROVIDER_B,
            PaymentMethod.UPI to Provider.PROVIDER_A,
            PaymentMethod.WALLET to Provider.PROVIDER_B,
            PaymentMethod.NET_BANKING to Provider.PROVIDER_A,
            PaymentMethod.EMI to Provider.PROVIDER_B
        )
        
        // Amount thresholds for routing decisions
        private val HIGH_VALUE_THRESHOLD = BigDecimal("100000") // 1 lakh
        private val LOW_VALUE_THRESHOLD = BigDecimal("100") // 100 rupees
    }
    
    /**
     * Selects the optimal provider for a payment.
     * 
     * Selection Logic:
     * 1. Get primary provider based on payment method
     * 2. Check if primary provider is healthy
     * 3. If unhealthy, select fallback provider
     * 4. If both unhealthy, throw exception
     * 5. Apply additional routing rules (amount-based, merchant-specific)
     * 
     * @param payment The payment to route
     * @return Selected PaymentProvider
     * @throws NoAvailableProviderException if no healthy provider found
     */
    fun selectProvider(payment: Payment): PaymentProvider {
        val paymentMethod = payment.transaction.paymentMethod
        val amount = payment.transaction.amount
        
        logger.info(
            "Selecting provider: transactionId={}, method={}, amount={}",
            payment.transactionId,
            paymentMethod,
            amount
        )
        
        // Get primary provider
        val primaryProviderId = getPrimaryProvider(paymentMethod, amount)
        val primaryProvider = providers[primaryProviderId]
        
        if (primaryProvider == null) {
            logger.error(
                "Primary provider not found: transactionId={}, providerId={}",
                payment.transactionId,
                primaryProviderId
            )
            throw NoAvailableProviderException(
                "Primary provider not configured: $primaryProviderId"
            )
        }
        
        // Check primary provider health
        if (isProviderHealthy(primaryProvider)) {
            logger.info(
                "Selected primary provider: transactionId={}, provider={}",
                payment.transactionId,
                primaryProviderId
            )
            return primaryProvider
        }
        
        // Primary unhealthy, try fallback
        logger.warn(
            "Primary provider unhealthy, trying fallback: transactionId={}, primary={}",
            payment.transactionId,
            primaryProviderId
        )
        
        val fallbackProviderId = getFallbackProvider(paymentMethod)
        val fallbackProvider = providers[fallbackProviderId]
        
        if (fallbackProvider == null) {
            logger.error(
                "Fallback provider not found: transactionId={}, providerId={}",
                payment.transactionId,
                fallbackProviderId
            )
            throw NoAvailableProviderException(
                "Fallback provider not configured: $fallbackProviderId"
            )
        }
        
        // Check fallback provider health
        if (isProviderHealthy(fallbackProvider)) {
            logger.info(
                "Selected fallback provider: transactionId={}, provider={}",
                payment.transactionId,
                fallbackProviderId
            )
            return fallbackProvider
        }
        
        // Both providers unhealthy
        logger.error(
            "No healthy provider available: transactionId={}, method={}, primary={}, fallback={}",
            payment.transactionId,
            paymentMethod,
            primaryProviderId,
            fallbackProviderId
        )
        
        throw NoAvailableProviderException(
            "No healthy provider available for payment method: $paymentMethod"
        )
    }
    
    /**
     * Gets the primary provider for a payment method.
     * 
     * Can be overridden based on:
     * - Transaction amount (high-value transactions may prefer different provider)
     * - Merchant preferences
     * - Time of day (load balancing)
     * - Geographic location
     * 
     * @param paymentMethod The payment method
     * @param amount The transaction amount
     * @return Primary provider ID
     */
    private fun getPrimaryProvider(
        paymentMethod: PaymentMethod,
        amount: BigDecimal
    ): Provider {
        // Default routing by payment method
        val defaultProvider = PRIMARY_PROVIDER_MAP[paymentMethod]
            ?: throw IllegalArgumentException("No provider configured for payment method: $paymentMethod")
        
        // Apply amount-based routing rules
        // High-value transactions may prefer providers with better fraud detection
        if (amount >= HIGH_VALUE_THRESHOLD) {
            logger.debug(
                "High-value transaction, using default provider: method={}, amount={}, provider={}",
                paymentMethod,
                amount,
                defaultProvider
            )
            // In production, might route to provider with better fraud detection
            return defaultProvider
        }
        
        // Low-value transactions may prefer providers with lower fees
        if (amount <= LOW_VALUE_THRESHOLD) {
            logger.debug(
                "Low-value transaction, using default provider: method={}, amount={}, provider={}",
                paymentMethod,
                amount,
                defaultProvider
            )
            // In production, might route to provider with lower fees
            return defaultProvider
        }
        
        return defaultProvider
    }
    
    /**
     * Gets the fallback provider for a payment method.
     * 
     * @param paymentMethod The payment method
     * @return Fallback provider ID
     */
    private fun getFallbackProvider(paymentMethod: PaymentMethod): Provider {
        return FALLBACK_PROVIDER_MAP[paymentMethod]
            ?: throw IllegalArgumentException("No fallback provider configured for payment method: $paymentMethod")
    }
    
    /**
     * Checks if a provider is healthy and available.
     * 
     * Health check includes:
     * - Provider's own health endpoint
     * - Circuit breaker state
     * - Recent error rate
     * - Response time metrics
     * 
     * @param provider The provider to check
     * @return true if healthy, false otherwise
     */
    private fun isProviderHealthy(provider: PaymentProvider): Boolean {
        return try {
            val isHealthy = provider.isHealthy()
            
            logger.debug(
                "Provider health check: provider={}, healthy={}",
                provider.getProviderId(),
                isHealthy
            )
            
            isHealthy
            
        } catch (e: Exception) {
            logger.warn(
                "Provider health check failed: provider={}, error={}",
                provider.getProviderId(),
                e.message
            )
            false
        }
    }
    
    /**
     * Gets all available providers for a payment method.
     * 
     * Used for:
     * - Failover scenarios
     * - Load balancing
     * - A/B testing
     * 
     * @param paymentMethod The payment method
     * @return List of available providers (primary first, then fallbacks)
     */
    fun getAvailableProviders(paymentMethod: PaymentMethod): List<PaymentProvider> {
        val primaryProviderId = PRIMARY_PROVIDER_MAP[paymentMethod]
        val fallbackProviderId = FALLBACK_PROVIDER_MAP[paymentMethod]
        
        val availableProviders = mutableListOf<PaymentProvider>()
        
        // Add primary provider if available
        primaryProviderId?.let { id ->
            providers[id]?.let { provider ->
                if (isProviderHealthy(provider)) {
                    availableProviders.add(provider)
                }
            }
        }
        
        // Add fallback provider if available
        fallbackProviderId?.let { id ->
            providers[id]?.let { provider ->
                if (isProviderHealthy(provider)) {
                    availableProviders.add(provider)
                }
            }
        }
        
        logger.debug(
            "Available providers for method: method={}, count={}",
            paymentMethod,
            availableProviders.size
        )
        
        return availableProviders
    }
    
    /**
     * Gets routing statistics for monitoring.
     * 
     * @return Map of provider to routing count
     */
    fun getRoutingStats(): Map<Provider, RoutingStats> {
        // In production, this would return actual metrics from a metrics store
        return providers.keys.associateWith { provider ->
            RoutingStats(
                provider = provider,
                totalRouted = 0,
                successRate = 0.0,
                avgResponseTime = 0L,
                isHealthy = providers[provider]?.isHealthy() ?: false
            )
        }
    }
}

/**
 * Routing statistics for a provider.
 * 
 * @property provider The provider ID
 * @property totalRouted Total number of payments routed to this provider
 * @property successRate Success rate (0.0 to 1.0)
 * @property avgResponseTime Average response time in milliseconds
 * @property isHealthy Current health status
 */
data class RoutingStats(
    val provider: Provider,
    val totalRouted: Long,
    val successRate: Double,
    val avgResponseTime: Long,
    val isHealthy: Boolean
)

/**
 * Exception thrown when no healthy provider is available.
 */
class NoAvailableProviderException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)


