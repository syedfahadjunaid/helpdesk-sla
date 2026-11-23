package com.helpdesk.sla;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;

/**
 * One SLA clock of one ticket (first response, or resolution).
 *
 * <p><b>Why the deadline is stored, not a timer.</b> An in-memory timer dies with the
 * process and only fires on the instance that created it. Instead, every time the clock
 * starts or resumes we compute the wall-clock instant at which the target will be reached
 * ({@code dueAt}) and the warning threshold ({@code warnAt}), and store them. A background
 * worker on any instance then just asks the database "which running clocks are past due?".
 * A restart loses nothing.
 *
 * <p><b>Pausing.</b> Business time already used is kept in {@code consumedSeconds}. While
 * running, the time used so far is {@code consumedSeconds + businessTimeBetween(runningSince,
 * now)}. Pausing folds the running part into {@code consumedSeconds} and clears the
 * deadlines; resuming recomputes them from what's left.
 *
 * <p><b>Locking.</b> Timers belong to their ticket. Code changes them only while holding the
 * ticket's row lock, so the worker and an agent can never update the same clock at once.
 */
@Entity
@Table(name = "sla_timers")
public class SlaTimer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ticket_id", nullable = false, updatable = false)
    private long ticketId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private SlaMetric metric;

    @Column(name = "target_minutes", nullable = false)
    private int targetMinutes;

    @Column(name = "warning_percent", nullable = false)
    private int warningPercent;

    @Column(name = "calendar_id", nullable = false)
    private long calendarId;

    @Enumerated(EnumType.STRING)
    @Column(name = "clock_state", nullable = false)
    private ClockState clockState;

    @Column(name = "consumed_seconds", nullable = false)
    private long consumedSeconds;

    @Column(name = "running_since")
    private Instant runningSince;

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(name = "warn_at")
    private Instant warnAt;

    @Column(name = "warned_at")
    private Instant warnedAt;

    @Column(name = "breached_at")
    private Instant breachedAt;

    @Column(name = "met_at")
    private Instant metAt;

    protected SlaTimer() {} // for JPA

    private SlaTimer(long ticketId, SlaMetric metric, SlaTarget target) {
        this.ticketId = ticketId;
        this.metric = metric;
        this.targetMinutes = target.minutes();
        this.warningPercent = target.warningPercent();
        this.calendarId = target.calendarId();
        this.clockState = ClockState.PAUSED;
    }

    /** A new timer, running from {@code now}. */
    public static SlaTimer start(long ticketId, SlaMetric metric, SlaTarget target, BusinessCalendar calendar, Instant now) {
        SlaTimer timer = new SlaTimer(ticketId, metric, target);
        timer.resume(now, calendar);
        return timer;
    }

    // ---- state changes ---------------------------------------------------------------

    /** Starts counting again (no-op if already running or finished). */
    public void resume(Instant now, BusinessCalendar calendar) {
        if (clockState != ClockState.PAUSED) {
            return;
        }
        clockState = ClockState.RUNNING;
        runningSince = now;
        schedule(calendar);
    }

    /** Stops counting for now; the time already used is kept (no-op unless running). */
    public void pause(Instant now, BusinessCalendar calendar) {
        if (clockState != ClockState.RUNNING) {
            return;
        }
        foldRunningTime(now, calendar);
        clockState = ClockState.PAUSED;
    }

    /** The target was met (an agent replied, or the ticket was resolved). */
    public void meet(Instant now, BusinessCalendar calendar) {
        if (metAt != null) {
            return;
        }
        foldRunningTime(now, calendar);
        clockState = ClockState.STOPPED;
        metAt = now;
    }

    /** Stops for good without meeting the target (e.g. the ticket was closed). */
    public void stop(Instant now, BusinessCalendar calendar) {
        foldRunningTime(now, calendar);
        clockState = ClockState.STOPPED;
    }

    /** A resolved ticket was reopened: the resolution clock runs again from where it stopped. */
    public void reopen(Instant now, BusinessCalendar calendar) {
        metAt = null;
        clockState = ClockState.PAUSED;
        resume(now, calendar);
    }

    /**
     * The ticket's priority changed, so its SLA policy did too. Time already used still
     * counts; only the target (and so the deadline) moves. A target that is already exceeded
     * makes the clock due immediately.
     */
    public void retarget(SlaTarget target, BusinessCalendar currentCalendar, BusinessCalendar newCalendar, Instant now) {
        boolean running = clockState == ClockState.RUNNING;
        if (running) {
            foldRunningTime(now, currentCalendar); // time so far counts on the calendar it ran on
        }
        targetMinutes = target.minutes();
        warningPercent = target.warningPercent();
        calendarId = target.calendarId();
        if (running) {
            runningSince = now;
            schedule(newCalendar);
        }
    }

    /** The worker found this clock past its deadline. The breach time is the deadline itself, not "when we noticed". */
    public void recordBreach() {
        if (breachedAt != null || dueAt == null) {
            throw new IllegalStateException("Timer " + id + " can't be breached now");
        }
        breachedAt = dueAt;
        warnAt = null; // a warning after the breach would be pointless
    }

    public void recordWarning(Instant now) {
        if (warnedAt != null) {
            throw new IllegalStateException("Timer " + id + " was already warned about");
        }
        warnedAt = now;
        warnAt = null;
    }

    // ---- questions the worker asks -----------------------------------------------------

    public boolean isBreachDue(Instant now) {
        return clockState == ClockState.RUNNING && breachedAt == null && !dueAt.isAfter(now);
    }

    public boolean isWarningDue(Instant now) {
        return clockState == ClockState.RUNNING && warnedAt == null && breachedAt == null
                && warnAt != null && !warnAt.isAfter(now);
    }

    /** Business time used so far, including the part of the current running stretch. */
    public Duration elapsed(Instant now, BusinessCalendar calendar) {
        Duration used = Duration.ofSeconds(consumedSeconds);
        if (clockState == ClockState.RUNNING) {
            used = used.plus(calendar.businessTimeBetween(runningSince, now));
        }
        return used;
    }

    /** Business time left before the target (negative once it's exceeded). */
    public Duration remaining(Instant now, BusinessCalendar calendar) {
        return target().minus(elapsed(now, calendar));
    }

    // ---- internals ---------------------------------------------------------------------

    private Duration target() {
        return Duration.ofMinutes(targetMinutes);
    }

    /** Moves the current running stretch into {@code consumedSeconds} and clears the deadlines. */
    private void foldRunningTime(Instant now, BusinessCalendar calendar) {
        if (clockState == ClockState.RUNNING) {
            consumedSeconds += calendar.businessTimeBetween(runningSince, now).toSeconds();
        }
        runningSince = null;
        dueAt = null;
        warnAt = null;
    }

    /** Computes the deadlines for a clock that is running from {@code runningSince}. */
    private void schedule(BusinessCalendar calendar) {
        Duration used = Duration.ofSeconds(consumedSeconds);
        dueAt = deadline(calendar, target(), used);
        Duration warningThreshold = target().multipliedBy(warningPercent).dividedBy(100);
        warnAt = (warnedAt == null && breachedAt == null) ? deadline(calendar, warningThreshold, used) : null;
    }

    private Instant deadline(BusinessCalendar calendar, Duration threshold, Duration used) {
        return used.compareTo(threshold) >= 0
                ? runningSince // already past it: due right away
                : calendar.addBusinessTime(runningSince, threshold.minus(used));
    }

    // ---- getters -----------------------------------------------------------------------

    public Long getId() { return id; }
    public long getTicketId() { return ticketId; }
    public SlaMetric getMetric() { return metric; }
    public int getTargetMinutes() { return targetMinutes; }
    public int getWarningPercent() { return warningPercent; }
    public long getCalendarId() { return calendarId; }
    public ClockState getClockState() { return clockState; }
    public long getConsumedSeconds() { return consumedSeconds; }
    public Instant getRunningSince() { return runningSince; }
    public Instant getDueAt() { return dueAt; }
    public Instant getWarnAt() { return warnAt; }
    public Instant getWarnedAt() { return warnedAt; }
    public Instant getBreachedAt() { return breachedAt; }
    public Instant getMetAt() { return metAt; }
}
