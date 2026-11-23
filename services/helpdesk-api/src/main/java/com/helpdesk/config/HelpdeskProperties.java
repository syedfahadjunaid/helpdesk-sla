package com.helpdesk.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Type-safe view of the {@code helpdesk.*} section in application.yml. The app refuses to
 * start if a required value is missing, instead of failing later at the first request.
 *
 * @param instanceName shown in logs and health output, to tell instances apart
 * @param publicUrl where users reach the app (nginx). Redirect URIs and the allowed
 *     WebSocket origin are built from it, never from request headers, which a client controls.
 */
@Validated
@ConfigurationProperties(prefix = "helpdesk")
public record HelpdeskProperties(
        @NotBlank String instanceName,
        @NotBlank String publicUrl,
        @Valid @NotNull Keycloak keycloak,
        @Valid @NotNull StompRelay stompRelay,
        @Valid @NotNull Mail mail,
        @Valid @NotNull Sla sla,
        @Valid @NotNull Outbox outbox,
        @Valid @NotNull Tickets tickets) {

    /**
     * How this app signs users in, and which bearer tokens it accepts.
     *
     * @param issuerUri the realm as the browser sees it; also the {@code iss} of every token
     * @param backchannelUri the same realm as this service reaches it (inside Docker that's
     *     {@code http://keycloak:8080/...}, because "localhost" there is the container itself)
     */
    public record Keycloak(
            @NotBlank String issuerUri,
            @NotBlank String backchannelUri,
            @NotBlank String clientId,
            @NotBlank String clientSecret,
            @NotBlank String apiAudience) {}

    /** RabbitMQ's STOMP plugin, which fans WebSocket messages out to every app instance. */
    public record StompRelay(@NotBlank String host, int port, @NotBlank String login, @NotBlank String passcode) {}

    public record Mail(@NotBlank String domain, @Valid @NotNull Inbound inbound) {

        /**
         * @param mailpitUrl the mailbox we poll for new customer emails (Mailpit's REST API)
         * @param maxSizeBytes emails larger than this are ignored, not parsed
         */
        public record Inbound(boolean enabled, @NotBlank String mailpitUrl, @NotNull Duration pollInterval,
                @Min(1024) long maxSizeBytes) {}
    }

    /** The escalation worker: how often it looks for due SLA clocks, and how many per transaction. */
    public record Sla(@NotNull Duration workerInterval, @Min(1) int batchSize) {}

    public record Outbox(@NotNull Duration relayInterval, @Min(1) int batchSize) {}

    /** @param autoCloseAfter how long a resolved ticket waits for the customer before it closes itself */
    public record Tickets(@NotNull Duration autoCloseAfter) {}
}
