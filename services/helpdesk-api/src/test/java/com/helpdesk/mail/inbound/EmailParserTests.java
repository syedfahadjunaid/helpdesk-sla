package com.helpdesk.mail.inbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Parsing hand-written emails, as a mail client would send them. */
class EmailParserTests {

    @Test
    void readsTheHeadersThatMatterForThreading() {
        ParsedEmail email = EmailParser.parse(bytes("""
                From: Jane Doe <Jane@Example.test>
                To: support@helpdesk.test
                Cc: boss@example.test
                Subject: Re: [HD-1001] Printer on fire
                Message-ID: <reply-2@mail.example.test>
                In-Reply-To: <hd-1001.abc@helpdesk.test>
                References: <original-1@mail.example.test> <hd-1001.abc@helpdesk.test>
                Content-Type: text/plain; charset=UTF-8

                It is still smoking.
                """));

        assertThat(email.messageId()).isEqualTo("<reply-2@mail.example.test>");
        assertThat(email.fromAddress()).isEqualTo("jane@example.test");
        assertThat(email.fromName()).isEqualTo("Jane Doe");
        assertThat(email.recipients()).containsExactly("support@helpdesk.test", "boss@example.test");
        assertThat(email.threadIds()).containsExactly("<original-1@mail.example.test>", "<hd-1001.abc@helpdesk.test>");
        assertThat(email.subject()).isEqualTo("Re: [HD-1001] Printer on fire");
        assertThat(email.body()).isEqualTo("It is still smoking.");
        assertThat(email.autoSubmitted()).isNull();
    }

    @Test
    void keepsOnlyWhatTheCustomerJustWrote() {
        String body = """
                Thanks, that fixed it!

                On Mon, 23 Nov 2026 at 10:15, General support <support@helpdesk.test> wrote:
                > ##- Please type your reply above this line -##
                >
                > Have you tried turning it off and on again?
                """;
        assertThat(EmailParser.newText(body)).isEqualTo("Thanks, that fixed it!");
    }

    @Test
    void cutsAtOurReplyMarkerAndOutlookSeparators() {
        assertThat(EmailParser.newText("Yes please.\n##- Please type your reply above this line -##\nold"))
                .isEqualTo("Yes please.");
        assertThat(EmailParser.newText("Done.\n\n-----Original Message-----\nFrom: support"))
                .isEqualTo("Done.");
        assertThat(EmailParser.newText("> only quoted text")).isEqualTo("(no text)");
    }

    @Test
    void prefersThePlainTextPartOfAMultipartEmail() {
        ParsedEmail email = EmailParser.parse(bytes("""
                From: jane@example.test
                To: support@helpdesk.test
                Subject: Hello
                Message-ID: <m1@example.test>
                MIME-Version: 1.0
                Content-Type: multipart/alternative; boundary="b1"

                --b1
                Content-Type: text/html; charset=UTF-8

                <p>The <b>HTML</b> version</p>
                --b1
                Content-Type: text/plain; charset=UTF-8

                The plain version
                --b1--
                """));
        assertThat(email.body()).isEqualTo("The plain version");
    }

    @Test
    void turnsHtmlOnlyEmailsIntoText() {
        ParsedEmail email = EmailParser.parse(bytes("""
                From: jane@example.test
                To: support@helpdesk.test
                Subject: Hello
                Content-Type: text/html; charset=UTF-8

                <html><head><style>p { color: red }</style></head><body><p>Line one</p><p>Fish &amp; chips</p>\
                <script>alert(1)</script></body></html>
                """));
        assertThat(email.body()).isEqualTo("Line one\nFish & chips");
        assertThat(email.messageId()).as("no Message-ID header").isNull();
    }

    @Test
    void noticesAutomaticEmails() {
        ParsedEmail email = EmailParser.parse(bytes("""
                From: jane@example.test
                To: support@helpdesk.test
                Subject: Out of office
                Auto-Submitted: auto-replied
                Precedence: bulk
                X-Helpdesk-Origin: helpdesk

                I'm away.
                """));
        assertThat(email.autoSubmitted()).isEqualTo("auto-replied");
        assertThat(email.precedence()).isEqualTo("bulk");
        assertThat(email.helpdeskOrigin()).isEqualTo("helpdesk");
    }

    @Test
    void cleansSubjectsForNewTickets() {
        assertThat(InboundEmailService.cleanSubject("Re: [HD-1001] Printer on fire")).isEqualTo("Printer on fire");
        assertThat(InboundEmailService.cleanSubject("FWD: RE: Invoice")).isEqualTo("Invoice");
        assertThat(InboundEmailService.cleanSubject("   ")).isEqualTo("(no subject)");
        assertThat(InboundEmailService.cleanSubject(null)).isEqualTo("(no subject)");
    }

    private static byte[] bytes(String email) {
        return email.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
    }
}
