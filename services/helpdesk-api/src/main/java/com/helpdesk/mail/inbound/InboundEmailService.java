package com.helpdesk.mail.inbound;

import com.helpdesk.config.HelpdeskProperties;
import com.helpdesk.customer.Customer;
import com.helpdesk.customer.CustomerService;
import com.helpdesk.mail.EmailThreads;
import com.helpdesk.queue.TicketQueue;
import com.helpdesk.queue.TicketQueueRepository;
import com.helpdesk.ticket.Ticket;
import com.helpdesk.ticket.TicketRepository;
import com.helpdesk.ticket.TicketService;
import com.helpdesk.ticket.TicketStatus;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Email to ticket: decides what an incoming email is and records it, in one transaction.
 *
 * <ol>
 *   <li><b>Deduplicate</b> on Message-ID: the first statement inserts the email's id into
 *       {@code inbound_emails} with {@code ON CONFLICT DO NOTHING}. If the same email is
 *       fetched again (a retry, or two instances polling at once), nothing happens.</li>
 *   <li><b>Ignore</b> automatic mail and our own mail, so auto-replies can't start a loop.</li>
 *   <li><b>Thread</b>: a reply (In-Reply-To/References pointing at a known email, or
 *       "[HD-1001]" in the subject) from the ticket's own customer is added to that ticket.
 *       Someone else replying to the thread gets a new ticket instead: knowing a Message-ID
 *       mustn't let a stranger write into another customer's conversation.</li>
 *   <li>Otherwise <b>create</b> a ticket in the queue whose address the email was sent to.</li>
 * </ol>
 */
@Service
public class InboundEmailService {

    private static final Logger log = LoggerFactory.getLogger(InboundEmailService.class);
    private static final Pattern TICKET_TOKEN = Pattern.compile("\\[HD-(\\d{1,15})]");
    private static final Pattern REPLY_PREFIX = Pattern.compile("^(?i)((re|fw|fwd|aw|sv)\\s*:\\s*)+");
    private static final int MAX_SUBJECT = 300;
    private static final Set<String> AUTOMATIC_PRECEDENCE = Set.of("bulk", "junk", "list", "auto_reply");

    public enum Outcome { CREATED_TICKET, APPENDED, IGNORED, DUPLICATE }

    public record IngestResult(Outcome outcome, String messageId, Long ticketId, String reason) {}

    private final JdbcClient jdbc;
    private final CustomerService customers;
    private final TicketQueueRepository queues;
    private final TicketRepository tickets;
    private final TicketService ticketService;
    private final EmailThreads threads;
    private final Clock clock;
    private final String domain;

    InboundEmailService(JdbcClient jdbc, CustomerService customers, TicketQueueRepository queues,
            TicketRepository tickets, TicketService ticketService, EmailThreads threads, Clock clock,
            HelpdeskProperties props) {
        this.jdbc = jdbc;
        this.customers = customers;
        this.queues = queues;
        this.tickets = tickets;
        this.ticketService = ticketService;
        this.threads = threads;
        this.clock = clock;
        this.domain = props.mail().domain();
    }

    /**
     * @param mailboxAddress the support address the email was fetched from (decides the queue)
     * @param mailboxRef the mail server's own id for the email, kept for tracing
     */
    @Transactional
    public IngestResult ingest(byte[] raw, String mailboxAddress, String mailboxRef) {
        ParsedEmail email = EmailParser.parse(raw);
        String messageId = email.messageId() != null ? email.messageId() : syntheticMessageId(raw);

        if (!claim(messageId, email, mailboxRef)) {
            log.debug("Email {} was already processed", messageId);
            return new IngestResult(Outcome.DUPLICATE, messageId, null, "already processed");
        }

        String ignored = ignoreReason(email);
        if (ignored != null) {
            finish(messageId, Outcome.IGNORED, ignored, null);
            log.info("Ignored email {} from {}: {}", messageId, email.fromAddress(), ignored);
            return new IngestResult(Outcome.IGNORED, messageId, null, ignored);
        }

        Customer customer = customers.findOrCreate(email.fromAddress(), email.fromName());
        Optional<Ticket> thread = findThread(email, customer);
        if (thread.isPresent() && thread.get().getStatus() != TicketStatus.CLOSED) {
            Ticket ticket = ticketService.lock(thread.get().getId());
            ticketService.addCustomerReply(ticket, customer, email.body(), messageId);
            threads.remember(messageId, ticket.getId());
            finish(messageId, Outcome.APPENDED, null, ticket.getId());
            return new IngestResult(Outcome.APPENDED, messageId, ticket.getId(), null);
        }

        TicketQueue queue = queues.findByInboundAddress(mailboxAddress.toLowerCase(Locale.ROOT))
                .or(queues::findByDefaultQueueTrue)
                .orElseThrow(() -> new IllegalStateException("No queue for " + mailboxAddress + " and no default queue"));
        String body = thread.isPresent()
                ? "(Follow-up to " + thread.get().getNumber() + ", which is closed.)\n\n" + email.body()
                : email.body();
        Ticket ticket = ticketService.createFromEmail(customer, queue, cleanSubject(email.subject()), body, messageId);
        threads.remember(messageId, ticket.getId());
        finish(messageId, Outcome.CREATED_TICKET, null, ticket.getId());
        log.info("Email {} from {} became {}", messageId, email.fromAddress(), ticket.getNumber());
        return new IngestResult(Outcome.CREATED_TICKET, messageId, ticket.getId(), null);
    }

