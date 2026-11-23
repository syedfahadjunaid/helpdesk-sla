package com.helpdesk.common;

/** The request is valid, but the current state forbids it (e.g. replying to a closed ticket). Answered with 409. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
