package com.helpdesk.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * All code asks this {@link Clock} for "now" instead of calling {@code Instant.now()}.
 * SLA maths is all about time, so tests replace it with a clock they can move forward by
 * hand (see {@code MutableClock} in the tests) and check deadlines to the second.
 */
@Configuration
public class TimeConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
