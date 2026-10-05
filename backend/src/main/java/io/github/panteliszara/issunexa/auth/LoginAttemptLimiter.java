package io.github.panteliszara.issunexa.auth;

import io.github.panteliszara.issunexa.user.UserAccount;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Service
public class LoginAttemptLimiter {

    private final Clock clock;
    private final LoginThrottleProperties policy;
    private final Map<String, Budget> accounts = new HashMap<>();
    private final Map<String, Budget> sources = new HashMap<>();

    public LoginAttemptLimiter(Clock clock, LoginThrottleProperties policy) {
        this.clock = clock;
        this.policy = policy;
    }

    // Count every login POST, including malformed bodies, invalid CSRF and successful logins.
    public synchronized void checkSource(String source) {
        Instant now = clock.instant();
        Budget budget = budget(sources, source, policy.maxSourceEntries(), policy.sourceWindow(), now);
        if (budget.used >= policy.sourceLimit()) {
            throw throttled(now, budget.expiresAt);
        }
        budget.used++;
    }

    public synchronized AccountAttempt beginAccount(String identifier) {
        String account = UserAccount.normalizeEmail(identifier);
        Instant now = clock.instant();
        Budget budget = budget(accounts, account, policy.maxAccountEntries(), policy.accountWindow(), now);
        // Reserve before BCrypt, so parallel requests cannot all pass the last available slot.
        if (budget.used + budget.inFlight >= policy.accountLimit()) {
            throw throttled(now, budget.expiresAt);
        }
        budget.inFlight++;
        return new AccountAttempt(account, budget);
    }

    private Budget budget(Map<String, Budget> state, String key, int capacity, Duration window, Instant now) {
        // Lazy expiry retains at most capacity entries even while idle. Never evict a live budget.
        state.values().removeIf(value -> !now.isBefore(value.expiresAt));
        Budget existing = state.get(key);
        if (existing != null) {
            return existing;
        }
        if (state.size() >= capacity) {
            Instant earliest = state.values().stream().map(value -> value.expiresAt).min(Instant::compareTo).orElseThrow();
            throw throttled(now, earliest);
        }
        Budget created = new Budget(now.plus(window));
        state.put(key, created);
        return created;
    }

    private static LoginThrottledException throttled(Instant now, Instant until) {
        Duration remaining = Duration.between(now, until);
        long seconds = remaining.getSeconds() + (remaining.getNano() == 0 ? 0 : 1);
        return new LoginThrottledException(Math.max(1, seconds));
    }

    private static final class Budget {
        private final Instant expiresAt;
        private int used;
        private int inFlight;

        private Budget(Instant expiresAt) {
            this.expiresAt = expiresAt;
        }
    }

    public final class AccountAttempt implements AutoCloseable {
        private final String account;
        private final Budget budget;
        private boolean completed;

        private AccountAttempt(String account, Budget budget) {
            this.account = account;
            this.budget = budget;
        }

        public void succeeded() {
            complete(true);
        }

        @Override
        public void close() {
            complete(false);
        }

        private void complete(boolean success) {
            synchronized (LoginAttemptLimiter.this) {
                if (completed) return;
                completed = true;
                // A slow completion from an expired window must not modify its replacement.
                if (accounts.get(account) != budget) return;
                budget.inFlight--;
                budget.used = success ? 0 : budget.used + 1;
                if (budget.used == 0 && budget.inFlight == 0) {
                    accounts.remove(account);
                }
            }
        }
    }
}
