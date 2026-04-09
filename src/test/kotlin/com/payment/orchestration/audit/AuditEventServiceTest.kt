package com.payment.orchestration.audit

import com.payment.orchestration.domain.model.PaymentMethod
import com.payment.orchestration.domain.model.Provider
import com.payment.orchestration.events.PaymentEvent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested
import org.mockito.kotlin.*
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import java.time.Instant
import java.util.Optional

/**
 * Audit Event Service Tests
 * 
 * Test Coverage:
 * - Event logging
 * - Query operations
 * - Statistics generation
 * - Compliance export
 * - Error handling
 */
@DisplayName("Audit Event Service Tests")
class AuditEventServiceTest {

    private lateinit var repository: AuditEventRepository
    private lateinit var service: AuditEventService

    @BeforeEach
    fun setup() {
        repository = mock()
        service = AuditEventService(repository)
    }

    @Nested
    @DisplayName("Event Logging Tests")
    inner class EventLoggingTests {

        @Test
        @DisplayName("Should log payment event")
        fun testLogEvent() {
            // Given
            val event = PaymentEvent.PaymentCreated(
                eventId = "evt_123",
                timestamp = Instant.now(),
                paymentId = "pay_123",
                aggregateVersion = 1,
                customerId = "cust_456",
                amount = 10000L,
                currency = "USD",
                paymentMethod = PaymentMethod.CARD,
                provider = Provider.PROVIDER_A,
                description = "Test payment",
                metadata = emptyMap()
            )

            val savedLog = AuditEventLog(
                id = 1L,
                eventId = event.eventId,
                eventType = event.eventType,
                paymentId = event.paymentId,
                aggregateVersion = event.aggregateVersion,
                eventData = event.toJson(),
                timestamp = event.timestamp
            )

            whenever(repository.save(any())).thenReturn(savedLog)

            // When
            val result = service.logEvent(
                event = event,
                userId = "user_789",
                ipAddress = "192.168.1.1",
                userAgent = "Mozilla/5.0",
                correlationId = "corr_abc"
            )

            // Then
            assertNotNull(result)
            assertEquals(1L, result.id)
            assertEquals(event.eventId, result.eventId)
            assertEquals(event.eventType, result.eventType)
            assertEquals("user_789", result.userId)
            assertEquals("192.168.1.1", result.ipAddress)
            
            verify(repository).save(any())
        }

        @Test
        @DisplayName("Should log event without optional fields")
        fun testLogEventWithoutOptionalFields() {
            // Given
            val event = PaymentEvent.PaymentFailed(
                eventId = "evt_456",
                timestamp = Instant.now(),
                paymentId = "pay_456",
                aggregateVersion = 2,
                provider = Provider.PROVIDER_B,
                errorCode = "ERROR",
                errorMessage = "Failed",
                failureReason = "Test"
            )

            val savedLog = AuditEventLog(
                id = 2L,
                eventId = event.eventId,
                eventType = event.eventType,
                paymentId = event.paymentId,
                aggregateVersion = event.aggregateVersion,
                eventData = event.toJson(),
                timestamp = event.timestamp
            )

            whenever(repository.save(any())).thenReturn(savedLog)

            // When
            val result = service.logEvent(event)

            // Then
            assertNotNull(result)
            assertNull(result.userId)
            assertNull(result.ipAddress)
            assertNull(result.userAgent)
            assertNull(result.correlationId)
        }

        @Test
        @DisplayName("Should throw exception on logging failure")
        fun testLogEventFailure() {
            // Given
            val event = PaymentEvent.PaymentCreated(
                eventId = "evt_789",
                timestamp = Instant.now(),
                paymentId = "pay_789",
                aggregateVersion = 1,
                customerId = "cust_123",
                amount = 5000L,
                currency = "EUR",
                paymentMethod = PaymentMethod.UPI,
                provider = Provider.PROVIDER_B,
                description = null,
                metadata = emptyMap()
            )

            whenever(repository.save(any())).thenThrow(RuntimeException("Database error"))

            // When/Then
            assertThrows(AuditEventException::class.java) {
                service.logEvent(event)
            }
        }
    }

