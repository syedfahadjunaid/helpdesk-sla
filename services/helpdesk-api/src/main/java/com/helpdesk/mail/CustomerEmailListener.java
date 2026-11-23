package com.helpdesk.mail;

import com.helpdesk.events.TicketEvent;
import com.helpdesk.messaging.EventMessages;
import com.helpdesk.messaging.ProcessedMessages;
import com.helpdesk.messaging.RabbitTopology;
import com.helpdesk.ticket.AuthorType;
import com.helpdesk.ticket.Channel;
import com.helpdesk.ticket.Ticket;
import com.helpdesk.ticket.TicketMessage;
import com.helpdesk.ticket.TicketMessageRepository;
import com.helpdesk.ticket.TicketRepository;
import com.helpdesk.ticket.Visibility;
import java.util.UUID;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Emails customers: an acknowledgement for each new email ticket, and every public agent
 * reply. Driven by domain events, so the agent's HTTP request never waits for (or fails
 * because of) the mail server.
 */
@Component
public class CustomerEmailListener {

    private static final String CONSUMER = "customer-email";

    private final EventMessages messages;
    private final ProcessedMessages processed;
    private final TicketRepository tickets;
    private final TicketMessageRepository ticketMessages;
    private final OutboundMailer mailer;
    private final TransactionTemplate transactions;

    CustomerEmailListener(EventMessages messages, ProcessedMessages processed, TicketRepository tickets,
            TicketMessageRepository ticketMessages, OutboundMailer mailer, TransactionTemplate transactions) {
        this.messages = messages;
        this.processed = processed;
        this.tickets = tickets;
        this.ticketMessages = ticketMessages;
        this.mailer = mailer;
        this.transactions = transactions;
    }

    @RabbitListener(queues = RabbitTopology.CUSTOMER_EMAIL_QUEUE)
    public void onEvent(Message message) {
        EventMessages.Received received = messages.read(message);
        transactions.executeWithoutResult(status -> {
            if (processed.firstTime(CONSUMER, received.eventId())) {
                handle(received.eventId(), received.event());
            }
        });
    }

    private void handle(UUID eventId, TicketEvent event) {
        switch (event) {
            case TicketEvent.Created e -> {
                if (e.channel() == Channel.EMAIL) {
                    mailer.sendAcknowledgement(ticket(e), eventId);
                }
            }
            case TicketEvent.MessageAdded e -> {
                if (e.authorType() == AuthorType.AGENT && e.visibility() == Visibility.PUBLIC) {
                    TicketMessage reply = ticketMessages.findById(e.messageId()).orElseThrow();
                    mailer.sendAgentReply(ticket(e), reply, eventId);
                }
            }
            case TicketEvent.Assigned _, TicketEvent.StatusChanged _, TicketEvent.PriorityChanged _,
                    TicketEvent.SlaWarning _, TicketEvent.SlaBreached _ -> {
                // not for customers
            }
        }
    }

    private Ticket ticket(TicketEvent event) {
        return tickets.findById(event.ticketId()).orElseThrow();
    }
}
