package io.github.panteliszara.issunexa.demo;

import org.springframework.security.core.AuthenticationException;

public class DemoExpiredException extends AuthenticationException {
    public DemoExpiredException() {
        super("The demo session has expired. Start a new demo.");
    }
}
