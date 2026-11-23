package com.helpdesk.customer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Someone who asks for help. Customers never sign in: they're known by their email address. */
@Entity
@Table(name = "customers")
public class Customer {

    @Id
    private Long id;

    /** Always lower case (a database CHECK enforces it), so lookups are case-insensitive. */
    @Column(nullable = false, updatable = false)
    private String email;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Customer() {} // for JPA

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getDisplayName() { return displayName; }
    public Instant getCreatedAt() { return createdAt; }

    /** "Jane Doe" if we know the name, else the address. */
    public String getNameOrEmail() {
        return displayName == null || displayName.isBlank() ? email : displayName;
    }
}
