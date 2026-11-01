package io.github.panteliszara.issunexa.demo;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

public final class DemoHttpResponses {
    private DemoHttpResponses() { }

    public static void unavailable(HttpServletResponse response) throws IOException {
        response.resetBuffer();
        response.setStatus(429);
        response.setContentType("application/problem+json");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Retry-After", "60");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Demo unavailable\",\"status\":429,"
                + "\"detail\":\"The demo is temporarily unavailable. Try again later.\"}");
    }
}
