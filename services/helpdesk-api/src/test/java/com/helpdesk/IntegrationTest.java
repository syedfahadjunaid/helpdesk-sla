package com.helpdesk;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.helpdesk.events.OutboxRelay;
import com.helpdesk.ticket.Priority;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base class for integration tests: the whole application on a random port, against real
 * Postgres, RabbitMQ and Mailpit containers. All subclasses share one Spring context (and so
 * one set of containers), because their configuration is identical.
 *
 * <p>Isolation without cleanup: every test creates its own people (random names), its own
 * queue and its own tickets, and only asserts on those.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, IntegrationTest.TestBeans.class})
public abstract class IntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        /** Replaces the system clock everywhere in the app. */
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }

        /** Trusts tokens signed by {@link TestTokens} (instead of Keycloak's keys). */
        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            return TestTokens.decoder();
        }
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected JsonMapper json;

    @Autowired
    protected OutboxRelay outboxRelay;

    // ---- people and queues -------------------------------------------------------------

    /** Signs the user in once (creating their agent record) and returns their agent id. */
    protected long provision(TestUsers.User user) throws Exception {
        return getJson("/api/me", user).get("id").asLong();
    }

    /** A fresh queue for this test, with an inbound address, and these agents as members. */
    protected long newQueue(TestUsers.User... members) throws Exception {
        String key = "q-" + TestTokens.randomSuffix();
        long queueId = jdbc.sql("""
                        INSERT INTO queues (key, name, inbound_address) VALUES (:key, :name, :address) RETURNING id""")
                .param("key", key)
                .param("name", "Queue " + key)
                .param("address", key + "@helpdesk.test")
                .query(Long.class)
                .single();
        for (TestUsers.User member : members) {
            long agentId = provision(member);
            jdbc.sql("INSERT INTO queue_members (queue_id, agent_id) VALUES (:queue, :agent)")
                    .param("queue", queueId)
                    .param("agent", agentId)
                    .update();
        }
        return queueId;
    }

    protected String inboundAddressOf(long queueId) {
        return jdbc.sql("SELECT inbound_address FROM queues WHERE id = :id").param("id", queueId)
                .query(String.class).single();
    }

    // ---- tickets -----------------------------------------------------------------------

    /** An agent logs a ticket; returns its id. */
    protected long createTicket(TestUsers.User agent, long queueId, Priority priority) throws Exception {
        JsonNode created = postJson("/api/tickets", agent, Map.of(
                "customerEmail", "customer-" + TestTokens.randomSuffix() + "@example.test",
                "customerName", "Casey Customer",
                "subject", "Printer is on fire",
                "description", "It started smoking after the update.",
                "queueId", queueId,
                "priority", priority.name()));
        return created.get("ticket").get("id").asLong();
    }

    protected JsonNode ticket(long ticketId, TestUsers.User user) throws Exception {
        return getJson("/api/tickets/" + ticketId, user);
    }

    protected JsonNode act(long ticketId, String action, TestUsers.User user) throws Exception {
        return postJson("/api/tickets/" + ticketId + "/actions/" + action, user, Map.of());
    }

    protected JsonNode timer(JsonNode ticketDetail, String metric) {
        for (JsonNode timer : ticketDetail.get("ticket").get("sla")) {
            if (metric.equals(timer.get("metric").asString())) {
                return timer;
            }
        }
        throw new AssertionError("No " + metric + " timer in " + ticketDetail);
    }

    // ---- HTTP helpers (browser session + CSRF, like the web app) -----------------------------

    protected JsonNode getJson(String path, TestUsers.User user) throws Exception {
        return read(mvc.perform(get(path).with(user.session())).andExpect(status().isOk()));
    }

    protected JsonNode postJson(String path, TestUsers.User user, Object body) throws Exception {
        return read(perform(path, user, body).andExpect(status().is2xxSuccessful()));
    }

    protected ResultActions perform(String path, TestUsers.User user, Object body) throws Exception {
        return mvc.perform(post(path).with(user.session()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    protected ResultActions performPut(String path, TestUsers.User user, Object body) throws Exception {
        return mvc.perform(put(path).with(user.session()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    protected JsonNode read(ResultActions result) throws Exception {
        String content = result.andReturn().getResponse().getContentAsString();
        return content.isEmpty() ? json.createObjectNode() : json.readTree(content);
    }

    /** Publishes everything waiting in the outbox (the relay doesn't run by itself in tests). */
    protected void publishOutbox() {
        while (outboxRelay.publishBatch() > 0) {
            // keep going until the outbox is empty
        }
    }
}
