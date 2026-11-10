package io.pulseboard.core.api.error;

import static org.assertj.core.api.Assertions.assertThat;

import io.pulseboard.core.domain.metrics.AggregateOverflowException;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/** Mapping of transient database failures to retryable problems (never a generic 500 or a misleading 409). */
class ProblemDetailsExceptionHandlerTest {

    private final ProblemDetailsExceptionHandler handler = new ProblemDetailsExceptionHandler();

    @Test
    void deadlockAndLockTimeoutAreRetryable503() {
        for (ConcurrencyFailureException ex : new ConcurrencyFailureException[] {
            new PessimisticLockingFailureException("deadlock detected"),
            new CannotAcquireLockException("lock timeout")}) {
            ResponseEntity<ProblemDetail> response = handler.handleConcurrencyFailure(ex);
            assertThat(response.getStatusCode().value()).isEqualTo(503);
            assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
            assertThat(response.getBody().getType())
                    .isEqualTo(URI.create("urn:pulseboard:problem:concurrency-conflict"));
        }
    }

    @Test
    void queryTimeoutIs503AndOverflowIs422() {
        ResponseEntity<ProblemDetail> timeout = handler.handleQueryTimeout(new QueryTimeoutException("cancelled"));
        assertThat(timeout.getStatusCode().value()).isEqualTo(503);
        assertThat(timeout.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotBlank();
        assertThat(timeout.getBody().getType()).isEqualTo(URI.create("urn:pulseboard:problem:query-timeout"));

        ProblemDetail overflow = handler.handleAggregateOverflow(new AggregateOverflowException("overflow", null));
        assertThat(overflow.getStatus()).isEqualTo(422);
        assertThat(overflow.getType()).isEqualTo(URI.create("urn:pulseboard:problem:aggregate-out-of-range"));
    }
}
