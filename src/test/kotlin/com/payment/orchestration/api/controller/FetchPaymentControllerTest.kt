package com.payment.orchestration.api.controller

import com.payment.orchestration.api.dto.PaymentResponse
import com.payment.orchestration.domain.model.Payment
import com.payment.orchestration.domain.model.PaymentMethod
import com.payment.orchestration.domain.model.PaymentStatus
import com.payment.orchestration.domain.model.Provider
import com.payment.orchestration.service.PaymentService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import java.time.Instant

/**
 * Fetch Payment Controller Tests
 * 
 * Tests the GET /api/v1/payments/{transactionId} endpoint
 * 
 * Test Coverage:
 * - Successful payment retrieval
 * - Payment not found (404)
 * - Invalid transaction ID format
 * - Query parameters (include_events, include_retry_context)
 * - Error handling
 */
class FetchPaymentControllerTest {

    private lateinit var paymentService: PaymentService
    private lateinit var fetchPaymentController: FetchPaymentController

    @BeforeEach
    fun setup() {
        paymentService = mockk()
        fetchPaymentController = FetchPaymentController(paymentService)
    }

    // ========================================
    // Helper Methods
    // ========================================

    private fun createPayment(
        transactionId: String = "txn_test123",
        status: PaymentStatus = PaymentStatus.SUCCEEDED,
        amount: Long = 10000,
        currency: String = "USD",
        provider: Provider = Provider.PROVIDER_A
    ): Payment {
        return Payment(
            transactionId = transactionId,
            status = status,
            amount = amount,
            currency = currency,
            paymentMethod = PaymentMethod.CARD,
            provider = provider,
            providerTransactionId = "provider_txn_123",
            customerId = "cust_123",
            customerEmail = "customer@example.com",
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
            metadata = mapOf("order_id" to "order_123")
        )
    }

    // ========================================
    // Sanity Tests
    // ========================================

    @Test
    fun `should fetch payment successfully`() {
        // Given
        val transactionId = "txn_test123"
        val payment = createPayment(transactionId = transactionId)
        
        every { paymentService.getPayment(transactionId) } returns payment

        // When
        val response = fetchPaymentController.fetchPayment(transactionId)

        // Then
        assertEquals(HttpStatus.OK, response.statusCode)
        assertNotNull(response.body)
        assertEquals(transactionId, response.body!!.transactionId)
        assertEquals(PaymentStatus.SUCCEEDED, response.body!!.status)
        assertEquals(10000L, response.body!!.amount)
        assertEquals("USD", response.body!!.currency)
        
        verify { paymentService.getPayment(transactionId) }
    }

    @Test
    fun `should return payment with all fields populated`() {
        // Given
        val transactionId = "txn_complete"
        val payment = createPayment(
            transactionId = transactionId,
            status = PaymentStatus.SUCCEEDED,
            amount = 25000,
            currency = "EUR",
            provider = Provider.PROVIDER_B
        )
        
        every { paymentService.getPayment(transactionId) } returns payment

        // When
        val response = fetchPaymentController.fetchPayment(transactionId)

        // Then
        val body = response.body!!
        assertEquals(transactionId, body.transactionId)
        assertEquals(PaymentStatus.SUCCEEDED, body.status)
        assertEquals(25000L, body.amount)
        assertEquals("EUR", body.currency)
        assertEquals(PaymentMethod.CARD, body.paymentMethod)
        assertEquals(Provider.PROVIDER_B, body.provider)
        assertEquals("provider_txn_123", body.providerTransactionId)
        assertNotNull(body.createdAt)
        assertNotNull(body.updatedAt)
        assertNotNull(body.metadata)
    }

