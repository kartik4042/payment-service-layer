package com.payment.orchestration.service

import com.payment.orchestration.domain.model.Payment
import com.payment.orchestration.domain.model.PaymentStatus
import com.payment.orchestration.domain.model.Transaction
import com.payment.orchestration.repository.PaymentRepositoryAdapter
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Payment Service Implementation
 * 
 * Main implementation of PaymentService interface.
 * Coordinates between controllers and orchestration service.
 * 
 * Responsibilities:
 * - Request validation
 * - Domain object creation
 * - Delegation to orchestration service
 * - Response transformation
 * - Authorization checking
 * 
 * @property orchestrationService Core orchestration engine
 * @property paymentRepository Repository for payment queries
 */
@Service
@Transactional
class PaymentServiceImpl(
    private val orchestrationService: PaymentOrchestrationService,
    private val paymentRepository: PaymentRepositoryAdapter
) : PaymentService {
    
    private val logger = LoggerFactory.getLogger(PaymentServiceImpl::class.java)
    
    /**
     * Creates and processes a new payment.
     */
    override fun createPayment(
        transaction: Transaction,
        idempotencyKey: String?,
        requestId: String?
    ): Payment {
        logger.info(
            "Creating payment: amount={}, method={}, idempotencyKey={}, requestId={}",
            transaction.amount,
            transaction.paymentMethod,
            idempotencyKey,
            requestId
        )
        
        // Create payment domain object
        val payment = Payment.create(
            amount = transaction.amount.toLong(),
            currency = transaction.currency,
            paymentMethod = transaction.paymentMethod,
            idempotencyKey = idempotencyKey,
            customerId = transaction.customerId,
            customerEmail = transaction.customerEmail,
            metadata = transaction.metadata
        )
        
        // Delegate to orchestration service
        val processedPayment = orchestrationService.orchestratePayment(payment)
        
        logger.info(
            "Payment created: transactionId={}, status={}, requestId={}",
            processedPayment.transactionId,
            processedPayment.status,
            requestId
        )
        
        return processedPayment
    }
    
    /**
     * Retrieves a payment by transaction ID.
     */
    override fun fetchPayment(
        transactionId: String,
        includeEvents: Boolean,
        includeRetryContext: Boolean,
        requestId: String?
    ): Payment {
        logger.debug(
            "Fetching payment: transactionId={}, requestId={}",
            transactionId,
            requestId
        )
        
        val payment = orchestrationService.getPayment(transactionId)
        
        // TODO: Add authorization check
        // if (!isAuthorized(payment, currentUser)) {
        //     throw UnauthorizedException("Not authorized to access this payment")
        // }
        
        // TODO: Include events if requested
        // if (includeEvents) {
        //     payment = payment.copy(events = eventRepository.findByPaymentId(payment.id))
        // }
        
        // TODO: Include retry context if requested
        // if (includeRetryContext) {
        //     payment = payment.copy(retryContext = retryRepository.findByPaymentId(payment.id))
        // }
        
        return payment
    }
    
    /**
     * Cancels a payment.
     */
    override fun cancelPayment(
        transactionId: String,
        requestId: String?
    ): Payment {
        logger.info(
            "Cancelling payment: transactionId={}, requestId={}",
            transactionId,
            requestId
        )
        
        val payment = orchestrationService.cancelPayment(transactionId)
        
        logger.info(
            "Payment cancelled: transactionId={}, requestId={}",
            transactionId,
            requestId
        )
        
        return payment
    }
    
    /**
     * Retrieves payment status.
     */
    override fun getPaymentStatus(transactionId: String): PaymentStatusResponse {
        logger.debug("Getting payment status: transactionId={}", transactionId)
        
        val payment = orchestrationService.getPayment(transactionId)
        
        return PaymentStatusResponse(
            transactionId = payment.transactionId,
            status = payment.status,
            providerStatus = payment.providerStatus,
            updatedAt = payment.updatedAt
        )
    }
    
    /**
     * Lists payments for a merchant.
     */
    override fun listPayments(
        merchantId: String,
        filters: PaymentFilters?,
        page: Int,
        size: Int
    ): PaymentListResponse {
        logger.debug(
            "Listing payments: merchantId={}, page={}, size={}",
            merchantId,
            page,
            size
        )
        
        val pageable = PageRequest.of(page, size)
        
        val paymentsPage = if (filters?.status != null) {
            paymentRepository.findByMerchantIdAndStatus(
                merchantId,
                filters.status,
                pageable
            )
        } else {
            paymentRepository.findByMerchantId(merchantId, pageable)
        }
        
        return PaymentListResponse(
            payments = paymentsPage.content,
            totalCount = paymentsPage.totalElements,
            page = page,
            size = size,
            totalPages = paymentsPage.totalPages
        )
    }
}


