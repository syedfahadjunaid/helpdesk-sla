package com.helpdesk.ticket;

import com.helpdesk.agent.Agent;
import com.helpdesk.customer.Customer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

/**
 * One message in a ticket's conversation. Append-only: never edited or deleted (the
 * database enforces it with a trigger and by withholding UPDATE/DELETE from the app login).
 */
@Entity
@Immutable
@Table(name = "ticket_messages")
public class TicketMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ticket_id", nullable = false)
    private long ticketId;

    @Enumerated(EnumType.STRING)
    @Column(name = "author_type", nullable = false)
    private AuthorType authorType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_agent_id")
    private Agent authorAgent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_customer_id")
    private Customer authorCustomer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Visibility visibility;

    @Column(nullable = false)
    private String body;

    /** The email's Message-ID, for messages that arrived by email. */
    @Column(name = "email_message_id")
    private String emailMessageId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TicketMessage() {} // for JPA

    static TicketMessage byAgent(long ticketId, Agent agent, Visibility visibility, String body, Instant now) {
        TicketMessage message = new TicketMessage();
        message.ticketId = ticketId;
        message.authorType = AuthorType.AGENT;
        message.authorAgent = agent;
        message.visibility = visibility;
        message.body = body;
        message.createdAt = now;
        return message;
    }

    static TicketMessage byCustomer(long ticketId, Customer customer, String body, String emailMessageId, Instant now) {
        TicketMessage message = new TicketMessage();
        message.ticketId = ticketId;
        message.authorType = AuthorType.CUSTOMER;
        message.authorCustomer = customer;
        message.visibility = Visibility.PUBLIC;
        message.body = body;
        message.emailMessageId = emailMessageId;
        message.createdAt = now;
        return message;
    }

    /** "Ann Agent" or "jane@example.com". */
    public String authorName() {
        return switch (authorType) {
            case AGENT -> authorAgent.getDisplayName();
            case CUSTOMER -> authorCustomer.getNameOrEmail();
        };
    }

    public Long getId() { return id; }
    public long getTicketId() { return ticketId; }
    public AuthorType getAuthorType() { return authorType; }
    public Agent getAuthorAgent() { return authorAgent; }
    public Customer getAuthorCustomer() { return authorCustomer; }
    public Visibility getVisibility() { return visibility; }
    public String getBody() { return body; }
    public String getEmailMessageId() { return emailMessageId; }
    public Instant getCreatedAt() { return createdAt; }
}
