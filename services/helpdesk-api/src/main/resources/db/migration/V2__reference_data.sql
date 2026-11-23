-- Reference data every installation starts with: calendars, SLA policies and queues.
-- (Demo people and tickets are not here: scripts/seed-demo.ps1 creates them through the API.)

INSERT INTO business_calendars (name, time_zone) VALUES
    ('Office hours (London)', 'Europe/London'),
    ('24x7', 'UTC');

-- Office hours: Monday to Friday, 09:00-17:00 London time (540 = 09:00, 1020 = 17:00).
INSERT INTO business_hours (calendar_id, day_of_week, opens_minute, closes_minute)
SELECT c.id, d, 540, 1020
FROM business_calendars c, generate_series(1, 5) AS d
WHERE c.name = 'Office hours (London)';

-- 24x7: every day, all day.
INSERT INTO business_hours (calendar_id, day_of_week, opens_minute, closes_minute)
SELECT c.id, d, 0, 1440
FROM business_calendars c, generate_series(1, 7) AS d
WHERE c.name = '24x7';

-- England and Wales bank holidays (the clock doesn't tick on these days).
INSERT INTO holidays (calendar_id, holiday_date, name)
SELECT c.id, h.day, h.name
FROM business_calendars c,
     (VALUES (DATE '2026-12-25', 'Christmas Day'),
             (DATE '2026-12-28', 'Boxing Day (substitute day)'),
             (DATE '2027-01-01', 'New Year''s Day'),
             (DATE '2027-03-26', 'Good Friday'),
             (DATE '2027-03-29', 'Easter Monday'),
             (DATE '2027-05-03', 'Early May bank holiday'),
             (DATE '2027-05-31', 'Spring bank holiday'),
             (DATE '2027-08-30', 'Summer bank holiday'),
             (DATE '2027-12-27', 'Christmas Day (substitute day)'),
             (DATE '2027-12-28', 'Boxing Day (substitute day)')) AS h (day, name)
WHERE c.name = 'Office hours (London)';

-- Targets are business minutes on the policy's calendar. Urgent tickets run on the 24x7
-- calendar; everything else only counts office hours.
INSERT INTO sla_policies (priority, first_response_minutes, resolution_minutes, warning_percent, calendar_id, updated_at)
SELECT p.priority, p.first_response, p.resolution, 75, c.id, now()
FROM (VALUES ('URGENT', 30, 240, '24x7'),
             ('HIGH', 60, 480, 'Office hours (London)'),
             ('NORMAL', 240, 1440, 'Office hours (London)'),
             ('LOW', 480, 2400, 'Office hours (London)')) AS p (priority, first_response, resolution, calendar)
JOIN business_calendars c ON c.name = p.calendar;

INSERT INTO queues (key, name, inbound_address, is_default) VALUES
    ('general', 'General support', 'support@helpdesk.test', true),
    ('billing', 'Billing', 'billing@helpdesk.test', false);
