package com.helpdesk.common;

/**
 * The caller may see this ticket but not do this to it (e.g. resolving someone else's
 * ticket without being a supervisor). Answered with 403.
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
