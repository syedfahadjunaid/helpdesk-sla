package com.helpdesk.sla;

import com.helpdesk.ticket.Priority;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/** The SLA targets for one priority, e.g. "urgent: first response in 30 minutes, 24x7". */
@Entity
@Table(name = "sla_policies")
public class SlaPolicy {

    @Id
    @Enumerated(EnumType.STRING)
    private Priority priority;

    @Column(name = "first_response_minutes", nullable = false)
    private int firstResponseMinutes;

    @Column(name = "resolution_minutes", nullable = false)
    private int resolutionMinutes;

    @Column(name = "warning_percent", nullable = false)
    private int warningPercent;

    @Column(name = "calendar_id", nullable = false)
    private long calendarId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Optimistic locking: two admins saving at once can't silently overwrite each other. */
    @Version
    private long version;

    protected SlaPolicy() {} // for JPA

    public SlaTarget target(SlaMetric metric) {
        return switch (metric) {
            case FIRST_RESPONSE -> new SlaTarget(firstResponseMinutes, warningPercent, calendarId);
            case RESOLUTION -> new SlaTarget(resolutionMinutes, warningPercent, calendarId);
        };
    }

    void update(int firstResponseMinutes, int resolutionMinutes, int warningPercent, long calendarId, Instant now) {
        this.firstResponseMinutes = firstResponseMinutes;
        this.resolutionMinutes = resolutionMinutes;
        this.warningPercent = warningPercent;
        this.calendarId = calendarId;
        this.updatedAt = now;
    }

    public Priority getPriority() { return priority; }
    public int getFirstResponseMinutes() { return firstResponseMinutes; }
    public int getResolutionMinutes() { return resolutionMinutes; }
    public int getWarningPercent() { return warningPercent; }
    public long getCalendarId() { return calendarId; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
