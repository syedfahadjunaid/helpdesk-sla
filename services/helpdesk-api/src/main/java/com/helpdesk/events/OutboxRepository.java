package com.helpdesk.events;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * The next unpublished events, locked for this transaction.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED}: rows another relay instance is already publishing
     * are skipped instead of waited for, so several instances share the work without
     * publishing the same row at the same time.
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE published_at IS NULL AND next_attempt_at <= :now
            ORDER BY id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    List<OutboxEvent> lockNextBatch(Instant now, int limit);

    long countByPublishedAtIsNull();

    List<OutboxEvent> findByEventTypeOrderById(String eventType);

    List<OutboxEvent> findByTicketIdOrderById(long ticketId);
}
