package com.helpdesk.messaging;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Makes message consumers idempotent. RabbitMQ (and our outbox) deliver <em>at least
 * once</em>, so the same event can arrive twice; handling it twice must not, say, notify
 * someone twice.
 *
 * <p>Usage, inside the consumer's transaction: {@code if (!processed.firstTime(...)) return;}
 * The insert and the consumer's own writes commit together; if the consumer fails, both roll
 * back and the redelivered message is handled for real.
 */
@Component
public class ProcessedMessages {

    private final JdbcClient jdbc;
    private final Clock clock;

    ProcessedMessages(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** True the first time this consumer sees this event id; false for every redelivery. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean firstTime(String consumer, UUID eventId) {
        int inserted = jdbc.sql("""
                        INSERT INTO processed_messages (consumer, event_id, processed_at) VALUES (:consumer, :eventId, :now)
                        ON CONFLICT DO NOTHING""")
                .param("consumer", consumer)
                .param("eventId", eventId)
                .param("now", Timestamp.from(clock.instant()))
                .update();
        return inserted == 1;
    }
}
