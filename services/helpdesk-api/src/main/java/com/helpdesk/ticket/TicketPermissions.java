package com.helpdesk.ticket;

import com.helpdesk.security.Actor;
import java.util.ArrayList;
import java.util.List;

/**
 * Who may do what to a ticket. (Which moves are legal at all is {@link TicketStatus}'s job.)
 *
 * <p>Every staff member can <em>see</em> every ticket: it's one support team, and hiding
 * tickets would make handovers harder. What differs is what they may change:
 * <ul>
 *   <li>the <b>assignee</b> works the ticket: replies, waits, resolves, closes;</li>
 *   <li><b>queue members</b> pick up unassigned tickets and reopen resolved ones;</li>
 *   <li><b>supervisors</b> (and admins) may do anything, including assigning to others.</li>
 * </ul>
 * The web app shows only the actions {@link #allowedActions} returns; the server checks
 * again on every request, so the UI is a convenience, not the guard.
 */
public final class TicketPermissions {

    private TicketPermissions() {}

    public static boolean mayPerform(Actor actor, Ticket ticket, TicketAction action) {
        boolean supervisor = actor.isSupervisor();
        boolean assignee = ticket.isAssignedTo(actor.agentId());
        boolean member = ticket.getQueue().hasMember(actor.agentId());
        return switch (action) {
            case ASSIGN -> supervisor || member;
            case WAIT_ON_CUSTOMER, RESUME, RESOLVE, CLOSE -> supervisor || assignee;
            case REOPEN -> supervisor || assignee || member;
            case CUSTOMER_REPLIED, AUTO_CLOSE -> false; // only the system does these
        };
    }

    /** Replies to the customer come from whoever owns the ticket (internal notes are open to all staff). */
    public static boolean mayReplyPublicly(Actor actor, Ticket ticket) {
        return actor.isSupervisor() || ticket.isAssignedTo(actor.agentId());
    }

    /** Triage: the assignee, or any queue member while nobody has it yet. */
    public static boolean mayChangePriority(Actor actor, Ticket ticket) {
        return actor.isSupervisor() || ticket.isAssignedTo(actor.agentId())
                || (ticket.getAssignee() == null && ticket.getQueue().hasMember(actor.agentId()));
    }

    /** The manual actions this actor may take on this ticket right now. */
    public static List<TicketAction> allowedActions(Actor actor, Ticket ticket) {
        List<TicketAction> allowed = new ArrayList<>();
        for (TicketAction action : TicketAction.values()) {
            if (action.isManual() && ticket.getStatus().allows(action) && mayPerform(actor, ticket, action)) {
                allowed.add(action);
            }
        }
        return allowed;
    }
}
