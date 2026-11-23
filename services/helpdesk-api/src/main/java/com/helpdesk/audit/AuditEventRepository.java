package com.helpdesk.audit;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    List<AuditEvent> findByTicketIdOrderById(long ticketId);

    long countByTicketIdAndAction(long ticketId, String action);

    long countByAction(String action);
}
