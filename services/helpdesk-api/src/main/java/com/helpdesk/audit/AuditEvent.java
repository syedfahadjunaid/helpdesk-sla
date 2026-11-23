package com.helpdesk.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One entry in the append-only history. {@code @Immutable} means Hibernate never issues an
 * UPDATE for it; the database adds a trigger and withholds UPDATE/DELETE from the app login.
 */
@Entity
@Immutable
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ticket_id")
    private Long ticketId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false)
    private ActorRef.Type actorType;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "actor_name", nullable = false)
    private String actorName;

    @Column(nullable = false)
    private String action;

    /** Free-form facts about the change, stored as jsonb (e.g. {"from": "OPEN", "to": "RESOLVED"}). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> details;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected AuditEvent() {} // for JPA

    AuditEvent(Long ticketId, ActorRef actor, String action, Map<String, Object> details, Instant occurredAt) {
        this.ticketId = ticketId;
        this.actorType = actor.type();
        this.actorId = actor.id();
        this.actorName = actor.name();
        this.action = action;
        this.details = new LinkedHashMap<>(details); // (Map.copyOf would reject null values)
        this.occurredAt = occurredAt;
    }

    public Long getId() { return id; }
    public Long getTicketId() { return ticketId; }
    public ActorRef.Type getActorType() { return actorType; }
    public Long getActorId() { return actorId; }
    public String getActorName() { return actorName; }
    public String getAction() { return action; }
    public Map<String, Object> getDetails() { return details; }
    public Instant getOccurredAt() { return occurredAt; }
}
