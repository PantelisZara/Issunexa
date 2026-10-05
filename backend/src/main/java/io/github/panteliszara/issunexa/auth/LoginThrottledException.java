package io.github.panteliszara.issunexa.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;

public class LoginThrottledException extends RuntimeException {

    private final long retryAfterSeconds;

    public LoginThrottledException(long retryAfterSeconds) {
        super("Too many sign-in attempts. Please try again later.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public ProblemDetail problemDetail() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, getMessage());
        problem.setType(URI.create("about:blank"));
        problem.setTitle("Too many requests");
        problem.setInstance(URI.create("/api/auth/login"));
        return problem;
    }
}
