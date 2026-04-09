package com.payment.orchestration.api.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Standard error response DTO.
 * 
 * Provides consistent error format across all API endpoints.
 * Follows industry best practices for API error responses.
 * 
 * Example JSON:
 * ```json
 * {
 *   "error": {
 *     "code": "validation_error",
 *     "message": "Invalid request parameters",
 *     "type": "invalid_request_error",
 *     "details": [
 *       {
 *         "field": "amount",
 *         "message": "Amount must be at least 50",
 *         "code": "amount_too_small"
 *       }
 *     ],
 *     "request_id": "req_abc123def456",
 *     "documentation_url": "https://docs.payment-orchestration.com/errors/validation_error"
 *   }
 * }
 * ```
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ErrorResponse(
    /**
     * Error details.
     */
    val error: ErrorDetail
)

/**
 * Detailed error information.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ErrorDetail(
    /**
     * Machine-readable error code.
     * Used for programmatic error handling.
     */
    val code: String,
    
    /**
     * Human-readable error message.
     * Suitable for display to end users.
     */
    val message: String,
    
    /**
     * Error category/type.
     */
    val type: ErrorType,
    
    /**
     * Parameter that caused the error (if applicable).
     */
    val param: String? = null,
    
    /**
     * Detailed validation errors (for validation failures).
     */
    val details: List<ValidationError>? = null,
    
    /**
     * Request ID for debugging and support.
     */
    @JsonProperty("request_id")
    val requestId: String? = null,
    
    /**
     * Link to error documentation.
     */
    @JsonProperty("documentation_url")
    val documentationUrl: String? = null
)

/**
 * Validation error detail.
 */
data class ValidationError(
    /**
     * Field name that failed validation.
     */
    val field: String,
    
    /**
     * Validation error message.
     */
    val message: String,
    
    /**
     * Validation error code.
     */
    val code: String
)

/**
 * Error types/categories.
 */
enum class ErrorType {
    /**
     * Authentication failed (401).
     */
    @JsonProperty("authentication_error")
    AUTHENTICATION_ERROR,
    
    /**
     * Authorization failed (403).
     */
    @JsonProperty("authorization_error")
    AUTHORIZATION_ERROR,
    
    /**
     * Invalid request parameters (400).
     */
    @JsonProperty("invalid_request_error")
    INVALID_REQUEST_ERROR,
    
    /**
     * Card-related errors (402).
     */
    @JsonProperty("card_error")
    CARD_ERROR,
    
    /**
     * Idempotency-related errors (409).
     */
    @JsonProperty("idempotency_error")
    IDEMPOTENCY_ERROR,
    
    /**
     * Rate limit exceeded (429).
     */
    @JsonProperty("rate_limit_error")
    RATE_LIMIT_ERROR,
    
    /**
     * Payment provider errors (502, 504).
     */
    @JsonProperty("provider_error")
    PROVIDER_ERROR,
    
    /**
     * Internal API errors (500, 503).
     */
    @JsonProperty("api_error")
    API_ERROR
}


