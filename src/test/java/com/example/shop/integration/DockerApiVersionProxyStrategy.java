package com.example.shop.integration;

import org.testcontainers.dockerclient.DockerClientProviderStrategy;
import org.testcontainers.dockerclient.InvalidConfigurationException;
import org.testcontainers.dockerclient.TransportConfig;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Custom Testcontainers strategy that proxies Docker API calls and upgrades
 * API version from v1.32 (Testcontainers default) to v1.40 (Docker Desktop 4.72+ minimum).
 *
 * Docker Desktop 4.72.0 dropped support for API versions below 1.40, but
 * Testcontainers 1.21.x still sends /v1.32/ requests, causing 400 responses.
 */
public class DockerApiVersionProxyStrategy extends DockerClientProviderStrategy {

    static final String PROXY_SOCKET_PATH = "/tmp/docker-tc-proxy.sock";
    static final String REAL_SOCKET_PATH = "/var/run/docker.sock";

    private static final AtomicBoolean proxyStarted = new AtomicBoolean(false);

    static {
        startProxy();
    }

    static void startProxy() {
        if (!proxyStarted.compareAndSet(false, true)) return;

        Path proxyPath = Paths.get(PROXY_SOCKET_PATH);
        Path realPath = Paths.get(REAL_SOCKET_PATH);

        if (!Files.exists(realPath)) return;

        try { Files.deleteIfExists(proxyPath); } catch (IOException ignored) {}

        Thread proxyThread = new Thread(() -> {
            try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
                server.bind(UnixDomainSocketAddress.of(PROXY_SOCKET_PATH));
                proxyPath.toFile().setWritable(true, false);
                proxyPath.toFile().setReadable(true, false);

                while (!Thread.currentThread().isInterrupted()) {
                    SocketChannel client = server.accept();
                    Thread handler = new Thread(() -> handleConnection(client), "docker-proxy-handler");
                    handler.setDaemon(true);
                    handler.start();
                }
            } catch (IOException e) {
                if (!Thread.currentThread().isInterrupted()) {
                    System.err.println("[DockerProxy] Server error: " + e.getMessage());
                }
            }
        }, "docker-proxy-server");
        proxyThread.setDaemon(true);
        proxyThread.start();

        // Wait briefly for the socket to appear
        for (int i = 0; i < 20; i++) {
            if (Files.exists(proxyPath)) break;
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
    }

    private static void handleConnection(SocketChannel client) {
        try (client;
             SocketChannel real = SocketChannel.open(StandardProtocolFamily.UNIX)) {

            real.connect(UnixDomainSocketAddress.of(REAL_SOCKET_PATH));

            ByteBuffer buf = ByteBuffer.allocate(65536);

            // Client → Docker: rewrite old API versions on every request line
            Thread c2r = new Thread(() -> {
                try {
                    ByteBuffer local = ByteBuffer.allocate(65536);
                    while (client.read(local) > 0) {
                        local.flip();
                        byte[] data = new byte[local.remaining()];
                        local.get(data);
                        local.clear();
                        // Always rewrite v1.10–v1.39 to v1.40 in every outgoing chunk
                        String text = new String(data);
                        String patched = text.replaceAll("/v1\\.(1[0-9]|2[0-9]|3[0-9])/", "/v1.40/");
                        real.write(ByteBuffer.wrap(patched.getBytes()));
                    }
                } catch (IOException ignored) {}
                try { real.shutdownOutput(); } catch (IOException ignored) {}
            }, "docker-proxy-c2r");
            c2r.setDaemon(true);
            c2r.start();

            // Docker → Client (pass-through)
            try {
                while (real.read(buf) > 0) {
                    buf.flip();
                    client.write(buf);
                    buf.clear();
                }
            } catch (IOException ignored) {}
            c2r.interrupt();
        } catch (IOException ignored) {}
    }

    @Override
    public String getDescription() {
        return "Docker API version proxy (" + PROXY_SOCKET_PATH + " -> v1.40)";
    }

    @Override
    public TransportConfig getTransportConfig() throws InvalidConfigurationException {
        return TransportConfig.builder()
            .dockerHost(URI.create("unix://" + PROXY_SOCKET_PATH))
            .build();
    }

    @Override
    protected boolean isApplicable() {
        return Files.exists(Paths.get(REAL_SOCKET_PATH))
            && Files.exists(Paths.get(PROXY_SOCKET_PATH));
    }

    @Override
    protected int getPriority() {
        return 100; // Higher than DockerDesktopClientProviderStrategy (79)
    }
}
