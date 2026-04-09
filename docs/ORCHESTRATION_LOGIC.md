# Payment Orchestration System - Orchestration Logic

## Document Information
- **Version**: 1.0.0
- **Last Updated**: 2026-04-09
- **Author**: Backend Engineering Team
- **Status**: Production Ready

---

## Table of Contents
1. [Overview](#1-overview)
2. [Payment Flow](#2-payment-flow)
3. [Routing Logic](#3-routing-logic)
4. [Retry Strategy](#4-retry-strategy)
5. [Circuit Breaker](#5-circuit-breaker)
6. [Idempotency](#6-idempotency)
7. [Error Handling](#7-error-handling)
8. [State Management](#8-state-management)
9. [Webhook Processing](#9-webhook-processing)
10. [Code Examples](#10-code-examples)

---

## 1. Overview

### 1.1 Orchestration Purpose

The orchestration layer coordinates payment processing across multiple providers by:
- **Routing**: Selecting the optimal provider based on rules
- **Retry**: Automatically retrying failed payments
- **Failover**: Switching to backup providers on failure
- **Idempotency**: Preventing duplicate payments
- **Monitoring**: Tracking provider health and performance

### 1.2 Key Components

```
┌─────────────────────────────────────────────────────────┐
│              ORCHESTRATION COMPONENTS                    │
└─────────────────────────────────────────────────────────┘

PaymentOrchestrationService
├─ RoutingEngine (provider selection)
├─ RetryManager (retry logic)
├─ CircuitBreaker (failure isolation)
├─ IdempotencyService (duplicate prevention)
└─ EventPublisher (domain events)
```

---

## 2. Payment Flow

### 2.1 High-Level Flow

```mermaid
sequenceDiagram
    participant Client
    participant API
    participant Orchestrator
    participant Router
    participant CircuitBreaker
    participant Provider
    participant Database
    participant Cache

    Client->>API: POST /payments
    API->>API: Validate Request
    API->>Cache: Check Idempotency Key
    
    alt Key Exists (Completed)
        Cache-->>API: Return Cached Response
        API-->>Client: 200 OK (Cached)
    else Key Exists (Processing)
        Cache-->>API: 409 Conflict
        API-->>Client: 409 Conflict
    else New Request
        Cache->>Cache: Store Key (PROCESSING)
        API->>Orchestrator: Process Payment
        
        Orchestrator->>Database: Create Payment Record
        Orchestrator->>Router: Select Provider
        Router-->>Orchestrator: Provider: Stripe
        
        Orchestrator->>CircuitBreaker: Check State
        CircuitBreaker-->>Orchestrator: CLOSED (Healthy)
        
        Orchestrator->>Provider: Execute Payment
        
        alt Success
            Provider-->>Orchestrator: Success
            Orchestrator->>Database: Update Status: SUCCEEDED
            Orchestrator->>Cache: Update Key (COMPLETED)
            Orchestrator-->>API: Payment Result
            API-->>Client: 201 Created
        else Provider Failure
            Provider-->>Orchestrator: Timeout/Error
            Orchestrator->>Router: Get Fallback
            Router-->>Orchestrator: Provider: PayPal
            Orchestrator->>Provider: Retry with PayPal
            Provider-->>Orchestrator: Success
            Orchestrator->>Database: Update Status: SUCCEEDED
            Orchestrator->>Cache: Update Key (COMPLETED)
            Orchestrator-->>API: Payment Result
            API-->>Client: 201 Created
        end
    end
```

### 2.2 Detailed Step-by-Step Flow

**Step 1: Request Validation**
```kotlin
fun validatePaymentRequest(request: CreatePaymentRequest) {
    require(request.amount > 0) { "Amount must be positive" }
    require(request.currency.length == 3) { "Invalid currency code" }
    require(request.idempotencyKey.isNotBlank()) { "Idempotency key required" }
    require(request.customerId.isNotBlank()) { "Customer ID required" }
}
```

**Step 2: Idempotency Check**
```kotlin
fun checkIdempotency(key: String): IdempotencyResult {
    val existing = idempotencyService.get(key)
    
    return when (existing?.status) {
        "COMPLETED" -> IdempotencyResult.Completed(existing.response)
        "PROCESSING" -> IdempotencyResult.Conflict(existing.transactionId)
        null -> IdempotencyResult.New
        else -> IdempotencyResult.Failed
    }
}
```

**Step 3: Create Payment Record**
```kotlin
@Transactional
fun createPaymentRecord(request: CreatePaymentRequest): Payment {
    val payment = Payment(
        transactionId = generateTransactionId(),
        idempotencyKey = request.idempotencyKey,
        customerId = request.customerId,
        amount = request.amount,
        currency = request.currency,
        paymentMethod = request.paymentMethod,
        status = PaymentStatus.PENDING,
        retryCount = 0,
        maxRetries = 3
    )
    
    return paymentRepository.save(payment)
}
```

**Step 4: Provider Selection**
```kotlin
fun selectProvider(payment: Payment): Provider {
    val rules = routingEngine.getRoutingRules(payment)
    
    // Geographic routing
    val geoProvider = rules.geographicRules
        .firstOrNull { it.country == payment.customerCountry }
        ?.provider
    
    if (geoProvider != null && isProviderHealthy(geoProvider)) {
        return geoProvider
    }
    
    // Fallback to default provider
    return rules.defaultProvider
}
```

**Step 5: Circuit Breaker Check**
```kotlin
fun checkCircuitBreaker(provider: Provider): CircuitBreakerState {
    val state = circuitBreakerService.getState(provider)
    
    return when (state) {
        CircuitBreakerState.CLOSED -> {
            // Provider is healthy, proceed
            CircuitBreakerState.CLOSED
        }
        CircuitBreakerState.OPEN -> {
            // Provider is unhealthy, use fallback
            logger.warn("Circuit breaker OPEN for $provider")
            CircuitBreakerState.OPEN
        }
        CircuitBreakerState.HALF_OPEN -> {
            // Testing if provider recovered
            CircuitBreakerState.HALF_OPEN
        }
    }
}
```

**Step 6: Execute Payment**
```kotlin
suspend fun executePayment(payment: Payment, provider: Provider): PaymentResult {
    val startTime = System.currentTimeMillis()
    
    try {
        // Update status to PROCESSING
        paymentRepository.updateStatus(payment.id, PaymentStatus.PROCESSING)
        
        // Call provider API
        val response = providerConnector.processPayment(provider, payment)
        
        val duration = System.currentTimeMillis() - startTime
        
        // Record metrics
        metricsService.recordPaymentDuration(provider, duration)
        metricsService.incrementPaymentSuccess(provider)
        
        // Update payment record
        paymentRepository.update(payment.copy(
            status = PaymentStatus.SUCCEEDED,
            provider = provider,
            providerTransactionId = response.transactionId,
            completedAt = LocalDateTime.now()
        ))
        
        // Publish success event
        eventPublisher.publish(PaymentSucceededEvent(payment.id))
        
        return PaymentResult.Success(response)
        
    } catch (e: ProviderTimeoutException) {
        metricsService.incrementPaymentTimeout(provider)
        circuitBreakerService.recordFailure(provider)
        return PaymentResult.Timeout(e)
        
    } catch (e: ProviderException) {
        metricsService.incrementPaymentFailure(provider)
        circuitBreakerService.recordFailure(provider)
        return PaymentResult.Failed(e)
    }
}
```

**Step 7: Retry Logic**
```kotlin
suspend fun retryPayment(payment: Payment): PaymentResult {
    if (payment.retryCount >= payment.maxRetries) {
        return PaymentResult.MaxRetriesExceeded
    }
    
    // Exponential backoff
    val delayMs = calculateBackoff(payment.retryCount)
    delay(delayMs)
    
    // Get fallback provider
    val fallbackProvider = routingEngine.getFallbackProvider(payment)
    
    // Increment retry count
    paymentRepository.incrementRetryCount(payment.id)
    
    // Record retry attempt
    retryAttemptRepository.save(RetryAttempt(
        paymentId = payment.id,
        attemptNumber = payment.retryCount + 1,
        provider = fallbackProvider,
        attemptedAt = LocalDateTime.now()
    ))
    
    // Execute with fallback provider
    return executePayment(payment, fallbackProvider)
}

fun calculateBackoff(retryCount: Int): Long {
    val baseDelay = 1000L // 1 second
    val maxDelay = 10000L // 10 seconds
    val exponentialDelay = baseDelay * (2.0.pow(retryCount)).toLong()
    val jitter = Random.nextLong(0, 1000)
    
    return min(exponentialDelay + jitter, maxDelay)
}
```

---

## 3. Routing Logic

### 3.1 Geographic Routing

```kotlin
class GeographicRoutingEngine : RoutingEngine {
    
    private val countryProviderMap = mapOf(
        // North America
        "US" to Provider.STRIPE,
        "CA" to Provider.STRIPE,
        "MX" to Provider.PAYPAL,
        
        // Europe
        "GB" to Provider.STRIPE,
        "DE" to Provider.ADYEN,
        "FR" to Provider.ADYEN,
        "IT" to Provider.ADYEN,
        "ES" to Provider.ADYEN,
        
        // Asia Pacific
        "IN" to Provider.RAZORPAY,
        "SG" to Provider.STRIPE,
        "AU" to Provider.STRIPE,
        "JP" to Provider.STRIPE,
        "CN" to Provider.ALIPAY,
        
        // Latin America
        "BR" to Provider.MERCADOPAGO,
        "AR" to Provider.MERCADOPAGO,
        "CL" to Provider.PAYPAL,
        "CO" to Provider.PAYPAL
    )
    
    override fun selectProvider(payment: Payment): Provider {
        val country = payment.customerCountry ?: "US"
        
        // Get preferred provider for country
        val preferredProvider = countryProviderMap[country] ?: Provider.STRIPE
        
        // Check if provider is healthy
        if (isProviderHealthy(preferredProvider)) {
            logger.info("Selected $preferredProvider for country $country")
            return preferredProvider
        }
        
        // Fallback to global provider
        logger.warn("Preferred provider $preferredProvider unhealthy, using fallback")
        return getFallbackProvider(country)
    }
    
    private fun getFallbackProvider(country: String): Provider {
        val fallbackOrder = listOf(
            Provider.STRIPE,
            Provider.PAYPAL,
            Provider.ADYEN
        )
        
        return fallbackOrder.firstOrNull { isProviderHealthy(it) }
            ?: Provider.STRIPE // Last resort
    }
}
```

### 3.2 Cost-Based Routing

```kotlin
class CostBasedRoutingEngine : RoutingEngine {
    
    private val providerFees = mapOf(
        Provider.STRIPE to 0.029, // 2.9% + $0.30
        Provider.PAYPAL to 0.034, // 3.4% + $0.30
        Provider.ADYEN to 0.025   // 2.5% + $0.10
    )
    
    override fun selectProvider(payment: Payment): Provider {
        // Calculate cost for each provider
        val costs = providerFees.mapValues { (provider, feeRate) ->
            calculateCost(payment.amount, feeRate)
        }
        
        // Select cheapest healthy provider
        return costs.entries
            .filter { isProviderHealthy(it.key) }
            .minByOrNull { it.value }
            ?.key
            ?: Provider.STRIPE
    }
    
    private fun calculateCost(amount: Long, feeRate: Double): Double {
        val fixedFee = 30 // cents
        return (amount * feeRate) + fixedFee
    }
}
```

### 3.3 Performance-Based Routing

```kotlin
class PerformanceBasedRoutingEngine : RoutingEngine {
    
    override fun selectProvider(payment: Payment): Provider {
        val providers = listOf(Provider.STRIPE, Provider.PAYPAL, Provider.ADYEN)
        
        // Get performance metrics for each provider
        val metrics = providers.associateWith { provider ->
            ProviderMetrics(
                successRate = metricsService.getSuccessRate(provider),
                avgLatency = metricsService.getAverageLatency(provider),
                availability = metricsService.getAvailability(provider)
            )
        }
        
        // Calculate score (higher is better)
        val scores = metrics.mapValues { (_, metric) ->
            (metric.successRate * 0.5) +
            ((1000.0 / metric.avgLatency) * 0.3) +
            (metric.availability * 0.2)
        }
        
        // Select best performing provider
        return scores.maxByOrNull { it.value }?.key ?: Provider.STRIPE
    }
}

data class ProviderMetrics(
    val successRate: Double,
    val avgLatency: Double,
    val availability: Double
)
```

---

## 4. Retry Strategy

### 4.1 Exponential Backoff

```kotlin
class RetryManager {
    
    private val maxRetries = 3
    private val baseDelay = 1000L // 1 second
    private val maxDelay = 10000L // 10 seconds
    private val multiplier = 2.0
    
    suspend fun retryWithBackoff(
        payment: Payment,
        operation: suspend (Payment) -> PaymentResult
    ): PaymentResult {
        var lastError: Exception? = null
        
        repeat(maxRetries) { attempt ->
            try {
                val result = operation(payment)
                
                if (result is PaymentResult.Success) {
                    return result
                }
                
                // Calculate delay for next retry
                val delay = calculateDelay(attempt)
                logger.info("Retry attempt ${attempt + 1} failed, waiting ${delay}ms")
                delay(delay)
                
            } catch (e: Exception) {
                lastError = e
                logger.error("Retry attempt ${attempt + 1} failed", e)
            }
        }
        
        return PaymentResult.Failed(
            lastError ?: Exception("Max retries exceeded")
        )
    }
    
    private fun calculateDelay(attempt: Int): Long {
        val exponentialDelay = (baseDelay * multiplier.pow(attempt)).toLong()
        val jitter = Random.nextLong(0, 1000)
        return min(exponentialDelay + jitter, maxDelay)
    }
}
```

### 4.2 Retry Decision Logic

```kotlin
fun shouldRetry(error: PaymentError): Boolean {
    return when (error) {
        // Retry on transient errors
        is ProviderTimeoutException -> true
        is NetworkException -> true
        is ServiceUnavailableException -> true
        is RateLimitException -> true
        
        // Don't retry on permanent errors
        is CardDeclinedException -> false
        is InsufficientFundsException -> false
        is InvalidCardException -> false
        is AuthenticationException -> false
        
        // Maybe retry on provider errors
        is ProviderException -> error.isRetryable
        
        else -> false
    }
}
```

---

## 5. Circuit Breaker

### 5.1 Circuit Breaker States

```kotlin
enum class CircuitBreakerState {
    CLOSED,    // Normal operation
    OPEN,      // Failing, reject requests
    HALF_OPEN  // Testing recovery
}

class CircuitBreakerService {
    
    private val failureThreshold = 5
    private val successThreshold = 2
    private val timeout = Duration.ofSeconds(30)
    
    private val states = ConcurrentHashMap<Provider, CircuitBreakerState>()
    private val failureCounts = ConcurrentHashMap<Provider, AtomicInteger>()
    private val successCounts = ConcurrentHashMap<Provider, AtomicInteger>()
    private val lastFailureTime = ConcurrentHashMap<Provider, Instant>()
    
    fun getState(provider: Provider): CircuitBreakerState {
        val state = states.getOrDefault(provider, CircuitBreakerState.CLOSED)
        
        // Check if we should transition from OPEN to HALF_OPEN
        if (state == CircuitBreakerState.OPEN) {
            val lastFailure = lastFailureTime[provider]
            if (lastFailure != null && 
                Duration.between(lastFailure, Instant.now()) > timeout) {
                transitionTo(provider, CircuitBreakerState.HALF_OPEN)
                return CircuitBreakerState.HALF_OPEN
            }
        }
        
        return state
    }
    
    fun recordSuccess(provider: Provider) {
        val state = getState(provider)
        
        when (state) {
            CircuitBreakerState.HALF_OPEN -> {
                val successes = successCounts
                    .computeIfAbsent(provider) { AtomicInteger(0) }
                    .incrementAndGet()
                
                if (successes >= successThreshold) {
                    transitionTo(provider, CircuitBreakerState.CLOSED)
                    resetCounters(provider)
                }
            }
            CircuitBreakerState.CLOSED -> {
                // Reset failure count on success
                failureCounts[provider]?.set(0)
            }
            else -> {}
        }
    }
    
    fun recordFailure(provider: Provider) {
        val failures = failureCounts
            .computeIfAbsent(provider) { AtomicInteger(0) }
            .incrementAndGet()
        
        lastFailureTime[provider] = Instant.now()
        
        if (failures >= failureThreshold) {
            transitionTo(provider, CircuitBreakerState.OPEN)
            logger.error("Circuit breaker OPEN for $provider after $failures failures")
        }
    }
    
    private fun transitionTo(provider: Provider, newState: CircuitBreakerState) {
        val oldState = states.put(provider, newState)
        logger.info("Circuit breaker for $provider: $oldState -> $newState")
        
        // Publish event
        eventPublisher.publish(CircuitBreakerStateChangedEvent(
            provider = provider,
            oldState = oldState,
            newState = newState
        ))
    }
    
    private fun resetCounters(provider: Provider) {
        failureCounts[provider]?.set(0)
        successCounts[provider]?.set(0)
    }
}
```

---

## 6. Idempotency

### 6.1 Idempotency Implementation

```kotlin
class IdempotencyService(
    private val redisTemplate: RedisTemplate<String, String>,
    private val idempotencyRepository: IdempotencyRepository
) {
    
    private val ttl = Duration.ofHours(24)
    
    suspend fun checkAndStore(
        key: String,
        request: CreatePaymentRequest
    ): IdempotencyResult {
        // Check Redis first (fast path)
        val cached = redisTemplate.opsForValue().get(key)
        if (cached != null) {
            return parseIdempotencyRecord(cached)
        }
        
        // Check database (slow path)
        val existing = idempotencyRepository.findByKey(key)
        if (existing != null) {
            // Cache in Redis
            cacheIdempotencyRecord(key, existing)
            return IdempotencyResult.fromRecord(existing)
        }
        
        // Store new idempotency key
        val record = IdempotencyRecord(
            key = key,
            requestHash = hashRequest(request),
            requestPayload = objectMapper.writeValueAsString(request),
            status = "PROCESSING",
            expiresAt = LocalDateTime.now().plus(ttl)
        )
        
        idempotencyRepository.save(record)
        cacheIdempotencyRecord(key, record)
        
        return IdempotencyResult.New
    }
    
    suspend fun markCompleted(
        key: String,
        response: PaymentResponse
    ) {
        val record = idempotencyRepository.findByKey(key)
            ?: throw IllegalStateException("Idempotency key not found: $key")
        
        val updated = record.copy(
            status = "COMPLETED",
            responseStatus = 201,
            responsePayload = objectMapper.writeValueAsString(response),
            paymentId = response.transactionId
        )
        
        idempotencyRepository.save(updated)
        cacheIdempotencyRecord(key, updated)
    }
    
    private fun hashRequest(request: CreatePaymentRequest): String {
        val json = objectMapper.writeValueAsString(request)
        return MessageDigest.getInstance("SHA-256")
            .digest(json.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
    
    private fun cacheIdempotencyRecord(key: String, record: IdempotencyRecord) {
        val json = objectMapper.writeValueAsString(record)
        redisTemplate.opsForValue().set(key, json, ttl)
    }
}
```

---

## 7. Error Handling

### 7.1 Error Classification

```kotlin
sealed class PaymentError(message: String) : Exception(message) {
    // Transient errors (retry)
    class ProviderTimeout(provider: Provider) : 
        PaymentError("Provider $provider timed out")
    
    class NetworkError(cause: Throwable) : 
        PaymentError("Network error: ${cause.message}")
    
    class ServiceUnavailable(provider: Provider) : 
        PaymentError("Provider $provider unavailable")
    
    // Permanent errors (don't retry)
    class CardDeclined(reason: String) : 
        PaymentError("Card declined: $reason")
    
    class InsufficientFunds : 
        PaymentError("Insufficient funds")
    
    class InvalidCard(reason: String) : 
        PaymentError("Invalid card: $reason")
    
    // Business errors
    class ValidationError(field: String, reason: String) : 
        PaymentError("Validation error on $field: $reason")
    
    class IdempotencyConflict(transactionId: String) : 
        PaymentError("Request already processing: $transactionId")
}
```

### 7.2 Error Recovery Matrix

| Error Type | Retry? | Fallback Provider? | Client Action |
|------------|--------|-------------------|---------------|
| Provider Timeout | Yes (3x) | Yes | Wait and poll status |
| Network Error | Yes (3x) | Yes | Retry request |
| Service Unavailable | Yes (3x) | Yes | Retry after delay |
| Rate Limit | Yes (after delay) | No | Exponential backoff |
| Card Declined | No | No | Use different card |
| Insufficient Funds | No | No | Add funds |
| Invalid Card | No | No | Fix card details |
| Validation Error | No | No | Fix request |
| Idempotency Conflict | No | No | Poll transaction status |

---

## 8. State Management

### 8.1 Payment State Machine

```kotlin
enum class PaymentStatus {
    PENDING,      // Initial state
    PROCESSING,   // Being processed
    SUCCEEDED,    // Completed successfully
    FAILED,       // Failed permanently
    CANCELLED,    // Cancelled by user
    REFUNDED,     // Refunded after success
    DISPUTED      // Chargeback/dispute
}

class PaymentStateMachine {
    
    private val validTransitions = mapOf(
        PaymentStatus.PENDING to setOf(
            PaymentStatus.PROCESSING,
            PaymentStatus.CANCELLED
        ),
        PaymentStatus.PROCESSING to setOf(
            PaymentStatus.SUCCEEDED,
            PaymentStatus.FAILED,
            PaymentStatus.CANCELLED
        ),
        PaymentStatus.SUCCEEDED to setOf(
            PaymentStatus.REFUNDED,
            PaymentStatus.DISPUTED
        ),
        PaymentStatus.FAILED to emptySet(),
        PaymentStatus.CANCELLED to emptySet(),
        PaymentStatus.REFUNDED to emptySet(),
        PaymentStatus.DISPUTED to emptySet()
    )
    
    fun canTransition(from: PaymentStatus, to: PaymentStatus): Boolean {
        return validTransitions[from]?.contains(to) == true
    }
    
    fun transition(payment: Payment, newStatus: PaymentStatus): Payment {
        require(canTransition(payment.status, newStatus)) {
            "Invalid state transition: ${payment.status} -> $newStatus"
        }
        
        return payment.copy(
            status = newStatus,
            updatedAt = LocalDateTime.now()
        )
    }
}
```

---

## 9. Webhook Processing

### 9.1 Webhook Handler

```kotlin
class WebhookService {
    
    suspend fun processWebhook(
        provider: Provider,
        payload: String,
        signature: String
    ): WebhookResult {
        // Verify signature
        if (!verifySignature(provider, payload, signature)) {
            logger.error("Invalid webhook signature from $provider")
            return WebhookResult.InvalidSignature
        }
        
        // Parse webhook
        val event = parseWebhookEvent(provider, payload)
        
        // Find associated payment
        val payment = paymentRepository.findByProviderTransactionId(
            event.transactionId
        ) ?: return WebhookResult.PaymentNotFound
        
        // Process event
        return when (event.type) {
            "payment.succeeded" -> handlePaymentSucceeded(payment, event)
            "payment.failed" -> handlePaymentFailed(payment, event)
            "payment.refunded" -> handlePaymentRefunded(payment, event)
            else -> WebhookResult.UnknownEventType
        }
    }
    
    private fun verifySignature(
        provider: Provider,
        payload: String,
        signature: String
    ): Boolean {
        val secret = getWebhookSecret(provider)
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(secret.toByteArray(), "HmacSHA256")
        mac.init(secretKey)
        
        val expectedSignature = mac.doFinal(payload.toByteArray())
            .joinToString("") { "%02x".format(it) }
        
        return signature == expectedSignature
    }
}
```

---

## 10. Code Examples

### 10.1 Complete Payment Processing

```kotlin
@Service
class PaymentOrchestrationService(
    private val routingEngine: RoutingEngine,
    private val circuitBreakerService: CircuitBreakerService,
    private val idempotencyService: IdempotencyService,
    private val retryManager: RetryManager,
    private val paymentRepository: PaymentRepository,
    private val eventPublisher: EventPublisher
) {
    
    suspend fun processPayment(
        request: CreatePaymentRequest
    ): PaymentResponse {
        // 1. Check idempotency
        when (val result = idempotencyService.checkAndStore(
            request.idempotencyKey, request
        )) {
            is IdempotencyResult.Completed -> return result.response
            is IdempotencyResult.Conflict -> throw IdempotencyConflictException(
                result.transactionId
            )
            is IdempotencyResult.New -> {} // Continue
        }
        
        // 2. Create payment record
        val payment = createPaymentRecord(request)
        
        try {
            // 3. Select provider
            val provider = routingEngine.selectProvider(payment)
            
            // 4. Check circuit breaker
            val cbState = circuitBreakerService.getState(provider)
            if (cbState == CircuitBreakerState.OPEN) {
                // Use fallback provider
                val fallback = routingEngine.getFallbackProvider(payment)
                return executeWithRetry(payment, fallback)
            }
            
            // 5. Execute payment
            return executeWithRetry(payment, provider)
            
        } catch (e: Exception) {
            // Mark as failed
            paymentRepository.updateStatus(payment.id, PaymentStatus.FAILED)
            idempotencyService.markFailed(request.idempotencyKey)
            throw e
        }
    }
    
    private suspend fun executeWithRetry(
        payment: Payment,
        provider: Provider
    ): PaymentResponse {
        return retryManager.retryWithBackoff(payment) { p ->
            executePayment(p, provider)
        }.let { result ->
            when (result) {
                is PaymentResult.Success -> {
                    val response = result.toResponse()
                    idempotencyService.markCompleted(
                        payment.idempotencyKey,
                        response
                    )
                    response
                }
                else -> throw PaymentFailedException(result.error)
            }
        }
    }
}
```

---

**Last Updated**: 2026-04-09  
**Document Version**: 1.0.0