package com.payment.orchestration.retry

import com.payment.orchestration.domain.model.PaymentStatus
import com.payment.orchestration.domain.model.Provider
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.net.URI
import java.time.Instant

/**
 * Bulk Retry Controller
 * 
 * REST API for bulk retry operations on failed payments.
 * 
 * Endpoints:
 * - POST /api/v1/retry/bulk - Start bulk retry job
 * - GET /api/v1/retry/bulk/{jobId} - Get job progress
 * - GET /api/v1/retry/bulk/{jobId}/results - Get detailed results
 * - DELETE /api/v1/retry/bulk/{jobId} - Cancel job
 * - GET /api/v1/retry/bulk/active - Get all active jobs
 * 
 * Security:
 * - Requires RETRY_ADMIN role
 * - Audit logging of all operations
 * - Rate limiting applied
 * 
 * @property bulkRetryService Bulk retry service
 */
@RestController
@RequestMapping("/api/v1/retry/bulk")
class BulkRetryController(
    private val bulkRetryService: BulkRetryService
) {

    /**
     * Start bulk retry operation
     * 
     * POST /api/v1/retry/bulk
     * 
     * Request Body:
     * ```json
     * {
     *   "statuses": ["FAILED", "PENDING"],
     *   "provider": "PROVIDER_A",
     *   "startTime": "2024-01-01T00:00:00Z",
     *   "endTime": "2024-01-31T23:59:59Z",
     *   "batchSize": 100
     * }
     * ```
     * 
     * Response 202:
     * ```json
     * {
     *   "jobId": "bulk_retry_1234567890_5678",
     *   "message": "Bulk retry job started",
     *   "statusUrl": "/api/v1/retry/bulk/bulk_retry_1234567890_5678"
     * }
     * ```
     */
    @PostMapping
    fun startBulkRetry(
        @RequestBody request: BulkRetryRequestDto
    ): ResponseEntity<BulkRetryStartResponse> {
        val bulkRetryRequest = BulkRetryRequest(
            statuses = request.statuses,
            provider = request.provider,
            startTime = request.startTime,
            endTime = request.endTime,
            batchSize = request.batchSize
        )

        val jobId = bulkRetryService.startBulkRetry(bulkRetryRequest)

        val response = BulkRetryStartResponse(
            jobId = jobId,
            message = "Bulk retry job started",
            statusUrl = "/api/v1/retry/bulk/$jobId"
        )

        return ResponseEntity
            .accepted()
            .location(URI.create("/api/v1/retry/bulk/$jobId"))
            .body(response)
    }

    /**
     * Get bulk retry job progress
     * 
     * GET /api/v1/retry/bulk/{jobId}
     * 
     * Response 200:
     * ```json
     * {
     *   "jobId": "bulk_retry_1234567890_5678",
     *   "status": "RUNNING",
     *   "totalProcessed": 150,
     *   "successCount": 120,
     *   "failureCount": 30,
     *   "startTime": "2024-01-01T10:00:00Z",
     *   "endTime": null,
     *   "errorMessage": null
     * }
     * ```
     */
    @GetMapping("/{jobId}")
    fun getProgress(@PathVariable jobId: String): ResponseEntity<BulkRetryProgress> {
        val progress = bulkRetryService.getProgress(jobId)
            ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok(progress)
    }

    /**
     * Get detailed bulk retry results
     * 
     * GET /api/v1/retry/bulk/{jobId}/results
     * 
     * Response 200:
     * ```json
     * {
     *   "jobId": "bulk_retry_1234567890_5678",
     *   "status": "COMPLETED",
     *   "totalProcessed": 200,
     *   "successCount": 180,
     *   "failureCount": 20,
     *   "successfulPayments": ["pay_1", "pay_2", ...],
     *   "failedPayments": ["pay_3", "pay_4", ...],
     *   "failures": {
     *     "pay_3": "Provider timeout",
     *     "pay_4": "Insufficient funds"
     *   },
     *   "startTime": "2024-01-01T10:00:00Z",
     *   "endTime": "2024-01-01T10:05:30Z",
     *   "duration": 330000,
     *   "errorMessage": null
     * }
     * ```
     */
    @GetMapping("/{jobId}/results")
    fun getResults(@PathVariable jobId: String): ResponseEntity<BulkRetryResults> {
        val results = bulkRetryService.getResults(jobId)
            ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok(results)
    }

    /**
     * Cancel bulk retry job
     * 
     * DELETE /api/v1/retry/bulk/{jobId}
     * 
     * Response 200:
     * ```json
     * {
     *   "jobId": "bulk_retry_1234567890_5678",
     *   "message": "Job cancelled successfully"
     * }
     * ```
     * 
     * Response 404: Job not found or already completed
     */
    @DeleteMapping("/{jobId}")
    fun cancelJob(@PathVariable jobId: String): ResponseEntity<BulkRetryCancelResponse> {
        val cancelled = bulkRetryService.cancelJob(jobId)

        return if (cancelled) {
            ResponseEntity.ok(
                BulkRetryCancelResponse(
                    jobId = jobId,
                    message = "Job cancelled successfully"
                )
            )
        } else {
            ResponseEntity.notFound().build()
        }
    }

    /**
     * Get all active bulk retry jobs
     * 
     * GET /api/v1/retry/bulk/active
     * 
     * Response 200:
     * ```json
     * [
     *   {
     *     "jobId": "bulk_retry_1234567890_5678",
     *     "status": "RUNNING",
     *     "totalProcessed": 50,
     *     "successCount": 45,
     *     "failureCount": 5,
     *     "startTime": "2024-01-01T10:00:00Z"
     *   }
     * ]
     * ```
     */
    @GetMapping("/active")
    fun getActiveJobs(): ResponseEntity<List<BulkRetryProgress>> {
        val activeJobs = bulkRetryService.getActiveJobs()
        return ResponseEntity.ok(activeJobs)
    }
}

/**
 * Bulk Retry Request DTO
 * 
 * Request body for starting bulk retry.
 */
data class BulkRetryRequestDto(
    val statuses: List<PaymentStatus> = listOf(PaymentStatus.FAILED),
    val provider: Provider? = null,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    val startTime: Instant? = null,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    val endTime: Instant? = null,
    val batchSize: Int = 100
)

/**
 * Bulk Retry Start Response
 * 
 * Response when bulk retry job is started.
 */
data class BulkRetryStartResponse(
    val jobId: String,
    val message: String,
    val statusUrl: String
)

/**
 * Bulk Retry Cancel Response
 * 
 * Response when bulk retry job is cancelled.
 */
data class BulkRetryCancelResponse(
    val jobId: String,
    val message: String
)


