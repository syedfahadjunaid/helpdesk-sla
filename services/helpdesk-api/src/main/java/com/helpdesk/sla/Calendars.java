package com.helpdesk.sla;

import com.helpdesk.common.NotFoundException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Loads business calendars from the database and keeps them in memory.
 *
 * <p>Calendars are reference data maintained by Flyway migrations (the application login
 * can't change them), so caching them for the life of the process is safe. If calendars
 * ever become editable at runtime, this cache needs invalidating, and running timers
 * re-scheduling, when one changes.
 */
@Component
public class Calendars {

    /** A calendar as listed by the API. */
    public record CalendarSummary(long id, String name, String timeZone) {}

    private final JdbcClient jdbc;
    private final Map<Long, BusinessCalendar> cache = new ConcurrentHashMap<>();

    Calendars(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public BusinessCalendar get(long calendarId) {
        return cache.computeIfAbsent(calendarId, this::load);
    }

    public List<CalendarSummary> list() {
        return jdbc.sql("SELECT id, name, time_zone FROM business_calendars ORDER BY id")
                .query((rs, row) -> new CalendarSummary(rs.getLong("id"), rs.getString("name"), rs.getString("time_zone")))
                .list();
    }

    private BusinessCalendar load(long calendarId) {
        String zone = jdbc.sql("SELECT time_zone FROM business_calendars WHERE id = :id")
                .param("id", calendarId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new NotFoundException("Business calendar " + calendarId + " not found"));
        List<BusinessCalendar.Window> windows = jdbc.sql("""
                        SELECT day_of_week, opens_minute, closes_minute
                        FROM business_hours WHERE calendar_id = :id""")
                .param("id", calendarId)
                .query((rs, row) -> new BusinessCalendar.Window(
                        DayOfWeek.of(rs.getInt("day_of_week")), rs.getInt("opens_minute"), rs.getInt("closes_minute")))
                .list();
        List<LocalDate> holidays = jdbc.sql("SELECT holiday_date FROM holidays WHERE calendar_id = :id")
                .param("id", calendarId)
                .query((rs, row) -> rs.getObject("holiday_date", LocalDate.class))
                .list();
        return new BusinessCalendar(ZoneId.of(zone), windows, holidays);
    }
}
