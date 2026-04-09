package com.payment.orchestration.service

import com.payment.orchestration.domain.model.Payment
import com.payment.orchestration.domain.model.PaymentStatus
import com.payment.orchestration.provider.PaymentProvider
import com.payment.orchestration.routing.RoutingEngine
import com.payment.orchestration.retry.RetryManager
import com.payment.orchestration.retry.FailoverManager
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Payment Orchestration Service
 * 
 * Core orchestration engine that coordinates the entire payment flow.
 * 
 * Responsibilities:
 * - Payment lifecycle management
 * - Provider selection via routing engine
 * - Retry and failover coordination
 * - State transitions
 * - Event publishing
 * - Idempotency handling
 * 
 * Payment Flow:
 * 1. INITIATED → Validate payment
 * 2. ROUTING → Select provider via routing engine
 * 3. PROCESSING → Execute payment with retry/failover
 * 4. SUCCEEDED/FAILED → Update final status
 * 
 * Error Handling:
 * - Transient errors: Retry with exponential backoff
 * - Provider failures: Failover to alternative provider
 * - Permanent errors: Fail immediately
 * - Validation errors: Fail immediately
 * 
 * @property routingEngine For provider selection
 * @property retryManager For retry logic
 * @property failoverManager For failover logic
 * @property paymentRepository For persistence (to be implemented)
 */
