package com.helpdesk.events;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A domain event waiting to be (or already) published to RabbitMQ.
 *
 * <p>The <b>transactional outbox</b> pattern: we can't commit a database change and publish
 * to a broker atomically (they're two systems). So the event is written to this table in
 * the <em>same transaction</em> as the change, which makes them atomic, and a separate relay
 * publishes rows that aren't published yet. If the relay crashes after publishing but before
 * marking the row, the event is published again: delivery is <em>at least once</em>, and
 * consumers deduplicate by {@code eventId}.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    @Column(name = "ticket_id", updatable = false)
    private Long ticketId;

    /** The event as JSON (a jsonb column). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private String payload;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error")
    private String lastError;

    protected OutboxEvent() {} // for JPA

    OutboxEvent(UUID eventId, String eventType, Long ticketId, String payload, Instant occurredAt) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.ticketId = ticketId;
        this.payload = payload;
        this.occurredAt = occurredAt;
        this.nextAttemptAt = occurredAt;
    }

    void markPublished(Instant now) {
        publishedAt = now;
        attempts++;
        lastError = null;
    }

    /** Try again later, waiting longer after each failure (2s, 4s, 8s ... up to 5 minutes). */
    void markFailed(String error, Instant now) {
        attempts++;
        lastError = error.length() > 500 ? error.substring(0, 500) : error;
        long backoffSeconds = Math.min(MAX_BACKOFF.toSeconds(), 1L << Math.min(attempts, 20));
        nextAttemptAt = now.plusSeconds(backoffSeconds);
    }

    public Long getId() { return id; }
    public UUID getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public Long getTicketId() { return ticketId; }
    public String getPayload() { return payload; }
    public Instant getOccurredAt() { return occurredAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public int getAttempts() { return attempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public String getLastError() { return lastError; }
}
