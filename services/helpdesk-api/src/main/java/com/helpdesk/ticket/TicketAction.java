package com.helpdesk.ticket;

/** Everything that can move a ticket through its lifecycle (see {@link TicketStatus}). */
public enum TicketAction {
    // Done by agents through the API
    ASSIGN,
    WAIT_ON_CUSTOMER,
    RESUME,
    RESOLVE,
    REOPEN,
    CLOSE,

    // Done by the system only
    /** The customer emailed about the ticket. */
    CUSTOMER_REPLIED,
    /** A resolved ticket heard nothing back for a while (see helpdesk.tickets.auto-close-after). */
    AUTO_CLOSE;

    /** Can an agent ask for this through the API? */
    public boolean isManual() {
        return switch (this) {
            case ASSIGN, WAIT_ON_CUSTOMER, RESUME, RESOLVE, REOPEN, CLOSE -> true;
            case CUSTOMER_REPLIED, AUTO_CLOSE -> false;
        };
    }
}
