package com.helpdesk;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A clock tests can set and move forward by hand, replacing the system clock in the
 * application (see {@link IntegrationTest}). SLA maths is all about elapsed business time,
 * and this makes "two hours later" instant and exact, with no sleeping.
 *
 * <p>Thread-safe: the app's background threads (message listeners) read it too.
 */
public class MutableClock extends Clock {

    /** Monday 23 November 2026, 10:00 in London (= UTC in winter): inside office hours. */
    public static final Instant MONDAY_10AM_LONDON = Instant.parse("2026-11-23T10:00:00Z");

    private final AtomicReference<Instant> now = new AtomicReference<>(MONDAY_10AM_LONDON);

    public void set(Instant instant) {
        now.set(instant);
    }

    public Instant advance(Duration duration) {
        return now.updateAndGet(current -> current.plus(duration));
    }

    @Override
    public Instant instant() {
        return now.get();
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("The app works in UTC instants");
    }
}
