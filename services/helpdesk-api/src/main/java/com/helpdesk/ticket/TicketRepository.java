package com.helpdesk.ticket;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface TicketRepository extends JpaRepository<Ticket, Long>, JpaSpecificationExecutor<Ticket> {

    /** Loads a ticket and locks its row until the transaction ends (SELECT ... FOR NO KEY UPDATE). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Ticket t WHERE t.id = :id")
    Optional<Ticket> findByIdForUpdate(long id);

    /**
     * Tickets with an SLA clock past its warning or breach deadline, locked for this
     * transaction.
     *
     * <p>This is what makes the escalation worker safe with several instances:
     * {@code FOR UPDATE SKIP LOCKED} hands each instance a different set of tickets, and a
     * ticket an agent is editing right now (row locked) is simply picked up on the next run.
     * Nobody waits, so there are no deadlocks, and no ticket is processed twice at once.
     */
    @Query(value = """
            SELECT k.id FROM tickets k
            WHERE EXISTS (
                SELECT 1 FROM sla_timers s
                WHERE s.ticket_id = k.id AND s.clock_state = 'RUNNING'
                  AND ((s.breached_at IS NULL AND s.due_at <= :now)
                    OR (s.warned_at IS NULL AND s.breached_at IS NULL AND s.warn_at <= :now)))
            ORDER BY k.id
            LIMIT :limit
            FOR UPDATE OF k SKIP LOCKED""", nativeQuery = true)
    List<Long> lockTicketsWithDueTimers(Instant now, int limit);

    /**
     * The most urgent unassigned ticket in a queue (earliest first-response deadline), locked.
     * Two agents pressing "next ticket" at the same moment get different tickets.
     */
    @Query(value = """
            SELECT k.id FROM tickets k
            LEFT JOIN sla_timers s ON s.ticket_id = k.id AND s.metric = 'FIRST_RESPONSE'
            WHERE k.status = 'NEW' AND k.queue_id = :queueId
            ORDER BY s.due_at NULLS LAST, k.id
            LIMIT 1
            FOR UPDATE OF k SKIP LOCKED""", nativeQuery = true)
    Optional<Long> lockNextUnassigned(long queueId);

    /** Resolved tickets nobody replied to since {@code cutoff}, locked (for auto-close). */
    @Query(value = """
            SELECT id FROM tickets
            WHERE status = 'RESOLVED' AND resolved_at <= :cutoff
            ORDER BY id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    List<Long> lockResolvedBefore(Instant cutoff, int limit);
}
