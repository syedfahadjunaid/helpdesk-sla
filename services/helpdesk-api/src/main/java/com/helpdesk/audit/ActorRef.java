package com.helpdesk.audit;

import com.helpdesk.customer.Customer;
import com.helpdesk.security.Actor;

/**
 * Who did something, as recorded in the audit history: an agent, a customer (by email), or
 * the system itself (the SLA worker, auto-close).
 */
public record ActorRef(Type type, Long id, String name) {

    public enum Type { AGENT, CUSTOMER, SYSTEM }

    public static final ActorRef SYSTEM = new ActorRef(Type.SYSTEM, null, "system");

    public static ActorRef agent(Actor actor) {
        return new ActorRef(Type.AGENT, actor.agentId(), actor.username());
    }

    public static ActorRef customer(Customer customer) {
        return new ActorRef(Type.CUSTOMER, customer.getId(), customer.getEmail());
    }
}
