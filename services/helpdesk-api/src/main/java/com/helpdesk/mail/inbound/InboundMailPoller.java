package com.helpdesk.mail.inbound;

import com.helpdesk.config.HelpdeskProperties;
import com.helpdesk.mail.inbound.InboundEmailService.IngestResult;
import com.helpdesk.queue.TicketQueue;
import com.helpdesk.queue.TicketQueueRepository;
import com.helpdesk.ticket.Ticket;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Checks each queue's support address for new email every few seconds.
 *
 * <p>Every instance polls. Two instances may fetch the same email at the same moment; the
 * Message-ID check in {@link InboundEmailService} makes sure only one ticket results. If we
 * crash after creating the ticket but before tagging the email in the mailbox, the next poll
 * fetches it again and the same check skips it.
 */
@Component
public class InboundMailPoller {

    private static final Logger log = LoggerFactory.getLogger(InboundMailPoller.class);
    private static final int BATCH = 25;

    private final MailpitMailbox mailbox;
    private final InboundEmailService intake;
    private final TicketQueueRepository queues;
    private final HelpdeskProperties.Mail.Inbound settings;

    InboundMailPoller(MailpitMailbox mailbox, InboundEmailService intake, TicketQueueRepository queues,
            HelpdeskProperties props) {
        this.mailbox = mailbox;
        this.intake = intake;
        this.queues = queues;
        this.settings = props.mail().inbound();
    }

    @Scheduled(fixedDelayString = "${helpdesk.mail.inbound.poll-interval}", initialDelay = 5_000)
    public void poll() {
        if (!settings.enabled()) {
            return;
        }
        try {
            pollOnce();
        } catch (RuntimeException e) {
            log.warn("Checking the support mailboxes failed; will retry: {}", e.getMessage());
        }
    }

    /** One pass over every support address. Returns how many emails were handled. Public for tests. */
    public int pollOnce() {
        int handled = 0;
        for (TicketQueue queue : queues.findByInboundAddressNotNull()) {
            for (MailpitMailbox.Summary summary : mailbox.unprocessed(queue.getInboundAddress(), BATCH)) {
                handled++;
                if (summary.size() > settings.maxSizeBytes()) {
                    log.warn("Skipping email {} ({} bytes): too large", summary.id(), summary.size());
                    mailbox.markProcessed(summary.id(), List.of("helpdesk-too-large"));
                    continue;
                }
                IngestResult result;
                try {
                    result = intake.ingest(mailbox.raw(summary.id()), queue.getInboundAddress(), summary.id());
                } catch (IllegalArgumentException e) {
                    log.warn("Skipping unreadable email {}: {}", summary.id(), e.getMessage());
                    mailbox.markProcessed(summary.id(), List.of("helpdesk-unreadable"));
                    continue;
                }
                mailbox.markProcessed(summary.id(), tagsFor(result));
            }
        }
        return handled;
    }

    private static List<String> tagsFor(IngestResult result) {
        return switch (result.outcome()) {
            case CREATED_TICKET, APPENDED -> List.of(Ticket.number(result.ticketId()));
            case IGNORED -> List.of("helpdesk-ignored");
            case DUPLICATE -> List.of();
        };
    }
}
