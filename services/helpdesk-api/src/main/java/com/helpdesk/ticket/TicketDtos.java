package com.helpdesk.ticket;

import com.helpdesk.sla.SlaService.SlaTimerView;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * Request and response bodies of the ticket API. Records: immutable, with equals/hashCode
 * and JSON support for free. Requests never contain who is asking: that comes from the token.
 */
public final class TicketDtos {

    private TicketDtos() {}

    // ---- requests ----------------------------------------------------------------------

    /** An agent logs a ticket on a customer's behalf (e.g. after a phone call). */
    public record CreateTicketRequest(
            @NotBlank @Email @Size(max = 320) String customerEmail,
            @Size(max = 200) String customerName,
            @NotBlank @Size(max = 300) String subject,
            @NotBlank @Size(max = 20_000) String description,
            @NotNull Long queueId,
            @NotNull Priority priority) {}

    public record ReplyRequest(@NotBlank @Size(max = 20_000) String body, @NotNull Visibility visibility) {}

    /** {@code assigneeId} is only read for ASSIGN; empty means "assign to me". */
    public record ActionRequest(Long assigneeId) {}

    public record PriorityRequest(@NotNull Priority priority) {}

    // ---- responses ---------------------------------------------------------------------

    public record PersonRef(long id, String name) {}

    public record CustomerRef(long id, String email, String name) {}

    public record QueueRef(long id, String key, String name) {}

    /** A ticket in a list. */
    public record TicketSummary(
            long id,
            String number,
            String subject,
            TicketStatus status,
            Priority priority,
            Channel channel,
            QueueRef queue,
            CustomerRef customer,
            PersonRef assignee,
            int escalationLevel,
            Instant createdAt,
            Instant updatedAt,
            long version,
            List<SlaTimerView> sla) {}

    public record MessageView(long id, AuthorType authorType, String authorName, Visibility visibility, String body,
            Instant createdAt, boolean viaEmail) {}

    /** One ticket with everything the detail page needs, including what this user may do. */
    public record TicketDetail(
            TicketSummary ticket,
            Instant firstRespondedAt,
            Instant resolvedAt,
            Instant closedAt,
            List<MessageView> messages,
            List<TicketAction> allowedActions,
            boolean canReplyPublicly,
            boolean canChangePriority) {}
}
