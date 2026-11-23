package com.helpdesk.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Reads the realm roles Keycloak puts in a token. The realm adds them to both the ID token
 * (browser sign-in) and the access token (scripts) as a top-level {@code roles} claim;
 * access tokens also carry Keycloak's standard {@code realm_access.roles}, read as a fallback.
 * Only roles this app knows become authorities.
 */
public final class RolesClaim {

    private RolesClaim() {}

    public static List<GrantedAuthority> authorities(Map<String, Object> claims) {
        List<String> names = new ArrayList<>(stringList(claims.get("roles")));
        if (claims.get("realm_access") instanceof Map<?, ?> realmAccess) {
            names.addAll(stringList(realmAccess.get("roles")));
        }
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (Role role : Role.values()) {
            if (names.contains(role.name())) {
                authorities.add(new SimpleGrantedAuthority(role.authority()));
            }
        }
        return authorities;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof Collection<?> values)) {
            return List.of();
        }
        List<String> strings = new ArrayList<>();
        for (Object item : values) {
            if (item instanceof String s) {
                strings.add(s);
            }
        }
        return strings;
    }
}
