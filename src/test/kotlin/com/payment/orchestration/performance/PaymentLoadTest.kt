package com.payment.orchestration.performance

import io.gatling.javaapi.core.*
import io.gatling.javaapi.core.CoreDsl.*
import io.gatling.javaapi.http.HttpDsl.*
import java.time.Duration

/**
 * Payment Load Test
 * 
 * Comprehensive performance and load testing for the payment orchestration system.
 * 
 * Test Scenarios:
 * 1. Baseline Load Test - Normal traffic (100 RPS)
 * 2. Stress Test - High traffic (500 RPS)
 * 3. Spike Test - Sudden traffic spike (1000 RPS)
 * 4. Endurance Test - Sustained load (50 RPS for 30 minutes)
 * 5. Capacity Test - Find breaking point
 * 
 * Performance Targets:
 * - P95 Latency: < 2 seconds
 * - P99 Latency: < 5 seconds
 * - Success Rate: > 99.5%
 * - Throughput: 1000 TPS
 * - Error Rate: < 0.5%
 * 
 * Running Tests:
 * ```bash
 * # Run all scenarios
 * mvn gatling:test
 * 
 * # Run specific scenario
 * mvn gatling:test -Dgatling.simulationClass=PaymentLoadTest
 * 
 * # Generate report
 * mvn gatling:test && open target/gatling/results/index.html
 * ```
 * 
 * Dependencies:
 * ```xml
 * <dependency>
 *     <groupId>io.gatling.highcharts</groupId>
 *     <artifactId>gatling-charts-highcharts</artifactId>
 *     <version>3.9.5</version>
 *     <scope>test</scope>
 * </dependency>
 * ```
 */
class PaymentLoadTest : Simulation() {

    // ========================================
    // Configuration
    // ========================================

    private val baseUrl = System.getProperty("baseUrl", "http://localhost:8080")
    private val testDuration = Duration.ofMinutes(
        System.getProperty("testDuration", "5").toLong()
    )
    
    // HTTP Protocol Configuration
    private val httpProtocol = http
        .baseUrl(baseUrl)
        .acceptHeader("application/json")
        .contentTypeHeader("application/json")
        .userAgentHeader("Gatling-LoadTest/1.0")

    // ========================================
    // Test Data Feeders
    // ========================================

    /**
     * Payment request feeder
     * 
     * Generates realistic payment data for testing.
     */
    private val paymentFeeder = iterator {
        generateSequence(0) { it + 1 }.map { i ->
            mapOf(
                "paymentId" to "pay_load_${System.currentTimeMillis()}_$i",
                "customerId" to "cust_${(1000..9999).random()}",
                "amount" to (1000..100000).random(),
                "currency" to listOf("USD", "EUR", "GBP", "INR").random(),
                "paymentMethod" to listOf("CARD", "UPI", "WALLET", "NET_BANKING").random(),
                "provider" to listOf("PROVIDER_A", "PROVIDER_B").random(),
                "idempotencyKey" to "idem_${System.currentTimeMillis()}_$i"
            )
        }.iterator()
    }

    /**
     * Customer feeder
     * 
     * Simulates different customer profiles.
     */
    private val customerFeeder = iterator {
        generateSequence(0) { it + 1 }.map { i ->
            mapOf(
                "customerId" to "cust_${i % 1000}",
                "country" to listOf("US", "IN", "GB", "DE", "FR", "JP").random(),
                "email" to "customer${i % 1000}@example.com"
            )
        }.iterator()
    }

    // ========================================
    // Scenarios
    // ========================================

    /**
     * Create Payment Scenario
     * 
     * Tests the payment creation endpoint.
     */
    private val createPaymentScenario = scenario("Create Payment")
        .feed(paymentFeeder)
        .feed(customerFeeder)
        .exec(
            http("Create Payment Request")
                .post("/api/v1/payments")
                .header("X-Idempotency-Key", "#{idempotencyKey}")
                .body(StringBody("""
                    {
                        "customerId": "#{customerId}",
                        "amount": #{amount},
                        "currency": "#{currency}",
                        "paymentMethod": "#{paymentMethod}",
                        "description": "Load test payment",
                        "metadata": {
                            "test": "load_test",
                            "scenario": "create_payment"
                        }
                    }
                """.trimIndent()))
                .check(status().`in`(200, 201))
                .check(jsonPath("$.paymentId").saveAs("createdPaymentId"))
        )
        .pause(Duration.ofMillis(100))

