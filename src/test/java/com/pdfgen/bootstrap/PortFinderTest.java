package com.pdfgen.bootstrap;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;

class PortFinderTest {

    private final InetAddress loopback = InetAddress.getLoopbackAddress();

    @Test
    void keepsThePreferredPortWhenFree() throws IOException {
        int free;
        try (ServerSocket probe = new ServerSocket(0, 1, loopback)) {
            free = probe.getLocalPort();
        }
        assertThat(PortFinder.choose(loopback, free)).isEqualTo(free);
    }

    @Test
    void fallsBackToAnOsAssignedPortWhenTaken() throws IOException {
        try (ServerSocket busy = new ServerSocket()) {
            busy.bind(new InetSocketAddress(loopback, 0));
            assertThat(PortFinder.isFree(loopback, busy.getLocalPort())).isFalse();
            assertThat(PortFinder.choose(loopback, busy.getLocalPort())).isZero();
        }
    }
}
