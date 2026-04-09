, log warning | Alert if Redis down > 5min |
| **Circuit Breaker Open** | Error rate > 50% in 5min window | Route to fallback provider | Payment succeeds with fallback | Alert immediately |
| **All Providers Failed** | All providers exhausted | Return 503 Service Unavailable | Client retries later | Page on-call immediately |
| **Invalid State Transition** | Concurrent status update | Reject update, log error | Transaction remains in valid state | Alert if frequent |
| **Duplicate Request (Processing)** | Idempotency key exists with "processing" | Return 409 Conflict | Client polls status | Track duplicate rate |
| **Duplicate Request (Completed)** | Idempotency key exists with "completed" | Return cached response | Client receives same result | Normal behavior |
| **Webhook Signature Invalid** | HMAC verification fails | Reject webhook, log security event | Manual investigation | Security alert |
| **Out-of-Order Webhook** | Webhook timestamp < last update | Ignore webhook | Transaction state unchanged | Log for debugging |
| **Database Deadlock** | Deadlock detected | Retry transaction 3x | Transaction succeeds on retry | Alert if frequent |
| **Insufficient Funds** | Provider returns insufficient_funds | Return 402 immediately | Client uses different payment method | Track by customer |

### 7.2 Detailed Failure Scenarios

#### Scenario 1: Provider Timeout

**Situation**: Payment provider doesn't respond within 5 seconds

**Detection**:
```kotlin
try {
    val response = httpClient.post(providerUrl) {
        contentType(ContentType.Application.Json)
        setBody(payload)
        timeout {
            requestTimeoutMillis = 5000
        }
    }
} catch (error: HttpRequestTimeoutException) {
    // Timeout detected
    handleProviderTimeout(error)
}
```

**Recovery Flow**:
```
1. Log timeout event
2. Update circuit breaker (increment failure count)
3. Check retry count
   - If < 3: Wait (exponential backoff), retry same provider
   - If = 3: Switch to fallback provider
4. If fallback succeeds: Return success
5. If all providers timeout: Return 503
```

**Expected Outcome**: 
- 80% of timeouts succeed on retry
- 15% succeed on fallback
- 5% fail completely (all providers timeout)

**Client Behavior**: Retry with exponential backoff

---

#### Scenario 2: Card Declined

**Situation**: Customer's card is declined by provider

**Detection**:
```kotlin
if (response.status == HttpStatusCode.PaymentRequired) {
    val errorCode = response.body<ErrorResponse>().error.code
    if (errorCode == "card_declined") {
        // Card declined
        handleCardDeclined(response)
    }
}
```

**Recovery Flow**:
```
1. Log decline event with reason
2. Update transaction status to FAILED
3. Record decline reason in transaction
4. DO NOT retry (non-retryable error)
5. Return 402 to client with decline reason
```

**Expected Outcome**: Client prompts user for different payment method

**Client Behavior**: Do not retry, use different card or payment method

---

#### Scenario 3: Database Unavailable

**Situation**: Primary database is down or unreachable

**Detection**:
```kotlin
try {
    transactionRepository.save(transaction)
} catch (error: DataAccessException) {
    // Database unavailable
    handleDatabaseUnavailable(error)
}
```

**Recovery Flow**:
```
1. Log database error
2. Retry connection 3x with 1s delay
3. If still failing:
   - Check if read replica available
   - If yes: Use read replica (read-only mode)
   - If no: Return 503 Service Unavailable
4. Alert operations team (page on-call)
5. Trigger database failover (if configured)
```

**Expected Outcome**: 
- Automatic failover to replica within 2 minutes
- Zero data loss (synchronous replication)

**Client Behavior**: Retry after 60 seconds

---

#### Scenario 4: Circuit Breaker Opens

**Situation**: Provider error rate exceeds 50% in 5-minute window

**Detection**:
```kotlin
val errorRate = calculateErrorRate(provider, windowSeconds = 300)
if (errorRate > 0.5) {
    // Open circuit breaker
    openCircuitBreaker(provider)
}
```

