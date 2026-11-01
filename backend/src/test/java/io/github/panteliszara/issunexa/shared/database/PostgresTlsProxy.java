package io.github.panteliszara.issunexa.shared.database;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/** Disposable PostgreSQL TLS terminator; the upstream deliberately receives plaintext protocol. */
final class PostgresTlsProxy implements AutoCloseable {
    private static final int SSL_REQUEST = 80877103;
    private final ServerSocket listener = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final Set<Thread> workers = ConcurrentHashMap.newKeySet();
    private final Semaphore connections = new Semaphore(8);
    private final AtomicInteger handshakes = new AtomicInteger();
    private volatile int handshakeLimit = Integer.MAX_VALUE;
    private volatile boolean closed;
    private Thread acceptor;

    PostgresTlsProxy() throws IOException {
    }

    int port() {
        return listener.getLocalPort();
    }

    int handshakes() {
        return handshakes.get();
    }

    void allowHandshakes(int limit) {
        handshakeLimit = limit;
    }

    void start(String upstreamHost, int upstreamPort, byte[] keyStoreBytes)
            throws GeneralSecurityException, IOException {
        var keyStore = KeyStore.getInstance("PKCS12");
        char[] password = "synthetic-proxy-password".toCharArray();
        keyStore.load(new ByteArrayInputStream(keyStoreBytes), password);
        var keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, password);
        var tls = SSLContext.getInstance("TLS");
        tls.init(keyManagers.getKeyManagers(), null, null);
        acceptor = Thread.ofPlatform().name("managed-tls-proxy-accept").start(() -> {
            while (!closed) {
                try {
                    Socket socket = listener.accept();
                    if (!connections.tryAcquire()) {
                        socket.close();
                        continue;
                    }
                    sockets.add(socket);
                    startWorker(() -> {
                        try {
                            serve(socket, tls, upstreamHost, upstreamPort);
                        } finally {
                            closeSocket(socket);
                            connections.release();
                        }
                    });
                } catch (IOException error) {
                    if (!closed) throw new IllegalStateException("TLS proxy accept failed", error);
                }
            }
        });
    }

    private void serve(Socket socket, SSLContext context, String upstreamHost, int upstreamPort) {
        try {
            socket.setSoTimeout(15_000);
            var input = new DataInputStream(socket.getInputStream());
            if (input.readInt() != 8 || input.readInt() != SSL_REQUEST) return;
            if (handshakes.get() >= handshakeLimit) {
                socket.getOutputStream().write('N');
                socket.getOutputStream().flush();
                return;
            }
            socket.getOutputStream().write('S');
            socket.getOutputStream().flush();
            try (var client = (SSLSocket) context.getSocketFactory()
                    .createSocket(socket, socket.getInetAddress().getHostAddress(), socket.getPort(), true);
                    var upstream = new Socket()) {
                client.setUseClientMode(false);
                client.setSoTimeout(15_000);
                client.startHandshake();
                handshakes.incrementAndGet();
                sockets.add(client);
                sockets.add(upstream);
                try {
                    upstream.connect(new InetSocketAddress(upstreamHost, upstreamPort), 5_000);
                    upstream.setSoTimeout(15_000);
                    Thread response = startWorker(() -> copy(upstream, client));
                    copy(client, upstream);
                    closeSocket(upstream);
                    response.join(5_000);
                    if (response.isAlive()) throw new IllegalStateException("TLS proxy forwarding did not stop");
                } finally {
                    sockets.remove(client);
                    sockets.remove(upstream);
                }
            }
        } catch (IOException ignored) {
            // Certificate rejection and abrupt client disconnects are expected test inputs.
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private Thread startWorker(Runnable action) {
        Thread worker = Thread.ofPlatform().name("managed-tls-proxy-forward").unstarted(() -> {
            try {
                action.run();
            } finally {
                workers.remove(Thread.currentThread());
            }
        });
        workers.add(worker);
        worker.start();
        return worker;
    }

    private void copy(Socket source, Socket target) {
        try {
            source.getInputStream().transferTo(target.getOutputStream());
        } catch (IOException ignored) {
            // Closing either endpoint ends its companion forwarding worker.
        } finally {
            closeSocket(source);
            closeSocket(target);
        }
    }

    private void closeSocket(Socket socket) {
        sockets.remove(socket);
        try {
            socket.close();
        } catch (IOException ignored) {
            // Closing an already disconnected disposable socket is harmless.
        }
    }

    @Override
    public void close() throws Exception {
        closed = true;
        listener.close();
        if (acceptor != null) acceptor.join(5_000);
        sockets.forEach(this::closeSocket);
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        for (Thread worker : Set.copyOf(workers)) {
            worker.join(Math.max(1, Duration.ofNanos(Math.max(0, deadline - System.nanoTime())).toMillis()));
        }
        if ((acceptor != null && acceptor.isAlive()) || workers.stream().anyMatch(Thread::isAlive)) {
            throw new IllegalStateException("TLS proxy workers did not stop");
        }
    }
}