    /**
     * Fetch Payment Scenario
     * 
     * Tests the payment retrieval endpoint.
     */
    private val fetchPaymentScenario = scenario("Fetch Payment")
        .feed(paymentFeeder)
        .exec(
            // First create a payment
            http("Create Payment for Fetch")
                .post("/api/v1/payments")
                .header("X-Idempotency-Key", "#{idempotencyKey}")
                .body(StringBody("""
                    {
                        "customerId": "#{customerId}",
                        "amount": #{amount},
                        "currency": "#{currency}",
                        "paymentMethod": "#{paymentMethod}",
                        "description": "Fetch test payment"
                    }
                """.trimIndent()))
                .check(status().`in`(200, 201))
                .check(jsonPath("$.paymentId").saveAs("paymentId"))
        )
        .pause(Duration.ofMillis(50))
        .exec(
            // Then fetch it
            http("Fetch Payment Request")
                .get("/api/v1/payments/#{paymentId}")
                .check(status().`is`(200))
                .check(jsonPath("$.paymentId").`is`("#{paymentId}"))
        )

    /**
     * Idempotency Test Scenario
     * 
     * Tests duplicate request handling.
     */
    private val idempotencyScenario = scenario("Idempotency Test")
        .feed(paymentFeeder)
        .exec(
            // First request
            http("First Payment Request")
                .post("/api/v1/payments")
                .header("X-Idempotency-Key", "#{idempotencyKey}")
                .body(StringBody("""
                    {
                        "customerId": "#{customerId}",
                        "amount": #{amount},
                        "currency": "#{currency}",
                        "paymentMethod": "#{paymentMethod}",
                        "description": "Idempotency test"
                    }
                """.trimIndent()))
                .check(status().`in`(200, 201))
                .check(jsonPath("$.paymentId").saveAs("firstPaymentId"))
        )
        .pause(Duration.ofMillis(100))
        .exec(
            // Duplicate request with same idempotency key
            http("Duplicate Payment Request")
                .post("/api/v1/payments")
                .header("X-Idempotency-Key", "#{idempotencyKey}")
                .body(StringBody("""
                    {
                        "customerId": "#{customerId}",
                        "amount": #{amount},
                        "currency": "#{currency}",
                        "paymentMethod": "#{paymentMethod}",
                        "description": "Idempotency test"
                    }
                """.trimIndent()))
                .check(status().`is`(200))
                .check(jsonPath("$.paymentId").`is`("#{firstPaymentId}"))
        )

    /**
     * Mixed Workload Scenario
     * 
     * Simulates realistic traffic with multiple operations.
     */
    private val mixedWorkloadScenario = scenario("Mixed Workload")
        .feed(paymentFeeder)
        .randomSwitch(
            60.0 to exec(createPaymentScenario.injectOpen(atOnceUsers(1))),
            30.0 to exec(fetchPaymentScenario.injectOpen(atOnceUsers(1))),
            10.0 to exec(idempotencyScenario.injectOpen(atOnceUsers(1)))
        )

    /**
     * Provider Failover Scenario
     * 
     * Tests system behavior during provider failures.
     */
    private val failoverScenario = scenario("Provider Failover")
        .feed(paymentFeeder)
        .exec(
            http("Payment with Potential Failover")
                .post("/api/v1/payments")
                .header("X-Idempotency-Key", "#{idempotencyKey}")
                .body(StringBody("""
                    {
                        "customerId": "#{customerId}",
                        "amount": #{amount},
                        "currency": "#{currency}",
                        "paymentMethod": "#{paymentMethod}",
                        "description": "Failover test"
                    }
                """.trimIndent()))
                .check(status().`in`(200, 201, 503))
        )

    // ========================================
    // Load Profiles
    // ========================================

    /**
     * Baseline Load Test
     * 
     * Normal traffic: 100 RPS for 5 minutes
     */
    private val baselineLoad = createPaymentScenario.injectOpen(
        constantUsersPerSec(100.0).during(testDuration)
    ).protocols(httpProtocol)

    /**
     * Stress Test
     * 
     * High traffic: Ramp up to 500 RPS
     */
    private val stressTest = createPaymentScenario.injectOpen(
        rampUsersPerSec(100.0).to(500.0).during(Duration.ofMinutes(2)),
        constantUsersPerSec(500.0).during(Duration.ofMinutes(3))
    ).protocols(httpProtocol)

    /**
     * Spike Test
     * 
     * Sudden traffic spike: 1000 RPS for 1 minute
     */
    private val spikeTest = createPaymentScenario.injectOpen(
        nothingFor(Duration.ofSeconds(10)),
        atOnceUsers(1000),
        constantUsersPerSec(1000.0).during(Duration.ofMinutes(1))
    ).protocols(httpProtocol)

