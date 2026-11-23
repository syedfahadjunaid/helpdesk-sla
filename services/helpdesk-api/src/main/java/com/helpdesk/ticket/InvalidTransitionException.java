package com.helpdesk.ticket;

import com.helpdesk.common.ConflictException;
import java.util.Locale;

/** The ticket's current status doesn't allow this action (answered with 409 Conflict). */
public class InvalidTransitionException extends ConflictException {

    public InvalidTransitionException(TicketStatus status, TicketAction action) {
        super("Can't " + action.name().toLowerCase(Locale.ROOT).replace('_', ' ')
                + " a ticket that is " + status.name().toLowerCase(Locale.ROOT).replace('_', ' ') + ".");
    }
}
