package com.helpdesk.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.helpdesk.IntegrationTest;
import com.helpdesk.MutableClock;
import com.helpdesk.TestUsers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/** The ticket API end to end: lifecycle, permissions, status codes, audit history. */
class TicketLifecycleApiTests extends IntegrationTest {

    @BeforeEach
    void officeHours() {
        clock.set(MutableClock.MONDAY_10AM_LONDON);
    }

    @Test
    void anAgentPicksUpRepliesWaitsResolvesAndReopens() throws Exception {
        TestUsers.User ann = TestUsers.agent("ann");
        long queue = newQueue(ann);
        long id = createTicket(ann, queue, Priority.HIGH);

        JsonNode created = ticket(id, ann);
        assertThat(created.get("ticket").get("status").asString()).isEqualTo("NEW");
        assertThat(created.get("ticket").get("number").asString()).isEqualTo("HD-" + id);
        assertThat(names(created.get("allowedActions"))).containsExactly("ASSIGN");
        assertThat(timer(created, "FIRST_RESPONSE").get("health").asString()).isEqualTo("ON_TRACK");

        JsonNode assigned = act(id, "ASSIGN", ann);
        assertThat(assigned.get("ticket").get("status").asString()).isEqualTo("OPEN");
        assertThat(assigned.get("ticket").get("assignee").get("name").asString()).isEqualTo(ann.displayName());
        assertThat(names(assigned.get("allowedActions"))).contains("WAIT_ON_CUSTOMER", "RESOLVE");

        clock.advance(Duration.ofMinutes(15));
        postJson("/api/tickets/" + id + "/messages", ann, Map.of("body", "Try turning it off.", "visibility", "PUBLIC"));
        JsonNode replied = ticket(id, ann);
        assertThat(timer(replied, "FIRST_RESPONSE").get("health").asString()).isEqualTo("MET");
        assertThat(timer(replied, "FIRST_RESPONSE").get("elapsedSeconds").asLong()).isEqualTo(15 * 60);

        JsonNode waiting = act(id, "WAIT_ON_CUSTOMER", ann);
        assertThat(waiting.get("ticket").get("status").asString()).isEqualTo("WAITING_ON_CUSTOMER");
        assertThat(timer(waiting, "RESOLUTION").get("clockState").asString()).isEqualTo("PAUSED");

        assertThat(act(id, "RESUME", ann).get("ticket").get("status").asString()).isEqualTo("OPEN");
        JsonNode resolved = act(id, "RESOLVE", ann);
        assertThat(resolved.get("ticket").get("status").asString()).isEqualTo("RESOLVED");
        assertThat(timer(resolved, "RESOLUTION").get("health").asString()).isEqualTo("MET");

        JsonNode reopened = act(id, "REOPEN", ann);
        assertThat(reopened.get("ticket").get("status").asString()).isEqualTo("OPEN");
        assertThat(timer(reopened, "RESOLUTION").get("clockState").asString()).isEqualTo("RUNNING");

        List<String> history = new ArrayList<>();
        getJson("/api/tickets/" + id + "/history", ann).forEach(event -> history.add(event.get("action").asString()));
        assertThat(history).containsExactly("TICKET_CREATED", "ASSIGNED", "STATUS_CHANGED", "MESSAGE_ADDED",
                "STATUS_CHANGED", "STATUS_CHANGED", "STATUS_CHANGED", "STATUS_CHANGED");
    }

