package com.helpdesk.audit;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {

    /** One history entry as the API shows it. */
    public record AuditView(long id, String actorType, String actorName, String action, Map<String, Object> details,
            Instant occurredAt) {

        static AuditView of(AuditEvent event) {
            return new AuditView(event.getId(), event.getActorType().name(), event.getActorName(), event.getAction(),
                    event.getDetails(), event.getOccurredAt());
        }
    }

    private final AuditEventRepository events;
    private final Clock clock;

    AuditService(AuditEventRepository events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    /**
     * {@code MANDATORY}: must run inside the caller's transaction, so the history entry is
     * committed together with the change it describes, or not at all.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Long ticketId, ActorRef actor, String action, Map<String, Object> details) {
        events.save(new AuditEvent(ticketId, actor, action, details, clock.instant()));
    }

    @Transactional(readOnly = true)
    public List<AuditView> historyOf(long ticketId) {
        return events.findByTicketIdOrderById(ticketId).stream().map(AuditView::of).toList();
    }
}
