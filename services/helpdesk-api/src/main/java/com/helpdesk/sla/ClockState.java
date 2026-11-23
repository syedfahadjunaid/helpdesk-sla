package com.helpdesk.sla;

/** Whether an SLA timer is counting. */
public enum ClockState {
    /** Counting business time; {@code due_at} says when the target will be reached. */
    RUNNING,
    /** Waiting on the customer: time doesn't count, and resumes where it left off. */
    PAUSED,
    /** Finished: the target was met (or the ticket was closed). */
    STOPPED
}
