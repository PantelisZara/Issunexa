package io.github.panteliszara.issunexa.demo;

import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DemoAdmissionLimiterTests {
    @Test
    void sourceAndGlobalBudgetsRecoverWithoutEvictingLiveBudgets() {
        Clock clock = mock(Clock.class);
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-01T00:00:00Z"));
        when(clock.instant()).thenAnswer(call -> now.get());
        DemoAdmissionLimiter limiter = new DemoAdmissionLimiter(clock);
        for (int i = 0; i < 5; i++) limiter.check("same-source");
        assertThatThrownBy(() -> limiter.check("same-source")).isInstanceOf(DemoUnavailableException.class);
        for (int i = 0; i < 45; i++) limiter.check("source-" + i);
        assertThatThrownBy(() -> limiter.check("new-source")).isInstanceOf(DemoUnavailableException.class);
        now.set(now.get().plusSeconds(60));
        limiter.check("same-source");
        limiter.check("new-source");
    }
}
