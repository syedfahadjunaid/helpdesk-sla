package com.helpdesk.sla;

import static com.helpdesk.sla.BusinessCalendarTests.london;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The SLA clock's arithmetic: start, pause, resume, warn, breach, retarget. Plain JUnit, exact instants. */
class SlaTimerTests {

    private static final BusinessCalendar ALWAYS = BusinessCalendar.twentyFourSeven(ZoneId.of("UTC"));
    private static final BusinessCalendar OFFICE = new BusinessCalendar(ZoneId.of("Europe/London"),
            BusinessCalendarTests.weekdays(540, 1020), Set.of());
    private static final SlaTarget ONE_HOUR = new SlaTarget(60, 75, 1);
    private static final Instant TEN_AM = Instant.parse("2026-11-23T10:00:00Z");

    @Test
    void aNewTimerKnowsWhenItWillWarnAndWhenItWillBreach() {
        SlaTimer timer = SlaTimer.start(1, SlaMetric.FIRST_RESPONSE, ONE_HOUR, ALWAYS, TEN_AM);

        assertThat(timer.getClockState()).isEqualTo(ClockState.RUNNING);
        assertThat(timer.getWarnAt()).isEqualTo(TEN_AM.plus(Duration.ofMinutes(45)));  // 75% of an hour
        assertThat(timer.getDueAt()).isEqualTo(TEN_AM.plus(Duration.ofMinutes(60)));
        assertThat(timer.isWarningDue(TEN_AM.plus(Duration.ofMinutes(44)))).isFalse();
        assertThat(timer.isWarningDue(TEN_AM.plus(Duration.ofMinutes(45)))).isTrue();
        assertThat(timer.isBreachDue(TEN_AM.plus(Duration.ofMinutes(60)))).isTrue();
    }

    @Test
    void pausingStopsTheClockAndResumingPushesTheDeadlineBack() {
        SlaTimer timer = SlaTimer.start(1, SlaMetric.RESOLUTION, ONE_HOUR, ALWAYS, TEN_AM);

        timer.pause(TEN_AM.plus(Duration.ofMinutes(20)), ALWAYS);       // 20 minutes used
        assertThat(timer.getClockState()).isEqualTo(ClockState.PAUSED);
        assertThat(timer.getDueAt()).isNull();
        assertThat(timer.isBreachDue(TEN_AM.plus(Duration.ofDays(3)))).as("a paused clock never breaches").isFalse();

        Instant elevenAm = TEN_AM.plus(Duration.ofHours(1));              // waited 40 minutes for the customer
        timer.resume(elevenAm, ALWAYS);

        assertThat(timer.elapsed(elevenAm, ALWAYS)).isEqualTo(Duration.ofMinutes(20));
        assertThat(timer.getDueAt()).isEqualTo(elevenAm.plus(Duration.ofMinutes(40)));
        assertThat(timer.getWarnAt()).isEqualTo(elevenAm.plus(Duration.ofMinutes(25)));
    }

    @Test
    void aBreachIsRecordedAtTheDeadlineNotWhenTheWorkerNoticed() {
        SlaTimer timer = SlaTimer.start(1, SlaMetric.FIRST_RESPONSE, ONE_HOUR, ALWAYS, TEN_AM);
        Instant workerWasDownUntil = TEN_AM.plus(Duration.ofHours(5));

        assertThat(timer.isBreachDue(workerWasDownUntil)).isTrue();
        timer.recordBreach();

        assertThat(timer.getBreachedAt()).isEqualTo(TEN_AM.plus(Duration.ofHours(1)));
        assertThat(timer.isBreachDue(workerWasDownUntil)).as("never twice").isFalse();
        assertThat(timer.isWarningDue(workerWasDownUntil)).as("no warning after a breach").isFalse();
    }