    /**
     * Records the email as "being processed". Returns false if it was seen before. With
     * {@code ON CONFLICT DO NOTHING}, a second transaction inserting the same id waits for
     * the first to commit, then inserts nothing: exactly one of them wins.
     */
    private boolean claim(String messageId, ParsedEmail email, String mailboxRef) {
        return jdbc.sql("""
                        INSERT INTO inbound_emails (message_id, mailbox_ref, from_address, subject, received_at, outcome)
                        VALUES (:messageId, :ref, :from, :subject, :now, 'PROCESSING')
                        ON CONFLICT (message_id) DO NOTHING""")
                .param("messageId", messageId)
                .param("ref", mailboxRef)
                .param("from", email.fromAddress() == null ? "(none)" : email.fromAddress())
                .param("subject", truncate(email.subject()))
                .param("now", Timestamp.from(clock.instant()))
                .update() == 1;
    }

    private void finish(String messageId, Outcome outcome, String reason, Long ticketId) {
        jdbc.sql("UPDATE inbound_emails SET outcome = :outcome, outcome_reason = :reason, ticket_id = :ticket "
                        + "WHERE message_id = :messageId")
                .param("outcome", outcome.name())
                .param("reason", reason)
                .param("ticket", ticketId)
                .param("messageId", messageId)
                .update();
    }

    /** Why this email must not become (part of) a ticket, or null if it's fine. */
    private String ignoreReason(ParsedEmail email) {
        if (email.fromAddress() == null) {
            return "no sender address";
        }
        if (email.helpdeskOrigin() != null) {
            return "sent by this helpdesk (mail loop)";
        }
        if (email.autoSubmitted() != null && !"no".equalsIgnoreCase(email.autoSubmitted().strip())) {
            return "automatic email (Auto-Submitted: " + email.autoSubmitted().strip() + ")";
        }
        if (email.precedence() != null && AUTOMATIC_PRECEDENCE.contains(email.precedence().strip().toLowerCase(Locale.ROOT))) {
            return "bulk or automatic email (Precedence: " + email.precedence().strip() + ")";
        }
        if (ownAddresses().contains(email.fromAddress())) {
            return "sent from one of our own addresses";
        }
        return null;
    }

    private Set<String> ownAddresses() {
        Set<String> own = queues.findByInboundAddressNotNull().stream()
                .map(TicketQueue::getInboundAddress)
                .collect(Collectors.toCollection(HashSet::new));
        own.add("noreply@" + domain);
        return own;
    }

    /** The ticket this email replies to, if it's from that ticket's customer. */
    private Optional<Ticket> findThread(ParsedEmail email, Customer sender) {
        Optional<Long> ticketId = threads.findTicket(email.threadIds()).or(() -> ticketNumberIn(email.subject()));
        return ticketId.flatMap(tickets::findById)
                .filter(ticket -> ticket.getCustomer().getId().equals(sender.getId()));
    }

    private static Optional<Long> ticketNumberIn(String subject) {
        if (subject == null) {
            return Optional.empty();
        }
        Matcher matcher = TICKET_TOKEN.matcher(subject);
        return matcher.find() ? Optional.of(Long.parseLong(matcher.group(1))) : Optional.empty();
    }

    /** "Re: [HD-1001] Printer on fire" -> "Printer on fire". */
    static String cleanSubject(String subject) {
        if (subject == null || subject.isBlank()) {
            return "(no subject)";
        }
        String cleaned = TICKET_TOKEN.matcher(REPLY_PREFIX.matcher(subject.strip()).replaceFirst("")).replaceAll("");
        cleaned = REPLY_PREFIX.matcher(cleaned.strip()).replaceFirst("").strip();
        return cleaned.isEmpty() ? "(no subject)" : truncate(cleaned);
    }

    private static String truncate(String text) {
        return text == null || text.length() <= MAX_SUBJECT ? text : text.substring(0, MAX_SUBJECT);
    }

    /** For the rare email without a Message-ID: a stable id from its content, so dedup still works. */
    private String syntheticMessageId(byte[] raw) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(raw);
            return "<sha256." + HexFormat.of().formatHex(hash) + "@" + domain + ">";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
