# Payment Orchestration System - API Design

## Document Information
- **Version**: 1.0.0
- **Last Updated**: 2026-04-09
- **Author**: API Design Team
- **Status**: Production Ready

---

## Table of Contents
1. [Overview](#1-overview)
2. [API Principles](#2-api-principles)
3. [Authentication](#3-authentication)
4. [Core Endpoints](#4-core-endpoints)
5. [Error Handling](#5-error-handling)
6. [Idempotency](#6-idempotency)
7. [Rate Limiting](#7-rate-limiting)
8. [Webhooks](#8-webhooks)
9. [Versioning](#9-versioning)
10. [Examples](#10-examples)

---

## 1. Overview

### 1.1 API Design Philosophy

The Payment Orchestration API follows RESTful principles with a focus on:
- **Simplicity**: Easy to understand and integrate
- **Consistency**: Predictable patterns across all endpoints
- **Reliability**: Idempotent operations and proper error handling
- **Security**: Authentication, encryption, and audit logging
- **Performance**: Optimized for high throughput (1000+ TPS)

### 1.2 Base URL

```
Production:  https://api.payment-orchestration.com/v1
Staging:     https://api-staging.payment-orchestration.com/v1
Development: http://localhost:8080/api/v1
```

### 1.3 Content Type

All requests and responses use JSON:
```
Content-Type: application/json
Accept: application/json
```

---

## 2. API Principles

### 2.1 RESTful Design

| Method | Usage | Idempotent |
|--------|-------|------------|
| GET | Retrieve resources | Yes |
| POST | Create resources | No (with idempotency key: Yes) |
| PUT | Update/Replace resources | Yes |
| PATCH | Partial update | No |
| DELETE | Remove resources | Yes |

### 2.2 Resource Naming

- Use plural nouns: `/payments`, `/transactions`
- Use kebab-case: `/bulk-retry`, `/health-check`
- Avoid verbs in URLs (use HTTP methods instead)
- Use sub-resources for relationships: `/payments/{id}/events`

### 2.3 HTTP Status Codes

| Code | Meaning | Usage |
|------|---------|-------|
| 200 | OK | Successful GET, PUT, PATCH |
| 201 | Created | Successful POST |
| 202 | Accepted | Async operation started |
| 204 | No Content | Successful DELETE |
| 400 | Bad Request | Invalid request data |
| 401 | Unauthorized | Missing/invalid authentication |
| 403 | Forbidden | Insufficient permissions |
| 404 | Not Found | Resource doesn't exist |
| 409 | Conflict | Idempotency conflict |
| 429 | Too Many Requests | Rate limit exceeded |
| 500 | Internal Server Error | Server error |
| 502 | Bad Gateway | Provider error |
| 503 | Service Unavailable | Service down |
| 504 | Gateway Timeout | Provider timeout |

---

## 3. Authentication

### 3.1 API Key Authentication

**Header Format**:
```http
Authorization: Bearer sk_live_abc123xyz789
```

**Example**:
```bash
curl -X POST https://api.payment-orchestration.com/v1/payments \
  -H "Authorization: Bearer sk_live_abc123xyz789" \
  -H "Content-Type: application/json" \
  -d '{"amount": 10000, "currency": "USD"}'
```

### 3.2 API Key Types

| Type | Prefix | Usage |
|------|--------|-------|
| Live | `sk_live_` | Production environment |
| Test | `sk_test_` | Testing/development |
| Restricted | `rk_live_` | Limited permissions |

### 3.3 JWT Token (Optional)

For user-specific operations:
```http
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

---

## 4. Core Endpoints

### 4.1 Create Payment

**Endpoint**: `POST /api/v1/payments`

**Description**: Process a payment through the orchestration system.

**Request Headers**:
```http
Content-Type: application/json
Authorization: Bearer sk_live_abc123xyz789
Idempotency-Key: unique_key_123
```

**Request Body**:
```json
{
  "amount": 10000,
  "currency": "USD",
  "customerId": "cust_123",
  "customerEmail": "customer@example.com",
  "paymentMethod": "CREDIT_CARD",
  "card": {
    "token": "tok_visa_4242",
    "last4": "4242",
    "brand": "visa",
    "expiryMonth": 12,
    "expiryYear": 2025
  },
  "billingAddress": {
    "line1": "123 Main St",
    "city": "San Francisco",
    "state": "CA",
    "postalCode": "94105",
    "country": "US"
  },
  "metadata": {
    "orderId": "order_456",
    "description": "Premium subscription",
    "customField": "value"
  }
}
```

**Response (201 Created)**:
```json
{
  "transactionId": "txn_abc123",
  "status": "SUCCEEDED",
  "provider": "STRIPE",
  "amount": 10000,
  "currency": "USD",
  "customerId": "cust_123",
  "paymentMethod": "CREDIT_CARD",
  "providerTransactionId": "ch_1234567890",
  "createdAt": "2026-04-09T05:00:00Z",
  "updatedAt": "2026-04-09T05:00:02Z",
  "completedAt": "2026-04-09T05:00:02Z",
  "metadata": {
    "orderId": "order_456",
    "description": "Premium subscription"
  }
}
```

**Field Descriptions**:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `amount` | integer | Yes | Amount in smallest currency unit (cents) |
| `currency` | string | Yes | ISO 4217 currency code (USD, EUR, GBP) |
| `customerId` | string | Yes | Unique customer identifier |
| `customerEmail` | string | No | Customer email for receipts |
| `paymentMethod` | enum | Yes | CREDIT_CARD, DEBIT_CARD, BANK_TRANSFER |
| `card.token` | string | Yes* | Tokenized card (from provider) |
| `billingAddress` | object | No | Billing address for verification |
| `metadata` | object | No | Custom key-value pairs (max 50 keys) |

*Required for card payments

---

### 4.2 Get Payment

**Endpoint**: `GET /api/v1/payments/{transactionId}`

**Description**: Retrieve payment details and status.

**Request**:
```bash
curl -X GET https://api.payment-orchestration.com/v1/payments/txn_abc123 \
  -H "Authorization: Bearer sk_live_abc123xyz789"
```

**Response (200 OK)**:
```json
{
  "transactionId": "txn_abc123",
  "status": "SUCCEEDED",
  "provider": "STRIPE",
  "amount": 10000,
  "currency": "USD",
  "customerId": "cust_123",
  "customerEmail": "customer@example.com",
  "paymentMethod": "CREDIT_CARD",
  "card": {
    "last4": "4242",
    "brand": "visa",
    "expiryMonth": 12,
    "expiryYear": 2025
  },
  "providerTransactionId": "ch_1234567890",
  "createdAt": "2026-04-09T05:00:00Z",
  "updatedAt": "2026-04-09T05:00:02Z",
  "completedAt": "2026-04-09T05:00:02Z",
  "events": [
    {
      "eventType": "PAYMENT_INITIATED",
      "timestamp": "2026-04-09T05:00:00Z",
      "details": {}
    },
    {
      "eventType": "PAYMENT_ROUTED",
      "provider": "STRIPE",
      "timestamp": "2026-04-09T05:00:01Z",
      "details": {
        "routingReason": "geographic_preference"
      }
    },
    {
      "eventType": "PAYMENT_SUCCEEDED",
      "timestamp": "2026-04-09T05:00:02Z",
      "details": {
        "providerTransactionId": "ch_1234567890"
      }
    }
  ],
  "metadata": {
    "orderId": "order_456",
    "description": "Premium subscription"
  }
}
```

---

### 4.3 List Payments

**Endpoint**: `GET /api/v1/payments`

**Description**: List payments with filtering and pagination.

**Query Parameters**:

| Parameter | Type | Description | Example |
|-----------|------|-------------|---------|
| `status` | string | Filter by status | `SUCCEEDED`, `FAILED` |
| `customerId` | string | Filter by customer | `cust_123` |
| `provider` | string | Filter by provider | `STRIPE`, `PAYPAL` |
| `startDate` | ISO 8601 | Start date filter | `2026-04-01T00:00:00Z` |
| `endDate` | ISO 8601 | End date filter | `2026-04-09T23:59:59Z` |
| `limit` | integer | Results per page (max 100) | `50` |
| `offset` | integer | Pagination offset | `0` |

**Request**:
```bash
curl -X GET "https://api.payment-orchestration.com/v1/payments?status=SUCCEEDED&limit=10&offset=0" \
  -H "Authorization: Bearer sk_live_abc123xyz789"
```

**Response (200 OK)**:
```json
{
  "data": [
    {
      "transactionId": "txn_abc123",
      "status": "SUCCEEDED",
      "amount": 10000,
      "currency": "USD",
      "provider": "STRIPE",
      "createdAt": "2026-04-09T05:00:00Z"
    }
  ],
  "pagination": {
    "total": 1250,
    "limit": 10,
    "offset": 0,
    "hasMore": true
  }
}
```

---

### 4.4 Provider Health Check

**Endpoint**: `GET /api/v1/health/providers`

**Description**: Check health status of all payment providers.

**Response (200 OK)**:
```json
{
  "providers": [
    {
      "provider": "STRIPE",
      "status": "HEALTHY",
      "uptime": 99.98,
      "lastCheckTime": "2026-04-09T05:00:00Z",
      "responseTime": 245,
      "circuitBreakerState": "CLOSED",
      "successRate": 99.5
    },
    {
      "provider": "PAYPAL",
      "status": "DEGRADED",
      "uptime": 98.5,
      "lastCheckTime": "2026-04-09T05:00:00Z",
      "responseTime": 1250,
      "circuitBreakerState": "HALF_OPEN",
      "successRate": 95.2
    }
  ]
}
```

---

### 4.5 Bulk Retry

**Endpoint**: `POST /api/v1/retry/bulk`

**Description**: Retry multiple failed payments in batch.

**Request Body**:
```json
{
  "statuses": ["FAILED"],
  "provider": "STRIPE",
  "startTime": "2026-04-08T00:00:00Z",
  "endTime": "2026-04-09T00:00:00Z",
  "batchSize": 100
}
```

**Response (202 Accepted)**:
```json
{
  "jobId": "bulk_retry_1234567890_5678",
  "message": "Bulk retry job started",
  "statusUrl": "/api/v1/retry/bulk/bulk_retry_1234567890_5678",
  "estimatedCount": 150
}
```

---

### 4.6 Get Bulk Retry Status

**Endpoint**: `GET /api/v1/retry/bulk/{jobId}`

**Response (200 OK)**:
```json
{
  "jobId": "bulk_retry_1234567890_5678",
  "status": "IN_PROGRESS",
  "totalCount": 150,
  "processedCount": 75,
  "successCount": 60,
  "failedCount": 15,
  "startedAt": "2026-04-09T05:00:00Z",
  "estimatedCompletionAt": "2026-04-09T05:15:00Z"
}
```

---

### 4.7 Get Audit Events

**Endpoint**: `GET /api/v1/audit/payments/{transactionId}/events`

**Description**: Retrieve complete audit trail for a payment.

**Response (200 OK)**:
```json
{
  "transactionId": "txn_abc123",
  "events": [
    {
      "eventId": "evt_001",
      "eventType": "PAYMENT_INITIATED",
      "timestamp": "2026-04-09T05:00:00Z",
      "actor": "api_client_123",
      "ipAddress": "192.168.1.100",
      "userAgent": "PaymentSDK/1.0",
      "payload": {
        "amount": 10000,
        "currency": "USD"
      }
    },
    {
      "eventId": "evt_002",
      "eventType": "PAYMENT_ROUTED",
      "timestamp": "2026-04-09T05:00:01Z",
      "provider": "STRIPE",
      "routingReason": "geographic_preference",
      "details": {
        "country": "US",
        "selectedProvider": "STRIPE",
        "fallbackProviders": ["PAYPAL", "ADYEN"]
      }
    }
  ]
}
```

---

## 5. Error Handling

### 5.1 Error Response Format

All errors follow a consistent structure:

```json
{
  "error": {
    "code": "validation_error",
    "message": "Invalid request parameters",
    "details": [
      {
        "field": "amount",
        "issue": "Amount must be greater than 0"
      }
    ],
    "requestId": "req_abc123",
    "timestamp": "2026-04-09T05:00:00Z"
  }
}
```

### 5.2 Error Categories

**validation_error**:
```yaml
description: "Request validation failed"
http_codes: [400]
examples:
  - invalid_amount
  - missing_required_field
  - invalid_currency
```

**authentication_error**:
```yaml
description: "Authentication failed"
http_codes: [401]
examples:
  - invalid_api_key
  - expired_token
  - missing_authorization
```

**authorization_error**:
```yaml
description: "Insufficient permissions"
http_codes: [403]
examples:
  - access_denied
  - insufficient_permissions
```

**payment_error**:
```yaml
description: "Payment processing errors"
http_codes: [402]
examples:
  - card_declined
  - insufficient_funds
  - invalid_card_number
```

**idempotency_error**:
```yaml
description: "Idempotency-related errors"
http_codes: [409]
examples:
  - idempotency_conflict
  - duplicate_request
```

**rate_limit_error**:
```yaml
description: "Rate limit exceeded"
http_codes: [429]
examples:
  - rate_limit_exceeded
  - too_many_requests
```

**provider_error**:
```yaml
description: "Payment provider errors"
http_codes: [502, 504]
examples:
  - provider_timeout
  - provider_unavailable
  - provider_error
```

**api_error**:
```yaml
description: "Internal API errors"
http_codes: [500, 503]
examples:
  - internal_error
  - service_unavailable
  - database_error
```

### 5.3 Standard Error Codes

| Error Code | HTTP Status | Description | Retry? |
|------------|-------------|-------------|--------|
| `authentication_failed` | 401 | Invalid API key or token | No |
| `invalid_api_key` | 401 | API key format is invalid | No |
| `expired_token` | 401 | JWT token has expired | No |
| `access_denied` | 403 | Insufficient permissions | No |
| `validation_error` | 400 | Request validation failed | No |
| `invalid_transaction_id` | 400 | Transaction ID format invalid | No |
| `missing_required_field` | 400 | Required field missing | No |
| `invalid_currency` | 400 | Currency not supported | No |
| `amount_too_small` | 400 | Amount below minimum | No |
| `amount_too_large` | 400 | Amount exceeds maximum | No |
| `card_declined` | 402 | Card payment declined | No |
| `insufficient_funds` | 402 | Insufficient funds | No |
| `invalid_card_number` | 402 | Card number invalid | No |
| `expired_card` | 402 | Card has expired | No |
| `incorrect_cvc` | 402 | CVC code incorrect | No |
| `card_not_supported` | 402 | Card type not supported | No |
| `idempotency_conflict` | 409 | Request already processing | Yes |
| `duplicate_request` | 409 | Duplicate idempotency key | Yes |
| `rate_limit_exceeded` | 429 | Too many requests | Yes |
| `provider_timeout` | 504 | Provider timeout | Yes |
| `provider_unavailable` | 503 | Provider unavailable | Yes |
| `provider_error` | 502 | Provider returned error | Maybe |
| `internal_error` | 500 | Internal server error | Yes |
| `service_unavailable` | 503 | Service unavailable | Yes |
| `database_error` | 500 | Database error | Yes |
| `resource_not_found` | 404 | Resource not found | No |

---

## 6. Idempotency

### 6.1 Idempotency Keys

Use idempotency keys to safely retry requests:

```http
POST /api/v1/payments
Idempotency-Key: unique_key_123
```

**Rules**:
- Keys must be unique per request
- Keys are valid for 24 hours
- Same key returns same response
- Use UUIDs or unique identifiers

**Example**:
```bash
# First request
curl -X POST https://api.payment-orchestration.com/v1/payments \
  -H "Authorization: Bearer sk_live_abc123xyz789" \
  -H "Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000" \
  -d '{"amount": 10000, "currency": "USD"}'

# Retry (returns same response)
curl -X POST https://api.payment-orchestration.com/v1/payments \
  -H "Authorization: Bearer sk_live_abc123xyz789" \
  -H "Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000" \
  -d '{"amount": 10000, "currency": "USD"}'
```

### 6.2 Conflict Handling

If request is still processing:

**Response (409 Conflict)**:
```json
{
  "error": {
    "code": "idempotency_conflict",
    "message": "Request with this idempotency key is still processing",
    "transactionId": "txn_abc123",
    "statusUrl": "/api/v1/payments/txn_abc123"
  }
}
```

---

## 7. Rate Limiting

### 7.1 Rate Limits

| Tier | Requests/Second | Requests/Hour |
|------|-----------------|---------------|
| Free | 10 | 1,000 |
| Basic | 50 | 10,000 |
| Pro | 200 | 50,000 |
| Enterprise | Custom | Custom |

### 7.2 Rate Limit Headers

```http
X-RateLimit-Limit: 200
X-RateLimit-Remaining: 150
X-RateLimit-Reset: 1617235200
```

### 7.3 Rate Limit Exceeded

**Response (429 Too Many Requests)**:
```json
{
  "error": {
    "code": "rate_limit_exceeded",
    "message": "Rate limit exceeded. Retry after 60 seconds",
    "retryAfter": 60
  }
}
```

---

## 8. Webhooks

### 8.1 Webhook Events

Subscribe to payment events:

| Event | Description |
|-------|-------------|
| `payment.initiated` | Payment started |
| `payment.succeeded` | Payment completed successfully |
| `payment.failed` | Payment failed |
| `payment.refunded` | Payment refunded |
| `payment.disputed` | Payment disputed/chargeback |

### 8.2 Webhook Payload

```json
{
  "id": "evt_abc123",
  "type": "payment.succeeded",
  "createdAt": "2026-04-09T05:00:00Z",
  "data": {
    "transactionId": "txn_abc123",
    "status": "SUCCEEDED",
    "amount": 10000,
    "currency": "USD",
    "provider": "STRIPE",
    "providerTransactionId": "ch_1234567890"
  }
}
```

### 8.3 Webhook Signature Verification

Verify webhook authenticity using HMAC-SHA256:

```kotlin
fun verifyWebhookSignature(
    payload: String,
    signature: String,
    secret: String
): Boolean {
    val mac = Mac.getInstance("HmacSHA256")
    val secretKey = SecretKeySpec(secret.toByteArray(), "HmacSHA256")
    mac.init(secretKey)
    val expectedSignature = mac.doFinal(payload.toByteArray())
        .joinToString("") { "%02x".format(it) }
    return signature == expectedSignature
}
```

---

## 9. Versioning

### 9.1 API Versions

- Current: `v1`
- Deprecated: None
- Sunset: None

### 9.2 Version Header

```http
API-Version: 2026-04-09
```

### 9.3 Breaking Changes

Breaking changes require new API version. Non-breaking changes:
- Adding new endpoints
- Adding optional fields
- Adding new event types
- Adding new error codes

---

## 10. Examples

### 10.1 Complete Payment Flow

```bash
# 1. Create payment
RESPONSE=$(curl -X POST https://api.payment-orchestration.com/v1/payments \
  -H "Authorization: Bearer sk_live_abc123xyz789" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{
    "amount": 10000,
    "currency": "USD",
    "customerId": "cust_123",
    "paymentMethod": "CREDIT_CARD",
    "card": {
      "token": "tok_visa_4242"
    }
  }')

# 2. Extract transaction ID
TRANSACTION_ID=$(echo $RESPONSE | jq -r '.transactionId')

# 3. Check payment status
curl -X GET "https://api.payment-orchestration.com/v1/payments/$TRANSACTION_ID" \
  -H "Authorization: Bearer sk_live_abc123xyz789"

# 4. Get audit trail
curl -X GET "https://api.payment-orchestration.com/v1/audit/payments/$TRANSACTION_ID/events" \
  -H "Authorization: Bearer sk_live_abc123xyz789"
```

### 10.2 Error Handling Example

```kotlin
suspend fun createPayment(request: CreatePaymentRequest): Result<Payment> {
    return try {
        val response = httpClient.post("/api/v1/payments") {
            header("Authorization", "Bearer $apiKey")
            header("Idempotency-Key", request.idempotencyKey)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        
        when (response.status) {
            HttpStatusCode.Created -> Result.success(response.body())
            HttpStatusCode.BadRequest -> {
                val error: ErrorResponse = response.body()
                Result.failure(ValidationException(error.message))
            }
            HttpStatusCode.Conflict -> {
                // Idempotency conflict - poll status
                val transactionId = response.body<ErrorResponse>().transactionId
                pollPaymentStatus(transactionId)
            }
            HttpStatusCode.TooManyRequests -> {
                val retryAfter = response.headers["Retry-After"]?.toInt() ?: 60
                delay(retryAfter * 1000L)
                createPayment(request) // Retry
            }
            else -> Result.failure(ApiException("Unexpected error"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}
```

---


**Last Updated**: 2026-04-09  
**Document Version**: 1.0.0
