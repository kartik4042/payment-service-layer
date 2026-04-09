package com.payment.orchestration.api.controller

import com.payment.orchestration.api.dto.PaymentResponse
import com.payment.orchestration.domain.model.Payment
import com.payment.orchestration.service.PaymentService
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * REST Controller for fetching payment details.
 * 
 * Handles GET /api/v1/payments/{transaction_id} endpoint.
 * 
 * Responsibilities:
 * - Transaction ID validation
 * - Authorization checking (payment belongs to requesting client)
 * - Delegation to service layer
 * - Response transformation
 * - HTTP status code mapping
 * 
 * This controller follows RESTful conventions:
 * - GET for resource retrieval
 * - 200 OK for successful retrieval
 * - 404 Not Found for non-existent resources
 * - 403 Forbidden for unauthorized access
 * 
 * @property paymentService Service layer for payment retrieval
 */
@RestController
@RequestMapping("/api/v1/payments")
class FetchPaymentController(
    private val paymentService: PaymentService
) {
    private val logger = LoggerFactory.getLogger(FetchPaymentController::class.java)
    
    /**
     * Fetches payment details by transaction ID.
     * 
     * HTTP Method: GET
     * Path: /api/v1/payments/{transaction_id}
     * 
     * Request Headers:
     * - Authorization: Bearer {api_key} (required)
     * - X-Request-ID: {request_id} (optional, for tracing)
     * 
     * Query Parameters (optional):
     * - include_events: Include full event history (default: false)
     * - include_retry_context: Include retry details (default: false)
     * 
     * Success Response:
     * - Status: 200 OK
     * - Body: PaymentResponse with transaction details
     * 
     * Error Responses:
     * - 400 Bad Request: Invalid transaction ID format
     * - 401 Unauthorized: Invalid or missing API key
     * - 403 Forbidden: Payment belongs to different client
     * - 404 Not Found: Transaction does not exist
     * - 500 Internal Server Error: Unexpected error
     * 
     * @param transactionId The transaction identifier
     * @param includeEvents Whether to include event history
     * @param includeRetryContext Whether to include retry details
     * @param requestId Optional request ID for tracing
     * @return ResponseEntity with PaymentResponse and 200 status
     */
    @GetMapping("/{transaction_id}")
    fun fetchPayment(
        @PathVariable("transaction_id") transactionId: String,
        @RequestParam("include_events", required = false, defaultValue = "false") 
        includeEvents: Boolean,
        @RequestParam("include_retry_context", required = false, defaultValue = "false") 
        includeRetryContext: Boolean,
        @RequestHeader("X-Request-ID", required = false) requestId: String?
    ): ResponseEntity<PaymentResponse> {
        // Generate request ID if not provided
        val effectiveRequestId = requestId ?: generateRequestId()
        
        // Validate transaction ID format
        // Transaction IDs must match pattern: txn_[alphanumeric]
        if (!transactionId.matches(Regex("^txn_[a-zA-Z0-9]{10,}$"))) {
            logger.warn(
                "Invalid transaction ID format: transactionId={}, requestId={}",
                transactionId,
                effectiveRequestId
            )
            throw IllegalArgumentException("Invalid transaction ID format: $transactionId")
        }
        
        // Log incoming request
        logger.info(
            "Fetching payment: transactionId={}, includeEvents={}, includeRetryContext={}, requestId={}",
            transactionId,
            includeEvents,
            includeRetryContext,
            effectiveRequestId
        )
        
        try {
            // Delegate to service layer
            // Service layer handles:
            // - Database query
            // - Authorization checking
            // - Event history retrieval (if requested)
            // - Retry context retrieval (if requested)
            val payment: Payment = paymentService.fetchPayment(
                transactionId = transactionId,
                includeEvents = includeEvents,
                includeRetryContext = includeRetryContext,
                requestId = effectiveRequestId
            )
            
            // Transform domain model to DTO
            val response = PaymentResponse.fromPayment(payment)
            
            // Log successful retrieval
            logger.info(
                "Payment fetched successfully: transactionId={}, status={}, requestId={}",
                payment.transactionId,
                payment.status,
                effectiveRequestId
            )
            
            // Return 200 OK with payment details
            // Add cache control headers for performance
            return ResponseEntity
                .ok()
                .header("X-Request-ID", effectiveRequestId)
                .header("Cache-Control", getCacheControl(payment))
                .body(response)
                
        } catch (e: Exception) {
            // Log error (exception handling is done by global exception handler)
            logger.error(
                "Error fetching payment: transactionId={}, requestId={}, error={}",
                transactionId,
                effectiveRequestId,
                e.message,
                e
            )
            throw e
        }
    }
    
    /**
     * Determines appropriate cache control header based on payment status.
     * 
     * Terminal states (succeeded, failed, cancelled) can be cached longer
     * since they won't change. Active states should not be cached.
     * 
     * @param payment The payment object
     * @return Cache-Control header value
     */
    private fun getCacheControl(payment: Payment): String {
        return if (payment.isTerminal()) {
            // Terminal states can be cached for 1 hour
            "private, max-age=3600"
        } else {
            // Active states should not be cached
            "no-cache, no-store, must-revalidate"
        }
    }
    
    /**
     * Generates a unique request ID for tracing.
     * Format: req_{uuid}
     * 
     * @return Generated request ID
     */
    private fun generateRequestId(): String {
        return "req_${UUID.randomUUID().toString().replace("-", "")}"
    }
}


