package com.helpdesk.sla;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * When the SLA clock is allowed to tick: weekly opening hours in a time zone, minus holidays.
 *
 * <p>This is plain Java with no Spring or database code, so the tricky time maths can be
 * unit-tested exhaustively (weekends, holidays, daylight-saving changes, tickets that arrive
 * at night). Everything is computed in {@link Instant}s; local dates and times are only used
 * to find where each day's opening hours begin and end in the calendar's zone.
 *
 * <p>Example: with Monday-Friday 09:00-17:00, a ticket that arrives on Friday at 16:00 with a
 * 2-hour target is due on Monday at 10:00 (one hour on Friday, one on Monday).
 */
public final class BusinessCalendar {

    /** Searching further than this means the calendar (almost) never opens: a configuration error. */
    private static final int MAX_DAYS_SEARCHED = 5 * 366;

    private static final int MINUTES_PER_DAY = 24 * 60;

    /**
     * Opening hours on one weekday, as minutes after local midnight.
     *
     * @param opensMinute 0-1439, e.g. 540 for 09:00
     * @param closesMinute 1-1440, e.g. 1020 for 17:00, or 1440 for "until midnight"
     */
    public record Window(DayOfWeek day, int opensMinute, int closesMinute) {
        public Window {
            Objects.requireNonNull(day, "day");
            if (opensMinute < 0 || closesMinute > MINUTES_PER_DAY || opensMinute >= closesMinute) {
                throw new IllegalArgumentException("Invalid opening hours: " + opensMinute + "-" + closesMinute);
            }
        }
    }

    /** One stretch of business time, as absolute instants. */
    private record Interval(Instant start, Instant end) {}

    private final ZoneId zone;
    private final Map<DayOfWeek, List<Window>> windowsByDay = new EnumMap<>(DayOfWeek.class);
    private final Set<LocalDate> holidays;

    public BusinessCalendar(ZoneId zone, Collection<Window> windows, Collection<LocalDate> holidays) {
        this.zone = Objects.requireNonNull(zone, "zone");
        if (windows.isEmpty()) {
            throw new IllegalArgumentException("A business calendar needs at least one opening window");
        }
        for (Window window : windows) {
            windowsByDay.computeIfAbsent(window.day(), day -> new ArrayList<>()).add(window);
        }
        for (List<Window> dayWindows : windowsByDay.values()) {
            dayWindows.sort(Comparator.comparingInt(Window::opensMinute));
            for (int i = 1; i < dayWindows.size(); i++) {
                if (dayWindows.get(i).opensMinute() < dayWindows.get(i - 1).closesMinute()) {
                    throw new IllegalArgumentException("Overlapping opening hours on " + dayWindows.get(i).day());
                }
            }
        }
        this.holidays = Set.copyOf(holidays);
    }

    /** Open all day, every day. */
    public static BusinessCalendar twentyFourSeven(ZoneId zone) {
        List<Window> allDay = new ArrayList<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            allDay.add(new Window(day, 0, MINUTES_PER_DAY));
        }
        return new BusinessCalendar(zone, allDay, Set.of());
    }

    public ZoneId zone() {
        return zone;
    }

    /** How much business time passes between two instants (zero if {@code to} isn't after {@code from}). */
    public Duration businessTimeBetween(Instant from, Instant to) {
        if (!to.isAfter(from)) {
            return Duration.ZERO;
        }
        Duration total = Duration.ZERO;
        LocalDate lastDay = to.atZone(zone).toLocalDate();
        for (LocalDate day = from.atZone(zone).toLocalDate(); !day.isAfter(lastDay); day = day.plusDays(1)) {
            for (Interval open : openIntervals(day)) {
                Instant start = later(open.start(), from);
                Instant end = earlier(open.end(), to);
                if (end.isAfter(start)) {
                    total = total.plus(Duration.between(start, end));
                }
            }
        }
        return total;
    }

    /**
     * The instant at which {@code amount} of business time has passed, counting from {@code start}.
     * Outside opening hours the count simply waits for the next opening.
     */
    public Instant addBusinessTime(Instant start, Duration amount) {
        if (amount.isNegative()) {
            throw new IllegalArgumentException("Can't add negative business time: " + amount);
        }
        if (amount.isZero()) {
            return start;
        }
        Duration remaining = amount;
        LocalDate day = start.atZone(zone).toLocalDate();
        for (int searched = 0; searched < MAX_DAYS_SEARCHED; searched++, day = day.plusDays(1)) {
            for (Interval open : openIntervals(day)) {
                Instant from = later(open.start(), start);
                if (!open.end().isAfter(from)) {
                    continue; // this window is already over
                }
                Duration available = Duration.between(from, open.end());
                if (available.compareTo(remaining) >= 0) {
                    return from.plus(remaining);
                }
                remaining = remaining.minus(available);
            }
        }
        throw new IllegalStateException("No business hours found within " + MAX_DAYS_SEARCHED + " days");
    }

    /** Is the clock ticking at this instant? */
    public boolean isBusinessTime(Instant instant) {
        LocalDate day = instant.atZone(zone).toLocalDate();
        for (Interval open : openIntervals(day)) {
            if (!instant.isBefore(open.start()) && instant.isBefore(open.end())) {
                return true;
            }
        }
        return false;
    }

    /** The day's opening hours as instants; none on holidays and closed weekdays. */
    private List<Interval> openIntervals(LocalDate day) {
        if (holidays.contains(day)) {
            return List.of();
        }
        List<Interval> intervals = new ArrayList<>();
        for (Window window : windowsByDay.getOrDefault(day.getDayOfWeek(), List.of())) {
            intervals.add(new Interval(instantAt(day, window.opensMinute()), instantAt(day, window.closesMinute())));
        }
        return intervals;
    }

    /**
     * The instant of a local wall-clock time. On a daylight-saving change, a time that doesn't
     * exist (the hour skipped in spring) moves forward, and a time that happens twice (autumn)
     * takes the earlier one; that's what {@code LocalDateTime.atZone} does.
     */
    private Instant instantAt(LocalDate day, int minuteOfDay) {
        if (minuteOfDay == MINUTES_PER_DAY) {
            return day.plusDays(1).atStartOfDay(zone).toInstant();
        }
        return day.atTime(LocalTime.ofSecondOfDay(minuteOfDay * 60L)).atZone(zone).toInstant();
    }

    private static Instant later(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static Instant earlier(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
