package com.helpdesk.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.helpdesk.sla.ClockState;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/** The lifecycle state machine, checked move by move. */
class TicketStatusTests {

    @ParameterizedTest(name = "{0} --{1}--> {2}")
    @CsvSource({
            "NEW, ASSIGN, OPEN",
            "NEW, CUSTOMER_REPLIED, NEW",
            "OPEN, ASSIGN, OPEN",
            "OPEN, WAIT_ON_CUSTOMER, WAITING_ON_CUSTOMER",
            "OPEN, RESOLVE, RESOLVED",
            "OPEN, CUSTOMER_REPLIED, OPEN",
            "WAITING_ON_CUSTOMER, CUSTOMER_REPLIED, OPEN",
            "WAITING_ON_CUSTOMER, RESUME, OPEN",
            "WAITING_ON_CUSTOMER, RESOLVE, RESOLVED",
            "WAITING_ON_CUSTOMER, ASSIGN, WAITING_ON_CUSTOMER",
            "RESOLVED, REOPEN, OPEN",
            "RESOLVED, CUSTOMER_REPLIED, OPEN",
            "RESOLVED, CLOSE, CLOSED",
            "RESOLVED, AUTO_CLOSE, CLOSED",
    })
    void allowedMoves(TicketStatus from, TicketAction action, TicketStatus to) {
        assertThat(from.allows(action)).isTrue();
        assertThat(from.after(action)).isEqualTo(to);
    }

    @Test
    void everythingElseIsRejected() {
        Set<String> allowed = Set.of("NEW ASSIGN", "NEW CUSTOMER_REPLIED", "OPEN ASSIGN", "OPEN WAIT_ON_CUSTOMER",
                "OPEN RESOLVE", "OPEN CUSTOMER_REPLIED", "WAITING_ON_CUSTOMER CUSTOMER_REPLIED",
                "WAITING_ON_CUSTOMER RESUME", "WAITING_ON_CUSTOMER RESOLVE", "WAITING_ON_CUSTOMER ASSIGN",
                "RESOLVED REOPEN", "RESOLVED CUSTOMER_REPLIED", "RESOLVED CLOSE", "RESOLVED AUTO_CLOSE");
        for (TicketStatus status : TicketStatus.values()) {
            for (TicketAction action : TicketAction.values()) {
                boolean expected = allowed.contains(status + " " + action);
                assertThat(status.allows(action)).as(status + " " + action).isEqualTo(expected);
                if (!expected) {
                    assertThatThrownBy(() -> status.after(action)).isInstanceOf(InvalidTransitionException.class);
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(TicketAction.class)
    void aClosedTicketIsFinal(TicketAction action) {
        assertThat(TicketStatus.CLOSED.allows(action)).isFalse();
    }

    @Test
    void theSlaClockRunsOnlyWhileTheBallIsInOurCourt() {
        assertThat(TicketStatus.NEW.slaClock()).isEqualTo(ClockState.RUNNING);
        assertThat(TicketStatus.OPEN.slaClock()).isEqualTo(ClockState.RUNNING);
        assertThat(TicketStatus.WAITING_ON_CUSTOMER.slaClock()).isEqualTo(ClockState.PAUSED);
        assertThat(TicketStatus.RESOLVED.slaClock()).isEqualTo(ClockState.STOPPED);
        assertThat(TicketStatus.CLOSED.slaClock()).isEqualTo(ClockState.STOPPED);
    }

    @Test
    void onlySomeActionsAreForPeople() {
        Set<TicketAction> manual = EnumSet.noneOf(TicketAction.class);
        for (TicketAction action : TicketAction.values()) {
            if (action.isManual()) {
                manual.add(action);
            }
        }
        assertThat(manual).doesNotContain(TicketAction.CUSTOMER_REPLIED, TicketAction.AUTO_CLOSE);
    }

    @Test
    void theErrorMessageSaysWhatWasAttempted() {
        assertThatThrownBy(() -> TicketStatus.CLOSED.after(TicketAction.REOPEN))
                .hasMessage("Can't reopen a ticket that is closed.");
    }
}
