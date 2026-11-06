package com.pdfgen.adapters.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;

/** Opens a URL in the default browser (Java Desktop API, or the Windows URL handler as fallback). */
public final class Browser {

    private static final Logger log = LoggerFactory.getLogger(Browser.class);

    private Browser() {
    }

    public static void open(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
            new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
        } catch (IOException | UnsupportedOperationException e) {
            log.warn("Cannot open the browser; open {} manually", url, e);
        }
    }
}