**Recovery Flow**:
```
1. Open circuit breaker for provider
2. Log circuit breaker event
3. Alert operations team
4. Route all new requests to fallback providers
5. After 30 seconds: Transition to HALF_OPEN
6. Allow one test request
   - If succeeds: Close circuit
   - If fails: Reopen circuit for another 30s
```

**Expected Outcome**: 
- Provider issues isolated
- Payments continue with fallback providers
- Automatic recovery when provider healthy

**Monitoring**: Dashboard shows circuit breaker state per provider

---

#### Scenario 5: All Providers Failed

**Situation**: Primary and all fallback providers have failed

**Detection**:
```kotlin
if (retryContext.attempts.size >= maxAttempts) {
    if (retryContext.attempts.all { it.status == "failed" }) {
        // All providers failed
        handleAllProvidersFailed()
    }
}
```

**Recovery Flow**:
```
1. Log critical error
2. Update transaction status to FAILED
3. Store all retry attempts for debugging
4. Return 503 Service Unavailable
5. Page on-call engineer immediately
6. Client should retry later (exponential backoff)
```

**Expected Outcome**: 
- Rare occurrence (< 0.01% of transactions)
- Indicates systemic issue
- Requires immediate investigation

**Client Behavior**: Retry after 5 minutes with exponential backoff

---

#### Scenario 6: Concurrent Status Updates

**Situation**: Two processes try to update transaction status simultaneously

**Detection**:
```kotlin
// Using optimistic locking with JPA @Version
try {
    val transaction = transactionRepository.findById(txnId)
        .orElseThrow { TransactionNotFoundException() }
    
    // JPA automatically checks version field
    transaction.status = newStatus
    transactionRepository.save(transaction)
} catch (error: OptimisticLockingFailureException) {
    // Concurrent update detected
    handleConcurrentUpdate()
}
```

**Recovery Flow**:
```
1. Detect version mismatch
2. Reload transaction with latest version
3. Validate if transition still valid
   - If yes: Retry update with new version
   - If no: Reject update, log warning
4. Return appropriate error to caller
```

**Expected Outcome**: 
- One update succeeds, others rejected
- Transaction remains in consistent state
- No data corruption

---

#### Scenario 7: Idempotency Key Collision

**Situation**: Two requests with same idempotency key arrive concurrently

**Detection**:
```kotlin
// Using Redis SETNX for atomic lock
val lockAcquired = redisTemplate.opsForValue().setIfAbsent(
    "idempotency:$key",
    "processing",
    Duration.ofDays(1)
) ?: false

if (!lockAcquired) {
    // Collision detected
    handleIdempotencyCollision()
}
```

**Recovery Flow**:
```
Request A:
1. Check Redis: Key not found
2. SETNX: Success (lock acquired)
3. Process payment
4. Update Redis with result

Request B (concurrent):
1. Check Redis: Key found (status: processing)
2. Return 409 Conflict
3. Client polls transaction status
4. Eventually receives result from Request A
```

**Expected Outcome**: 
- Exactly one payment processed
- No duplicate charges
- Second request gets cached result

**Client Behavior**: Poll transaction status every 2-5 seconds

---

#### Scenario 8: Webhook Replay Attack

**Situation**: Malicious actor replays a webhook to change transaction status

**Detection**:
```kotlin
fun verifyWebhookSignature(payload: String, signature: String, secret: String) {
    val mac = Mac.getInstance("HmacSHA256")
    val secretKey = SecretKeySpec(secret.toByteArray(), "HmacSHA256")
    mac.init(secretKey)
    
    val expectedSignature = mac.doFinal(payload.toByteArray())
        .joinToString("") { "%02x".format(it) }
    
    if (!MessageDigest.isEqual(signature.toByteArray(), expectedSignature.toByteArray())) {
        // Invalid signature - potential replay attack
        throw WebhookSignatureException("Invalid webhook signature")
    }
}
```

