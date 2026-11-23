package com.helpdesk.sla;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Numbers for the SLA dashboard. Read-only aggregate SQL (no entities), computed live:
 * at this scale that's simpler and always correct. A busy helpdesk would precompute them,
 * for example from the same domain events that drive notifications.
 */
@RestController
@RequestMapping("/api/dashboard")
public class SlaDashboardController {

    private static final Duration WINDOW = Duration.ofDays(7);

    public record QueueLoad(long queueId, String queueName, long active, long unassigned, long waitingOnCustomer,
            long atRisk, long escalated) {}

    /** How each SLA metric did over the window: targets met on time vs breached. */
    public record Compliance(String metric, long met, long breached, Double compliancePercent,
            Long averageBusinessSeconds) {}

    public record DailyBreaches(LocalDate day, long breaches) {}

    /** A running clock close to its deadline. */
    public record DueSoon(long ticketId, String ticketNumber, String subject, String priority, String metric,
            Instant dueAt, String assignee) {}

    public record Dashboard(Instant generatedAt, int windowDays, List<QueueLoad> queues, List<Compliance> compliance,
            List<DailyBreaches> breachesByDay, List<DueSoon> dueSoon) {}

    private final JdbcClient jdbc;
    private final Clock clock;

    SlaDashboardController(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @GetMapping("/sla")
    @Transactional(readOnly = true)
    public Dashboard sla() {
        Instant now = clock.instant();
        Timestamp since = Timestamp.from(now.minus(WINDOW));
        return new Dashboard(now, (int) WINDOW.toDays(), queueLoad(), compliance(since), breachesByDay(since), dueSoon());
    }

    private List<QueueLoad> queueLoad() {
        return jdbc.sql("""
                        SELECT q.id, q.name,
                               count(t.id) FILTER (WHERE t.status IN ('NEW', 'OPEN', 'WAITING_ON_CUSTOMER')) AS active,
                               count(t.id) FILTER (WHERE t.status = 'NEW') AS unassigned,
                               count(t.id) FILTER (WHERE t.status = 'WAITING_ON_CUSTOMER') AS waiting,
                               count(t.id) FILTER (WHERE t.status IN ('NEW', 'OPEN', 'WAITING_ON_CUSTOMER')
                                   AND EXISTS (SELECT 1 FROM sla_timers s
                                               WHERE s.ticket_id = t.id AND s.warned_at IS NOT NULL
                                                 AND s.breached_at IS NULL AND s.met_at IS NULL)) AS at_risk,
                               count(t.id) FILTER (WHERE t.status IN ('NEW', 'OPEN', 'WAITING_ON_CUSTOMER')
                                   AND t.escalation_level > 0) AS escalated
                        FROM queues q LEFT JOIN tickets t ON t.queue_id = q.id
                        GROUP BY q.id, q.name
                        ORDER BY q.id""")
                .query((rs, row) -> new QueueLoad(rs.getLong("id"), rs.getString("name"), rs.getLong("active"),
                        rs.getLong("unassigned"), rs.getLong("waiting"), rs.getLong("at_risk"), rs.getLong("escalated")))
                .list();
    }

    private List<Compliance> compliance(Timestamp since) {
        return jdbc.sql("""
                        SELECT metric,
                               count(*) FILTER (WHERE met_at IS NOT NULL AND breached_at IS NULL) AS met,
                               count(*) FILTER (WHERE breached_at IS NOT NULL) AS breached,
                               round(avg(consumed_seconds) FILTER (WHERE met_at IS NOT NULL)) AS avg_seconds
                        FROM sla_timers
                        WHERE met_at >= :since OR breached_at >= :since
                        GROUP BY metric
                        ORDER BY metric""")
                .param("since", since)
                .query((rs, row) -> {
                    long met = rs.getLong("met");
                    long breached = rs.getLong("breached");
                    Double percent = met + breached == 0 ? null : Math.round(1000.0 * met / (met + breached)) / 10.0;
                    long avg = rs.getLong("avg_seconds");
                    return new Compliance(rs.getString("metric"), met, breached, percent, rs.wasNull() ? null : avg);
                })
                .list();
    }

    private List<DailyBreaches> breachesByDay(Timestamp since) {
        return jdbc.sql("""
                        SELECT (breached_at AT TIME ZONE 'UTC')::date AS day, count(*) AS breaches
                        FROM sla_timers WHERE breached_at >= :since
                        GROUP BY 1 ORDER BY 1""")
                .param("since", since)
                .query((rs, row) -> new DailyBreaches(rs.getObject("day", LocalDate.class), rs.getLong("breaches")))
                .list();
    }

    private List<DueSoon> dueSoon() {
        return jdbc.sql("""
                        SELECT t.id, t.subject, t.priority, s.metric, s.due_at, a.display_name
                        FROM sla_timers s
                        JOIN tickets t ON t.id = s.ticket_id
                        LEFT JOIN agents a ON a.id = t.assignee_id
                        WHERE s.clock_state = 'RUNNING' AND s.breached_at IS NULL
                        ORDER BY s.due_at
                        LIMIT 10""")
                .query((rs, row) -> new DueSoon(rs.getLong("id"), "HD-" + rs.getLong("id"), rs.getString("subject"),
                        rs.getString("priority"), rs.getString("metric"), rs.getTimestamp("due_at").toInstant(),
                        rs.getString("display_name")))
                .list();
    }
}
