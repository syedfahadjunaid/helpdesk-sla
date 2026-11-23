package com.helpdesk.common;

/** The thing doesn't exist, or the caller may not know it exists. Answered with 404. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
