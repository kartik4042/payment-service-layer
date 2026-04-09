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
 * Provider B Implementation
 * 
 * Handles UPI payment processing (Razorpay-like provider).
 * 
 * Supported Payment Methods:
 * - UPI (Unified Payments Interface)
 * 
 * Features:
 * - Idempotency via transaction ID
 * - Automatic retry on transient failures
 * - Circuit breaker compatible
 * - Comprehensive error mapping
 * - Health check endpoint
 * - VPA (Virtual Payment Address) validation
 * 
 * Configuration:
 * - Base URL: ${provider.b.base-url}
 * - API Key: ${provider.b.api-key}
 * - Timeout: 30 seconds
 * - Retry: 3 attempts with exponential backoff
 * 
 * API Endpoints:
 * - POST /v1/upi/collect - Initiate UPI collect request
 * - GET /v1/upi/status/{id} - Get transaction status
 * - GET /v1/health - Health check
 * 
 * UPI Flow:
 * 1. Initiate collect request with VPA
 * 2. Customer approves on UPI app
 * 3. Poll for status or receive webhook
 * 4. Update payment status
 * 
 * @property restTemplate HTTP client for API calls
 * @property baseUrl Provider B API base URL
 * @property apiKey Provider B API key
 */
@Component
class ProviderB(
    private val restTemplate: RestTemplate,
    private val baseUrl: String = "https://api.provider-b.com",
    private val apiKey: String = System.getenv("PROVIDER_B_API_KEY") ?: "test_key"
) : PaymentProvider {
    
    private val logger = LoggerFactory.getLogger(ProviderB::class.java)
    
    companion object {
        private const val TIMEOUT_SECONDS = 30L
        private const val HEALTH_CHECK_TIMEOUT_SECONDS = 5L
        
        // Provider B specific error codes
        private const val ERROR_RATE_LIMIT = "rate_limit_exceeded"
        private const val ERROR_INVALID_VPA = "invalid_vpa"
        private const val ERROR_VPA_NOT_FOUND = "vpa_not_found"
        private const val ERROR_TRANSACTION_DECLINED = "transaction_declined"
        private const val ERROR_BANK_UNAVAILABLE = "bank_unavailable"
        private const val ERROR_TIMEOUT = "transaction_timeout"
        
        // VPA validation regex
        private val VPA_REGEX = Regex("^[a-zA-Z0-9._-]+@[a-zA-Z0-9]+$")
    }
    
    override fun getProviderId(): Provider = Provider.PROVIDER_B
    
    /**
     * Processes UPI payment via Provider B.
     * 
     * Flow:
     * 1. Validate payment method (must be UPI)
     * 2. Validate VPA format
     * 3. Build provider-specific request
     * 4. Call Provider B API with idempotency key
     * 5. Map provider response to standard format
     * 6. Handle errors appropriately
     * 
     * Note: UPI payments are asynchronous. Initial response indicates
     * collect request was sent, not payment completion. Status must be
     * polled or received via webhook.
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
        if (payment.transaction.paymentMethod != PaymentMethod.UPI) {
            logger.error(
                "ProviderB only supports UPI payments: transactionId={}, method={}",
                payment.transactionId,
                payment.transaction.paymentMethod
            )
            throw ProviderPermanentException(
                "ProviderB only supports UPI payments, got: ${payment.transaction.paymentMethod}"
            )
        }
        
        // Validate VPA
        val vpa = payment.transaction.paymentDetails["vpa"] as? String
        if (vpa == null || !isValidVPA(vpa)) {
            logger.error(
                "Invalid VPA format: transactionId={}, vpa={}",
                payment.transactionId,
                vpa
            )
            throw ProviderPermanentException(
                "Invalid VPA format: $vpa"
            )
        }
        
        logger.info(
            "Processing UPI payment via ProviderB: transactionId={}, amount={}, currency={}, vpa={}",
            payment.transactionId,
            payment.transaction.amount,
            payment.transaction.currency,
            maskVPA(vpa)
        )
        
        try {
            // Build provider-specific request
            val request = buildUPICollectRequest(payment, vpa)
            
            // Call Provider B API
            // In production, this would use RestTemplate with proper configuration
            val response = callProviderAPI(request, payment.transactionId)
            
            val duration = Duration.between(startTime, Instant.now())
            logger.info(
                "ProviderB payment processed: transactionId={}, success={}, duration={}ms",
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
                "Unexpected error processing payment via ProviderB: transactionId={}",
                payment.transactionId,
                e
            )
            throw ProviderTransientException(
                "Unexpected error from ProviderB: ${e.message}",
                e
            )
        }
    }
    
    /**
     * Checks Provider B health status.
     * 
     * Calls GET /v1/health endpoint with 5 second timeout.
     * 
     * @return true if healthy, false otherwise
     */
    override fun isHealthy(): Boolean {
        return try {
            logger.debug("Checking ProviderB health")
            
            // In production, call actual health endpoint
            // For now, simulate health check
            val healthEndpoint = "$baseUrl/v1/health"
            
            // Simulate API call with timeout
            Thread.sleep(100) // Simulate network latency
            
            logger.debug("ProviderB health check passed")
            true
            
        } catch (e: Exception) {
            logger.warn("ProviderB health check failed: {}", e.message)
            false
        }
    }
    
    /**
     * Retrieves transaction status from Provider B.
     * 
     * Used for polling UPI transaction status.
     * 
     * @param transactionId The transaction identifier
     * @return ProviderResponse with current status
     */
    override fun getTransactionStatus(transactionId: String): ProviderResponse {
        logger.info("Fetching transaction status from ProviderB: transactionId={}", transactionId)
        
        try {
            // In production, call GET /v1/upi/status/{id}
            // For now, simulate status check
            
            // Simulate different statuses
            val random = Math.random()
            
            return when {
                random < 0.7 -> {
                    // Success
                    ProviderResponse(
                        success = true,
                        providerTransactionId = "upi_${transactionId}",
                        providerStatus = "success",
                        metadata = mapOf(
                            "provider" to "ProviderB",
                            "fetched_at" to Instant.now().toString(),
                            "upi_ref" to "UPI${System.currentTimeMillis()}"
                        )
                    )
                }
                random < 0.85 -> {
                    // Pending (customer hasn't approved yet)
                    ProviderResponse(
                        success = false,
                        providerTransactionId = "upi_${transactionId}",
                        providerStatus = "pending",
                        errorCode = "pending_approval",
                        errorMessage = "Waiting for customer approval",
                        isTransient = true
                    )
                }
                else -> {
                    // Failed
                    ProviderResponse(
                        success = false,
                        providerTransactionId = "upi_${transactionId}",
                        providerStatus = "failed",
                        errorCode = ERROR_TRANSACTION_DECLINED,
                        errorMessage = "Transaction declined by customer",
                        isTransient = false
                    )
                }
            }
            
        } catch (e: Exception) {
            logger.error(
                "Error fetching transaction status from ProviderB: transactionId={}",
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
     * Validates VPA format.
     * 
     * Valid VPA format: username@bankcode
     * Examples: user@paytm, john.doe@okaxis
     * 
     * @param vpa The VPA to validate
     * @return true if valid, false otherwise
     */
    private fun isValidVPA(vpa: String): Boolean {
        return VPA_REGEX.matches(vpa)
    }
    
    /**
     * Masks VPA for logging (PII protection).
     * 
     * Example: user@paytm -> u***@paytm
     * 
     * @param vpa The VPA to mask
     * @return Masked VPA
     */
    private fun maskVPA(vpa: String): String {
        val parts = vpa.split("@")
        if (parts.size != 2) return "***@***"
        
        val username = parts[0]
        val bank = parts[1]
        
        val maskedUsername = if (username.length <= 2) {
            "***"
        } else {
            username.first() + "***"
        }
        
        return "$maskedUsername@$bank"
    }
    
    /**
     * Builds Provider B specific UPI collect request.
     */
    private fun buildUPICollectRequest(payment: Payment, vpa: String): Map<String, Any> {
        return mapOf(
            "amount" to payment.transaction.amount,
            "currency" to payment.transaction.currency,
            "vpa" to vpa,
            "description" to "Payment for ${payment.transaction.merchantId}",
            "callback_url" to "https://api.merchant.com/webhooks/upi",
            "metadata" to mapOf(
                "transaction_id" to payment.transactionId,
                "merchant_id" to payment.transaction.merchantId,
                "customer_id" to payment.transaction.customerId
            )
        )
    }
    
    /**
     * Simulates Provider B API call.
     * In production, this would use RestTemplate.
     */
    private fun callProviderAPI(request: Map<String, Any>, transactionId: String): ProviderResponse {
        // Simulate API call
        // In production: restTemplate.postForObject("$baseUrl/v1/upi/collect", request, ProviderBResponse::class.java)
        
        // Simulate 90% success rate (UPI has lower success rate than cards)
        val random = Math.random()
        
        return when {
            random < 0.90 -> {
                // Success - collect request sent
                ProviderResponse(
                    success = true,
                    providerTransactionId = "upi_${transactionId}",
                    providerStatus = "pending",
                    metadata = mapOf(
                        "provider" to "ProviderB",
                        "processed_at" to Instant.now().toString(),
                        "collect_request_sent" to true
                    )
                )
            }
            random < 0.95 -> {
                // Transient failure (bank unavailable)
                ProviderResponse(
                    success = false,
                    providerTransactionId = "upi_${transactionId}",
                    providerStatus = "bank_unavailable",
                    errorCode = ERROR_BANK_UNAVAILABLE,
                    errorMessage = "Bank service temporarily unavailable",
                    isTransient = true
                )
            }
            else -> {
                // Permanent failure (invalid VPA)
                ProviderResponse(
                    success = false,
                    providerTransactionId = "upi_${transactionId}",
                    providerStatus = "failed",
                    errorCode = ERROR_VPA_NOT_FOUND,
                    errorMessage = "VPA not found or inactive",
                    isTransient = false
                )
            }
        }
    }
    
    /**
     * Handles 4xx client errors from Provider B.
     */
    private fun handleClientError(
        e: HttpClientErrorException,
        transactionId: String
    ): ProviderResponse {
        logger.warn(
            "ProviderB client error: transactionId={}, status={}, body={}",
            transactionId,
            e.statusCode,
            e.responseBodyAsString
        )
        
        // Parse error response
        val errorCode = extractErrorCode(e.responseBodyAsString)
        
        // Determine if retryable
        val isTransient = errorCode == ERROR_RATE_LIMIT || errorCode == ERROR_BANK_UNAVAILABLE
        
        if (isTransient) {
            throw ProviderTransientException(
                "ProviderB transient error: $errorCode",
                e
            )
        } else {
            throw ProviderPermanentException(
                "ProviderB payment failed: $errorCode",
                e
            )
        }
    }
    
    /**
     * Handles 5xx server errors from Provider B.
     */
    private fun handleServerError(
        e: HttpServerErrorException,
        transactionId: String
    ): ProviderResponse {
        logger.error(
            "ProviderB server error: transactionId={}, status={}",
            transactionId,
            e.statusCode,
            e
        )
        
        throw ProviderTransientException(
            "ProviderB server error: ${e.statusCode}",
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
            "ProviderB network error: transactionId={}",
            transactionId,
            e
        )
        
        if (e.cause is SocketTimeoutException) {
            throw ProviderTimeoutException(
                "ProviderB request timeout: $transactionId",
                e
            )
        } else {
            throw ProviderNetworkException(
                "ProviderB network error: ${e.message}",
                e
            )
        }
    }
    
    /**
     * Extracts error code from Provider B error response.
     */
    private fun extractErrorCode(responseBody: String): String {
        // In production, parse JSON response
        // For now, return generic error
        return "unknown_error"
    }
}


