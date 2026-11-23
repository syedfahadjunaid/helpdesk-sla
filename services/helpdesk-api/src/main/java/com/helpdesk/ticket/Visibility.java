package com.helpdesk.ticket;

/** Who can see a ticket message. */
public enum Visibility {
    /** Part of the conversation with the customer: emailed to them. */
    PUBLIC,
    /** A note between staff. Never leaves the helpdesk. */
    INTERNAL
}