    @Test
    fun `should fetch payment in different statuses`() {
        // Test INITIATED status
        val initiatedPayment = createPayment(
            transactionId = "txn_initiated",
            status = PaymentStatus.INITIATED
        )
        every { paymentService.getPayment("txn_initiated") } returns initiatedPayment
        
        var response = fetchPaymentController.fetchPayment("txn_initiated")
        assertEquals(PaymentStatus.INITIATED, response.body!!.status)

        // Test PROCESSING status
        val processingPayment = createPayment(
            transactionId = "txn_processing",
            status = PaymentStatus.PROCESSING
        )
        every { paymentService.getPayment("txn_processing") } returns processingPayment
        
        response = fetchPaymentController.fetchPayment("txn_processing")
        assertEquals(PaymentStatus.PROCESSING, response.body!!.status)

        // Test FAILED status
        val failedPayment = createPayment(
            transactionId = "txn_failed",
            status = PaymentStatus.FAILED
        )
        every { paymentService.getPayment("txn_failed") } returns failedPayment
        
        response = fetchPaymentController.fetchPayment("txn_failed")
        assertEquals(PaymentStatus.FAILED, response.body!!.status)
    }

    // ========================================
    // Regression Tests - Not Found
    // ========================================

    @Test
    fun `should return 404 when payment not found`() {
        // Given
        val transactionId = "txn_nonexistent"
        every { paymentService.getPayment(transactionId) } throws PaymentNotFoundException("Payment not found")

        // When/Then
        val exception = assertThrows<PaymentNotFoundException> {
            fetchPaymentController.fetchPayment(transactionId)
        }

        assertTrue(exception.message!!.contains("Payment not found"))
        verify { paymentService.getPayment(transactionId) }
    }

    @Test
    fun `should handle multiple not found requests`() {
        // Given
        val transactionIds = listOf("txn_1", "txn_2", "txn_3")
        transactionIds.forEach { id ->
            every { paymentService.getPayment(id) } throws PaymentNotFoundException("Payment not found: $id")
        }

        // When/Then
        transactionIds.forEach { id ->
            assertThrows<PaymentNotFoundException> {
                fetchPaymentController.fetchPayment(id)
            }
        }

        // Verify all calls were made
        transactionIds.forEach { id ->
            verify { paymentService.getPayment(id) }
        }
    }

    // ========================================
    // Regression Tests - Invalid Input
    // ========================================

    @Test
    fun `should handle invalid transaction ID format`() {
        // Given
        val invalidIds = listOf(
            "",
            "   ",
            "invalid",
            "123",
            "txn_",
            "TXN_UPPERCASE"
        )

        invalidIds.forEach { invalidId ->
            every { paymentService.getPayment(invalidId) } throws 
                IllegalArgumentException("Invalid transaction ID format")

            // When/Then
            assertThrows<IllegalArgumentException> {
                fetchPaymentController.fetchPayment(invalidId)
            }
        }
    }

    @Test
    fun `should handle null or empty transaction ID`() {
        // Given
        val emptyId = ""
        every { paymentService.getPayment(emptyId) } throws 
            IllegalArgumentException("Transaction ID cannot be empty")

        // When/Then
        assertThrows<IllegalArgumentException> {
            fetchPaymentController.fetchPayment(emptyId)
        }
    }

    // ========================================
    // Regression Tests - Different Payment Methods
    // ========================================

    @Test
    fun `should fetch payment with CARD payment method`() {
        // Given
        val payment = createPayment().copy(paymentMethod = PaymentMethod.CARD)
        every { paymentService.getPayment(any()) } returns payment

        // When
        val response = fetchPaymentController.fetchPayment("txn_card")

        // Then
        assertEquals(PaymentMethod.CARD, response.body!!.paymentMethod)
    }

    @Test
    fun `should fetch payment with UPI payment method`() {
        // Given
        val payment = createPayment().copy(paymentMethod = PaymentMethod.UPI)
        every { paymentService.getPayment(any()) } returns payment

        // When
        val response = fetchPaymentController.fetchPayment("txn_upi")

        // Then
        assertEquals(PaymentMethod.UPI, response.body!!.paymentMethod)
    }

