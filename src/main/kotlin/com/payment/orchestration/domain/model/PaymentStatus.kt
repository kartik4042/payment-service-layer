package com.payment.orchestration.domain.model

/**
 * Represents the lifecycle status of a payment transaction.
 * 
 * State transitions follow a strict state machine:
 * INITIATED → ROUTING → PROCESSING → [SUCCEEDED | FAILED | PENDING]
 *                          ↓
 *                      RETRYING → PROCESSING
 * 
 * Terminal states: SUCCEEDED, FAILED, CANCELLED
 * 
 * @property isTerminal Indicates if this status is a terminal state (no further transitions)
 */
enum class PaymentStatus(val isTerminal: Boolean) {
    /**
     * Payment has been created but not yet routed to a provider.
     * Initial state for all payments.
     */
    INITIATED(false),
    
    /**
     * Provider selection is in progress.
     * Routing engine is evaluating rules to select optimal provider.
     */
    ROUTING(false),
    
    /**
     * Payment has been submitted to the provider and is being processed.
     * Waiting for provider response.
     */
    PROCESSING(false),
    
    /**
     * Provider has accepted the payment but final confirmation is pending.
     * Common for async payment methods (bank transfers, wallets).
     * Requires webhook or polling for final status.
     */
    PENDING(false),
    
    /**
     * Payment completed successfully.
     * Customer has been charged, funds will be settled.
     * Terminal state - no further transitions allowed.
     */
    SUCCEEDED(true),
    
    /**
     * Payment failed permanently.
     * Could be due to decline, fraud, or exhausted retries.
     * Terminal state - no further transitions allowed.
     */
    FAILED(true),
    
    /**
     * System is retrying the payment after a transient failure.
     * Automatic retry with exponential backoff or provider failover.
     */
    RETRYING(false),
    
    /**
     * Payment was cancelled by user or system.
     * Terminal state - no further transitions allowed.
     */
    CANCELLED(true);
    
    /**
     * Validates if transition to target status is allowed.
     * 
     * @param target The target status to transition to
     * @return true if transition is valid, false otherwise
     */
    fun canTransitionTo(target: PaymentStatus): Boolean {
        // Terminal states cannot transition
        if (this.isTerminal) return false
        
        return when (this) {
            INITIATED -> target in setOf(ROUTING, CANCELLED)
            ROUTING -> target in setOf(PROCESSING, FAILED, CANCELLED)
            PROCESSING -> target in setOf(SUCCEEDED, FAILED, PENDING, RETRYING, CANCELLED)
            RETRYING -> target in setOf(PROCESSING, FAILED)
            PENDING -> target in setOf(SUCCEEDED, FAILED)
            else -> false
        }
    }
    
    companion object {
        /**
         * Returns all terminal statuses.
         */
        fun terminalStatuses(): Set<PaymentStatus> = 
            values().filter { it.isTerminal }.toSet()
        
        /**
         * Returns all active (non-terminal) statuses.
         */
        fun activeStatuses(): Set<PaymentStatus> = 
            values().filter { !it.isTerminal }.toSet()
    }
}


