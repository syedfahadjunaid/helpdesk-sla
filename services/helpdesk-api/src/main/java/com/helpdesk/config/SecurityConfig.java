package com.helpdesk.config;

import com.helpdesk.security.Role;
import com.helpdesk.security.RolesClaim;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.util.matcher.RequestMatcher;
import tools.jackson.databind.json.JsonMapper;

/**
 * All HTTP security rules, in one place. Two ways in:
 *
 * <ol>
 *   <li><b>Browsers</b> (the web app): the app is its own backend-for-frontend. It signs the
 *       user in with Keycloak (authorization code + PKCE, as a confidential client) and keeps
 *       the result in a server-side session (Spring Session, stored in Postgres so every
 *       instance shares it). The browser only gets the HttpOnly session cookie; no token ever
 *       reaches JavaScript. Because browsers send cookies automatically, state-changing
 *       requests also need a CSRF token.</li>
 *   <li><b>Scripts and integrations</b>: a Keycloak access token in the
 *       {@code Authorization: Bearer} header, verified on every request (stateless, no
 *       cookies, so no CSRF).</li>
 * </ol>
 */
@Configuration
@EnableMethodSecurity // @PreAuthorize on controller methods
public class SecurityConfig {

    /** Readable by the SPA. Not "XSRF-TOKEN": cookies are shared by every port on localhost. */
    public static final String CSRF_COOKIE = "HELPDESK-XSRF-TOKEN";

    private static final String[] STAFF = {Role.AGENT.name(), Role.SUPERVISOR.name(), Role.ADMIN.name()};

    /** Requests to the API or the WebSocket endpoint that carry a bearer token. */
    private static final RequestMatcher BEARER_TOKEN_REQUEST = SecurityConfig::isBearerTokenRequest;

    @Bean
    @Order(1)
    SecurityFilterChain bearerTokenSecurity(HttpSecurity http, JwtAuthenticationConverter tokenRoles) throws Exception {
        http
                .securityMatcher(BEARER_TOKEN_REQUEST)
                .authorizeHttpRequests(auth -> auth.anyRequest().hasAnyRole(STAFF))
                .oauth2ResourceServer(server -> server.jwt(jwt -> jwt.jwtAuthenticationConverter(tokenRoles)))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable); // no cookies are involved, so CSRF can't happen
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain browserSecurity(HttpSecurity http, ClientRegistrationRepository registrations,
            HelpdeskProperties props, JsonMapper json) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/**", "/ws", "/ws/**").hasAnyRole(STAFF)
                        // Nothing else lives here: the SPA itself is served by nginx.
                        .anyRequest().denyAll())
                .oauth2Login(login -> login
                        // The SPA starts sign-in by navigating here; no generated login page.
                        .loginPage("/oauth2/authorization/" + KeycloakClientConfig.REGISTRATION_ID)
                        .defaultSuccessUrl("/", true)
                        .failureUrl("/?signin=failed")
                        .userInfoEndpoint(userInfo -> userInfo.userAuthoritiesMapper(rolesFromIdToken())))
                // An API call without a session gets 401 (not a redirect to Keycloak, which
                // fetch() would follow invisibly); the SPA then starts a sign-in itself.
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .csrf(csrf -> csrf
                        .spa() // cookie-based token, read by JavaScript and sent back in a header
                        .csrfTokenRepository(csrfCookieRepository()))
                .logout(logout -> logout
                        .logoutUrl("/logout") // POST only, with the CSRF header
                        .logoutSuccessHandler(keycloakLogout(registrations, props, json)));
        return http.build();
    }

    private static boolean isBearerTokenRequest(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
                && (path.startsWith("/api/") || path.equals("/ws") || path.startsWith("/ws/"));
    }

    /** Browser sign-in: the roles come from the ID token's {@code roles} claim (see the realm's protocol mapper). */
    private static GrantedAuthoritiesMapper rolesFromIdToken() {
        return authorities -> {
            Set<GrantedAuthority> mapped = new HashSet<>();
            for (GrantedAuthority authority : authorities) {
                if (authority instanceof OidcUserAuthority oidc) {
                    mapped.addAll(RolesClaim.authorities(oidc.getIdToken().getClaims()));
                }
            }
            return mapped;
        };
    }

    private static CookieCsrfTokenRepository csrfCookieRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieName(CSRF_COOKIE);
        repository.setCookieCustomizer(cookie -> cookie.sameSite("Strict"));
        return repository;
    }

    /**
     * Signing out ends the app session and then the Keycloak session (OpenID Connect
     * RP-initiated logout), otherwise the next sign-in would succeed without a password.
     *
     * <p>Spring's handler would answer with a redirect, but the SPA calls {@code /logout}
     * with fetch() (it has to, to send the CSRF header), and fetch() can't hand a cross-site
     * redirect to the browser. So the Keycloak logout URL is returned as JSON, and the SPA
     * navigates there.
     */
    private static LogoutSuccessHandler keycloakLogout(ClientRegistrationRepository registrations,
            HelpdeskProperties props, JsonMapper json) {
        var handler = new OidcClientInitiatedLogoutSuccessHandler(registrations);
        handler.setPostLogoutRedirectUri(props.publicUrl() + "/");
        handler.setRedirectStrategy((request, response, url) -> {
            response.setStatus(HttpStatus.OK.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            json.writeValue(response.getOutputStream(), Map.of("logoutUrl", url));
        });
        return handler;
    }
}
