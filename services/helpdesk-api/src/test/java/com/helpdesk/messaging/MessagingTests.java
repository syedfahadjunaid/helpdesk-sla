package com.helpdesk.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.helpdesk.IntegrationTest;
import com.helpdesk.MailpitTestClient;
import com.helpdesk.MutableClock;
import com.helpdesk.TestUsers;
import com.helpdesk.events.OutboxEvent;
import com.helpdesk.events.OutboxRepository;
import com.helpdesk.events.TicketEvent;
import com.helpdesk.sla.SlaEscalationWorker;
import com.helpdesk.ticket.Priority;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.JsonNode;

/**
 * The event pipeline: outbox, relay, RabbitMQ, consumers. Real broker, real consumers;
 * the relay is triggered by hand ({@code publishOutbox()}), consumers react on their own.
 */
class MessagingTests extends IntegrationTest {

    @Autowired
    OutboxRepository outbox;

    @Autowired
    RabbitTemplate rabbit;

    @Autowired
    SlaEscalationWorker worker;

    @Value("${helpdesk.mail.inbound.mailpit-url}")
    String mailpitUrl;

    @BeforeEach
    void mondayMorning() {
        clock.set(MutableClock.MONDAY_10AM_LONDON);
    }

    @Test
    void eventsAreMarkedPublishedOnlyOnceTheBrokerConfirmedThem() throws Exception {
        TestUsers.User ann = TestUsers.agent("ann");
        long id = createTicket(ann, newQueue(ann), Priority.NORMAL);
        assertThat(outbox.findByTicketIdOrderById(id)).isNotEmpty().allMatch(event -> event.getPublishedAt() == null);

        publishOutbox();

        assertThat(outbox.findByTicketIdOrderById(id)).allSatisfy(event -> {
            assertThat(event.getPublishedAt()).isNotNull();
            assertThat(event.getAttempts()).isEqualTo(1);
        });
    }

    @Test
    void anAssignmentNotifiesTheAssigneeAndARedeliveryDoesNotNotifyTwice() throws Exception {
        TestUsers.User ann = TestUsers.agent("ann");
        TestUsers.User sam = TestUsers.supervisor("sam");
        long queue = newQueue(ann);
        long annId = provision(ann);
        long id = createTicket(ann, queue, Priority.NORMAL);
        postJson("/api/tickets/" + id + "/actions/ASSIGN", sam, Map.of("assigneeId", annId));

        publishOutbox();
        await().atMost(Duration.ofSeconds(10)).until(() -> notificationsAbout(ann, id, "ASSIGNED") == 1);

        // RabbitMQ delivers at least once. Simulate a duplicate: the same event, same id, again.
        OutboxEvent assigned = outbox.findByTicketIdOrderById(id).stream()
                .filter(event -> event.getEventType().equals(TicketEvent.ASSIGNED))
                .findFirst().orElseThrow();
        Message duplicate = MessageBuilder.withBody(assigned.getPayload().getBytes(StandardCharsets.UTF_8))
                .setMessageId(assigned.getEventId().toString())
                .setContentType("application/json")
                .build();
        rabbit.send(RabbitTopology.EVENTS_EXCHANGE, assigned.getEventType(), duplicate);

        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(4))
                .until(() -> notificationsAbout(ann, id, "ASSIGNED") == 1);
    }

    @Test
    void aBreachNotifiesSupervisorsInTheAppAndByEmail() throws Exception {
        TestUsers.User ann = TestUsers.agent("ann");
        TestUsers.User sam = TestUsers.supervisor("sam");
        provision(sam);
        long id = createTicket(ann, newQueue(ann), Priority.URGENT);

        clock.advance(Duration.ofMinutes(31));
        while (worker.processDueBatch() > 0) {
            // escalate everything due
        }
        publishOutbox();

        await().atMost(Duration.ofSeconds(10)).until(() -> notificationsAbout(sam, id, "SLA_BREACHED") == 1);
        MailpitTestClient mailpit = new MailpitTestClient(mailpitUrl, json);
        await().atMost(Duration.ofSeconds(10)).until(() ->
                mailpit.hasSubject("to:\"" + sam.email() + "\"", "[HD-" + id + "] SLA breached: first response"));
    }

    @Test
    void aMessageNoConsumerCanReadEndsUpInTheDeadLetterQueue() {
        rabbit.send(RabbitTopology.EVENTS_EXCHANGE, TicketEvent.ASSIGNED,
                MessageBuilder.withBody("not json".getBytes(StandardCharsets.UTF_8)).setMessageId("not-a-uuid").build());

        Message dead = rabbit.receive(RabbitTopology.NOTIFICATIONS_QUEUE + ".dlq", 20_000);
        assertThat(dead).isNotNull();
        assertThat(new String(dead.getBody(), StandardCharsets.UTF_8)).isEqualTo("not json");
    }

    private long notificationsAbout(TestUsers.User user, long ticketId, String kind) throws Exception {
        long count = 0;
        for (JsonNode item : getJson("/api/notifications", user).get("items")) {
            if (item.get("ticketId").asLong() == ticketId && item.get("kind").asString().equals(kind)) {
                count++;
            }
        }
        return count;
    }
}
