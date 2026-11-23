package com.helpdesk.ticket;

import com.helpdesk.sla.ClockState;

/**
 * The ticket lifecycle as a state machine.
 *
 * <pre>
 *            assign                 wait on customer
 *   NEW ───────────────► OPEN ─────────────────────────► WAITING_ON_CUSTOMER
 *    │                    ▲  ▲   ◄── resume / customer replied ──┘     │
 *    │                    │  │                                         │
 *    │                    │  └──────── reopen / customer replied ──┐   │ resolve
 *    │                    │ resolve                                │   ▼
 *    │                    └──────────────────────────────────► RESOLVED ── close / auto-close ──► CLOSED
 * </pre>
 *
 * All rules about which move is legal live in {@link #next}. Who may make a move is decided
 * in {@link TicketPermissions}; what happens to the SLA clocks in {@link #slaClock}.
 */
public enum TicketStatus {
    /** Nobody has picked it up yet (the database guarantees: NEW if and only if unassigned). */
    NEW,
    /** Assigned and being worked on. */
    OPEN,
    /** An agent asked the customer something. The resolution clock is paused. */
    WAITING_ON_CUSTOMER,
    /** Done, as far as the agent is concerned. A customer reply reopens it. */
    RESOLVED,
    /** Final. A customer email about it starts a new, linked ticket instead. */
    CLOSED;

    /**
     * The status after {@code action}, or null if the move isn't allowed.
     *
     * <p>Nested switch <em>expressions</em> without a {@code default}: the compiler checks
     * that every status and every action is handled, so a new status or action can't be
     * added without deciding all of its transitions.
     */
    private TicketStatus next(TicketAction action) {
        return switch (this) {
            case NEW -> switch (action) {
                case ASSIGN -> OPEN;
                case CUSTOMER_REPLIED -> NEW;
                case WAIT_ON_CUSTOMER, RESUME, RESOLVE, REOPEN, CLOSE, AUTO_CLOSE -> null;
            };
            case OPEN -> switch (action) {
                case ASSIGN, CUSTOMER_REPLIED -> OPEN;
                case WAIT_ON_CUSTOMER -> WAITING_ON_CUSTOMER;
                case RESOLVE -> RESOLVED;
                case RESUME, REOPEN, CLOSE, AUTO_CLOSE -> null;
            };
            case WAITING_ON_CUSTOMER -> switch (action) {
                case ASSIGN -> WAITING_ON_CUSTOMER;
                case RESUME, CUSTOMER_REPLIED -> OPEN;
                case RESOLVE -> RESOLVED;
                case WAIT_ON_CUSTOMER, REOPEN, CLOSE, AUTO_CLOSE -> null;
            };
            case RESOLVED -> switch (action) {
                case REOPEN, CUSTOMER_REPLIED -> OPEN;
                case CLOSE, AUTO_CLOSE -> CLOSED;
                case ASSIGN, WAIT_ON_CUSTOMER, RESUME, RESOLVE -> null;
            };
            case CLOSED -> null;
        };
    }

    /** The status after {@code action}; throws (and the API answers 409) if the move isn't allowed. */
    public TicketStatus after(TicketAction action) {
        TicketStatus next = next(action);
        if (next == null) {
            throw new InvalidTransitionException(this, action);
        }
        return next;
    }

    public boolean allows(TicketAction action) {
        return next(action) != null;
    }

    /** What the SLA clocks do while a ticket is in this status. */
    public ClockState slaClock() {
        return switch (this) {
            case NEW, OPEN -> ClockState.RUNNING;
            case WAITING_ON_CUSTOMER -> ClockState.PAUSED;
            case RESOLVED, CLOSED -> ClockState.STOPPED;
        };
    }

    /** Still needs work (not resolved or closed). */
    public boolean isActive() {
        return slaClock() != ClockState.STOPPED;
    }
}
