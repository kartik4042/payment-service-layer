package com.payment.orchestration.webhook

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.payment.orchestration.domain.model.PaymentStatus
import com.payment.orchestration.domain.model.Provider
import com.payment.orchestration.repository.PaymentRepository
import com.payment.orchestration.repository.entity.PaymentEntity
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Webhook Service Tests
 * 
 * Tests webhook processing including:
 * - HMAC signature verification
 * - Replay attack protection
 * - Duplicate detection
 * - Transaction status updates
 * - Error handling
 */
class WebhookServiceTest {

    private lateinit var paymentRepository: PaymentRepository
    private lateinit var objectMapper: ObjectMapper
    private lateinit var webhookService: WebhookService

    private val providerASecret = "test_secret_provider_a"
    private val providerBSecret = "test_secret_provider_b"

    @BeforeEach
    fun setup() {
        paymentRepository = mockk()
        objectMapper = ObjectMapper().apply {
            registerModule(KotlinModule.Builder().build())
            registerModule(JavaTimeModule())
        }
        
        webhookService = WebhookService(
            paymentRepository = paymentRepository,
            objectMapper = objectMapper,
            providerASecret = providerASecret,
            providerBSecret = providerBSecret
        )
    }

    @AfterEach
    fun cleanup() {
        clearAllMocks()
    }

    // ========================================
    // Helper Methods
    // ========================================

