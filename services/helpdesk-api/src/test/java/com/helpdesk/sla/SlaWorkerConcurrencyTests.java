package com.helpdesk.sla;

import static org.assertj.core.api.Assertions.assertThat;

import com.helpdesk.IntegrationTest;
import com.helpdesk.MutableClock;
import com.helpdesk.TestUsers;
import com.helpdesk.ticket.Priority;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Several worker instances running at the very same moment must never escalate the same
 * ticket twice. Here four threads stand in for four app instances: each runs the worker
 * with its own transactions against the same database, released together by a latch.
 */
class SlaWorkerConcurrencyTests extends IntegrationTest {

    private static final int TICKETS = 60;
    private static final int WORKERS = 4;

    @Autowired
    SlaEscalationWorker worker;

    @Test
    void fourWorkersEscalateEachBreachExactlyOnce() throws Exception {
        clock.set(MutableClock.MONDAY_10AM_LONDON);
        TestUsers.User ann = TestUsers.agent("ann");
        long queue = newQueue(ann);
        List<Long> ticketIds = new ArrayList<>();
        for (int i = 0; i < TICKETS; i++) {
            ticketIds.add(createTicket(ann, queue, Priority.URGENT));
        }
        clock.advance(Duration.ofMinutes(31)); // every first-response target (30 min) is now missed

        AtomicInteger batches = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        List<Future<?>> runs = new ArrayList<>();
        for (int i = 0; i < WORKERS; i++) {
            runs.add(pool.submit(() -> {
                start.await();
                while (worker.processDueBatch() > 0) {
                    batches.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> run : runs) {
            run.get(60, TimeUnit.SECONDS); // rethrows anything a worker threw
        }
        pool.shutdown();

        assertThat(batches.get()).as("the work was really shared out in batches").isGreaterThan(1);
        assertThat(countFor(ticketIds,
                "SELECT count(*) FROM audit_events WHERE action = 'SLA_BREACHED' AND ticket_id IN (:ids)"))
                .isEqualTo(TICKETS);
        assertThat(countFor(ticketIds,
                "SELECT count(*) FROM outbox_events WHERE event_type = 'sla.breached' AND ticket_id IN (:ids)"))
                .isEqualTo(TICKETS);
        assertThat(countFor(ticketIds, "SELECT count(*) FROM tickets WHERE escalation_level = 1 AND id IN (:ids)"))
                .as("every ticket escalated exactly one level").isEqualTo(TICKETS);
    }

    private long countFor(List<Long> ticketIds, String sql) {
        return jdbc.sql(sql).param("ids", ticketIds).query(Long.class).single();
    }
}
