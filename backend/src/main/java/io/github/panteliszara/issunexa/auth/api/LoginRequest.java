package io.github.panteliszara.issunexa.auth.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Size(min = 1, max = 254) String email,
        @NotBlank @Schema(minLength = 1, format = "password", accessMode = Schema.AccessMode.WRITE_ONLY) String password) {

    @Override
    public String toString() {
        return "LoginRequest[credentials=REDACTED]";
    }

}
