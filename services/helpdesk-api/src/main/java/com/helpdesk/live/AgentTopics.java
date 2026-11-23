package com.helpdesk.live;

import java.time.Instant;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Each agent's personal WebSocket topic, {@code /topic/agent.<id>}. Only that agent may
 * subscribe to it ({@link StompSecurityInterceptor}). We send a content-free "you have news"
 * ping; the browser then fetches its notifications through the REST API.
 */
@Component
public class AgentTopics {

    public static final String PREFIX = "/topic/agent.";

    /** The ping's body: no content, just "go and fetch your notifications". */
    public record NotificationsChanged(String type, Instant sentAt) {}

    private final SimpMessagingTemplate stomp;

    AgentTopics(SimpMessagingTemplate stomp) {
        this.stomp = stomp;
    }

    public static String topicOf(long agentId) {
        return PREFIX + agentId;
    }

    public void ping(long agentId) {
        stomp.convertAndSend(topicOf(agentId), new NotificationsChanged("notifications", Instant.now()));
    }
}
