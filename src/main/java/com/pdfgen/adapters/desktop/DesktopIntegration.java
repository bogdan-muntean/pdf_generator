package com.pdfgen.adapters.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.AWTException;
import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.io.IOException;
import java.io.InputStream;

/**
 * Once the web server is up: publishes the instance (port), opens the browser and adds a tray icon
 * (Open viewer / Quit). On context close, which happens <em>after</em> the web server has stopped,
 * it removes the tray icon and releases the instance file and lock.
 */
@Component
public class DesktopIntegration implements ApplicationListener<WebServerInitializedEvent>, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(DesktopIntegration.class);

    private final ObjectProvider<InstanceRegistry> registry;
    private final AppShutdown shutdown;
    private final boolean openBrowser;
    private final boolean trayEnabled;
    private volatile TrayIcon trayIcon;
    private volatile String url;
    private volatile boolean published;

    public DesktopIntegration(ObjectProvider<InstanceRegistry> registry, AppShutdown shutdown,
                              @Value("${pdfgen.desktop.open-browser:false}") boolean openBrowser,
                              @Value("${pdfgen.desktop.tray:false}") boolean trayEnabled) {
        this.registry = registry;
        this.shutdown = shutdown;
        this.openBrowser = openBrowser;
        this.trayEnabled = trayEnabled;
    }

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        int port = event.getWebServer().getPort();
        url = "http://127.0.0.1:" + port + "/";
        registry.ifAvailable(instances -> {
            instances.publish(port);
            published = true;
        });
        log.info("Viewer running at {}", url);
        if (trayEnabled) {
            EventQueue.invokeLater(this::installTray);
        }
        if (openBrowser) {
            Browser.open(url);
        }
    }

    private void installTray() {
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            log.info("System tray not available; use the Quit button in the viewer to stop the application");
            return;
        }
        PopupMenu menu = new PopupMenu();
        MenuItem open = new MenuItem("Open viewer");
        open.addActionListener(e -> Browser.open(url));
        MenuItem quit = new MenuItem("Quit");
        quit.addActionListener(e -> shutdown.quit());
        menu.add(open);
        menu.addSeparator();
        menu.add(quit);

        TrayIcon icon = new TrayIcon(loadIcon(), "PDF Generator - " + url, menu);
        icon.setImageAutoSize(true);
        icon.addActionListener(e -> Browser.open(url)); // double click
        try {
            SystemTray.getSystemTray().add(icon);
            trayIcon = icon;
        } catch (AWTException e) {
            log.warn("Cannot add the tray icon", e);
        }
    }

    private static Image loadIcon() {
        try (InputStream in = DesktopIntegration.class.getResourceAsStream("/launcher/icon.png")) {
            return ImageIO.read(in);
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Tray icon missing from the classpath", e);
        }
    }

    @Override
    public void destroy() {
        TrayIcon icon = trayIcon;
        if (icon != null) {
            try {
                EventQueue.invokeAndWait(() -> SystemTray.getSystemTray().remove(icon));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.lang.reflect.InvocationTargetException e) {
                log.warn("Cannot remove the tray icon", e.getCause());
            }
        }
        // Only after a successful start: if startup failed, the launcher still owns the lock (it may retry).
        if (published) {
            registry.ifAvailable(InstanceRegistry::close);
        }
    }
}
