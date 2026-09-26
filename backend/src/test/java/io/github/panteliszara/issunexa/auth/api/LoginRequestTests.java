package io.github.panteliszara.issunexa.auth.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoginRequestTests {

    @Test
    void redactsCredentialsFromDebugRepresentation() {
        LoginRequest request = new LoginRequest("alice@example.com", "private test password");

        assertThat(request.toString()).contains("REDACTED").doesNotContain(request.email(), request.password());
    }

}
