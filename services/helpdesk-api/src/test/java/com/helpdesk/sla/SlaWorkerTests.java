package com.helpdesk.sla;

import static org.assertj.core.api.Assertions.assertThat;

import com.helpdesk.IntegrationTest;
import com.helpdesk.MutableClock;
import com.helpdesk.TestUsers;
import com.helpdesk.audit.AuditEventRepository;
import com.helpdesk.events.OutboxRepository;
import com.helpdesk.events.TicketEvent;
import com.helpdesk.ticket.Priority;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * The escalation worker against a real database, with time moved by hand: warnings,
 * breaches, pauses, business hours, and catching up after downtime.
 */
class SlaWorkerTests extends IntegrationTest {

    @Autowired
    SlaEscalationWorker worker;

    @Autowired
    AuditEventRepository audit;

    @Autowired
    OutboxRepository outbox;

    @BeforeEach
    void mondayMorning() {
        clock.set(MutableClock.MONDAY_10AM_LONDON);
    }

    @Test
    void warnsAtSeventyFivePercentThenBreachesAndEscalates() throws Exception {
        TestUsers.User ann = TestUsers.agent("ann");
        long id = createTicket(ann, newQueue(ann), Priority.URGENT); // 30 minutes to first response, 24x7
        Instant created = clock.instant();

        clock.advance(Duration.ofMinutes(22));
        runWorker();
        assertThat(audit.countByTicketIdAndAction(id, "SLA_WARNING")).as("not yet at 75%").isZero();

        clock.advance(Duration.ofSeconds(30)); // 22.5 minutes = 75% of 30
        runWorker();
        assertThat(audit.countByTicketIdAndAction(id, "SLA_WARNING")).isEqualTo(1);
        assertThat(timer(ticket(id, ann), "FIRST_RESPONSE").get("health").asString()).isEqualTo("AT_RISK");

        clock.advance(Duration.ofMinutes(10));
        runWorker();
        runWorker(); // running again changes nothing
        assertThat(audit.countByTicketIdAndAction(id, "SLA_BREACHED")).isEqualTo(1);

        JsonNode breached = ticket(id, ann);
        assertThat(breached.get("ticket").get("escalationLevel").asInt()).isEqualTo(1);
        JsonNode firstResponse = timer(breached, "FIRST_RESPONSE");
        assertThat(firstResponse.get("health").asString()).isEqualTo("BREACHED");
        assertThat(Instant.parse(firstResponse.get("breachedAt").asString()))
                .isEqualTo(created.plus(Duration.ofMinutes(30)));

        assertThat(outbox.findByTicketIdOrderById(id)).extracting("eventType")
                .contains(TicketEvent.SLA_WARNING, TicketEvent.SLA_BREACHED);
    }

    @Test
    void waitingOnTheCustomerPausesTheClockAndResumingPushesTheDeadlineBack() throws Exception {
        TestUsers.User ann = TestUsers.agent("ann");
        long id = createTicket(ann, newQueue(ann), Priority.URGENT); // resolution: 240 minutes, 24x7
        Instant created = clock.instant();
        act(id, "ASSIGN", ann);

        clock.advance(Duration.ofMinutes(10));
        act(id, "WAIT_ON_CUSTOMER", ann);

        clock.advance(Duration.ofHours(6)); // far beyond every deadline, but we're waiting on the customer
        runWorker();
        assertThat(audit.countByTicketIdAndAction(id, "SLA_BREACHED")).isZero();
        assertThat(timer(ticket(id, ann), "RESOLUTION").get("health").asString()).isEqualTo("PAUSED");

        act(id, "RESUME", ann);
        JsonNode resolution = timer(ticket(id, ann), "RESOLUTION");
        assertThat(Instant.parse(resolution.get("dueAt").asString()))
                .as("240 minutes, plus the 6 hours spent waiting")
                .isEqualTo(created.plus(Duration.ofMinutes(240)).plus(Duration.ofHours(6)));
        assertThat(resolution.get("elapsedSeconds").asLong()).isEqualTo(10 * 60);
    }

    @Test
    void normalTicketsOnlyUseOfficeHours() throws Exception {
        TestUsers.User ann = TestUsers.agent("ann");
        clock.set(Instant.parse("2026-11-27T16:30:00Z")); // Friday 16:30 in London
        long id = createTicket(ann, newQueue(ann), Priority.NORMAL); // 240 business minutes to first response

        JsonNode firstResponse = timer(ticket(id, ann), "FIRST_RESPONSE");
        // 30 minutes on Friday, then 3.5 hours from 09:00 on Monday.
        assertThat(firstResponse.get("dueAt").asString()).isEqualTo("2026-11-30T12:30:00Z");

        clock.set(Instant.parse("2026-11-28T12:00:00Z")); // Saturday
        JsonNode weekend = timer(ticket(id, ann), "FIRST_RESPONSE");
        assertThat(weekend.get("ticking").asBoolean()).isFalse();
        assertThat(weekend.get("remainingSeconds").asLong()).isEqualTo(Duration.ofMinutes(210).toSeconds());
    }

    @Test
    void raisingThePriorityPullsTheDeadlineIn() throws Exception {
        TestUsers.User ann = TestUsers.agent("ann");
        long id = createTicket(ann, newQueue(ann), Priority.LOW);
        act(id, "ASSIGN", ann);
        clock.advance(Duration.ofMinutes(40));

        JsonNode urgent = read(performPut("/api/tickets/" + id + "/priority", ann, Map.of("priority", "URGENT")));
        assertThat(urgent.get("ticket").get("priority").asString()).isEqualTo("URGENT");
        // 40 minutes are already used of the new 30-minute target: due right away.
        assertThat(timer(urgent, "FIRST_RESPONSE").get("dueAt").asString()).isEqualTo(clock.instant().toString());

        runWorker();
        assertThat(audit.countByTicketIdAndAction(id, "SLA_BREACHED")).isEqualTo(1);
    }

    @Test
    void afterDowntimeTheWorkerCatchesUpAndDatesTheBreachCorrectly() throws Exception {
        TestUsers.User ann = TestUsers.agent("ann");
        long id = createTicket(ann, newQueue(ann), Priority.URGENT);
        Instant created = clock.instant();

        // Nobody ran the worker for 5 hours (all instances down). Deadlines are in the
        // database, so the first run afterwards finds everything that fell due meanwhile.
        clock.advance(Duration.ofHours(5));
        runWorker();

        JsonNode detail = ticket(id, ann);
        assertThat(detail.get("ticket").get("escalationLevel").asInt()).as("both targets missed").isEqualTo(2);
        assertThat(timer(detail, "FIRST_RESPONSE").get("breachedAt").asString())
                .isEqualTo(created.plus(Duration.ofMinutes(30)).toString());
        assertThat(timer(detail, "RESOLUTION").get("breachedAt").asString())
                .isEqualTo(created.plus(Duration.ofMinutes(240)).toString());
        assertThat(audit.countByTicketIdAndAction(id, "SLA_WARNING"))
                .as("no stale warnings for targets already breached").isZero();
    }

    private void runWorker() {
        while (worker.processDueBatch() > 0) {
            // until nothing is due
        }
    }
}
