package com.pdfgen.adapters.desktop;

import com.pdfgen.bootstrap.PdfGeneratorApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Real Tomcat on an OS-assigned port: Quit must stop the web server (port free again), then delete the
 * instance file and release the lock (ADR-0021). {@code exit-jvm=false} keeps the test JVM alive.
 */
class ShutdownIntegrationTest {

    @TempDir
    Path runDir;

    @Test
    void quitReleasesThePortAndTheInstanceLock() throws Exception {
        InstanceRegistry registry = new InstanceRegistry(runDir);
        assertThat(registry.tryLock()).isTrue();

        SpringApplication app = new SpringApplication(PdfGeneratorApplication.class);
        app.addInitializers(context -> context.getBeanFactory().registerSingleton("instanceRegistry", registry));
        ConfigurableApplicationContext context = app.run(
                "--server.port=0", "--pdfgen.data-dir=samples", "--pdfgen.system-font-dir=",
                "--pdfgen.desktop.exit-jvm=false", "--logging.level.root=WARN");
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        assertThat(port).isPositive();
        assertThat(new InstanceRegistry(runDir).readPublished())
                .hasValueSatisfying(instance -> assertThat(instance.port()).isEqualTo(port));

        assertThat(post(port, null)).as("no action header").isEqualTo(403);
        assertThat(isFree(port)).as("still running").isFalse();

        assertThat(post(port, "shutdown")).isEqualTo(202);

        await().atMost(Duration.ofSeconds(15)).until(() -> !context.isActive());
        assertThat(isFree(port)).as("port released").isTrue();
        assertThat(new InstanceRegistry(runDir).readPublished()).as("instance file deleted").isEmpty();
        try (InstanceRegistry next = new InstanceRegistry(runDir)) {
            assertThat(next.tryLock()).as("lock released").isTrue();
        }
    }

    private static int post(int port, String action) throws IOException {
        HttpURLConnection connection = (HttpURLConnection)
                URI.create("http://127.0.0.1:" + port + "/api/shutdown").toURL().openConnection();
        connection.setRequestMethod("POST");
        if (action != null) {
            connection.setRequestProperty("X-Pdfgen-Action", action);
        }
        try {
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }

    private static boolean isFree(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
