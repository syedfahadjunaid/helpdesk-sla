package com.helpdesk.queue;

import com.helpdesk.queue.QueueService.QueueView;
import com.helpdesk.security.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/queues")
public class QueueController {

    public record MembersRequest(@NotNull @Size(max = 500) List<@NotNull Long> agentIds) {}

    private final QueueService queues;

    QueueController(QueueService queues) {
        this.queues = queues;
    }

    @GetMapping
    List<QueueView> list() {
        return queues.list();
    }

    @PutMapping("/{queueId}/members")
    @PreAuthorize("hasRole('ADMIN')")
    QueueView replaceMembers(@PathVariable long queueId, @Valid @RequestBody MembersRequest request, Actor actor) {
        return queues.replaceMembers(actor, queueId, request.agentIds());
    }
}
