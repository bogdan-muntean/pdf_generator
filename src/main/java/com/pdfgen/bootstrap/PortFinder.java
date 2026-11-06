package com.pdfgen.bootstrap;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

/**
 * Picks the viewer port (ADR-0021): the preferred port if it is free on the loopback address,
 * otherwise {@code 0}, which lets the OS assign any free port. No range scan: port 0 always works,
 * and the single-instance file tells the launcher which port is actually in use.
 */
final class PortFinder {

    private PortFinder() {
    }

    static int choose(InetAddress address, int preferred) {
        return preferred > 0 && isFree(address, preferred) ? preferred : 0;
    }

    static boolean isFree(InetAddress address, int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(address, port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
