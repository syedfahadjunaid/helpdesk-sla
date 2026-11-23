package com.helpdesk.queue;

import com.helpdesk.agent.Agent;
import com.helpdesk.agent.AgentRepository;
import com.helpdesk.audit.ActorRef;
import com.helpdesk.audit.AuditService;
import com.helpdesk.common.BusinessRuleException;
import com.helpdesk.common.NotFoundException;
import com.helpdesk.security.Actor;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class QueueService {

    /** A queue with live ticket counts, for the queue list and the sidebar. */
    public record QueueView(long id, String key, String name, String inboundAddress, boolean defaultQueue,
            List<Long> memberIds, long openTickets, long unassignedTickets) {}

    private final TicketQueueRepository queues;
    private final AgentRepository agents;
    private final AuditService audit;
    private final JdbcClient jdbc;

    QueueService(TicketQueueRepository queues, AgentRepository agents, AuditService audit, JdbcClient jdbc) {
        this.queues = queues;
        this.agents = agents;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    public List<QueueView> list() {
        Map<Long, long[]> counts = ticketCounts();
        return queues.findAllByOrderById().stream()
                .map(queue -> view(queue, counts.getOrDefault(queue.getId(), new long[2])))
                .toList();
    }

    public TicketQueue get(long queueId) {
        return queues.findById(queueId).orElseThrow(() -> new NotFoundException("Queue " + queueId + " not found"));
    }

    public List<Long> queueIdsOf(long agentId) {
        return queues.findIdsByMember(agentId);
    }

    /** Admins decide who works which queue. Only agents who have signed in at least once can be added. */
    @Transactional
    public QueueView replaceMembers(Actor actor, long queueId, Collection<Long> agentIds) {
        TicketQueue queue = get(queueId);
        Set<Long> wanted = Set.copyOf(agentIds);
        List<Agent> members = agents.findAllById(wanted);
        if (members.size() != wanted.size()) {
            throw new BusinessRuleException("agentIds", "Unknown agent id in the list");
        }
        queue.replaceMembers(members);
        audit.record(null, ActorRef.agent(actor), "QUEUE_MEMBERS_CHANGED",
                Map.of("queueId", queueId, "agentIds", wanted.stream().sorted().toList()));
        return view(queue, ticketCounts().getOrDefault(queueId, new long[2]));
    }

    private QueueView view(TicketQueue queue, long[] counts) {
        List<Long> memberIds = queue.getMembers().stream().map(Agent::getId).sorted().toList();
        return new QueueView(queue.getId(), queue.getKey(), queue.getName(), queue.getInboundAddress(),
                queue.isDefaultQueue(), memberIds, counts[0], counts[1]);
    }

    /** queue id -> [tickets not yet resolved, of which unassigned]. */
    private Map<Long, long[]> ticketCounts() {
        return jdbc.sql("""
                        SELECT queue_id,
                               count(*) FILTER (WHERE status IN ('NEW', 'OPEN', 'WAITING_ON_CUSTOMER')) AS open,
                               count(*) FILTER (WHERE status = 'NEW') AS unassigned
                        FROM tickets GROUP BY queue_id""")
                .query(rs -> {
                    Map<Long, long[]> result = new HashMap<>();
                    while (rs.next()) {
                        result.put(rs.getLong("queue_id"), new long[] {rs.getLong("open"), rs.getLong("unassigned")});
                    }
                    return result;
                });
    }
}
