package com.helpdesk.messaging;

import com.helpdesk.events.TicketEvent;
import java.util.UUID;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Reads a domain event (and its id) back out of a RabbitMQ message. */
@Component
public class EventMessages {

    /** The event plus the id consumers deduplicate on. */
    public record Received(UUID eventId, TicketEvent event) {}

    private final JsonMapper json;

    EventMessages(JsonMapper json) {
        this.json = json;
    }

    public Received read(Message message) {
        try {
            UUID eventId = UUID.fromString(message.getMessageProperties().getMessageId());
            return new Received(eventId, json.readValue(message.getBody(), TicketEvent.class));
        } catch (JacksonException | IllegalArgumentException | NullPointerException e) {
            // A malformed message won't get better by retrying: straight to the dead-letter queue.
            throw new AmqpRejectAndDontRequeueException("Unreadable event message", e);
        }
    }
}
