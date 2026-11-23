package com.helpdesk.events;

import com.helpdesk.config.HelpdeskProperties;
import com.helpdesk.messaging.RabbitTopology;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes outbox rows to RabbitMQ.
 *
 * <p>Each batch runs in one transaction that holds row locks on the events it publishes
 * ({@code SKIP LOCKED}, see {@link OutboxRepository#lockNextBatch}). A row is marked as
 * published only after RabbitMQ <em>confirms</em> it (publisher confirms: the broker has
 * taken responsibility for the message). Anything not confirmed is retried later with
 * backoff. Several app instances can run this at once.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final long CONFIRM_TIMEOUT_SECONDS = 5;

    private final OutboxRepository outbox;
    private final RabbitTemplate rabbit;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final int batchSize;

    OutboxRelay(OutboxRepository outbox, RabbitTemplate rabbit, Clock clock, TransactionTemplate transactions,
            HelpdeskProperties props) {
        this.outbox = outbox;
        this.rabbit = rabbit;
        this.clock = clock;
        this.transactions = transactions;
        this.batchSize = props.outbox().batchSize();
    }

    @Scheduled(fixedDelayString = "${helpdesk.outbox.relay-interval}")
    public void relay() {
        try {
            while (publishBatch() == batchSize) {
                // a full batch: there may be more waiting
            }
        } catch (RuntimeException e) {
            log.warn("Outbox relay failed; will retry: {}", e.getMessage());
        }
    }

    /**
     * Publishes one batch in its own transaction and returns how many events it looked at.
     * Public so tests can drive it.
     *
     * <p>Why a {@link TransactionTemplate} rather than {@code @Transactional}? {@code relay()}
     * calls this method on {@code this}, which bypasses Spring's proxy, and the annotation
     * would silently do nothing. The template makes the transaction explicit.
     */
    public int publishBatch() {
        Integer published = transactions.execute(status -> publishBatchInTransaction());
        return published == null ? 0 : published;
    }

    private int publishBatchInTransaction() {
        Instant now = clock.instant();
        List<OutboxEvent> batch = outbox.lockNextBatch(now, batchSize);

        // Send everything first, then wait for the confirms: the broker confirms in bulk,
        // so this is much faster than waiting after each message.
        List<CorrelationData> confirms = new ArrayList<>(batch.size());
        for (OutboxEvent event : batch) {
            CorrelationData correlation = new CorrelationData(event.getEventId().toString());
            rabbit.send(RabbitTopology.EVENTS_EXCHANGE, event.getEventType(), toMessage(event), correlation);
            confirms.add(correlation);
        }
        for (int i = 0; i < batch.size(); i++) {
            OutboxEvent event = batch.get(i);
            String failure = awaitConfirm(confirms.get(i));
            if (failure == null) {
                event.markPublished(clock.instant());
            } else {
                log.warn("Event {} ({}) not published: {}", event.getEventId(), event.getEventType(), failure);
                event.markFailed(failure, clock.instant());
            }
        }
        return batch.size();
    }

    /** null if the broker accepted and routed the message; otherwise why not. */
    private static String awaitConfirm(CorrelationData correlation) {
        try {
            CorrelationData.Confirm confirm = correlation.getFuture().get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!confirm.ack()) {
                return "nacked by broker: " + confirm.reason();
            }
            if (correlation.getReturned() != null) {
                return "unroutable: " + correlation.getReturned().getReplyText();
            }
            return null;
        } catch (TimeoutException e) {
            return "no confirm within " + CONFIRM_TIMEOUT_SECONDS + "s";
        } catch (ExecutionException e) {
            return "publish failed: " + e.getCause();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        }
    }

    private static Message toMessage(OutboxEvent event) {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(event.getEventId().toString()); // consumers deduplicate on this
        properties.setType(event.getEventType());
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT); // survives a broker restart
        properties.setTimestamp(Date.from(event.getOccurredAt()));
        return new Message(event.getPayload().getBytes(StandardCharsets.UTF_8), properties);
    }
}
