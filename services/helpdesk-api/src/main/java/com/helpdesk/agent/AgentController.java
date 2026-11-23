package com.helpdesk.agent;

import com.helpdesk.queue.QueueService;
import com.helpdesk.security.Actor;
import com.helpdesk.security.Role;
import java.util.List;
import java.util.Set;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Who am I, and who else works here (for "assign to" pickers). */
@RestController
@RequestMapping("/api")
public class AgentController {

    /** The signed-in user, as the web app needs it. */
    public record MeView(long id, String username, String displayName, Set<Role> roles, List<Long> queueIds) {}

    public record AgentView(long id, String username, String displayName, boolean supervisor) {

        static AgentView of(Agent agent) {
            return new AgentView(agent.getId(), agent.getUsername(), agent.getDisplayName(), agent.isSupervisor());
        }
    }

    private final AgentRepository agents;
    private final QueueService queues;

    AgentController(AgentRepository agents, QueueService queues) {
        this.agents = agents;
        this.queues = queues;
    }

    @GetMapping("/me")
    MeView me(Actor actor) {
        return new MeView(actor.agentId(), actor.username(), actor.displayName(), actor.roles(),
                queues.queueIdsOf(actor.agentId()));
    }

    @GetMapping("/agents")
    @Transactional(readOnly = true)
    List<AgentView> list() {
        return agents.findAllByOrderByDisplayName().stream().map(AgentView::of).toList();
    }
}
