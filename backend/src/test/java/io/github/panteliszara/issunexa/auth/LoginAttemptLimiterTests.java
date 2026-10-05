package io.github.panteliszara.issunexa.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoginAttemptLimiterTests {

    private static final Instant START = Instant.parse("2026-10-05T10:00:00Z");
    private final Clock clock = mock(Clock.class);
    private LoginAttemptLimiter limiter;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(START);
        limiter = new LoginAttemptLimiter(clock, policy(2048, 512));
    }

    @ParameterizedTest
    @ValueSource(strings = {"alice@example.test", "missing@example.test"})
    void allowsFiveFailuresThenRejectsWithoutExtendingTheWindow(String identifier) {
        fail(identifier, 5);
        assertDelay(() -> limiter.beginAccount(identifier), 300);
        when(clock.instant()).thenReturn(START.plusMillis(299_001));
        assertDelay(() -> limiter.beginAccount(identifier), 1);
        when(clock.instant()).thenReturn(START.plusSeconds(300));
        fail(identifier, 5);
        assertDelay(() -> limiter.beginAccount(identifier), 300);
    }

    @Test
    void normalizesIdentifiersAndIsolatesAccounts() {
        fail("  ALICE@EXAMPLE.TEST  ", 5);
        assertDelay(() -> limiter.beginAccount("alice@example.test"), 300);
        try (var attempt = limiter.beginAccount("bob@example.test")) {
            attempt.succeeded();
        }
    }

    @Test
    void successfulVerificationResetsFailuresButNotSourceVolume() {
        fail("alice@example.test", 4);
        for (int i = 0; i < 30; i++) limiter.checkSource("192.0.2.1");
        try (var attempt = limiter.beginAccount("alice@example.test")) {
            attempt.succeeded();
        }
        fail("alice@example.test", 5);
        assertDelay(() -> limiter.beginAccount("alice@example.test"), 300);
        assertDelay(() -> limiter.checkSource("192.0.2.1"), 60);
    }

    @Test
    void sourceBudgetIsIsolatedAndRecoversAtExactBoundary() {
        for (int i = 0; i < 30; i++) limiter.checkSource("192.0.2.1");
        assertDelay(() -> limiter.checkSource("192.0.2.1"), 60);
        limiter.checkSource("192.0.2.2");
        when(clock.instant()).thenReturn(START.plusSeconds(59));
        assertDelay(() -> limiter.checkSource("192.0.2.1"), 1);
        when(clock.instant()).thenReturn(START.plusSeconds(60));
        limiter.checkSource("192.0.2.1");
    }

    @Test
    void successfulCompletionPreservesOtherInFlightReservations() {
        fail("alice@example.test", 3);
        var first = limiter.beginAccount("alice@example.test");
        var second = limiter.beginAccount("alice@example.test");
        assertDelay(() -> limiter.beginAccount("alice@example.test"), 300);
        first.succeeded();
        fail("alice@example.test", 4);
        assertDelay(() -> limiter.beginAccount("alice@example.test"), 300);
        second.close();
        assertDelay(() -> limiter.beginAccount("alice@example.test"), 300);
    }

    @Test
    void oldWindowCompletionCannotResetOrChargeANewWindow() {
        var old = limiter.beginAccount("alice@example.test");
        when(clock.instant()).thenReturn(START.plusSeconds(300));
        fail("alice@example.test", 5);
        old.succeeded();
        old.close();
        assertDelay(() -> limiter.beginAccount("alice@example.test"), 300);
    }

    @Test
    void closesAnAttemptOnlyOnce() {
        var attempt = limiter.beginAccount("alice@example.test");
        attempt.close();
        attempt.close();
        fail("alice@example.test", 4);
        assertDelay(() -> limiter.beginAccount("alice@example.test"), 300);
    }

    @Test
    void accountCapacityFailsClosedWithoutEvictingLiveBudgetsThenExpires() {
        limiter = new LoginAttemptLimiter(clock, policy(2, 2));
        fail("alice@example.test", 5);
        fail("bob@example.test", 1);
        for (int i = 0; i < 1000; i++) {
            String identifier = "unknown-" + i + "@example.test";
            assertDelay(() -> limiter.beginAccount(identifier), 300);
        }
        assertDelay(() -> limiter.beginAccount("alice@example.test"), 300);
        // A successful login frees a slot, while blocked accounts retain their budgets.
        try (var attempt = limiter.beginAccount("bob@example.test")) { attempt.succeeded(); }
        fail("carol@example.test", 1);
        assertDelay(() -> limiter.beginAccount("dave@example.test"), 300);
        when(clock.instant()).thenReturn(START.plusSeconds(300));
        fail("dave@example.test", 1);
    }

    @Test
    void sourceCapacityFailsClosedWithoutEvictionAndRecovers() {
        limiter = new LoginAttemptLimiter(clock, policy(2, 2));
        for (int i = 0; i < 30; i++) limiter.checkSource("192.0.2.1");
        limiter.checkSource("192.0.2.2");
        for (int i = 3; i < 1000; i++) {
            String source = "synthetic-source-" + i;
            assertDelay(() -> limiter.checkSource(source), 60);
        }
        assertDelay(() -> limiter.checkSource("192.0.2.1"), 60);
        when(clock.instant()).thenReturn(START.plusSeconds(60));
        limiter.checkSource("192.0.2.3");
    }

    @Test
    void capacityRetryUsesEarliestExpiry() {
        limiter = new LoginAttemptLimiter(clock, policy(2, 2));
        fail("alice@example.test", 1);
        limiter.checkSource("192.0.2.1");
        when(clock.instant()).thenReturn(START.plusSeconds(10));
        fail("bob@example.test", 1);
        limiter.checkSource("192.0.2.2");
        assertDelay(() -> limiter.beginAccount("carol@example.test"), 290);
        assertDelay(() -> limiter.checkSource("192.0.2.3"), 50);
    }

    @Test
    void concurrentAccountAttemptsCannotOverrunTheVerificationBudget() throws Exception {
        assertThat(concurrentAdmissions(() -> {
            try (var ignored = limiter.beginAccount("alice@example.test")) {
                return true;
            } catch (LoginThrottledException exception) { return false; }
        })).isEqualTo(5);
    }

    @Test
    void concurrentSourceRequestsCannotOverrunTheBudget() throws Exception {
        assertThat(concurrentAdmissions(() -> {
            try {
                limiter.checkSource("192.0.2.1");
                return true;
            } catch (LoginThrottledException exception) { return false; }
        })).isEqualTo(30);
    }

    @Test
    void rejectsInvalidPolicyAtStartup() {
        assertThatThrownBy(() -> policy(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginThrottleProperties(5, Duration.ZERO, 30, Duration.ofMinutes(1), 2, 2, ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginThrottleProperties(5, Duration.ofHours(2), 30, Duration.ofMinutes(1), 2, 2, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private int concurrentAdmissions(java.util.concurrent.Callable<Boolean> operation) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> tasks = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 100; i++) {
                tasks.add(executor.submit(() -> {
                    if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Start barrier timed out");
                    return operation.call();
                }));
            }
            start.countDown();
            int admitted = 0;
            for (var task : tasks) if (task.get(10, TimeUnit.SECONDS)) admitted++;
            return admitted;
        }
    }

    private void fail(String identifier, int count) {
        for (int i = 0; i < count; i++) {
            try (var ignored = limiter.beginAccount(identifier)) { /* Failed verification. */ }
        }
    }

    private static LoginThrottleProperties policy(int accounts, int sources) {
        return new LoginThrottleProperties(5, Duration.ofMinutes(5), 30, Duration.ofMinutes(1), accounts, sources, "");
    }

    private static void assertDelay(Runnable operation, long seconds) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(LoginThrottledException.class,
                error -> assertThat(error.retryAfterSeconds()).isEqualTo(seconds));
    }
}
