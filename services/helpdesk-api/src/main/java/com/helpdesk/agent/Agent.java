package com.helpdesk.agent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A staff member, as far as the helpdesk needs to know. Rows are created and refreshed by
 * {@link com.helpdesk.security.AgentDirectory} when someone signs in; JPA only reads them.
 */
@Entity
@Table(name = "agents")
public class Agent {

    @Id
    private Long id;

    @Column(nullable = false, updatable = false)
    private String subject;

    @Column(nullable = false)
    private String username;

    private String email;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(nullable = false)
    private boolean supervisor;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Agent() {} // for JPA

    public Long getId() { return id; }
    public String getSubject() { return subject; }
    public String getUsername() { return username; }
    public String getEmail() { return email; }
    public String getDisplayName() { return displayName; }
    public boolean isSupervisor() { return supervisor; }
    public Instant getCreatedAt() { return createdAt; }
}
