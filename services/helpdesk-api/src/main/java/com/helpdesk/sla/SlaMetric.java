package com.helpdesk.sla;

/** What an SLA timer measures. Every ticket has one timer per metric. */
public enum SlaMetric {
    /** Until an agent first replies to the customer (a public message). */
    FIRST_RESPONSE,
    /** Until the ticket is resolved. */
    RESOLUTION
}
