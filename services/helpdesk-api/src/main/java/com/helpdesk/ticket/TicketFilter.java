package com.helpdesk.ticket;

import com.helpdesk.common.BusinessRuleException;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

/**
 * Optional filters for the ticket list, turned into a JPA Criteria query. Every filter is
 * a bound parameter, never string concatenation, so search text can't inject SQL.
 *
 * @param assignee "me", "unassigned", or an agent id
 * @param text matched case-insensitively against the subject and the ticket number
 */
public record TicketFilter(Long queueId, List<TicketStatus> statuses, String assignee, Priority priority,
        Boolean escalated, String text) {

    Specification<Ticket> toSpecification(long currentAgentId) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (queueId != null) {
                where.add(cb.equal(root.get("queue").get("id"), queueId));
            }
            if (statuses != null && !statuses.isEmpty()) {
                where.add(root.get("status").in(statuses));
            }
            if (priority != null) {
                where.add(cb.equal(root.get("priority"), priority));
            }
            if (Boolean.TRUE.equals(escalated)) {
                where.add(cb.greaterThan(root.get("escalationLevel"), 0));
            }
            if (assignee != null && !assignee.isBlank()) {
                switch (assignee) {
                    case "me" -> where.add(cb.equal(root.get("assignee").get("id"), currentAgentId));
                    case "unassigned" -> where.add(cb.isNull(root.get("assignee")));
                    default -> where.add(cb.equal(root.get("assignee").get("id"), parseId(assignee)));
                }
            }
            if (text != null && !text.isBlank()) {
                String needle = text.trim().toLowerCase(Locale.ROOT);
                String number = needle.startsWith("hd-") ? needle.substring(3) : needle;
                List<Predicate> anyOf = new ArrayList<>();
                anyOf.add(cb.like(cb.lower(root.get("subject")), "%" + escapeLike(needle) + "%", '\\'));
                if (number.chars().allMatch(Character::isDigit) && !number.isEmpty() && number.length() < 18) {
                    anyOf.add(cb.equal(root.get("id"), Long.parseLong(number)));
                }
                where.add(cb.or(anyOf.toArray(Predicate[]::new)));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    private static long parseId(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new BusinessRuleException("assignee", "Use 'me', 'unassigned' or an agent id.");
        }
    }

    /** % and _ are wildcards in LIKE; a user searching for "50%" means the characters. */
    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