@Service
@Transactional
class PaymentOrchestrationService(
    private val routingEngine: RoutingEngine,
    private val retryManager: RetryManager,
    private val failoverManager: FailoverManager,
    private val paymentRepository: com.payment.orchestration.repository.PaymentRepositoryAdapter,
    private val idempotencyService: com.payment.orchestration.idempotency.IdempotencyService,
    private val eventPublisher: com.payment.orchestration.events.EventPublisher
) {
    private val logger = LoggerFactory.getLogger(PaymentOrchestrationService::class.java)
    
    /**
     * Orchestrates the complete payment flow.
     * 
     * This is the main entry point for payment processing.
     * 
     * Flow:
     * 1. Validate payment
     * 2. Check idempotency
     * 3. Transition to ROUTING state
     * 4. Select provider
     * 5. Transition to PROCESSING state
     * 6. Execute payment with retry/failover
     * 7. Transition to final state (SUCCEEDED/FAILED)
     * 8. Persist payment
     * 9. Publish events
     * 
     * @param payment The payment to process
     * @return Processed payment with final status
     */
    fun orchestratePayment(payment: Payment): Payment {
        logger.info(
            "Starting payment orchestration: transactionId={}, amount={}, method={}",
            payment.transactionId,
            payment.transaction.amount,
            payment.transaction.paymentMethod
        )
        
        var currentPayment = payment
        
        try {
            // Step 1: Validate payment
            validatePayment(currentPayment)
            
            // Step 2: Check idempotency
            val existingPayment = idempotencyService.checkIdempotency(
                currentPayment.transactionId,
                currentPayment.transaction
            )
            if (existingPayment != null) {
                logger.info(
                    "Returning existing payment from idempotency check: transactionId={}",
                    existingPayment.transactionId
                )
                return existingPayment
            }
            
            // Step 3: Transition to ROUTING state
            currentPayment = transitionToRouting(currentPayment)
            
            // Step 4: Select provider via routing engine
            val selectedProvider = routingEngine.selectProvider(currentPayment)
            
            logger.info(
                "Provider selected: transactionId={}, provider={}",
                currentPayment.transactionId,
                selectedProvider.getProviderId()
            )
            
            // Step 5: Transition to PROCESSING state
            currentPayment = transitionToProcessing(currentPayment, selectedProvider)
            
            // Step 6: Execute payment with retry logic
            val retryResult = retryManager.executeWithRetry(currentPayment) {
                selectedProvider.processPayment(currentPayment)
            }
            
            // Step 7: Handle result
            currentPayment = if (retryResult.success) {
                // Success - transition to SUCCEEDED
                handleSuccess(currentPayment, retryResult, selectedProvider)
            } else {
                // Primary provider failed - attempt failover
                handleFailure(currentPayment, retryResult, selectedProvider)
            }
            
            // Step 8: Persist payment
            currentPayment = paymentRepository.save(currentPayment)
            
            // Step 9: Mark idempotency as completed
            idempotencyService.markCompleted(currentPayment.transactionId)
            
            // Step 10: Publish events
            publishPaymentEvent(currentPayment)
            
            logger.info(
                "Payment orchestration completed: transactionId={}, status={}, provider={}",
                currentPayment.transactionId,
                currentPayment.status,
                currentPayment.selectedProvider
            )
            
            return currentPayment
            
        } catch (e: Exception) {
            logger.error(
                "Payment orchestration failed: transactionId={}",
                currentPayment.transactionId,
                e
            )
            
            // Transition to FAILED state
            currentPayment = transitionToFailed(currentPayment, e.message ?: "Unknown error")
            
            // Persist failure
            currentPayment = paymentRepository.save(currentPayment)
            
            // Mark idempotency as failed (allows retry)
            idempotencyService.markFailed(currentPayment.transactionId)
            
            return currentPayment
        }
    }
    
    /**
     * Validates payment before processing.
     * 
     * Validation checks:
     * - Transaction amount > 0
     * - Valid payment method
     * - Valid currency
     * - Required payment details present
     * 
     * @param payment The payment to validate
     * @throws IllegalArgumentException if validation fails
     */
    private fun validatePayment(payment: Payment) {
        logger.debug("Validating payment: transactionId={}", payment.transactionId)
        
        // Amount validation
        if (payment.transaction.amount <= java.math.BigDecimal.ZERO) {
            throw IllegalArgumentException("Payment amount must be greater than zero")
        }
        
        // Payment method validation
        if (payment.transaction.paymentMethod == null) {
            throw IllegalArgumentException("Payment method is required")
        }
        
        // Currency validation
        if (payment.transaction.currency.isBlank()) {
            throw IllegalArgumentException("Currency is required")
        }
        
        // Payment details validation (method-specific)
        when (payment.transaction.paymentMethod) {
            com.payment.orchestration.domain.model.PaymentMethod.CARD -> {
                if (!payment.transaction.paymentDetails.containsKey("card_token")) {
                    throw IllegalArgumentException("Card token is required for CARD payments")
                }
            }
            com.payment.orchestration.domain.model.PaymentMethod.UPI -> {
                if (!payment.transaction.paymentDetails.containsKey("vpa")) {
                    throw IllegalArgumentException("VPA is required for UPI payments")
                }
            }
            else -> {
                // Other payment methods
            }
        }
        
        logger.debug("Payment validation passed: transactionId={}", payment.transactionId)
    }
    
    /**
     * Transitions payment to ROUTING state.
     */
    private fun transitionToRouting(payment: Payment): Payment {
        logger.debug("Transitioning to ROUTING: transactionId={}", payment.transactionId)
        
        return payment.copy(
            status = PaymentStatus.ROUTING,
            updatedAt = Instant.now()
        )
    }
    
    /**
     * Transitions payment to PROCESSING state.
     */
    private fun transitionToProcessing(payment: Payment, provider: PaymentProvider): Payment {
        logger.debug(
            "Transitioning to PROCESSING: transactionId={}, provider={}",
            payment.transactionId,
            provider.getProviderId()
        )
        
        return payment.copy(
            status = PaymentStatus.PROCESSING,
            selectedProvider = provider.getProviderId(),
            updatedAt = Instant.now()
        )
    }
    
    /**
     * Handles successful payment.
     */
    private fun handleSuccess(
        payment: Payment,
        retryResult: com.payment.orchestration.retry.RetryResult,
        provider: PaymentProvider
    ): Payment {
        logger.info(
            "Payment succeeded: transactionId={}, provider={}, attempts={}",
            payment.transactionId,
            provider.getProviderId(),
            retryResult.getTotalAttempts()
        )
        
        val providerResponse = retryResult.response!!
        
        return payment.copy(
            status = PaymentStatus.SUCCEEDED,
            providerTransactionId = providerResponse.providerTransactionId,
            providerStatus = providerResponse.providerStatus,
            completedAt = Instant.now(),
            updatedAt = Instant.now(),
            metadata = payment.metadata + mapOf(
                "retry_attempts" to retryResult.getTotalAttempts(),
                "total_duration_ms" to retryResult.getTotalDuration().toMillis()
            )
        )
    }
    
    /**
     * Handles payment failure with failover attempt.
     */
    private fun handleFailure(
        payment: Payment,
        retryResult: com.payment.orchestration.retry.RetryResult,
        provider: PaymentProvider
    ): Payment {
        logger.warn(
            "Payment failed with primary provider: transactionId={}, provider={}, reason={}",
            payment.transactionId,
            provider.getProviderId(),
            retryResult.reason
        )
        
        // Attempt failover
        val failoverResult = failoverManager.attemptFailover(payment, provider, retryResult)
        
        return if (failoverResult.success) {
            // Failover succeeded
            logger.info(
                "Payment succeeded via failover: transactionId={}, fallbackProvider={}",
                payment.transactionId,
                failoverResult.fallbackProvider
            )
            
            val providerResponse = failoverResult.fallbackResponse!!
            
            payment.copy(
                status = PaymentStatus.SUCCEEDED,
                selectedProvider = failoverResult.fallbackProvider,
                providerTransactionId = providerResponse.providerTransactionId,
                providerStatus = providerResponse.providerStatus,
                completedAt = Instant.now(),
                updatedAt = Instant.now(),
                metadata = payment.metadata + mapOf(
                    "failover_attempted" to true,
                    "primary_provider" to failoverResult.primaryProvider.name,
                    "fallback_provider" to failoverResult.fallbackProvider!!.name,
                    "total_attempts" to failoverResult.getTotalAttempts()
                )
            )
        } else {
            // Failover also failed
            logger.error(
                "Payment failed after failover: transactionId={}, reason={}",
                payment.transactionId,
                failoverResult.reason
            )
            
            transitionToFailed(
                payment,
                failoverResult.reason ?: "Payment failed after retry and failover"
            )
        }
    }
    
    /**
     * Transitions payment to FAILED state.
     */
    private fun transitionToFailed(payment: Payment, reason: String): Payment {
        logger.error(
            "Transitioning to FAILED: transactionId={}, reason={}",
            payment.transactionId,
            reason
        )
        
        return payment.copy(
            status = PaymentStatus.FAILED,
            failureReason = reason,
            completedAt = Instant.now(),
            updatedAt = Instant.now()
        )
    }
    
    /**
     * Retrieves payment by transaction ID.
     * 
     * @param transactionId The transaction identifier
     * @return Payment if found
     * @throws PaymentNotFoundException if not found
     */
    fun getPayment(transactionId: String): Payment {
        logger.debug("Retrieving payment: transactionId={}", transactionId)
        
        return paymentRepository.findByTransactionId(transactionId)
            ?: throw PaymentNotFoundException("Payment not found: $transactionId")
    }
    
    /**
     * Cancels a payment.
     * 
     * Only payments in INITIATED or ROUTING state can be cancelled.
     * 
     * @param transactionId The transaction identifier
     * @return Cancelled payment
     */
    fun cancelPayment(transactionId: String): Payment {
        logger.info("Cancelling payment: transactionId={}", transactionId)
        
        val payment = getPayment(transactionId)
        
        // Check if cancellable
        if (!payment.isCancellable()) {
            throw IllegalStateException(
                "Payment cannot be cancelled in ${payment.status} state"
            )
        }
        
        // Transition to CANCELLED
        val cancelledPayment = payment.copy(
    
    /**
     * Publishes payment event based on payment status.
     * 
     * @param payment The payment to publish event for
     */
    private fun publishPaymentEvent(payment: Payment) {
        val event = when (payment.status) {
            PaymentStatus.INITIATED -> com.payment.orchestration.events.PaymentEvent.PaymentCreated(
                eventId = java.util.UUID.randomUUID().toString(),
                timestamp = Instant.now(),
                paymentId = payment.id,
                transactionId = payment.transactionId,
                aggregateVersion = 1,
                customerId = payment.customerId,
                merchantId = payment.merchantId,
                amount = payment.transaction.amount,
                currency = payment.transaction.currency,
                paymentMethod = payment.transaction.paymentMethod,
                provider = payment.selectedProvider,
                description = payment.transaction.description,
                metadata = payment.metadata
            )
            PaymentStatus.SUCCEEDED -> com.payment.orchestration.events.PaymentEvent.PaymentSucceeded(
                eventId = java.util.UUID.randomUUID().toString(),
                timestamp = Instant.now(),
                paymentId = payment.id,
                transactionId = payment.transactionId,
                aggregateVersion = 2,
                provider = payment.selectedProvider!!,
                providerTransactionId = payment.providerTransactionId,
                providerStatus = payment.providerStatus,
                completedAt = payment.completedAt!!,
                metadata = payment.metadata
            )
            PaymentStatus.FAILED -> com.payment.orchestration.events.PaymentEvent.PaymentFailed(
                eventId = java.util.UUID.randomUUID().toString(),
                timestamp = Instant.now(),
                paymentId = payment.id,
                transactionId = payment.transactionId,
                aggregateVersion = 2,
                provider = payment.selectedProvider,
                reason = payment.failureReason ?: "Unknown error",
                errorCode = "PAYMENT_FAILED",
                retryable = false,
                metadata = payment.metadata
            )
            PaymentStatus.CANCELLED -> com.payment.orchestration.events.PaymentEvent.PaymentCancelled(
                eventId = java.util.UUID.randomUUID().toString(),
                timestamp = Instant.now(),
                paymentId = payment.id,
                transactionId = payment.transactionId,
                aggregateVersion = 2,
                reason = "Cancelled by user",
                cancelledBy = "system",
                metadata = payment.metadata
            )
            else -> {
                logger.debug("No event to publish for status: {}", payment.status)
                return
            }
        }
        
        eventPublisher.publish(event)
    }
            status = PaymentStatus.CANCELLED,
            completedAt = Instant.now(),
            updatedAt = Instant.now()
        )
        
        // Persist
        paymentRepository.save(cancelledPayment)
        
        logger.info("Payment cancelled: transactionId={}", transactionId)
        
        return cancelledPayment
    }
}

/**
 * Exception thrown when payment is not found.
 */
class PaymentNotFoundException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)