    @Nested
    @DisplayName("Query Operations Tests")
    inner class QueryOperationsTests {

        @Test
        @DisplayName("Should get events by payment ID")
        fun testGetEventsByPaymentId() {
            // Given
            val paymentId = "pay_123"
            val events = listOf(
                createAuditEventLog(1L, "evt_1", "PaymentCreated", paymentId),
                createAuditEventLog(2L, "evt_2", "PaymentAuthorized", paymentId)
            )

            whenever(repository.findByPaymentIdOrderByTimestampDesc(paymentId))
                .thenReturn(events)

            // When
            val result = service.getEventsByPaymentId(paymentId)

            // Then
            assertEquals(2, result.size)
            assertEquals("evt_1", result[0].eventId)
            assertEquals("evt_2", result[1].eventId)
        }

        @Test
        @DisplayName("Should get events by correlation ID")
        fun testGetEventsByCorrelationId() {
            // Given
            val correlationId = "corr_123"
            val events = listOf(
                createAuditEventLog(1L, "evt_1", "PaymentCreated", "pay_123", correlationId),
                createAuditEventLog(2L, "evt_2", "PaymentCreated", "pay_456", correlationId)
            )

            whenever(repository.findByCorrelationIdOrderByTimestampDesc(correlationId))
                .thenReturn(events)

            // When
            val result = service.getEventsByCorrelationId(correlationId)

            // Then
            assertEquals(2, result.size)
            assertTrue(result.all { it.correlationId == correlationId })
        }

        @Test
        @DisplayName("Should query events with pagination")
        fun testQueryEventsWithPagination() {
            // Given
            val query = AuditEventQuery(
                paymentId = "pay_123",
                page = 0,
                size = 10
            )

            val events = listOf(
                createAuditEventLog(1L, "evt_1", "PaymentCreated", "pay_123"),
                createAuditEventLog(2L, "evt_2", "PaymentAuthorized", "pay_123")
            )

            whenever(repository.findByPaymentIdOrderByTimestampDesc("pay_123"))
                .thenReturn(events)

            // When
            val result = service.queryEvents(query)

            // Then
            assertEquals(2, result.content.size)
            assertEquals(2L, result.totalElements)
        }

        @Test
        @DisplayName("Should get event by ID")
        fun testGetEventById() {
            // Given
            val eventId = 1L
            val event = createAuditEventLog(eventId, "evt_123", "PaymentCreated", "pay_123")

            whenever(repository.findById(eventId)).thenReturn(Optional.of(event))

            // When
            val result = service.getEventById(eventId)

            // Then
            assertNotNull(result)
            assertEquals(eventId, result?.id)
            assertEquals("evt_123", result?.eventId)
        }

        @Test
        @DisplayName("Should return null for non-existent event")
        fun testGetEventByIdNotFound() {
            // Given
            val eventId = 999L
            whenever(repository.findById(eventId)).thenReturn(Optional.empty())

            // When
            val result = service.getEventById(eventId)

            // Then
            assertNull(result)
        }
    }

    @Nested
    @DisplayName("Statistics Tests")
    inner class StatisticsTests {

        @Test
        @DisplayName("Should get audit statistics")
        fun testGetStatistics() {
            // Given
            val now = Instant.now()
            
            whenever(repository.count()).thenReturn(1000L)
            whenever(repository.countByEventType()).thenReturn(
                listOf(
                    arrayOf("PaymentCreated", 400L),
                    arrayOf("PaymentAuthorized", 300L),
                    arrayOf("PaymentFailed", 100L)
                )
            )
            whenever(repository.countSince(any())).thenReturn(50L, 300L)
            whenever(repository.findOldestEventTimestamp()).thenReturn(now.minusSeconds(86400 * 30))
            whenever(repository.findNewestEventTimestamp()).thenReturn(now)

            // When
            val result = service.getStatistics()

            // Then
            assertEquals(1000L, result.totalEvents)
            assertEquals(3, result.eventsByType.size)
            assertEquals(400L, result.eventsByType["PaymentCreated"])
            assertEquals(50L, result.eventsLast24Hours)
            assertEquals(300L, result.eventsLast7Days)
            assertNotNull(result.oldestEvent)
            assertNotNull(result.newestEvent)
        }
    }

    @Nested
    @DisplayName("Compliance Export Tests")
    inner class ComplianceExportTests {

        @Test
        @DisplayName("Should export events for compliance")
        fun testExportEventsForCompliance() {
            // Given
            val startTime = Instant.now().minusSeconds(86400 * 7)
            val endTime = Instant.now()
            
            val events = listOf(
                createAuditEventLog(1L, "evt_1", "PaymentCreated", "pay_123"),
                createAuditEventLog(2L, "evt_2", "PaymentAuthorized", "pay_123"),
                createAuditEventLog(3L, "evt_3", "PaymentCaptured", "pay_123")
            )

            val pageable = PageRequest.of(0, Int.MAX_VALUE)
            val page = PageImpl(events, pageable, events.size.toLong())

            whenever(repository.findByTimestampBetweenOrderByTimestampDesc(
                startTime, endTime, pageable
            )).thenReturn(page)

            // When
            val result = service.exportEventsForCompliance(startTime, endTime)

            // Then
            assertEquals(3, result.size)
            verify(repository).findByTimestampBetweenOrderByTimestampDesc(
                startTime, endTime, pageable
            )
        }
    }

    @Nested
    @DisplayName("Edge Cases Tests")
    inner class EdgeCasesTests {

        @Test
        @DisplayName("Should handle empty query results")
        fun testEmptyQueryResults() {
            // Given
            val paymentId = "pay_nonexistent"
            whenever(repository.findByPaymentIdOrderByTimestampDesc(paymentId))
                .thenReturn(emptyList())

            // When
            val result = service.getEventsByPaymentId(paymentId)

            // Then
            assertTrue(result.isEmpty())
        }

        @Test
        @DisplayName("Should handle query with all parameters")
        fun testQueryWithAllParameters() {
            // Given
            val query = AuditEventQuery(
                paymentId = "pay_123",
                eventType = "PaymentCreated",
                startTime = Instant.now().minusSeconds(3600),
                endTime = Instant.now(),
                userId = "user_456",
                correlationId = "corr_789",
                page = 0,
                size = 20
            )

            whenever(repository.findByPaymentIdOrderByTimestampDesc(any()))
                .thenReturn(emptyList())

            // When
            val result = service.queryEvents(query)

            // Then
            assertNotNull(result)
            assertTrue(result.content.isEmpty())
        }
    }

    // Helper method
    private fun createAuditEventLog(
        id: Long,
        eventId: String,
        eventType: String,
        paymentId: String,
        correlationId: String? = null
    ): AuditEventLog {
        return AuditEventLog(
            id = id,
            eventId = eventId,
            eventType = eventType,
            paymentId = paymentId,
            aggregateVersion = 1,
            eventData = "{}",
            timestamp = Instant.now(),
            correlationId = correlationId
        )
    }
}


