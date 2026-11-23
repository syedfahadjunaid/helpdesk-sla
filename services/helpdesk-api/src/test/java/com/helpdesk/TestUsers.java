package com.helpdesk;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;

import com.helpdesk.security.Role;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Made-up staff accounts. Every call creates a brand-new person (random suffix), so tests
 * never share agents, memberships or notifications and can run in any order.
 */
public final class TestUsers {

    /** A Keycloak user as far as the app can tell from a token. */
    public record User(String subject, String username, String displayName, String email, Set<Role> roles) {

        /** Signed in through the browser flow (an OIDC session), for MockMvc requests. */
        public RequestPostProcessor session() {
            return oidcLogin()
                    .idToken(token -> token
                            .subject(subject)
                            .claim("preferred_username", username)
                            .claim("name", displayName)
                            .claim("email", email))
                    .authorities(roles.stream()
                            .<GrantedAuthority>map(role -> new SimpleGrantedAuthority(role.authority()))
                            .toList());
        }

        public String bearerToken() {
            return TestTokens.issue(this);
        }
    }

    private TestUsers() {}

    public static User agent(String name) {
        return user(name, Set.of(Role.AGENT));
    }

    public static User supervisor(String name) {
        return user(name, Set.of(Role.AGENT, Role.SUPERVISOR));
    }

    public static User admin(String name) {
        return user(name, Set.of(Role.AGENT, Role.SUPERVISOR, Role.ADMIN));
    }

    /** Signed in, but not helpdesk staff (no known role). */
    public static User outsider(String name) {
        return user(name, Set.of());
    }

    private static User user(String name, Set<Role> roles) {
        String suffix = TestTokens.randomSuffix();
        String username = name + "-" + suffix;
        return new User("sub-" + username, username, capitalise(name) + " " + suffix, username + "@staff.test",
                roles);
    }

    private static String capitalise(String name) {
        return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
