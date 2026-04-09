package com.payment.orchestration.audit

import com.payment.orchestration.events.PaymentEvent
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Audit Event Repository
 * 
 * Data access layer for audit event logs.
 */
@Repository
interface AuditEventRepository : JpaRepository<AuditEventLog, Long> {
    
    /**
     * Find events by payment ID
     */
    fun findByPaymentIdOrderByTimestampDesc(paymentId: String): List<AuditEventLog>
    
    /**
     * Find events by event type
     */
    fun findByEventTypeOrderByTimestampDesc(
        eventType: String,
        pageable: PageRequest
    ): Page<AuditEventLog>
    
    /**
     * Find events by time range
     */
    fun findByTimestampBetweenOrderByTimestampDesc(
        startTime: Instant,
        endTime: Instant,
        pageable: PageRequest
    ): Page<AuditEventLog>
    
    /**
     * Find events by correlation ID
     */
    fun findByCorrelationIdOrderByTimestampDesc(correlationId: String): List<AuditEventLog>
    
    /**
     * Count events by event type
     */
    @Query("SELECT e.eventType, COUNT(e) FROM AuditEventLog e GROUP BY e.eventType")
    fun countByEventType(): List<Array<Any>>
    
    /**
     * Count events in last 24 hours
     */
    @Query("SELECT COUNT(e) FROM AuditEventLog e WHERE e.timestamp >= :since")
    fun countSince(@Param("since") since: Instant): Long
    
    /**
     * Find oldest event timestamp
     */
    @Query("SELECT MIN(e.timestamp) FROM AuditEventLog e")
    fun findOldestEventTimestamp(): Instant?
    
    /**
     * Find newest event timestamp
     */
    @Query("SELECT MAX(e.timestamp) FROM AuditEventLog e")
    fun findNewestEventTimestamp(): Instant?
}

/**
 * Audit Event Service
 * 
 * Service for managing audit event logs.
 * 
 * Features:
 * - Automatic event logging
 * - Query and search capabilities
 * - Statistics and reporting
 * - Compliance support
 * 
 * Usage:
 * ```kotlin
 * @Service
 * class PaymentService(
 *     private val auditEventService: AuditEventService
 * ) {
 *     fun createPayment(request: PaymentRequest): Payment {
 *         val payment = // ... create payment
 *         
 *         // Log event
 *         auditEventService.logEvent(
 *             event = PaymentEvent.PaymentCreated(...),
 *             userId = currentUser.id,
 *             ipAddress = request.ipAddress,
 *             userAgent = request.userAgent
 *         )
 *         
 *         return payment
 *     }
 * }
 * ```
 * 
 * @property repository Audit event repository
 */
