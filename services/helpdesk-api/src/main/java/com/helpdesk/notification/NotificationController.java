package com.helpdesk.notification;

import com.helpdesk.notification.NotificationService.NotificationList;
import com.helpdesk.security.Actor;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in agent's own notifications. There is no way to read anyone else's. */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notifications;

    NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    NotificationList mine(Actor actor) {
        return notifications.latest(actor.agentId(), 30);
    }

    @PostMapping("/{notificationId}/read")
    Map<String, Integer> markRead(@PathVariable long notificationId, Actor actor) {
        return Map.of("updated", notifications.markRead(actor.agentId(), notificationId));
    }

    @PostMapping("/read-all")
    Map<String, Integer> markAllRead(Actor actor) {
        return Map.of("updated", notifications.markRead(actor.agentId(), null));
    }
}
