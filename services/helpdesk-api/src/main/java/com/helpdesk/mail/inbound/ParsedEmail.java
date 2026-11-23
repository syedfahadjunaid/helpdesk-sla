package com.helpdesk.mail.inbound;

import java.util.List;

/**
 * The parts of an incoming email the helpdesk cares about, already decoded.
 *
 * @param messageId the RFC 5322 Message-ID, angle brackets included; null if the email had none
 * @param threadIds In-Reply-To and References ids, oldest first: what this email replies to
 * @param recipients every To and Cc address, lower case
 * @param body the new text only: quoted history and our "reply above this line" footer removed
 * @param autoSubmitted the Auto-Submitted header (RFC 3834), e.g. "auto-replied"; null if absent
 * @param precedence the Precedence header ("bulk", "list", "auto_reply"...); null if absent
 * @param helpdeskOrigin our own loop-marker header; set only if the email came from this helpdesk
 */
public record ParsedEmail(
        String messageId,
        String fromAddress,
        String fromName,
        List<String> recipients,
        String subject,
        String body,
        List<String> threadIds,
        String autoSubmitted,
        String precedence,
        String helpdeskOrigin) {

    public ParsedEmail {
        recipients = List.copyOf(recipients);
        threadIds = List.copyOf(threadIds);
    }
}
