package com.helpdesk.security;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;

/**
 * Keycloak realm roles that mean something here. Spring Security sees them as authorities
 * {@code ROLE_AGENT}, {@code ROLE_SUPERVISOR} and {@code ROLE_ADMIN}, so
 * {@code hasRole('ADMIN')} works in {@code @PreAuthorize}.
 */
public enum Role {
    /** Works tickets in the queues they belong to. */
    AGENT,
    /** Also assigns anyone's tickets, works any ticket, and is told about SLA breaches. */
    SUPERVISOR,
    /** Also manages SLA policies and queue membership. */
    ADMIN;

    public static final String PREFIX = "ROLE_";

    public String authority() {
        return PREFIX + name();
    }

    /** The roles among some granted authorities (unknown ones are ignored). */
    public static Set<Role> fromAuthorities(Collection<? extends GrantedAuthority> authorities) {
        Set<Role> roles = EnumSet.noneOf(Role.class);
        for (GrantedAuthority authority : authorities) {
            for (Role role : values()) {
                if (role.authority().equals(authority.getAuthority())) {
                    roles.add(role);
                }
            }
        }
        return roles;
    }
}
