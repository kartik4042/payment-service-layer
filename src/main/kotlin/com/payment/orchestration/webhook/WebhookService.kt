package com.payment.orchestration.webhook

import com.payment.orchestration.domain.model.PaymentStatus
import com.payment.orchestration.domain.model.Provider
import com.payment.orchestration.repository.PaymentRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Webhook Service
 * 
 * Processes webhooks from payment providers with security and idempotency guarantees.
 * 
 * Features:
 * - HMAC-SHA256 signature verification
 * - Replay attack protection (5-minute window)
 * - Duplicate webhook detection
 * - Transaction status updates
 * - Audit logging
 * 
 * Security:
 * - All webhooks must have valid HMAC signature
 * - Webhooks older than 5 minutes are rejected
 * - Constant-time signature comparison prevents timing attacks
 * 
 * @property paymentRepository Repository for payment data
 * @property objectMapper JSON object mapper
 * @property webhookSecrets Map of provider webhook secrets
 */
@Service
class WebhookService(
    private val paymentRepository: PaymentRepository,
    private val objectMapper: ObjectMapper,
    @Value("\${webhook.secrets.provider-a:secret_a}") private val providerASecret: String,
    @Value("\${webhook.secrets.provider-b:secret_b}") private val providerBSecret: String
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    
    private val webhookSecrets = mapOf(
        Provider.PROVIDER_A to providerASecret,
        Provider.PROVIDER_B to providerBSecret
    )
    
    // Store processed webhook IDs to prevent duplicates (in production, use Redis)
    private val processedWebhooks = mutableSetOf<String>()

    /**
     * Process webhook from payment provider
     * 
     * @param provider Payment provider
     * @param signature HMAC signature from provider
     * @param payload Raw webhook payload
     * @return Webhook processing result
     * @throws WebhookSignatureException if signature is invalid
     * @throws WebhookReplayException if webhook is too old
     * @throws WebhookDuplicateException if webhook already processed
     */
    fun processWebhook(
        provider: Provider,
        signature: String,
        payload: String
    ): WebhookResult {
        logger.info("Processing webhook from provider: ${provider.name}")

        // 1. Verify signature
        verifySignature(provider, signature, payload)

        // 2. Parse webhook payload
        val webhookData = parseWebhookPayload(payload)

        // 3. Check for replay attack
        checkReplayAttack(webhookData.timestamp)

        // 4. Check for duplicate
        checkDuplicate(webhookData.webhookId)

        // 5. Update transaction status
        val transactionId = updateTransactionStatus(
            transactionId = webhookData.transactionId,
            status = webhookData.status,
            provider = provider,
            webhookData = webhookData
        )

        // 6. Mark webhook as processed
        markAsProcessed(webhookData.webhookId)

        logger.info("Webhook processed successfully: ${webhookData.webhookId}, transaction: $transactionId")

        return WebhookResult(
            transactionId = transactionId,
            status = webhookData.status,
            webhookId = webhookData.webhookId
        )
    }

    /**
     * Verify HMAC signature
     * 
     * Uses constant-time comparison to prevent timing attacks.
     * 
     * @param provider Payment provider
     * @param signature Signature from webhook header
     * @param payload Raw webhook payload
     * @throws WebhookSignatureException if signature is invalid
     */
    private fun verifySignature(
        provider: Provider,
        signature: String,
        payload: String
    ) {
        val secret = webhookSecrets[provider]
            ?: throw IllegalStateException("No webhook secret configured for provider: ${provider.name}")

        // Calculate expected signature using HMAC-SHA256
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        val expectedSignature = mac.doFinal(payload.toByteArray())
            .joinToString("") { "%02x".format(it) }

        // Constant-time comparison to prevent timing attacks
        val isValid = MessageDigest.isEqual(
            signature.toByteArray(),
            expectedSignature.toByteArray()
        )

        if (!isValid) {
            logger.error("Invalid webhook signature from provider: ${provider.name}")
            throw WebhookSignatureException("Invalid webhook signature")
        }

        logger.debug("Webhook signature verified for provider: ${provider.name}")
    }

    /**
     * Parse webhook payload
     * 
     * @param payload Raw JSON payload
     * @return Parsed webhook data
     */
    private fun parseWebhookPayload(payload: String): WebhookData {
        return try {
            objectMapper.readValue<WebhookData>(payload)
        } catch (e: Exception) {
            logger.error("Failed to parse webhook payload", e)
            throw WebhookParseException("Invalid webhook payload format", e)
        }
    }

    /**
     * Check for replay attack
     * 
     * Rejects webhooks older than 5 minutes.
     * 
     * @param timestamp Webhook timestamp
     * @throws WebhookReplayException if webhook is too old
     */
    private fun checkReplayAttack(timestamp: Instant) {
        val now = Instant.now()
        val age = ChronoUnit.MINUTES.between(timestamp, now)

        if (age > 5) {
            logger.warn("Webhook replay detected: webhook is $age minutes old")
            throw WebhookReplayException("Webhook is too old: $age minutes")
        }

        if (timestamp.isAfter(now.plus(1, ChronoUnit.MINUTES))) {
            logger.warn("Webhook timestamp is in the future")
            throw WebhookReplayException("Webhook timestamp is in the future")
        }
    }

    /**
     * Check for duplicate webhook
     * 
     * @param webhookId Unique webhook ID
     * @throws WebhookDuplicateException if webhook already processed
     */
    private fun checkDuplicate(webhookId: String) {
        if (processedWebhooks.contains(webhookId)) {
            logger.info("Duplicate webhook detected: $webhookId")
            throw WebhookDuplicateException("Webhook already processed: $webhookId")
        }
    }

    /**
     * Update transaction status based on webhook
     * 
     * @param transactionId Transaction ID
     * @param status New payment status
     * @param provider Payment provider
     * @param webhookData Webhook data
     * @return Transaction ID
     */
    private fun updateTransactionStatus(
        transactionId: String,
        status: PaymentStatus,
        provider: Provider,
        webhookData: WebhookData
    ): String {
        val payment = paymentRepository.findById(transactionId)
            .orElseThrow { 
                logger.error("Transaction not found: $transactionId")
                WebhookProcessingException("Transaction not found: $transactionId")
            }

        // Validate provider matches
        if (payment.provider != provider) {
            logger.error("Provider mismatch: expected ${payment.provider}, got $provider")
            throw WebhookProcessingException("Provider mismatch")
        }

        // Update status
        val oldStatus = payment.status
        payment.status = status
        payment.updatedAt = Instant.now()

        // Add webhook metadata
        payment.metadata = payment.metadata?.toMutableMap()?.apply {
            put("webhook_id", webhookData.webhookId)
            put("webhook_timestamp", webhookData.timestamp.toString())
            put("webhook_event_type", webhookData.eventType)
        } ?: mutableMapOf(
            "webhook_id" to webhookData.webhookId,
            "webhook_timestamp" to webhookData.timestamp.toString(),
            "webhook_event_type" to webhookData.eventType
        )

        paymentRepository.save(payment)

        logger.info("Transaction status updated: $transactionId, $oldStatus -> $status")

        return transactionId
    }

    /**
     * Mark webhook as processed
     * 
     * @param webhookId Webhook ID
     */
    private fun markAsProcessed(webhookId: String) {
        processedWebhooks.add(webhookId)
        
        // In production, use Redis with TTL
        // redisTemplate.opsForValue().set("webhook:$webhookId", "processed", Duration.ofHours(24))
    }
}

/**
 * Webhook Data
 * 
 * Parsed webhook payload from payment provider.
 */
data class WebhookData(
    val webhookId: String,
    val transactionId: String,
    val status: PaymentStatus,
    val eventType: String,
    val timestamp: Instant,
    val providerTransactionId: String? = null,
    val amount: Long? = null,
    val currency: String? = null,
    val metadata: Map<String, Any>? = null
)

/**
 * Webhook Result
 * 
 * Result of webhook processing.
 */
data class WebhookResult(
    val transactionId: String,
    val status: PaymentStatus,
    val webhookId: String
)

/**
 * Exception thrown when webhook signature is invalid
 */
class WebhookSignatureException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * Exception thrown when webhook replay is detected
 */
class WebhookReplayException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * Exception thrown when duplicate webhook is detected
 */
class WebhookDuplicateException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * Exception thrown when webhook payload cannot be parsed
 */
class WebhookParseException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * Exception thrown when webhook processing fails
 */
class WebhookProcessingException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)


