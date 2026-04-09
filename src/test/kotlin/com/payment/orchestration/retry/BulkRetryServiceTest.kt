package com.payment.orchestration.retry

import com.payment.orchestration.domain.model.Payment
import com.payment.orchestration.domain.model.PaymentStatus
import com.payment.orchestration.domain.model.Provider
import com.payment.orchestration.domain.repository.PaymentRepository
import com.payment.orchestration.service.PaymentOrchestrationService
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.mockk.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import java.math.BigDecimal
import java.time.Instant
import java.util.*

/**
 * Bulk Retry Service Tests
 * 
 * Comprehensive test suite for bulk retry functionality.
 * 
 * Test Categories:
 * 1. Job Creation and Execution
 * 2. Progress Tracking
 * 3. Result Retrieval
 * 4. Job Cancellation
 * 5. Batch Processing
 * 6. Error Handling
 * 7. Concurrent Operations
 * 8. Edge Cases
 */
class BulkRetryServiceTest {

    private lateinit var paymentRepository: PaymentRepository
    private lateinit var orchestrationService: PaymentOrchestrationService
    private lateinit var circuitBreakerRegistry: CircuitBreakerRegistry
    private lateinit var bulkRetryService: BulkRetryService

    @BeforeEach
    fun setup() {
        paymentRepository = mockk()
        orchestrationService = mockk()
        circuitBreakerRegistry = mockk(relaxed = true)
        
        bulkRetryService = BulkRetryService(
            paymentRepository = paymentRepository,
            orchestrationService = orchestrationService,
            circuitBreakerRegistry = circuitBreakerRegistry
        )
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    // ========================================
    // 1. Job Creation and Execution Tests
    // ========================================

    @Test
    fun `should start bulk retry job successfully`() {
        // Given
        val request = BulkRetryRequest(
            statuses = listOf(PaymentStatus.FAILED),
            provider = Provider.PROVIDER_A,
            startTime = Instant.now().minusSeconds(3600),
            endTime = Instant.now(),
            batchSize = 50
        )

        val failedPayments = createFailedPayments(10)
        every { 
            paymentRepository.findByStatusInAndProviderAndCreatedAtBetween(
                any(), any(), any(), any(), any()
            ) 
        } returns PageImpl(failedPayments)

        coEvery { orchestrationService.retryPayment(any()) } returns mockk(relaxed = true)

        // When
        val jobId = bulkRetryService.startBulkRetry(request)

        // Then
        assertNotNull(jobId)
        assertTrue(jobId.startsWith("bulk_retry_"))
        
        // Wait for async processing to start
        Thread.sleep(100)
        
        val progress = bulkRetryService.getProgress(jobId)
        assertNotNull(progress)
        assertEquals(jobId, progress?.jobId)
    }

    @Test
    fun `should process payments in batches`() {
        // Given
        val batchSize = 5
        val totalPayments = 12
        val request = BulkRetryRequest(
            statuses = listOf(PaymentStatus.FAILED),
            batchSize = batchSize
        )

        val failedPayments = createFailedPayments(totalPayments)
        every { 
            paymentRepository.findByStatusInAndProviderAndCreatedAtBetween(
                any(), any(), any(), any(), any()
            ) 
        } returns PageImpl(failedPayments)

        coEvery { orchestrationService.retryPayment(any()) } returns mockk(relaxed = true)

        // When
        val jobId = bulkRetryService.startBulkRetry(request)

        // Wait for processing
        Thread.sleep(500)

        // Then
        val results = bulkRetryService.getResults(jobId)
        assertNotNull(results)
        assertEquals(totalPayments, results?.totalProcessed)
        
        // Verify batching occurred (3 batches: 5 + 5 + 2)
        verify(atLeast = 3) { 
            paymentRepository.findByStatusInAndProviderAndCreatedAtBetween(
                any(), any(), any(), any(), any()
            ) 
        }
    }

    @Test
    fun `should handle empty payment list`() {
        // Given
        val request = BulkRetryRequest(
            statuses = listOf(PaymentStatus.FAILED)
        )

        every { 
            paymentRepository.findByStatusInAndProviderAndCreatedAtBetween(
                any(), any(), any(), any(), any()
            ) 
        } returns PageImpl(emptyList())

        // When
        val jobId = bulkRetryService.startBulkRetry(request)

        // Wait for processing
        Thread.sleep(200)

        // Then
        val results = bulkRetryService.getResults(jobId)
        assertNotNull(results)
        assertEquals(BulkRetryStatus.COMPLETED, results?.status)
        assertEquals(0, results?.totalProcessed)
        assertEquals(0, results?.successCount)
        assertEquals(0, results?.failureCount)
    }

    // ========================================
    // 2. Progress Tracking Tests
    // ========================================

    @Test
    fun `should track progress during execution`() {
        // Given
        val request = BulkRetryRequest(
            statuses = listOf(PaymentStatus.FAILED),
            batchSize = 10
        )

        val failedPayments = createFailedPayments(20)
        every { 
            paymentRepository.findByStatusInAndProviderAndCreatedAtBetween(
                any(), any(), any(), any(), any()
            ) 
        } returns PageImpl(failedPayments)

        coEvery { orchestrationService.retryPayment(any()) } coAnswers {
            delay(50) // Simulate processing time
            mockk(relaxed = true)
        }

        // When
        val jobId = bulkRetryService.startBulkRetry(request)

        // Check progress at different stages
        Thread.sleep(100)
        val progress1 = bulkRetryService.getProgress(jobId)
        
        Thread.sleep(300)
        val progress2 = bulkRetryService.getProgress(jobId)

        // Then
        assertNotNull(progress1)
        assertNotNull(progress2)
        assertEquals(BulkRetryStatus.RUNNING, progress1?.status)
        
        // Progress should increase
        assertTrue((progress2?.totalProcessed ?: 0) >= (progress1?.totalProcessed ?: 0))
    }

    @Test
    fun `should return null for non-existent job`() {
        // When
        val progress = bulkRetryService.getProgress("non_existent_job")

        // Then
        assertNull(progress)
    }

    @Test
    fun `should update progress with success and failure counts`() {
        // Given
        val request = BulkRetryRequest(
            statuses = listOf(PaymentStatus.FAILED),
            batchSize = 5
        )

        val failedPayments = createFailedPayments(10)
        every { 
            paymentRepository.findByStatusInAndProviderAndCreatedAtBetween(
                any(), any(), any(), any(), any()
            ) 
        } returns PageImpl(failedPayments)

        // Mock some successes and some failures
        var callCount = 0
        coEvery { orchestrationService.retryPayment(any()) } coAnswers {
            callCount++
            if (callCount % 3 == 0) {
                throw RuntimeException("Retry failed")
            }
            mockk(relaxed = true)
        }

        // When
        val jobId = bulkRetryService.startBulkRetry(request)
        Thread.sleep(500)

        // Then
        val progress = bulkRetryService.getProgress(jobId)
        assertNotNull(progress)
        assertTrue((progress?.successCount ?: 0) > 0)
        assertTrue((progress?.failureCount ?: 0) > 0)
        assertEquals(10, progress?.totalProcessed)
    }

    // ========================================
    // 3. Result Retrieval Tests
    // ========================================

    @Test
    fun `should return detailed results after completion`() {
        // Given
        val request = BulkRetryRequest(
            statuses = listOf(PaymentStatus.FAILED),
            batchSize = 10
        )

        val failedPayments = createFailedPayments(5)
        every { 
            paymentRepository.findByStatusInAndProviderAndCreatedAtBetween(
                any(), any(), any(), any(), any()
            ) 
        } returns PageImpl(failedPayments)

        coEvery { orchestrationService.retryPayment(any()) } returns mockk(relaxed = true)

        // When
        val jobId = bulkRetryService.startBulkRetry(request)
        Thread.sleep(300)

        // Then
        val results = bulkRetryService.getResults(jobId)
        assertNotNull(results)
        assertEquals(jobId, results?.jobId)
        assertEquals(BulkRetryStatus.COMPLETED, results?.status)
        assertEquals(5, results?.totalProcessed)
        assertEquals(5, results?.successCount)
        assertEquals(0, results?.failureCount)
        assertNotNull(results?.startTime)
        assertNotNull(results?.endTime)
        assertTrue((results?.duration ?: 0) > 0)
    }

    @Test
    fun `should include failure details in results`() {
        // Given
        val request = BulkRetryRequest(
            statuses = listOf(PaymentStatus.FAILED),
            batchSize = 5
        )

        val failedPayments = createFailedPayments(3)
        every { 
            paymentRepository.findByStatusInAndProviderAndCreatedAtBetween(
                any(), any(), any(), any(), any()
            ) 
        } returns PageImpl(failedPayments)

        val errorMessage = "Provider timeout"
        coEvery { orchestrationService.retryPayment(any()) } throws RuntimeException(errorMessage)

        // When
        val jobId = bulkRetryService.startBulkRetry(request)
        Thread.sleep(300)

        // Then
        val results = bulkRetryService.getResults(jobId)
        assertNotNull(results)
        assertEquals(3, results?.failureCount)
        assertEquals(3, results?.failures?.size)
        assertTrue(results?.failures?.values?.all { it.contains(errorMessage) } ?: false)
    }

    @Test
    fun `should return null results for non-existent job`() {
        // When
        val results = bulkRetryService.getResults("non_existent_job")

        // Then
        assertNull(results)
    }

    // ========================================
    // 4. Job Cancellation Tests
    // ========================================

    @Test
    fun `should cancel running job successfully`() {
        // Given
        val request = BulkRetryRequest(
            statuses = listOf(PaymentStatus.FAILED),
            batchSize = 10
        )

        val failedPayments = createFailedPayments(100)
        every { 
            paymentRepository.findByStatusInAndProviderAndCreatedAtBetween(
                any(), any(), any(), any(), any()
            ) 
        } returns PageImpl(failedPayments)

        coEvery { orchestrationService.retryPayment(any()) } coAnswers {
            delay(100) // Slow processing
            mockk(relaxed = true)
        }

        // When
        val jobId = bulkRetryService.startBulkRetry(request)
        Thread.sleep(200) // Let it start processing
        
        val cancelled = bulkRetryService.cancelJob(jobId)

        // Then
        assertTrue(cancelled)
        
        Thread.sleep(100)
        val progress = bulkRetryService.getProgress(jobId)
        assertEquals(BulkRetryStatus.CANCELLED, progress?.status)
    }

    @Test
    fun `should not cancel completed job`() {
        // Given
        val request = BulkRetryRequest(
            statuses = listOf(PaymentStatus.FAILED),
            batchSize = 10
        )

        val failedPayments = createFailedPayments(2)
        every { 
            paymentRepository.findByStatusInAndProviderAndCreatedAtBetween(
                any(), any(), any(), any(), any()
            ) 
        } returns PageImpl(failedPayments)

        coEvery { orchestrationService.retryPayment(any()) } returns mockk(relaxed = true)

        // When
        val jobId = bulkRetryService.startBulkRetry(request)
        Thread.sleep(300) // Wait for completion
        
        val cancelled = bulkRetryService.cancelJob(jobId)

        // Then
        assertFalse(cancelled)
        
        val results = bulkRetryService.getResults(jobId)
        assertEquals(BulkRetryStatus.COMPLETED, results?.status)
    }

    @Test
    fun `should return false when cancelling non-existent job`() {
        // When
        val cancelled = bulkRetryService.cancelJob("non_existent_job")

        // Then
        assertFalse(cancelled)
    }

    // ========================================
    // 5. Batch Processing Tests
    // ========================================

    @Test
    fun `should respect batch size configuration`() {
        // Given
        val batchSize = 3
        val request = BulkRetryRequest(
            statuses = listOf(PaymentStatus.FAILED),
            batchSize = batchSize
        )

        val failedPayments = createFailedPayments(10)
        every { 
            paymentRepository.findByStatusInAndProviderAndCreatedAtBetween(
                any(), any(), any(), any(), capture(slot<Pageable>())
            ) 
        } returns PageImpl(failedPayments)

        coEvery { orchestrationService.retryPayment(any()) } returns mockk(relaxed = true)

        // When
        val jobId = bulkRetryService.startBulkRetry(request)
        Thread.sleep(300)

        // Then
        verify { 
            paymentRepository.findByStatusInAndProviderAndCreatedAtBetween(
                any(), any(), any(), any(), match { it.pageSize == batchSize }
            ) 
        }
    }

    @Test
    fun `should handle large batch sizes`() {
        // Given
        val request = BulkRetryRequest(
            statuses = listOf(PaymentStatus.FAILED),
            batchSize = 1000
        )

        val failedPayments = createFailedPayments(500)
        every { 
            paymentRepository.findByStatusInAndProviderAndCreatedAtBetween(
                any(), any(), any(), any(), any()
            ) 
        } returns PageImpl(failedPayments)

        coEvery { orchestrationService.retryPayment(any()) } returns mockk(relaxed = true)

        // When
        val jobId = bulkRetryService.startBulkRetry(request)
        Thread.sleep(500)

        // Then
        val results = bulkRetryService.getResults(jobId)
        assertNotNull(results)
        assertEquals(500, results?.totalProcessed)
    }

    // ========================================
    // 6. Error Handling Tests
    // ========================================

    @Test
    fun `should handle partial failures gracefully`() {
        // Given
        val request = BulkRetryRequest(
            statuses = listOf(PaymentStatus.FAILED),
            batchSize = 5
        )

        val failedPayments = createFailedPayments(10)
        every { 
            paymentRepository.findByStatusInAndProviderAndCreatedAtBetween(
                any(), any(), any(), any(), any()
            ) 
        } returns PageImpl(failedPayments)

        // Fail every other payment
        var callCount = 0
        coEvery { orchestrationService.retryPayment(any()) } coAnswers {
            callCount++
            if (callCount % 2 == 0) {
                throw RuntimeException("Retry failed")
            }
            mockk(relaxed = true)
        }

        // When
        val jobId = bulkRetryService.startBulkRetry(request)
        Thread.sleep(500)

        // Then
        val results = bulkRetryService.getResults(jobId)
        assertNotNull(results)
        assertEquals(BulkRetryStatus.COMPLETED, results?.status)
        assertEquals(10, results?.totalProcessed)
        assertEquals(5, results?.successCount)
        assertEquals(5, results?.failureCount)
    }

    @Test
    fun `should handle repository errors`() {
        // Given
        val request = BulkRetryRequest(
            statuses = listOf(PaymentStatus.FAILED)
        )

        every { 
            paymentRepository.findByStatusInAndProviderAndCreatedAtBetween(
                any(), any(), any(), any(), any()
            ) 
        } throws RuntimeException("Database error")

        // When
        val jobId = bulkRetryService.startBulkRetry(request)
        Thread.sleep(300)

        // Then
        val results = bulkRetryService.getResults(jobId)
        assertNotNull(results)
        assertEquals(BulkRetryStatus.FAILED, results?.status)
        assertNotNull(results?.errorMessage)
        assertTrue(results?.errorMessage?.contains("Database error") ?: false)
    }

    @Test
    fun `should continue processing after individual payment failures`() {
        // Given
        val request = BulkRetryRequest(
            statuses = listOf(PaymentStatus.FAILED),
            batchSize = 5
        )

        val failedPayments = createFailedPayments(8)
        every { 
            paymentRepository.findByStatusInAndProviderAndCreatedAtBetween(
                any(), any(), any(), any(), any()
            ) 
        } returns PageImpl(failedPayments)

        // Fail the 3rd payment
        var callCount = 0
        coEvery { orchestrationService.retryPayment(any()) } coAnswers {
            callCount++
            if (callCount == 3) {
                throw RuntimeException("Payment 3 failed")
            }
            mockk(relaxed = true)
        }

        // When
        val jobId = bulkRetryService.startBulkRetry(request)
        Thread.sleep(500)

        // Then
        val results = bulkRetryService.getResults(jobId)
        assertNotNull(results)
        assertEquals(8, results?.totalProcessed)
        assertEquals(7, results?.successCount)
        assertEquals(1, results?.failureCount)
    }

    // ========================================
    // 7. Concurrent Operations Tests
    // ========================================

    @Test
    fun `should handle multiple concurrent jobs`() {
        // Given
        val request1 = BulkRetryRequest(statuses = listOf(PaymentStatus.FAILED))
        val request2 = BulkRetryRequest(statuses = listOf(PaymentStatus.PENDING))

        val failedPayments = createFailedPayments(5)
        every { 
            paymentRepository.findByStatusInAndProviderAndCreatedAtBetween(
                any(), any(), any(), any(), any()
            ) 
        } returns PageImpl(failedPayments)

        coEvery { orchestrationService.retryPayment(any()) } returns mockk(relaxed = true)

        // When
        val jobId1 = bulkRetryService.startBulkRetry(request1)
        val jobId2 = bulkRetryService.startBulkRetry(request2)

        Thread.sleep(300)

        // Then
        assertNotEquals(jobId1, jobId2)
        
        val progress1 = bulkRetryService.getProgress(jobId1)
        val progress2 = bulkRetryService.getProgress(jobId2)
        
        assertNotNull(progress1)
        assertNotNull(progress2)
    }

    @Test
    fun `should list all active jobs`() {
        // Given
        val request = BulkRetryRequest(
            statuses = listOf(PaymentStatus.FAILED),
            batchSize = 10
        )

        val failedPayments = createFailedPayments(50)
        every { 
            paymentRepository.findByStatusInAndProviderAndCreatedAtBetween(
                any(), any(), any(), any(), any()
            ) 
        } returns PageImpl(failedPayments)

        coEvery { orchestrationService.retryPayment(any()) } coAnswers {
            delay(100)
            mockk(relaxed = true)
        }

        // When
        val jobId1 = bulkRetryService.startBulkRetry(request)
        val jobId2 = bulkRetryService.startBulkRetry(request)
        
        Thread.sleep(200)

        // Then
        val activeJobs = bulkRetryService.getActiveJobs()
        assertTrue(activeJobs.size >= 2)
        assertTrue(activeJobs.any { it.jobId == jobId1 })
        assertTrue(activeJobs.any { it.jobId == jobId2 })
    }

    // ========================================
    // 8. Edge Cases Tests
    // ========================================

    @Test
    fun `should handle job cleanup after completion`() {
        // Given
        val request = BulkRetryRequest(
            statuses = listOf(PaymentStatus.FAILED),
            batchSize = 5
        )

        val failedPayments = createFailedPayments(3)
        every { 
            paymentRepository.findByStatusInAndProviderAndCreatedAtBetween(
                any(), any(), any(), any(), any()
            ) 
        } returns PageImpl(failedPayments)

        coEvery { orchestrationService.retryPayment(any()) } returns mockk(relaxed = true)

        // When
        val jobId = bulkRetryService.startBulkRetry(request)
        Thread.sleep(300)

        // Trigger cleanup
        bulkRetryService.cleanupOldJobs()

        // Then - job should still be accessible immediately after completion
        val results = bulkRetryService.getResults(jobId)
        assertNotNull(results)
    }

    @Test
    fun `should filter by provider correctly`() {
        // Given
        val request = BulkRetryRequest(
            statuses = listOf(PaymentStatus.FAILED),
            provider = Provider.PROVIDER_A
        )

        val failedPayments = createFailedPayments(5, Provider.PROVIDER_A)
        every { 
            paymentRepository.findByStatusInAndProviderAndCreatedAtBetween(
                any(), Provider.PROVIDER_A, any(), any(), any()
            ) 
        } returns PageImpl(failedPayments)

        coEvery { orchestrationService.retryPayment(any()) } returns mockk(relaxed = true)

        // When
        val jobId = bulkRetryService.startBulkRetry(request)
        Thread.sleep(300)

        // Then
        verify { 
            paymentRepository.findByStatusInAndProviderAndCreatedAtBetween(
                any(), Provider.PROVIDER_A, any(), any(), any()
            ) 
        }
    }

    @Test
    fun `should filter by time range correctly`() {
        // Given
        val startTime = Instant.now().minusSeconds(7200)
        val endTime = Instant.now().minusSeconds(3600)
        
        val request = BulkRetryRequest(
            statuses = listOf(PaymentStatus.FAILED),
            startTime = startTime,
            endTime = endTime
        )

        val failedPayments = createFailedPayments(5)
        every { 
            paymentRepository.findByStatusInAndProviderAndCreatedAtBetween(
                any(), any(), startTime, endTime, any()
            ) 
        } returns PageImpl(failedPayments)

        coEvery { orchestrationService.retryPayment(any()) } returns mockk(relaxed = true)

        // When
        val jobId = bulkRetryService.startBulkRetry(request)
        Thread.sleep(300)

        // Then
        verify { 
            paymentRepository.findByStatusInAndProviderAndCreatedAtBetween(
                any(), any(), startTime, endTime, any()
            ) 
        }
    }

    // ========================================
    // Helper Methods
    // ========================================

    private fun createFailedPayments(count: Int, provider: Provider = Provider.PROVIDER_A): List<Payment> {
        return (1..count).map { index ->
            Payment(
                id = "pay_$index",
                amount = BigDecimal("100.00"),
                currency = "USD",
                status = PaymentStatus.FAILED,
                provider = provider,
                merchantId = "merchant_123",
                customerId = "customer_456",
                idempotencyKey = "idem_$index",
                createdAt = Instant.now().minusSeconds(3600)
            )
        }
    }
}


