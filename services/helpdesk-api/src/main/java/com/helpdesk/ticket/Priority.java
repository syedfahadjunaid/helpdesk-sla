package com.helpdesk.ticket;

/** How urgent a ticket is. Each priority has its own SLA policy (targets and calendar). */
public enum Priority {
    LOW,
    NORMAL,
    HIGH,
    URGENT
}