    private fun generateHmacSignature(payload: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun createWebhookPayload(
        webhookId: String = "webhook_${UUID.randomUUID()}",
        transactionId: String = "txn_test123",
        status: PaymentStatus = PaymentStatus.SUCCEEDED,
        timestamp: Instant = Instant.now()
    ): String {
        val webhookData = WebhookData(
            webhookId = webhookId,
            transactionId = transactionId,
            status = status,
            eventType = "payment.succeeded",
            timestamp = timestamp,
            providerTransactionId = "provider_txn_123",
            amount = 10000,
            currency = "USD"
        )
        return objectMapper.writeValueAsString(webhookData)
    }

    private fun createPaymentEntity(
        transactionId: String = "txn_test123",
        status: PaymentStatus = PaymentStatus.PROCESSING,
        provider: Provider = Provider.PROVIDER_A
    ): PaymentEntity {
        return PaymentEntity(
            transactionId = transactionId,
            status = status,
            amount = 10000,
            currency = "USD",
            paymentMethod = "CARD",
            provider = provider,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )
    }

    // ========================================
    // Sanity Tests
    // ========================================

    @Test
    fun `should process valid webhook successfully`() {
        // Given
        val transactionId = "txn_test123"
        val payload = createWebhookPayload(transactionId = transactionId)
        val signature = generateHmacSignature(payload, providerASecret)
        
        val paymentEntity = createPaymentEntity(transactionId = transactionId)
        every { paymentRepository.findById(transactionId) } returns Optional.of(paymentEntity)
        every { paymentRepository.save(any()) } returns paymentEntity

        // When
        val result = webhookService.processWebhook(
            provider = Provider.PROVIDER_A,
            signature = signature,
            payload = payload
        )

        // Then
        assertEquals(transactionId, result.transactionId)
        assertEquals(PaymentStatus.SUCCEEDED, result.status)
        verify { paymentRepository.save(any()) }
    }

    @Test
    fun `should verify HMAC signature correctly`() {
        // Given
        val payload = createWebhookPayload()
        val validSignature = generateHmacSignature(payload, providerASecret)
        
        val paymentEntity = createPaymentEntity()
        every { paymentRepository.findById(any()) } returns Optional.of(paymentEntity)
        every { paymentRepository.save(any()) } returns paymentEntity

        // When/Then - Should not throw exception
        assertDoesNotThrow {
            webhookService.processWebhook(
                provider = Provider.PROVIDER_A,
                signature = validSignature,
                payload = payload
            )
        }
    }

    @Test
    fun `should update transaction status from webhook`() {
        // Given
        val transactionId = "txn_test123"
        val payload = createWebhookPayload(
            transactionId = transactionId,
            status = PaymentStatus.SUCCEEDED
        )
        val signature = generateHmacSignature(payload, providerASecret)
        
        val paymentEntity = createPaymentEntity(
            transactionId = transactionId,
            status = PaymentStatus.PROCESSING
        )
        every { paymentRepository.findById(transactionId) } returns Optional.of(paymentEntity)
        every { paymentRepository.save(any()) } answers {
            val saved = firstArg<PaymentEntity>()
            assertEquals(PaymentStatus.SUCCEEDED, saved.status)
            saved
        }

        // When
        webhookService.processWebhook(
            provider = Provider.PROVIDER_A,
            signature = signature,
            payload = payload
        )

        // Then
        verify { paymentRepository.save(match { it.status == PaymentStatus.SUCCEEDED }) }
    }

    // ========================================
    // Regression Tests - Signature Verification
    // ========================================

    @Test
    fun `should reject webhook with invalid signature`() {
        // Given
        val payload = createWebhookPayload()
        val invalidSignature = "invalid_signature_12345"

        // When/Then
        val exception = assertThrows<WebhookSignatureException> {
            webhookService.processWebhook(
                provider = Provider.PROVIDER_A,
                signature = invalidSignature,
                payload = payload
            )
        }

        assertTrue(exception.message!!.contains("Invalid webhook signature"))
        verify(exactly = 0) { paymentRepository.save(any()) }
    }

    @Test
    fun `should reject webhook with wrong provider secret`() {
        // Given
        val payload = createWebhookPayload()
        // Sign with Provider B secret but send to Provider A
        val signature = generateHmacSignature(payload, providerBSecret)

        // When/Then
        assertThrows<WebhookSignatureException> {
            webhookService.processWebhook(
                provider = Provider.PROVIDER_A,
                signature = signature,
                payload = payload
            )
        }
    }

    @Test
    fun `should use correct secret for each provider`() {
        // Given - Provider A webhook
        val payloadA = createWebhookPayload(transactionId = "txn_a")
        val signatureA = generateHmacSignature(payloadA, providerASecret)
        
        val paymentEntityA = createPaymentEntity(
            transactionId = "txn_a",
            provider = Provider.PROVIDER_A
        )
        every { paymentRepository.findById("txn_a") } returns Optional.of(paymentEntityA)
        every { paymentRepository.save(any()) } returns paymentEntityA

        // When - Process Provider A webhook
        assertDoesNotThrow {
            webhookService.processWebhook(
                provider = Provider.PROVIDER_A,
                signature = signatureA,
                payload = payloadA
            )
        }

        // Given - Provider B webhook
        val payloadB = createWebhookPayload(transactionId = "txn_b")
        val signatureB = generateHmacSignature(payloadB, providerBSecret)
        
        val paymentEntityB = createPaymentEntity(
            transactionId = "txn_b",
            provider = Provider.PROVIDER_B
        )
        every { paymentRepository.findById("txn_b") } returns Optional.of(paymentEntityB)
        every { paymentRepository.save(any()) } returns paymentEntityB

        // When - Process Provider B webhook
        assertDoesNotThrow {
            webhookService.processWebhook(
                provider = Provider.PROVIDER_B,
                signature = signatureB,
                payload = payloadB
            )
        }
    }

    // ========================================
    // Regression Tests - Replay Attack Protection
    // ========================================

    @Test
    fun `should reject webhook older than 5 minutes`() {
        // Given
        val oldTimestamp = Instant.now().minus(6, ChronoUnit.MINUTES)
        val payload = createWebhookPayload(timestamp = oldTimestamp)
        val signature = generateHmacSignature(payload, providerASecret)

        // When/Then
        val exception = assertThrows<WebhookReplayException> {
            webhookService.processWebhook(
                provider = Provider.PROVIDER_A,
                signature = signature,
                payload = payload
            )
        }

        assertTrue(exception.message!!.contains("too old"))
        verify(exactly = 0) { paymentRepository.save(any()) }
    }

    @Test
    fun `should accept webhook within 5 minute window`() {
        // Given
        val recentTimestamp = Instant.now().minus(4, ChronoUnit.MINUTES)
        val payload = createWebhookPayload(timestamp = recentTimestamp)
        val signature = generateHmacSignature(payload, providerASecret)
        
        val paymentEntity = createPaymentEntity()
        every { paymentRepository.findById(any()) } returns Optional.of(paymentEntity)
        every { paymentRepository.save(any()) } returns paymentEntity

        // When/Then - Should not throw exception
        assertDoesNotThrow {
            webhookService.processWebhook(
                provider = Provider.PROVIDER_A,
                signature = signature,
                payload = payload
            )
        }
    }

    @Test
    fun `should reject webhook with future timestamp`() {
        // Given
        val futureTimestamp = Instant.now().plus(2, ChronoUnit.MINUTES)
        val payload = createWebhookPayload(timestamp = futureTimestamp)
        val signature = generateHmacSignature(payload, providerASecret)

        // When/Then
        val exception = assertThrows<WebhookReplayException> {
            webhookService.processWebhook(
                provider = Provider.PROVIDER_A,
                signature = signature,
                payload = payload
            )
        }

        assertTrue(exception.message!!.contains("future"))
    }

    // ========================================
    // Regression Tests - Duplicate Detection
    // ========================================

    @Test
    fun `should detect duplicate webhook`() {
        // Given
        val webhookId = "webhook_duplicate_test"
        val payload = createWebhookPayload(webhookId = webhookId)
        val signature = generateHmacSignature(payload, providerASecret)
        
        val paymentEntity = createPaymentEntity()
        every { paymentRepository.findById(any()) } returns Optional.of(paymentEntity)
        every { paymentRepository.save(any()) } returns paymentEntity

        // When - Process webhook first time
        webhookService.processWebhook(
            provider = Provider.PROVIDER_A,
            signature = signature,
            payload = payload
        )

        // Then - Second attempt should throw duplicate exception
        assertThrows<WebhookDuplicateException> {
            webhookService.processWebhook(
                provider = Provider.PROVIDER_A,
                signature = signature,
                payload = payload
            )
        }

        // Verify save was called only once
        verify(exactly = 1) { paymentRepository.save(any()) }
    }

    @Test
    fun `should allow different webhooks with different IDs`() {
        // Given
        val payload1 = createWebhookPayload(
            webhookId = "webhook_1",
            transactionId = "txn_1"
        )
        val signature1 = generateHmacSignature(payload1, providerASecret)
        
        val payload2 = createWebhookPayload(
            webhookId = "webhook_2",
            transactionId = "txn_2"
        )
        val signature2 = generateHmacSignature(payload2, providerASecret)
        
        val paymentEntity1 = createPaymentEntity(transactionId = "txn_1")
        val paymentEntity2 = createPaymentEntity(transactionId = "txn_2")
        
        every { paymentRepository.findById("txn_1") } returns Optional.of(paymentEntity1)
        every { paymentRepository.findById("txn_2") } returns Optional.of(paymentEntity2)
        every { paymentRepository.save(any()) } returnsArgument 0

        // When/Then - Both should succeed
        assertDoesNotThrow {
            webhookService.processWebhook(
                provider = Provider.PROVIDER_A,
                signature = signature1,
                payload = payload1
            )
        }

        assertDoesNotThrow {
            webhookService.processWebhook(
                provider = Provider.PROVIDER_A,
                signature = signature2,
                payload = payload2
            )
        }

        verify(exactly = 2) { paymentRepository.save(any()) }
    }

    // ========================================
    // Regression Tests - Error Handling
    // ========================================

    @Test
    fun `should throw exception when transaction not found`() {
        // Given
        val payload = createWebhookPayload(transactionId = "txn_nonexistent")
        val signature = generateHmacSignature(payload, providerASecret)
        
        every { paymentRepository.findById("txn_nonexistent") } returns Optional.empty()

        // When/Then
        val exception = assertThrows<WebhookProcessingException> {
            webhookService.processWebhook(
                provider = Provider.PROVIDER_A,
                signature = signature,
                payload = payload
            )
        }

        assertTrue(exception.message!!.contains("Transaction not found"))
    }

    @Test
    fun `should throw exception when provider mismatch`() {
        // Given
        val payload = createWebhookPayload()
        val signature = generateHmacSignature(payload, providerASecret)
        
        // Payment belongs to Provider B but webhook is from Provider A
        val paymentEntity = createPaymentEntity(provider = Provider.PROVIDER_B)
        every { paymentRepository.findById(any()) } returns Optional.of(paymentEntity)

        // When/Then
        val exception = assertThrows<WebhookProcessingException> {
            webhookService.processWebhook(
                provider = Provider.PROVIDER_A,
                signature = signature,
                payload = payload
            )
        }

        assertTrue(exception.message!!.contains("Provider mismatch"))
    }

    @Test
    fun `should throw exception for invalid JSON payload`() {
        // Given
        val invalidPayload = "{ invalid json }"
        val signature = generateHmacSignature(invalidPayload, providerASecret)

        // When/Then
        assertThrows<WebhookParseException> {
            webhookService.processWebhook(
                provider = Provider.PROVIDER_A,
                signature = signature,
                payload = invalidPayload
            )
        }
    }

    // ========================================
    // Regression Tests - Metadata Updates
    // ========================================

    @Test
    fun `should add webhook metadata to transaction`() {
        // Given
        val webhookId = "webhook_metadata_test"
        val payload = createWebhookPayload(webhookId = webhookId)
        val signature = generateHmacSignature(payload, providerASecret)
        
        val paymentEntity = createPaymentEntity()
        every { paymentRepository.findById(any()) } returns Optional.of(paymentEntity)
        every { paymentRepository.save(any()) } answers {
            val saved = firstArg<PaymentEntity>()
            assertNotNull(saved.metadata)
            assertEquals(webhookId, saved.metadata!!["webhook_id"])
            assertTrue(saved.metadata!!.containsKey("webhook_timestamp"))
            assertTrue(saved.metadata!!.containsKey("webhook_event_type"))
            saved
        }

        // When
        webhookService.processWebhook(
            provider = Provider.PROVIDER_A,
            signature = signature,
            payload = payload
        )

        // Then
        verify { paymentRepository.save(any()) }
    }

    @Test
    fun `should preserve existing metadata when adding webhook data`() {
        // Given
        val payload = createWebhookPayload()
        val signature = generateHmacSignature(payload, providerASecret)
        
        val paymentEntity = createPaymentEntity().apply {
            metadata = mutableMapOf("existing_key" to "existing_value")
        }
        every { paymentRepository.findById(any()) } returns Optional.of(paymentEntity)
        every { paymentRepository.save(any()) } answers {
            val saved = firstArg<PaymentEntity>()
            assertEquals("existing_value", saved.metadata!!["existing_key"])
            assertTrue(saved.metadata!!.containsKey("webhook_id"))
            saved
        }

        // When
        webhookService.processWebhook(
            provider = Provider.PROVIDER_A,
            signature = signature,
            payload = payload
        )

        // Then
        verify { paymentRepository.save(any()) }
    }

    // ========================================
    // Regression Tests - Status Transitions
    // ========================================

    @Test
    fun `should update status from PROCESSING to SUCCEEDED`() {
        // Given
        val payload = createWebhookPayload(status = PaymentStatus.SUCCEEDED)
        val signature = generateHmacSignature(payload, providerASecret)
        
        val paymentEntity = createPaymentEntity(status = PaymentStatus.PROCESSING)
        every { paymentRepository.findById(any()) } returns Optional.of(paymentEntity)
        every { paymentRepository.save(any()) } answers { firstArg() }

        // When
        val result = webhookService.processWebhook(
            provider = Provider.PROVIDER_A,
            signature = signature,
            payload = payload
        )

        // Then
        assertEquals(PaymentStatus.SUCCEEDED, result.status)
        verify { paymentRepository.save(match { it.status == PaymentStatus.SUCCEEDED }) }
    }

    @Test
    fun `should update status from PROCESSING to FAILED`() {
        // Given
        val payload = createWebhookPayload(status = PaymentStatus.FAILED)
        val signature = generateHmacSignature(payload, providerASecret)
        
        val paymentEntity = createPaymentEntity(status = PaymentStatus.PROCESSING)
        every { paymentRepository.findById(any()) } returns Optional.of(paymentEntity)
        every { paymentRepository.save(any()) } answers { firstArg() }

        // When
        val result = webhookService.processWebhook(
            provider = Provider.PROVIDER_A,
            signature = signature,
            payload = payload
        )

        // Then
        assertEquals(PaymentStatus.FAILED, result.status)
        verify { paymentRepository.save(match { it.status == PaymentStatus.FAILED }) }
    }
}


