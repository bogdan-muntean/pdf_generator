package com.pdfgen.adapters.web;

import com.pdfgen.adapters.desktop.AppShutdown;
import com.pdfgen.adapters.desktop.InstanceRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Application lifecycle endpoints: identity check for the single-instance launcher, and Quit (ADR-0021). */
@RestController
@RequestMapping("/api")
public class AppController {

    /**
     * Required on {@code POST /api/shutdown}. A custom header forces a CORS preflight, which this app never
     * approves, so a web page from another origin cannot shut the application down.
     */
    public static final String ACTION_HEADER = "X-Pdfgen-Action";

    private final AppShutdown shutdown;
    private final ObjectProvider<InstanceRegistry> registry;

    public AppController(AppShutdown shutdown, ObjectProvider<InstanceRegistry> registry) {
        this.shutdown = shutdown;
        this.registry = registry;
    }

    @GetMapping("/ping")
    public Map<String, String> ping() {
        String id = registry.getIfAvailable() == null ? "none" : registry.getIfAvailable().instanceId();
        return Map.of("app", "pdf-generator", "instanceId", id);
    }

    @PostMapping("/shutdown")
    public ResponseEntity<Map<String, String>> shutdown(
            @RequestHeader(name = ACTION_HEADER, required = false) String action) {
        if (!"shutdown".equals(action)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "missing header " + ACTION_HEADER + ": shutdown"));
        }
        shutdown.quit();
        return ResponseEntity.accepted().body(Map.of("status", "shutting down"));
    }
}
