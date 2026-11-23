package com.helpdesk.ticket;

import com.helpdesk.audit.AuditService;
import com.helpdesk.audit.AuditService.AuditView;
import com.helpdesk.common.PageResponse;
import com.helpdesk.security.Actor;
import com.helpdesk.ticket.TicketDtos.ActionRequest;
import com.helpdesk.ticket.TicketDtos.CreateTicketRequest;
import com.helpdesk.ticket.TicketDtos.MessageView;
import com.helpdesk.ticket.TicketDtos.PriorityRequest;
import com.helpdesk.ticket.TicketDtos.ReplyRequest;
import com.helpdesk.ticket.TicketDtos.TicketDetail;
import com.helpdesk.ticket.TicketDtos.TicketSummary;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The ticket API. Thin on purpose: parse the request, call {@link TicketService}, return
 * the result. Rules live in the service; status codes in {@code GlobalExceptionHandler}.
 */
@RestController
@RequestMapping("/api")
public class TicketController {

    private final TicketService tickets;
    private final AuditService audit;

    TicketController(TicketService tickets, AuditService audit) {
        this.tickets = tickets;
        this.audit = audit;
    }

    @GetMapping("/tickets")
    PageResponse<TicketSummary> search(
            @RequestParam(required = false) Long queueId,
            @RequestParam(required = false) List<TicketStatus> status,
            @RequestParam(required = false) String assignee,
            @RequestParam(required = false) Priority priority,
            @RequestParam(required = false) Boolean escalated,
            @RequestParam(required = false) String q,
            @PageableDefault(size = 25, sort = "updatedAt", direction = Sort.Direction.DESC) Pageable pageable,
            Actor actor) {
        return tickets.search(new TicketFilter(queueId, status, assignee, priority, escalated, q), actor, pageable);
    }

    @PostMapping("/tickets")
    @ResponseStatus(HttpStatus.CREATED)
    TicketDetail create(@Valid @RequestBody CreateTicketRequest request, Actor actor) {
        return tickets.createByAgent(actor, request);
    }

    @GetMapping("/tickets/{ticketId}")
    TicketDetail get(@PathVariable long ticketId, Actor actor) {
        return tickets.detail(ticketId, actor);
    }

    @GetMapping("/tickets/{ticketId}/history")
    List<AuditView> history(@PathVariable long ticketId, Actor actor) {
        tickets.detail(ticketId, actor); // 404 for unknown tickets, rather than an empty list
        return audit.historyOf(ticketId);
    }

    @PostMapping("/tickets/{ticketId}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    MessageView reply(@PathVariable long ticketId, @Valid @RequestBody ReplyRequest request, Actor actor) {
        return tickets.addAgentMessage(actor, ticketId, request.body(), request.visibility());
    }

    /** {@code POST /api/tickets/1001/actions/RESOLVE}: one endpoint per state-machine action. */
    @PostMapping("/tickets/{ticketId}/actions/{action}")
    TicketDetail act(@PathVariable long ticketId, @PathVariable TicketAction action,
            @RequestBody(required = false) ActionRequest request, Actor actor) {
        return tickets.perform(actor, ticketId, action, request == null ? null : request.assigneeId());
    }

    @PutMapping("/tickets/{ticketId}/priority")
    TicketDetail changePriority(@PathVariable long ticketId, @Valid @RequestBody PriorityRequest request, Actor actor) {
        return tickets.changePriority(actor, ticketId, request.priority());
    }

    /** "Next ticket, please": 200 with the claimed ticket, or 204 if the queue has nothing waiting. */
    @PostMapping("/queues/{queueId}/next")
    ResponseEntity<TicketDetail> claimNext(@PathVariable long queueId, Actor actor) {
        return tickets.claimNext(actor, queueId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
