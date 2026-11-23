package com.helpdesk.security;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.ClaimAccessor;
import org.springframework.stereotype.Component;

/**
 * Maps a verified token to an agent record, creating the record on first sign-in
 * ("just-in-time provisioning"). Staff accounts are managed in Keycloak; this table only
 * keeps what the helpdesk needs to refer to people (ids for assignment, names for display,
 * and whether they're a supervisor, for background jobs that have no token to look at).
 *
 * <p>Works for both ways of signing in: the browser's OIDC login (the principal is an
 * {@code OidcUser}) and bearer tokens (a {@code Jwt}); both are {@link ClaimAccessor}s.
 */
@Component
public class AgentDirectory {

    private final JdbcClient jdbc;
    private final Clock clock;

    AgentDirectory(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Actor resolve(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof ClaimAccessor claims)) {
            throw new AccessDeniedException("Not signed in with a Keycloak account");
        }
        Set<Role> roles = Role.fromAuthorities(authentication.getAuthorities());
        if (roles.isEmpty()) {
            throw new AccessDeniedException("Not a helpdesk staff account");
        }
        String subject = Objects.requireNonNull(claims.getClaimAsString("sub"), "token has no subject");
        String username = Objects.requireNonNullElse(claims.getClaimAsString("preferred_username"), subject);
        String displayName = Objects.requireNonNullElse(claims.getClaimAsString("name"), username);
        String email = claims.getClaimAsString("email");
        boolean supervisor = roles.contains(Role.SUPERVISOR) || roles.contains(Role.ADMIN);

        long agentId = upsert(subject, username, email, displayName, supervisor);
        return new Actor(agentId, username, displayName, roles);
    }

    /** What we store about an agent, to compare with the token. */
    private record AgentRow(long id, String username, String email, String displayName, boolean supervisor) {}

    /**
     * Ordinary requests only read. The row is written when it's missing (first sign-in) or
     * the token says something changed (new name, role granted or removed). The write is
     * an {@code INSERT ... ON CONFLICT} so two parallel first requests from the same new user
     * can't fail with a duplicate key.
     */
    private long upsert(String subject, String username, String email, String displayName, boolean supervisor) {
        AgentRow existing = jdbc.sql("""
                        SELECT id, username, email, display_name, supervisor FROM agents WHERE subject = :subject""")
                .param("subject", subject)
                .query((rs, row) -> new AgentRow(rs.getLong("id"), rs.getString("username"), rs.getString("email"),
                        rs.getString("display_name"), rs.getBoolean("supervisor")))
                .optional()
                .orElse(null);
        if (existing != null && existing.equals(new AgentRow(existing.id(), username, email, displayName, supervisor))) {
            return existing.id();
        }
        return jdbc.sql("""
                        INSERT INTO agents (subject, username, email, display_name, supervisor, created_at)
                        VALUES (:subject, :username, :email, :displayName, :supervisor, :now)
                        ON CONFLICT (subject) DO UPDATE
                            SET username = EXCLUDED.username, email = EXCLUDED.email,
                                display_name = EXCLUDED.display_name, supervisor = EXCLUDED.supervisor
                        RETURNING id""")
                .param("subject", subject)
                .param("username", username)
                .param("email", email)
                .param("displayName", displayName)
                .param("supervisor", supervisor)
                .param("now", Timestamp.from(clock.instant()))
                .query(Long.class)
                .single();
    }
}
