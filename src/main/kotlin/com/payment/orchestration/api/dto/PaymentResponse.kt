package com.payment.orchestration.api.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.payment.orchestration.domain.model.Payment
import com.payment.orchestration.domain.model.PaymentMethod
import com.payment.orchestration.domain.model.PaymentStatus
import com.payment.orchestration.domain.model.Provider
import java.time.Instant

/**
 * Response DTO for payment operations.
 * 
 * This DTO represents the API contract for payment responses.
 * Includes only non-sensitive information suitable for client consumption.
 * 
 * @JsonInclude(JsonInclude.Include.NON_NULL) excludes null fields from JSON response
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class PaymentResponse(
    /**
     * Unique transaction identifier.
     */
    @JsonProperty("transaction_id")
    val transactionId: String,
    
    /**
     * Current payment status.
     */
    val status: PaymentStatus,
    
    /**
     * Payment amount in smallest currency unit.
     */
    val amount: Long,
    
    /**
     * ISO 4217 currency code.
     */
    val currency: String,
    
    /**
     * Payment method summary (no sensitive data).
     */
    @JsonProperty("payment_method")
    val paymentMethod: PaymentMethodSummary,
    
    /**
     * Customer identifier (if provided).
     */
    @JsonProperty("customer_id")
    val customerId: String? = null,
    
    /**
     * Selected payment provider (if routed).
     */
    val provider: Provider? = null,
    
    /**
     * Provider's transaction identifier (if available).
     */
    @JsonProperty("provider_transaction_id")
    val providerTransactionId: String? = null,
    
    /**
     * Routing strategy used (if routed).
     */
    @JsonProperty("routing_strategy")
    val routingStrategy: String? = null,
    
    /**
     * Transaction creation timestamp (ISO 8601).
     */
    @JsonProperty("created_at")
    val createdAt: Instant,
    
    /**
     * Last update timestamp (ISO 8601).
     */
    @JsonProperty("updated_at")
    val updatedAt: Instant,
    
    /**
     * Completion timestamp for terminal states (ISO 8601).
     */
    @JsonProperty("completed_at")
    val completedAt: Instant? = null,
    
    /**
     * Custom metadata.
     */
    val metadata: Map<String, String>? = null,
    
    /**
     * Next action required from client (e.g., 3D Secure redirect).
     */
    @JsonProperty("next_action")
    val nextAction: NextAction? = null
) {
    companion object {
        /**
         * Creates a PaymentResponse from a Payment domain object.
         * 
         * @param payment The payment domain object
         * @return PaymentResponse DTO
         */
        fun fromPayment(payment: Payment): PaymentResponse {
            return PaymentResponse(
                transactionId = payment.transactionId,
                status = payment.status,
                amount = payment.amount,
                currency = payment.currency,
                paymentMethod = PaymentMethodSummary.fromPaymentMethod(
                    payment.paymentMethod
                ),
                customerId = payment.customerId,
                provider = payment.provider,
                providerTransactionId = payment.providerTransactionId,
                routingStrategy = payment.routingStrategy,
                createdAt = payment.createdAt,
                updatedAt = payment.updatedAt,
                completedAt = payment.completedAt,
                metadata = payment.metadata.takeIf { it.isNotEmpty() },
                nextAction = determineNextAction(payment)
            )
        }
        
        /**
         * Determines if any next action is required from the client.
         * 
         * @param payment The payment domain object
         * @return NextAction if action required, null otherwise
         */
        private fun determineNextAction(payment: Payment): NextAction? {
            return when (payment.status) {
                PaymentStatus.PENDING -> NextAction(
                    type = NextActionType.POLL,
                    pollIntervalSeconds = 5
                )
                // Add other next actions as needed (e.g., 3D Secure redirect)
                else -> null
            }
        }
    }
}

/**
 * Payment method summary for response (no sensitive data).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class PaymentMethodSummary(
    /**
     * Payment method type.
     */
    val type: PaymentMethod,
    
    /**
     * Card summary (if card payment).
     */
    val card: CardSummary? = null,
    
    /**
     * Wallet summary (if wallet payment).
     */
    val wallet: WalletSummary? = null
) {
    companion object {
        fun fromPaymentMethod(method: PaymentMethod): PaymentMethodSummary {
            return PaymentMethodSummary(
                type = method,
                // Card and wallet summaries would be populated from actual payment method details
                // For now, just return the type
                card = null,
                wallet = null
            )
        }
    }
}

/**
 * Card summary (no sensitive data).
 */
data class CardSummary(
    /**
     * Last 4 digits of card number.
     */
    val last4: String,
    
    /**
     * Card brand (e.g., "visa", "mastercard").
     */
    val brand: String,
    
    /**
     * Expiration month (1-12).
     */
    @JsonProperty("exp_month")
    val expMonth: Int,
    
    /**
     * Expiration year (YYYY).
     */
    @JsonProperty("exp_year")
    val expYear: Int
)

/**
 * Wallet summary.
 */
data class WalletSummary(
    /**
     * Wallet provider name.
     */
    val provider: String
)

/**
 * Next action required from client.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class NextAction(
    /**
     * Type of action required.
     */
    val type: NextActionType,
    
    /**
     * Redirect URL (if type is REDIRECT).
     */
    @JsonProperty("redirect_url")
    val redirectUrl: String? = null,
    
    /**
     * Recommended polling interval in seconds (if type is POLL).
     */
    @JsonProperty("poll_interval_seconds")
    val pollIntervalSeconds: Int? = null
)

/**
 * Types of next actions.
 */
enum class NextActionType {
    /**
     * Client should redirect user to URL (e.g., 3D Secure).
     */
    REDIRECT,
    
    /**
     * Client should poll transaction status.
     */
    POLL,
    
    /**
     * Client should authenticate user.
     */
    AUTHENTICATE
}


