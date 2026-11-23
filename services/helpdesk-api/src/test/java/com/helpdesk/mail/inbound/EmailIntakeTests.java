package com.helpdesk.mail.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.helpdesk.IntegrationTest;
import com.helpdesk.MailpitTestClient;
import com.helpdesk.MutableClock;
import com.helpdesk.TestTokens;
import com.helpdesk.TestUsers;
import com.helpdesk.mail.inbound.InboundEmailService.IngestResult;
import com.helpdesk.mail.inbound.InboundEmailService.Outcome;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import tools.jackson.databind.JsonNode;

/** Email to ticket, against a real SMTP server and mailbox (Mailpit). */
class EmailIntakeTests extends IntegrationTest {

    @Autowired
    InboundMailPoller poller;

    @Autowired
    InboundEmailService intake;

    @Autowired
    JavaMailSender smtp;

    @Value("${helpdesk.mail.inbound.mailpit-url}")
    String mailpitUrl;

    private TestUsers.User ann;
    private long queue;
    private String supportAddress;
    private String customer;

    @BeforeEach
    void setUp() throws Exception {
        clock.set(MutableClock.MONDAY_10AM_LONDON);
        ann = TestUsers.agent("ann");
        queue = newQueue(ann);
        supportAddress = inboundAddressOf(queue);
        customer = "jane-" + TestTokens.randomSuffix() + "@example.test";
    }

    @Test
    void anEmailToTheSupportAddressBecomesATicketAndIsAcknowledged() throws Exception {
        String messageId = "<" + TestTokens.randomSuffix() + "@mail.example.test>";
        MimeMessage email = smtp.createMimeMessage();
        email.setFrom(new InternetAddress(customer, "Jane Doe"));
        email.setRecipients(jakarta.mail.Message.RecipientType.TO, supportAddress);
        email.setSubject("My invoice is wrong");
        email.setText("Please check invoice 42.", StandardCharsets.UTF_8.name());
        email.setHeader("Message-ID", messageId);
        smtp.send(email); // through Mailpit's SMTP port, like a real customer's mail

        await().atMost(Duration.ofSeconds(15)).until(() -> {
            poller.pollOnce();
            return ticketOf(messageId) != null;
        });
        long id = ticketOf(messageId);

        JsonNode ticket = ticket(id, ann);
        assertThat(ticket.get("ticket").get("channel").asString()).isEqualTo("EMAIL");
        assertThat(ticket.get("ticket").get("subject").asString()).isEqualTo("My invoice is wrong");
        assertThat(ticket.get("ticket").get("queue").get("id").asLong()).isEqualTo(queue);
        assertThat(ticket.get("ticket").get("customer").get("email").asString()).isEqualTo(customer);
        assertThat(ticket.get("messages").get(0).get("body").asString()).isEqualTo("Please check invoice 42.");
        assertThat(ticket.get("messages").get(0).get("viaEmail").asBoolean()).isTrue();

        // The original email is tagged in the mailbox, so it isn't fetched again...
        MailpitTestClient mailpit = new MailpitTestClient(mailpitUrl, json);
        List<JsonNode> original = mailpit.search("to:\"" + supportAddress + "\"");
        assertThat(original).hasSize(1);
        assertThat(original.getFirst().get("Tags").toString()).contains("helpdesk", "HD-" + id);
        assertThat(poller.pollOnce()).isZero();

        // ...and, once the outbox is relayed, the customer gets an acknowledgement.
        publishOutbox();
        await().atMost(Duration.ofSeconds(15)).until(() ->
                mailpit.hasSubject("to:\"" + customer + "\"", "[HD-" + id + "] We received your request"));
    }

