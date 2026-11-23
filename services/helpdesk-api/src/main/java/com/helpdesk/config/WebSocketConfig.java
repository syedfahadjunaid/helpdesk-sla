package com.helpdesk.config;

import com.helpdesk.live.StompSecurityInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Live updates over WebSocket, using the STOMP protocol.
 *
 * <p>Browsers connect to {@code /ws} on whichever app instance nginx picks. Instead of an
 * in-memory broker (which would only reach browsers connected to the same instance), Spring
 * relays STOMP to RabbitMQ's STOMP plugin: a message sent by any instance reaches every
 * subscribed browser on every instance. Scaling out needs no sticky sessions.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final HelpdeskProperties props;
    private final StompSecurityInterceptor security;

    WebSocketConfig(HelpdeskProperties props, StompSecurityInterceptor security) {
        this.props = props;
        this.security = security;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Origin check: a page on another site can't open a socket with the user's cookie.
        registry.addEndpoint("/ws").setAllowedOrigins(props.publicUrl());
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        HelpdeskProperties.StompRelay relay = props.stompRelay();
        registry.setApplicationDestinationPrefixes("/app");
        registry.enableStompBrokerRelay("/topic")
                .setRelayHost(relay.host())
                .setRelayPort(relay.port())
                // one connection per browser session, plus a shared "system" connection for sends
                .setClientLogin(relay.login())
                .setClientPasscode(relay.passcode())
                .setSystemLogin(relay.login())
                .setSystemPasscode(relay.passcode());
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(security);
    }
}
