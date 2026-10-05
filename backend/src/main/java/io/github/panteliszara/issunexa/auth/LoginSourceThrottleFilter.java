package io.github.panteliszara.issunexa.auth;

import io.github.panteliszara.issunexa.shared.security.SecurityProblemHandler;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class LoginSourceThrottleFilter extends OncePerRequestFilter {

    // Use the same parsed application-path semantics as MVC/security, including matrix parameters.
    private static final RequestMatcher LOGIN = PathPatternRequestMatcher.withDefaults()
            .matcher(HttpMethod.POST, "/api/auth/login");

    private final LoginAttemptLimiter limiter;
    private final LoginSourceResolver sourceResolver;
    private final SecurityProblemHandler problemHandler;

    public LoginSourceThrottleFilter(LoginAttemptLimiter limiter, LoginSourceResolver sourceResolver,
            SecurityProblemHandler problemHandler) {
        this.limiter = limiter;
        this.sourceResolver = sourceResolver;
        this.problemHandler = problemHandler;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !LOGIN.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            limiter.checkSource(sourceResolver.resolve(request));
        } catch (LoginThrottledException exception) {
            problemHandler.loginThrottled(response, exception);
            return;
        }
        chain.doFilter(request, response);
    }
}
