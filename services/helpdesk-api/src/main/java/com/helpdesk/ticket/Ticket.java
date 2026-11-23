package com.helpdesk.ticket;

import com.helpdesk.agent.Agent;
import com.helpdesk.customer.Customer;
import com.helpdesk.queue.TicketQueue;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * A ticket: the aggregate root of the helpdesk. Its SLA timers and messages belong to it.
 *
 * <p>Changing a ticket (or its timers) always starts by locking its row
 * ({@link TicketRepository#findByIdForUpdate}). That one lock serialises agents, the inbound
 * mail processor and the SLA worker on the same ticket. {@code @Version} adds optimistic
 * locking on top, as a safety net for code paths that forget to lock.
 *
 * <p>Status changes go through {@link #moveTo}, which only accepts what
 * {@link TicketStatus#after} allowed; there are no public setters.
 */
@Entity
@Table(name = "tickets")
public class Ticket {

    public static final String NUMBER_PREFIX = "HD-";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String subject;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TicketStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Priority priority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Channel channel;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "queue_id")
    private TicketQueue queue;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", updatable = false)
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_id")
    private Agent assignee;

    @Column(name = "escalation_level", nullable = false)
    private int escalationLevel;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "first_responded_at")
    private Instant firstRespondedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Version
    private long version;

    protected Ticket() {} // for JPA

    /** A new, unassigned ticket. */
    static Ticket open(String subject, Priority priority, Channel channel, TicketQueue queue, Customer customer,
            Instant now) {
        Ticket ticket = new Ticket();
        ticket.subject = subject;
        ticket.status = TicketStatus.NEW;
        ticket.priority = priority;
        ticket.channel = channel;
        ticket.queue = queue;
        ticket.customer = customer;
        ticket.createdAt = now;
        ticket.updatedAt = now;
        return ticket;
    }

    public static String number(long id) {
        return NUMBER_PREFIX + id;
    }

    public String getNumber() {
        return number(id);
    }

    // ---- changes (package-private: only TicketService and its helpers change tickets) ------

    /** Moves to a status that {@link TicketStatus#after} returned, keeping the timestamps in step. */
    void moveTo(TicketStatus next, Instant now) {
        if (next == TicketStatus.RESOLVED) {
            resolvedAt = now;
        } else if (status == TicketStatus.RESOLVED && next == TicketStatus.OPEN) {
            resolvedAt = null; // reopened
        }
        if (next == TicketStatus.CLOSED) {
            closedAt = now;
        }
        status = next;
        updatedAt = now;
    }

    void assignTo(Agent agent, Instant now) {
        assignee = agent;
        updatedAt = now;
    }

    void changePriority(Priority newPriority, Instant now) {
        priority = newPriority;
        updatedAt = now;
    }

    /** Records the first public reply by an agent (later replies don't change it). */
    void recordResponse(Instant now) {
        if (firstRespondedAt == null) {
            firstRespondedAt = now;
        }
        updatedAt = now;
    }

    void touch(Instant now) {
        updatedAt = now;
    }

    /** An SLA was breached: one level up. Returns the new level. */
    public int escalate(Instant now) {
        escalationLevel++;
        updatedAt = now;
        return escalationLevel;
    }

    public boolean isAssignedTo(long agentId) {
        return assignee != null && assignee.getId() == agentId;
    }

    // ---- getters -----------------------------------------------------------------------

    public Long getId() { return id; }
    public String getSubject() { return subject; }
    public TicketStatus getStatus() { return status; }
    public Priority getPriority() { return priority; }
    public Channel getChannel() { return channel; }
    public TicketQueue getQueue() { return queue; }
    public Customer getCustomer() { return customer; }
    public Agent getAssignee() { return assignee; }
    public Long getAssigneeId() { return assignee == null ? null : assignee.getId(); }
    public int getEscalationLevel() { return escalationLevel; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getFirstRespondedAt() { return firstRespondedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public Instant getClosedAt() { return closedAt; }
    public long getVersion() { return version; }
}
