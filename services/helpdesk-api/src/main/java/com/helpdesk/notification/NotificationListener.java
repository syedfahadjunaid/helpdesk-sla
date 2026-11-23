package com.helpdesk.notification;

import com.helpdesk.agent.Agent;
import com.helpdesk.agent.AgentRepository;
import com.helpdesk.events.TicketEvent;
import com.helpdesk.live.AgentTopics;
import com.helpdesk.mail.OutboundMailer;
import com.helpdesk.messaging.EventMessages;
import com.helpdesk.messaging.ProcessedMessages;
import com.helpdesk.messaging.RabbitTopology;
import com.helpdesk.notification.NotificationService.Kind;
import com.helpdesk.ticket.AuthorType;
import com.helpdesk.ticket.Ticket;
import com.helpdesk.ticket.TicketRepository;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Decides who hears about what, and tells them: an in-app notification for each person, a
 * live "you have news" ping on their personal WebSocket topic, and, for SLA breaches, an
 * email to the supervisors.
 *
 * <p>Idempotent ({@link ProcessedMessages}): a redelivered event is skipped. If handling
 * fails (say the mail server is down), the transaction rolls back and RabbitMQ redelivers,
 * a few times with backoff and then to the dead-letter queue.
 */
@Component
public class NotificationListener {

    private static final String CONSUMER = "notifications";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm 'UTC'", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    private final EventMessages messages;
    private final ProcessedMessages processed;
    private final NotificationService notifications;
    private final TicketRepository tickets;
    private final AgentRepository agents;
    private final OutboundMailer mailer;
    private final AgentTopics agentTopics;
    private final TransactionTemplate transactions;

    NotificationListener(EventMessages messages, ProcessedMessages processed, NotificationService notifications,
            TicketRepository tickets, AgentRepository agents, OutboundMailer mailer, AgentTopics agentTopics,
            TransactionTemplate transactions) {
        this.messages = messages;
        this.processed = processed;
        this.notifications = notifications;
        this.tickets = tickets;
        this.agents = agents;
        this.mailer = mailer;
        this.agentTopics = agentTopics;
        this.transactions = transactions;
    }

    @RabbitListener(queues = RabbitTopology.NOTIFICATIONS_QUEUE)
    public void onEvent(Message message) {
        EventMessages.Received received = messages.read(message);
        Set<Long> notified = transactions.execute(status -> processed.firstTime(CONSUMER, received.eventId())
                ? handle(received.eventId(), received.event())
                : Set.of());
        // After commit: the browser will refetch, and must find the new rows.
        if (notified != null) {
            notified.forEach(agentTopics::ping);
        }
    }

    /** Creates the notifications for one event; returns the agents who got a new one. */
    private Set<Long> handle(UUID eventId, TicketEvent event) {
        return switch (event) {
            case TicketEvent.Assigned e -> e.assignedById() != null && e.assignedById() == e.assigneeId()
                    ? Set.of() // you assigned it to yourself: no need to tell you
                    : notify(List.of(e.assigneeId()), e, Kind.ASSIGNED,
                            e.ticketNumber() + " was assigned to you: " + subject(e), eventId);
            case TicketEvent.MessageAdded e -> e.authorType() == AuthorType.CUSTOMER && e.assigneeId() != null
                    ? notify(List.of(e.assigneeId()), e, Kind.CUSTOMER_REPLIED,
                            "The customer replied on " + e.ticketNumber() + ": " + subject(e), eventId)
                    : Set.of();
            case TicketEvent.SlaWarning e -> notify(e.assigneeId() != null ? List.of(e.assigneeId()) : queueMembers(e),
                    e, Kind.SLA_WARNING, e.ticketNumber() + " is close to its " + metric(e.metric().name())
                            + " deadline (" + TIME.format(e.dueAt()) + "): " + subject(e), eventId);
            case TicketEvent.SlaBreached e -> breached(e, eventId);
            // Not routed to this queue (see RabbitTopology), and nothing to say if they were.
            case TicketEvent.Created _, TicketEvent.StatusChanged _, TicketEvent.PriorityChanged _ -> Set.of();
        };
    }

    /** Breach: tell the assignee and every supervisor, in the app and (supervisors) by email. */
    private Set<Long> breached(TicketEvent.SlaBreached e, UUID eventId) {
        List<Agent> supervisors = agents.findBySupervisorTrue();
        Set<Long> recipients = new LinkedHashSet<>();
        if (e.assigneeId() != null) {
            recipients.add(e.assigneeId());
        }
        supervisors.forEach(supervisor -> recipients.add(supervisor.getId()));
        Set<Long> notified = notify(List.copyOf(recipients), e, Kind.SLA_BREACHED,
                e.ticketNumber() + " breached its " + metric(e.metric().name()) + " target (escalation level "
                        + e.escalationLevel() + "): " + subject(e), eventId);
        mailer.sendBreachAlert(ticket(e), e.metric().name(), e.escalationLevel(), supervisors, eventId);
        return notified;
    }

    private Set<Long> notify(List<Long> agentIds, TicketEvent event, Kind kind, String text, UUID eventId) {
        Set<Long> created = new LinkedHashSet<>();
        for (Long agentId : agentIds) {
            if (notifications.create(agentId, event.ticketId(), kind, text, eventId)) {
                created.add(agentId);
            }
        }
        return created;
    }

    private List<Long> queueMembers(TicketEvent.SlaWarning e) {
        return ticket(e).getQueue().getMembers().stream().map(Agent::getId).toList();
    }

    private String subject(TicketEvent event) {
        return ticket(event).getSubject();
    }

    private Ticket ticket(TicketEvent event) {
        return tickets.findById(event.ticketId()).orElseThrow();
    }

    private static String metric(String metric) {
        return metric.toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
