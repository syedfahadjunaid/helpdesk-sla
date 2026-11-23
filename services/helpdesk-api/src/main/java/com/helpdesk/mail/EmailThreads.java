package com.helpdesk.mail;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Which ticket an email belongs to, by Message-ID. Every email we receive or send for a
 * ticket is recorded, so when a customer replies, the {@code In-Reply-To} and
 * {@code References} headers of their reply lead back to the ticket.
 */
@Component
public class EmailThreads {

    private final JdbcClient jdbc;

    EmailThreads(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void remember(String messageId, long ticketId) {
        jdbc.sql("INSERT INTO email_threads (message_id, ticket_id) VALUES (:id, :ticket) ON CONFLICT DO NOTHING")
                .param("id", messageId)
                .param("ticket", ticketId)
                .update();
    }

    /** The ticket of the most recent known message among these ids (a reply's References, newest last). */
    public Optional<Long> findTicket(Collection<String> messageIds) {
        if (messageIds.isEmpty()) {
            return Optional.empty();
        }
        List<Long> found = jdbc.sql("SELECT DISTINCT ticket_id FROM email_threads WHERE message_id IN (:ids)")
                .param("ids", messageIds)
                .query(Long.class)
                .list();
        // Normally one ticket. If a thread somehow spans several, the newest ticket wins.
        return found.stream().max(Long::compare);
    }
}
