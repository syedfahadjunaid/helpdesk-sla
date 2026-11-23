package com.helpdesk.ticket;

import com.helpdesk.config.HelpdeskProperties;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Closes resolved tickets the customer didn't answer within {@code helpdesk.tickets.auto-close-after}.
 * Same multi-instance pattern as the SLA worker: lock a batch with SKIP LOCKED, act, commit.
 */
@Component
public class AutoCloseJob {

    private static final Logger log = LoggerFactory.getLogger(AutoCloseJob.class);
    private static final int BATCH_SIZE = 50;

    private final TicketRepository tickets;
    private final TicketService ticketService;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Duration closeAfter;

    AutoCloseJob(TicketRepository tickets, TicketService ticketService, TransactionTemplate transactions, Clock clock,
            HelpdeskProperties props) {
        this.tickets = tickets;
        this.ticketService = ticketService;
        this.transactions = transactions;
        this.clock = clock;
        this.closeAfter = props.tickets().autoCloseAfter();
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void run() {
        try {
            while (closeBatch() == BATCH_SIZE) {
                // more may be waiting
            }
        } catch (RuntimeException e) {
            log.warn("Auto-close failed; the next run retries: {}", e.getMessage());
        }
    }

    /** Public so tests can drive it. Returns how many tickets were closed. */
    public int closeBatch() {
        Integer closed = transactions.execute(status -> {
            List<Long> ids = tickets.lockResolvedBefore(clock.instant().minus(closeAfter), BATCH_SIZE);
            tickets.findAllById(ids).forEach(ticketService::autoClose);
            return ids.size();
        });
        return closed == null ? 0 : closed;
    }
}
