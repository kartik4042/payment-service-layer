package com.payment.orchestration.api.dto

import com.fasterxml.jackson.annotation.JsonProperty
import com.payment.orchestration.domain.model.PaymentMethod
import jakarta.validation.Valid
import jakarta.validation.constraints.*

/**
 * Request DTO for creating a new payment.
 * 
 * This DTO represents the API contract for payment creation.
 * All fields are validated using Jakarta Bean Validation annotations.
 * 
 * Example JSON:
 * ```json
 * {
 *   "amount": 10000,
 *   "currency": "USD",
 *   "payment_method": {
 *     "type": "card",
 *     "card": {
 *       "token": "tok_visa_4242"
 *     }
 *   },
 *   "customer_id": "cust_123",
 *   "customer_email": "customer@example.com",
 *   "description": "Order #12345",
 *   "metadata": {
 *     "order_id": "ord_12345"
 *   }
 * }
 * ```
 */
data class CreatePaymentRequest(
    /**
     * Payment amount in smallest currency unit (cents for USD).
     * Must be at least 50 (e.g., $0.50) and not exceed 99,999,999,999 (e.g., $999,999,999.99).
     */
    @field:NotNull(message = "Amount is required")
    @field:Min(value = 50, message = "Amount must be at least 50")
    @field:Max(value = 99_999_999_999, message = "Amount exceeds maximum allowed")
    val amount: Long,
    
    /**
     * ISO 4217 currency code (e.g., "USD", "EUR", "GBP").
     * Must be exactly 3 uppercase letters.
     */
    @field:NotBlank(message = "Currency is required")
    @field:Pattern(
        regexp = "^[A-Z]{3}$",
        message = "Currency must be a valid ISO 4217 code (3 uppercase letters)"
    )
    val currency: String,
    
    /**
     * Payment method details.
     * Contains the type and method-specific information.
     */
    @field:NotNull(message = "Payment method is required")
    @field:Valid
    @JsonProperty("payment_method")
    val paymentMethod: PaymentMethodDto,
    
    /**
     * Optional customer identifier.
     * Used for routing decisions and customer tracking.
     */
    @field:Size(max = 100, message = "Customer ID must not exceed 100 characters")
    @JsonProperty("customer_id")
    val customerId: String? = null,
    
    /**
     * Optional customer email address.
     * Used for receipts and notifications.
     */
    @field:Email(message = "Invalid email format")
    @field:Size(max = 255, message = "Email must not exceed 255 characters")
    @JsonProperty("customer_email")
    val customerEmail: String? = null,
    
    /**
     * Optional payment description.
     * Appears on customer statements and internal records.
     */
    @field:Size(max = 500, message = "Description must not exceed 500 characters")
    val description: String? = null,
    
    /**
     * Optional statement descriptor.
     * Appears on customer's credit card statement.
     * Must be alphanumeric and max 22 characters.
     */
    @field:Size(max = 22, message = "Statement descriptor must not exceed 22 characters")
    @field:Pattern(
        regexp = "^[a-zA-Z0-9 ]*$",
        message = "Statement descriptor must be alphanumeric"
    )
    @JsonProperty("statement_descriptor")
    val statementDescriptor: String? = null,
    
    /**
     * Optional country code for routing decisions.
     * ISO 3166-1 alpha-2 code (e.g., "US", "IN", "GB").
     */
    @field:Pattern(
        regexp = "^[A-Z]{2}$",
        message = "Country must be a valid ISO 3166-1 alpha-2 code"
    )
    val country: String? = null,
    
    /**
     * Optional custom metadata (max 50 key-value pairs).
     * Keys max 40 characters, values max 500 characters.
     */
    @field:Size(max = 50, message = "Metadata cannot exceed 50 entries")
    val metadata: Map<String, String>? = null,
    
    /**
     * Optional webhook URL for async status updates.
     * Must be HTTPS for security.
     */
    @field:Pattern(
        regexp = "^https://.*",
        message = "Webhook URL must use HTTPS"
    )
    @JsonProperty("webhook_url")
    val webhookUrl: String? = null
) {
    init {
        // Additional validation for metadata keys and values
        metadata?.forEach { (key, value) ->
            require(key.length <= 40) {
                "Metadata key must not exceed 40 characters: $key"
            }
            require(value.length <= 500) {
                "Metadata value must not exceed 500 characters for key: $key"
            }
        }
    }
}

