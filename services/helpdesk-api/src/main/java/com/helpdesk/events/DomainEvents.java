package com.helpdesk.events;

import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Where services publish domain events: into the outbox, inside their own transaction. */
@Component
public class DomainEvents {

    private final OutboxRepository outbox;
    private final JsonMapper json;
    private final Clock clock;

    DomainEvents(OutboxRepository outbox, JsonMapper json, Clock clock) {
        this.outbox = outbox;
        this.json = json;
        this.clock = clock;
    }

    /**
     * {@code MANDATORY}: there must be a surrounding transaction. The event then commits (or
     * rolls back) together with the change it announces, so we never announce a change that
     * didn't happen, or make one without announcing it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(TicketEvent event) {
        String payload = json.writeValueAsString(event);
        outbox.save(new OutboxEvent(UUID.randomUUID(), event.eventType(), event.ticketId(), payload, clock.instant()));
    }
}