@Service
class AuditEventService(
    private val repository: AuditEventRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Log a payment event
     * 
     * Creates an immutable audit log entry for the event.
     * 
     * @param event Payment event to log
     * @param userId User who triggered the event
     * @param ipAddress IP address of the request
     * @param userAgent User agent string
     * @param correlationId Request correlation ID
     * @param metadata Additional metadata
     * @return Saved audit event log
     */
    @Transactional
    fun logEvent(
        event: PaymentEvent,
        userId: String? = null,
        ipAddress: String? = null,
        userAgent: String? = null,
        correlationId: String? = null,
        metadata: Map<String, String>? = null
    ): AuditEventLog {
        try {
            val auditLog = AuditEventLog(
                eventId = event.eventId,
                eventType = event.eventType,
                paymentId = event.paymentId,
                aggregateVersion = event.aggregateVersion,
                eventData = event.toJson(),
                timestamp = event.timestamp,
                userId = userId,
                ipAddress = ipAddress,
                userAgent = userAgent,
                correlationId = correlationId,
                metadata = metadata?.let { serializeMetadata(it) }
            )

            val saved = repository.save(auditLog)
            
            logger.info(
                "Audit event logged: eventId={}, type={}, paymentId={}",
                event.eventId,
                event.eventType,
                event.paymentId
            )
            
            return saved
        } catch (e: Exception) {
            logger.error("Failed to log audit event: ${event.eventId}", e)
            throw AuditEventException("Failed to log audit event", e)
        }
    }

    /**
     * Get audit events for a payment
     * 
     * Returns all audit events for a specific payment in chronological order.
     * 
     * @param paymentId Payment identifier
     * @return List of audit events
     */
    fun getEventsByPaymentId(paymentId: String): List<AuditEventLog> {
        return repository.findByPaymentIdOrderByTimestampDesc(paymentId)
    }

    /**
     * Get audit events by correlation ID
     * 
     * Returns all events for a specific request correlation ID.
     * Useful for tracing a complete request flow.
     * 
     * @param correlationId Request correlation ID
     * @return List of audit events
     */
    fun getEventsByCorrelationId(correlationId: String): List<AuditEventLog> {
        return repository.findByCorrelationIdOrderByTimestampDesc(correlationId)
    }

    /**
     * Query audit events
     * 
     * Flexible query interface for searching audit events.
     * 
     * @param query Query parameters
     * @return Page of audit events
     */
    fun queryEvents(query: AuditEventQuery): Page<AuditEventLog> {
        val pageable = PageRequest.of(
            query.page,
            query.size,
            Sort.by(Sort.Direction.DESC, "timestamp")
        )

        return when {
            // Query by payment ID
            query.paymentId != null -> {
                val events = repository.findByPaymentIdOrderByTimestampDesc(query.paymentId)
                PageImpl(
                    events.drop(query.page * query.size).take(query.size),
                    pageable,
                    events.size.toLong()
                )
            }
            
            // Query by event type
            query.eventType != null -> {
                repository.findByEventTypeOrderByTimestampDesc(query.eventType, pageable)
            }
            
            // Query by time range
            query.startTime != null && query.endTime != null -> {
                repository.findByTimestampBetweenOrderByTimestampDesc(
                    query.startTime,
                    query.endTime,
                    pageable
                )
            }
            
            // Query all
            else -> {
                repository.findAll(pageable)
            }
        }
    }

    /**
     * Get audit event statistics
     * 
     * Returns statistics about audit events for reporting and monitoring.
     * 
     * @return Audit event statistics
     */
    fun getStatistics(): AuditEventStatistics {
        val totalEvents = repository.count()
        
        val eventsByType = repository.countByEventType()
            .associate { it[0] as String to it[1] as Long }
        
        val now = Instant.now()
        val eventsLast24Hours = repository.countSince(now.minusSeconds(86400))
        val eventsLast7Days = repository.countSince(now.minusSeconds(604800))
        
        val oldestEvent = repository.findOldestEventTimestamp()
        val newestEvent = repository.findNewestEventTimestamp()
        
        return AuditEventStatistics(
            totalEvents = totalEvents,
            eventsByType = eventsByType,
            eventsLast24Hours = eventsLast24Hours,
            eventsLast7Days = eventsLast7Days,
            oldestEvent = oldestEvent,
            newestEvent = newestEvent
        )
    }

    /**
     * Get event by ID
     * 
     * @param id Event log ID
     * @return Audit event log or null if not found
     */
    fun getEventById(id: Long): AuditEventLog? {
        return repository.findById(id).orElse(null)
    }

    /**
     * Export events for compliance
     * 
     * Exports audit events in a format suitable for compliance reporting.
     * 
     * @param startTime Start of time range
     * @param endTime End of time range
     * @return List of audit events
     */
    fun exportEventsForCompliance(
        startTime: Instant,
        endTime: Instant
    ): List<AuditEventLog> {
        val pageable = PageRequest.of(0, Int.MAX_VALUE)
        return repository.findByTimestampBetweenOrderByTimestampDesc(
            startTime,
            endTime,
            pageable
        ).content
    }

    /**
     * Serialize metadata to JSON
     */
    private fun serializeMetadata(metadata: Map<String, String>): String {
        return metadata.entries.joinToString(",", "{", "}") { (key, value) ->
            "\"$key\":\"$value\""
        }
    }
}

/**
 * Audit Event Exception
 * 
 * Exception thrown when audit event operations fail.
 */
class AuditEventException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * Page Implementation
 * 
 * Simple page implementation for in-memory paging.
 */
private class PageImpl<T>(
    private val content: List<T>,
    private val pageable: PageRequest,
    private val total: Long
) : Page<T> {
    override fun getContent(): List<T> = content
    override fun getTotalElements(): Long = total
    override fun getTotalPages(): Int = ((total + pageable.pageSize - 1) / pageable.pageSize).toInt()
    override fun getNumber(): Int = pageable.pageNumber
    override fun getSize(): Int = pageable.pageSize
    override fun getNumberOfElements(): Int = content.size
    override fun hasContent(): Boolean = content.isNotEmpty()
    override fun getSort(): Sort = pageable.sort
    override fun isFirst(): Boolean = pageable.pageNumber == 0
    override fun isLast(): Boolean = pageable.pageNumber >= totalPages - 1
    override fun hasNext(): Boolean = pageable.pageNumber < totalPages - 1
    override fun hasPrevious(): Boolean = pageable.pageNumber > 0
    override fun nextPageable(): org.springframework.data.domain.Pageable = pageable.next()
    override fun previousPageable(): org.springframework.data.domain.Pageable = pageable.previous()
    override fun <U : Any?> map(converter: org.springframework.core.convert.converter.Converter<in T, out U>): Page<U> {
        return PageImpl(content.map { converter.convert(it)!! }, pageable, total)
    }
    override fun iterator(): Iterator<T> = content.iterator()
}


