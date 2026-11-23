package com.helpdesk.sla;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Business-hours maths, tested with plain JUnit (no Spring, no database). Every expectation
 * is written as London wall-clock time, the way a person would check it by hand.
 */
class BusinessCalendarTests {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    /** Monday-Friday 09:00-17:00 London, closed on Christmas Day and the Boxing Day substitute. */
    private static final BusinessCalendar OFFICE = new BusinessCalendar(LONDON, weekdays(540, 1020),
            Set.of(LocalDate.parse("2026-12-25"), LocalDate.parse("2026-12-28")));

    @ParameterizedTest(name = "{0} + {1} business minutes = {2}")
    @CsvSource({
            // within one day
            "2026-11-23T10:00, 120, 2026-11-23T12:00",
            // runs past closing time into the next morning
            "2026-11-23T16:00, 120, 2026-11-24T10:00",
            // Friday afternoon to Monday morning: the weekend doesn't count
            "2026-11-27T16:00, 120, 2026-11-30T10:00",
            // arrives before opening: the clock starts at 09:00
            "2026-11-23T07:00, 60, 2026-11-23T10:00",
            // arrives on a Saturday
            "2026-11-28T12:00, 30, 2026-11-30T09:30",
            // arrives after closing
            "2026-11-23T18:30, 15, 2026-11-24T09:15",
            // exactly a full day of work
            "2026-11-23T09:00, 480, 2026-11-23T17:00",
            // Christmas Eve: Christmas Day, the weekend and the substitute Monday are all closed
            "2026-12-24T16:00, 120, 2026-12-29T10:00",
    })
    void addsBusinessTime(String start, int minutes, String expected) {
        Instant due = OFFICE.addBusinessTime(london(start), Duration.ofMinutes(minutes));
        assertThat(due).isEqualTo(london(expected));
    }

    @Test
    void officeHoursStayAtNineToFiveLocalTimeAcrossTheClockChange() {
        // Clocks go forward on Sunday 29 March 2026: 16:00 on Friday is GMT, 10:00 on Monday is BST.
        Instant due = OFFICE.addBusinessTime(london("2026-03-27T16:00"), Duration.ofHours(2));

        assertThat(due).isEqualTo(london("2026-03-30T10:00"));
        assertThat(due).isEqualTo(Instant.parse("2026-03-30T09:00:00Z"));
    }

    @Test
    void aTwentyFourSevenClockCountsRealElapsedTimeOverTheShortDay() {
        BusinessCalendar always = BusinessCalendar.twentyFourSeven(LONDON);
        // Saturday 23:00 GMT + 4 hours of real time. That night lasts an hour less, so it's 04:00 BST.
        Instant due = always.addBusinessTime(london("2027-03-27T23:00"), Duration.ofHours(4));

        assertThat(due).isEqualTo(london("2027-03-28T04:00"));
        assertThat(Duration.between(london("2027-03-27T23:00"), due)).isEqualTo(Duration.ofHours(4));
    }

    @Test
    void measuresBusinessTimeBetweenTwoInstants() {
        assertThat(OFFICE.businessTimeBetween(london("2026-11-27T16:00"), london("2026-11-30T10:00")))
                .isEqualTo(Duration.ofHours(2));
        assertThat(OFFICE.businessTimeBetween(london("2026-11-28T08:00"), london("2026-11-29T20:00")))
                .as("a whole weekend").isZero();
        assertThat(OFFICE.businessTimeBetween(london("2026-11-24T10:00"), london("2026-11-23T10:00")))
                .as("backwards").isZero();
    }

    @Test
    void addingAndMeasuringAgree() {
        // Property check: for many starts and amounts, "add" and "between" are inverses.
        Instant start = london("2026-11-20T00:00");
        for (int hour = 0; hour < 24 * 10; hour += 5) {
            for (int minutes : List.of(1, 59, 61, 480, 481, 2400)) {
                Instant from = start.plus(Duration.ofHours(hour));
                Instant due = OFFICE.addBusinessTime(from, Duration.ofMinutes(minutes));
                assertThat(OFFICE.businessTimeBetween(from, due)).isEqualTo(Duration.ofMinutes(minutes));
            }
        }
    }

    @Test
    void supportsSeveralWindowsPerDay() {
        List<BusinessCalendar.Window> withLunch = new ArrayList<>();
        for (DayOfWeek day : List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY)) {
            withLunch.add(new BusinessCalendar.Window(day, 540, 720));   // 09:00-12:00
            withLunch.add(new BusinessCalendar.Window(day, 780, 1020));  // 13:00-17:00
        }
        BusinessCalendar calendar = new BusinessCalendar(LONDON, withLunch, Set.of());

        assertThat(calendar.addBusinessTime(london("2026-11-23T11:00"), Duration.ofHours(2)))
                .isEqualTo(london("2026-11-23T14:00"));
        assertThat(calendar.isBusinessTime(london("2026-11-23T12:30"))).isFalse();
    }

    @Test
    void knowsWhenItIsOpen() {
        assertThat(OFFICE.isBusinessTime(london("2026-11-23T09:00"))).isTrue();
        assertThat(OFFICE.isBusinessTime(london("2026-11-23T17:00"))).as("closing time is closed").isFalse();
        assertThat(OFFICE.isBusinessTime(london("2026-11-28T12:00"))).as("Saturday").isFalse();
        assertThat(OFFICE.isBusinessTime(london("2026-12-25T12:00"))).as("holiday").isFalse();
    }

    @Test
    void rejectsBrokenCalendars() {
        assertThatThrownBy(() -> new BusinessCalendar(LONDON, List.of(), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BusinessCalendar.Window(DayOfWeek.MONDAY, 600, 600))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BusinessCalendar(LONDON, List.of(
                new BusinessCalendar.Window(DayOfWeek.MONDAY, 540, 720),
                new BusinessCalendar.Window(DayOfWeek.MONDAY, 700, 800)), Set.of()))
                .hasMessageContaining("Overlapping");
        assertThatThrownBy(() -> OFFICE.addBusinessTime(Instant.now(), Duration.ofMinutes(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static List<BusinessCalendar.Window> weekdays(int opens, int closes) {
        List<BusinessCalendar.Window> windows = new ArrayList<>();
        for (DayOfWeek day : List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY)) {
            windows.add(new BusinessCalendar.Window(day, opens, closes));
        }
        return windows;
    }

    static Instant london(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(LONDON).toInstant();
    }
}
