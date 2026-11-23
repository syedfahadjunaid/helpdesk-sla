package com.helpdesk.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on the {@code @Scheduled} background jobs (SLA worker, outbox relay, mailbox poller,
 * auto-close). Integration tests set {@code helpdesk.scheduling.enabled=false} and call the
 * jobs directly, so nothing runs at a moment the test didn't choose.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "helpdesk.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {}
