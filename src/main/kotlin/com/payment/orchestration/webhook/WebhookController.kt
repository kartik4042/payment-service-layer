package com.payment.orchestration.webhook

import com.payment.orchestration.domain.model.Provider
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Webhook Controller
 * 
 * Handles incoming webhooks from payment providers.
 * 
 * Features:
 * - HMAC signature verification
 * - Replay attack protection
 * - Idempotent webhook processing
 * - Provider-specific webhook handling
 * 
 * Endpoints:
 * - POST /api/v1/webhooks/{provider} - Receive webhook from provider
 * 
 * Security:
 * - All webhooks must include valid HMAC signature
 * - Webhooks older than 5 minutes are rejected
 * - Duplicate webhooks are detected and ignored
 * 
 * @property webhookService Service for processing webhooks
 */
@RestController
@RequestMapping("/api/v1/webhooks")
class WebhookController(
    private val webhookService: WebhookService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Handle webhook from payment provider
     * 
     * @param provider Provider name (PROVIDER_A, PROVIDER_B)
     * @param signature HMAC signature from provider
     * @param payload Raw webhook payload
     * @return 200 OK if webhook processed successfully
     */
    @PostMapping("/{provider}")
    fun handleWebhook(
        @PathVariable provider: String,
        @RequestHeader("X-Signature") signature: String,
        @RequestBody payload: String
    ): ResponseEntity<WebhookResponse> {
        logger.info("Received webhook from provider: $provider")

        return try {
            // Parse provider enum
            val providerEnum = try {
                Provider.valueOf(provider.uppercase())
            } catch (e: IllegalArgumentException) {
                logger.error("Invalid provider: $provider")
                return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(WebhookResponse(
                        success = false,
                        message = "Invalid provider: $provider"
                    ))
            }

            // Process webhook
            val result = webhookService.processWebhook(
                provider = providerEnum,
                signature = signature,
                payload = payload
            )

            logger.info("Webhook processed successfully for provider: $provider, transactionId: ${result.transactionId}")

            ResponseEntity.ok(WebhookResponse(
                success = true,
                message = "Webhook processed successfully",
                transactionId = result.transactionId
            ))

        } catch (e: WebhookSignatureException) {
            logger.error("Invalid webhook signature from provider: $provider", e)
            ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(WebhookResponse(
                    success = false,
                    message = "Invalid webhook signature"
                ))

        } catch (e: WebhookReplayException) {
            logger.warn("Webhook replay detected from provider: $provider", e)
            ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(WebhookResponse(
                    success = false,
                    message = "Webhook replay detected"
                ))

        } catch (e: WebhookDuplicateException) {
            logger.info("Duplicate webhook from provider: $provider", e)
            ResponseEntity.ok(WebhookResponse(
                success = true,
                message = "Webhook already processed"
            ))

        } catch (e: Exception) {
            logger.error("Error processing webhook from provider: $provider", e)
            ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(WebhookResponse(
                    success = false,
                    message = "Internal server error"
                ))
        }
    }

    /**
     * Health check endpoint for webhook service
     */
    @GetMapping("/health")
    fun health(): ResponseEntity<Map<String, String>> {
        return ResponseEntity.ok(mapOf(
            "status" to "UP",
            "service" to "webhook"
        ))
    }
}

/**
 * Webhook Response
 */
data class WebhookResponse(
    val success: Boolean,
    val message: String,
    val transactionId: String? = null
)


