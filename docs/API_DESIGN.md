Idempotency-related errors"
  http_codes: [409]
  examples:
    - idempotency_conflict
    - duplicate_request

rate_limit_error:
  description: "Rate limit exceeded"
  http_codes: [429]
  examples:
    - rate_limit_exceeded
    - too_many_requests

provider_error:
  description: "Payment provider errors"
  http_codes: [502, 504]
  examples:
    - provider_timeout
    - provider_unavailable
    - provider_error

api_error:
  description: "Internal API errors"
  http_codes: [500, 503]
  examples:
    - internal_error
    - service_unavailable
    - database_error
```

### 4.2 Standard Error Codes

**Complete Error Code Reference**:

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

### 4.3 Error Response Best Practices

**Client Error Handling Guidelines**:

```typescript
// Example client-side error handling
async function createPayment(request: CreatePaymentRequest): Promise<Payment> {
  try {
    const response = await fetch('/api/v1/payments', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${apiKey}`,
        'Idempotency-Key': request.idempotency_key
      },
      body: JSON.stringify(request)
    });
    
    if (!response.ok) {
      const error = await response.json();
      
      switch (response.status) {
        case 400:
          // Validation error - fix request and retry
          throw new ValidationError(error);
        
        case 401:
          // Authentication error - refresh credentials
          throw new AuthenticationError(error);
        
        case 402:
          // Payment declined - prompt for different payment method
          throw new PaymentDeclinedError(error);
        
        case 409:
          // Idempotency conflict - poll transaction status
          return await pollTransactionStatus(error.transaction_id);
        
        case 429:
          // Rate limit - wait and retry
          await sleep(60000);
          return await createPayment(request);
        
        case 500:
        case 503:
        case 504:
          // Server/provider error - retry with exponential backoff
          return await retryWithBackoff(() => createPayment(request));
        
        default:
          throw new UnknownError(error);
      }
    }
    
    return await response.json();
  } catch (error) {
    // Handle network errors
    if (error instanceof NetworkError) {
      return await retryWithBackoff(() => createPayment(request));
    }
    throw error;
  }
}
```

---

## 5. Versioning & Compatibility

### 5.1 API Versioning Strategy

**Versioning Approach**: URL-based versioning

**Current Version**: v1

**Version Format**: `/api/v{major_version}/{resource}`

**Examples**:
- `/api/v1/payments`
- `/api/v2/payments` (future)

### 5.2 Backward Compatibility Rules

**Breaking Changes** (require new major version):
- Removing fields from response
- Changing field types
- Renaming fields
- Changing validation rules (more restrictive)
- Removing endpoints
- Changing authentication mechanism

**Non-Breaking Changes** (can be added to existing version):
- Adding new optional fields to request
- Adding new fields to response
- Adding new endpoints
- Adding new query parameters
- Relaxing validation rules
- Adding new error codes

### 5.3 Deprecation Policy

**Deprecation Timeline**:
1. **Announcement**: 6 months before deprecation
2. **Warning Headers**: Add deprecation warnings to responses
3. **Migration Period**: 6 months to migrate
4. **Sunset**: API version removed

**Deprecation Headers**:
```http
Deprecation: true
Sunset: Sat, 01 Jan 2027 00:00:00 GMT
Link: <https://docs.payment-orchestration.com/migration/v1-to-v2>; rel="deprecation"
```

### 5.4 Version Support Matrix

| Version | Status | Released | Deprecated | Sunset |
|---------|--------|----------|------------|--------|
| v1 | Current | 2026-04-09 | - | - |
| v2 | Planned | 2027-01-01 | - | - |

### 5.5 Field Evolution Strategy

**Adding Optional Fields** (Non-Breaking):
```json
// v1 Response
{
  "transaction_id": "txn_123",
  "status": "succeeded",
  "amount": 10000
}

// v1 Response (with new optional field)
{
  "transaction_id": "txn_123",
  "status": "succeeded",
  "amount": 10000,
  "fees": {  // NEW: Optional field added
    "total_fee": 320
  }
}
```

**Deprecating Fields** (Breaking - requires v2):
```json
// v1 Response
{
  "transaction_id": "txn_123",
  "status": "succeeded",
  "provider_id": "stripe"  // DEPRECATED
}

// v2 Response
{
  "transaction_id": "txn_123",
  "status": "succeeded",
  "provider": {  // NEW: Replaces provider_id
    "id": "stripe",
    "name": "Stripe",
    "type": "card_processor"
  }
}
```

### 5.6 Client Library Versioning

**Recommended Client Library Versions**:
- Node.js: `@payment-orchestration/node@^1.0.0`
- Python: `payment-orchestration==1.0.0`
- Java: `com.payment-orchestration:client:1.0.0`
- Ruby: `payment-orchestration ~> 1.0`

**Version Compatibility**:
```yaml
client_library_v1:
  compatible_api_versions: ["v1"]
  minimum_api_version: "v1.0.0"
  
client_library_v2:
  compatible_api_versions: ["v1", "v2"]
  minimum_api_version: "v1.0.0"
```

---

## 6. API Design Principles

### 6.1 RESTful Design

**Resource-Oriented**:
- Resources are nouns (payments, not createPayment)
- Use HTTP methods for actions (POST, GET, PUT, DELETE)
- Use HTTP status codes correctly

**URL Structure**:
```
/api/v1/payments                    # Collection
/api/v1/payments/{transaction_id}   # Individual resource
/api/v1/payments/{transaction_id}/refunds  # Sub-resource
```

### 6.2 Consistency

**Naming Conventions**:
- Use snake_case for field names
- Use lowercase for URLs
- Use plural nouns for collections
- Use consistent terminology across API

**Timestamp Format**:
- Always use ISO 8601 format
- Always use UTC timezone
- Format: `2026-04-09T05:00:00.000Z`

**Currency Amounts**:
- Always use smallest currency unit (cents)
- Always use integers (no decimals)
- Always include currency code

### 6.3 Security

**Authentication**:
- API keys for server-to-server
- JWT tokens for user-facing APIs
- Always use HTTPS (TLS 1.3)

**Data Protection**:
- Never return sensitive data (full card numbers, CVV)
- Mask sensitive data in logs
- Use tokenization for card data

**Rate Limiting**:
- Implement per-key rate limits
- Return rate limit headers
- Use 429 status code when exceeded

### 6.4 Performance

**Pagination** (for future list endpoints):
```http
GET /api/v1/payments?limit=100&starting_after=txn_123
```

**Filtering** (for future list endpoints):
```http
GET /api/v1/payments?status=succeeded&created_after=2026-04-01
```

**Field Selection** (for future optimization):
```http
GET /api/v1/payments/txn_123?fields=transaction_id,status,amount
```

---

## 7. OpenAPI Specification

### 7.1 OpenAPI 3.0 Schema (Excerpt)

```yaml
openapi: 3.0.3
info:
  title: Payment Orchestration API
  version: 1.0.0
  description: API for processing payments through multiple payment providers
  contact:
    name: API Support
    email: api-support@payment-orchestration.com
    url: https://docs.payment-orchestration.com

servers:
  - url: https://api.payment-orchestration.com/api/v1
    description: Production
  - url: https://sandbox.payment-orchestration.com/api/v1
    description: Sandbox

security:
  - ApiKeyAuth: []
  - BearerAuth: []

paths:
  /payments:
    post:
      summary: Create Payment
      operationId: createPayment
      tags:
        - Payments
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/CreatePaymentRequest'
      responses:
        '201':
          description: Payment created successfully
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/Payment'
        '400':
          description: Bad request
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/Error'
        '401':
          description: Unauthorized
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/Error'

  /payments/{transaction_id}:
    get:
      summary: Fetch Payment
      operationId: fetchPayment
      tags:
        - Payments
      parameters:
        - name: transaction_id
          in: path
          required: true
          schema:
            type: string
            pattern: '^txn_[a-zA-Z0-9]{10,}$'
        - name: include_events
          in: query
          schema:
            type: boolean
            default: false
        - name: include_retry_context
          in: query
          schema:
            type: boolean
            default: false
      responses:
        '200':
          description: Payment retrieved successfully
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/Payment'
        '404':
          description: Payment not found
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/Error'

components:
  securitySchemes:
    ApiKeyAuth:
      type: apiKey
      in: header
      name: Authorization
      description: API key authentication (format: Bearer sk_live_...)
    
    BearerAuth:
      type: http
      scheme: bearer
      bearerFormat: JWT

  schemas:
    CreatePaymentRequest:
      type: object
      required:
        - amount
        - currency
        - payment_method
      properties:
        amount:
          type: integer
          minimum: 50
          maximum: 99999999999
          description: Amount in smallest currency unit
        currency:
          type: string
          pattern: '^[A-Z]{3}$'
          enum: [USD, EUR, GBP, JPY, CAD, AUD, CHF, CNY, INR]
        payment_method:
          $ref: '#/components/schemas/PaymentMethod'
        idempotency_key:
          type: string
          maxLength: 255
        customer_id:
          type: string
          maxLength: 255
        customer_email:
          type: string
          format: email
          maxLength: 255
        metadata:
          type: object
          maxProperties: 50

    Payment:
      type: object
      properties:
        transaction_id:
          type: string
          pattern: '^txn_[a-zA-Z0-9]{10,}$'
        status:
          type: string
          enum: [initiated, routing, processing, pending, succeeded, failed, retrying, cancelled]
        amount:
          type: integer
        currency:
          type: string
        payment_method:
          $ref: '#/components/schemas/PaymentMethodSummary'
        provider:
          type: string
        provider_transaction_id:
          type: string
        created_at:
          type: string
          format: date-time
        updated_at:
          type: string
          format: date-time
        metadata:
          type: object

    Error:
      type: object
      required:
        - error
      properties:
        error:
          type: object
          required:
            - code
            - message
            - type
          properties:
            code:
              type: string
            message:
              type: string
            type:
              type: string
            param:
              type: string
            details:
              type: array
              items:
                type: object
            request_id:
              type: string
            documentation_url:
              type: string
```

---

## 8. Summary

### 8.1 Key Design Decisions

✅ **Domain Models**: Clear, well-defined models with validation rules  
✅ **Status Lifecycle**: 8-state state machine with clear transitions  
✅ **REST API**: Resource-oriented design following REST principles  
✅ **Error Handling**: Comprehensive error taxonomy with retry guidance  
✅ **Versioning**: URL-based versioning with clear compatibility rules  
✅ **Security**: API key authentication, data masking, rate limiting  
✅ **Idempotency**: Built-in support via headers or request body  
✅ **Backward Compatibility**: Clear rules for breaking vs non-breaking changes

### 8.2 API Maturity

**Current State**: Level 2 (HTTP Verbs + Status Codes)

**Future Enhancements**:
- Level 3: HATEOAS (Hypermedia controls)
- GraphQL endpoint for flexible queries
- Webhook management API
- Batch payment processing
- Subscription management

### 8.3 Documentation

**Available Resources**:
- API Reference: https://docs.payment-orchestration.com/api
- OpenAPI Spec: https://api.payment-orchestration.com/openapi.json
- Postman Collection: https://docs.payment-orchestration.com/postman
- Client Libraries: https://docs.payment-orchestration.com/libraries
- Migration Guides: https://docs.payment-orchestration.com/migration

---

**Document Version**: 1.0.0  
**Last Updated**: 2026-04-09  
**Status**: Ready for Implementation  
**Next Steps**: Generate OpenAPI spec, implement API endpoints, create client libraries