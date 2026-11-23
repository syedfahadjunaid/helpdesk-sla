package com.helpdesk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Helpdesk API: tickets, queues, SLA clocks, escalation, email intake and live updates.
 *
 * <p>Every instance runs the same code, including the background jobs (SLA worker, outbox
 * relay, mailbox poller). They coordinate through the database ({@code FOR UPDATE SKIP
 * LOCKED} and unique constraints), not through leader election, so running two or ten
 * instances is safe.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class HelpdeskApplication {

    public static void main(String[] args) {
        SpringApplication.run(HelpdeskApplication.class, args);
    }
}
