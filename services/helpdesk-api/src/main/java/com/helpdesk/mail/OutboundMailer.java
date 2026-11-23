package com.helpdesk.mail;

import com.helpdesk.agent.Agent;
import com.helpdesk.config.HelpdeskProperties;
import com.helpdesk.queue.TicketQueue;
import com.helpdesk.ticket.Ticket;
import com.helpdesk.ticket.TicketMessage;
import com.helpdesk.ticket.TicketMessageRepository;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Locale;
import java.util.UUID;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Writes and sends the helpdesk's emails: acknowledgements and agent replies to customers,
 * and breach alerts to supervisors.
 *
 * <p>Called from message consumers, which run at least once. So each email gets a
 * Message-ID derived from the event that caused it: if the same event is handled twice, the
 * duplicate has the same Message-ID, and mail clients thread (or drop) it as the same email.
 */
@Component
public class OutboundMailer {

    /** Customers type above this line; the intake throws away everything from it down (the quoted history). */
    public static final String REPLY_ABOVE_MARKER = "##- Please type your reply above this line -##";

    /** Marks mail we sent, so it's ignored if it ever comes back into a support inbox (no mail loops). */
    public static final String ORIGIN_HEADER = "X-Helpdesk-Origin";
    public static final String TICKET_HEADER = "X-Helpdesk-Ticket";

    private final JavaMailSender mailSender;
    private final TicketMessageRepository messages;
    private final EmailThreads threads;
    private final String domain;

    OutboundMailer(JavaMailSender mailSender, TicketMessageRepository messages, EmailThreads threads,
            HelpdeskProperties props) {
        this.mailSender = mailSender;
        this.messages = messages;
        this.threads = threads;
        this.domain = props.mail().domain();
    }

    /** "We've got your email": sent once per new email ticket. An auto-reply, so marked as one. */
    public void sendAcknowledgement(Ticket ticket, UUID eventId) {
        String body = REPLY_ABOVE_MARKER + "\n\n"
                + "Hello " + ticket.getCustomer().getNameOrEmail() + ",\n\n"
                + "Thanks for contacting us. Your request has been received as ticket " + ticket.getNumber() + ".\n"
                + "We'll get back to you soon. To add anything, simply reply to this email.\n\n"
                + "-- " + ticket.getQueue().getName() + "\n";
        send(ticket, "[" + ticket.getNumber() + "] We received your request: " + ticket.getSubject(), body, eventId, true);
    }

    /** An agent's public reply, emailed to the customer as part of the same thread. */
    public void sendAgentReply(Ticket ticket, TicketMessage reply, UUID eventId) {
        String body = REPLY_ABOVE_MARKER + "\n\n"
                + reply.getBody().strip() + "\n\n"
                + "-- " + reply.authorName() + ", " + ticket.getQueue().getName() + "\n"
                + "Ticket " + ticket.getNumber() + ". Reply to this email to continue the conversation.\n";
        send(ticket, "[" + ticket.getNumber() + "] Re: " + ticket.getSubject(), body, eventId, false);
    }

    /** Tells supervisors that a ticket missed an SLA target. */
    public void sendBreachAlert(Ticket ticket, String metric, int escalationLevel, Collection<Agent> supervisors,
            UUID eventId) {
        String[] to = supervisors.stream().map(Agent::getEmail).filter(email -> email != null && !email.isBlank())
                .toArray(String[]::new);
        if (to.length == 0) {
            return;
        }
        String what = metric.toLowerCase(Locale.ROOT).replace('_', ' ');
        String assignee = ticket.getAssignee() == null ? "nobody (unassigned)" : ticket.getAssignee().getDisplayName();
        String body = "Ticket " + ticket.getNumber() + " missed its " + what + " target and is now at escalation level "
                + escalationLevel + ".\n\n"
                + "Subject:  " + ticket.getSubject() + "\n"
                + "Queue:    " + ticket.getQueue().getName() + "\n"
                + "Priority: " + ticket.getPriority() + "\n"
                + "Assignee: " + assignee + "\n";
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, false, StandardCharsets.UTF_8.name());
            helper.setFrom(new InternetAddress(noReplyAddress(), "Helpdesk SLA monitor", StandardCharsets.UTF_8.name()));
            helper.setTo(to);
            helper.setSubject("[" + ticket.getNumber() + "] SLA breached: " + what);
            helper.setText(body, false);
            mime.setHeader("Message-ID", messageId(ticket, eventId));
            mime.setHeader("Auto-Submitted", "auto-generated"); // RFC 3834: machine-made, don't auto-reply
            mime.setHeader(ORIGIN_HEADER, "sla-alert");
            mime.setHeader(TICKET_HEADER, ticket.getNumber());
            mailSender.send(mime);
        } catch (MessagingException | UnsupportedEncodingException e) {
            throw new MailSendException("Could not build the breach alert for " + ticket.getNumber(), e);
        }
    }

    private void send(Ticket ticket, String subject, String body, UUID eventId, boolean autoReply) {
        TicketQueue queue = ticket.getQueue();
        String messageId = messageId(ticket, eventId);
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, false, StandardCharsets.UTF_8.name());
            String from = queue.getInboundAddress() != null ? queue.getInboundAddress() : noReplyAddress();
            helper.setFrom(new InternetAddress(from, queue.getName(), StandardCharsets.UTF_8.name()));
            helper.setTo(ticket.getCustomer().getEmail());
            helper.setSubject(subject);
            helper.setText(body, false);
            mime.setHeader("Message-ID", messageId); // JavaMailSenderImpl keeps an explicit Message-ID
            // Thread under the customer's latest email, so it shows as a conversation in their mail client.
            messages.findFirstByTicketIdAndEmailMessageIdNotNullOrderByIdDesc(ticket.getId())
                    .map(TicketMessage::getEmailMessageId)
                    .ifPresent(parent -> {
                        try {
                            mime.setHeader("In-Reply-To", parent);
                            mime.setHeader("References", parent);
                        } catch (MessagingException e) {
                            throw new MailSendException("Bad header", e);
                        }
                    });
            if (autoReply) {
                mime.setHeader("Auto-Submitted", "auto-replied");  // RFC 3834
                mime.setHeader("X-Auto-Response-Suppress", "All"); // asks Outlook/Exchange not to auto-answer
            }
            mime.setHeader(ORIGIN_HEADER, "helpdesk");
            mime.setHeader(TICKET_HEADER, ticket.getNumber());
            mailSender.send(mime);
        } catch (MessagingException | UnsupportedEncodingException e) {
            throw new MailSendException("Could not build the email for " + ticket.getNumber(), e);
        }
        threads.remember(messageId, ticket.getId());
    }

    private String messageId(Ticket ticket, UUID eventId) {
        return "<" + ticket.getNumber().toLowerCase(Locale.ROOT) + "." + eventId + "@" + domain + ">";
    }

    private String noReplyAddress() {
        return "noreply@" + domain;
    }
}
