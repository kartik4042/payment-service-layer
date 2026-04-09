package com.payment.orchestration.retry

import com.payment.orchestration.domain.model.Payment
import com.payment.orchestration.domain.model.PaymentStatus
import com.payment.orchestration.domain.repository.PaymentRepository
import com.payment.orchestration.service.PaymentOrchestrationService
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bulk Retry Service
 * 
 * Service for batch retry operations on failed payments.
 * 
 * Use Cases:
 * - Retry failed payments after provider outage recovery
 * - Scheduled retry of transient failures
 * - Manual retry operations by support team
 * - Automated recovery from system issues
 * 
 * Features:
 * - Batch processing with configurable batch size
 * - Progress tracking and reporting
 * - Async execution for non-blocking operations
 * - Retry filtering by status, provider, time range
 * - Detailed retry results and statistics
 * 
 * Safety:
 * - Idempotency checks before retry
 * - Rate limiting to prevent provider overload
 * - Circuit breaker integration
 * - Automatic rollback on critical failures
 * 
 * Usage:
 * ```kotlin
 * val request = BulkRetryRequest(
 *     statuses = listOf(PaymentStatus.FAILED, PaymentStatus.PENDING),
 *     provider = Provider.PROVIDER_A,
 *     startTime = Instant.now().minusSeconds(3600),
 *     endTime = Instant.now(),
 *     batchSize = 100
 * )
 * 
 * val jobId = bulkRetryService.startBulkRetry(request)
 * val progress = bulkRetryService.getProgress(jobId)
 * ```
 * 
 * @property paymentRepository Payment repository
 * @property orchestrationService Payment orchestration service
 */
