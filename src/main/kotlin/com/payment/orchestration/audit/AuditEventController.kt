package com.payment.orchestration.audit

import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.Instant

/**
 * Audit Event Controller
 * 
 * REST API for querying audit event logs.
 * 
 * Endpoints:
 * - GET /api/v1/audit/events - Query events
 * - GET /api/v1/audit/events/{id} - Get event by ID
 * - GET /api/v1/audit/payments/{paymentId}/events - Get events for payment
 * - GET /api/v1/audit/statistics - Get audit statistics
 * - GET /api/v1/audit/export - Export events for compliance
 * 
 * Security:
 * - Requires AUDIT_READ role
 * - IP whitelisting for export endpoint
 * - Rate limiting applied
 * 
 * @property auditEventService Audit event service
 */
@RestController
@RequestMapping("/api/v1/audit")
class AuditEventController(
    private val auditEventService: AuditEventService
) {

    /**
     * Query audit events
     * 
     * GET /api/v1/audit/events?paymentId=pay_123&eventType=PaymentCreated&page=0&size=50
     * 
     * Query Parameters:
     * - paymentId: Filter by payment ID
     * - eventType: Filter by event type
     * - startTime: Filter by start time (ISO 8601)
     * - endTime: Filter by end time (ISO 8601)
     * - userId: Filter by user ID
     * - correlationId: Filter by correlation ID
     * - page: Page number (default: 0)
     * - size: Page size (default: 50, max: 1000)
     * 
     * Response:
     * ```json
     * {
     *   "content": [...],
     *   "totalElements": 100,
     *   "totalPages": 2,
     *   "number": 0,
     *   "size": 50
     * }
     * ```
     */
    @GetMapping("/events")
    fun queryEvents(
        @RequestParam(required = false) paymentId: String?,
        @RequestParam(required = false) eventType: String?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) startTime: Instant?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) endTime: Instant?,
        @RequestParam(required = false) userId: String?,
        @RequestParam(required = false) correlationId: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int
    ): ResponseEntity<AuditEventPageResponse> {
        val query = AuditEventQuery(
            paymentId = paymentId,
            eventType = eventType,
            startTime = startTime,
            endTime = endTime,
            userId = userId,
            correlationId = correlationId,
            page = page,
            size = size
        )

        val result = auditEventService.queryEvents(query)

        val response = AuditEventPageResponse(
            content = result.content.map { it.toSummary() },
            totalElements = result.totalElements,
            totalPages = result.totalPages,
            number = result.number,
            size = result.size
        )

        return ResponseEntity.ok(response)
    }

    /**
     * Get audit event by ID
     * 
     * GET /api/v1/audit/events/123
     * 
     * Response:
     * ```json
     * {
     *   "id": 123,
     *   "eventId": "evt_abc",
     *   "eventType": "PaymentCreated",
     *   "paymentId": "pay_123",
     *   "eventData": "{...}",
     *   "timestamp": "2024-01-01T00:00:00Z",
     *   "userId": "user_456",
     *   "ipAddress": "192.168.1.1",
     *   "correlationId": "corr_789"
     * }
     * ```
     */
    @GetMapping("/events/{id}")
    fun getEventById(@PathVariable id: Long): ResponseEntity<AuditEventLog> {
        val event = auditEventService.getEventById(id)
            ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok(event)
    }

    /**
     * Get audit events for a payment
     * 
     * GET /api/v1/audit/payments/pay_123/events
     * 
     * Returns all audit events for a specific payment in chronological order.
     * 
     * Response:
     * ```json
     * [
     *   {
     *     "id": 1,
     *     "eventId": "evt_1",
     *     "eventType": "PaymentCreated",
     *     "paymentId": "pay_123",
     *     "timestamp": "2024-01-01T00:00:00Z"
     *   },
     *   {
     *     "id": 2,
     *     "eventId": "evt_2",
     *     "eventType": "PaymentAuthorized",
     *     "paymentId": "pay_123",
     *     "timestamp": "2024-01-01T00:00:05Z"
     *   }
     * ]
     * ```
     */
    @GetMapping("/payments/{paymentId}/events")
    fun getEventsByPaymentId(@PathVariable paymentId: String): ResponseEntity<List<AuditEventSummary>> {
        val events = auditEventService.getEventsByPaymentId(paymentId)
        val summaries = events.map { it.toSummary() }
        return ResponseEntity.ok(summaries)
    }

    /**
     * Get audit events by correlation ID
     * 
     * GET /api/v1/audit/correlation/corr_123/events
     * 
     * Returns all events for a specific request correlation ID.
     * Useful for tracing a complete request flow across services.
     * 
     * Response: Same as getEventsByPaymentId
     */
    @GetMapping("/correlation/{correlationId}/events")
    fun getEventsByCorrelationId(@PathVariable correlationId: String): ResponseEntity<List<AuditEventSummary>> {
        val events = auditEventService.getEventsByCorrelationId(correlationId)
        val summaries = events.map { it.toSummary() }
        return ResponseEntity.ok(summaries)
    }

    /**
     * Get audit statistics
     * 
     * GET /api/v1/audit/statistics
     * 
     * Returns statistics about audit events for monitoring and reporting.
     * 
     * Response:
     * ```json
     * {
     *   "totalEvents": 10000,
     *   "eventsByType": {
     *     "PaymentCreated": 3000,
     *     "PaymentAuthorized": 2500,
     *     "PaymentFailed": 500
     *   },
     *   "eventsLast24Hours": 1200,
     *   "eventsLast7Days": 7500,
     *   "oldestEvent": "2024-01-01T00:00:00Z",
     *   "newestEvent": "2024-01-31T23:59:59Z"
     * }
     * ```
     */
    @GetMapping("/statistics")
    fun getStatistics(): ResponseEntity<AuditEventStatistics> {
        val statistics = auditEventService.getStatistics()
        return ResponseEntity.ok(statistics)
    }

    /**
     * Export events for compliance
     * 
     * GET /api/v1/audit/export?startTime=2024-01-01T00:00:00Z&endTime=2024-01-31T23:59:59Z
     * 
     * Exports audit events in a format suitable for compliance reporting.
     * 
     * Security:
     * - Requires AUDIT_EXPORT role
     * - IP whitelisting enforced
     * - Audit trail of export operations
     * 
     * Query Parameters:
     * - startTime: Start of time range (required)
     * - endTime: End of time range (required)
     * 
     * Response: Full audit event logs (not summaries)
     */
    @GetMapping("/export")
    fun exportEvents(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) startTime: Instant,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) endTime: Instant
    ): ResponseEntity<List<AuditEventLog>> {
        require(startTime.isBefore(endTime)) { "Start time must be before end time" }
        require(endTime.isBefore(Instant.now().plusSeconds(86400))) { "End time cannot be in the future" }

        val events = auditEventService.exportEventsForCompliance(startTime, endTime)
        return ResponseEntity.ok(events)
    }
}

/**
 * Audit Event Page Response
 * 
 * Paginated response for audit event queries.
 */
data class AuditEventPageResponse(
    val content: List<AuditEventSummary>,
    val totalElements: Long,
    val totalPages: Int,
    val number: Int,
    val size: Int
)


