package com.helpdesk.sla;

/**
 * One SLA target, as copied from a policy onto a timer. Timers keep their own copy, so
 * editing a policy later doesn't silently move the deadlines of tickets already running.
 *
 * @param minutes business minutes allowed
 * @param warningPercent warn when this share of the target has been used
 * @param calendarId which business calendar counts the minutes
 */
public record SlaTarget(int minutes, int warningPercent, long calendarId) {

    public SlaTarget {
        if (minutes <= 0) {
            throw new IllegalArgumentException("SLA target must be positive: " + minutes);
        }
        if (warningPercent < 1 || warningPercent > 99) {
            throw new IllegalArgumentException("Warning percent must be 1-99: " + warningPercent);
        }
    }
}