    @Test
    void theSameEmailFetchedTwiceMakesOneTicket() throws Exception {
        byte[] raw = email(customer, supportAddress, "<dup-" + TestTokens.randomSuffix() + "@example.test>", null,
                "Duplicate?", "Hello");
        assertThat(intake.ingest(raw, supportAddress, "ref-1").outcome()).isEqualTo(Outcome.CREATED_TICKET);
        assertThat(intake.ingest(raw, supportAddress, "ref-1").outcome()).isEqualTo(Outcome.DUPLICATE);

        // Two instances fetching the same new email at the very same moment: one ticket.
        byte[] raced = email(customer, supportAddress, "<race-" + TestTokens.randomSuffix() + "@example.test>", null,
                "Race", "Hello again");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Callable<IngestResult> ingest = () -> intake.ingest(raced, supportAddress, "ref-2");
        List<Future<IngestResult>> results = pool.invokeAll(List.of(ingest, ingest));
        pool.shutdown();
        List<Outcome> outcomes = List.of(results.get(0).get().outcome(), results.get(1).get().outcome());
        assertThat(outcomes).containsExactlyInAnyOrder(Outcome.CREATED_TICKET, Outcome.DUPLICATE);
    }

    @Test
    void aCustomerReplyIsThreadedOntoTheTicketAndResumesTheSlaClock() throws Exception {
        String firstId = "<first-" + TestTokens.randomSuffix() + "@example.test>";
        long id = intake.ingest(email(customer, supportAddress, firstId, null, "Printer on fire", "Help!"),
                supportAddress, "ref").ticketId();
        act(id, "ASSIGN", ann);
        postJson("/api/tickets/" + id + "/messages", ann, Map.of("body", "Is it plugged in?", "visibility", "PUBLIC"));
        act(id, "WAIT_ON_CUSTOMER", ann);
        assertThat(timer(ticket(id, ann), "RESOLUTION").get("clockState").asString()).isEqualTo("PAUSED");

        // The agent's reply goes out by email, with a Message-ID we remember for this ticket.
        publishOutbox();
        MailpitTestClient mailpit = new MailpitTestClient(mailpitUrl, json);
        await().atMost(Duration.ofSeconds(15)).until(() ->
                mailpit.hasSubject("to:\"" + customer + "\"", "[HD-" + id + "] Re: Printer on fire"));
        String ourReplyId = jdbc.sql("""
                        SELECT message_id FROM email_threads WHERE ticket_id = :id AND message_id LIKE '<hd-%'
                        ORDER BY message_id LIMIT 1""")
                .param("id", id).query(String.class).single();

        clock.advance(Duration.ofHours(1));
        String quotedReply = """
                Yes, and it still smokes.

                On Mon, 23 Nov 2026 at 10:05, Support wrote:
                > ##- Please type your reply above this line -##
                > Is it plugged in?
                """;
        IngestResult reply = intake.ingest(email(customer, supportAddress, "<reply-" + TestTokens.randomSuffix()
                + "@example.test>", ourReplyId, "Re: [HD-" + id + "] Re: Printer on fire", quotedReply), supportAddress, "r");

        assertThat(reply.outcome()).isEqualTo(Outcome.APPENDED);
        assertThat(reply.ticketId()).isEqualTo(id);
        JsonNode after = ticket(id, ann);
        assertThat(after.get("ticket").get("status").asString()).isEqualTo("OPEN");
        assertThat(timer(after, "RESOLUTION").get("clockState").asString()).isEqualTo("RUNNING");
        JsonNode messages = after.get("messages");
        assertThat(messages.get(messages.size() - 1).get("body").asString()).isEqualTo("Yes, and it still smokes.");
    }

    @Test
    void theTicketNumberInTheSubjectThreadsWhenHeadersAreMissing() throws Exception {
        long id = intake.ingest(email(customer, supportAddress, null, null, "Wifi down", "No wifi"), supportAddress, "r")
                .ticketId();
        IngestResult reply = intake.ingest(email(customer, supportAddress, null, null, "Re: [HD-" + id + "] Wifi down",
                "Still down"), supportAddress, "r2");
        assertThat(reply.outcome()).isEqualTo(Outcome.APPENDED);
        assertThat(reply.ticketId()).isEqualTo(id);
    }

