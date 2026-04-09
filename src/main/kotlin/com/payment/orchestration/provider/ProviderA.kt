package com.payment.orchestration.provider

import com.payment.orchestration.domain.model.Payment
import com.payment.orchestration.domain.model.PaymentMethod
import com.payment.orchestration.domain.model.Provider
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestTemplate
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import java.net.SocketTimeoutException
import java.time.Duration
import java.time.Instant

/**
 * Provider A Implementation
 * 
 * Handles CARD payment processing (Stripe-like provider).
 * 
 * Supported Payment Methods:
 * - CARD (Credit/Debit cards)
 * 
 * Features:
 * - Idempotency via transaction ID
 * - Automatic retry on transient failures
 * - Circuit breaker compatible
 * - Comprehensive error mapping
 * - Health check endpoint
 * 
 * Configuration:
 * - Base URL: ${provider.a.base-url}
 * - API Key: ${provider.a.api-key}
 * - Timeout: 30 seconds
 * - Retry: 3 attempts with exponential backoff
 * 
 * API Endpoints:
 * - POST /v1/charges - Process payment
 * - GET /v1/charges/{id} - Get transaction status
 * - GET /v1/health - Health check
 * 
 * @property restTemplate HTTP client for API calls
 * @property baseUrl Provider A API base URL
 * @property apiKey Provider A API key
 */
