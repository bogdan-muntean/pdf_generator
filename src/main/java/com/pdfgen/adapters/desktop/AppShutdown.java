package com.pdfgen.adapters.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Quits the application in a fixed order (ADR-0021), triggered from the tray, the viewer's Quit button
 * or {@code POST /api/shutdown}:
 * <ol>
 *   <li>close the Spring context: the web server stops first (graceful, at most 5 s), and that releases the port;</li>
 *   <li>then the beans are destroyed: tray icon removed, instance file deleted, lock released ({@link DesktopIntegration});</li>
 *   <li>finally the JVM exits (needed because the AWT thread would keep it alive).</li>
 * </ol>
 * Quit must <em>always</em> end the process: any failure is logged and the JVM still exits, and a watchdog halts
 * it if a clean exit hangs. (Seen in practice: rebuilding the jar under a running app made class loading fail
 * during shutdown, which left a half-stopped process holding the port.)
 */
@Component
public class AppShutdown {

    private static final Logger log = LoggerFactory.getLogger(AppShutdown.class);
    static final Duration WATCHDOG = Duration.ofSeconds(20);

    private final ConfigurableApplicationContext context;
    private final boolean exitJvm;
    private final AtomicBoolean started = new AtomicBoolean();

    public AppShutdown(ConfigurableApplicationContext context,
                       @Value("${pdfgen.desktop.exit-jvm:true}") boolean exitJvm) {
        this.context = context;
        this.exitJvm = exitJvm;
    }

    /** @return false if a shutdown is already in progress */
    public boolean quit() {
        if (!started.compareAndSet(false, true)) {
            return false;
        }
        if (exitJvm) {
            startWatchdog();
        }
        Thread thread = new Thread(() -> {
            int code = 1;
            try {
                log.info("Shutting down: stopping the web server and releasing its port");
                code = SpringApplication.exit(context);
                log.info("Stopped");
            } catch (Throwable error) {
                log.error("Clean shutdown failed; exiting anyway", error);
            } finally {
                if (exitJvm) {
                    System.exit(code);
                }
            }
        }, "pdfgen-shutdown");
        thread.start();
        return true;
    }

    /** Last resort: if the JVM is still alive after {@link #WATCHDOG}, stop it without running hooks. */
    private static void startWatchdog() {
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(WATCHDOG);
                log.error("Shutdown did not finish within {}; halting the JVM", WATCHDOG);
                Runtime.getRuntime().halt(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "pdfgen-shutdown-watchdog");
        watchdog.setDaemon(true); // does not keep the JVM alive when the normal exit works
        watchdog.start();
    }
}
