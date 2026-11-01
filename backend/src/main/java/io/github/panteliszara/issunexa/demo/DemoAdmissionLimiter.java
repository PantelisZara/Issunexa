package io.github.panteliszara.issunexa.demo;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Component
@Profile("demo")
public class DemoAdmissionLimiter {
    private final Clock clock;
    private final Map<String, Budget> sources = new HashMap<>();
    private Instant globalUntil = Instant.MIN;
    private int globalUsed;

    public DemoAdmissionLimiter(Clock clock) {
        this.clock = clock;
    }

    public synchronized void check(String source) {
        Instant now = clock.instant();
        sources.values().removeIf(budget -> !now.isBefore(budget.until));
        if (!now.isBefore(globalUntil)) {
            globalUntil = now.plusSeconds(60);
            globalUsed = 0;
        }
        if (globalUsed >= 50) throw new DemoUnavailableException();
        Budget budget = sources.get(source);
        if (budget == null) {
            if (sources.size() >= 128) throw new DemoUnavailableException();
            budget = new Budget(now.plusSeconds(60));
            sources.put(source, budget);
        }
        if (budget.used >= 5) throw new DemoUnavailableException();
        budget.used++;
        globalUsed++;
    }

    private static final class Budget {
        private final Instant until;
        private int used;
        private Budget(Instant until) { this.until = until; }
    }
}
