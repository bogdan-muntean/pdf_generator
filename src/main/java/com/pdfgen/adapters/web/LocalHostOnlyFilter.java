package com.pdfgen.adapters.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.Set;

/**
 * DNS-rebinding guard (ADR-0021). The server only listens on 127.0.0.1, but a malicious site could point
 * its own domain at 127.0.0.1 and reach it from the browser. Such requests carry a foreign {@code Host}
 * header, so anything not addressed to {@code 127.0.0.1} / {@code localhost} is rejected.
 * <p>
 * CSRF guard (ADR-0024): a request that changes something (POST/PUT/DELETE/PATCH) is also rejected when the
 * browser says it comes from another site ({@code Origin} not on localhost, or {@code Sec-Fetch-Site: cross-site}).
 * Requests without these headers (scripts, curl, the launcher) are not browser cross-site requests and pass.
 */
@Component
public class LocalHostOnlyFilter extends OncePerRequestFilter {

    private static final Set<String> ALLOWED_HOSTS = Set.of("127.0.0.1", "localhost", "[::1]");
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!ALLOWED_HOSTS.contains(request.getServerName().toLowerCase())) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Only local requests are accepted");
            return;
        }
        if (!SAFE_METHODS.contains(request.getMethod()) && fromAnotherSite(request)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Cross-site requests are not accepted");
            return;
        }
        chain.doFilter(request, response);
    }

    static boolean fromAnotherSite(HttpServletRequest request) {
        if ("cross-site".equalsIgnoreCase(request.getHeader("Sec-Fetch-Site"))) {
            return true;
        }
        String origin = request.getHeader("Origin");
        if (origin == null) {
            return false;
        }
        try {
            URI uri = URI.create(origin.strip());
            String host = uri.getHost();
            return host == null || !ALLOWED_HOSTS.contains(host.toLowerCase())
                    || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()));
        } catch (IllegalArgumentException e) {
            return true; // "null" (sandboxed pages, file://) and garbage
        }
    }
}
