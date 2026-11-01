package io.github.panteliszara.issunexa.demo;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

public class DemoActivityFilter extends OncePerRequestFilter {
    private final DemoWorkspaceService workspaces;
    private final AtomicLong lastCleanup = new AtomicLong(System.nanoTime() - 60_000_000_000L);

    public DemoActivityFilter(DemoWorkspaceService workspaces) {
        this.workspaces = workspaces;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/") || path.equals("/api/health/live") || path.equals("/api/health/ready")) {
            chain.doFilter(request, response);
            return;
        }
        var session = request.getSession(false);
        if (session != null && session.getAttribute(DemoWorkspaceService.SESSION_WORKSPACE) instanceof Long workspace
                && !request.getRequestURI().equals("/api/demo") && !workspaces.active(workspace)) {
            session.invalidate();
            SecurityContextHolder.clearContext();
            response.setStatus(401);
            response.setContentType("application/problem+json");
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Demo expired\",\"status\":401,"
                    + "\"detail\":\"The demo session has expired. Start a new demo.\"}");
            return;
        }
        // Actual activity only; no timer, idle connection, or unconditional polling.
        if (session != null && session.getAttribute(DemoWorkspaceService.SESSION_WORKSPACE) != null) {
            long now = System.nanoTime();
            long previous = lastCleanup.get();
            if (now - previous >= 60_000_000_000L && lastCleanup.compareAndSet(previous, now)) workspaces.cleanup();
        }
        chain.doFilter(request, response);
    }
}
