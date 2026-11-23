package com.helpdesk.queue;

import com.helpdesk.agent.Agent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * A queue of tickets (e.g. "General support", "Billing") and the agents who work it.
 * Named {@code TicketQueue} because {@code java.util.Queue} is everywhere.
 *
 * <p>Email to a queue's {@code inboundAddress} becomes a ticket in that queue. Queues are
 * reference data (created by migrations); admins manage who is a member.
 */
@Entity
@Table(name = "queues")
public class TicketQueue {

    @Id
    private Long id;

    @Column(nullable = false, updatable = false)
    private String key;

    @Column(nullable = false)
    private String name;

    @Column(name = "inbound_address")
    private String inboundAddress;

    @Column(name = "is_default", nullable = false)
    private boolean defaultQueue;

    @ManyToMany
    @JoinTable(name = "queue_members",
            joinColumns = @JoinColumn(name = "queue_id"),
            inverseJoinColumns = @JoinColumn(name = "agent_id"))
    private Set<Agent> members = new HashSet<>();

    protected TicketQueue() {} // for JPA

    public boolean hasMember(long agentId) {
        return members.stream().anyMatch(agent -> agent.getId() == agentId);
    }

    void replaceMembers(Collection<Agent> agents) {
        members.clear();
        members.addAll(agents);
    }

    public Long getId() { return id; }
    public String getKey() { return key; }
    public String getName() { return name; }
    public String getInboundAddress() { return inboundAddress; }
    public boolean isDefaultQueue() { return defaultQueue; }
    public Set<Agent> getMembers() { return Set.copyOf(members); }
}
