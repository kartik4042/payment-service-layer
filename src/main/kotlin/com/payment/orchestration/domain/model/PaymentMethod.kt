package com.payment.orchestration.domain.model

/**
 * Represents the type of payment method used for a transaction.
 * Each payment method has different processing characteristics and provider support.
 */
enum class PaymentMethod {
    /**
     * Credit or debit card payment.
     * Supports: Visa, Mastercard, Amex, Discover, etc.
     * Processing: Synchronous (immediate result)
     * Providers: Stripe, PayPal, Adyen, Braintree
     */
    CARD,
    
    /**
     * Bank account direct debit.
     * Supports: ACH (US), SEPA (EU), BACS (UK)
     * Processing: Asynchronous (2-5 business days)
     * Providers: Stripe, Adyen
     */
    BANK_ACCOUNT,
    
    /**
     * Digital wallet payment.
     * Supports: PayPal, Apple Pay, Google Pay, Venmo
     * Processing: Synchronous or asynchronous depending on wallet
     * Providers: PayPal, Stripe, Braintree
     */
    WALLET,
    
    /**
     * Unified Payments Interface (India).
     * Supports: UPI VPA-based payments
     * Processing: Synchronous (real-time)
     * Providers: Razorpay, PayU
     */
    UPI,
    
    /**
     * Alternative payment methods.
     * Supports: Klarna, Afterpay, Affirm, iDEAL, SEPA Direct Debit
     * Processing: Varies by method
     * Providers: Adyen, Stripe
     */
    ALTERNATIVE;
    
    /**
     * Indicates if this payment method typically processes synchronously.
     * 
     * @return true if payment result is immediate, false if async
     */
    fun isSynchronous(): Boolean = when (this) {
        CARD, UPI -> true
        BANK_ACCOUNT -> false
        WALLET, ALTERNATIVE -> false // Can be either, default to async
    }
    
    /**
     * Returns the typical settlement time for this payment method.
     * 
     * @return Settlement time in days
     */
    fun typicalSettlementDays(): Int = when (this) {
        CARD -> 2
        BANK_ACCOUNT -> 5
        WALLET -> 1
        UPI -> 1
        ALTERNATIVE -> 3
    }
}


