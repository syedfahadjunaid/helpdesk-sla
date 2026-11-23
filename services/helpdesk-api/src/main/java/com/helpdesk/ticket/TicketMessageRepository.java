package com.helpdesk.ticket;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketMessageRepository extends JpaRepository<TicketMessage, Long> {

    List<TicketMessage> findByTicketIdOrderById(long ticketId);

    /** The customer's latest email on a ticket: replies to them should thread under it. */
    Optional<TicketMessage> findFirstByTicketIdAndEmailMessageIdNotNullOrderByIdDesc(long ticketId);
}