/**
 * Payment method DTO supporting multiple payment types.
 */
data class PaymentMethodDto(
    /**
     * Type of payment method.
     */
    @field:NotNull(message = "Payment method type is required")
    val type: PaymentMethod,
    
    /**
     * Card payment details (required if type is CARD).
     */
    val card: CardDto? = null,
    
    /**
     * Bank account details (required if type is BANK_ACCOUNT).
     */
    @JsonProperty("bank_account")
    val bankAccount: BankAccountDto? = null,
    
    /**
     * Wallet details (required if type is WALLET).
     */
    val wallet: WalletDto? = null,
    
    /**
     * UPI details (required if type is UPI).
     */
    val upi: UpiDto? = null,
    
    /**
     * Alternative payment method details (required if type is ALTERNATIVE).
     */
    val alternative: AlternativeDto? = null
) {
    init {
        // Validate that appropriate details are provided for the payment method type
        when (type) {
            PaymentMethod.CARD -> requireNotNull(card) {
                "Card details are required for card payments"
            }
            PaymentMethod.BANK_ACCOUNT -> requireNotNull(bankAccount) {
                "Bank account details are required for bank account payments"
            }
            PaymentMethod.WALLET -> requireNotNull(wallet) {
                "Wallet details are required for wallet payments"
            }
            PaymentMethod.UPI -> requireNotNull(upi) {
                "UPI details are required for UPI payments"
            }
            PaymentMethod.ALTERNATIVE -> requireNotNull(alternative) {
                "Alternative payment details are required"
            }
        }
    }
}

/**
 * Card payment details.
 */
data class CardDto(
    /**
     * Tokenized card (from provider's tokenization service).
     * Never send raw card numbers to the API.
     */
    @field:NotBlank(message = "Card token is required")
    @field:Pattern(
        regexp = "^tok_[a-zA-Z0-9_]+$",
        message = "Invalid card token format"
    )
    val token: String
)

/**
 * Bank account payment details.
 */
data class BankAccountDto(
    /**
     * Last 4 digits of account number (for display).
     */
    @field:Pattern(regexp = "^[0-9]{4}$", message = "Invalid account number format")
    @JsonProperty("account_number_last4")
    val accountNumberLast4: String? = null,
    
    /**
     * Bank routing number.
     */
    @JsonProperty("routing_number")
    val routingNumber: String? = null,
    
    /**
     * Account type.
     */
    @JsonProperty("account_type")
    val accountType: String? = null
)

/**
 * Wallet payment details.
 */
data class WalletDto(
    /**
     * Wallet provider (e.g., "paypal", "apple_pay", "google_pay").
     */
    @field:NotBlank(message = "Wallet provider is required")
    val provider: String,
    
    /**
     * Customer email for wallet.
     */
    @field:Email(message = "Invalid email format")
    val email: String? = null
)

/**
 * UPI payment details.
 */
data class UpiDto(
    /**
     * UPI Virtual Payment Address (VPA).
     */
    @field:NotBlank(message = "UPI VPA is required")
    @field:Pattern(
        regexp = "^[a-zA-Z0-9._-]+@[a-zA-Z0-9]+$",
        message = "Invalid UPI VPA format"
    )
    val vpa: String
)

/**
 * Alternative payment method details.
 */
data class AlternativeDto(
    /**
     * Alternative payment method type.
     */
    @field:NotBlank(message = "Alternative payment method is required")
    val method: String,
    
    /**
     * Method-specific details.
     */
    val details: Map<String, String>? = null
)