**Recovery Flow**:
```
1. Verify HMAC signature
2. If invalid:
   - Reject webhook (return 401)
   - Log security event
   - Alert security team
   - Block source IP if repeated
3. If valid:
   - Check webhook timestamp
   - Reject if > 5 minutes old (replay protection)
   - Process webhook
```

**Expected Outcome**: 
- Malicious webhooks rejected
- Transaction state protected
- Security team alerted

---

### 7.3 Recovery Procedures

#### Procedure 1: Manual Transaction Recovery

**When**: Transaction stuck in PROCESSING state for > 10 minutes

**Steps**:
```bash
# 1. Query transaction status
SELECT * FROM transactions WHERE transaction_id = 'txn_123';

# 2. Check provider status
curl -X GET https://api.stripe.com/v1/charges/ch_123 \
  -H "Authorization: Bearer sk_live_..."

# 3. Reconcile status
# If provider shows success but our DB shows processing:
UPDATE transactions 
SET status = 'SUCCEEDED',
    provider_transaction_id = 'ch_123',
    updated_at = NOW(),
    completed_at = NOW()
WHERE transaction_id = 'txn_123';

# 4. Create reconciliation event
INSERT INTO transaction_events (
    event_id, transaction_id, event_type,
    from_status, to_status, timestamp, reason, actor
) VALUES (
    'evt_manual_123', 'txn_123', 'reconciled',
    'PROCESSING', 'SUCCEEDED', NOW(),
    'Manual reconciliation after provider confirmation',
    'ops_team'
);

# 5. Notify customer
# Send email/webhook with updated status
```

---

#### Procedure 2: Circuit Breaker Manual Override

**When**: Circuit breaker stuck open but provider is healthy

**Steps**:
```bash
# 1. Check provider health
curl -X GET https://api.stripe.com/v1/health

# 2. Verify error rate
SELECT 
    COUNT(*) as total,
    SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) as failures
FROM transactions
WHERE provider = 'stripe'
  AND created_at > NOW() - INTERVAL '5 minutes';

# 3. If provider healthy, manually close circuit
redis-cli SET circuit_breaker:stripe:state "CLOSED"
redis-cli DEL circuit_breaker:stripe:failure_count
redis-cli DEL circuit_breaker:stripe:opened_at

# 4. Monitor next 10 transactions
# Ensure success rate returns to normal
```

---

#### Procedure 3: Bulk Transaction Retry

**When**: Provider outage caused multiple failures, need to retry

**Steps**:
```kotlin
// 1. Identify failed transactions during outage
val failedTransactions = transactionRepository.findByStatusAndProviderAndCreatedAtBetween(
    status = PaymentStatus.FAILED,
    provider = Provider.STRIPE,
    startTime = outageStartTime,
    endTime = outageEndTime
).filter { it.statusReason?.contains(Regex("timeout|unavailable")) == true }

// 2. Retry each transaction
failedTransactions.forEach { transaction ->
    try {
        // Reset status to INITIATED
        updateTransactionStatus(
            transactionId = transaction.id,
            toStatus = PaymentStatus.INITIATED,
            reason = "Bulk retry after provider outage"
        )
        
        // Reprocess payment
        val result = processPaymentRetry(transaction)
        
        // Log result
        logger.info("Retry result for ${transaction.id}: ${result.status}")
        
        // Rate limit: 10 retries per second
        Thread.sleep(100)
        
    } catch (error: Exception) {
        logger.error("Retry failed for ${transaction.id}: ${error.message}", error)
    }
}

// 3. Generate reconciliation report
// Show success/failure counts
```

---

### 7.4 Monitoring & Alerting

#### Key Metrics to Monitor

