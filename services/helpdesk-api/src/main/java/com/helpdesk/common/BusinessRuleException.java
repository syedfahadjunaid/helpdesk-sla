package com.helpdesk.common;

/**
 * Well-formed input that breaks a business rule (e.g. assigning a ticket to someone who isn't
 * in its queue). Answered with 422 and, if {@code field} is set, an {@code errors[]} entry.
 */
public class BusinessRuleException extends RuntimeException {

    private final String field;

    public BusinessRuleException(String message) {
        this(null, message);
    }

    public BusinessRuleException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