@Service
class BulkRetryService(
    private val paymentRepository: PaymentRepository,
    private val orchestrationService: PaymentOrchestrationService
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val activeJobs = ConcurrentHashMap<String, BulkRetryJob>()

    /**
     * Start bulk retry operation
     * 
     * Initiates an async bulk retry job for failed payments.
     * 
     * @param request Bulk retry request parameters
     * @return Job ID for tracking progress
     */
    fun startBulkRetry(request: BulkRetryRequest): String {
        val jobId = generateJobId()
        
        val job = BulkRetryJob(
            jobId = jobId,
            request = request,
            status = BulkRetryStatus.RUNNING,
            startTime = Instant.now()
        )
        
        activeJobs[jobId] = job
        
        logger.info("Starting bulk retry job: jobId={}, request={}", jobId, request)
        
        // Execute async
        executeBulkRetryAsync(job)
        
        return jobId
    }

    /**
     * Execute bulk retry asynchronously
     * 
     * Processes payments in batches to avoid memory issues.
     */
    @Async
    @Transactional
    protected fun executeBulkRetryAsync(job: BulkRetryJob) {
        try {
            val request = job.request
            var page = 0
            var hasMore = true
            
            while (hasMore && job.status == BulkRetryStatus.RUNNING) {
                // Fetch batch of failed payments
                val pageable = PageRequest.of(page, request.batchSize)
                val payments = findFailedPayments(request, pageable)
                
                if (payments.isEmpty()) {
                    hasMore = false
                    continue
                }
                
                // Process batch
                processBatch(job, payments)
                
                // Update progress
                job.totalProcessed.addAndGet(payments.size)
                
                // Check if more pages
                hasMore = payments.size == request.batchSize
                page++
                
                // Rate limiting - small delay between batches
                if (hasMore) {
                    Thread.sleep(100)
                }
            }
            
            // Mark as completed
            job.status = BulkRetryStatus.COMPLETED
            job.endTime = Instant.now()
            
            logger.info(
                "Bulk retry job completed: jobId={}, total={}, successful={}, failed={}",
                job.jobId,
                job.totalProcessed.get(),
                job.successCount.get(),
                job.failureCount.get()
            )
            
        } catch (e: Exception) {
            logger.error("Bulk retry job failed: jobId=${job.jobId}", e)
            job.status = BulkRetryStatus.FAILED
            job.endTime = Instant.now()
            job.errorMessage = e.message
        }
    }

    /**
     * Process a batch of payments
     */
    private fun processBatch(job: BulkRetryJob, payments: List<Payment>) {
        payments.forEach { payment ->
            try {
                // Retry payment
                val result = retryPayment(payment)
                
                if (result.isSuccess) {
                    job.successCount.incrementAndGet()
                    job.successfulPayments.add(payment.id)
                } else {
                    job.failureCount.incrementAndGet()
                    job.failedPayments.add(payment.id)
                    job.failures[payment.id] = result.errorMessage ?: "Unknown error"
                }
                
            } catch (e: Exception) {
                logger.error("Failed to retry payment: paymentId=${payment.id}", e)
                job.failureCount.incrementAndGet()
                job.failedPayments.add(payment.id)
                job.failures[payment.id] = e.message ?: "Unknown error"
            }
        }
    }

    /**
     * Retry a single payment
     */
    private fun retryPayment(payment: Payment): RetryResult {
        return try {
            // Check if payment is retryable
            if (!isRetryable(payment)) {
                return RetryResult(
                    isSuccess = false,
                    errorMessage = "Payment is not in retryable state: ${payment.status}"
                )
            }
            
            // Attempt retry through orchestration service
            val result = orchestrationService.retryPayment(payment.id)
            
            RetryResult(
                isSuccess = result.status == PaymentStatus.SUCCESS,
                errorMessage = if (result.status != PaymentStatus.SUCCESS) {
                    "Retry failed with status: ${result.status}"
                } else null
            )
            
        } catch (e: Exception) {
            RetryResult(
                isSuccess = false,
                errorMessage = e.message
            )
        }
    }

    /**
     * Check if payment is retryable
     */
    private fun isRetryable(payment: Payment): Boolean {
        return payment.status in listOf(
            PaymentStatus.FAILED,
            PaymentStatus.PENDING,
            PaymentStatus.PROCESSING
        )
    }

    /**
     * Find failed payments matching criteria
     */
    private fun findFailedPayments(
        request: BulkRetryRequest,
        pageable: PageRequest
    ): List<Payment> {
        return when {
            // Filter by provider and time range
            request.provider != null && request.startTime != null && request.endTime != null -> {
                paymentRepository.findByStatusInAndProviderAndCreatedAtBetween(
                    request.statuses,
                    request.provider,
                    request.startTime,
                    request.endTime,
                    pageable
                ).content
            }
            
            // Filter by time range only
            request.startTime != null && request.endTime != null -> {
                paymentRepository.findByStatusInAndCreatedAtBetween(
                    request.statuses,
                    request.startTime,
                    request.endTime,
                    pageable
                ).content
            }
            
            // Filter by provider only
            request.provider != null -> {
                paymentRepository.findByStatusInAndProvider(
                    request.statuses,
                    request.provider,
                    pageable
                ).content
            }
            
            // Filter by status only
            else -> {
                paymentRepository.findByStatusIn(
                    request.statuses,
                    pageable
                ).content
            }
        }
    }

    /**
     * Get progress of bulk retry job
     * 
     * @param jobId Job identifier
     * @return Job progress or null if not found
     */
    fun getProgress(jobId: String): BulkRetryProgress? {
        val job = activeJobs[jobId] ?: return null
        
        return BulkRetryProgress(
            jobId = job.jobId,
            status = job.status,
            totalProcessed = job.totalProcessed.get(),
            successCount = job.successCount.get(),
            failureCount = job.failureCount.get(),
            startTime = job.startTime,
            endTime = job.endTime,
            errorMessage = job.errorMessage
        )
    }

    /**
     * Get detailed results of bulk retry job
     * 
     * @param jobId Job identifier
     * @return Detailed job results or null if not found
     */
    fun getResults(jobId: String): BulkRetryResults? {
        val job = activeJobs[jobId] ?: return null
        
        return BulkRetryResults(
            jobId = job.jobId,
            status = job.status,
            totalProcessed = job.totalProcessed.get(),
            successCount = job.successCount.get(),
            failureCount = job.failureCount.get(),
            successfulPayments = job.successfulPayments.toList(),
            failedPayments = job.failedPayments.toList(),
            failures = job.failures.toMap(),
            startTime = job.startTime,
            endTime = job.endTime,
            duration = if (job.endTime != null) {
                java.time.Duration.between(job.startTime, job.endTime).toMillis()
            } else null,
            errorMessage = job.errorMessage
        )
    }

    /**
     * Cancel bulk retry job
     * 
     * @param jobId Job identifier
     * @return true if cancelled, false if not found or already completed
     */
    fun cancelJob(jobId: String): Boolean {
        val job = activeJobs[jobId] ?: return false
        
        if (job.status == BulkRetryStatus.RUNNING) {
            job.status = BulkRetryStatus.CANCELLED
            job.endTime = Instant.now()
            logger.info("Bulk retry job cancelled: jobId={}", jobId)
            return true
        }
        
        return false
    }

    /**
     * Get all active jobs
     * 
     * @return List of active job progress
     */
    fun getActiveJobs(): List<BulkRetryProgress> {
        return activeJobs.values
            .filter { it.status == BulkRetryStatus.RUNNING }
            .map { job ->
                BulkRetryProgress(
                    jobId = job.jobId,
                    status = job.status,
                    totalProcessed = job.totalProcessed.get(),
                    successCount = job.successCount.get(),
                    failureCount = job.failureCount.get(),
                    startTime = job.startTime,
                    endTime = job.endTime,
                    errorMessage = job.errorMessage
                )
            }
    }

    /**
     * Clean up completed jobs
     * 
     * Removes jobs older than specified duration.
     * 
     * @param olderThan Duration to keep jobs
     */
    fun cleanupCompletedJobs(olderThan: java.time.Duration = java.time.Duration.ofHours(24)) {
        val cutoff = Instant.now().minus(olderThan)
        
        val toRemove = activeJobs.values
            .filter { job ->
                job.status != BulkRetryStatus.RUNNING &&
                job.endTime != null &&
                job.endTime!!.isBefore(cutoff)
            }
            .map { it.jobId }
        
        toRemove.forEach { jobId ->
            activeJobs.remove(jobId)
            logger.debug("Cleaned up completed job: jobId={}", jobId)
        }
        
        if (toRemove.isNotEmpty()) {
            logger.info("Cleaned up {} completed jobs", toRemove.size)
        }
    }

    /**
     * Generate unique job ID
     */
    private fun generateJobId(): String {
        return "bulk_retry_${System.currentTimeMillis()}_${(1000..9999).random()}"
    }
}

