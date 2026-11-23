package com.helpdesk.sla;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * SLA timers. Callers must already hold the owning ticket's row lock before changing a timer
 * (see {@link SlaTimer}); these queries don't lock anything themselves.
 */
public interface SlaTimerRepository extends JpaRepository<SlaTimer, Long> {

    List<SlaTimer> findByTicketIdOrderByMetric(long ticketId);

    List<SlaTimer> findByTicketIdIn(Collection<Long> ticketIds);
}
