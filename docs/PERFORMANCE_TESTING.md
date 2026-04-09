# Performance Testing Guide

## Overview

Comprehensive performance and load testing strategy for the Payment Orchestration System using Gatling.

## Test Scenarios

### 1. Baseline Load Test
**Purpose:** Establish normal performance baseline  
**Load:** 100 requests/second  
**Duration:** 5 minutes  
**Command:**
```bash
mvn gatling:test -Dscenario=baseline
```

**Expected Results:**
- P95 Latency: < 2 seconds
- P99 Latency: < 5 seconds
- Success Rate: > 99.5%
- Error Rate: < 0.5%

### 2. Stress Test
**Purpose:** Test system under high load  
**Load:** Ramp from 100 to 500 RPS  
**Duration:** 5 minutes (2 min ramp + 3 min sustained)  
**Command:**
```bash
mvn gatling:test -Dscenario=stress
```

**Expected Results:**
- System remains stable at 500 RPS
- P95 Latency: < 3 seconds
- Success Rate: > 99%
- No memory leaks or resource exhaustion

### 3. Spike Test
**Purpose:** Test sudden traffic spikes  
**Load:** Instant spike to 1000 RPS  
**Duration:** 1 minute spike  
**Command:**
```bash
mvn gatling:test -Dscenario=spike
```

**Expected Results:**
- System handles spike gracefully
- Circuit breakers activate if needed
- Recovery within 30 seconds
- No cascading failures

### 4. Endurance Test
**Purpose:** Test long-term stability  
**Load:** 50 requests/second  
**Duration:** 30 minutes  
**Command:**
```bash
mvn gatling:test -Dscenario=endurance
```

**Expected Results:**
- No memory leaks
- Stable response times
- No resource exhaustion
- Consistent throughput

### 5. Capacity Test
**Purpose:** Find system breaking point  
**Load:** Incremental increase (50 RPS steps)  
**Duration:** 10 minutes  
**Command:**
```bash
mvn gatling:test -Dscenario=capacity
```

**Expected Results:**
- Identify maximum sustainable load
- Document degradation points
- Measure recovery behavior

### 6. Realistic Workload Test
**Purpose:** Simulate production traffic  
**Load:** Mixed operations (60% create, 30% fetch, 10% idempotency)  
**Duration:** 9 minutes (2 min ramp-up + 5 min sustained + 2 min ramp-down)  
**Command:**
```bash
mvn gatling:test -Dscenario=realistic
```

**Expected Results:**
- Realistic performance metrics
- Balanced resource utilization
- Representative of production behavior

## Performance Targets

### Latency Targets
| Percentile | Target | Critical |
|------------|--------|----------|
| P50 (Median) | < 500ms | < 1s |
| P95 | < 2s | < 3s |
| P99 | < 5s | < 10s |
| P99.9 | < 10s | < 20s |

### Throughput Targets
| Metric | Target | Maximum |
|--------|--------|---------|
| Requests/Second | 100 RPS | 1000 RPS |
| Concurrent Users | 500 | 5000 |
| Daily Volume | 8.6M requests | 86M requests |

### Reliability Targets
| Metric | Target |
|--------|--------|
| Success Rate | > 99.5% |
| Error Rate | < 0.5% |
| Timeout Rate | < 0.1% |
| Availability | > 99.9% |

## Test Data

### Payment Request Distribution
- **Card Payments:** 60%
- **UPI Payments:** 20%
- **Wallet Payments:** 15%
- **Net Banking:** 5%

### Amount Distribution
- **Small (< $10):** 30%
- **Medium ($10-$100):** 50%
- **Large (> $100):** 20%

### Geographic Distribution
- **US:** 40%
- **India:** 30%
- **Europe:** 20%
- **Others:** 10%

## Monitoring During Tests

### Key Metrics to Watch

#### Application Metrics (Prometheus)
```bash
# Open Prometheus
http://localhost:8080/actuator/prometheus

# Key queries:
- payment_processing_duration_bucket (latency)
- payment_requests_total (throughput)
- payment_errors_total (error rate)
- circuit_breaker_state (circuit breaker status)
- provider_health_status (provider health)
```

#### JVM Metrics
```bash
# Open JVM metrics
http://localhost:8080/actuator/metrics

# Key metrics:
- jvm.memory.used
- jvm.threads.live
- jvm.gc.pause
- process.cpu.usage
```

#### Health Check
```bash
# System health
http://localhost:8080/actuator/health

# Provider health
http://localhost:8080/api/v1/health/providers
```

#### Distributed Tracing
```bash
# Zipkin UI
http://localhost:9411

# Search for slow traces
- Min Duration: 2000ms
- Tag: error=true
```

## Performance Tuning

### Database Optimization
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 50  # Increase for high load
      minimum-idle: 10
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000
```

### Thread Pool Configuration
```yaml
server:
  tomcat:
    threads:
      max: 200  # Adjust based on CPU cores
      min-spare: 10
    accept-count: 100
    max-connections: 10000
```

### Circuit Breaker Tuning
```kotlin
CircuitBreakerConfig.custom()
    .failureRateThreshold(50.0f)  // 50% failure rate
    .slidingWindowSize(100)       // Last 100 requests
    .waitDurationInOpenState(Duration.ofSeconds(30))  // Recovery time
    .build()
```

### Provider Timeout Configuration
```kotlin
RestTemplate()
    .setConnectTimeout(Duration.ofSeconds(5))
    .setReadTimeout(Duration.ofSeconds(10))
