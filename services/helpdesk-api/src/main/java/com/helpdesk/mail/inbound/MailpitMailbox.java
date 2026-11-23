package com.helpdesk.mail.inbound;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.helpdesk.config.HelpdeskProperties;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The support mailbox, read through Mailpit's REST API.
 *
 * <p>In this project Mailpit plays the company mail server: customers' emails to
 * support@helpdesk.test land there, and so do the emails we send (so you can read them in its
 * web UI). In production this class would be swapped for an IMAP client, or for the inbound
 * webhook of an email provider; everything after "here are the raw bytes of a new email" stays
 * the same ({@link InboundEmailService}).
 *
 * <p>"Processed" is a Mailpit tag rather than the read flag, so opening an email in the web
 * UI doesn't hide it from the helpdesk.
 */
@Component
public class MailpitMailbox {

    public static final String PROCESSED_TAG = "helpdesk";

    /** One email in a search result. Mailpit's JSON uses capitalised names. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Summary(@JsonProperty("ID") String id, @JsonProperty("MessageID") String messageId,
            @JsonProperty("Subject") String subject, @JsonProperty("Size") long size) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchResult(@JsonProperty("messages") List<Summary> messages) {}

    private final RestClient http;

    MailpitMailbox(RestClient.Builder builder, HelpdeskProperties props) {
        this.http = builder.baseUrl(props.mail().inbound().mailpitUrl()).build();
    }

    /** Emails sent to {@code address} that we haven't processed yet, oldest first. */
    public List<Summary> unprocessed(String address, int limit) {
        SearchResult result = http.get()
                .uri(uri -> uri.path("/api/v1/search")
                        .queryParam("query", "to:\"" + address + "\" -tag:" + PROCESSED_TAG)
                        .queryParam("limit", limit)
                        .build())
                .retrieve()
                .body(SearchResult.class);
        List<Summary> messages = result == null || result.messages() == null
                ? new ArrayList<>()
                : new ArrayList<>(result.messages());
        Collections.reverse(messages); // Mailpit lists newest first
        return messages;
    }

    /** The email exactly as received (headers and MIME body). */
    public byte[] raw(String id) {
        return http.get().uri("/api/v1/message/{id}/raw", id).retrieve().body(byte[].class);
    }

    /** Tags the email (e.g. "helpdesk", "HD-1001") and marks it read, so it isn't fetched again. */
    public void markProcessed(String id, List<String> tags) {
        List<String> allTags = new ArrayList<>(tags);
        allTags.addFirst(PROCESSED_TAG);
        http.put().uri("/api/v1/tags")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("IDs", List.of(id), "Tags", allTags))
                .retrieve()
                .toBodilessEntity();
        http.put().uri("/api/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("IDs", List.of(id), "Read", true))
                .retrieve()
                .toBodilessEntity();
    }
}
