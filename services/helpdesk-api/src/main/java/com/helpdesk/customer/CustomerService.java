package com.helpdesk.customer;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Locale;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerService {

    private final CustomerRepository customers;
    private final JdbcClient jdbc;
    private final Clock clock;

    CustomerService(CustomerRepository customers, JdbcClient jdbc, Clock clock) {
        this.customers = customers;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * The customer with this address, created on first contact. {@code ON CONFLICT DO
     * NOTHING} makes two emails from a new customer, processed at the same moment, safe.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Customer findOrCreate(String email, String displayName) {
        String normalised = email.trim().toLowerCase(Locale.ROOT);
        jdbc.sql("""
                        INSERT INTO customers (email, display_name, created_at) VALUES (:email, :name, :now)
                        ON CONFLICT (email) DO NOTHING""")
                .param("email", normalised)
                .param("name", displayName == null || displayName.isBlank() ? null : displayName.trim())
                .param("now", Timestamp.from(clock.instant()))
                .update();
        return customers.findByEmail(normalised).orElseThrow();
    }
}
