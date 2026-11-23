package com.helpdesk.live;

import com.helpdesk.events.TicketEvent;
import com.helpdesk.messaging.EventMessages;
import com.helpdesk.messaging.RabbitTopology;
import java.time.Instant;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Turns domain events into WebSocket pushes on {@code /topic/tickets}.
 *
 * <p>Only one app instance handles each event (they compete on the queue), yet every browser
 * gets the push, whichever instance its WebSocket is connected to: the message goes to
 * RabbitMQ's STOMP plugin (the "broker relay"), which fans it out to all subscribers.
 *
 * <p>The push is a small "ticket X changed" hint, not the ticket itself. The browser refetches
 * through the REST API, which applies the normal permission checks, so the WebSocket can
 * never show someone data the API wouldn't.
 */
@Component
public class LiveUpdateListener {

    public static final String TICKETS_TOPIC = "/topic/tickets";

    /** What browsers receive. {@code type} is the event type, e.g. "ticket.status-changed". */
    public record TicketChanged(String type, long ticketId, String ticketNumber, Instant sentAt) {}

    private final EventMessages messages;
    private final SimpMessagingTemplate stomp;

    LiveUpdateListener(EventMessages messages, SimpMessagingTemplate stomp) {
        this.messages = messages;
        this.stomp = stomp;
    }

    @RabbitListener(queues = RabbitTopology.LIVE_UPDATES_QUEUE)
    public void onEvent(Message message) {
        TicketEvent event = messages.read(message).event();
        stomp.convertAndSend(TICKETS_TOPIC,
                new TicketChanged(event.eventType(), event.ticketId(), event.ticketNumber(), Instant.now()));
    }
}