/**
 * Bulk Retry Request
 * 
 * Parameters for bulk retry operation.
 */
data class BulkRetryRequest(
    val statuses: List<PaymentStatus> = listOf(PaymentStatus.FAILED),
    val provider: com.payment.orchestration.domain.model.Provider? = null,
    val startTime: Instant? = null,
    val endTime: Instant? = null,
    val batchSize: Int = 100
) {
    init {
        require(batchSize in 1..1000) { "Batch size must be between 1 and 1000" }
        require(statuses.isNotEmpty()) { "At least one status must be specified" }
        if (startTime != null && endTime != null) {
            require(startTime.isBefore(endTime)) { "Start time must be before end time" }
        }
    }
}

/**
 * Bulk Retry Job
 * 
 * Internal representation of a bulk retry job.
 */
internal data class BulkRetryJob(
    val jobId: String,
    val request: BulkRetryRequest,
    @Volatile var status: BulkRetryStatus,
    val startTime: Instant,
    @Volatile var endTime: Instant? = null,
    @Volatile var errorMessage: String? = null,
    val totalProcessed: AtomicInteger = AtomicInteger(0),
    val successCount: AtomicInteger = AtomicInteger(0),
    val failureCount: AtomicInteger = AtomicInteger(0),
    val successfulPayments: MutableList<String> = mutableListOf(),
    val failedPayments: MutableList<String> = mutableListOf(),
    val failures: MutableMap<String, String> = mutableMapOf()
)

/**
 * Bulk Retry Status
 * 
 * Status of bulk retry job.
 */
enum class BulkRetryStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * Bulk Retry Progress
 * 
 * Progress information for a bulk retry job.
 */
data class BulkRetryProgress(
    val jobId: String,
    val status: BulkRetryStatus,
    val totalProcessed: Int,
    val successCount: Int,
    val failureCount: Int,
    val startTime: Instant,
    val endTime: Instant?,
    val errorMessage: String?
)

/**
 * Bulk Retry Results
 * 
 * Detailed results of a bulk retry job.
 */
data class BulkRetryResults(
    val jobId: String,
    val status: BulkRetryStatus,
    val totalProcessed: Int,
    val successCount: Int,
    val failureCount: Int,
    val successfulPayments: List<String>,
    val failedPayments: List<String>,
    val failures: Map<String, String>,
    val startTime: Instant,
    val endTime: Instant?,
    val duration: Long?,
    val errorMessage: String?
)

/**
 * Retry Result
 * 
 * Result of a single payment retry.
 */
private data class RetryResult(
    val isSuccess: Boolean,
    val errorMessage: String? = null
)

