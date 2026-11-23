package com.helpdesk.ticket;

import com.helpdesk.agent.Agent;
import com.helpdesk.agent.AgentRepository;
import com.helpdesk.audit.ActorRef;
import com.helpdesk.audit.AuditService;
import com.helpdesk.common.BusinessRuleException;
import com.helpdesk.common.ConflictException;
import com.helpdesk.common.ForbiddenException;
import com.helpdesk.common.NotFoundException;
import com.helpdesk.common.PageResponse;
import com.helpdesk.customer.Customer;
import com.helpdesk.customer.CustomerService;
import com.helpdesk.events.DomainEvents;
import com.helpdesk.events.TicketEvent;
import com.helpdesk.queue.QueueService;
import com.helpdesk.queue.TicketQueue;
import com.helpdesk.security.Actor;
import com.helpdesk.sla.SlaService;
import com.helpdesk.sla.SlaService.SlaTimerView;
import com.helpdesk.ticket.TicketDtos.CreateTicketRequest;
import com.helpdesk.ticket.TicketDtos.CustomerRef;
import com.helpdesk.ticket.TicketDtos.MessageView;
import com.helpdesk.ticket.TicketDtos.PersonRef;
import com.helpdesk.ticket.TicketDtos.QueueRef;
import com.helpdesk.ticket.TicketDtos.TicketDetail;
import com.helpdesk.ticket.TicketDtos.TicketSummary;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Everything that changes a ticket goes through here.
 *
 * <p>Each change follows the same recipe, in one transaction:
 * <ol>
 *   <li>lock the ticket row ({@link #lock});</li>
 *   <li>check the move is legal ({@link TicketStatus}, 409) and allowed for this person
 *       ({@link TicketPermissions}, 403);</li>
 *   <li>change the ticket and its SLA clocks ({@link SlaService});</li>
 *   <li>append to the audit history and write domain events to the outbox.</li>
 * </ol>
 * Because history and events are written in the same transaction, they can't disagree
 * with what actually happened.
 */
@Service
@Transactional(readOnly = true)
public class TicketService {

    private final TicketRepository tickets;
    private final TicketMessageRepository messages;
    private final AgentRepository agents;
    private final QueueService queues;
    private final CustomerService customers;
    private final SlaService sla;
    private final AuditService audit;
    private final DomainEvents events;
    private final Clock clock;

    TicketService(TicketRepository tickets, TicketMessageRepository messages, AgentRepository agents,
            QueueService queues, CustomerService customers, SlaService sla, AuditService audit, DomainEvents events,
            Clock clock) {
        this.tickets = tickets;
        this.messages = messages;
        this.agents = agents;
        this.queues = queues;
        this.customers = customers;
        this.sla = sla;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    // =====================================================================================
    // Reading
    // =====================================================================================

    public PageResponse<TicketSummary> search(TicketFilter filter, Actor actor, Pageable pageable) {
        Page<Ticket> page = tickets.findAll(filter.toSpecification(actor.agentId()), pageable);
        Instant now = clock.instant();
        Map<Long, List<SlaTimerView>> timers = sla.viewsByTicket(page.map(Ticket::getId).getContent(), now);
        return PageResponse.of(page.map(ticket -> summary(ticket, timers.getOrDefault(ticket.getId(), List.of()))));
    }

    public TicketDetail detail(long ticketId, Actor actor) {
        return detail(find(ticketId), actor);
    }

    // =====================================================================================
    // Creating
    // =====================================================================================

    /** An agent logs a ticket for a customer. The description is kept as an internal note. */
    @Transactional
    public TicketDetail createByAgent(Actor actor, CreateTicketRequest request) {
        Instant now = clock.instant();
        TicketQueue queue = queues.get(request.queueId());
        Customer customer = customers.findOrCreate(request.customerEmail(), request.customerName());
        Ticket ticket = create(request.subject(), request.priority(), Channel.AGENT, queue, customer,
                ActorRef.agent(actor), now);
        Agent author = agents.getReferenceById(actor.agentId());
        messages.save(TicketMessage.byAgent(ticket.getId(), author, Visibility.INTERNAL, request.description(), now));
        return detail(ticket, actor);
    }

    /** A new email from a customer. Called by the mail intake inside its transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Ticket createFromEmail(Customer customer, TicketQueue queue, String subject, String body,
            String emailMessageId) {
        Instant now = clock.instant();
        Ticket ticket = create(subject, Priority.NORMAL, Channel.EMAIL, queue, customer, ActorRef.customer(customer), now);
        TicketMessage message = messages.save(TicketMessage.byCustomer(ticket.getId(), customer, body, emailMessageId, now));
        events.publish(new TicketEvent.MessageAdded(ticket.getId(), ticket.getNumber(), message.getId(),
                AuthorType.CUSTOMER, Visibility.PUBLIC, null));
        return ticket;
    }

    private Ticket create(String subject, Priority priority, Channel channel, TicketQueue queue, Customer customer,
            ActorRef actor, Instant now) {
        Ticket ticket = tickets.save(Ticket.open(subject, priority, channel, queue, customer, now));
        sla.startTimers(ticket, now);
        audit.record(ticket.getId(), actor, "TICKET_CREATED", details(
                "queue", queue.getKey(), "priority", priority, "channel", channel));
        events.publish(new TicketEvent.Created(ticket.getId(), ticket.getNumber(), queue.getId(), priority, channel));
        return ticket;
    }

    // =====================================================================================
    // Conversation
    // =====================================================================================

    @Transactional
    public MessageView addAgentMessage(Actor actor, long ticketId, String body, Visibility visibility) {
        Instant now = clock.instant();
        Ticket ticket = lock(ticketId);
        if (ticket.getStatus() == TicketStatus.CLOSED) {
            throw new ConflictException("This ticket is closed. A customer email about it opens a new ticket.");
        }
        if (visibility == Visibility.PUBLIC && !TicketPermissions.mayReplyPublicly(actor, ticket)) {
            throw new ForbiddenException(ticket.getAssignee() == null
                    ? "Assign the ticket to yourself before replying to the customer."
                    : "Only the assignee or a supervisor can reply to the customer.");
        }
        TicketMessage message = messages.save(
                TicketMessage.byAgent(ticketId, agents.getReferenceById(actor.agentId()), visibility, body, now));
        if (visibility == Visibility.PUBLIC) {
            ticket.recordResponse(now);
            sla.onPublicAgentReply(ticket, now);
        } else {
            ticket.touch(now);
        }
        audit.record(ticketId, ActorRef.agent(actor), "MESSAGE_ADDED",
                details("messageId", message.getId(), "visibility", visibility));
        events.publish(new TicketEvent.MessageAdded(ticketId, ticket.getNumber(), message.getId(), AuthorType.AGENT,
                visibility, ticket.getAssigneeId()));
        return messageView(message);
    }

    /**
     * The customer replied by email to a ticket that isn't closed. The ticket must already be
     * locked by the caller. A reply while we wait on the customer resumes the SLA clock; a
     * reply to a resolved ticket reopens it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void addCustomerReply(Ticket ticket, Customer customer, String body, String emailMessageId) {
        Instant now = clock.instant();
        TicketMessage message = messages.save(
                TicketMessage.byCustomer(ticket.getId(), customer, body, emailMessageId, now));
        ticket.touch(now);
        ActorRef actor = ActorRef.customer(customer);
        audit.record(ticket.getId(), actor, "MESSAGE_ADDED",
                details("messageId", message.getId(), "visibility", Visibility.PUBLIC));
        events.publish(new TicketEvent.MessageAdded(ticket.getId(), ticket.getNumber(), message.getId(),
                AuthorType.CUSTOMER, Visibility.PUBLIC, ticket.getAssigneeId()));
        transition(ticket, TicketAction.CUSTOMER_REPLIED, actor, now);
    }

    // =====================================================================================
    // Lifecycle
    // =====================================================================================

    @Transactional
    public TicketDetail perform(Actor actor, long ticketId, TicketAction action, Long assigneeId) {
        if (!action.isManual()) {
            throw new BusinessRuleException("action", action + " is done by the system, not by people.");
        }
        Instant now = clock.instant();
        Ticket ticket = lock(ticketId);
        TicketStatus status = ticket.getStatus();
        if (!status.allows(action)) {
            throw new InvalidTransitionException(status, action);
        }
        if (!TicketPermissions.mayPerform(actor, ticket, action)) {
            throw new ForbiddenException("You can't " + action.name().toLowerCase(Locale.ROOT).replace('_', ' ')
                    + " this ticket.");
        }
        if (action == TicketAction.ASSIGN) {
            assign(actor, ticket, assigneeId == null ? actor.agentId() : assigneeId, now);
        } else {
            transition(ticket, action, ActorRef.agent(actor), now);
        }
        return detail(ticket, actor);
    }

    /**
     * "Give me the next ticket": the most urgent unassigned ticket in the queue, assigned to
     * the caller. Empty if there is none.
     */
    @Transactional
    public Optional<TicketDetail> claimNext(Actor actor, long queueId) {
        TicketQueue queue = queues.get(queueId);
        if (!actor.isSupervisor() && !queue.hasMember(actor.agentId())) {
            throw new ForbiddenException("You're not a member of the " + queue.getName() + " queue.");
        }
        Instant now = clock.instant();
        return tickets.lockNextUnassigned(queueId).map(id -> {
            Ticket ticket = find(id);
            assign(actor, ticket, actor.agentId(), now);
            return detail(ticket, actor);
        });
    }

    @Transactional
    public TicketDetail changePriority(Actor actor, long ticketId, Priority priority) {
        Instant now = clock.instant();
        Ticket ticket = lock(ticketId);
        if (!ticket.getStatus().isActive()) {
            throw new ConflictException("The priority of a resolved or closed ticket can't change.");
        }
        if (!TicketPermissions.mayChangePriority(actor, ticket)) {
            throw new ForbiddenException("Only the assignee or a supervisor can change the priority.");
        }
        Priority from = ticket.getPriority();
        if (from != priority) {
            ticket.changePriority(priority, now);
            sla.onPriorityChanged(ticket, now);
            audit.record(ticketId, ActorRef.agent(actor), "PRIORITY_CHANGED", details("from", from, "to", priority));
            events.publish(new TicketEvent.PriorityChanged(ticketId, ticket.getNumber(), from, priority));
        }
        return detail(ticket, actor);
    }

    /** Closes a resolved ticket nobody replied to. Called by the auto-close job with the ticket locked. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void autoClose(Ticket ticket) {
        transition(ticket, TicketAction.AUTO_CLOSE, ActorRef.SYSTEM, clock.instant());
    }

    /** Loads a ticket and locks it for the rest of the transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Ticket lock(long ticketId) {
        return tickets.findByIdForUpdate(ticketId)
                .orElseThrow(() -> new NotFoundException("Ticket " + Ticket.number(ticketId) + " not found"));
    }

    private void assign(Actor actor, Ticket ticket, long assigneeId, Instant now) {
        if (assigneeId != actor.agentId() && !actor.isSupervisor()) {
            throw new ForbiddenException("Only supervisors can assign tickets to someone else.");
        }
        Agent assignee = agents.findById(assigneeId)
                .orElseThrow(() -> new BusinessRuleException("assigneeId", "No such agent."));
        if (!ticket.getQueue().hasMember(assigneeId) && !assignee.isSupervisor()) {
            throw new BusinessRuleException("assigneeId",
                    assignee.getDisplayName() + " isn't a member of the " + ticket.getQueue().getName() + " queue.");
        }
        Long previous = ticket.getAssigneeId();
        if (previous != null && previous == assigneeId) {
            return; // already theirs
        }
        ticket.assignTo(assignee, now);
        audit.record(ticket.getId(), ActorRef.agent(actor), "ASSIGNED",
                details("assigneeId", assigneeId, "assignee", assignee.getUsername(), "previousAssigneeId", previous));
        events.publish(new TicketEvent.Assigned(ticket.getId(), ticket.getNumber(), assigneeId, previous,
                actor.agentId()));
        transition(ticket, TicketAction.ASSIGN, ActorRef.agent(actor), now);
    }

    /** Applies a status change and everything that comes with it. No-op if the status stays the same. */
    private void transition(Ticket ticket, TicketAction action, ActorRef actor, Instant now) {
        TicketStatus from = ticket.getStatus();
        TicketStatus to = from.after(action);
        if (from == to) {
            return;
        }
        ticket.moveTo(to, now);
        sla.onStatusChanged(ticket, from, to, now);
        audit.record(ticket.getId(), actor, "STATUS_CHANGED",
                details("from", from, "to", to, "action", action, "slaClock", to.slaClock()));
        events.publish(new TicketEvent.StatusChanged(ticket.getId(), ticket.getNumber(), from, to, action,
                ticket.getAssigneeId()));
    }

    // =====================================================================================
    // Mapping to API views
    // =====================================================================================

    private Ticket find(long ticketId) {
        return tickets.findById(ticketId)
                .orElseThrow(() -> new NotFoundException("Ticket " + Ticket.number(ticketId) + " not found"));
    }

    private TicketDetail detail(Ticket ticket, Actor actor) {
        Instant now = clock.instant();
        List<MessageView> conversation = messages.findByTicketIdOrderById(ticket.getId()).stream()
                .map(TicketService::messageView)
                .toList();
        return new TicketDetail(summary(ticket, sla.viewsOf(ticket.getId(), now)), ticket.getFirstRespondedAt(),
                ticket.getResolvedAt(), ticket.getClosedAt(), conversation,
                TicketPermissions.allowedActions(actor, ticket),
                ticket.getStatus() != TicketStatus.CLOSED && TicketPermissions.mayReplyPublicly(actor, ticket),
                ticket.getStatus().isActive() && TicketPermissions.mayChangePriority(actor, ticket));
    }

    private static TicketSummary summary(Ticket ticket, List<SlaTimerView> timers) {
        TicketQueue queue = ticket.getQueue();
        Customer customer = ticket.getCustomer();
        Agent assignee = ticket.getAssignee();
        return new TicketSummary(ticket.getId(), ticket.getNumber(), ticket.getSubject(), ticket.getStatus(),
                ticket.getPriority(), ticket.getChannel(), new QueueRef(queue.getId(), queue.getKey(), queue.getName()),
                new CustomerRef(customer.getId(), customer.getEmail(), customer.getNameOrEmail()),
                assignee == null ? null : new PersonRef(assignee.getId(), assignee.getDisplayName()),
                ticket.getEscalationLevel(), ticket.getCreatedAt(), ticket.getUpdatedAt(), ticket.getVersion(), timers);
    }

    private static MessageView messageView(TicketMessage message) {
        return new MessageView(message.getId(), message.getAuthorType(), message.authorName(), message.getVisibility(),
                message.getBody(), message.getCreatedAt(), message.getEmailMessageId() != null);
    }

    /** Audit details as an ordered map that allows null values (unlike {@code Map.of}). */
    private static Map<String, Object> details(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            Object value = keysAndValues[i + 1];
            map.put((String) keysAndValues[i], value instanceof Enum<?> e ? e.name() : value);
        }
        return map;
    }
}
