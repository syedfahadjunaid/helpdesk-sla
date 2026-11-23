package com.helpdesk.security;

import java.util.Set;

/**
 * The signed-in staff member making a request: their agent record plus the roles from
 * their (verified) token. Controllers receive it as a method parameter
 * (see {@link ActorArgumentResolver}), so it can never come from the request body.
 */
public record Actor(long agentId, String username, String displayName, Set<Role> roles) {

    public Actor {
        roles = Set.copyOf(roles);
    }

    /** Supervisors and admins may act on any ticket. */
    public boolean isSupervisor() {
        return roles.contains(Role.SUPERVISOR) || roles.contains(Role.ADMIN);
    }

    public boolean isAdmin() {
        return roles.contains(Role.ADMIN);
    }
}
