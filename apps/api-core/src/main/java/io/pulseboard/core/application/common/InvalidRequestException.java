package io.pulseboard.core.application.common;

import java.util.List;

/**
 * The request is syntactically or semantically invalid as a whole; rendered as 400 with the individual
 * violations listed.
 */
public class InvalidRequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<Violation> violations;

    public InvalidRequestException(String message, List<Violation> violations) {
        super(message);
        this.violations = List.copyOf(violations);
    }

    public InvalidRequestException(String message) {
        this(message, List.of());
    }

    public List<Violation> getViolations() {
        return violations;
    }

    /**
     * One violation.
     *
     * @param location where the problem is: a JSON pointer into the body or a query parameter name
     * @param message what is wrong
     */
    public record Violation(String location, String message) {
    }
}
