package io.github.panteliszara.issunexa.auth.api;

import io.github.panteliszara.issunexa.user.UserRole;

public record AuthenticatedSessionResponse(Long id, String email, String displayName, UserRole role) {
}
