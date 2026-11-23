package com.helpdesk.sla;

import com.helpdesk.ticket.Ticket;
import com.helpdesk.ticket.TicketStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps a ticket's SLA timers in step with what happens to the ticket. Called by the ticket
 * service inside its transaction, while it holds the ticket's row lock.
 */
@Service
public class SlaService {

    /** How an SLA clock looks right now, for people. */
    public enum SlaHealth { ON_TRACK, AT_RISK, BREACHED, PAUSED, MET, STOPPED }

    /**
     * One timer as the API shows it.
     *
     * @param ticking false while paused, stopped, or outside the calendar's business hours
     */
    public record SlaTimerView(SlaMetric metric, SlaHealth health, ClockState clockState, int targetMinutes,
            Instant dueAt, Instant warnedAt, Instant breachedAt, Instant metAt, long elapsedSeconds,
            long remainingSeconds, boolean ticking) {}

    private final SlaPolicyRepository policies;
    private final SlaTimerRepository timers;
    private final Calendars calendars;

    SlaService(SlaPolicyRepository policies, SlaTimerRepository timers, Calendars calendars) {
        this.policies = policies;
        this.timers = timers;
        this.calendars = calendars;
    }

    /** A new ticket: both clocks start now, with targets from its priority's policy. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void startTimers(Ticket ticket, Instant now) {
        SlaPolicy policy = policyFor(ticket);
        for (SlaMetric metric : SlaMetric.values()) {
            SlaTarget target = policy.target(metric);
            timers.save(SlaTimer.start(ticket.getId(), metric, target, calendars.get(target.calendarId()), now));
        }
    }

    /**
     * The ticket changed status: run, pause or stop each clock accordingly.
     * <ul>
     *   <li>waiting on the customer pauses both clocks;</li>
     *   <li>resolving meets both targets (a resolution also answers the customer);</li>
     *   <li>reopening a resolved ticket restarts the resolution clock where it stopped.</li>
     * </ul>
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onStatusChanged(Ticket ticket, TicketStatus from, TicketStatus to, Instant now) {
        boolean reopened = from == TicketStatus.RESOLVED && to.slaClock() == ClockState.RUNNING;
        for (SlaTimer timer : timers.findByTicketIdOrderByMetric(ticket.getId())) {
            BusinessCalendar calendar = calendars.get(timer.getCalendarId());
            if (timer.getMetAt() != null) {
                if (reopened && timer.getMetric() == SlaMetric.RESOLUTION) {
                    timer.reopen(now, calendar);
                }
                continue;
            }
            switch (to.slaClock()) {
                case RUNNING -> timer.resume(now, calendar);
                case PAUSED -> timer.pause(now, calendar);
                case STOPPED -> {
                    if (to == TicketStatus.RESOLVED) {
                        timer.meet(now, calendar);
                    } else {
                        timer.stop(now, calendar);
                    }
                }
            }
        }
    }

    /** An agent replied to the customer: the first-response target is met. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onPublicAgentReply(Ticket ticket, Instant now) {
        for (SlaTimer timer : timers.findByTicketIdOrderByMetric(ticket.getId())) {
            if (timer.getMetric() == SlaMetric.FIRST_RESPONSE) {
                timer.meet(now, calendars.get(timer.getCalendarId()));
            }
        }
    }

    /** New priority, new policy: clocks keep the time already used but get the new targets. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onPriorityChanged(Ticket ticket, Instant now) {
        SlaPolicy policy = policyFor(ticket);
        for (SlaTimer timer : timers.findByTicketIdOrderByMetric(ticket.getId())) {
            SlaTarget target = policy.target(timer.getMetric());
            // Elapsed time is measured on the old calendar up to now, then the new one from now on.
            timer.retarget(target, calendars.get(timer.getCalendarId()), calendars.get(target.calendarId()), now);
        }
    }

    public List<SlaTimerView> viewsOf(long ticketId, Instant now) {
        return timers.findByTicketIdOrderByMetric(ticketId).stream().map(timer -> view(timer, now)).toList();
    }

    public Map<Long, List<SlaTimerView>> viewsByTicket(Collection<Long> ticketIds, Instant now) {
        return timers.findByTicketIdIn(ticketIds).stream()
                .sorted((a, b) -> a.getMetric().compareTo(b.getMetric()))
                .collect(Collectors.groupingBy(SlaTimer::getTicketId,
                        Collectors.mapping(timer -> view(timer, now), Collectors.toList())));
    }

    private SlaPolicy policyFor(Ticket ticket) {
        return policies.findById(ticket.getPriority())
                .orElseThrow(() -> new IllegalStateException("No SLA policy for priority " + ticket.getPriority()));
    }

    private SlaTimerView view(SlaTimer timer, Instant now) {
        BusinessCalendar calendar = calendars.get(timer.getCalendarId());
        boolean ticking = timer.getClockState() == ClockState.RUNNING && calendar.isBusinessTime(now);
        return new SlaTimerView(timer.getMetric(), health(timer, now), timer.getClockState(), timer.getTargetMinutes(),
                timer.getDueAt(), timer.getWarnedAt(), timer.getBreachedAt(), timer.getMetAt(),
                timer.elapsed(now, calendar).toSeconds(), timer.remaining(now, calendar).toSeconds(), ticking);
    }

    static SlaHealth health(SlaTimer timer, Instant now) {
        if (timer.getBreachedAt() != null) {
            return SlaHealth.BREACHED;
        }
        if (timer.getMetAt() != null) {
            return SlaHealth.MET;
        }
        return switch (timer.getClockState()) {
            case STOPPED -> SlaHealth.STOPPED;
            case PAUSED -> SlaHealth.PAUSED;
            case RUNNING -> {
                if (!timer.getDueAt().isAfter(now)) {
                    yield SlaHealth.BREACHED; // the worker will record it within seconds
                }
                boolean warned = timer.getWarnedAt() != null || (timer.getWarnAt() != null && !timer.getWarnAt().isAfter(now));
                yield warned ? SlaHealth.AT_RISK : SlaHealth.ON_TRACK;
            }
        };
    }
}
