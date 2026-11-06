package com.pdfgen.bootstrap;

import com.pdfgen.adapters.desktop.Browser;
import com.pdfgen.adapters.desktop.InstanceRegistry;
import com.pdfgen.adapters.desktop.InstanceRegistry.Instance;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.web.server.PortInUseException;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import javax.swing.JOptionPane;
import java.awt.GraphicsEnvironment;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Starts the viewer as a desktop app (ADR-0021): one instance only, a free port chosen automatically,
 * browser and tray icon on start, visible error dialog if startup fails (the shortcut runs without a console).
 */
final class ServeLauncher {

    static final Path RUN_DIR = Path.of("run");
    static final String LOG_FILE = "logs/pdf-generator.log";
    private static final Duration HANDOVER_WAIT = Duration.ofSeconds(20);

    private ServeLauncher() {
    }

    /** @return 0 if the app runs (or an existing instance was brought up); non-zero on failure. */
    static int launch(String[] args) {
        System.setProperty("java.awt.headless", "false");
        InstanceRegistry registry = new InstanceRegistry(RUN_DIR);
        if (!registry.tryLock()) {
            return openRunningInstance(registry);
        }
        try {
            try {
                start(args, registry, false);
            } catch (RuntimeException e) {
                if (!isPortInUse(e)) {
                    throw e;
                }
                // Someone grabbed the preferred port between our check and Tomcat's bind: let the OS choose.
                start(args, registry, true);
            }
            return 0;
        } catch (RuntimeException e) {
            registry.close();
            showError("The application could not start:\n" + rootMessage(e));
            return 1;
        }
    }

    private static void start(String[] args, InstanceRegistry registry, boolean forceOsPort) {
        SpringApplication app = new SpringApplication(PdfGeneratorApplication.class);
        app.setHeadless(false);
        app.setDefaultProperties(Map.of(
                "logging.file.name", LOG_FILE,
                "pdfgen.desktop.open-browser", "true",
                "pdfgen.desktop.tray", "true"));
        app.addInitializers(context -> context.getBeanFactory().registerSingleton("instanceRegistry", registry));
        app.addListeners(new PortSelector(forceOsPort));
        app.run(args);
    }

    /** Picks {@code server.port} after all configuration is loaded, right before the web server starts. */
    private record PortSelector(boolean forceOsPort) implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {
        @Override
        public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
            ConfigurableEnvironment environment = event.getEnvironment();
            int preferred = environment.getProperty("pdfgen.preferred-port", Integer.class, 8080);
            int port = forceOsPort ? 0 : PortFinder.choose(InetAddress.getLoopbackAddress(), preferred);
            environment.getPropertySources().addFirst(new MapPropertySource("pdfgen-port", Map.of("server.port", port)));
        }
    }

    /** Another process holds the lock: wait for it to publish its port, verify it, open the browser. */
    private static int openRunningInstance(InstanceRegistry registry) {
        long deadline = System.nanoTime() + HANDOVER_WAIT.toNanos();
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        while (System.nanoTime() < deadline) {
            Optional<Instance> running = registry.readPublished().filter(instance -> answers(http, instance));
            if (running.isPresent()) {
                Browser.open(running.get().url());
                return 0;
            }
            sleep(Duration.ofMillis(300));
        }
        showError("Another copy of PDF Generator seems to be running but does not respond.\n"
                + "Close it from the tray icon, or end the 'javaw' process, then try again.");
        return 1;
    }

    private static boolean answers(HttpClient http, Instance instance) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(instance.url() + "api/ping"))
                    .timeout(Duration.ofSeconds(2)).GET().build();
            String body = http.send(request, HttpResponse.BodyHandlers.ofString()).body();
            return body.contains("\"instanceId\":\"" + instance.id() + "\"");
        } catch (java.io.IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean isPortInUse(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof PortInUseException) {
                return true;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return String.valueOf(root.getMessage());
    }

    private static void showError(String message) {
        String full = message + "\n\nDetails: " + Path.of(LOG_FILE).toAbsolutePath();
        System.err.println(full);
        if (!GraphicsEnvironment.isHeadless()) {
            JOptionPane.showMessageDialog(null, full, "PDF Generator", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