    @Test
    void aWarningIsSentOnceEvenIfTheClockPausesAndResumes() {
        SlaTimer timer = SlaTimer.start(1, SlaMetric.RESOLUTION, ONE_HOUR, ALWAYS, TEN_AM);
        timer.recordWarning(TEN_AM.plus(Duration.ofMinutes(46)));

        timer.pause(TEN_AM.plus(Duration.ofMinutes(50)), ALWAYS);
        timer.resume(TEN_AM.plus(Duration.ofHours(2)), ALWAYS);

        assertThat(timer.getWarnAt()).isNull();
        assertThat(timer.isWarningDue(TEN_AM.plus(Duration.ofHours(3)))).isFalse();
        assertThat(timer.getDueAt()).isEqualTo(TEN_AM.plus(Duration.ofHours(2)).plus(Duration.ofMinutes(10)));
    }

    @Test
    void meetingTheTargetFreezesTheClock() {
        SlaTimer timer = SlaTimer.start(1, SlaMetric.FIRST_RESPONSE, ONE_HOUR, ALWAYS, TEN_AM);
        Instant replied = TEN_AM.plus(Duration.ofMinutes(12));

        timer.meet(replied, ALWAYS);

        assertThat(timer.getClockState()).isEqualTo(ClockState.STOPPED);
        assertThat(timer.getMetAt()).isEqualTo(replied);
        assertThat(timer.elapsed(TEN_AM.plus(Duration.ofDays(1)), ALWAYS)).isEqualTo(Duration.ofMinutes(12));
        assertThat(timer.isBreachDue(TEN_AM.plus(Duration.ofDays(1)))).isFalse();
    }

    @Test
    void reopeningContinuesFromWhereTheClockStopped() {
        SlaTimer timer = SlaTimer.start(1, SlaMetric.RESOLUTION, ONE_HOUR, ALWAYS, TEN_AM);
        timer.meet(TEN_AM.plus(Duration.ofMinutes(30)), ALWAYS);

        Instant reopened = TEN_AM.plus(Duration.ofDays(1));
        timer.reopen(reopened, ALWAYS);

        assertThat(timer.getMetAt()).isNull();
        assertThat(timer.getClockState()).isEqualTo(ClockState.RUNNING);
        assertThat(timer.getDueAt()).isEqualTo(reopened.plus(Duration.ofMinutes(30)));
    }

    @Test
    void raisingThePriorityShortensTheDeadlineKeepingTimeAlreadyUsed() {
        SlaTimer timer = SlaTimer.start(1, SlaMetric.FIRST_RESPONSE, new SlaTarget(240, 75, 1), ALWAYS, TEN_AM);
        Instant later = TEN_AM.plus(Duration.ofMinutes(20));

        timer.retarget(new SlaTarget(30, 50, 2), ALWAYS, ALWAYS, later);

        assertThat(timer.getTargetMinutes()).isEqualTo(30);
        assertThat(timer.getDueAt()).isEqualTo(later.plus(Duration.ofMinutes(10)));
        assertThat(timer.getWarnAt()).as("50% of 30 minutes was already used").isEqualTo(later);
    }

    @Test
    void aTargetAlreadyExceededAfterRetargetingIsDueImmediately() {
        SlaTimer timer = SlaTimer.start(1, SlaMetric.FIRST_RESPONSE, new SlaTarget(240, 75, 1), ALWAYS, TEN_AM);
        Instant later = TEN_AM.plus(Duration.ofMinutes(45));

        timer.retarget(new SlaTarget(30, 75, 2), ALWAYS, ALWAYS, later);

        assertThat(timer.getDueAt()).isEqualTo(later);
        assertThat(timer.isBreachDue(later)).isTrue();
    }

    @Test
    void onOfficeHoursAFridayEveningTicketIsDueOnMondayMorning() {
        SlaTimer timer = SlaTimer.start(1, SlaMetric.FIRST_RESPONSE, ONE_HOUR, OFFICE, london("2026-11-27T16:30"));

        assertThat(timer.getDueAt()).isEqualTo(london("2026-11-30T09:30"));
        assertThat(timer.remaining(london("2026-11-28T12:00"), OFFICE))
                .as("nothing is used up over the weekend").isEqualTo(Duration.ofMinutes(30));
    }
}
