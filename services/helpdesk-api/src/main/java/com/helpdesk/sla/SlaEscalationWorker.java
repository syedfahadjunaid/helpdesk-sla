package com.helpdesk.sla;

import com.helpdesk.audit.ActorRef;
import com.helpdesk.audit.AuditService;
import com.helpdesk.config.HelpdeskProperties;
import com.helpdesk.events.DomainEvents;
import com.helpdesk.events.TicketEvent;
import com.helpdesk.ticket.Ticket;
import com.helpdesk.ticket.TicketRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The background worker that sends SLA warnings and escalates breaches.
 *
 * <p><b>Safe with several instances.</b> Each run locks a batch of tickets that have a due
 * clock with {@code FOR UPDATE SKIP LOCKED} ({@link TicketRepository#lockTicketsWithDueTimers}).
 * Two instances running at the same moment get disjoint batches; a ticket locked by one is
 * invisible to the other until that transaction commits, and by then its clock is no longer
 * due. The check is repeated on the locked, freshly loaded timer ({@link SlaTimer#isBreachDue}),
 * so the handler is idempotent: running it twice changes nothing the second time.
 *
 * <p><b>Belt and braces.</b> A unique index on {@code audit_events (ticket_id, action, metric)}
 * makes a second "SLA_BREACHED" row for the same clock impossible, even if this code had a bug.
 *
 * <p><b>Restart-proof.</b> Deadlines are stored, so a worker that was down for an hour simply
 * finds every clock that fell due in the meantime. The breach is recorded at the deadline
 * ({@code breached_at = due_at}), not at the moment the worker noticed.
 */
@Component
public class SlaEscalationWorker {

    private static final Logger log = LoggerFactory.getLogger(SlaEscalationWorker.class);

    private final TicketRepository tickets;
    private final SlaTimerRepository timers;
    private final AuditService audit;
    private final DomainEvents events;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final int batchSize;

    SlaEscalationWorker(TicketRepository tickets, SlaTimerRepository timers, AuditService audit, DomainEvents events,
            Clock clock, TransactionTemplate transactions, HelpdeskProperties props) {
        this.tickets = tickets;
        this.timers = timers;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
        this.transactions = transactions;
        this.batchSize = props.sla().batchSize();
    }

    @Scheduled(fixedDelayString = "${helpdesk.sla.worker-interval}")
    public void run() {
        try {
            while (processDueBatch() == batchSize) {
                // a full batch: more may be waiting
            }
        } catch (RuntimeException e) {
            log.warn("SLA worker run failed; the next run retries: {}", e.getMessage());
        }
    }

    /** One batch in one transaction. Returns how many tickets it locked. Public so tests can drive it. */
    public int processDueBatch() {
        Integer count = transactions.execute(status -> processDueBatchInTransaction());
        return count == null ? 0 : count;
    }

    private int processDueBatchInTransaction() {
        Instant now = clock.instant();
        List<Long> ticketIds = tickets.lockTicketsWithDueTimers(now, batchSize);
        if (ticketIds.isEmpty()) {
            return 0;
        }
        Map<Long, Ticket> lockedTickets = tickets.findAllById(ticketIds).stream()
                .collect(Collectors.toMap(Ticket::getId, Function.identity()));
        for (SlaTimer timer : timers.findByTicketIdIn(ticketIds)) {
            Ticket ticket = lockedTickets.get(timer.getTicketId());
            if (timer.isBreachDue(now)) {
                escalate(ticket, timer, now);
            } else if (timer.isWarningDue(now)) {
                warn(ticket, timer, now);
            }
        }
        return ticketIds.size();
    }

    private void warn(Ticket ticket, SlaTimer timer, Instant now) {
        timer.recordWarning(now);
        audit.record(ticket.getId(), ActorRef.SYSTEM, "SLA_WARNING", details(timer, "dueAt", timer.getDueAt()));
        events.publish(new TicketEvent.SlaWarning(ticket.getId(), ticket.getNumber(), timer.getMetric(),
                timer.getDueAt(), ticket.getAssigneeId(), ticket.getQueue().getId()));
    }

    /**
     * A target was missed: record the breach and escalate the ticket one level. Escalation
     * makes the ticket stand out in every list and notifies the queue's supervisors (by the
     * notification consumer reacting to the {@code sla.breached} event).
     */
    private void escalate(Ticket ticket, SlaTimer timer, Instant now) {
        timer.recordBreach();
        int level = ticket.escalate(now);
        audit.record(ticket.getId(), ActorRef.SYSTEM, "SLA_BREACHED",
                details(timer, "dueAt", timer.getDueAt(), "escalationLevel", level));
        events.publish(new TicketEvent.SlaBreached(ticket.getId(), ticket.getNumber(), timer.getMetric(),
                timer.getBreachedAt(), level, ticket.getAssigneeId(), ticket.getQueue().getId()));
        log.info("{} breached its {} target; escalated to level {}", ticket.getNumber(), timer.getMetric(), level);
    }

    private static Map<String, Object> details(SlaTimer timer, Object... more) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("metric", timer.getMetric().name()); // part of the "once only" unique index
        details.put("targetMinutes", timer.getTargetMinutes());
        for (int i = 0; i < more.length; i += 2) {
            details.put((String) more[i], more[i + 1] == null ? null : more[i + 1].toString());
        }
        return details;
    }
}