```yaml
# Prometheus metrics

# Request metrics
payment_requests_total{status, provider}
payment_request_duration_seconds{provider, percentile}
payment_errors_total{error_code, provider}

# Provider metrics
provider_success_rate{provider}
provider_latency_seconds{provider, percentile}
provider_circuit_breaker_state{provider}

# Retry metrics
payment_retry_attempts_total{provider, attempt_number}
payment_failover_total{from_provider, to_provider}

# Idempotency metrics
idempotency_cache_hits_total
idempotency_conflicts_total

# Database metrics
database_query_duration_seconds{query_type, percentile}
database_connection_pool_usage{state}
```

#### Alert Rules

```yaml
# alerts.yaml

groups:
  - name: payment_orchestration
    interval: 30s
    rules:
      # High error rate
      - alert: HighPaymentErrorRate
        expr: |
          rate(payment_errors_total[5m]) / rate(payment_requests_total[5m]) > 0.01
        for: 2m
        severity: critical
        annotations:
          summary: "Payment error rate above 1%"
          description: "Error rate: {{ $value | humanizePercentage }}"
      
      # Provider circuit breaker open
      - alert: ProviderCircuitBreakerOpen
        expr: provider_circuit_breaker_state{state="OPEN"} == 1
        for: 1m
        severity: high
        annotations:
          summary: "Circuit breaker open for {{ $labels.provider }}"
      
      # High latency
      - alert: HighPaymentLatency
        expr: |
          histogram_quantile(0.95, payment_request_duration_seconds) > 2.0
        for: 5m
        severity: warning
        annotations:
          summary: "P95 latency above 2 seconds"
      
      # Database connection pool exhausted
      - alert: DatabaseConnectionPoolExhausted
        expr: |
          database_connection_pool_usage{state="in_use"} / 
          database_connection_pool_usage{state="total"} > 0.9
        for: 2m
        severity: critical
        annotations:
          summary: "Database connection pool 90% utilized"
      
      # High retry rate
      - alert: HighRetryRate
        expr: |
          rate(payment_retry_attempts_total[5m]) / 
          rate(payment_requests_total[5m]) > 0.1
        for: 5m
        severity: warning
        annotations:
          summary: "Retry rate above 10%"
```

---

## 8. Summary

### 8.1 Key Orchestration Principles

✅ **Idempotency First**: Every request is idempotent by design  
✅ **Fail Fast**: Non-retryable errors return immediately  
✅ **Retry Smart**: Exponential backoff with circuit breakers  
✅ **Failover Gracefully**: Automatic fallback to healthy providers  
✅ **Persist Everything**: Complete audit trail of all operations  
✅ **Monitor Continuously**: Real-time metrics and alerting  
✅ **Recover Automatically**: Self-healing where possible  

### 8.2 Performance Characteristics

| Metric | Target | Typical | Worst Case |
|--------|--------|---------|------------|
| **Latency (P95)** | < 500ms | 350ms | 2000ms |
| **Success Rate** | > 99% | 99.5% | 98% |
| **Retry Rate** | < 5% | 2% | 10% |
| **Failover Rate** | < 2% | 0.5% | 5% |
| **Idempotency Hit Rate** | < 1% | 0.1% | 2% |

### 8.3 Operational Runbook

**Daily Operations**:
- Monitor dashboard for anomalies
- Review error logs for patterns
- Check circuit breaker states
- Verify provider health metrics

**Weekly Operations**:
- Review retry/failover trends
- Analyze routing effectiveness
- Update routing rules if needed
- Review capacity metrics

**Monthly Operations**:
- Conduct chaos engineering tests
- Review and update runbooks
- Analyze cost per provider
- Optimize routing strategies

**Incident Response**:
1. Check monitoring dashboard
2. Identify affected provider/component
3. Review recent deployments
4. Check provider status pages
5. Execute recovery procedure
6. Document incident
7. Conduct post-mortem

---

## 9. Testing Strategy

### 9.1 Unit Tests

