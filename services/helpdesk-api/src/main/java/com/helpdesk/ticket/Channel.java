package com.helpdesk.ticket;

/** How a ticket reached us. */
public enum Channel {
    /** The customer emailed one of the queues' addresses. */
    EMAIL,
    /** An agent opened it in the web app (e.g. after a phone call). */
    AGENT
}
