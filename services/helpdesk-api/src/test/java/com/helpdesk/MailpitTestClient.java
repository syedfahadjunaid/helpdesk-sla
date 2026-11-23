package com.helpdesk;

import java.util.ArrayList;
import java.util.List;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reads the Mailpit test container's mailbox, to check what the app sent. */
public class MailpitTestClient {

    private final RestClient http;
    private final JsonMapper json;

    public MailpitTestClient(String baseUrl, JsonMapper json) {
        this.http = RestClient.create(baseUrl);
        this.json = json;
    }

    /** Message summaries matching a Mailpit search, e.g. {@code to:"jane@example.test"}. */
    public List<JsonNode> search(String query) {
        String body = http.get()
                .uri(uri -> uri.path("/api/v1/search").queryParam("query", query).build())
                .retrieve()
                .body(String.class);
        List<JsonNode> messages = new ArrayList<>();
        json.readTree(body).get("messages").forEach(messages::add);
        return messages;
    }

    /** Is there a message matching the search whose subject contains {@code subject}? */
    public boolean hasSubject(String query, String subject) {
        return search(query).stream().anyMatch(message -> message.get("Subject").asString().contains(subject));
    }

    public String raw(String id) {
        return http.get().uri("/api/v1/message/{id}/raw", id).retrieve().body(String.class);
    }
}