```kotlin
// Test routing logic
@Test
fun `should route card payment to Razorpay for India`() {
    val context = RoutingContext(
        country = "IN",
        paymentMethodType = PaymentMethod.CARD,
        amount = BigDecimal("10000"),
        currency = "INR"
    )
    
    val decision = routingEngine.routePayment(context)
    
    assertEquals(Provider.RAZORPAY, decision.primaryProvider)
    assertTrue(decision.fallbackProviders.contains(Provider.STRIPE))
}

// Test retry logic
@Test
fun `should calculate exponential backoff correctly`() {
    val delays = listOf(
        retryManager.calculateExponentialBackoff(1),
        retryManager.calculateExponentialBackoff(2),
        retryManager.calculateExponentialBackoff(3)
    )
    
    assertEquals(listOf(1000L, 2000L, 4000L), delays)
}

// Test idempotency
@Test
fun `should detect duplicate request`() {
    val key = "test_key_123"
    
    // First request
    val result1 = idempotencyService.checkIdempotency(key, "client_123")
    assertFalse(result1.exists)
    
    // Acquire lock
    idempotencyService.acquireIdempotencyLock(key, "processing")
    
    // Second request (concurrent)
    val result2 = idempotencyService.checkIdempotency(key, "client_123")
    assertTrue(result2.exists)
    assertEquals("processing", result2.status)
}
```

### 9.2 Integration Tests

```kotlin
// Test end-to-end payment flow
@Test
fun `should process payment successfully end-to-end`() {
    val request = CreatePaymentRequest(
        amount = BigDecimal("10000"),
        currency = "USD",
        paymentMethod = PaymentMethodDto(
            type = PaymentMethod.CARD,
            card = CardDto(token = "tok_visa_4242")
        )
    )
    
    val response = orchestrationService.processPayment(request)
    
    assertEquals(PaymentStatus.SUCCEEDED, response.status)
    assertTrue(response.transactionId.startsWith("txn_"))
    
    // Verify database
    val transaction = transactionRepository.findById(response.transactionId).get()
    assertEquals(PaymentStatus.SUCCEEDED, transaction.status)
}

// Test failover
@Test
fun `should failover to backup provider on timeout`() {
    // Mock primary provider to timeout
    every { stripeConnector.processPayment(any()) } throws TimeoutException()
    
    val request = createTestPaymentRequest()
    val response = orchestrationService.processPayment(request)
    
    // Should succeed with fallback provider
    assertEquals(PaymentStatus.SUCCEEDED, response.status)
    assertNotEquals(Provider.STRIPE, response.provider)
    assertTrue(response.provider in listOf(Provider.PAYPAL, Provider.ADYEN))
}
```

### 9.3 Chaos Engineering Tests

```kotlin
// Test circuit breaker
@Test
fun `should open circuit breaker on high error rate`() {
    // Simulate 60% error rate
    repeat(100) { i ->
        if (i < 60) {
            simulateProviderError(Provider.STRIPE)
        } else {
            simulateProviderSuccess(Provider.STRIPE)
        }
    }
    
    // Circuit should be open
    val circuit = circuitBreakerRegistry.circuitBreaker(Provider.STRIPE.name)
    assertEquals(CircuitBreaker.State.OPEN, circuit.state)
    
    // New requests should use fallback
    val request = createTestPaymentRequest()
    val response = orchestrationService.processPayment(request)
    assertNotEquals(Provider.STRIPE, response.provider)
}

// Test database failover
@Test
fun `should failover to replica on database failure`() {
    // Simulate primary database failure
    simulateDatabaseFailure("primary")
    
    // Should failover to replica
    val request = createTestPaymentRequest()
    val response = orchestrationService.processPayment(request)
    
    // Should succeed (read from replica, write queued)
    assertTrue(response.status in listOf(PaymentStatus.SUCCEEDED, PaymentStatus.PENDING))
}
```

---

**Document Version**: 1.0.0  
**Last Updated**: 2026-04-09  
**Status**: Production Ready  
**Next Steps**: Implement orchestration logic, deploy to staging, conduct load testing