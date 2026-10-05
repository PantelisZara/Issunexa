package io.github.panteliszara.issunexa.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("issunexa.login-throttle")
public record LoginThrottleProperties(
        @DefaultValue("5") int accountLimit,
        @DefaultValue("5m") Duration accountWindow,
        @DefaultValue("30") int sourceLimit,
        @DefaultValue("1m") Duration sourceWindow,
        @DefaultValue("2048") int maxAccountEntries,
        @DefaultValue("512") int maxSourceEntries,
        @DefaultValue("") String trustedProxy) {

    public LoginThrottleProperties {
        if (accountLimit < 1 || sourceLimit < 1 || maxAccountEntries < 1 || maxSourceEntries < 1) {
            throw new IllegalArgumentException("Login throttle limits and capacities must be positive.");
        }
        validateWindow(accountWindow);
        validateWindow(sourceWindow);
    }

    private static void validateWindow(Duration window) {
        if (window == null || window.compareTo(Duration.ofSeconds(1)) < 0
                || window.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("Login throttle windows must be between one second and one hour.");
        }
    }
}
