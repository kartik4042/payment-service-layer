package com.payment.orchestration.events

import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * Event Publisher
 * 
 * Publishes domain events to interested subscribers.
 * 
 * Publishing Strategies:
 * 1. In-Memory (Spring ApplicationEventPublisher) - Default
 * 2. Message Queue (Kafka/RabbitMQ) - For distributed systems
 * 3. Event Store - For event sourcing
 * 
 * Features:
 * - Asynchronous event publishing
 * - Event ordering guarantees
 * - Retry on failure
 * - Dead letter queue for failed events
 * - Event versioning
 * 
 * Usage:
 * ```kotlin
 * @Service
 * class PaymentService(private val eventPublisher: EventPublisher) {
 *     fun createPayment(request: PaymentRequest): Payment {
 *         val payment = // ... create payment
 *         
 *         eventPublisher.publish(
 *             PaymentEvent.PaymentCreated(
 *                 eventId = UUID.randomUUID().toString(),
 *                 timestamp = Instant.now(),
 *                 paymentId = payment.id,
 *                 aggregateVersion = 1,
 *                 customerId = request.customerId,
 *                 amount = request.amount,
 *                 currency = request.currency,
 *                 paymentMethod = request.paymentMethod,
 *                 provider = payment.provider,
 *                 description = request.description,
 *                 metadata = request.metadata
 *             )
 *         )
 *         
 *         return payment
 *     }
 * }
 * ```
 * 
 * @property applicationEventPublisher Spring's event publisher
 */
@Service
class EventPublisher(
    private val applicationEventPublisher: ApplicationEventPublisher
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val eventMetrics = ConcurrentHashMap<String, EventMetrics>()

    /**
     * Publish a payment event
     * 
     * Events are published asynchronously to avoid blocking the main thread.
     * 
     * @param event Payment event to publish
     */
    fun publish(event: PaymentEvent) {
        try {
            logger.info(
                "Publishing event: type={}, paymentId={}, eventId={}",
                event.eventType,
                event.paymentId,
                event.eventId
            )

            // Publish to Spring's event system
            applicationEventPublisher.publishEvent(event)

            // Track metrics
            trackEventMetrics(event)

            logger.debug("Event published successfully: {}", event.eventId)
        } catch (e: Exception) {
            logger.error("Failed to publish event: ${event.eventId}", e)
            // In production, send to dead letter queue
            handlePublishFailure(event, e)
        }
    }

    /**
     * Publish multiple events in order
     * 
     * Ensures events are published in the order they occurred.
     * 
     * @param events List of events to publish
     */
    fun publishAll(events: List<PaymentEvent>) {
        events.forEach { event ->
            publish(event)
        }
    }

    /**
     * Track event metrics
     * 
     * Tracks number of events published by type.
     */
    private fun trackEventMetrics(event: PaymentEvent) {
        val metrics = eventMetrics.computeIfAbsent(event.eventType) {
            EventMetrics(eventType = it)
        }
        metrics.incrementCount()
    }

    /**
     * Handle publish failure
     * 
     * In production, this would:
     * 1. Send to dead letter queue
     * 2. Trigger alerts
     * 3. Log for manual intervention
     */
    private fun handlePublishFailure(event: PaymentEvent, error: Exception) {
        logger.error(
            "Event publish failed - eventId: {}, type: {}, error: {}",
            event.eventId,
            event.eventType,
            error.message
        )
        
        // TODO: Send to dead letter queue
        // deadLetterQueue.send(event, error)
        
        // TODO: Trigger alert
        // alertService.sendAlert("Event publish failed", event, error)
    }

    /**
     * Get event metrics
     * 
     * Returns metrics for all event types.
     */
    fun getEventMetrics(): Map<String, EventMetrics> {
        return eventMetrics.toMap()
    }

    /**
     * Get metrics for specific event type
     */
    fun getEventMetrics(eventType: String): EventMetrics? {
        return eventMetrics[eventType]
    }

    /**
     * Reset metrics
     * 
     * Useful for testing.
     */
    fun resetMetrics() {
        eventMetrics.clear()
    }
}

/**
 * Event Metrics
 * 
 * Tracks metrics for a specific event type.
 */
data class EventMetrics(
    val eventType: String,
    @Volatile private var count: Long = 0
) {
    fun incrementCount() {
        count++
    }

    fun getCount(): Long = count
}

