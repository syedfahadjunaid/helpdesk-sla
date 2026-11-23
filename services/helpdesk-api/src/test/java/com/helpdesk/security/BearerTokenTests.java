package com.helpdesk.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.helpdesk.IntegrationTest;
import com.helpdesk.TestTokens;
import com.helpdesk.TestUsers;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** Scripts and integrations call the API with a bearer token: verified on every request. */
class BearerTokenTests extends IntegrationTest {

    @Test
    void aValidTokenGetsIn() throws Exception {
        TestUsers.User sam = TestUsers.supervisor("sam");
        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + sam.bearerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(sam.username()))
                .andExpect(jsonPath("$.roles.length()").value(2));
    }

    @Test
    void noCsrfTokenIsNeededBecauseNoCookieIsInvolved() throws Exception {
        TestUsers.User ann = TestUsers.agent("ann");
        long queue = newQueue(ann);
        mvc.perform(post("/api/tickets").header(HttpHeaders.AUTHORIZATION, "Bearer " + ann.bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("customerEmail", "c@example.test", "subject", "Hi",
                                "description", "Hello", "queueId", queue, "priority", "LOW"))))
                .andExpect(status().isCreated());
    }

    @Test
    void aTokenIssuedForAnotherApplicationIsRejected() throws Exception {
        String token = TestTokens.issue(TestUsers.agent("ann"), "some-other-api");
        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aTamperedTokenIsRejected() throws Exception {
        String token = TestUsers.agent("ann").bearerToken();
        String tampered = token.substring(0, token.length() - 4) + "AAAA";
        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aValidTokenWithoutAHelpdeskRoleIsForbidden() throws Exception {
        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + TestUsers.outsider("olly").bearerToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void onlyAdminsChangeQueueMembership() throws Exception {
        TestUsers.User ann = TestUsers.agent("ann");
        long queue = newQueue();
        long annId = provision(ann);
        String body = json.writeValueAsString(Map.of("agentIds", List.of(annId)));

        mvc.perform(put("/api/queues/" + queue + "/members")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ann.bearerToken())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/queues/" + queue + "/members")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestUsers.admin("ada").bearerToken())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberIds[0]").value(annId));
    }
}