    @Test
    void withoutASessionTheApiAnswers401NotARedirect() throws Exception {
        mvc.perform(get("/api/tickets")).andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("Location"));
    }

    @Test
    void stateChangesNeedTheCsrfToken() throws Exception {
        TestUsers.User ann = TestUsers.agent("ann");
        mvc.perform(post("/api/tickets").with(ann.session())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void signedInPeopleWithoutAHelpdeskRoleGet403() throws Exception {
        mvc.perform(get("/api/me").with(TestUsers.outsider("olly").session())).andExpect(status().isForbidden());
    }

    @Test
    void onlyTheAssigneeOrASupervisorWorksATicket() throws Exception {
        TestUsers.User ann = TestUsers.agent("ann");
        TestUsers.User bob = TestUsers.agent("bob");
        TestUsers.User sam = TestUsers.supervisor("sam");
        long queue = newQueue(ann, bob);
        long id = createTicket(ann, queue, Priority.NORMAL);
        act(id, "ASSIGN", ann);

        JsonNode asBob = ticket(id, bob);
        assertThat(names(asBob.get("allowedActions"))).as("bob sees it but may only take it over").containsExactly("ASSIGN");
        assertThat(asBob.get("canReplyPublicly").asBoolean()).isFalse();

        perform("/api/tickets/" + id + "/actions/RESOLVE", bob, Map.of())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("You can't resolve this ticket."));
        perform("/api/tickets/" + id + "/messages", bob, Map.of("body", "Hi", "visibility", "PUBLIC"))
                .andExpect(status().isForbidden());
        perform("/api/tickets/" + id + "/messages", bob, Map.of("body", "FYI: known issue", "visibility", "INTERNAL"))
                .andExpect(status().isCreated());

        assertThat(act(id, "RESOLVE", sam).get("ticket").get("status").asString()).isEqualTo("RESOLVED");
    }

    @Test
    void onlySupervisorsAssignToOthersAndOnlyToQueueMembers() throws Exception {
        TestUsers.User ann = TestUsers.agent("ann");
        TestUsers.User bob = TestUsers.agent("bob");
        TestUsers.User outsiderAgent = TestUsers.agent("zed");
        TestUsers.User sam = TestUsers.supervisor("sam");
        long queue = newQueue(ann, bob);
        long bobId = provision(bob);
        long zedId = provision(outsiderAgent);
        long id = createTicket(ann, queue, Priority.NORMAL);

        perform("/api/tickets/" + id + "/actions/ASSIGN", ann, Map.of("assigneeId", bobId))
                .andExpect(status().isForbidden());
        perform("/api/tickets/" + id + "/actions/ASSIGN", sam, Map.of("assigneeId", zedId))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("assigneeId"));
        perform("/api/tickets/" + id + "/actions/ASSIGN", sam, Map.of("assigneeId", bobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticket.assignee.id").value(bobId));
    }

    @Test
    void movesTheStateMachineForbidsAre409() throws Exception {
        TestUsers.User sam = TestUsers.supervisor("sam");
        long queue = newQueue(sam);
        long id = createTicket(sam, queue, Priority.LOW);

        perform("/api/tickets/" + id + "/actions/RESOLVE", sam, Map.of())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Can't resolve a ticket that is new."));
        perform("/api/tickets/" + id + "/actions/CUSTOMER_REPLIED", sam, Map.of())
                .andExpect(status().isUnprocessableContent());
        perform("/api/tickets/" + id + "/actions/FLY_TO_THE_MOON", sam, Map.of())
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownTicketsAre404() throws Exception {
        mvc.perform(get("/api/tickets/999999999").with(TestUsers.agent("ann").session()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Ticket HD-999999999 not found"));
    }

    @Test
    void validationErrorsNameTheField() throws Exception {
        TestUsers.User ann = TestUsers.agent("ann");
        perform("/api/tickets", ann, Map.of("customerEmail", "not-an-email", "subject", "", "description", "x",
                "queueId", 1, "priority", "LOW"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'customerEmail')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'subject')]").exists());
    }

    @Test
    void nextTicketHandsOutTheMostUrgentOne() throws Exception {
        TestUsers.User ann = TestUsers.agent("ann");
        long queue = newQueue(ann);
        createTicket(ann, queue, Priority.LOW);
        long urgent = createTicket(ann, queue, Priority.URGENT);

        JsonNode claimed = postJson("/api/queues/" + queue + "/next", ann, Map.of());
        assertThat(claimed.get("ticket").get("id").asLong()).isEqualTo(urgent);
        assertThat(claimed.get("ticket").get("status").asString()).isEqualTo("OPEN");

        postJson("/api/queues/" + queue + "/next", ann, Map.of()); // the LOW one
        perform("/api/queues/" + queue + "/next", ann, Map.of()).andExpect(status().isNoContent());
        perform("/api/queues/" + queue + "/next", TestUsers.agent("nonmember"), Map.of())
                .andExpect(status().isForbidden());
    }

    @Test
    void historyCannotBeRewrittenEvenWithDirectSql() throws Exception {
        TestUsers.User ann = TestUsers.agent("ann");
        long id = createTicket(ann, newQueue(ann), Priority.NORMAL);

        // The app's own database login: no UPDATE or DELETE privilege on history tables...
        assertThatThrownBy(() -> jdbc.sql("UPDATE audit_events SET action = 'NOTHING' WHERE ticket_id = :id")
                .param("id", id).update())
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("permission denied for table audit_events");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM ticket_messages WHERE ticket_id = :id").param("id", id).update())
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("permission denied for table ticket_messages");
    }

    @Test
    void adminsChangeSlaPoliciesAgentsCannot() throws Exception {
        TestUsers.User ann = TestUsers.agent("ann");
        TestUsers.User ada = TestUsers.admin("ada");
        JsonNode low = null;
        for (JsonNode policy : getJson("/api/sla-policies", ann)) {
            if ("LOW".equals(policy.get("priority").asString())) {
                low = policy;
            }
        }
        assertThat(low).isNotNull();
        Map<String, Object> sameValues = Map.of(
                "firstResponseMinutes", low.get("firstResponseMinutes").asInt(),
                "resolutionMinutes", low.get("resolutionMinutes").asInt(),
                "warningPercent", low.get("warningPercent").asInt(),
                "calendarId", low.get("calendarId").asLong(),
                "version", low.get("version").asLong());

        performPut("/api/sla-policies/LOW", ann, sameValues).andExpect(status().isForbidden());
        performPut("/api/sla-policies/LOW", ada, sameValues).andExpect(status().isOk());
        performPut("/api/sla-policies/LOW", ada, sameValues)
                .andExpect(status().isConflict()); // the version moved on: a stale edit is refused
    }

    private static List<String> names(JsonNode array) {
        List<String> names = new ArrayList<>();
        array.forEach(node -> names.add(node.asString()));
        return names;
    }
}
