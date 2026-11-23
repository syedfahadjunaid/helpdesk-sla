package com.helpdesk.sla;

import com.helpdesk.audit.ActorRef;
import com.helpdesk.audit.AuditService;
import com.helpdesk.common.BusinessRuleException;
import com.helpdesk.common.ConflictException;
import com.helpdesk.common.NotFoundException;
import com.helpdesk.security.Actor;
import com.helpdesk.sla.Calendars.CalendarSummary;
import com.helpdesk.ticket.Priority;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SLA policies: everyone can read them; admins change them. A change applies to tickets
 * created (or re-prioritised) afterwards; running clocks keep the targets they started with.
 */
@RestController
@RequestMapping("/api")
public class SlaPolicyController {

    public record PolicyView(Priority priority, int firstResponseMinutes, int resolutionMinutes, int warningPercent,
            long calendarId, String calendarName, Instant updatedAt, long version) {}

    /**
     * @param version the version the admin edited, so a concurrent change isn't silently overwritten
     */
    public record PolicyUpdate(
            @NotNull @Min(1) @Max(60 * 24 * 90) Integer firstResponseMinutes,
            @NotNull @Min(1) @Max(60 * 24 * 365) Integer resolutionMinutes,
            @NotNull @Min(1) @Max(99) Integer warningPercent,
            @NotNull Long calendarId,
            @NotNull Long version) {}

    private final SlaPolicyRepository policies;
    private final Calendars calendars;
    private final AuditService audit;
    private final Clock clock;

    SlaPolicyController(SlaPolicyRepository policies, Calendars calendars, AuditService audit, Clock clock) {
        this.policies = policies;
        this.calendars = calendars;
        this.audit = audit;
        this.clock = clock;
    }

    @GetMapping("/sla-policies")
    @Transactional(readOnly = true)
    public List<PolicyView> list() {
        Map<Long, String> names = calendarNames();
        return policies.findAll().stream()
                .sorted((a, b) -> b.getPriority().compareTo(a.getPriority()))
                .map(policy -> view(policy, names))
                .toList();
    }

    @GetMapping("/calendars")
    public List<CalendarSummary> calendars() {
        return calendars.list();
    }

    @PutMapping("/sla-policies/{priority}")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public PolicyView update(@PathVariable Priority priority, @Valid @RequestBody PolicyUpdate update, Actor actor) {
        SlaPolicy policy = policies.findById(priority)
                .orElseThrow(() -> new NotFoundException("No SLA policy for " + priority));
        if (policy.getVersion() != update.version()) {
            throw new ConflictException("Someone else changed this policy. Reload and try again.");
        }
        if (update.resolutionMinutes() < update.firstResponseMinutes()) {
            throw new BusinessRuleException("resolutionMinutes", "Resolution can't be faster than first response.");
        }
        Map<Long, String> names = calendarNames();
        if (!names.containsKey(update.calendarId())) {
            throw new BusinessRuleException("calendarId", "Unknown calendar.");
        }
        policy.update(update.firstResponseMinutes(), update.resolutionMinutes(), update.warningPercent(),
                update.calendarId(), clock.instant());
        audit.record(null, ActorRef.agent(actor), "SLA_POLICY_CHANGED", Map.of(
                "priority", priority.name(),
                "firstResponseMinutes", update.firstResponseMinutes(),
                "resolutionMinutes", update.resolutionMinutes(),
                "warningPercent", update.warningPercent(),
                "calendarId", update.calendarId()));
        policies.flush(); // bump @Version now, so the response carries the new version
        return view(policy, names);
    }

    private Map<Long, String> calendarNames() {
        return calendars.list().stream()
                .collect(Collectors.toMap(CalendarSummary::id, CalendarSummary::name));
    }

    private static PolicyView view(SlaPolicy policy, Map<Long, String> calendarNames) {
        return new PolicyView(policy.getPriority(), policy.getFirstResponseMinutes(), policy.getResolutionMinutes(),
                policy.getWarningPercent(), policy.getCalendarId(), calendarNames.get(policy.getCalendarId()),
                policy.getUpdatedAt(), policy.getVersion());
    }
}