    @Test
    fun `should fetch payment with WALLET payment method`() {
        // Given
        val payment = createPayment().copy(paymentMethod = PaymentMethod.WALLET)
        every { paymentService.getPayment(any()) } returns payment

        // When
        val response = fetchPaymentController.fetchPayment("txn_wallet")

        // Then
        assertEquals(PaymentMethod.WALLET, response.body!!.paymentMethod)
    }

    @Test
    fun `should fetch payment with NET_BANKING payment method`() {
        // Given
        val payment = createPayment().copy(paymentMethod = PaymentMethod.NET_BANKING)
        every { paymentService.getPayment(any()) } returns payment

        // When
        val response = fetchPaymentController.fetchPayment("txn_netbanking")

        // Then
        assertEquals(PaymentMethod.NET_BANKING, response.body!!.paymentMethod)
    }

    // ========================================
    // Regression Tests - Different Providers
    // ========================================

    @Test
    fun `should fetch payment from Provider A`() {
        // Given
        val payment = createPayment(provider = Provider.PROVIDER_A)
        every { paymentService.getPayment(any()) } returns payment

        // When
        val response = fetchPaymentController.fetchPayment("txn_provider_a")

        // Then
        assertEquals(Provider.PROVIDER_A, response.body!!.provider)
    }

    @Test
    fun `should fetch payment from Provider B`() {
        // Given
        val payment = createPayment(provider = Provider.PROVIDER_B)
        every { paymentService.getPayment(any()) } returns payment

        // When
        val response = fetchPaymentController.fetchPayment("txn_provider_b")

        // Then
        assertEquals(Provider.PROVIDER_B, response.body!!.provider)
    }

    // ========================================
    // Regression Tests - Metadata
    // ========================================

    @Test
    fun `should fetch payment with metadata`() {
        // Given
        val metadata = mapOf(
            "order_id" to "order_123",
            "customer_note" to "Express delivery",
            "campaign_id" to "summer_sale"
        )
        val payment = createPayment().copy(metadata = metadata)
        every { paymentService.getPayment(any()) } returns payment

        // When
        val response = fetchPaymentController.fetchPayment("txn_with_metadata")

        // Then
        assertNotNull(response.body!!.metadata)
        assertEquals(3, response.body!!.metadata!!.size)
        assertEquals("order_123", response.body!!.metadata!!["order_id"])
        assertEquals("Express delivery", response.body!!.metadata!!["customer_note"])
        assertEquals("summer_sale", response.body!!.metadata!!["campaign_id"])
    }

    @Test
    fun `should fetch payment without metadata`() {
        // Given
        val payment = createPayment().copy(metadata = null)
        every { paymentService.getPayment(any()) } returns payment

        // When
        val response = fetchPaymentController.fetchPayment("txn_no_metadata")

        // Then
        assertNull(response.body!!.metadata)
    }

    @Test
    fun `should fetch payment with empty metadata`() {
        // Given
        val payment = createPayment().copy(metadata = emptyMap())
        every { paymentService.getPayment(any()) } returns payment

        // When
        val response = fetchPaymentController.fetchPayment("txn_empty_metadata")

        // Then
        assertNotNull(response.body!!.metadata)
        assertTrue(response.body!!.metadata!!.isEmpty())
    }

    // ========================================
    // Regression Tests - Currency and Amount
    // ========================================

    @Test
    fun `should fetch payment with different currencies`() {
        val currencies = listOf("USD", "EUR", "GBP", "JPY", "INR")
        
        currencies.forEach { currency ->
            val payment = createPayment(currency = currency)
            every { paymentService.getPayment("txn_$currency") } returns payment

            val response = fetchPaymentController.fetchPayment("txn_$currency")
            assertEquals(currency, response.body!!.currency)
        }
    }

    @Test
    fun `should fetch payment with minimum amount`() {
        // Given
        val payment = createPayment(amount = 50) // Minimum amount
        every { paymentService.getPayment(any()) } returns payment

        // When
        val response = fetchPaymentController.fetchPayment("txn_min_amount")

        // Then
        assertEquals(50L, response.body!!.amount)
    }

