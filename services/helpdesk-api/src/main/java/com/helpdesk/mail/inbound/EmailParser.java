package com.helpdesk.mail.inbound;

import com.helpdesk.mail.OutboundMailer;
import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a raw email (RFC 5322 / MIME bytes) into a {@link ParsedEmail}, using Jakarta Mail.
 * Pure: no database, no network, so it's unit-tested with hand-written emails.
 */
public final class EmailParser {

    /** Longest message body we keep; anything longer is cut (a ticket isn't an archive). */
    static final int MAX_BODY_CHARS = 20_000;

    private static final Pattern MESSAGE_ID = Pattern.compile("<[^<>\\s]+>");
    /** "On Mon, 3 Mar 2026 at 10:00, Ann Agent wrote:" (Gmail, Apple Mail, Thunderbird...). */
    private static final Pattern ATTRIBUTION_LINE = Pattern.compile("^\\s*On .{0,300}wrote:\\s*$");
    private static final Pattern OUTLOOK_SEPARATOR = Pattern.compile("^\\s*-{2,}\\s*Original Message\\s*-{2,}\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_BREAK = Pattern.compile("(?i)<\\s*(br|/p|/div|/li|/tr)\\s*/?>");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]*>");
    private static final Pattern HTML_HIDDEN = Pattern.compile("(?is)<(script|style|head)[^>]*>.*?</\\1\\s*>");

    private static final Session SESSION = Session.getInstance(new Properties());

    private EmailParser() {}

    public static ParsedEmail parse(byte[] raw) {
        try {
            MimeMessage mime = new MimeMessage(SESSION, new ByteArrayInputStream(raw));
            InternetAddress from = firstAddress(mime.getFrom());
            List<String> recipients = new ArrayList<>();
            addAddresses(recipients, mime.getRecipients(jakarta.mail.Message.RecipientType.TO));
            addAddresses(recipients, mime.getRecipients(jakarta.mail.Message.RecipientType.CC));

            List<String> threadIds = new ArrayList<>(messageIds(mime.getHeader("References", " ")));
            for (String parent : messageIds(mime.getHeader("In-Reply-To", " "))) {
                if (!threadIds.contains(parent)) {
                    threadIds.add(parent);
                }
            }
            List<String> ownId = messageIds(mime.getHeader("Message-ID", null));
            String text = textOf(mime);
            return new ParsedEmail(
                    ownId.isEmpty() ? null : ownId.getFirst(),
                    from == null ? null : from.getAddress().toLowerCase(Locale.ROOT),
                    from == null ? null : from.getPersonal(),
                    recipients,
                    mime.getSubject(),
                    newText(text == null ? "" : text),
                    threadIds,
                    mime.getHeader("Auto-Submitted", null),
                    mime.getHeader("Precedence", null),
                    mime.getHeader(OutboundMailer.ORIGIN_HEADER, null));
        } catch (MessagingException | IOException e) {
            throw new IllegalArgumentException("Not a readable email: " + e.getMessage(), e);
        }
    }

    /**
     * Only what the customer just wrote: cut at our "reply above this line" marker, at an
     * "On ... wrote:" attribution or an Outlook "Original Message" separator, then drop
     * trailing quoted ("&gt; ") lines.
     */
    static String newText(String body) {
        List<String> kept = new ArrayList<>();
        for (String line : body.replace("\r\n", "\n").split("\n", -1)) {
            if (line.contains(OutboundMailer.REPLY_ABOVE_MARKER)
                    || ATTRIBUTION_LINE.matcher(line).matches()
                    || OUTLOOK_SEPARATOR.matcher(line).matches()) {
                break;
            }
            kept.add(line);
        }
        while (!kept.isEmpty() && (kept.getLast().isBlank() || kept.getLast().stripLeading().startsWith(">"))) {
            kept.removeLast();
        }
        String text = String.join("\n", kept).strip();
        if (text.isEmpty()) {
            text = "(no text)";
        }
        return text.length() > MAX_BODY_CHARS ? text.substring(0, MAX_BODY_CHARS) + "\n[cut]" : text;
    }

    /** The plain-text body: text/plain preferred, else HTML with the tags stripped. */
    private static String textOf(Part part) throws MessagingException, IOException {
        if (part.isMimeType("text/plain")) {
            return part.getContent().toString();
        }
        if (part.isMimeType("text/html")) {
            return htmlToText(part.getContent().toString());
        }
        if (part.isMimeType("multipart/*") && part.getContent() instanceof Multipart multipart) {
            String html = null;
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart child = multipart.getBodyPart(i);
                if (Part.ATTACHMENT.equalsIgnoreCase(child.getDisposition())) {
                    continue; // attachments aren't stored in this version
                }
                if (child.isMimeType("text/plain")) {
                    return child.getContent().toString();
                }
                String nested = textOf(child);
                if (nested != null && html == null) {
                    html = nested;
                }
            }
            return html;
        }
        return null;
    }

    static String htmlToText(String html) {
        String text = HTML_HIDDEN.matcher(html).replaceAll("");
        text = HTML_BREAK.matcher(text).replaceAll("\n");
        text = HTML_TAG.matcher(text).replaceAll("");
        return text.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
    }

    static List<String> messageIds(String header) {
        List<String> ids = new ArrayList<>();
        if (header != null) {
            Matcher matcher = MESSAGE_ID.matcher(header);
            while (matcher.find()) {
                ids.add(matcher.group());
            }
        }
        return ids;
    }

    private static InternetAddress firstAddress(Address[] addresses) {
        if (addresses == null) {
            return null;
        }
        for (Address address : addresses) {
            if (address instanceof InternetAddress internet && internet.getAddress() != null) {
                return internet;
            }
        }
        return null;
    }

    private static void addAddresses(List<String> into, Address[] addresses) {
        if (addresses == null) {
            return;
        }
        for (Address address : addresses) {
            if (address instanceof InternetAddress internet && internet.getAddress() != null) {
                into.add(internet.getAddress().toLowerCase(Locale.ROOT));
            }
        }
    }
}
