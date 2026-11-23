package com.helpdesk.messaging;

import com.helpdesk.events.TicketEvent;
import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The RabbitMQ layout, declared by the app at startup (idempotently: declaring what already
 * exists is a no-op).
 *
 * <pre>
 *   outbox relay ──► [helpdesk.events] (topic exchange, routing key = event type)
 *                        ├── #                                   ──► helpdesk.live-updates   ──► browsers (STOMP)
 *                        ├── ticket.assigned, ticket.message-added,
 *                        │   sla.*                               ──► helpdesk.notifications  ──► in-app + supervisor email
 *                        └── ticket.created, ticket.message-added ──► helpdesk.customer-email ──► emails to customers
 *
 *   notifications, customer-email ──(still failing after retries)──► [helpdesk.events.dlx] ──► &lt;queue&gt;.dlq
 * </pre>
 *
 * Each consumer has its own queue, so one slow or failing consumer (say, the mail server is
 * down) never holds up the others. Within a queue, app instances compete: each message is
 * handled by exactly one instance.
 */
@Configuration
public class RabbitTopology {

    public static final String EVENTS_EXCHANGE = "helpdesk.events";
    public static final String DEAD_LETTER_EXCHANGE = "helpdesk.events.dlx";

    public static final String LIVE_UPDATES_QUEUE = "helpdesk.live-updates";
    public static final String NOTIFICATIONS_QUEUE = "helpdesk.notifications";
    public static final String CUSTOMER_EMAIL_QUEUE = "helpdesk.customer-email";

    /** A live update nobody delivered within a minute is stale; the browser refetches anyway. */
    private static final int LIVE_UPDATE_TTL_MS = 60_000;

    @Bean
    Declarables helpdeskTopology() {
        TopicExchange events = new TopicExchange(EVENTS_EXCHANGE, true, false);
        DirectExchange deadLetters = new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
        List<Declarable> declarables = new ArrayList<>(List.of(events, deadLetters));

        // Best effort, so no dead-letter queue: a live update that can't be delivered is dropped
        // (the browser refetches on reconnect anyway), and expired ones simply disappear.
        Queue live = QueueBuilder.durable(LIVE_UPDATES_QUEUE).ttl(LIVE_UPDATE_TTL_MS).build();
        declarables.add(live);
        declarables.add(bind(live, events, "#"));

        Queue notifications = queueWithDeadLetters(declarables, deadLetters, NOTIFICATIONS_QUEUE);
        declarables.add(bind(notifications, events, TicketEvent.ASSIGNED));
        declarables.add(bind(notifications, events, TicketEvent.MESSAGE_ADDED));
        declarables.add(bind(notifications, events, "sla.*"));

        Queue customerEmail = queueWithDeadLetters(declarables, deadLetters, CUSTOMER_EMAIL_QUEUE);
        declarables.add(bind(customerEmail, events, TicketEvent.CREATED));
        declarables.add(bind(customerEmail, events, TicketEvent.MESSAGE_ADDED));

        return new Declarables(declarables);
    }

    /**
     * A durable queue plus its {@code .dlq}. A message the listener still can't handle after
     * its retries is moved there, to be inspected (and replayed) by a person, instead of
     * being lost or retried forever.
     */
    private static Queue queueWithDeadLetters(List<Declarable> declarables, DirectExchange deadLetters, String name) {
        Queue queue = QueueBuilder.durable(name)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(name)
                .build();
        Queue dlq = QueueBuilder.durable(name + ".dlq").build();
        declarables.add(queue);
        declarables.add(dlq);
        declarables.add(BindingBuilder.bind(dlq).to(deadLetters).with(name));
        return queue;
    }

    private static Binding bind(Queue queue, TopicExchange exchange, String routingKey) {
        return BindingBuilder.bind(queue).to(exchange).with(routingKey);
    }
}
