# Payment Orchestration System - SRE Metrics & Monitoring

## Document Information
- **Version**: 1.0.0
- **Last Updated**: 2026-04-09
- **Author**: SRE Team
- **Status**: Production Ready

---

## Table of Contents
1. [Overview](#1-overview)
2. [Golden Signals](#2-golden-signals)
3. [Service Level Objectives (SLOs)](#3-service-level-objectives-slos)
4. [Key Metrics](#4-key-metrics)
5. [Alerting Rules](#5-alerting-rules)
6. [Dashboards](#6-dashboards)
7. [Distributed Tracing](#7-distributed-tracing)
8. [Log Aggregation](#8-log-aggregation)
9. [Incident Response](#9-incident-response)
10. [Capacity Planning](#10-capacity-planning)

---

## 1. Overview

### 1.1 Observability Stack

```
┌─────────────────────────────────────────────────────────┐
│              OBSERVABILITY ARCHITECTURE                  │
└─────────────────────────────────────────────────────────┘

Application
├─ Metrics (Prometheus)
│  ├─ Golden Signals
│  ├─ Business Metrics
│  └─ Infrastructure Metrics
│
├─ Traces (Zipkin)
│  ├─ Request tracing
│  ├─ Span analysis
│  └─ Dependency mapping
│
└─ Logs (ELK Stack)
   ├─ Application logs
   ├─ Access logs
   └─ Error logs

Visualization
├─ Grafana (Dashboards)
├─ Kibana (Log analysis)
└─ Zipkin UI (Trace analysis)

Alerting
├─ Prometheus Alertmanager
├─ PagerDuty
└─ Slack
```

### 1.2 Monitoring Goals

- **Availability**: 99.95% uptime SLO
- **Latency**: P95 < 500ms, P99 < 1000ms
- **Throughput**: Support 1000+ TPS
- **Error Rate**: < 0.1% error rate
- **Data Accuracy**: 100% payment accuracy

---

## 2. Golden Signals

### 2.1 Latency

**Definition**: Time to process a payment request

**Metrics**:
```promql
# Request duration histogram
payment_request_duration_seconds_bucket

# P50, P95, P99 latencies
histogram_quantile(0.50, payment_request_duration_seconds_bucket)
histogram_quantile(0.95, payment_request_duration_seconds_bucket)
histogram_quantile(0.99, payment_request_duration_seconds_bucket)

# Average latency by provider
avg(payment_request_duration_seconds) by (provider)
```

**Targets**:
- P50: < 200ms
- P95: < 500ms
- P99: < 1000ms
- P99.9: < 2000ms

**Kotlin Implementation**:
```kotlin
@Service
class MetricsService(
    private val meterRegistry: MeterRegistry
) {
    
    fun recordPaymentDuration(provider: Provider, durationMs: Long) {
        Timer.builder("payment.request.duration")
            .tag("provider", provider.name)
            .tag("status", "success")
            .register(meterRegistry)
            .record(durationMs, TimeUnit.MILLISECONDS)
    }
    
    fun recordProviderLatency(provider: Provider, latencyMs: Long) {
        DistributionSummary.builder("provider.api.latency")
            .tag("provider", provider.name)
            .baseUnit("milliseconds")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(meterRegistry)
            .record(latencyMs.toDouble())
    }
}
```

### 2.2 Traffic

**Definition**: Request rate (requests per second)

**Metrics**:
```promql
# Total requests per second
rate(payment_requests_total[5m])

# Requests by status
rate(payment_requests_total{status="success"}[5m])
rate(payment_requests_total{status="failed"}[5m])

# Requests by provider
sum(rate(payment_requests_total[5m])) by (provider)

# Requests by payment method
sum(rate(payment_requests_total[5m])) by (payment_method)
```

**Targets**:
- Normal load: 100-500 TPS
- Peak load: 1000 TPS
- Burst capacity: 2000 TPS

**Kotlin Implementation**:
```kotlin
fun recordPaymentRequest(
    provider: Provider,
    paymentMethod: PaymentMethod,
    status: String
) {
    Counter.builder("payment.requests.total")
        .tag("provider", provider.name)
        .tag("payment_method", paymentMethod.name)
        .tag("status", status)
        .register(meterRegistry)
        .increment()
}
```

### 2.3 Errors

**Definition**: Rate of failed requests

**Metrics**:
```promql
# Error rate (percentage)
(
  sum(rate(payment_requests_total{status="failed"}[5m]))
  /
  sum(rate(payment_requests_total[5m]))
) * 100

# Errors by type
sum(rate(payment_errors_total[5m])) by (error_type)

# Provider error rate
sum(rate(provider_errors_total[5m])) by (provider)

# Circuit breaker state
circuit_breaker_state{provider="stripe"}
```

**Targets**:
- Overall error rate: < 0.1%
- Provider error rate: < 1%
- Timeout rate: < 0.5%

**Kotlin Implementation**:
```kotlin
fun recordPaymentError(
    provider: Provider,
    errorType: String,
    errorCode: String
) {
    Counter.builder("payment.errors.total")
        .tag("provider", provider.name)
        .tag("error_type", errorType)
        .tag("error_code", errorCode)
        .register(meterRegistry)
        .increment()
}

fun recordCircuitBreakerState(
    provider: Provider,
    state: CircuitBreakerState
) {
    Gauge.builder("circuit.breaker.state") { 
        when (state) {
            CircuitBreakerState.CLOSED -> 0.0
            CircuitBreakerState.HALF_OPEN -> 0.5
            CircuitBreakerState.OPEN -> 1.0
        }
    }
    .tag("provider", provider.name)
    .register(meterRegistry)
}
```

### 2.4 Saturation

**Definition**: Resource utilization

**Metrics**:
```promql
# CPU usage
process_cpu_usage

# Memory usage
jvm_memory_used_bytes / jvm_memory_max_bytes

# Database connections
hikaricp_connections_active / hikaricp_connections_max

# Thread pool utilization
executor_active_threads / executor_pool_size

# Queue depth
payment_queue_size
```

**Targets**:
- CPU: < 70%
- Memory: < 80%
- DB connections: < 80%
- Thread pool: < 80%

---

## 3. Service Level Objectives (SLOs)

### 3.1 Availability SLO

**Target**: 99.95% availability (21.6 minutes downtime/month)

**Measurement**:
```promql
# Availability percentage
(
  sum(rate(payment_requests_total{status!="5xx"}[30d]))
  /
  sum(rate(payment_requests_total[30d]))
) * 100
```

**Error Budget**:
- Monthly: 0.05% = 21.6 minutes
- Weekly: 5.04 minutes
- Daily: 43.2 seconds

### 3.2 Latency SLO

**Target**: 95% of requests < 500ms

**Measurement**:
```promql
# Percentage of requests under 500ms
(
  sum(rate(payment_request_duration_seconds_bucket{le="0.5"}[5m]))
  /
  sum(rate(payment_request_duration_seconds_count[5m]))
) * 100
```

### 3.3 Success Rate SLO

**Target**: 99.9% success rate

**Measurement**:
```promql
# Success rate
(
  sum(rate(payment_requests_total{status="success"}[5m]))
  /
  sum(rate(payment_requests_total[5m]))
) * 100
```

---

## 4. Key Metrics

### 4.1 Business Metrics

```kotlin
// Payment volume
Counter.builder("payment.volume.total")
    .tag("currency", currency)
    .register(meterRegistry)
    .increment()

// Payment amount
DistributionSummary.builder("payment.amount")
    .tag("currency", currency)
    .baseUnit("cents")
    .register(meterRegistry)
    .record(amount.toDouble())

// Revenue
Counter.builder("payment.revenue.total")
    .tag("currency", currency)
    .register(meterRegistry)
    .increment(amount.toDouble())

// Success rate by provider
Gauge.builder("payment.success.rate") {
    calculateSuccessRate(provider)
}
.tag("provider", provider.name)
.register(meterRegistry)
```

**Queries**:
```promql
# Total payment volume (last 24h)
sum(increase(payment_volume_total[24h]))

# Revenue by currency
sum(increase(payment_revenue_total[24h])) by (currency)

# Average transaction value
avg(payment_amount) by (currency)

# Success rate by provider
payment_success_rate{provider="stripe"}
```

### 4.2 Provider Metrics

```kotlin
// Provider API calls
Counter.builder("provider.api.calls.total")
    .tag("provider", provider.name)
    .tag("endpoint", endpoint)
    .tag("status", status)
    .register(meterRegistry)
    .increment()

// Provider response time
Timer.builder("provider.api.response.time")
    .tag("provider", provider.name)
    .publishPercentiles(0.5, 0.95, 0.99)
    .register(meterRegistry)
    .record(duration)

// Provider availability
Gauge.builder("provider.availability") {
    healthCheckService.getAvailability(provider)
}
.tag("provider", provider.name)
.register(meterRegistry)
```

**Queries**:
```promql
# Provider call rate
rate(provider_api_calls_total[5m])

# Provider error rate
rate(provider_api_calls_total{status="error"}[5m])
/ rate(provider_api_calls_total[5m])

# Provider P95 latency
histogram_quantile(0.95, 
  rate(provider_api_response_time_bucket[5m])
)
```

### 4.3 Infrastructure Metrics

```kotlin
// Database query duration
Timer.builder("database.query.duration")
    .tag("query_type", queryType)
    .register(meterRegistry)
    .record(duration)

// Cache hit rate
Gauge.builder("cache.hit.rate") {
    cacheStats.hitRate()
}
.tag("cache_name", cacheName)
.register(meterRegistry)

// Connection pool
Gauge.builder("connection.pool.active") {
    hikariDataSource.hikariPoolMXBean.activeConnections
}
.register(meterRegistry)
```

---

## 5. Alerting Rules

### 5.1 Critical Alerts (Page Immediately)

**High Error Rate**:
```yaml
alert: HighErrorRate
expr: |
  (
    sum(rate(payment_requests_total{status="failed"}[5m]))
    /
    sum(rate(payment_requests_total[5m]))
  ) > 0.05
for: 5m
severity: critical
annotations:
  summary: "High error rate detected"
  description: "Error rate is {{ $value }}% (threshold: 5%)"
```

**Service Down**:
```yaml
alert: ServiceDown
expr: up{job="payment-orchestration"} == 0
for: 1m
severity: critical
annotations:
  summary: "Payment service is down"
  description: "Service has been down for 1 minute"
```

**High Latency**:
```yaml
alert: HighLatency
expr: |
  histogram_quantile(0.95,
    rate(payment_request_duration_seconds_bucket[5m])
  ) > 1.0
for: 10m
severity: critical
annotations:
  summary: "High P95 latency detected"
  description: "P95 latency is {{ $value }}s (threshold: 1s)"
```

### 5.2 Warning Alerts (Investigate)

**Elevated Error Rate**:
```yaml
alert: ElevatedErrorRate
expr: |
  (
    sum(rate(payment_requests_total{status="failed"}[5m]))
    /
    sum(rate(payment_requests_total[5m]))
  ) > 0.01
for: 15m
severity: warning
annotations:
  summary: "Elevated error rate"
  description: "Error rate is {{ $value }}% (threshold: 1%)"
```

**Circuit Breaker Open**:
```yaml
alert: CircuitBreakerOpen
expr: circuit_breaker_state == 1
for: 5m
severity: warning
annotations:
  summary: "Circuit breaker open for {{ $labels.provider }}"
  description: "Provider {{ $labels.provider }} circuit breaker has been open for 5 minutes"
```

**High Memory Usage**:
```yaml
alert: HighMemoryUsage
expr: |
  (jvm_memory_used_bytes / jvm_memory_max_bytes) > 0.85
for: 10m
severity: warning
annotations:
  summary: "High memory usage"
  description: "Memory usage is {{ $value }}% (threshold: 85%)"
```

### 5.3 Info Alerts (Monitor)

**Provider Degraded**:
```yaml
alert: ProviderDegraded
expr: provider_availability < 0.99
for: 30m
severity: info
annotations:
  summary: "Provider {{ $labels.provider }} degraded"
  description: "Availability is {{ $value }} (threshold: 99%)"
```

---

## 6. Dashboards

### 6.1 Overview Dashboard

**Panels**:

1. **Golden Signals** (Top Row)
   - Request rate (line chart)
   - Error rate (line chart)
   - P95 latency (line chart)
   - Success rate (gauge)

2. **Payment Volume** (Second Row)
   - Total payments (stat)
   - Successful payments (stat)
   - Failed payments (stat)
   - Revenue (stat)

3. **Provider Health** (Third Row)
   - Provider status (table)
   - Circuit breaker states (stat)
   - Provider latency comparison (bar chart)

4. **System Health** (Fourth Row)
   - CPU usage (gauge)
   - Memory usage (gauge)
   - Database connections (gauge)
   - Cache hit rate (gauge)

**Grafana JSON**:
```json
{
  "dashboard": {
    "title": "Payment Orchestration - Overview",
    "panels": [
      {
        "title": "Request Rate",
        "targets": [{
          "expr": "rate(payment_requests_total[5m])"
        }]
      },
      {
        "title": "Error Rate",
        "targets": [{
          "expr": "(sum(rate(payment_requests_total{status=\"failed\"}[5m])) / sum(rate(payment_requests_total[5m]))) * 100"
        }]
      }
    ]
  }
}
```

### 6.2 Provider Dashboard

**Purpose**: Monitor individual provider performance

**Panels**:

1. **Provider Metrics** (Top Row)
   - Request rate by provider (line chart)
   - Success rate by provider (bar chart)
   - Error rate by provider (bar chart)

2. **Latency Analysis** (Second Row)
   - P50 latency (line chart)
   - P95 latency (line chart)
   - P99 latency (line chart)

3. **Circuit Breaker** (Third Row)
   - Circuit breaker state (stat)
   - Failure count (line chart)
   - Recovery time (stat)

4. **Cost Analysis** (Fourth Row)
   - Volume by provider (pie chart)
   - Cost by provider (bar chart)
   - Cost per transaction (table)

---

## 7. Distributed Tracing

### 7.1 Trace Structure

```
Payment Request Trace
├─ HTTP Request (span)
│  ├─ Request validation
│  └─ Authentication
│
├─ Idempotency Check (span)
│  ├─ Redis lookup
│  └─ Database lookup
│
├─ Payment Processing (span)
│  ├─ Provider selection
│  ├─ Circuit breaker check
│  ├─ Provider API call (span)
│  │  ├─ HTTP request
│  │  └─ Response parsing
│  └─ Database update
│
└─ Event Publishing (span)
   └─ Kafka publish
```

### 7.2 Trace Implementation

```kotlin
@Service
class PaymentOrchestrationService(
    private val tracer: Tracer
) {
    
    suspend fun processPayment(request: CreatePaymentRequest): PaymentResponse {
        val span = tracer.nextSpan().name("process-payment").start()
        
        try {
            span.tag("customer.id", request.customerId)
            span.tag("amount", request.amount.toString())
            span.tag("currency", request.currency)
            
            // Idempotency check
            val idempotencySpan = tracer.nextSpan(span.context())
                .name("idempotency-check")
                .start()
            try {
                checkIdempotency(request.idempotencyKey)
            } finally {
                idempotencySpan.finish()
            }
            
            // Provider selection
            val routingSpan = tracer.nextSpan(span.context())
                .name("provider-routing")
                .start()
            val provider = try {
                selectProvider(request)
            } finally {
                routingSpan.tag("provider", provider.name)
                routingSpan.finish()
            }
            
            // Execute payment
            val paymentSpan = tracer.nextSpan(span.context())
                .name("execute-payment")
                .start()
            val result = try {
                executePayment(request, provider)
            } finally {
                paymentSpan.tag("provider", provider.name)
                paymentSpan.tag("status", result.status)
                paymentSpan.finish()
            }
            
            span.tag("result", "success")
            return result
            
        } catch (e: Exception) {
            span.tag("error", "true")
            span.tag("error.message", e.message ?: "Unknown error")
            throw e
        } finally {
            span.finish()
        }
    }
}
```

---

## 8. Log Aggregation

### 8.1 Structured Logging

```kotlin
@Component
class StructuredLogger {
    
    private val logger = LoggerFactory.getLogger(javaClass)
    
    fun logPaymentInitiated(payment: Payment) {
        logger.info(
            buildJsonLog(
                "event" to "payment.initiated",
                "transaction_id" to payment.transactionId,
                "customer_id" to payment.customerId,
                "amount" to payment.amount,
                "currency" to payment.currency,
                "timestamp" to Instant.now().toString()
            )
        )
    }
    
    fun logPaymentSucceeded(payment: Payment, provider: Provider) {
        logger.info(
            buildJsonLog(
                "event" to "payment.succeeded",
                "transaction_id" to payment.transactionId,
                "provider" to provider.name,
                "duration_ms" to payment.duration,
                "timestamp" to Instant.now().toString()
            )
        )
    }
    
    fun logPaymentFailed(payment: Payment, error: Exception) {
        logger.error(
            buildJsonLog(
                "event" to "payment.failed",
                "transaction_id" to payment.transactionId,
                "error_type" to error.javaClass.simpleName,
                "error_message" to error.message,
                "timestamp" to Instant.now().toString()
            ),
            error
        )
    }
    
    private fun buildJsonLog(vararg pairs: Pair<String, Any?>): String {
        return pairs.joinToString(", ", "{", "}") { (key, value) ->
            "\"$key\": ${if (value is String) "\"$value\"" else value}"
        }
    }
}
```

### 8.2 Log Queries (Kibana)

```
# Find all failed payments in last hour
event:"payment.failed" AND @timestamp:[now-1h TO now]

# Find payments for specific customer
customer_id:"cust_123"

# Find slow payments (> 1s)
duration_ms:>1000

# Find provider timeouts
error_type:"ProviderTimeoutException"

# Find circuit breaker events
event:"circuit.breaker.state.changed"
```

---

## 9. Incident Response

### 9.1 Runbook: High Error Rate

**Symptoms**:
- Error rate > 5%
- PagerDuty alert triggered

**Investigation**:
1. Check Grafana dashboard for error spike
2. Identify affected provider(s)
3. Check provider status pages
4. Review recent deployments
5. Check circuit breaker states

**Resolution**:
```bash
# Check provider health
curl http://localhost:8080/api/v1/health/providers

# Check circuit breaker states
curl http://localhost:8080/actuator/circuitbreakers

# Force circuit breaker open (if needed)
curl -X POST http://localhost:8080/actuator/circuitbreakers/stripe/open

# Check recent errors
kubectl logs -l app=payment-orchestration --tail=100 | grep ERROR
```

### 9.2 Runbook: High Latency

**Symptoms**:
- P95 latency > 1s
- Slow response times

**Investigation**:
1. Check Zipkin for slow traces
2. Identify bottleneck (DB, provider, cache)
3. Check database connection pool
4. Check provider response times

**Resolution**:
```bash
# Check database connections
curl http://localhost:8080/actuator/metrics/hikaricp.connections.active

# Check cache hit rate
curl http://localhost:8080/actuator/metrics/cache.gets

# Scale up if needed
kubectl scale deployment payment-orchestration --replicas=5
```

---

## 10. Capacity Planning

### 10.1 Current Capacity

| Resource | Current | Max | Utilization |
|----------|---------|-----|-------------|
| Pods | 3 | 10 | 30% |
| CPU | 2 cores | 8 cores | 25% |
| Memory | 4 GB | 16 GB | 25% |
| DB Connections | 20 | 100 | 20% |
| TPS | 300 | 1000 | 30% |

### 10.2 Growth Projections

```promql
# Predict TPS growth (30 days)
predict_linear(
  rate(payment_requests_total[7d])[30d:1d],
  30 * 24 * 3600
)

# Predict memory growth
predict_linear(
  jvm_memory_used_bytes[7d],
  30 * 24 * 3600
)
```

### 10.3 Scaling Triggers

**Horizontal Scaling**:
```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: payment-orchestration-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: payment-orchestration
  minReplicas: 3
  maxReplicas: 10
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70
  - type: Resource
    resource:
      name: memory
      target:
        type: Utilization
        averageUtilization: 80
```

---

## Appendix

### A. Metric Naming Conventions

- Use snake_case: `payment_requests_total`
- Include unit suffix: `_seconds`, `_bytes`, `_total`
- Use consistent labels: `provider`, `status`, `currency`

### B. Prometheus Configuration

See `prometheus.yml` for complete configuration.

### C. Grafana Dashboards

Import dashboards from `/grafana/dashboards/` directory.

---

**Last Updated**: 2026-04-09  
**Document Version**: 1.0.0