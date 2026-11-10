package io.pulseboard.core.application.alert;

/** The tenant already owns the maximum number of alert rules. */
public class AlertRuleLimitExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AlertRuleLimitExceededException(int limit) {
        super("The tenant already has the maximum of " + limit + " alert rules; delete a rule first");
    }
}