    @Test
    void aStrangerReplyingInTheThreadGetsTheirOwnTicket() throws Exception {
        String firstId = "<orig-" + TestTokens.randomSuffix() + "@example.test>";
        long id = intake.ingest(email(customer, supportAddress, firstId, null, "Account question", "Hi"),
                supportAddress, "r").ticketId();

        IngestResult stranger = intake.ingest(email("mallory@evil.test", supportAddress, null, firstId,
                "Re: [HD-" + id + "] Account question", "Please send me the details"), supportAddress, "r2");

        assertThat(stranger.outcome()).isEqualTo(Outcome.CREATED_TICKET);
        assertThat(stranger.ticketId()).isNotEqualTo(id);
    }

    @Test
    void aReplyToAClosedTicketStartsAFollowUp() throws Exception {
        long id = intake.ingest(email(customer, supportAddress, null, null, "Broken mouse", "It clicks twice"),
                supportAddress, "r").ticketId();
        act(id, "ASSIGN", ann);
        act(id, "RESOLVE", ann);
        act(id, "CLOSE", ann);

        IngestResult followUp = intake.ingest(email(customer, supportAddress, null, null, "Re: [HD-" + id + "] Broken mouse",
                "It's back"), supportAddress, "r2");

        assertThat(followUp.outcome()).isEqualTo(Outcome.CREATED_TICKET);
        JsonNode ticket = ticket(followUp.ticketId(), ann);
        assertThat(ticket.get("ticket").get("subject").asString()).isEqualTo("Broken mouse");
        assertThat(ticket.get("messages").get(0).get("body").asString()).startsWith("(Follow-up to HD-" + id);
    }

    @Test
    void automaticEmailsAndOurOwnAreIgnoredSoTheyCantStartAMailLoop() {
        String outOfOffice = """
                From: %s
                To: %s
                Subject: Out of office
                Auto-Submitted: auto-replied

                I'm on holiday.
                """.formatted(customer, supportAddress);
        assertThat(intake.ingest(crlf(outOfOffice), supportAddress, "r1").outcome()).isEqualTo(Outcome.IGNORED);

        String fromOurselves = """
                From: %s
                To: %s
                Subject: [HD-1] Re: something

                Echo
                """.formatted(supportAddress, supportAddress);
        assertThat(intake.ingest(crlf(fromOurselves), supportAddress, "r2").outcome()).isEqualTo(Outcome.IGNORED);

        String bounced = """
                From: %s
                To: %s
                Subject: Re: [HD-1] Hi
                X-Helpdesk-Origin: helpdesk

                Our own email came back.
                """.formatted(customer, supportAddress);
        IngestResult loop = intake.ingest(crlf(bounced), supportAddress, "r3");
        assertThat(loop.outcome()).isEqualTo(Outcome.IGNORED);
        assertThat(loop.reason()).contains("mail loop");
    }

    private Long ticketOf(String messageId) {
        return jdbc.sql("SELECT ticket_id FROM inbound_emails WHERE message_id = :id")
                .param("id", messageId).query(Long.class).optional().orElse(null);
    }

    /** A plain-text email as raw bytes. {@code messageId} and {@code inReplyTo} may be null. */
    private static byte[] email(String from, String to, String messageId, String inReplyTo, String subject, String body) {
        StringBuilder email = new StringBuilder();
        email.append("From: ").append(from).append('\n');
        email.append("To: ").append(to).append('\n');
        email.append("Subject: ").append(subject).append('\n');
        if (messageId != null) {
            email.append("Message-ID: ").append(messageId).append('\n');
        }
        if (inReplyTo != null) {
            email.append("In-Reply-To: ").append(inReplyTo).append('\n');
            email.append("References: ").append(inReplyTo).append('\n');
        }
        email.append("Content-Type: text/plain; charset=UTF-8\n\n").append(body).append('\n');
        return crlf(email.toString());
    }

    private static byte[] crlf(String email) {
        return email.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
    }
}