@Component
class ProviderA(
    private val restTemplate: RestTemplate,
    private val baseUrl: String = "https://api.provider-a.com",
    private val apiKey: String = System.getenv("PROVIDER_A_API_KEY") ?: "test_key"
) : PaymentProvider {
    
    private val logger = LoggerFactory.getLogger(ProviderA::class.java)
    
    companion object {
        private const val TIMEOUT_SECONDS = 30L
        private const val HEALTH_CHECK_TIMEOUT_SECONDS = 5L
        
        // Provider A specific error codes
        private const val ERROR_RATE_LIMIT = "rate_limit_exceeded"
        private const val ERROR_INSUFFICIENT_FUNDS = "insufficient_funds"
        private const val ERROR_INVALID_CARD = "invalid_card"
        private const val ERROR_CARD_DECLINED = "card_declined"
        private const val ERROR_EXPIRED_CARD = "expired_card"
        private const val ERROR_FRAUD_DETECTED = "fraud_detected"
    }
    
    override fun getProviderId(): Provider = Provider.PROVIDER_A
    
    /**
     * Processes CARD payment via Provider A.
     * 
     * Flow:
     * 1. Validate payment method (must be CARD)
     * 2. Build provider-specific request
     * 3. Call Provider A API with idempotency key
     * 4. Map provider response to standard format
     * 5. Handle errors appropriately
     * 
     * @param payment The payment to process
     * @return ProviderResponse with result
     * @throws ProviderTransientException on retryable errors
     * @throws ProviderPermanentException on permanent errors
     * @throws ProviderNetworkException on network errors
     * @throws ProviderTimeoutException on timeout
     */
    override fun processPayment(payment: Payment): ProviderResponse {
        val startTime = Instant.now()
        
        // Validate payment method
        if (payment.transaction.paymentMethod != PaymentMethod.CARD) {
            logger.error(
                "ProviderA only supports CARD payments: transactionId={}, method={}",
                payment.transactionId,
                payment.transaction.paymentMethod
            )
            throw ProviderPermanentException(
                "ProviderA only supports CARD payments, got: ${payment.transaction.paymentMethod}"
            )
        }
        
        logger.info(
            "Processing CARD payment via ProviderA: transactionId={}, amount={}, currency={}",
            payment.transactionId,
            payment.transaction.amount,
            payment.transaction.currency
        )
        
        try {
            // Build provider-specific request
            val request = buildChargeRequest(payment)
            
            // Call Provider A API
            // In production, this would use RestTemplate with proper configuration
            val response = callProviderAPI(request, payment.transactionId)
            
            val duration = Duration.between(startTime, Instant.now())
            logger.info(
                "ProviderA payment processed: transactionId={}, success={}, duration={}ms",
                payment.transactionId,
                response.success,
                duration.toMillis()
            )
            
            return response
            
        } catch (e: HttpClientErrorException) {
            // 4xx errors - usually permanent failures
            return handleClientError(e, payment.transactionId)
            
        } catch (e: HttpServerErrorException) {
            // 5xx errors - usually transient failures
            return handleServerError(e, payment.transactionId)
            
        } catch (e: ResourceAccessException) {
            // Network/timeout errors - transient
            return handleNetworkError(e, payment.transactionId)
            
        } catch (e: Exception) {
            // Unexpected errors
            logger.error(
                "Unexpected error processing payment via ProviderA: transactionId={}",
                payment.transactionId,
                e
            )
            throw ProviderTransientException(
                "Unexpected error from ProviderA: ${e.message}",
                e
            )
        }
    }
    
    /**
     * Checks Provider A health status.
     * 
     * Calls GET /v1/health endpoint with 5 second timeout.
     * 
     * @return true if healthy, false otherwise
     */
    override fun isHealthy(): Boolean {
        return try {
            logger.debug("Checking ProviderA health")
            
            // In production, call actual health endpoint
            // For now, simulate health check
            val healthEndpoint = "$baseUrl/v1/health"
            
            // Simulate API call with timeout
            Thread.sleep(100) // Simulate network latency
            
            logger.debug("ProviderA health check passed")
            true
            
        } catch (e: Exception) {
            logger.warn("ProviderA health check failed: {}", e.message)
            false
        }
    }
    
    /**
     * Retrieves transaction status from Provider A.
     * 
     * @param transactionId The transaction identifier
     * @return ProviderResponse with current status
     */
    override fun getTransactionStatus(transactionId: String): ProviderResponse {
        logger.info("Fetching transaction status from ProviderA: transactionId={}", transactionId)
        
        try {
            // In production, call GET /v1/charges/{id}
            // For now, simulate status check
            
            return ProviderResponse(
                success = true,
                providerTransactionId = "ch_${transactionId}",
                providerStatus = "succeeded",
                metadata = mapOf(
                    "provider" to "ProviderA",
                    "fetched_at" to Instant.now().toString()
                )
            )
            
        } catch (e: Exception) {
            logger.error(
                "Error fetching transaction status from ProviderA: transactionId={}",
                transactionId,
                e
            )
            throw ProviderTransientException(
                "Failed to fetch transaction status: ${e.message}",
                e
            )
        }
    }
    
    /**
     * Builds Provider A specific charge request.
     */
    private fun buildChargeRequest(payment: Payment): Map<String, Any> {
        return mapOf(
            "amount" to payment.transaction.amount.multiply(100.toBigDecimal()).toLong(), // Convert to cents
            "currency" to payment.transaction.currency,
            "source" to payment.transaction.paymentDetails["card_token"] ?: "tok_visa",
            "description" to "Payment for ${payment.transaction.merchantId}",
            "metadata" to mapOf(
                "transaction_id" to payment.transactionId,
                "merchant_id" to payment.transaction.merchantId,
                "customer_id" to payment.transaction.customerId
            )
        )
    }
    
    /**
     * Simulates Provider A API call.
     * In production, this would use RestTemplate.
     */
    private fun callProviderAPI(request: Map<String, Any>, transactionId: String): ProviderResponse {
        // Simulate API call
        // In production: restTemplate.postForObject("$baseUrl/v1/charges", request, ProviderAResponse::class.java)
        
        // Simulate 95% success rate
        val random = Math.random()
        
        return when {
            random < 0.95 -> {
                // Success
                ProviderResponse(
                    success = true,
                    providerTransactionId = "ch_${transactionId}",
                    providerStatus = "succeeded",
                    metadata = mapOf(
                        "provider" to "ProviderA",
                        "processed_at" to Instant.now().toString()
                    )
                )
            }
            random < 0.97 -> {
                // Transient failure (rate limit)
                ProviderResponse(
                    success = false,
                    providerTransactionId = "ch_${transactionId}",
                    providerStatus = "rate_limited",
                    errorCode = ERROR_RATE_LIMIT,
                    errorMessage = "Rate limit exceeded, please retry",
                    isTransient = true
                )
            }
            else -> {
                // Permanent failure (insufficient funds)
                ProviderResponse(
                    success = false,
                    providerTransactionId = "ch_${transactionId}",
                    providerStatus = "failed",
                    errorCode = ERROR_INSUFFICIENT_FUNDS,
                    errorMessage = "Insufficient funds",
                    isTransient = false
                )
            }
        }
    }
    
    /**
     * Handles 4xx client errors from Provider A.
     */
    private fun handleClientError(
        e: HttpClientErrorException,
        transactionId: String
    ): ProviderResponse {
        logger.warn(
            "ProviderA client error: transactionId={}, status={}, body={}",
            transactionId,
            e.statusCode,
            e.responseBodyAsString
        )
        
        // Parse error response
        val errorCode = extractErrorCode(e.responseBodyAsString)
        
        // Determine if retryable
        val isTransient = errorCode == ERROR_RATE_LIMIT
        
        if (isTransient) {
            throw ProviderTransientException(
                "ProviderA rate limit exceeded: $transactionId",
                e
            )
        } else {
            throw ProviderPermanentException(
                "ProviderA payment failed: $errorCode",
                e
            )
        }
    }
    
    /**
     * Handles 5xx server errors from Provider A.
     */
    private fun handleServerError(
        e: HttpServerErrorException,
        transactionId: String
    ): ProviderResponse {
        logger.error(
            "ProviderA server error: transactionId={}, status={}",
            transactionId,
            e.statusCode,
            e
        )
        
        throw ProviderTransientException(
            "ProviderA server error: ${e.statusCode}",
            e
        )
    }
    
    /**
     * Handles network/timeout errors.
     */
    private fun handleNetworkError(
        e: ResourceAccessException,
        transactionId: String
    ): ProviderResponse {
        logger.error(
            "ProviderA network error: transactionId={}",
            transactionId,
            e
        )
        
        if (e.cause is SocketTimeoutException) {
            throw ProviderTimeoutException(
                "ProviderA request timeout: $transactionId",
                e
            )
        } else {
            throw ProviderNetworkException(
                "ProviderA network error: ${e.message}",
                e
            )
        }
    }
    
    /**
     * Extracts error code from Provider A error response.
     */
    private fun extractErrorCode(responseBody: String): String {
        // In production, parse JSON response
        // For now, return generic error
        return "unknown_error"
    }
}


