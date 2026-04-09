package com.payment.orchestration.service

import com.payment.orchestration.domain.model.Payment
import com.payment.orchestration.domain.model.Transaction

/**
 * Payment Service Interface
 * 
 * Service layer contract for payment operations.
 * This interface defines the public API for payment processing.
 * 
 * Responsibilities:
 * - Payment creation and processing
 * - Payment retrieval
 * - Payment cancellation
 * - Idempotency handling
 * 
 * Implementation:
 * - PaymentServiceImpl: Main implementation using PaymentOrchestrationService
 * 
 * Design Principles:
 * - Interface segregation: Clean contract for clients
 * - Dependency inversion: Controllers depend on interface, not implementation
 * - Single responsibility: Each method has one clear purpose
 * 
 * @see PaymentServiceImpl
 * @see PaymentOrchestrationService
 */
interface PaymentService {
    
    /**
     * Creates and processes a new payment.
     * 
     * This method:
     * 1. Creates Payment domain object from Transaction
     * 2. Checks idempotency (if idempotency key provided)
     * 3. Delegates to orchestration service for processing
     * 4. Returns processed payment
     * 
     * Idempotency:
     * - If idempotencyKey is provided and payment exists, returns existing payment
     * - If idempotencyKey is provided and payment doesn't exist, creates new payment
     * - If idempotencyKey is null, always creates new payment
     * 
     * @param transaction The transaction details
     * @param idempotencyKey Optional idempotency key for duplicate prevention
     * @param requestId Optional request ID for tracing
     * @return Processed payment
     * @throws IllegalArgumentException if transaction is invalid
     * @throws PaymentProcessingException if processing fails
     */
    fun createPayment(
        transaction: Transaction,
        idempotencyKey: String? = null,
        requestId: String? = null
    ): Payment
    
    /**
     * Retrieves a payment by transaction ID.
     * 
     * @param transactionId The transaction identifier
     * @param includeEvents Whether to include event history
     * @param includeRetryContext Whether to include retry details
     * @param requestId Optional request ID for tracing
     * @return Payment if found
     * @throws PaymentNotFoundException if payment not found
     * @throws UnauthorizedException if payment belongs to different client
     */
    fun fetchPayment(
        transactionId: String,
        includeEvents: Boolean = false,
        includeRetryContext: Boolean = false,
        requestId: String? = null
    ): Payment
    
    /**
     * Cancels a payment.
     * 
     * Only payments in INITIATED or ROUTING state can be cancelled.
     * Payments in PROCESSING, SUCCEEDED, FAILED, or CANCELLED state cannot be cancelled.
     * 
     * @param transactionId The transaction identifier
     * @param requestId Optional request ID for tracing
     * @return Cancelled payment
     * @throws PaymentNotFoundException if payment not found
     * @throws IllegalStateException if payment cannot be cancelled
     * @throws UnauthorizedException if payment belongs to different client
     */
    fun cancelPayment(
        transactionId: String,
        requestId: String? = null
    ): Payment
    
    /**
     * Retrieves payment status.
     * 
     * Lightweight method that returns only status information.
     * Useful for polling or status checks.
     * 
     * @param transactionId The transaction identifier
     * @return Payment status
     * @throws PaymentNotFoundException if payment not found
     */
    fun getPaymentStatus(transactionId: String): PaymentStatusResponse
    
    /**
     * Lists payments for a merchant.
     * 
     * Supports pagination and filtering.
     * 
     * @param merchantId The merchant identifier
     * @param filters Optional filters (status, date range, etc.)
     * @param page Page number (0-based)
     * @param size Page size
     * @return Paginated list of payments
     */
    fun listPayments(
        merchantId: String,
        filters: PaymentFilters? = null,
        page: Int = 0,
        size: Int = 20
    ): PaymentListResponse
}

/**
 * Payment Status Response
 * 
 * Lightweight response for status queries.
 * 
 * @property transactionId The transaction identifier
 * @property status Current payment status
 * @property providerStatus Provider's status (if available)
 * @property updatedAt Last update timestamp
 */
data class PaymentStatusResponse(
    val transactionId: String,
    val status: com.payment.orchestration.domain.model.PaymentStatus,
    val providerStatus: String? = null,
    val updatedAt: java.time.Instant
)

/**
 * Payment Filters
 * 
 * Filters for payment listing.
 * 
 * @property status Filter by payment status
 * @property paymentMethod Filter by payment method
 * @property fromDate Filter by date range (start)
 * @property toDate Filter by date range (end)
 * @property minAmount Filter by minimum amount
 * @property maxAmount Filter by maximum amount
 */
data class PaymentFilters(
    val status: com.payment.orchestration.domain.model.PaymentStatus? = null,
    val paymentMethod: com.payment.orchestration.domain.model.PaymentMethod? = null,
    val fromDate: java.time.Instant? = null,
    val toDate: java.time.Instant? = null,
    val minAmount: java.math.BigDecimal? = null,
    val maxAmount: java.math.BigDecimal? = null
)

/**
 * Payment List Response
 * 
 * Paginated response for payment listing.
 * 
 * @property payments List of payments
 * @property totalCount Total number of payments matching filters
 * @property page Current page number
 * @property size Page size
 * @property totalPages Total number of pages
 */
data class PaymentListResponse(
    val payments: List<Payment>,
    val totalCount: Long,
    val page: Int,
    val size: Int,
    val totalPages: Int
) {
    val hasNext: Boolean = page < totalPages - 1
    val hasPrevious: Boolean = page > 0
}

/**
 * Exception thrown when payment processing fails.
 */
class PaymentProcessingException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * Exception thrown when user is not authorized to access payment.
 */
class UnauthorizedException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)


