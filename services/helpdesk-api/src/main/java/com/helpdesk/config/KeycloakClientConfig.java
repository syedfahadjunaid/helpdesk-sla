package com.helpdesk.config;

import com.helpdesk.security.RolesClaim;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/**
 * How this app talks to Keycloak, both as a sign-in client (browsers) and as a resource
 * server (bearer tokens from scripts).
 *
 * <p>Built in code rather than from {@code spring.security.oauth2.*} properties because the
 * browser and this service reach Keycloak at different addresses: the browser is sent to
 * {@code http://localhost:8680}, while inside Docker this service calls
 * {@code http://keycloak:8080}. Property-based setup would fetch Keycloak's discovery document
 * from the browser-facing URL at startup, which isn't reachable from a container.
 */
@Configuration
public class KeycloakClientConfig {

    /** Also the last part of the sign-in URL: {@code /oauth2/authorization/keycloak}. */
    public static final String REGISTRATION_ID = "keycloak";

    @Bean
    ClientRegistrationRepository clientRegistrations(HelpdeskProperties props) {
        HelpdeskProperties.Keycloak keycloak = props.keycloak();
        String browserFacing = keycloak.issuerUri() + "/protocol/openid-connect";
        String backchannel = keycloak.backchannelUri() + "/protocol/openid-connect";

        ClientRegistration registration = ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .clientName("Keycloak")
                // A confidential client: it proves who it is with a secret the browser never sees.
                .clientId(keycloak.clientId())
                .clientSecret(keycloak.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                // PKCE on top of the secret, as the OAuth 2.0 security best practice recommends.
                .clientSettings(ClientRegistration.ClientSettings.builder().requireProofKey(true).build())
                .redirectUri(props.publicUrl() + "/login/oauth2/code/{registrationId}")
                .scope("openid", "profile", "email")
                // ID tokens are checked against this issuer (signature, iss, aud, expiry, nonce).
                .issuerUri(keycloak.issuerUri())
                .authorizationUri(browserFacing + "/auth") // the browser is redirected here
                .tokenUri(backchannel + "/token")          // server to server
                .jwkSetUri(backchannel + "/certs")         // server to server
                .userNameAttributeName("preferred_username")
                // Normally read from the discovery document; used to sign out of Keycloak too.
                .providerConfigurationMetadata(Map.of("end_session_endpoint", browserFacing + "/logout"))
                .build();
        return new InMemoryClientRegistrationRepository(registration);
    }

    /**
     * Verifies bearer tokens: signature (keys fetched from Keycloak over the internal
     * network), expiry, issuer, and that the token was issued <em>for this API</em> (the
     * {@code aud} claim). Without the audience check, a token Keycloak issued for some other
     * application in the realm would be accepted here too.
     */
    @Bean
    JwtDecoder jwtDecoder(HelpdeskProperties props) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withJwkSetUri(props.keycloak().backchannelUri() + "/protocol/openid-connect/certs")
                .build();
        decoder.setJwtValidator(tokenValidator(props.keycloak().issuerUri(), props.keycloak().apiAudience()));
        return decoder;
    }

    public static OAuth2TokenValidator<Jwt> tokenValidator(String issuer, String audience) {
        OAuth2TokenValidator<Jwt> audienceValidator = jwt -> jwt.getAudience() != null && jwt.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Wrong audience", null));
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), audienceValidator);
    }

    /** Bearer tokens: roles from the token's {@code roles} claim; the name is the username. */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> RolesClaim.authorities(jwt.getClaims()));
        converter.setPrincipalClaimName("preferred_username");
        return converter;
    }
}
