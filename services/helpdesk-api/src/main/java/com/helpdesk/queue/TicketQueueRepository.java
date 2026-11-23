package com.helpdesk.queue;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TicketQueueRepository extends JpaRepository<TicketQueue, Long> {

    List<TicketQueue> findAllByOrderById();

    Optional<TicketQueue> findByInboundAddress(String inboundAddress);

    Optional<TicketQueue> findByDefaultQueueTrue();

    List<TicketQueue> findByInboundAddressNotNull();

    @Query("SELECT q.id FROM TicketQueue q JOIN q.members m WHERE m.id = :agentId ORDER BY q.id")
    List<Long> findIdsByMember(long agentId);
}
