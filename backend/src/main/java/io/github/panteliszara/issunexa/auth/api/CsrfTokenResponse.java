package io.github.panteliszara.issunexa.auth.api;

public record CsrfTokenResponse(String token, String headerName) {
}
