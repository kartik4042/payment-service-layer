package com.payment.orchestration.api.controller

import com.payment.orchestration.api.dto.CreatePaymentRequest
import com.payment.orchestration.api.dto.PaymentResponse
import com.payment.orchestration.domain.model.Payment
import com.payment.orchestration.service.PaymentService
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * REST Controller for creating payments.
 * 
 * Handles POST /api/v1/payments endpoint.
 * 
 * Responsibilities:
 * - Request validation (via @Valid annotation)
 * - Idempotency key extraction from header
 * - Delegation to service layer
 * - Response transformation
 * - HTTP status code mapping
 * 
 * This controller follows RESTful conventions:
 * - POST for resource creation
 * - 201 Created for successful creation
 * - Location header with resource URI
 * - Idempotency support via Idempotency-Key header
 * 
 * @property paymentService Service layer for payment processing
 */
@RestController
@RequestMapping("/api/v1/payments")
class CreatePaymentController(
    private val paymentService: PaymentService
) {
    private val logger = LoggerFactory.getLogger(CreatePaymentController::class.java)
    
    /**
     * Creates a new payment.
     * 
     * HTTP Method: POST
     * Path: /api/v1/payments
     * 
     * Request Headers:
     * - Authorization: Bearer {api_key} (required)
     * - Idempotency-Key: {unique_key} (optional but recommended)
     * - X-Request-ID: {request_id} (optional, for tracing)
     * 
     * Success Response:
     * - Status: 201 Created
     * - Location: /api/v1/payments/{transaction_id}
     * - Body: PaymentResponse
     * 
     * Error Responses:
     * - 400 Bad Request: Invalid request parameters
     * - 401 Unauthorized: Invalid or missing API key
     * - 402 Payment Required: Payment declined
     * - 409 Conflict: Duplicate request (idempotency key already processing)
     * - 429 Too Many Requests: Rate limit exceeded
     * - 500 Internal Server Error: Unexpected error
     * - 503 Service Unavailable: All providers failed
     * 
     * @param request The payment creation request (validated)
     * @param idempotencyKey Optional idempotency key from header
     * @param requestId Optional request ID for tracing
     * @return ResponseEntity with PaymentResponse and 201 status
     */
    @PostMapping
    fun createPayment(
        @Valid @RequestBody request: CreatePaymentRequest,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        @RequestHeader("X-Request-ID", required = false) requestId: String?
    ): ResponseEntity<PaymentResponse> {
        // Generate request ID if not provided (for tracing and logging)
        val effectiveRequestId = requestId ?: generateRequestId()
        
        // Log incoming request (without sensitive data)
        logger.info(
            "Creating payment: amount={}, currency={}, method={}, idempotencyKey={}, requestId={}",
            request.amount,
            request.currency,
            request.paymentMethod.type,
            idempotencyKey?.take(10) + "...", // Log only first 10 chars
            effectiveRequestId
        )
        
        try {
            // Delegate to service layer for business logic
            // Service layer handles:
            // - Idempotency checking
            // - Transaction creation
            // - Provider routing
            // - Payment execution
            // - Retry and failover
            val payment: Payment = paymentService.createPayment(
                request = request,
                idempotencyKey = idempotencyKey,
                requestId = effectiveRequestId
            )
            
            // Transform domain model to DTO
            val response = PaymentResponse.fromPayment(payment)
            
            // Log successful creation
            logger.info(
                "Payment created successfully: transactionId={}, status={}, provider={}, requestId={}",
                payment.transactionId,
                payment.status,
                payment.provider,
                effectiveRequestId
            )
            
            // Return 201 Created with Location header
            // Location header points to the resource URI for fetching payment details
            return ResponseEntity
                .status(HttpStatus.CREATED)
                .header("Location", "/api/v1/payments/${payment.transactionId}")
                .header("X-Request-ID", effectiveRequestId)
                .body(response)
                
        } catch (e: Exception) {
            // Log error (exception handling is done by global exception handler)
            logger.error(
                "Error creating payment: requestId={}, error={}",
                effectiveRequestId,
                e.message,
                e
            )
            throw e
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