    /**
     * Endurance Test
     * 
     * Sustained load: 50 RPS for 30 minutes
     */
    private val enduranceTest = createPaymentScenario.injectOpen(
        constantUsersPerSec(50.0).during(Duration.ofMinutes(30))
    ).protocols(httpProtocol)

    /**
     * Capacity Test
     * 
     * Find breaking point: Gradually increase load
     */
    private val capacityTest = createPaymentScenario.injectOpen(
        incrementUsersPerSec(50.0)
            .times(20)
            .eachLevelLasting(Duration.ofSeconds(30))
            .separatedByRampsLasting(Duration.ofSeconds(10))
            .startingFrom(50.0)
    ).protocols(httpProtocol)

    /**
     * Realistic Workload Test
     * 
     * Mixed operations with realistic distribution
     */
    private val realisticWorkload = mixedWorkloadScenario.injectOpen(
        rampUsersPerSec(10.0).to(100.0).during(Duration.ofMinutes(2)),
        constantUsersPerSec(100.0).during(Duration.ofMinutes(5)),
        rampUsersPerSec(100.0).to(10.0).during(Duration.ofMinutes(2))
    ).protocols(httpProtocol)

    // ========================================
    // Assertions
    // ========================================

    init {
        // Select test scenario based on system property
        val scenario = System.getProperty("scenario", "baseline")
        
        val selectedTest = when (scenario) {
            "stress" -> stressTest
            "spike" -> spikeTest
            "endurance" -> enduranceTest
            "capacity" -> capacityTest
            "realistic" -> realisticWorkload
            else -> baselineLoad
        }

        setUp(selectedTest)
            .assertions(
                // Global assertions
                global().responseTime().percentile3().lt(5000), // P99 < 5s
                global().responseTime().percentile4().lt(2000), // P95 < 2s
                global().successfulRequests().percent().gt(99.5), // Success rate > 99.5%
                global().failedRequests().percent().lt(0.5), // Error rate < 0.5%
                
                // Per-request assertions
                forAll().responseTime().mean().lt(1000), // Mean < 1s
                forAll().failedRequests().count().lt(100) // Max 100 failures
            )
    }
}

/**
 * Performance Test Report
 * 
 * After running tests, Gatling generates an HTML report with:
 * - Request statistics (count, mean, min, max, percentiles)
 * - Response time distribution
 * - Requests per second over time
 * - Response time over time
 * - Success/failure rates
 * - Active users over time
 * 
 * Key Metrics to Monitor:
 * 1. Response Time Percentiles (P50, P95, P99)
 * 2. Throughput (requests per second)
 * 3. Error Rate (percentage of failed requests)
 * 4. Active Users (concurrent users)
 * 5. Response Time Distribution
 * 
 * Performance Tuning Tips:
 * 1. Database Connection Pool - Increase size if bottleneck
 * 2. Thread Pool - Adjust based on CPU cores
 * 3. Circuit Breaker - Tune thresholds for faster recovery
 * 4. Caching - Enable for frequently accessed data
 * 5. Provider Timeout - Balance between reliability and latency
 */
object PerformanceTestGuide {
    const val GUIDE = """
        Performance Testing Guide
        ========================
        
        1. Baseline Test (100 RPS)
           - Establishes normal performance
           - Run: mvn gatling:test -Dscenario=baseline
           
        2. Stress Test (500 RPS)
           - Tests system under high load
           - Run: mvn gatling:test -Dscenario=stress
           
        3. Spike Test (1000 RPS)
           - Tests sudden traffic spikes
           - Run: mvn gatling:test -Dscenario=spike
           
        4. Endurance Test (50 RPS, 30 min)
           - Tests memory leaks and stability
           - Run: mvn gatling:test -Dscenario=endurance
           
        5. Capacity Test (Incremental)
           - Finds system breaking point
           - Run: mvn gatling:test -Dscenario=capacity
           
        6. Realistic Workload
           - Mixed operations (60% create, 30% fetch, 10% idempotency)
           - Run: mvn gatling:test -Dscenario=realistic
        
        Performance Targets:
        - P95 Latency: < 2 seconds
        - P99 Latency: < 5 seconds
        - Success Rate: > 99.5%
        - Throughput: 1000 TPS
        - Error Rate: < 0.5%
        
        Monitoring During Tests:
        - Prometheus metrics: http://localhost:8080/actuator/prometheus
        - Health check: http://localhost:8080/actuator/health
        - JVM metrics: http://localhost:8080/actuator/metrics
        - Zipkin traces: http://localhost:9411
    """
}


