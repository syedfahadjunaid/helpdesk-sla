package com.helpdesk.events;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.helpdesk.sla.SlaMetric;
import com.helpdesk.ticket.AuthorType;
import com.helpdesk.ticket.Channel;
import com.helpdesk.ticket.Priority;
import com.helpdesk.ticket.TicketAction;
import com.helpdesk.ticket.TicketStatus;
import com.helpdesk.ticket.Visibility;
import java.time.Instant;

/**
 * Domain events: facts about tickets that other parts of the system react to (live updates
 * in browsers, in-app notifications, emails to customers and supervisors).
 *
 * <p>A <b>sealed</b> interface: every event type is listed here, so a {@code switch} over a
 * {@code TicketEvent} is checked for completeness by the compiler. Add a new event and the
 * compiler points at every consumer that must decide what to do with it.
 *
 * <p>Events travel as JSON. {@code @JsonTypeInfo} adds a {@code "type"} field (e.g.
 * {@code "sla.breached"}), which is also the RabbitMQ routing key, so consumers can bind to
 * just the events they care about ({@code sla.*}, {@code ticket.#}).
 *
 * <p>Events carry ids and small facts, not whole tickets: consumers load what they need,
 * and nothing sensitive (like message bodies) passes through the broker.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = TicketEvent.Created.class, name = TicketEvent.CREATED),
        @JsonSubTypes.Type(value = TicketEvent.Assigned.class, name = TicketEvent.ASSIGNED),
        @JsonSubTypes.Type(value = TicketEvent.StatusChanged.class, name = TicketEvent.STATUS_CHANGED),
        @JsonSubTypes.Type(value = TicketEvent.PriorityChanged.class, name = TicketEvent.PRIORITY_CHANGED),
        @JsonSubTypes.Type(value = TicketEvent.MessageAdded.class, name = TicketEvent.MESSAGE_ADDED),
        @JsonSubTypes.Type(value = TicketEvent.SlaWarning.class, name = TicketEvent.SLA_WARNING),
        @JsonSubTypes.Type(value = TicketEvent.SlaBreached.class, name = TicketEvent.SLA_BREACHED)
})
public sealed interface TicketEvent {

    String CREATED = "ticket.created";
    String ASSIGNED = "ticket.assigned";
    String STATUS_CHANGED = "ticket.status-changed";
    String PRIORITY_CHANGED = "ticket.priority-changed";
    String MESSAGE_ADDED = "ticket.message-added";
    String SLA_WARNING = "sla.warning";
    String SLA_BREACHED = "sla.breached";

    long ticketId();

    /** "HD-1001": what people see and say. */
    String ticketNumber();

    /** The event's name, also its RabbitMQ routing key. A switch expression: exhaustive by construction. */
    default String eventType() {
        return switch (this) {
            case Created _ -> CREATED;
            case Assigned _ -> ASSIGNED;
            case StatusChanged _ -> STATUS_CHANGED;
            case PriorityChanged _ -> PRIORITY_CHANGED;
            case MessageAdded _ -> MESSAGE_ADDED;
            case SlaWarning _ -> SLA_WARNING;
            case SlaBreached _ -> SLA_BREACHED;
        };
    }

    record Created(long ticketId, String ticketNumber, long queueId, Priority priority, Channel channel)
            implements TicketEvent {}

    record Assigned(long ticketId, String ticketNumber, long assigneeId, Long previousAssigneeId, Long assignedById)
            implements TicketEvent {}

    record StatusChanged(long ticketId, String ticketNumber, TicketStatus from, TicketStatus to, TicketAction action,
            Long assigneeId) implements TicketEvent {}

    record PriorityChanged(long ticketId, String ticketNumber, Priority from, Priority to) implements TicketEvent {}

    record MessageAdded(long ticketId, String ticketNumber, long messageId, AuthorType authorType,
            Visibility visibility, Long assigneeId) implements TicketEvent {}

    record SlaWarning(long ticketId, String ticketNumber, SlaMetric metric, Instant dueAt, Long assigneeId,
            long queueId) implements TicketEvent {}

    record SlaBreached(long ticketId, String ticketNumber, SlaMetric metric, Instant breachedAt, int escalationLevel,
            Long assigneeId, long queueId) implements TicketEvent {}
}
