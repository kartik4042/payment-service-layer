package com.payment.orchestration.audit

import jakarta.persistence.*
import java.time.Instant

/**
 * Audit Event Log
 * 
 * Persistent storage for all payment-related events for compliance and audit trail.
 * 
 * Purpose:
 * - Regulatory compliance (PCI-DSS, GDPR, SOX)
 * - Forensic analysis and dispute resolution
 * - Security incident investigation
 * - Business intelligence and analytics
 * - Audit trail for financial reconciliation
 * 
 * Retention Policy:
 * - Active: 90 days (hot storage)
 * - Archive: 7 years (cold storage)
 * - Compliance: Per regulatory requirements
 * 
 * Data Protection:
 * - Immutable records (no updates/deletes)
 * - Encrypted at rest
 * - Access logging
 * - Role-based access control
 * 
 * @property id Unique event log identifier
 * @property eventId Original event identifier
 * @property eventType Type of event (PaymentCreated, PaymentFailed, etc.)
 * @property paymentId Payment identifier
 * @property aggregateVersion Version of the payment aggregate
 * @property eventData Complete event data in JSON format
 * @property timestamp When the event occurred
 * @property userId User who triggered the event (if applicable)
 * @property ipAddress IP address of the request
 * @property userAgent User agent string
 * @property correlationId Request correlation ID for tracing
 * @property metadata Additional metadata
 * @property createdAt When the log entry was created
 */
@Entity
@Table(
    name = "audit_event_log",
    indexes = [
        Index(name = "idx_payment_id", columnList = "payment_id"),
        Index(name = "idx_event_type", columnList = "event_type"),
        Index(name = "idx_timestamp", columnList = "timestamp"),
        Index(name = "idx_correlation_id", columnList = "correlation_id"),
        Index(name = "idx_created_at", columnList = "created_at")
    ]
)
data class AuditEventLog(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "event_id", nullable = false, length = 100)
    val eventId: String,

    @Column(name = "event_type", nullable = false, length = 100)
    val eventType: String,

    @Column(name = "payment_id", nullable = false, length = 100)
    val paymentId: String,

    @Column(name = "aggregate_version", nullable = false)
    val aggregateVersion: Int,

    @Column(name = "event_data", nullable = false, columnDefinition = "TEXT")
    val eventData: String,

    @Column(name = "timestamp", nullable = false)
    val timestamp: Instant,

    @Column(name = "user_id", length = 100)
    val userId: String? = null,

    @Column(name = "ip_address", length = 45)
    val ipAddress: String? = null,

    @Column(name = "user_agent", length = 500)
    val userAgent: String? = null,

    @Column(name = "correlation_id", length = 100)
    val correlationId: String? = null,

    @Column(name = "metadata", columnDefinition = "TEXT")
    val metadata: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
) {
    /**
     * Convert to summary for API responses
     */
    fun toSummary(): AuditEventSummary {
        return AuditEventSummary(
            id = id!!,
            eventId = eventId,
            eventType = eventType,
            paymentId = paymentId,
            timestamp = timestamp,
            createdAt = createdAt
        )
    }
}

/**
 * Audit Event Summary
 * 
 * Lightweight representation for API responses.
 */
data class AuditEventSummary(
    val id: Long,
    val eventId: String,
    val eventType: String,
    val paymentId: String,
    val timestamp: Instant,
    val createdAt: Instant
)

/**
 * Audit Event Query Parameters
 * 
 * Parameters for querying audit events.
 */
data class AuditEventQuery(
    val paymentId: String? = null,
    val eventType: String? = null,
    val startTime: Instant? = null,
    val endTime: Instant? = null,
    val userId: String? = null,
    val correlationId: String? = null,
    val page: Int = 0,
    val size: Int = 50
) {
    init {
        require(size in 1..1000) { "Page size must be between 1 and 1000" }
        require(page >= 0) { "Page number must be non-negative" }
    }
}

/**
 * Audit Event Statistics
 * 
 * Statistics about audit events.
 */
data class AuditEventStatistics(
    val totalEvents: Long,
    val eventsByType: Map<String, Long>,
    val eventsLast24Hours: Long,
    val eventsLast7Days: Long,
    val oldestEvent: Instant?,
    val newestEvent: Instant?
)


