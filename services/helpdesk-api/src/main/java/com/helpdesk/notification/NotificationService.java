package com.helpdesk.notification;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * In-app notifications ("HD-1004 was assigned to you", "SLA breached on HD-1007").
 * Plain SQL: a notification is a simple row, and the unique key on
 * {@code (agent_id, source_event_id)} with {@code ON CONFLICT DO NOTHING} makes creating one
 * idempotent: the same event delivered twice still notifies each person once.
 */
@Service
public class NotificationService {

    public enum Kind { ASSIGNED, CUSTOMER_REPLIED, SLA_WARNING, SLA_BREACHED }

    public record NotificationView(long id, Long ticketId, String kind, String message, Instant createdAt,
            Instant readAt) {}

    public record NotificationList(long unread, List<NotificationView> items) {}

    private final JdbcClient jdbc;
    private final Clock clock;

    NotificationService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Returns true if it was created (false: this person already has it for this event). */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean create(long agentId, Long ticketId, Kind kind, String message, UUID sourceEventId) {
        return jdbc.sql("""
                        INSERT INTO notifications (agent_id, ticket_id, kind, message, source_event_id, created_at)
                        VALUES (:agent, :ticket, :kind, :message, :event, :now)
                        ON CONFLICT (agent_id, source_event_id) DO NOTHING""")
                .param("agent", agentId)
                .param("ticket", ticketId)
                .param("kind", kind.name())
                .param("message", message)
                .param("event", sourceEventId)
                .param("now", Timestamp.from(clock.instant()))
                .update() == 1;
    }

    @Transactional(readOnly = true)
    public NotificationList latest(long agentId, int limit) {
        long unread = jdbc.sql("SELECT count(*) FROM notifications WHERE agent_id = :agent AND read_at IS NULL")
                .param("agent", agentId)
                .query(Long.class)
                .single();
        List<NotificationView> items = jdbc.sql("""
                        SELECT id, ticket_id, kind, message, created_at, read_at FROM notifications
                        WHERE agent_id = :agent ORDER BY id DESC LIMIT :limit""")
                .param("agent", agentId)
                .param("limit", limit)
                .query((rs, row) -> new NotificationView(rs.getLong("id"), (Long) rs.getObject("ticket_id"),
                        rs.getString("kind"), rs.getString("message"), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("read_at") == null ? null : rs.getTimestamp("read_at").toInstant()))
                .list();
        return new NotificationList(unread, items);
    }

    /** Marks one (or, with {@code notificationId} null, all) of the agent's notifications read. */
    @Transactional
    public int markRead(long agentId, Long notificationId) {
        return jdbc.sql("""
                        UPDATE notifications SET read_at = :now
                        WHERE agent_id = :agent AND read_at IS NULL AND (CAST(:id AS bigint) IS NULL OR id = :id)""")
                .param("now", Timestamp.from(clock.instant()))
                .param("agent", agentId)
                .param("id", notificationId)
                .update();
    }
}
