package com.helpdesk.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import com.helpdesk.IntegrationTest;
import com.helpdesk.MutableClock;
import com.helpdesk.TestUsers;
import com.helpdesk.security.Actor;
import com.helpdesk.security.Role;
import com.helpdesk.ticket.TicketDtos.TicketDetail;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Agents pressing "next ticket" at the same moment never get the same ticket. */
class ClaimNextConcurrencyTests extends IntegrationTest {

    @Autowired
    TicketService tickets;

    @Test
    void eachWaitingTicketGoesToExactlyOneAgent() throws Exception {
        clock.set(MutableClock.MONDAY_10AM_LONDON);
        List<TestUsers.User> agents = List.of(TestUsers.agent("a1"), TestUsers.agent("a2"), TestUsers.agent("a3"),
                TestUsers.agent("a4"), TestUsers.agent("a5"));
        long queue = newQueue(agents.toArray(TestUsers.User[]::new));
        for (int i = 0; i < 12; i++) {
            createTicket(agents.getFirst(), queue, Priority.NORMAL);
        }
        List<Actor> actors = new ArrayList<>();
        for (TestUsers.User agent : agents) {
            actors.add(new Actor(provision(agent), agent.username(), agent.displayName(), Set.of(Role.AGENT)));
        }

        ConcurrentLinkedQueue<Long> claimed = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(actors.size());
        List<Future<?>> runs = new ArrayList<>();
        for (Actor actor : actors) {
            runs.add(pool.submit(() -> {
                start.await();
                while (true) {
                    Optional<TicketDetail> next = tickets.claimNext(actor, queue);
                    if (next.isEmpty()) {
                        return null;
                    }
                    claimed.add(next.get().ticket().id());
                }
            }));
        }
        start.countDown();
        for (Future<?> run : runs) {
            run.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(claimed).hasSize(12).doesNotHaveDuplicates();
        long unassigned = jdbc.sql("SELECT count(*) FROM tickets WHERE queue_id = :q AND assignee_id IS NULL")
                .param("q", queue).query(Long.class).single();
        assertThat(unassigned).isZero();
    }
}