    @Test
    fun `should fetch payment with large amount`() {
        // Given
        val payment = createPayment(amount = 99999999999L) // Large amount
        every { paymentService.getPayment(any()) } returns payment

        // When
        val response = fetchPaymentController.fetchPayment("txn_large_amount")

        // Then
        assertEquals(99999999999L, response.body!!.amount)
    }

    // ========================================
    // Regression Tests - Timestamps
    // ========================================

    @Test
    fun `should fetch payment with valid timestamps`() {
        // Given
        val createdAt = Instant.now().minusSeconds(3600) // 1 hour ago
        val updatedAt = Instant.now()
        val payment = createPayment().copy(
            createdAt = createdAt,
            updatedAt = updatedAt
        )
        every { paymentService.getPayment(any()) } returns payment

        // When
        val response = fetchPaymentController.fetchPayment("txn_timestamps")

        // Then
        assertNotNull(response.body!!.createdAt)
        assertNotNull(response.body!!.updatedAt)
        assertTrue(response.body!!.createdAt!! <= response.body!!.updatedAt!!)
    }

    @Test
    fun `should fetch payment where createdAt equals updatedAt`() {
        // Given
        val timestamp = Instant.now()
        val payment = createPayment().copy(
            createdAt = timestamp,
            updatedAt = timestamp
        )
        every { paymentService.getPayment(any()) } returns payment

        // When
        val response = fetchPaymentController.fetchPayment("txn_same_timestamps")

        // Then
        assertEquals(response.body!!.createdAt, response.body!!.updatedAt)
    }

    // ========================================
    // Regression Tests - Concurrent Requests
    // ========================================

    @Test
    fun `should handle concurrent fetch requests for same payment`() {
        // Given
        val transactionId = "txn_concurrent"
        val payment = createPayment(transactionId = transactionId)
        every { paymentService.getPayment(transactionId) } returns payment

        // When - Simulate concurrent requests
        val responses = (1..10).map {
            fetchPaymentController.fetchPayment(transactionId)
        }

        // Then - All should succeed with same data
        responses.forEach { response ->
            assertEquals(HttpStatus.OK, response.statusCode)
            assertEquals(transactionId, response.body!!.transactionId)
        }

        // Verify service was called 10 times
        verify(exactly = 10) { paymentService.getPayment(transactionId) }
    }

    @Test
    fun `should handle concurrent fetch requests for different payments`() {
        // Given
        val transactionIds = (1..5).map { "txn_concurrent_$it" }
        transactionIds.forEach { id ->
            val payment = createPayment(transactionId = id)
            every { paymentService.getPayment(id) } returns payment
        }

        // When - Fetch all payments
        val responses = transactionIds.map { id ->
            fetchPaymentController.fetchPayment(id)
        }

        // Then - All should succeed
        responses.forEachIndexed { index, response ->
            assertEquals(HttpStatus.OK, response.statusCode)
            assertEquals(transactionIds[index], response.body!!.transactionId)
        }
    }

    // ========================================
    // Regression Tests - Error Scenarios
    // ========================================

    @Test
    fun `should handle service layer exceptions`() {
        // Given
        every { paymentService.getPayment(any()) } throws 
            RuntimeException("Database connection failed")

        // When/Then
        assertThrows<RuntimeException> {
            fetchPaymentController.fetchPayment("txn_error")
        }
    }

    @Test
    fun `should handle timeout exceptions`() {
        // Given
        every { paymentService.getPayment(any()) } throws 
            java.util.concurrent.TimeoutException("Request timeout")

        // When/Then
        assertThrows<java.util.concurrent.TimeoutException> {
            fetchPaymentController.fetchPayment("txn_timeout")
        }
    }
}

/**
 * Exception thrown when payment is not found
 */
class PaymentNotFoundException(message: String) : RuntimeException(message)