```

### Caching Strategy
```kotlin
@Cacheable(value = "payments", key = "#paymentId")
fun getPayment(paymentId: String): Payment

@CacheEvict(value = "payments", key = "#paymentId")
fun updatePayment(paymentId: String, status: PaymentStatus)
```

## Bottleneck Analysis

### Common Bottlenecks

#### 1. Database Connection Pool Exhaustion
**Symptoms:**
- High wait times for connections
- Increasing response times
- Connection timeout errors

**Solution:**
```yaml
spring.datasource.hikari.maximum-pool-size: 100
```

#### 2. Thread Pool Saturation
**Symptoms:**
- Rejected requests
- High CPU usage
- Slow response times

**Solution:**
```yaml
server.tomcat.threads.max: 300
```

#### 3. Provider API Timeouts
**Symptoms:**
- High timeout rate
- Circuit breakers opening
- Retry exhaustion

**Solution:**
- Increase timeout values
- Implement better retry logic
- Add more provider capacity

#### 4. Memory Pressure
**Symptoms:**
- Frequent GC pauses
- OutOfMemoryError
- Slow response times

**Solution:**
```bash
java -Xmx4g -Xms2g -XX:+UseG1GC
```

#### 5. Network Latency
**Symptoms:**
- High P99 latency
- Geographic variance
- Provider-specific delays

**Solution:**
- Use geographic routing
- Implement caching
- Optimize payload size

## Test Execution

### Prerequisites
```bash
# 1. Start application
mvn spring-boot:run

# 2. Start Prometheus (optional)
docker run -d -p 9090:9090 prom/prometheus

# 3. Start Zipkin (optional)
docker run -d -p 9411:9411 openzipkin/zipkin

# 4. Verify health
curl http://localhost:8080/actuator/health
```

### Running Tests
```bash
# Run all tests
mvn gatling:test

# Run specific scenario
mvn gatling:test -Dscenario=baseline

# Run with custom duration
mvn gatling:test -Dscenario=stress -DtestDuration=10

# Run with custom base URL
mvn gatling:test -DbaseUrl=http://staging.example.com
```

### Analyzing Results
```bash
# Open Gatling report
open target/gatling/paymentloadtest-*/index.html

# Key sections:
1. Global Information - Overall statistics
2. Details - Per-request breakdown
3. Response Time Distribution - Histogram
4. Response Time Percentiles - P50, P95, P99
5. Requests per Second - Throughput over time
6. Responses per Second - Success/failure rates
```

## Performance Regression Testing

### Baseline Establishment
```bash
# 1. Run baseline test
mvn gatling:test -Dscenario=baseline

# 2. Save results
cp target/gatling/paymentloadtest-*/global_stats.json baseline_v1.0.0.json

# 3. Document baseline
echo "Baseline: P95=1.2s, P99=2.5s, Success=99.8%" > baseline.txt
```

### Regression Detection
```bash
# 1. Run test after changes
mvn gatling:test -Dscenario=baseline

# 2. Compare with baseline
# - P95 latency increase > 20%: FAIL
# - P99 latency increase > 30%: FAIL
# - Success rate decrease > 0.5%: FAIL

# 3. Investigate regressions
# - Check recent code changes
# - Review Prometheus metrics
# - Analyze Zipkin traces
```

## CI/CD Integration

### GitHub Actions Example
```yaml
name: Performance Tests

on:
  pull_request:
    branches: [main]
  schedule:
    - cron: '0 2 * * *'  # Daily at 2 AM

jobs:
  performance-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      
      - name: Set up JDK 17
        uses: actions/setup-java@v2
        with:
          java-version: '17'
      
      - name: Start application
        run: |
          mvn spring-boot:run &
          sleep 30
      
      - name: Run performance tests
        run: mvn gatling:test -Dscenario=baseline
      
      - name: Upload results
        uses: actions/upload-artifact@v2
        with:
          name: gatling-results
          path: target/gatling/
      
      - name: Check performance thresholds
        run: |
          # Parse results and fail if thresholds exceeded
          python scripts/check_performance.py
```

## Best Practices

### 1. Test Environment
- Use production-like infrastructure
- Isolate test environment
- Use realistic data volumes
- Monitor resource usage

### 2. Test Execution
- Run tests during off-peak hours
- Warm up system before testing
- Run multiple iterations
- Document test conditions

### 3. Result Analysis
- Compare with baseline
- Identify trends over time
- Investigate anomalies
- Document findings

### 4. Continuous Improvement
- Regular performance testing
- Track metrics over time
- Optimize bottlenecks
- Update baselines

## Troubleshooting

### High Latency
1. Check database query performance
2. Review provider API response times
3. Analyze thread pool utilization
4. Check for memory pressure

### High Error Rate
1. Review application logs
2. Check circuit breaker status
3. Verify provider health
4. Analyze error types

### Low Throughput
1. Check thread pool configuration
2. Review database connection pool
3. Analyze CPU/memory usage
4. Check network bandwidth

### Memory Issues
1. Analyze heap dumps
2. Check for memory leaks
3. Review GC logs
4. Optimize object creation

## Resources

- [Gatling Documentation](https://gatling.io/docs/)
- [Performance Testing Best Practices](https://martinfowler.com/articles/performance-testing.html)
- [JVM Performance Tuning](https://docs.oracle.com/en/java/javase/17/gctuning/)
- [Spring Boot Performance](https://spring.io/guides/gs/spring-boot/)