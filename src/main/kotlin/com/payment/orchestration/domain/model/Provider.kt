package com.payment.orchestration.domain.model

/**
 * Represents payment service providers integrated with the orchestration system.
 * Each provider has different capabilities, fees, and geographic coverage.
 */
enum class Provider(
    val displayName: String,
    val supportedMethods: Set<PaymentMethod>,
    val supportedCurrencies: Set<String>
) {
    /**
     * Stripe - Global payment processor.
     * Best for: Card payments, global coverage, developer experience
     * Fees: 2.9% + $0.30 per transaction
     */
    STRIPE(
        displayName = "Stripe",
        supportedMethods = setOf(
            PaymentMethod.CARD,
            PaymentMethod.BANK_ACCOUNT,
            PaymentMethod.WALLET
        ),
        supportedCurrencies = setOf("USD", "EUR", "GBP", "CAD", "AUD", "JPY", "CHF", "CNY")
    ),
    
    /**
     * PayPal - Digital wallet and payment processor.
     * Best for: Wallet payments, buyer protection, global reach
     * Fees: 2.9% + $0.30 per transaction
     */
    PAYPAL(
        displayName = "PayPal",
        supportedMethods = setOf(
            PaymentMethod.WALLET,
            PaymentMethod.CARD
        ),
        supportedCurrencies = setOf("USD", "EUR", "GBP", "CAD", "AUD")
    ),
    
    /**
     * Adyen - Enterprise payment platform.
     * Best for: European payments, alternative methods, high volume
     * Fees: Custom pricing (typically 0.6% - 1.5%)
     */
    ADYEN(
        displayName = "Adyen",
        supportedMethods = setOf(
            PaymentMethod.CARD,
            PaymentMethod.BANK_ACCOUNT,
            PaymentMethod.ALTERNATIVE
        ),
        supportedCurrencies = setOf("USD", "EUR", "GBP", "JPY", "CHF")
    ),
    
    /**
     * Razorpay - Indian payment gateway.
     * Best for: Indian market, UPI, local payment methods
     * Fees: 2% per transaction
     */
    RAZORPAY(
        displayName = "Razorpay",
        supportedMethods = setOf(
            PaymentMethod.CARD,
            PaymentMethod.UPI,
            PaymentMethod.WALLET,
            PaymentMethod.BANK_ACCOUNT
        ),
        supportedCurrencies = setOf("INR")
    ),
    
    /**
     * Braintree - PayPal-owned payment processor.
     * Best for: Venmo, PayPal integration, mobile payments
     * Fees: 2.9% + $0.30 per transaction
     */
    BRAINTREE(
        displayName = "Braintree",
        supportedMethods = setOf(
            PaymentMethod.CARD,
            PaymentMethod.WALLET
        ),
        supportedCurrencies = setOf("USD", "EUR", "GBP", "AUD")
    );
    
    /**
     * Checks if this provider supports the given payment method.
     * 
     * @param method The payment method to check
     * @return true if supported, false otherwise
     */
    fun supportsPaymentMethod(method: PaymentMethod): Boolean =
        method in supportedMethods
    
    /**
     * Checks if this provider supports the given currency.
     * 
     * @param currency ISO 4217 currency code (e.g., "USD", "EUR")
     * @return true if supported, false otherwise
     */
    fun supportsCurrency(currency: String): Boolean =
        currency.uppercase() in supportedCurrencies
    
    /**
     * Checks if this provider can process a payment with given method and currency.
     * 
     * @param method The payment method
     * @param currency ISO 4217 currency code
     * @return true if both method and currency are supported
     */
    fun canProcess(method: PaymentMethod, currency: String): Boolean =
        supportsPaymentMethod(method) && supportsCurrency(currency)
    
    companion object {
        /**
         * Finds all providers that support a specific payment method.
         * 
         * @param method The payment method to filter by
         * @return Set of providers supporting the method
         */
        fun findByPaymentMethod(method: PaymentMethod): Set<Provider> =
            values().filter { it.supportsPaymentMethod(method) }.toSet()
        
        /**
         * Finds all providers that support a specific currency.
         * 
         * @param currency ISO 4217 currency code
         * @return Set of providers supporting the currency
         */
        fun findByCurrency(currency: String): Set<Provider> =
            values().filter { it.supportsCurrency(currency) }.toSet()
        
        /**
         * Finds all providers that can process a payment with given method and currency.
         * 
         * @param method The payment method
         * @param currency ISO 4217 currency code
         * @return Set of capable providers
         */
        fun findCapableProviders(method: PaymentMethod, currency: String): Set<Provider> =
            values().filter { it.canProcess(method, currency) }.toSet()
    }
}


