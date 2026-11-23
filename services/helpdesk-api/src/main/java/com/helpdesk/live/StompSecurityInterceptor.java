package com.helpdesk.live;

import com.helpdesk.security.Actor;
import com.helpdesk.security.AgentDirectory;
import java.security.Principal;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Authorization for STOMP frames coming <em>from</em> browsers.
 *
 * <p>The WebSocket handshake is an ordinary HTTP request, so Spring Security has already
 * authenticated it (session cookie, or a bearer token for scripts) and the user is attached
 * to every frame. Here we decide what they may do with the connection:
 * <ul>
 *   <li>CONNECT: helpdesk staff only;</li>
 *   <li>SUBSCRIBE: to {@code /topic/tickets}, or to their <em>own</em> {@code /topic/agent.<id>};</li>
 *   <li>SEND: never. Browsers only listen; all changes go through the REST API, where the
 *       business rules are.</li>
 * </ul>
 *
 * <p>Cross-site WebSocket hijacking (a malicious page opening a socket with the user's cookie)
 * is blocked by the handshake's origin check (see {@code WebSocketConfig}) and the session
 * cookie being SameSite=Lax.
 */
@Component
public class StompSecurityInterceptor implements ChannelInterceptor {

    private final AgentDirectory agents;

    StompSecurityInterceptor(AgentDirectory agents) {
        this.agents = agents;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor stomp = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (stomp == null || stomp.getCommand() == null) {
            return message; // heartbeats and other non-frame messages
        }
        StompCommand command = stomp.getCommand();
        switch (command) {
            case CONNECT, STOMP -> staff(stomp.getUser());
            case SUBSCRIBE -> {
                Actor actor = staff(stomp.getUser());
                String destination = stomp.getDestination();
                boolean allowed = LiveUpdateListener.TICKETS_TOPIC.equals(destination)
                        || AgentTopics.topicOf(actor.agentId()).equals(destination);
                if (!allowed) {
                    throw new AccessDeniedException("You can't subscribe to " + destination);
                }
            }
            case SEND -> throw new AccessDeniedException("Sending over the WebSocket isn't supported; use the API.");
            default -> {
                // DISCONNECT, UNSUBSCRIBE, ACK, NACK...: harmless
            }
        }
        return message;
    }

    private Actor staff(Principal user) {
        if (!(user instanceof Authentication authentication) || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("Sign in first");
        }
        return agents.resolve(authentication);
    }
}