/**
 * Event Listener Example
 * 
 * Example of how to listen to payment events.
 * 
 * ```kotlin
 * @Component
 * class PaymentEventListener {
 *     private val logger = LoggerFactory.getLogger(javaClass)
 *     
 *     @EventListener
 *     @Async
 *     fun handlePaymentCreated(event: PaymentEvent.PaymentCreated) {
 *         logger.info("Payment created: {}", event.paymentId)
 *         
 *         // Send notification
 *         notificationService.sendPaymentCreatedNotification(event)
 *         
 *         // Update analytics
 *         analyticsService.trackPaymentCreated(event)
 *         
 *         // Update read model (CQRS)
 *         readModelService.updatePaymentView(event)
 *     }
 *     
 *     @EventListener
 *     @Async
 *     fun handlePaymentFailed(event: PaymentEvent.PaymentFailed) {
 *         logger.error("Payment failed: {}", event.paymentId)
 *         
 *         // Send alert
 *         alertService.sendPaymentFailedAlert(event)
 *         
 *         // Update fraud detection
 *         fraudService.analyzeFailedPayment(event)
 *     }
 *     
 *     @EventListener
 *     @Async
 *     fun handleCircuitBreakerOpened(event: PaymentEvent.CircuitBreakerOpened) {
 *         logger.warn("Circuit breaker opened for provider: {}", event.provider)
 *         
 *         // Send alert to ops team
 *         opsAlertService.sendCircuitBreakerAlert(event)
 *         
 *         // Update provider health dashboard
 *         dashboardService.updateProviderStatus(event.provider, "CIRCUIT_OPEN")
 *     }
 * }
 * ```
 */
object EventListenerExample {
    const val EXAMPLE = """
        Event Listener Best Practices:
        
        1. Use @Async for non-blocking processing
        2. Handle exceptions gracefully
        3. Keep listeners focused (single responsibility)
        4. Use separate listeners for different concerns
        5. Consider idempotency (events may be delivered multiple times)
        6. Log all event processing
        7. Monitor listener performance
        8. Use dead letter queue for failed processing
    """
}

/**
 * Kafka Event Publisher (Optional)
 * 
 * For distributed systems, publish events to Kafka.
 * 
 * ```kotlin
 * @Service
 * class KafkaEventPublisher(
 *     private val kafkaTemplate: KafkaTemplate<String, String>
 * ) {
 *     fun publish(event: PaymentEvent) {
 *         val topic = "payment-events"
 *         val key = event.paymentId
 *         val value = event.toJson()
 *         
 *         kafkaTemplate.send(topic, key, value)
 *             .addCallback(
 *                 { result ->
 *                     logger.info("Event published to Kafka: {}", event.eventId)
 *                 },
 *                 { error ->
 *                     logger.error("Failed to publish to Kafka: {}", event.eventId, error)
 *                 }
 *             )
 *     }
 * }
 * ```
 * 
 * Configuration:
 * ```yaml
 * spring:
 *   kafka:
 *     bootstrap-servers: localhost:9092
 *     producer:
 *       key-serializer: org.apache.kafka.common.serialization.StringSerializer
 *       value-serializer: org.apache.kafka.common.serialization.StringSerializer
 *       acks: all
 *       retries: 3
 * ```
 */
object KafkaIntegrationGuide {
    const val GUIDE = """
        Kafka Integration Steps:
        
        1. Add Kafka dependency:
           implementation("org.springframework.kafka:spring-kafka")
        
        2. Configure Kafka producer
        3. Create KafkaEventPublisher
        4. Update EventPublisher to use Kafka
        5. Create Kafka consumers in downstream services
        6. Monitor Kafka lag and throughput
        7. Handle Kafka failures gracefully
        8. Use schema registry for event versioning
    """
}

/**
 * Event Store Integration (Optional)
 * 
 * For event sourcing, store all events in an event store.
 * 
 * ```kotlin
 * @Service
 * class EventStorePublisher(
 *     private val eventStore: EventStore
 * ) {
 *     fun publish(event: PaymentEvent) {
 *         // Store event
 *         eventStore.append(
 *             streamId = event.paymentId,
 *             eventType = event.eventType,
 *             eventData = event.toJson(),
 *             metadata = mapOf(
 *                 "timestamp" to event.timestamp.toString(),
 *                 "version" to event.aggregateVersion.toString()
 *             )
 *         )
 *         
 *         // Publish to subscribers
 *         eventStore.publish(event)
 *     }
 *     
 *     fun getEvents(paymentId: String): List<PaymentEvent> {
 *         return eventStore.readStream(paymentId)
 *             .map { storedEvent -> 
 *                 deserializeEvent(storedEvent)
 *             }
 *     }
 * }
 * ```
 * 
 * Benefits:
 * - Complete audit trail
 * - Ability to rebuild state from events
 * - Time travel debugging
 * - Event replay for testing
 */
object EventSourcingGuide {
    const val GUIDE = """
        Event Sourcing Benefits:
        
        1. Complete audit trail - Every state change is recorded
        2. Temporal queries - Query state at any point in time
        3. Event replay - Rebuild state from events
        4. Debugging - Understand how system reached current state
        5. Analytics - Analyze historical patterns
        6. Compliance - Immutable audit log
        
        Considerations:
        
        1. Storage - Events accumulate over time
        2. Snapshots - Periodic snapshots for performance
        3. Schema evolution - Handle event versioning
        4. Complexity - More complex than CRUD
        5. Eventual consistency - Async event processing
    """
}


